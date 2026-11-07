//! Bounds-checked little-endian helpers. Every on-disk read goes through
//! these so that corrupted offsets produce errors instead of panics.

use crate::error::{Error, Result};

#[inline]
pub fn slice(b: &[u8], off: usize, len: usize) -> Result<&[u8]> {
    off.checked_add(len)
        .and_then(|end| b.get(off..end))
        .ok_or_else(|| Error::Corrupt(format!("read of {len} bytes at offset {off} out of bounds ({} available)", b.len())))
}

#[inline]
pub fn u8_at(b: &[u8], off: usize) -> Result<u8> {
    b.get(off).copied().ok_or_else(|| Error::Corrupt(format!("byte offset {off} out of bounds")))
}

#[inline]
pub fn u16_at(b: &[u8], off: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(slice(b, off, 2)?.try_into().unwrap()))
}

#[inline]
pub fn u32_at(b: &[u8], off: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(slice(b, off, 4)?.try_into().unwrap()))
}

#[inline]
pub fn u64_at(b: &[u8], off: usize) -> Result<u64> {
    Ok(u64::from_le_bytes(slice(b, off, 8)?.try_into().unwrap()))
}

#[inline]
pub fn be32_at(b: &[u8], off: usize) -> Result<u32> {
    Ok(u32::from_be_bytes(slice(b, off, 4)?.try_into().unwrap()))
}

/// Unchecked variants for buffers whose size has already been validated.
#[inline]
pub fn rd16(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes(b[off..off + 2].try_into().unwrap())
}
#[inline]
pub fn rd32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes(b[off..off + 4].try_into().unwrap())
}
#[inline]
pub fn rd64(b: &[u8], off: usize) -> u64 {
    u64::from_le_bytes(b[off..off + 8].try_into().unwrap())
}
#[inline]
pub fn wr16(b: &mut [u8], off: usize, v: u16) {
    b[off..off + 2].copy_from_slice(&v.to_le_bytes());
}
#[inline]
pub fn wr32(b: &mut [u8], off: usize, v: u32) {
    b[off..off + 4].copy_from_slice(&v.to_le_bytes());
}
#[inline]
pub fn wr64(b: &mut [u8], off: usize, v: u64) {
    b[off..off + 8].copy_from_slice(&v.to_le_bytes());
}

/// Builder for little-endian byte records.
#[derive(Default, Clone)]
pub struct Writer {
    pub buf: Vec<u8>,
}

impl Writer {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }
    pub fn u8(&mut self, v: u8) -> &mut Self {
        self.buf.push(v);
        self
    }
    pub fn u16(&mut self, v: u16) -> &mut Self {
        self.buf.extend_from_slice(&v.to_le_bytes());
        self
    }
    pub fn u32(&mut self, v: u32) -> &mut Self {
        self.buf.extend_from_slice(&v.to_le_bytes());
        self
    }
    pub fn u64(&mut self, v: u64) -> &mut Self {
        self.buf.extend_from_slice(&v.to_le_bytes());
        self
    }
    pub fn bytes(&mut self, v: &[u8]) -> &mut Self {
        self.buf.extend_from_slice(v);
        self
    }
    pub fn pad_to(&mut self, align: usize) -> &mut Self {
        while self.buf.len() % align != 0 {
            self.buf.push(0);
        }
        self
    }
    pub fn finish(self) -> Vec<u8> {
        self.buf
    }
}

/// Reads a NUL-terminated UTF-8 string, tolerating invalid UTF-8.
pub fn cstr(b: &[u8]) -> String {
    let end = b.iter().position(|&c| c == 0).unwrap_or(b.len());
    String::from_utf8_lossy(&b[..end]).into_owned()
}

/// Returns the bytes up to (not including) the first NUL.
pub fn cstr_bytes(b: &[u8]) -> &[u8] {
    let end = b.iter().position(|&c| c == 0).unwrap_or(b.len());
    &b[..end]
}
