//! Block device abstraction: image files, raw Windows disks and partition views.

use crate::error::{Error, Result};
use std::fs::{File, OpenOptions};
use std::path::Path;
use std::sync::{Arc, Mutex};

/// A random-access byte device.
pub trait Device: Send + Sync {
    fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<()>;
    fn write_at(&self, offset: u64, buf: &[u8]) -> Result<()>;
    /// Barrier: everything written so far must be durable once this returns.
    fn flush(&self) -> Result<()>;
    fn len(&self) -> u64;
    fn writable(&self) -> bool;
    /// Required I/O alignment in bytes (sector size), 1 if none.
    fn alignment(&self) -> u64 {
        1
    }
    fn describe(&self) -> String;
}

pub type DeviceRef = Arc<dyn Device>;

/// A regular file or a raw device node opened through the OS.
pub struct FileDevice {
    file: Mutex<File>,
    len: u64,
    writable: bool,
    sector: u64,
    name: String,
}

impl FileDevice {
    pub fn open(path: &Path, writable: bool) -> Result<Self> {
        let mut opts = OpenOptions::new();
        opts.read(true).write(writable);
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            // FILE_SHARE_READ | FILE_SHARE_WRITE so raw disks can be opened.
            opts.share_mode(0x1 | 0x2);
            if writable {
                // FILE_FLAG_WRITE_THROUGH
                opts.custom_flags(0x8000_0000);
            }
        }
        let file = opts.open(path)?;
        let name = path.display().to_string();
        #[allow(unused_mut)]
        let mut len = file.metadata().map(|m| m.len()).unwrap_or(0);
        #[allow(unused_mut)]
        let mut sector = 1u64;
        #[cfg(windows)]
        {
            if crate::windisk::is_device_path(&name) {
                let (l, s) = crate::windisk::query_geometry(&file)?;
                len = l;
                sector = s.max(512);
            }
        }
        #[cfg(unix)]
        {
            if len == 0 {
                use std::io::{Seek, SeekFrom};
                let mut f = &file;
                len = f.seek(SeekFrom::End(0))?;
            }
        }
        Ok(Self { file: Mutex::new(file), len, writable, sector, name })
    }

    fn raw_read(&self, offset: u64, buf: &mut [u8]) -> Result<()> {
        let f = self.file.lock().unwrap();
        let mut done = 0usize;
        while done < buf.len() {
            let n = pread(&f, &mut buf[done..], offset + done as u64)?;
            if n == 0 {
                return Err(Error::Io(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    format!("short read at offset {}", offset + done as u64),
                )));
            }
            done += n;
        }
        Ok(())
    }

    fn raw_write(&self, offset: u64, buf: &[u8]) -> Result<()> {
        let f = self.file.lock().unwrap();
        let mut done = 0usize;
        while done < buf.len() {
            let n = pwrite(&f, &buf[done..], offset + done as u64)?;
            if n == 0 {
                return Err(Error::Io(std::io::Error::new(std::io::ErrorKind::WriteZero, "device refused write")));
            }
            done += n;
        }
        Ok(())
    }
}

#[cfg(unix)]
fn pread(f: &File, buf: &mut [u8], off: u64) -> std::io::Result<usize> {
    use std::os::unix::fs::FileExt;
    loop {
        match f.read_at(buf, off) {
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            r => return r,
        }
    }
}
#[cfg(unix)]
fn pwrite(f: &File, buf: &[u8], off: u64) -> std::io::Result<usize> {
    use std::os::unix::fs::FileExt;
    loop {
        match f.write_at(buf, off) {
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            r => return r,
        }
    }
}
#[cfg(windows)]
fn pread(f: &File, buf: &mut [u8], off: u64) -> std::io::Result<usize> {
    use std::os::windows::fs::FileExt;
    f.seek_read(buf, off)
}
#[cfg(windows)]
fn pwrite(f: &File, buf: &[u8], off: u64) -> std::io::Result<usize> {
    use std::os::windows::fs::FileExt;
    f.seek_write(buf, off)
}

impl Device for FileDevice {
    fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<()> {
        if buf.is_empty() {
            return Ok(());
        }
        if offset.checked_add(buf.len() as u64).map_or(true, |e| e > self.len) {
            return Err(Error::Corrupt(format!(
                "read beyond end of device (offset {offset}, len {}, device size {})",
                buf.len(),
                self.len
            )));
        }
        let s = self.sector;
        if s <= 1 || (offset % s == 0 && buf.len() as u64 % s == 0) {
            return self.raw_read(offset, buf);
        }
        // Unaligned access on a raw disk: bounce through an aligned buffer.
        let start = offset / s * s;
        let end = (offset + buf.len() as u64).div_ceil(s) * s;
        let mut tmp = vec![0u8; (end - start) as usize];
        self.raw_read(start, &mut tmp)?;
        let o = (offset - start) as usize;
        buf.copy_from_slice(&tmp[o..o + buf.len()]);
        Ok(())
    }

    fn write_at(&self, offset: u64, buf: &[u8]) -> Result<()> {
        if !self.writable {
            return Err(Error::ReadOnly);
        }
        if buf.is_empty() {
            return Ok(());
        }
        if offset.checked_add(buf.len() as u64).map_or(true, |e| e > self.len) {
            return Err(Error::Other(format!("write beyond end of device at offset {offset}")));
        }
        let s = self.sector;
        if s <= 1 || (offset % s == 0 && buf.len() as u64 % s == 0) {
            return self.raw_write(offset, buf);
        }
        let start = offset / s * s;
        let end = (offset + buf.len() as u64).div_ceil(s) * s;
        let mut tmp = vec![0u8; (end - start) as usize];
        self.raw_read(start, &mut tmp)?;
        let o = (offset - start) as usize;
        tmp[o..o + buf.len()].copy_from_slice(buf);
        self.raw_write(start, &tmp)
    }

    fn flush(&self) -> Result<()> {
        if self.writable {
            self.file.lock().unwrap().sync_all()?;
        }
        Ok(())
    }

    fn len(&self) -> u64 {
        self.len
    }
    fn writable(&self) -> bool {
        self.writable
    }
    fn alignment(&self) -> u64 {
        self.sector
    }
    fn describe(&self) -> String {
        self.name.clone()
    }
}

/// A window `[offset, offset+len)` into another device (e.g. a GPT partition).
pub struct SubDevice {
    inner: DeviceRef,
    offset: u64,
    len: u64,
    name: String,
}

impl SubDevice {
    pub fn new(inner: DeviceRef, offset: u64, len: u64, name: String) -> Result<Self> {
        if offset.checked_add(len).map_or(true, |e| e > inner.len()) {
            return Err(Error::Corrupt("partition extends beyond the end of the disk".into()));
        }
        Ok(Self { inner, offset, len, name })
    }
}

impl Device for SubDevice {
    fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<()> {
        if offset.checked_add(buf.len() as u64).map_or(true, |e| e > self.len) {
            return Err(Error::Corrupt(format!("read beyond end of partition (offset {offset})")));
        }
        self.inner.read_at(self.offset + offset, buf)
    }
    fn write_at(&self, offset: u64, buf: &[u8]) -> Result<()> {
        if offset.checked_add(buf.len() as u64).map_or(true, |e| e > self.len) {
            return Err(Error::Other(format!("write beyond end of partition (offset {offset})")));
        }
        self.inner.write_at(self.offset + offset, buf)
    }
    fn flush(&self) -> Result<()> {
        self.inner.flush()
    }
    fn len(&self) -> u64 {
        self.len
    }
    fn writable(&self) -> bool {
        self.inner.writable()
    }
    fn alignment(&self) -> u64 {
        self.inner.alignment()
    }
    fn describe(&self) -> String {
        self.name.clone()
    }
}

/// An in-memory device, mostly useful for tests.
pub struct MemDevice {
    data: Mutex<Vec<u8>>,
    writable: bool,
}

impl MemDevice {
    pub fn new(data: Vec<u8>, writable: bool) -> Self {
        Self { data: Mutex::new(data), writable }
    }
    pub fn snapshot(&self) -> Vec<u8> {
        self.data.lock().unwrap().clone()
    }
}

impl Device for MemDevice {
    fn read_at(&self, offset: u64, buf: &mut [u8]) -> Result<()> {
        let d = self.data.lock().unwrap();
        let o = offset as usize;
        let src = d.get(o..o + buf.len()).ok_or_else(|| Error::Corrupt("read beyond end of memory device".into()))?;
        buf.copy_from_slice(src);
        Ok(())
    }
    fn write_at(&self, offset: u64, buf: &[u8]) -> Result<()> {
        if !self.writable {
            return Err(Error::ReadOnly);
        }
        let mut d = self.data.lock().unwrap();
        let o = offset as usize;
        let dst = d.get_mut(o..o + buf.len()).ok_or_else(|| Error::Other("write beyond end of memory device".into()))?;
        dst.copy_from_slice(buf);
        Ok(())
    }
    fn flush(&self) -> Result<()> {
        Ok(())
    }
    fn len(&self) -> u64 {
        self.data.lock().unwrap().len() as u64
    }
    fn writable(&self) -> bool {
        self.writable
    }
    fn describe(&self) -> String {
        "memory".into()
    }
}
