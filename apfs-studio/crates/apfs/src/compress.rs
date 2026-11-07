//! Transparent decompression of decmpfs-compressed files
//! (zlib, LZVN, LZFSE; inline in the xattr or in the resource fork).

use crate::bytes::*;
use crate::error::{Error, Result};
use std::io::Read;

pub const DECMPFS_MAGIC: u32 = 0x636d_7066; // "fpmc"
pub const BLOCK: usize = 0x10000;

pub const CMP_UNCOMPRESSED_ATTR: u32 = 1;
pub const CMP_ZLIB_ATTR: u32 = 3;
pub const CMP_ZLIB_RSRC: u32 = 4;
pub const CMP_SPARSE: u32 = 5;
pub const CMP_LZVN_ATTR: u32 = 7;
pub const CMP_LZVN_RSRC: u32 = 8;
pub const CMP_PLAIN_ATTR: u32 = 9;
pub const CMP_PLAIN_RSRC: u32 = 10;
pub const CMP_LZFSE_ATTR: u32 = 11;
pub const CMP_LZFSE_RSRC: u32 = 12;
pub const CMP_LZBITMAP_ATTR: u32 = 13;
pub const CMP_LZBITMAP_RSRC: u32 = 14;

#[derive(Debug, Clone)]
pub struct DecmpfsHeader {
    pub algo: u32,
    pub size: u64,
    pub payload: Vec<u8>,
}

pub fn parse_header(x: &[u8]) -> Result<DecmpfsHeader> {
    if x.len() < 16 || u32_at(x, 0)? != DECMPFS_MAGIC {
        return Err(Error::Corrupt("bad decmpfs header".into()));
    }
    Ok(DecmpfsHeader { algo: u32_at(x, 4)?, size: u64_at(x, 8)?, payload: x[16..].to_vec() })
}

pub fn algo_name(a: u32) -> &'static str {
    match a {
        CMP_UNCOMPRESSED_ATTR | CMP_PLAIN_ATTR | CMP_PLAIN_RSRC => "uncompressed",
        CMP_ZLIB_ATTR | CMP_ZLIB_RSRC => "zlib",
        CMP_LZVN_ATTR | CMP_LZVN_RSRC => "LZVN",
        CMP_LZFSE_ATTR | CMP_LZFSE_RSRC => "LZFSE",
        CMP_LZBITMAP_ATTR | CMP_LZBITMAP_RSRC => "LZBITMAP",
        CMP_SPARSE => "sparse",
        _ => "unknown",
    }
}

pub fn uses_resource_fork(algo: u32) -> bool {
    matches!(algo, CMP_ZLIB_RSRC | CMP_LZVN_RSRC | CMP_PLAIN_RSRC | CMP_LZFSE_RSRC | CMP_LZBITMAP_RSRC)
}

/// Upper bound for data decoded in one piece (inline payloads, single blocks).
pub const MAX_UNIT: usize = 256 << 20;

fn zlib(src: &[u8], expect: usize) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(expect.min(1 << 20));
    flate2::read::ZlibDecoder::new(src)
        .take(expect as u64 + 1)
        .read_to_end(&mut out)
        .map_err(|e| Error::Corrupt(format!("zlib data error: {e}")))?;
    Ok(out)
}

/// Decodes LZFSE with an output bound so corrupted size fields cannot
/// trigger huge allocations.
fn lzfse(src: &[u8], expect: usize) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(expect.min(1 << 20));
    let mut dec = lzfse_rust::LzfseRingDecoder::default();
    dec.reader_bytes(src)
        .take(expect as u64 + 1)
        .read_to_end(&mut out)
        .map_err(|e| Error::Corrupt(format!("LZFSE data error: {e}")))?;
    Ok(out)
}

/// Raw LZVN payloads are decoded by wrapping them in an LZFSE "bvxn" block.
fn lzvn(src: &[u8], expect: usize) -> Result<Vec<u8>> {
    let mut w = Vec::with_capacity(src.len() + 16);
    w.extend_from_slice(b"bvxn");
    w.extend_from_slice(&(expect as u32).to_le_bytes());
    w.extend_from_slice(&(src.len() as u32).to_le_bytes());
    w.extend_from_slice(src);
    w.extend_from_slice(b"bvx$");
    lzfse(&w, expect).map_err(|e| match e {
        Error::Corrupt(m) => Error::Corrupt(m.replace("LZFSE", "LZVN")),
        e => e,
    })
}

/// Decodes one compressed unit (an inline payload or a single fork block).
pub fn decode_unit(algo: u32, src: &[u8], expect: usize) -> Result<Vec<u8>> {
    if expect == 0 {
        return Ok(Vec::new());
    }
    if expect > MAX_UNIT {
        return Err(Error::Unsupported(format!("compressed unit of {expect} bytes")));
    }
    if src.is_empty() {
        return Err(Error::Corrupt("empty compressed block".into()));
    }
    let out = match algo {
        CMP_UNCOMPRESSED_ATTR => src.to_vec(),
        CMP_ZLIB_ATTR | CMP_ZLIB_RSRC => {
            if src[0] & 0x0F == 0x0F {
                src[1..].to_vec()
            } else {
                zlib(src, expect)?
            }
        }
        CMP_LZVN_ATTR | CMP_LZVN_RSRC => {
            if src[0] == 0x06 {
                src[1..].to_vec()
            } else {
                lzvn(src, expect)?
            }
        }
        CMP_LZFSE_ATTR | CMP_LZFSE_RSRC => {
            if src.starts_with(b"bvx") {
                lzfse(src, expect)?
            } else if src[0] == 0xFF || src[0] == 0x06 {
                src[1..].to_vec()
            } else {
                lzfse(src, expect)?
            }
        }
        CMP_PLAIN_ATTR | CMP_PLAIN_RSRC => {
            if src.len() == expect + 1 {
                src[1..].to_vec()
            } else {
                src.to_vec()
            }
        }
        CMP_LZBITMAP_ATTR | CMP_LZBITMAP_RSRC => {
            return Err(Error::Unsupported("LZBITMAP-compressed files".into()));
        }
        other => return Err(Error::Unsupported(format!("decmpfs compression type {other}"))),
    };
    if out.len() < expect {
        return Err(Error::Corrupt(format!(
            "compressed block decoded to {} bytes, expected {expect}",
            out.len()
        )));
    }
    let mut out = out;
    out.truncate(expect);
    Ok(out)
}

/// Block table of a resource-fork compressed file: (offset in fork, length).
pub fn fork_block_table(algo: u32, fork_head: &dyn Fn(u64, usize) -> Result<Vec<u8>>, fork_len: u64, size: u64) -> Result<Vec<(u64, u32)>> {
    let nblocks = size.div_ceil(BLOCK as u64) as usize;
    match algo {
        CMP_ZLIB_RSRC => {
            let h = fork_head(0, 16)?;
            let data_off = be32_at(&h, 0)? as u64;
            let t = fork_head(data_off + 4, 4)?;
            let n = u32_at(&t, 0)? as usize;
            if n > 1 << 24 || (n as u64) * 8 > fork_len {
                return Err(Error::Corrupt("compressed resource fork block count too large".into()));
            }
            let tbl = fork_head(data_off + 8, n * 8)?;
            let mut out = Vec::with_capacity(n);
            for i in 0..n {
                let off = rd32(&tbl, i * 8) as u64;
                let len = rd32(&tbl, i * 8 + 4);
                let abs = data_off + 4 + off;
                if abs + len as u64 > fork_len {
                    return Err(Error::Corrupt("compressed block lies outside the resource fork".into()));
                }
                out.push((abs, len));
            }
            if out.len() < nblocks {
                return Err(Error::Corrupt("compressed resource fork has too few blocks".into()));
            }
            Ok(out)
        }
        CMP_LZVN_RSRC | CMP_LZFSE_RSRC | CMP_PLAIN_RSRC | CMP_LZBITMAP_RSRC => {
            let first = fork_head(0, 4)?;
            let tsize = u32_at(&first, 0)? as usize;
            if tsize < 8 || tsize % 4 != 0 || tsize as u64 > fork_len || tsize > (nblocks + 1) * 4 + 4096 {
                return Err(Error::Corrupt("bad compressed block table".into()));
            }
            let t = fork_head(0, tsize)?;
            let offs: Vec<u64> = (0..tsize / 4).map(|i| rd32(&t, i * 4) as u64).collect();
            let mut out = Vec::new();
            for w in offs.windows(2) {
                if w[1] < w[0] || w[1] > fork_len {
                    return Err(Error::Corrupt("bad compressed block offsets".into()));
                }
                out.push((w[0], (w[1] - w[0]) as u32));
            }
            if out.len() < nblocks {
                return Err(Error::Corrupt("compressed resource fork has too few blocks".into()));
            }
            Ok(out)
        }
        _ => Err(Error::Unsupported(format!("decmpfs compression type {algo}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn zlib_raw_marker() {
        let v = decode_unit(CMP_ZLIB_ATTR, &[0xFF, b'h', b'i'], 2).unwrap();
        assert_eq!(v, b"hi");
    }
}
