//! Copy-on-write B-tree used by write transactions.
//!
//! Nodes are loaded lazily into an in-memory arena, modified there, and only
//! written (to freshly allocated blocks) when the transaction is flushed. The
//! original blocks are never modified, which is what makes commits crash-safe.

use crate::btree::{BTreeInfo, Node};
use crate::bytes::*;
use crate::checksum;
use crate::error::{Error, Result};
use crate::format::*;
use crate::keys::{compare, TreeKind};
use std::cmp::Ordering;

/// Where a node's bytes come from when it is not yet in memory.
pub trait NodeSource {
    /// Returns (paddr, node) for `oid`.
    fn read_node(&self, oid: u64, kind: TreeKind) -> Result<(u64, Node)>;
}

/// Allocation / output services needed by a flush.
pub trait FlushCtx {
    fn xid(&self) -> u64;
    fn block_size(&self) -> usize;
    fn alloc_block(&mut self) -> Result<u64>;
    fn free_block(&mut self, paddr: u64);
    fn new_virtual_oid(&mut self) -> u64;
    fn write_block(&mut self, paddr: u64, data: &[u8]) -> Result<()>;
}

/// Object-map updates produced by flushing a virtual tree.
#[derive(Debug, Clone)]
pub enum OmapChange {
    Remove { oid: u64, xid: u64 },
    Insert { oid: u64, xid: u64, paddr: u64 },
}

#[derive(Debug, Clone, Copy)]
enum Child {
    Disk(u64),
    Mem(usize),
}

#[derive(Debug, Clone)]
enum Slot {
    Val(Vec<u8>),
    Child(Child),
}

#[derive(Debug, Clone, Copy)]
struct Orig {
    oid: u64,
    paddr: u64,
    xid: u64,
}

#[derive(Debug, Clone)]
struct WNode {
    level: u16,
    keys: Vec<Vec<u8>>,
    slots: Vec<Slot>,
    orig: Option<Orig>,
    dirty: bool,
}

pub struct WTree {
    pub kind: TreeKind,
    pub virtual_: bool,
    nodes: Vec<WNode>,
    root: usize,
    info: BTreeInfo,
    key_delta: i64,
    node_delta: i64,
    aligned: bool,
    /// Nodes dropped by pruning/merging, released at flush.
    dropped: Vec<Orig>,
    changed: bool,
}

impl WTree {
    /// Opens a tree whose root object id is `root_oid`.
    pub fn open(src: &dyn NodeSource, root_oid: u64, kind: TreeKind, virtual_: bool) -> Result<Self> {
        let (paddr, n) = src.read_node(root_oid, kind)?;
        if !n.is_root() {
            return Err(Error::Corrupt(format!("tree root {root_oid:#x} is not flagged as root")));
        }
        if n.is_hashed() {
            return Err(Error::Unsupported("writing hashed (sealed) trees".into()));
        }
        let info = n.info()?;
        let mut t = Self {
            kind,
            virtual_,
            nodes: Vec::new(),
            root: 0,
            info,
            key_delta: 0,
            node_delta: 0,
            aligned: info.flags & BTREE_KV_NONALIGNED == 0,
            dropped: Vec::new(),
            changed: false,
        };
        if info.node_size as usize != n.data.len() {
            return Err(Error::Unsupported("B-tree nodes larger than one block".into()));
        }
        t.root = t.push_node(&n, paddr);
        Ok(t)
    }

    pub fn is_changed(&self) -> bool {
        self.changed
    }

    pub fn key_count(&self) -> u64 {
        (self.info.key_count as i64 + self.key_delta).max(0) as u64
    }

    fn push_node(&mut self, n: &Node, paddr: u64) -> usize {
        let mut keys = Vec::with_capacity(n.len());
        let mut slots = Vec::with_capacity(n.len());
        for i in 0..n.len() {
            // Bounds were validated when the node was parsed; errors here are
            // still propagated as empty entries rather than panics.
            keys.push(n.key(i).map(|k| k.to_vec()).unwrap_or_default());
            if n.is_leaf() {
                slots.push(Slot::Val(n.val(i).map(|v| v.to_vec()).unwrap_or_default()));
            } else {
                slots.push(Slot::Child(Child::Disk(n.child_oid(i).unwrap_or(0))));
            }
        }
        self.nodes.push(WNode {
            level: n.level,
            keys,
            slots,
            orig: Some(Orig { oid: n.hdr.oid, paddr, xid: n.hdr.xid }),
            dirty: false,
        });
        self.nodes.len() - 1
    }

    fn validate_loaded(&self, id: usize) -> Result<()> {
        let n = &self.nodes[id];
        for w in n.keys.windows(2) {
            if compare(self.kind, &w[0], &w[1]) != Ordering::Less {
                return Err(Error::Corrupt("B-tree node keys are out of order".into()));
            }
        }
        for (k, s) in n.keys.iter().zip(&n.slots) {
            if k.is_empty() {
                return Err(Error::Corrupt("unreadable B-tree key".into()));
            }
            if let Slot::Child(Child::Disk(0)) = s {
                return Err(Error::Corrupt("B-tree index entry with null child".into()));
            }
        }
        Ok(())
    }

    fn load_child(&mut self, src: &dyn NodeSource, id: usize, i: usize) -> Result<usize> {
        match self.nodes[id].slots[i] {
            Slot::Child(Child::Mem(c)) => Ok(c),
            Slot::Child(Child::Disk(oid)) => {
                let (paddr, n) = src.read_node(oid, self.kind)?;
                if n.level + 1 != self.nodes[id].level {
                    return Err(Error::Corrupt(format!("B-tree level mismatch at node {oid:#x}")));
                }
                if n.is_root() {
                    return Err(Error::Corrupt(format!("non-root node {oid:#x} flagged as root")));
                }
                let c = self.push_node(&n, paddr);
                self.validate_loaded(c)?;
                self.nodes[id].slots[i] = Slot::Child(Child::Mem(c));
                Ok(c)
            }
            Slot::Val(_) => Err(Error::Corrupt("leaf value where child expected".into())),
        }
    }

    fn floor(&self, id: usize, key: &[u8]) -> Option<usize> {
        let keys = &self.nodes[id].keys;
        let p = keys.partition_point(|k| compare(self.kind, k, key) != Ordering::Greater);
        if p == 0 {
            None
        } else {
            Some(p - 1)
        }
    }

    fn lower_bound(&self, id: usize, key: &[u8]) -> usize {
        self.nodes[id].keys.partition_point(|k| compare(self.kind, k, key) == Ordering::Less)
    }

    /// Returns the path of (node, index) pairs; the last entry is the leaf
    /// with the insertion position for `key`.
    fn descend(&mut self, src: &dyn NodeSource, key: &[u8]) -> Result<Vec<(usize, usize)>> {
        let mut path = Vec::new();
        let mut id = self.root;
        for _ in 0..32 {
            if self.nodes[id].level == 0 {
                let pos = self.lower_bound(id, key);
                path.push((id, pos));
                return Ok(path);
            }
            if self.nodes[id].keys.is_empty() {
                return Err(Error::Corrupt("empty B-tree index node".into()));
            }
            let i = self.floor(id, key).unwrap_or(0);
            path.push((id, i));
            id = self.load_child(src, id, i)?;
        }
        Err(Error::Corrupt("B-tree too deep".into()))
    }

    fn fix_path(&mut self, path: &[(usize, usize)]) {
        for &(id, _) in path {
            self.nodes[id].dirty = true;
        }
        // Propagate first keys upward.
        for w in (1..path.len()).rev() {
            let child = path[w].0;
            let (parent, idx) = path[w - 1];
            if let Some(first) = self.nodes[child].keys.first().cloned() {
                self.nodes[parent].keys[idx] = first;
            }
        }
        self.changed = true;
    }

    pub fn get(&mut self, src: &dyn NodeSource, key: &[u8]) -> Result<Option<Vec<u8>>> {
        let path = self.descend(src, key)?;
        let (leaf, pos) = *path.last().unwrap();
        let n = &self.nodes[leaf];
        if pos < n.keys.len() && compare(self.kind, &n.keys[pos], key) == Ordering::Equal {
            if let Slot::Val(v) = &n.slots[pos] {
                return Ok(Some(v.clone()));
            }
        }
        Ok(None)
    }

    /// Greatest record with key <= `key`.
    pub fn get_floor(&mut self, src: &dyn NodeSource, key: &[u8]) -> Result<Option<(Vec<u8>, Vec<u8>)>> {
        let mut out = None;
        // Scan from the floor position of each level is complex; use a reverse
        // approach: find the leaf, and if needed step to the previous record.
        let path = self.descend(src, key)?;
        let (leaf, pos) = *path.last().unwrap();
        let n = &self.nodes[leaf];
        if pos < n.keys.len() && compare(self.kind, &n.keys[pos], key) == Ordering::Equal {
            if let Slot::Val(v) = &n.slots[pos] {
                return Ok(Some((n.keys[pos].clone(), v.clone())));
            }
        }
        if pos > 0 {
            if let Slot::Val(v) = &n.slots[pos - 1] {
                return Ok(Some((n.keys[pos - 1].clone(), v.clone())));
            }
        }
        // The floor lives in an earlier leaf: walk up to find a left sibling.
        for lvl in (0..path.len() - 1).rev() {
            let (pid, pidx) = path[lvl];
            if pidx == 0 {
                continue;
            }
            // Rightmost record of subtree pidx-1.
            let mut id = self.load_child(src, pid, pidx - 1)?;
            loop {
                let len = self.nodes[id].keys.len();
                if len == 0 {
                    break;
                }
                if self.nodes[id].level == 0 {
                    if let Slot::Val(v) = &self.nodes[id].slots[len - 1] {
                        out = Some((self.nodes[id].keys[len - 1].clone(), v.clone()));
                    }
                    break;
                }
                id = self.load_child(src, id, len - 1)?;
            }
            break;
        }
        Ok(out)
    }

    /// Inserts or replaces. Returns true when a record was replaced.
    pub fn insert(&mut self, src: &dyn NodeSource, key: Vec<u8>, val: Vec<u8>) -> Result<bool> {
        if key.len() > 0xffff || val.len() > 0xffff {
            return Err(Error::Other("B-tree record too large".into()));
        }
        let path = self.descend(src, &key)?;
        let (leaf, pos) = *path.last().unwrap();
        let replaced;
        {
            let kind = self.kind;
            let n = &mut self.nodes[leaf];
            if pos < n.keys.len() && compare(kind, &n.keys[pos], &key) == Ordering::Equal {
                n.slots[pos] = Slot::Val(val.clone());
                replaced = true;
            } else {
                n.keys.insert(pos, key.clone());
                n.slots.insert(pos, Slot::Val(val.clone()));
                replaced = false;
            }
        }
        if !replaced {
            self.key_delta += 1;
        }
        self.info.longest_key = self.info.longest_key.max(key.len() as u32);
        self.info.longest_val = self.info.longest_val.max(val.len() as u32);
        self.fix_path(&path);
        Ok(replaced)
    }

    /// Removes a record; returns its value if it existed.
    pub fn remove(&mut self, src: &dyn NodeSource, key: &[u8]) -> Result<Option<Vec<u8>>> {
        let path = self.descend(src, key)?;
        let (leaf, pos) = *path.last().unwrap();
        let kind = self.kind;
        let n = &mut self.nodes[leaf];
        if pos < n.keys.len() && compare(kind, &n.keys[pos], key) == Ordering::Equal {
            n.keys.remove(pos);
            let v = n.slots.remove(pos);
            self.key_delta -= 1;
            self.fix_path(&path);
            if let Slot::Val(v) = v {
                return Ok(Some(v));
            }
            return Ok(Some(Vec::new()));
        }
        Ok(None)
    }

    /// In-order scan from `start`; callback returns false to stop.
    pub fn scan(&mut self, src: &dyn NodeSource, start: &[u8], f: &mut dyn FnMut(&[u8], &[u8]) -> Result<bool>) -> Result<()> {
        let root = self.root;
        self.scan_rec(src, root, Some(start), f, 0)?;
        Ok(())
    }

    fn scan_rec(
        &mut self,
        src: &dyn NodeSource,
        id: usize,
        start: Option<&[u8]>,
        f: &mut dyn FnMut(&[u8], &[u8]) -> Result<bool>,
        depth: usize,
    ) -> Result<bool> {
        if depth > 32 {
            return Err(Error::Corrupt("B-tree too deep".into()));
        }
        if self.nodes[id].level == 0 {
            let first = start.map(|s| self.lower_bound(id, s)).unwrap_or(0);
            let n = &self.nodes[id];
            for i in first..n.keys.len() {
                if let Slot::Val(v) = &n.slots[i] {
                    if !f(&n.keys[i], v)? {
                        return Ok(false);
                    }
                }
            }
            return Ok(true);
        }
        let first = start.map(|s| self.floor(id, s).unwrap_or(0)).unwrap_or(0);
        let len = self.nodes[id].keys.len();
        for i in first..len {
            let c = self.load_child(src, id, i)?;
            if !self.scan_rec(src, c, if i == first { start } else { None }, f, depth + 1)? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// Collects all records in [start, ...) while `pred(key)` holds.
    pub fn range(&mut self, src: &dyn NodeSource, start: &[u8], pred: &dyn Fn(&[u8]) -> bool) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
        let mut out = Vec::new();
        self.scan(src, start, &mut |k, v| {
            if !pred(k) {
                return Ok(false);
            }
            out.push((k.to_vec(), v.to_vec()));
            Ok(true)
        })?;
        Ok(out)
    }

    // -----------------------------------------------------------------------
    // Flushing
    // -----------------------------------------------------------------------

    fn entry_size(&self, level: u16, k: usize, v: usize) -> usize {
        match self.kind.fixed_kv() {
            Some((kl, vl)) => 4 + kl + if level == 0 { vl } else { 8 },
            None => {
                let v = if level == 0 { v } else { 8 };
                if self.aligned {
                    8 + k.div_ceil(8) * 8 + v.div_ceil(8) * 8
                } else {
                    8 + k + v
                }
            }
        }
    }

    fn slot_len(s: &Slot) -> usize {
        match s {
            Slot::Val(v) => v.len(),
            Slot::Child(_) => 8,
        }
    }

    fn node_bytes(&self, id: usize) -> usize {
        let n = &self.nodes[id];
        n.keys.iter().zip(&n.slots).map(|(k, s)| self.entry_size(n.level, k.len(), Self::slot_len(s))).sum()
    }

    fn capacity(&self, bs: usize, root: bool) -> usize {
        bs - BTREE_NODE_HDR - if root { BTREE_INFO_SIZE } else { 0 }
    }

    /// Removes empty nodes, merges small dirty siblings and collapses the root.
    fn prune(&mut self, src: &dyn NodeSource, id: usize, bs: usize) -> Result<()> {
        if self.nodes[id].level == 0 || !self.nodes[id].dirty {
            return Ok(());
        }
        let mut i = 0;
        while i < self.nodes[id].slots.len() {
            if let Slot::Child(Child::Mem(c)) = self.nodes[id].slots[i] {
                self.prune(src, c, bs)?;
                if self.nodes[c].keys.is_empty() {
                    if let Some(o) = self.nodes[c].orig.take() {
                        self.dropped.push(o);
                    }
                    self.nodes[id].keys.remove(i);
                    self.nodes[id].slots.remove(i);
                    self.nodes[id].dirty = true;
                    continue;
                }
            }
            i += 1;
        }
        // Merge adjacent in-memory siblings when at least one is dirty and the
        // result stays comfortably below one node.
        let limit = self.capacity(bs, false) * 3 / 4;
        let mut i = 0;
        while i + 1 < self.nodes[id].slots.len() {
            let (a, b) = match (&self.nodes[id].slots[i], &self.nodes[id].slots[i + 1]) {
                (Slot::Child(Child::Mem(a)), Slot::Child(Child::Mem(b))) => (*a, *b),
                _ => {
                    i += 1;
                    continue;
                }
            };
            if !(self.nodes[a].dirty || self.nodes[b].dirty) || self.node_bytes(a) + self.node_bytes(b) > limit {
                i += 1;
                continue;
            }
            let bk = std::mem::take(&mut self.nodes[b].keys);
            let bs_ = std::mem::take(&mut self.nodes[b].slots);
            self.nodes[a].keys.extend(bk);
            self.nodes[a].slots.extend(bs_);
            self.nodes[a].dirty = true;
            if let Some(o) = self.nodes[b].orig.take() {
                self.dropped.push(o);
            }
            self.nodes[id].keys.remove(i + 1);
            self.nodes[id].slots.remove(i + 1);
        }
        Ok(())
    }

    fn collapse_root(&mut self, src: &dyn NodeSource) -> Result<()> {
        loop {
            let r = self.root;
            if self.nodes[r].level == 0 {
                return Ok(());
            }
            match self.nodes[r].keys.len() {
                0 => {
                    self.nodes[r].level = 0;
                    self.nodes[r].dirty = true;
                    return Ok(());
                }
                1 => {
                    let c = self.load_child(src, r, 0)?;
                    let child = std::mem::replace(
                        &mut self.nodes[c],
                        WNode { level: 0, keys: vec![], slots: vec![], orig: None, dirty: false },
                    );
                    if let Some(o) = child.orig {
                        self.dropped.push(o);
                    }
                    let rn = &mut self.nodes[r];
                    rn.level = child.level;
                    rn.keys = child.keys;
                    rn.slots = child.slots;
                    rn.dirty = true;
                }
                _ => return Ok(()),
            }
        }
    }

    fn release(&mut self, ctx: &mut dyn FlushCtx, o: Orig, changes: &mut Vec<OmapChange>) {
        ctx.free_block(o.paddr);
        if self.virtual_ {
            changes.push(OmapChange::Remove { oid: o.oid, xid: o.xid });
        }
    }

    fn split_entries(&self, level: u16, entries: Vec<(Vec<u8>, Vec<u8>)>, cap: usize) -> Vec<Vec<(Vec<u8>, Vec<u8>)>> {
        let sizes: Vec<usize> = entries.iter().map(|(k, v)| self.entry_size(level, k.len(), v.len())).collect();
        let total: usize = sizes.iter().sum();
        let n = total.div_ceil(cap).max(1);
        let target = total.div_ceil(n);
        let mut out = Vec::new();
        let mut cur = Vec::new();
        let mut cur_size = 0;
        for ((k, v), s) in entries.into_iter().zip(sizes) {
            if !cur.is_empty() && (cur_size + s > cap || cur_size >= target) {
                out.push(std::mem::take(&mut cur));
                cur_size = 0;
            }
            cur.push((k, v));
            cur_size += s;
        }
        if !cur.is_empty() {
            out.push(cur);
        }
        out
    }

    /// Serialises one node into a full block.
    fn serialize(&self, bs: usize, oid: u64, xid: u64, level: u16, root: bool, entries: &[(Vec<u8>, Vec<u8>)], info: Option<&BTreeInfo>) -> Result<Vec<u8>> {
        let mut b = vec![0u8; bs];
        let fixed = self.kind.fixed_kv();
        let esz = if fixed.is_some() { 4 } else { 8 };
        let n = entries.len();
        let val_end = bs - if root { BTREE_INFO_SIZE } else { 0 };
        let payload: usize = entries.iter().map(|(k, v)| self.entry_size(level, k.len(), v.len()) - esz).sum();
        let mut toc_len = n.div_ceil(8).max(1) * 8 * esz;
        if BTREE_NODE_HDR + toc_len + payload > val_end {
            toc_len = n * esz;
        }
        if BTREE_NODE_HDR + toc_len + payload > val_end {
            return Err(Error::Other("internal error: B-tree node overflow".into()));
        }
        let key_start = BTREE_NODE_HDR + toc_len;
        let mut koff = 0usize;
        let mut voff = 0usize; // distance from val_end to the start of the last value
        let mut kpad = 0usize;
        let mut vpad = 0usize;
        for (i, (k, v)) in entries.iter().enumerate() {
            let (kspace, vspace) = match fixed {
                Some(_) => (k.len(), v.len()),
                None if self.aligned => (k.len().div_ceil(8) * 8, v.len().div_ceil(8) * 8),
                None => (k.len(), v.len()),
            };
            kpad += kspace - k.len();
            vpad += vspace - v.len();
            b[key_start + koff..key_start + koff + k.len()].copy_from_slice(k);
            voff += vspace;
            let vstart = val_end - voff;
            b[vstart..vstart + v.len()].copy_from_slice(v);
            let e = BTREE_NODE_HDR + i * esz;
            if fixed.is_some() {
                wr16(&mut b, e, koff as u16);
                wr16(&mut b, e + 2, voff as u16);
            } else {
                wr16(&mut b, e, koff as u16);
                wr16(&mut b, e + 2, k.len() as u16);
                wr16(&mut b, e + 4, voff as u16);
                wr16(&mut b, e + 6, v.len() as u16);
            }
            koff += kspace;
        }
        let mut flags = 0u16;
        if root {
            flags |= BTNODE_ROOT;
        }
        if level == 0 {
            flags |= BTNODE_LEAF;
        }
        if fixed.is_some() {
            flags |= BTNODE_FIXED_KV_SIZE;
        }
        wr16(&mut b, 0x20, flags);
        wr16(&mut b, 0x22, level);
        wr32(&mut b, 0x24, n as u32);
        wr16(&mut b, 0x28, 0);
        wr16(&mut b, 0x2a, toc_len as u16);
        let free_len = (val_end - voff) - (key_start + koff);
        wr16(&mut b, 0x2c, koff as u16);
        wr16(&mut b, 0x2e, free_len as u16);
        wr16(&mut b, 0x30, BTOFF_INVALID);
        wr16(&mut b, 0x32, kpad as u16);
        wr16(&mut b, 0x34, BTOFF_INVALID);
        wr16(&mut b, 0x36, vpad as u16);
        if let Some(info) = info {
            info.write(&mut b[bs - BTREE_INFO_SIZE..]);
        }
        let storage = if self.virtual_ { OBJ_VIRTUAL } else { OBJ_PHYSICAL };
        let ty = if root { OT_BTREE } else { OT_BTREE_NODE };
        ObjHeader { cksum: 0, oid, xid, type_and_flags: ty | storage, subtype: self.kind.subtype() }.write(&mut b);
        checksum::seal_object(&mut b);
        Ok(b)
    }

    fn write_chunk(
        &mut self,
        ctx: &mut dyn FlushCtx,
        reuse: Option<Orig>,
        level: u16,
        root: bool,
        entries: &[(Vec<u8>, Vec<u8>)],
        changes: &mut Vec<OmapChange>,
    ) -> Result<u64> {
        let paddr = ctx.alloc_block()?;
        let oid = if self.virtual_ {
            match reuse {
                Some(o) => o.oid,
                None => ctx.new_virtual_oid(),
            }
        } else {
            paddr
        };
        match reuse {
            Some(o) => self.release(ctx, o, changes),
            None => self.node_delta += 1,
        }
        let info = if root {
            let mut i = self.info;
            i.key_count = self.key_count();
            i.node_count = (self.info.node_count as i64 + self.node_delta).max(1) as u64;
            Some(i)
        } else {
            None
        };
        let bs = ctx.block_size();
        let block = self.serialize(bs, oid, ctx.xid(), level, root, entries, info.as_ref())?;
        ctx.write_block(paddr, &block)?;
        if self.virtual_ {
            changes.push(OmapChange::Insert { oid, xid: ctx.xid(), paddr });
        }
        Ok(oid)
    }

    /// Writes a dirty non-root subtree; returns replacement (first key, oid) entries,
    /// or None when the node is unchanged.
    fn write_node(&mut self, ctx: &mut dyn FlushCtx, id: usize, changes: &mut Vec<OmapChange>) -> Result<Option<Vec<(Vec<u8>, u64)>>> {
        if !self.nodes[id].dirty {
            return Ok(None);
        }
        let entries = self.collect_entries(ctx, id, changes)?;
        let level = self.nodes[id].level;
        let orig = self.nodes[id].orig.take();
        if entries.is_empty() {
            if let Some(o) = orig {
                self.release(ctx, o, changes);
                self.node_delta -= 1;
            }
            return Ok(Some(Vec::new()));
        }
        let cap = self.capacity(ctx.block_size(), false);
        let chunks = self.split_entries(level, entries, cap);
        let mut out = Vec::with_capacity(chunks.len());
        for (j, ch) in chunks.iter().enumerate() {
            let oid = self.write_chunk(ctx, if j == 0 { orig } else { None }, level, false, ch, changes)?;
            out.push((ch[0].0.clone(), oid));
        }
        self.nodes[id].dirty = false;
        Ok(Some(out))
    }

    /// Flushes children of `id` and returns its final (key, value) entries.
    fn collect_entries(&mut self, ctx: &mut dyn FlushCtx, id: usize, changes: &mut Vec<OmapChange>) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
        let level = self.nodes[id].level;
        let keys = std::mem::take(&mut self.nodes[id].keys);
        let slots = std::mem::take(&mut self.nodes[id].slots);
        let mut out = Vec::with_capacity(keys.len());
        for (k, s) in keys.into_iter().zip(slots) {
            match s {
                Slot::Val(v) => out.push((k, v)),
                Slot::Child(Child::Disk(oid)) => out.push((k, oid.to_le_bytes().to_vec())),
                Slot::Child(Child::Mem(c)) => match self.write_node(ctx, c, changes)? {
                    None => {
                        let oid = self.nodes[c].orig.map(|o| o.oid).unwrap_or(0);
                        out.push((k, oid.to_le_bytes().to_vec()));
                    }
                    Some(list) => {
                        for (ck, oid) in list {
                            out.push((ck, oid.to_le_bytes().to_vec()));
                        }
                    }
                },
            }
        }
        debug_assert!(level == 0 || out.iter().all(|(_, v)| v.len() == 8));
        Ok(out)
    }

    /// Writes all modified nodes. Returns the (possibly new) root oid.
    pub fn flush(&mut self, src: &dyn NodeSource, ctx: &mut dyn FlushCtx, changes: &mut Vec<OmapChange>) -> Result<u64> {
        let root_orig = self.nodes[self.root].orig;
        if !self.changed {
            return Ok(root_orig.map(|o| o.oid).unwrap_or(0));
        }
        let bs = ctx.block_size();
        let r = self.root;
        self.prune(src, r, bs)?;
        self.collapse_root(src)?;
        for o in std::mem::take(&mut self.dropped) {
            self.release(ctx, o, changes);
            self.node_delta -= 1;
        }
        let r = self.root;
        self.nodes[r].dirty = true;
        let mut level = self.nodes[r].level;
        let mut entries = self.collect_entries(ctx, r, changes)?;
        let orig = self.nodes[r].orig.take();
        let root_cap = self.capacity(bs, true);
        loop {
            let size: usize = entries.iter().map(|(k, v)| self.entry_size(level, k.len(), v.len())).sum();
            if size <= root_cap {
                break;
            }
            // Root overflow: push its content down one level.
            let chunks = self.split_entries(level, entries, self.capacity(bs, false));
            let mut next = Vec::with_capacity(chunks.len());
            for ch in &chunks {
                let oid = self.write_chunk(ctx, None, level, false, ch, changes)?;
                next.push((ch[0].0.clone(), oid.to_le_bytes().to_vec()));
            }
            entries = next;
            level += 1;
            if level > 16 {
                return Err(Error::Other("B-tree grew too deep".into()));
            }
        }
        let oid = self.write_chunk(ctx, orig, level, true, &entries, changes)?;
        // The arena now no longer reflects disk state; the tree must not be reused.
        self.nodes.clear();
        self.changed = false;
        Ok(oid)
    }
}
