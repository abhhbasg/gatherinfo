//! File-system record values: inodes, directory entries, xattrs, extents.

use crate::bytes::*;
use crate::error::{Error, Result};
use crate::format::*;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Dstream {
    pub size: u64,
    pub alloced_size: u64,
    pub default_crypto_id: u64,
    pub total_bytes_written: u64,
    pub total_bytes_read: u64,
}

impl Dstream {
    pub const SIZE: usize = 40;
    pub fn parse(b: &[u8]) -> Result<Self> {
        Ok(Self {
            size: u64_at(b, 0)?,
            alloced_size: u64_at(b, 8)?,
            default_crypto_id: u64_at(b, 16)?,
            total_bytes_written: u64_at(b, 24)?,
            total_bytes_read: u64_at(b, 32)?,
        })
    }
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u64(self.size).u64(self.alloced_size).u64(self.default_crypto_id).u64(self.total_bytes_written).u64(self.total_bytes_read);
        w.finish()
    }
}

/// One extended field (inode or dentry).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct XField {
    pub ty: u8,
    pub flags: u8,
    pub data: Vec<u8>,
}

pub fn parse_xfields(b: &[u8]) -> Result<Vec<XField>> {
    if b.is_empty() {
        return Ok(Vec::new());
    }
    let n = u16_at(b, 0)? as usize;
    let _used = u16_at(b, 2)? as usize;
    let mut out = Vec::with_capacity(n);
    let mut val = 4 + n * 4;
    for i in 0..n {
        let h = 4 + i * 4;
        let ty = u8_at(b, h)?;
        let flags = u8_at(b, h + 1)?;
        let size = u16_at(b, h + 2)? as usize;
        let data = slice(b, val, size)?.to_vec();
        val += size.div_ceil(8) * 8;
        out.push(XField { ty, flags, data });
    }
    Ok(out)
}

pub fn encode_xfields(x: &[XField]) -> Vec<u8> {
    if x.is_empty() {
        return Vec::new();
    }
    let used: usize = x.iter().map(|f| f.data.len().div_ceil(8) * 8).sum();
    let mut w = Writer::new();
    w.u16(x.len() as u16).u16(used as u16);
    for f in x {
        w.u8(f.ty).u8(f.flags).u16(f.data.len() as u16);
    }
    for f in x {
        w.bytes(&f.data);
        let pad = f.data.len().div_ceil(8) * 8 - f.data.len();
        for _ in 0..pad {
            w.u8(0);
        }
    }
    w.finish()
}

/// An inode record (j_inode_val_t plus xfields).
#[derive(Debug, Clone, Default)]
pub struct Inode {
    pub id: u64,
    pub parent_id: u64,
    pub private_id: u64,
    pub create_time: u64,
    pub mod_time: u64,
    pub change_time: u64,
    pub access_time: u64,
    pub internal_flags: u64,
    /// nchildren for directories, nlink otherwise.
    pub nchildren_or_nlink: u32,
    pub default_protection_class: u32,
    pub write_generation_counter: u32,
    pub bsd_flags: u32,
    pub owner: u32,
    pub group: u32,
    pub mode: u16,
    pub pad1: u16,
    pub uncompressed_size: u64,
    pub xfields: Vec<XField>,
}

impl Inode {
    pub const FIXED: usize = 0x5c;

    pub fn parse(id: u64, b: &[u8]) -> Result<Self> {
        if b.len() < Self::FIXED {
            return Err(Error::Corrupt(format!("inode {id} record too short")));
        }
        Ok(Self {
            id,
            parent_id: rd64(b, 0),
            private_id: rd64(b, 8),
            create_time: rd64(b, 0x10),
            mod_time: rd64(b, 0x18),
            change_time: rd64(b, 0x20),
            access_time: rd64(b, 0x28),
            internal_flags: rd64(b, 0x30),
            nchildren_or_nlink: rd32(b, 0x38),
            default_protection_class: rd32(b, 0x3c),
            write_generation_counter: rd32(b, 0x40),
            bsd_flags: rd32(b, 0x44),
            owner: rd32(b, 0x48),
            group: rd32(b, 0x4c),
            mode: rd16(b, 0x50),
            pad1: rd16(b, 0x52),
            uncompressed_size: rd64(b, 0x54),
            xfields: parse_xfields(&b[Self::FIXED..])?,
        })
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u64(self.parent_id)
            .u64(self.private_id)
            .u64(self.create_time)
            .u64(self.mod_time)
            .u64(self.change_time)
            .u64(self.access_time)
            .u64(self.internal_flags)
            .u32(self.nchildren_or_nlink)
            .u32(self.default_protection_class)
            .u32(self.write_generation_counter)
            .u32(self.bsd_flags)
            .u32(self.owner)
            .u32(self.group)
            .u16(self.mode)
            .u16(self.pad1)
            .u64(self.uncompressed_size);
        w.bytes(&encode_xfields(&self.xfields));
        w.finish()
    }

    pub fn xfield(&self, ty: u8) -> Option<&XField> {
        self.xfields.iter().find(|x| x.ty == ty)
    }

    /// Inserts or replaces an xfield, keeping ascending type order for new ones.
    pub fn set_xfield(&mut self, ty: u8, flags: u8, data: Vec<u8>) {
        if let Some(x) = self.xfields.iter_mut().find(|x| x.ty == ty) {
            x.flags = flags;
            x.data = data;
            return;
        }
        let pos = self.xfields.iter().position(|x| x.ty > ty).unwrap_or(self.xfields.len());
        self.xfields.insert(pos, XField { ty, flags, data });
    }

    pub fn remove_xfield(&mut self, ty: u8) {
        self.xfields.retain(|x| x.ty != ty);
    }

    pub fn name(&self) -> Option<String> {
        self.xfield(INO_EXT_TYPE_NAME).map(|x| cstr(&x.data))
    }

    pub fn dstream(&self) -> Option<Dstream> {
        self.xfield(INO_EXT_TYPE_DSTREAM).and_then(|x| Dstream::parse(&x.data).ok())
    }

    pub fn sparse_bytes(&self) -> u64 {
        self.xfield(INO_EXT_TYPE_SPARSE_BYTES).and_then(|x| u64_at(&x.data, 0).ok()).unwrap_or(0)
    }

    pub fn file_type(&self) -> u16 {
        self.mode & S_IFMT
    }
    pub fn is_dir(&self) -> bool {
        self.file_type() == S_IFDIR
    }
    pub fn is_file(&self) -> bool {
        self.file_type() == S_IFREG
    }
    pub fn is_symlink(&self) -> bool {
        self.file_type() == S_IFLNK
    }
    pub fn is_compressed(&self) -> bool {
        self.bsd_flags & BSD_COMPRESSED != 0
    }

    /// Logical size as seen by users (uncompressed size for compressed files).
    pub fn size(&self) -> u64 {
        if self.is_compressed() && self.internal_flags & INODE_HAS_UNCOMPRESSED_SIZE != 0 {
            return self.uncompressed_size;
        }
        self.dstream().map(|d| d.size).unwrap_or(0)
    }

    pub fn nlink(&self) -> u32 {
        if self.is_dir() {
            1
        } else {
            self.nchildren_or_nlink
        }
    }
}

/// A directory entry (j_drec_val_t).
#[derive(Debug, Clone)]
pub struct DirEntry {
    pub parent: u64,
    pub name: String,
    pub name_bytes: Vec<u8>,
    pub file_id: u64,
    pub date_added: u64,
    pub flags: u16,
    pub xfields: Vec<XField>,
    /// Exact on-disk key, useful for deletion.
    pub key: Vec<u8>,
}

impl DirEntry {
    pub fn parse(key: &[u8], val: &[u8], hashed: bool) -> Result<Self> {
        let hdr = u64_at(key, 0)?;
        let parent = hdr & OBJ_ID_MASK;
        let name_bytes = if hashed {
            let nlh = u32_at(key, 8)?;
            let len = (nlh & DREC_LEN_MASK) as usize;
            slice(key, 12, len)?.to_vec()
        } else {
            let len = u16_at(key, 8)? as usize;
            slice(key, 10, len)?.to_vec()
        };
        let nb = cstr_bytes(&name_bytes).to_vec();
        if val.len() < 18 {
            return Err(Error::Corrupt("directory record value too short".into()));
        }
        Ok(Self {
            parent,
            name: String::from_utf8_lossy(&nb).into_owned(),
            name_bytes: nb,
            file_id: rd64(val, 0),
            date_added: rd64(val, 8),
            flags: rd16(val, 16),
            xfields: parse_xfields(&val[18..])?,
            key: key.to_vec(),
        })
    }

    pub fn to_val(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u64(self.file_id).u64(self.date_added).u16(self.flags);
        w.bytes(&encode_xfields(&self.xfields));
        w.finish()
    }

    pub fn dtype(&self) -> u16 {
        self.flags & DREC_TYPE_MASK
    }
    pub fn is_dir(&self) -> bool {
        self.dtype() == DT_DIR
    }
    pub fn sibling_id(&self) -> Option<u64> {
        self.xfields
            .iter()
            .find(|x| x.ty == DREC_EXT_TYPE_SIBLING_ID)
            .and_then(|x| u64_at(&x.data, 0).ok())
    }
}

#[derive(Debug, Clone)]
pub enum XattrData {
    Embedded(Vec<u8>),
    Stream { obj_id: u64, dstream: Dstream },
}

#[derive(Debug, Clone)]
pub struct Xattr {
    pub name: String,
    pub flags: u16,
    pub data: XattrData,
}

impl Xattr {
    pub fn parse(key: &[u8], val: &[u8]) -> Result<Self> {
        let nlen = u16_at(key, 8)? as usize;
        let name = cstr(slice(key, 10, nlen)?);
        let flags = u16_at(val, 0)?;
        let dlen = u16_at(val, 2)? as usize;
        let d = slice(val, 4, dlen)?;
        let data = if flags & XATTR_DATA_STREAM != 0 {
            XattrData::Stream { obj_id: u64_at(d, 0)?, dstream: Dstream::parse(slice(d, 8, Dstream::SIZE)?)? }
        } else {
            XattrData::Embedded(d.to_vec())
        };
        Ok(Self { name, flags, data })
    }

    pub fn len(&self) -> u64 {
        match &self.data {
            XattrData::Embedded(v) => v.len() as u64,
            XattrData::Stream { dstream, .. } => dstream.size,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn to_val(&self) -> Vec<u8> {
        let mut w = Writer::new();
        match &self.data {
            XattrData::Embedded(v) => {
                w.u16(self.flags).u16(v.len() as u16).bytes(v);
            }
            XattrData::Stream { obj_id, dstream } => {
                let mut d = obj_id.to_le_bytes().to_vec();
                d.extend_from_slice(&dstream.to_bytes());
                w.u16(self.flags).u16(d.len() as u16).bytes(&d);
            }
        }
        w.finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FileExtent {
    pub logical: u64,
    pub length: u64,
    pub flags: u8,
    /// 0 means a hole.
    pub phys: u64,
    pub crypto_id: u64,
}

impl FileExtent {
    pub fn parse(key: &[u8], val: &[u8]) -> Result<Self> {
        let logical = u64_at(key, 8)?;
        let lf = u64_at(val, 0)?;
        Ok(Self {
            logical,
            length: lf & FILE_EXTENT_LEN_MASK,
            flags: (lf >> FILE_EXTENT_FLAG_SHIFT) as u8,
            phys: u64_at(val, 8)?,
            crypto_id: u64_at(val, 16)?,
        })
    }
    pub fn parse_fext(key: &[u8], val: &[u8]) -> Result<Self> {
        let logical = u64_at(key, 8)?;
        let lf = u64_at(val, 0)?;
        Ok(Self {
            logical,
            length: lf & FILE_EXTENT_LEN_MASK,
            flags: (lf >> FILE_EXTENT_FLAG_SHIFT) as u8,
            phys: u64_at(val, 8)?,
            crypto_id: 0,
        })
    }
    pub fn to_val(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u64((self.length & FILE_EXTENT_LEN_MASK) | ((self.flags as u64) << FILE_EXTENT_FLAG_SHIFT))
            .u64(self.phys)
            .u64(self.crypto_id);
        w.finish()
    }
}

/// Physical extent reference (extentref tree value).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PhysExtent {
    pub paddr: u64,
    pub len: u64,
    pub kind: u64,
    pub owner: u64,
    pub refcnt: u32,
}

impl PhysExtent {
    pub fn parse(key: &[u8], val: &[u8]) -> Result<Self> {
        let paddr = u64_at(key, 0)? & OBJ_ID_MASK;
        let lk = u64_at(val, 0)?;
        Ok(Self {
            paddr,
            len: lk & PEXT_LEN_MASK,
            kind: lk >> PEXT_KIND_SHIFT,
            owner: u64_at(val, 8)?,
            refcnt: u32_at(val, 16)?,
        })
    }
    pub fn to_val(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u64((self.len & PEXT_LEN_MASK) | (self.kind << PEXT_KIND_SHIFT)).u64(self.owner).u32(self.refcnt);
        w.finish()
    }
}

pub fn dtype_from_mode(mode: u16) -> u16 {
    (mode & S_IFMT) >> 12
}
