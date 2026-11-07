//! Locates APFS containers on a disk or disk image (GPT, MBR or bare).

use crate::device::{Device, DeviceRef, SubDevice};
use crate::error::Result;
use std::sync::Arc;

pub const APFS_GPT_TYPE: [u8; 16] = [
    0xEF, 0x57, 0x34, 0x7C, 0x00, 0x00, 0xAA, 0x11, 0xAA, 0x11, 0x00, 0x30, 0x65, 0x43, 0xEC, 0xAC,
];

#[derive(Debug, Clone)]
pub struct ContainerLocation {
    pub offset: u64,
    pub length: u64,
    pub label: String,
    /// True when the GPT type GUID says APFS (as opposed to only magic sniffing).
    pub typed: bool,
}

fn has_nxsb_magic(dev: &dyn Device, offset: u64) -> bool {
    let mut b = [0u8; 512];
    if offset + 512 > dev.len() || dev.read_at(offset, &mut b).is_err() {
        return false;
    }
    &b[32..36] == b"NXSB"
}

fn utf16_name(b: &[u8]) -> String {
    let units: Vec<u16> = b.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).take_while(|&u| u != 0).collect();
    String::from_utf16_lossy(&units)
}

fn scan_gpt(dev: &dyn Device, sector: u64) -> Option<Vec<ContainerLocation>> {
    let mut hdr = vec![0u8; sector as usize];
    dev.read_at(sector, &mut hdr).ok()?;
    if &hdr[0..8] != b"EFI PART" {
        return None;
    }
    let entries_lba = u64::from_le_bytes(hdr[72..80].try_into().ok()?);
    let count = u32::from_le_bytes(hdr[80..84].try_into().ok()?).min(1024) as u64;
    let esize = u32::from_le_bytes(hdr[84..88].try_into().ok()?) as u64;
    if !(128..=4096).contains(&esize) {
        return None;
    }
    let total = (count * esize) as usize;
    let mut tbl = vec![0u8; total.div_ceil(sector as usize) * sector as usize];
    dev.read_at(entries_lba.checked_mul(sector)?, &mut tbl).ok()?;
    let mut out = Vec::new();
    for i in 0..count as usize {
        let e = &tbl[i * esize as usize..(i + 1) * esize as usize];
        if e[0..16].iter().all(|&b| b == 0) {
            continue;
        }
        let first = u64::from_le_bytes(e[32..40].try_into().unwrap());
        let last = u64::from_le_bytes(e[40..48].try_into().unwrap());
        if last < first {
            continue;
        }
        let offset = first * sector;
        let length = (last - first + 1) * sector;
        if offset + length > dev.len() {
            continue;
        }
        let typed = e[0..16] == APFS_GPT_TYPE;
        if typed || has_nxsb_magic(dev, offset) {
            let name = utf16_name(&e[56..128.min(e.len())]);
            out.push(ContainerLocation {
                offset,
                length,
                label: if name.is_empty() { format!("Partition {}", i + 1) } else { name },
                typed,
            });
        }
    }
    Some(out)
}

fn scan_mbr(dev: &dyn Device) -> Vec<ContainerLocation> {
    let mut b = [0u8; 512];
    if dev.read_at(0, &mut b).is_err() || b[510] != 0x55 || b[511] != 0xAA {
        return Vec::new();
    }
    let mut out = Vec::new();
    for i in 0..4 {
        let e = &b[446 + i * 16..446 + (i + 1) * 16];
        let ty = e[4];
        if ty == 0 || ty == 0xEE {
            continue;
        }
        let start = u32::from_le_bytes(e[8..12].try_into().unwrap()) as u64 * 512;
        let len = u32::from_le_bytes(e[12..16].try_into().unwrap()) as u64 * 512;
        if len > 0 && start + len <= dev.len() && has_nxsb_magic(dev, start) {
            out.push(ContainerLocation { offset: start, length: len, label: format!("MBR partition {}", i + 1), typed: false });
        }
    }
    out
}

/// Finds every APFS container on `dev`.
pub fn find_containers(dev: &dyn Device) -> Vec<ContainerLocation> {
    if has_nxsb_magic(dev, 0) {
        return vec![ContainerLocation { offset: 0, length: dev.len(), label: "APFS container".into(), typed: false }];
    }
    let mut sectors = vec![512u64, 4096];
    let a = dev.alignment();
    if a > 1 && !sectors.contains(&a) {
        sectors.insert(0, a);
    }
    for s in sectors {
        if let Some(v) = scan_gpt(dev, s) {
            if !v.is_empty() {
                return v;
            }
        }
    }
    scan_mbr(dev)
}

/// Wraps a located container as its own device.
pub fn open_location(dev: &DeviceRef, loc: &ContainerLocation) -> Result<DeviceRef> {
    if loc.offset == 0 && loc.length == dev.len() {
        return Ok(dev.clone());
    }
    Ok(Arc::new(SubDevice::new(dev.clone(), loc.offset, loc.length, format!("{} ({})", dev.describe(), loc.label))?))
}
