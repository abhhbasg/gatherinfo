//! Read-side B-tree node parsing and traversal.

use crate::bytes::*;
use crate::error::{Error, Result};
use crate::format::*;
use crate::keys::{compare, TreeKind};
use std::cmp::Ordering;
use std::sync::Arc;

/// A parsed B-tree node. All accessors are bounds-checked.
#[derive(Debug, Clone)]
pub struct Node {
    pub data: Vec<u8>,
    pub hdr: ObjHeader,
    pub paddr: u64,
    pub flags: u16,
    pub level: u16,
    pub nkeys: u32,
    toc_start: usize,
    key_start: usize,
    val_end: usize,
    fixed: Option<(usize, usize)>,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct BTreeInfo {
    pub flags: u32,
    pub node_size: u32,
    pub key_size: u32,
    pub val_size: u32,
    pub longest_key: u32,
    pub longest_val: u32,
    pub key_count: u64,
    pub node_count: u64,
}

impl BTreeInfo {
    pub fn parse(b: &[u8]) -> Result<Self> {
        Ok(Self {
            flags: u32_at(b, 0)?,
            node_size: u32_at(b, 4)?,
            key_size: u32_at(b, 8)?,
            val_size: u32_at(b, 12)?,
            longest_key: u32_at(b, 16)?,
            longest_val: u32_at(b, 20)?,
            key_count: u64_at(b, 24)?,
            node_count: u64_at(b, 32)?,
        })
    }
    pub fn write(&self, b: &mut [u8]) {
        wr32(b, 0, self.flags);
        wr32(b, 4, self.node_size);
        wr32(b, 8, self.key_size);
        wr32(b, 12, self.val_size);
        wr32(b, 16, self.longest_key);
        wr32(b, 20, self.longest_val);
        wr64(b, 24, self.key_count);
        wr64(b, 32, self.node_count);
    }
}

impl Node {
    /// Parses a node buffer whose checksum has already been verified.
    pub fn parse(data: Vec<u8>, paddr: u64, kind: TreeKind) -> Result<Self> {
        let bs = data.len();
        let hdr = ObjHeader::parse(&data)?;
        let t = hdr.obj_type();
        if t != OT_BTREE && t != OT_BTREE_NODE {
            return Err(Error::Corrupt(format!("block {paddr:#x}: expected B-tree node, found object type {t:#x}")));
        }
        let flags = u16_at(&data, 0x20)?;
        let level = u16_at(&data, 0x22)?;
        let nkeys = u32_at(&data, 0x24)?;
        let toc_off = u16_at(&data, 0x28)? as usize;
        let toc_len = u16_at(&data, 0x2a)? as usize;
        let is_root = flags & BTNODE_ROOT != 0;
        let is_leaf = flags & BTNODE_LEAF != 0;
        if (level == 0) != is_leaf {
            return Err(Error::Corrupt(format!("block {paddr:#x}: B-tree level/leaf flag mismatch")));
        }
        let toc_start = BTREE_NODE_HDR + toc_off;
        let key_start = toc_start + toc_len;
        let val_end = if is_root { bs.checked_sub(BTREE_INFO_SIZE).unwrap_or(0) } else { bs };
        if key_start > val_end {
            return Err(Error::Corrupt(format!("block {paddr:#x}: B-tree table of contents out of bounds")));
        }
        let fixed = if flags & BTNODE_FIXED_KV_SIZE != 0 {
            let f = kind.fixed_kv().ok_or_else(|| {
                Error::Corrupt(format!("block {paddr:#x}: unexpected fixed-size B-tree node"))
            })?;
            Some(f)
        } else {
            None
        };
        let entry = if fixed.is_some() { 4 } else { 8 };
        if (nkeys as usize).saturating_mul(entry) > toc_len {
            return Err(Error::Corrupt(format!("block {paddr:#x}: B-tree key count {nkeys} exceeds table of contents")));
        }
        Ok(Self { data, hdr, paddr, flags, level, nkeys, toc_start, key_start, val_end, fixed })
    }

    pub fn is_leaf(&self) -> bool {
        self.flags & BTNODE_LEAF != 0
    }
    pub fn is_root(&self) -> bool {
        self.flags & BTNODE_ROOT != 0
    }
    pub fn is_hashed(&self) -> bool {
        self.flags & BTNODE_HASHED != 0
    }
    pub fn len(&self) -> usize {
        self.nkeys as usize
    }

    pub fn info(&self) -> Result<BTreeInfo> {
        if !self.is_root() {
            return Err(Error::Corrupt("B-tree info requested from non-root node".into()));
        }
        BTreeInfo::parse(slice(&self.data, self.data.len() - BTREE_INFO_SIZE, BTREE_INFO_SIZE)?)
    }

    pub fn key(&self, i: usize) -> Result<&[u8]> {
        if i >= self.len() {
            return Err(Error::Corrupt("B-tree key index out of range".into()));
        }
        let (off, len) = match self.fixed {
            Some((kl, _)) => (u16_at(&self.data, self.toc_start + i * 4)? as usize, kl),
            None => {
                let e = self.toc_start + i * 8;
                (u16_at(&self.data, e)? as usize, u16_at(&self.data, e + 2)? as usize)
            }
        };
        let start = self.key_start + off;
        if start + len > self.val_end {
            return Err(Error::Corrupt(format!("block {:#x}: B-tree key out of bounds", self.paddr)));
        }
        Ok(&self.data[start..start + len])
    }

    pub fn val(&self, i: usize) -> Result<&[u8]> {
        if i >= self.len() {
            return Err(Error::Corrupt("B-tree value index out of range".into()));
        }
        let (voff, len) = match self.fixed {
            Some((_, vl)) => {
                let v = u16_at(&self.data, self.toc_start + i * 4 + 2)?;
                if v == BTOFF_INVALID {
                    return Ok(&[]);
                }
                (v as usize, if self.is_leaf() { vl } else { 8 })
            }
            None => {
                let e = self.toc_start + i * 8;
                let v = u16_at(&self.data, e + 4)?;
                if v == BTOFF_INVALID {
                    return Ok(&[]);
                }
                (v as usize, u16_at(&self.data, e + 6)? as usize)
            }
        };
        if voff > self.val_end || voff < len {
            return Err(Error::Corrupt(format!("block {:#x}: B-tree value out of bounds", self.paddr)));
        }
        let start = self.val_end - voff;
        if start < self.key_start || start + len > self.val_end {
            return Err(Error::Corrupt(format!("block {:#x}: B-tree value out of bounds", self.paddr)));
        }
        Ok(&self.data[start..start + len])
    }

    pub fn child_oid(&self, i: usize) -> Result<u64> {
        let v = self.val(i)?;
        if v.len() < 8 {
            return Err(Error::Corrupt(format!("block {:#x}: index value too short", self.paddr)));
        }
        Ok(rd64(v, 0))
    }

    /// Index of the last key <= `target`, or None when target precedes all keys.
    pub fn floor(&self, kind: TreeKind, target: &[u8]) -> Result<Option<usize>> {
        let (mut lo, mut hi) = (0usize, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            match compare(kind, self.key(mid)?, target) {
                Ordering::Greater => hi = mid,
                _ => lo = mid + 1,
            }
        }
        Ok(if lo == 0 { None } else { Some(lo - 1) })
    }

    /// Index of the first key >= `target`.
    pub fn lower_bound(&self, kind: TreeKind, target: &[u8]) -> Result<usize> {
        let (mut lo, mut hi) = (0usize, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            match compare(kind, self.key(mid)?, target) {
                Ordering::Less => lo = mid + 1,
                _ => hi = mid,
            }
        }
        Ok(lo)
    }
}

/// Anything that can produce B-tree nodes by object id.
pub trait NodeLoader {
    fn load(&self, oid: u64, kind: TreeKind) -> Result<Arc<Node>>;
}

const MAX_DEPTH: usize = 16;

/// Exact-match lookup.
pub fn lookup(loader: &dyn NodeLoader, root: u64, kind: TreeKind, key: &[u8]) -> Result<Option<Vec<u8>>> {
    let mut node = loader.load(root, kind)?;
    for _ in 0..MAX_DEPTH {
        if node.is_leaf() {
            let i = node.lower_bound(kind, key)?;
            if i < node.len() && compare(kind, node.key(i)?, key) == Ordering::Equal {
                return Ok(Some(node.val(i)?.to_vec()));
            }
            return Ok(None);
        }
        match node.floor(kind, key)? {
            None => return Ok(None),
            Some(i) => {
                let child = node.child_oid(i)?;
                let next = loader.load(child, kind)?;
                if next.level + 1 != node.level {
                    return Err(Error::Corrupt(format!("B-tree level mismatch at node {child:#x}")));
                }
                node = next;
            }
        }
    }
    Err(Error::Corrupt("B-tree too deep".into()))
}

/// Finds the greatest record with key <= `key`; returns (key, value).
pub fn lookup_floor(loader: &dyn NodeLoader, root: u64, kind: TreeKind, key: &[u8]) -> Result<Option<(Vec<u8>, Vec<u8>)>> {
    fn rec(loader: &dyn NodeLoader, node: &Node, kind: TreeKind, key: &[u8], depth: usize) -> Result<Option<(Vec<u8>, Vec<u8>)>> {
        if depth > MAX_DEPTH {
            return Err(Error::Corrupt("B-tree too deep".into()));
        }
        let Some(mut i) = node.floor(kind, key)? else { return Ok(None) };
        if node.is_leaf() {
            return Ok(Some((node.key(i)?.to_vec(), node.val(i)?.to_vec())));
        }
        loop {
            let child = loader.load(node.child_oid(i)?, kind)?;
            if let Some(r) = rec(loader, &child, kind, key, depth + 1)? {
                return Ok(Some(r));
            }
            // Should not happen in a well-formed tree (index key == first child key),
            // but tolerate it by stepping left.
            if i == 0 {
                return Ok(None);
            }
            i -= 1;
        }
    }
    let root_node = loader.load(root, kind)?;
    rec(loader, &root_node, kind, key, 0)
}

/// In-order scan of all records with key >= `start`. The callback returns
/// `false` to stop.
pub fn scan(
    loader: &dyn NodeLoader,
    root: u64,
    kind: TreeKind,
    start: Option<&[u8]>,
    f: &mut dyn FnMut(&[u8], &[u8]) -> Result<bool>,
) -> Result<()> {
    fn rec(
        loader: &dyn NodeLoader,
        node: &Node,
        kind: TreeKind,
        start: Option<&[u8]>,
        f: &mut dyn FnMut(&[u8], &[u8]) -> Result<bool>,
        depth: usize,
    ) -> Result<bool> {
        if depth > MAX_DEPTH {
            return Err(Error::Corrupt("B-tree too deep".into()));
        }
        if node.is_leaf() {
            let first = match start {
                Some(s) => node.lower_bound(kind, s)?,
                None => 0,
            };
            for i in first..node.len() {
                if !f(node.key(i)?, node.val(i)?)? {
                    return Ok(false);
                }
            }
            return Ok(true);
        }
        let first = match start {
            Some(s) => node.floor(kind, s)?.unwrap_or(0),
            None => 0,
        };
        for i in first..node.len() {
            let child = loader.load(node.child_oid(i)?, kind)?;
            if child.level + 1 != node.level {
                return Err(Error::Corrupt(format!("B-tree level mismatch at node {:#x}", child.paddr)));
            }
            // Only the first visited child needs the start bound.
            let s = if i == first { start } else { None };
            if !rec(loader, &child, kind, s, f, depth + 1)? {
                return Ok(false);
            }
        }
        Ok(true)
    }
    let root_node = loader.load(root, kind)?;
    rec(loader, &root_node, kind, start, f, 0)?;
    Ok(())
}
