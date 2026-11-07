//! Windows physical disk helpers (enumeration, geometry, model names).

#![cfg(windows)]

use crate::error::{Error, Result};
use std::fs::File;
use std::os::windows::io::AsRawHandle;
use windows_sys::Win32::System::Ioctl::{
    DISK_GEOMETRY_EX, GET_LENGTH_INFORMATION, IOCTL_DISK_GET_DRIVE_GEOMETRY_EX, IOCTL_DISK_GET_LENGTH_INFO,
    IOCTL_STORAGE_QUERY_PROPERTY, PropertyStandardQuery, StorageDeviceProperty, STORAGE_DEVICE_DESCRIPTOR,
    STORAGE_PROPERTY_QUERY,
};
use windows_sys::Win32::System::IO::DeviceIoControl;

pub fn is_device_path(p: &str) -> bool {
    p.starts_with(r"\\.\") || p.starts_with(r"\\?\GLOBALROOT")
}

fn ioctl(file: &File, code: u32, input: &[u8], output: &mut [u8]) -> Result<u32> {
    let mut returned: u32 = 0;
    let ok = unsafe {
        DeviceIoControl(
            file.as_raw_handle() as _,
            code,
            if input.is_empty() { std::ptr::null() } else { input.as_ptr() as _ },
            input.len() as u32,
            output.as_mut_ptr() as _,
            output.len() as u32,
            &mut returned,
            std::ptr::null_mut(),
        )
    };
    if ok == 0 {
        return Err(Error::Io(std::io::Error::last_os_error()));
    }
    Ok(returned)
}

/// Returns (length in bytes, logical sector size).
pub fn query_geometry(file: &File) -> Result<(u64, u64)> {
    let mut len_buf = [0u8; std::mem::size_of::<GET_LENGTH_INFORMATION>()];
    ioctl(file, IOCTL_DISK_GET_LENGTH_INFO, &[], &mut len_buf)?;
    let len = i64::from_le_bytes(len_buf[0..8].try_into().unwrap()) as u64;
    let mut geo = vec![0u8; std::mem::size_of::<DISK_GEOMETRY_EX>() + 256];
    let sector = match ioctl(file, IOCTL_DISK_GET_DRIVE_GEOMETRY_EX, &[], &mut geo) {
        Ok(_) => {
            let g: DISK_GEOMETRY_EX = unsafe { std::ptr::read_unaligned(geo.as_ptr() as *const DISK_GEOMETRY_EX) };
            g.Geometry.BytesPerSector as u64
        }
        Err(_) => 512,
    };
    Ok((len, sector.max(512)))
}

fn query_model(file: &File) -> Option<String> {
    let q = STORAGE_PROPERTY_QUERY {
        PropertyId: StorageDeviceProperty,
        QueryType: PropertyStandardQuery,
        AdditionalParameters: [0],
    };
    let qb = unsafe {
        std::slice::from_raw_parts(&q as *const _ as *const u8, std::mem::size_of::<STORAGE_PROPERTY_QUERY>())
    };
    let mut out = vec![0u8; 1024];
    let n = ioctl(file, IOCTL_STORAGE_QUERY_PROPERTY, qb, &mut out).ok()? as usize;
    if n < std::mem::size_of::<STORAGE_DEVICE_DESCRIPTOR>() {
        return None;
    }
    let d: STORAGE_DEVICE_DESCRIPTOR = unsafe { std::ptr::read_unaligned(out.as_ptr() as *const _) };
    let pick = |off: u32| -> String {
        let off = off as usize;
        if off == 0 || off >= n {
            return String::new();
        }
        let end = out[off..n].iter().position(|&c| c == 0).map(|p| off + p).unwrap_or(n);
        String::from_utf8_lossy(&out[off..end]).trim().to_string()
    };
    let vendor = pick(d.VendorIdOffset);
    let product = pick(d.ProductIdOffset);
    let s = format!("{vendor} {product}").trim().to_string();
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

/// A physical disk visible to Windows.
#[derive(Debug, Clone)]
pub struct PhysicalDisk {
    pub path: String,
    pub index: u32,
    pub size: u64,
    pub model: String,
}

/// Enumerates `\\.\PhysicalDriveN`. Requires administrator rights; disks that
/// cannot be opened are skipped silently.
pub fn list_physical_disks() -> Vec<PhysicalDisk> {
    let mut out = Vec::new();
    for i in 0..64u32 {
        let path = format!(r"\\.\PhysicalDrive{i}");
        let f = match std::fs::OpenOptions::new().read(true).open(&path) {
            Ok(f) => f,
            Err(_) => continue,
        };
        let size = query_geometry(&f).map(|g| g.0).unwrap_or(0);
        let model = query_model(&f).unwrap_or_else(|| format!("Disk {i}"));
        out.push(PhysicalDisk { path, index: i, size, model });
    }
    out
}
