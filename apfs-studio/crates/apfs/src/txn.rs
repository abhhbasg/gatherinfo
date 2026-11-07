//! Write transactions.
//!
//! A transaction accumulates changes in memory (copy-on-write B-trees, new
//! data blocks written to free space) and commits them by writing a new
//! checkpoint. Until the final checkpoint superblock is written and flushed
//! nothing reachable from the previous checkpoint has been modified, so a
//! crash or power loss at any point leaves the container in its previous
//! consistent state.

use crate::btree::{self, Node};
use crate::bytes::*;
use crate::checksum;
use crate::container::{Container, EphLoader, EphemeralObject};
use crate::error::{Error, Result};
use crate::format::*;
use crate::keys::{self, TreeKind};
use crate::records::*;
use crate::spaceman::Spaceman;
use crate::volume::Volume;
use crate::wbtree::{FlushCtx, NodeSource, OmapChange, WTree};
use std::collections::BTreeMap;
use std::io::Read;

pub const MODIFIED_BY: &str = "apfs-studio (0.1.0)";

// ---------------------------------------------------------------------------
// Node sources
// ---------------------------------------------------------------------------

struct PhysSrc<'a>(&'a Container);

impl NodeSource for PhysSrc<'_> {
    fn read_node(&self, oid: u64, kind: TreeKind) -> Result<(u64, Node)> {
        let b = self.0.read_block_raw(oid)?;
        if !checksum::verify_object(&b) {
            return Err(Error::Corrupt(format!("B-tree node {oid:#x}: bad checksum")));
        }
        let n = Node::parse(b, oid, kind)?;
        if n.hdr.oid != oid || n.hdr.subtype != kind.subtype() || n.hdr.storage() != OBJ_PHYSICAL {
            return Err(Error::Corrupt(format!("B-tree node {oid:#x}: header does not match")));
        }
        Ok((oid, n))
    }
}

struct VirtSrc<'a> {
    c: &'a Container,
    omap_tree: u64,
    xid: u64,
}

impl NodeSource for VirtSrc<'_> {
    fn read_node(&self, oid: u64, kind: TreeKind) -> Result<(u64, Node)> {
        let v = self
            .c
            .omap_lookup(self.omap_tree, oid, self.xid)?
            .ok_or_else(|| Error::Corrupt(format!("virtual object {oid:#x} missing from object map")))?;
        if v.flags & (OMAP_VAL_ENCRYPTED | OMAP_VAL_NOHEADER) != 0 {
            return Err(Error::Unsupported("encrypted or header-less metadata".into()));
        }
        let b = self.c.read_block_raw(v.paddr)?;
        if !checksum::verify_object(&b) {
            return Err(Error::Corrupt(format!("B-tree node {oid:#x} at {:#x}: bad checksum", v.paddr)));
        }
        let n = Node::parse(b, v.paddr, kind)?;
        if n.hdr.oid != oid || n.hdr.xid != v.xid || n.hdr.subtype != kind.subtype() || n.hdr.storage() != OBJ_VIRTUAL {
            return Err(Error::Corrupt(format!("B-tree node {oid:#x}: header does not match object map")));
        }
        Ok((v.paddr, n))
    }
}

// ---------------------------------------------------------------------------
// Flush context
// ---------------------------------------------------------------------------

#[derive(Default, Debug, Clone, Copy)]
struct VolCounters {
    alloc: i64,
    alloced: u64,
    freed: u64,
}

struct Ctx<'a> {
    c: &'a Container,
    sm: &'a mut Spaceman,
    xid: u64,
    next_oid: &'a mut u64,
    counters: Option<&'a mut VolCounters>,
}

impl FlushCtx for Ctx<'_> {
    fn xid(&self) -> u64 {
        self.xid
    }
    fn block_size(&self) -> usize {
        self.c.block_size as usize
    }
    fn alloc_block(&mut self) -> Result<u64> {
        let p = self.sm.alloc_one(self.c)?;
        if let Some(cn) = self.counters.as_deref_mut() {
            cn.alloc += 1;
            cn.alloced += 1;
        }
        Ok(p)
    }
    fn free_block(&mut self, paddr: u64) {
        self.sm.free_later(paddr, 1);
        if let Some(cn) = self.counters.as_deref_mut() {
            cn.alloc -= 1;
            cn.freed += 1;
        }
    }
    fn new_virtual_oid(&mut self) -> u64 {
        let o = *self.next_oid;
        *self.next_oid += 1;
        o
    }
    fn write_block(&mut self, paddr: u64, data: &[u8]) -> Result<()> {
        self.c.write_block(paddr, data)
    }
}

// ---------------------------------------------------------------------------
// Per-volume state
// ---------------------------------------------------------------------------

struct VolTxn {
    vol: Volume,
    sb: Vec<u8>,
    /// xid of the volume superblock's entry in the container object map.
    sb_omap_xid: u64,
    fs: WTree,
    omap: WTree,
    omap_phys: Vec<u8>,
    extref: Option<WTree>,
    counters: VolCounters,
    dirty: bool,
}

/// Metadata applied to newly created files.
#[derive(Debug, Clone, Copy)]
pub struct NewFileMeta {
    pub mode: u16,
    pub uid: u32,
    pub gid: u32,
    pub create_time: u64,
    pub mod_time: u64,
}

impl NewFileMeta {
    pub fn file() -> Self {
        let now = now_ns();
        Self { mode: 0o644, uid: 99, gid: 99, create_time: now, mod_time: now }
    }
    pub fn dir() -> Self {
        Self { mode: 0o755, ..Self::file() }
    }
}

/// Progress callback: (bytes done) -> continue?
pub type Progress<'a> = &'a mut dyn FnMut(u64) -> bool;

pub struct Txn<'a> {
    c: &'a mut Container,
    xid: u64,
    next_oid: u64,
    sm: Spaceman,
    nx_omap: WTree,
    nx_omap_phys: Vec<u8>,
    nx_omap_changes: Vec<OmapChange>,
    vols: BTreeMap<usize, VolTxn>,
    /// Ephemeral objects for the new checkpoint (oid -> data).
    ephemeral: Vec<EphemeralObject>,
}

fn validate_name(name: &str) -> Result<()> {
    if name.is_empty() || name == "." || name == ".." {
        return Err(Error::InvalidName(name.into()));
    }
    if name.len() > 255 {
        return Err(Error::InvalidName(format!("{name} (longer than 255 bytes)")));
    }
    if name.contains('/') || name.contains('\0') {
        return Err(Error::InvalidName(format!("{name} (contains '/' or NUL)")));
    }
    Ok(())
}

impl<'a> Txn<'a> {
    pub fn begin(c: &'a mut Container) -> Result<Self> {
        if !c.is_writable() {
            return Err(Error::ReadOnly);
        }
        let xid = c.next_xid();
        if xid <= c.xid() {
            return Err(Error::Corrupt("next transaction id is not newer than the checkpoint".into()));
        }
        let next_oid = rd64(&c.sb, nx::NEXT_OID);
        let mut sm = Spaceman::load(c)?;
        // Reaper activity (snapshot deletion in progress) is left to macOS.
        let reaper = rd64(&c.sb, nx::REAPER_OID);
        if let Some(r) = c.ephemeral(reaper) {
            let head = u64_at(&r.data, 0x30)?;
            let flags = u32_at(&r.data, 0x40)?;
            if head != 0 || flags & 0x2 != 0 {
                return Err(Error::Unsupported("the container reaper has pending work; mount it on a Mac first".into()));
            }
        }
        let mut ephemeral = c.ephemeral.clone();
        Self::drain_free_queues(c, &mut sm, &mut ephemeral)?;
        let (_, op) = c.read_object(c.omap_oid(), "container object map", Some(OT_OMAP))?;
        let nx_tree = rd64(&op, 0x30);
        let nx_omap = WTree::open(&PhysSrc(c), nx_tree, TreeKind::Omap, false)?;
        Ok(Self {
            c,
            xid,
            next_oid,
            sm,
            nx_omap,
            nx_omap_phys: op,
            nx_omap_changes: Vec::new(),
            vols: BTreeMap::new(),
            ephemeral,
        })
    }

    /// Releases every block held in the space manager's free queues and
    /// replaces the queue trees with empty roots. Blocks in a free queue are
    /// not referenced by the current checkpoint, so this is crash-safe.
    fn drain_free_queues(c: &Container, sm: &mut Spaceman, eph: &mut Vec<EphemeralObject>) -> Result<()> {
        let bs = c.block_size as usize;
        for q in [SFQ_IP, SFQ_MAIN, SFQ_TIER2] {
            let off = sm::FQ + q * sm::FQ_SIZE;
            let count = rd64(&sm.data, off);
            let tree = rd64(&sm.data, off + 8);
            if tree == 0 {
                if count != 0 {
                    return Err(Error::Corrupt("free queue has records but no tree".into()));
                }
                continue;
            }
            let root = c.ephemeral(tree).ok_or_else(|| Error::Corrupt("free queue tree missing".into()))?;
            let root_node = Node::parse(root.data.clone(), 0, TreeKind::FreeQueue)?;
            let info = root_node.info()?;
            let mut recs: Vec<(u64, u64)> = Vec::new();
            // Collect node oids so the non-root ones can be dropped.
            let mut node_oids = vec![tree];
            fn collect(c: &Container, oid: u64, out: &mut Vec<u64>, depth: usize) -> Result<()> {
                if depth > 16 {
                    return Err(Error::Corrupt("free queue tree too deep".into()));
                }
                let e = c.ephemeral(oid).ok_or_else(|| Error::Corrupt("free queue node missing".into()))?;
                let n = Node::parse(e.data.clone(), 0, TreeKind::FreeQueue)?;
                if !n.is_leaf() {
                    for i in 0..n.len() {
                        let ch = n.child_oid(i)?;
                        out.push(ch);
                        collect(c, ch, out, depth + 1)?;
                    }
                }
                Ok(())
            }
            collect(c, tree, &mut node_oids, 0)?;
            btree::scan(&EphLoader(c), tree, TreeKind::FreeQueue, None, &mut |k, v| {
                let paddr = rd64(k, 8);
                let len = if v.len() >= 8 { rd64(v, 0) } else { 1 };
                recs.push((paddr, len));
                Ok(true)
            })?;
            let total: u64 = recs.iter().map(|r| r.1).sum();
            if total != count {
                return Err(Error::Corrupt(format!("free queue {q} count {count} does not match its records ({total})")));
            }
            for (p, n) in recs {
                if q == SFQ_IP {
                    for b in p..p + n {
                        sm.ip_free_now(b)?;
                    }
                } else if q == SFQ_MAIN {
                    if sm.ip_contains(p) {
                        return Err(Error::Corrupt("main free queue record inside the internal pool".into()));
                    }
                    sm.free_now(c, p, n)?;
                } else if n != 0 {
                    return Err(Error::Unsupported("tier-2 free queue".into()));
                }
            }
            // Build an empty root that keeps the original info.
            let mut b = vec![0u8; bs];
            let toc_len = 0x240usize.min(bs - BTREE_NODE_HDR - BTREE_INFO_SIZE);
            wr16(&mut b, 0x20, BTNODE_ROOT | BTNODE_LEAF | BTNODE_FIXED_KV_SIZE);
            wr16(&mut b, 0x22, 0);
            wr32(&mut b, 0x24, 0);
            wr16(&mut b, 0x28, 0);
            wr16(&mut b, 0x2a, toc_len as u16);
            wr16(&mut b, 0x2c, 0);
            wr16(&mut b, 0x2e, (bs - BTREE_NODE_HDR - BTREE_INFO_SIZE - toc_len) as u16);
            wr16(&mut b, 0x30, BTOFF_INVALID);
            wr16(&mut b, 0x32, 0);
            wr16(&mut b, 0x34, BTOFF_INVALID);
            wr16(&mut b, 0x36, 0);
            let mut ni = info;
            ni.key_count = 0;
            ni.node_count = 1;
            ni.longest_key = 16;
            ni.write(&mut b[bs - BTREE_INFO_SIZE..]);
            ObjHeader { cksum: 0, oid: tree, xid: 0, type_and_flags: root.type_and_flags, subtype: root.subtype }.write(&mut b);
            eph.retain(|e| !node_oids[1..].contains(&e.oid));
            for e in eph.iter_mut() {
                if e.oid == tree {
                    e.data = b.clone();
                    e.size = bs as u32;
                }
            }
            wr64(&mut sm.data, off, 0);
            wr64(&mut sm.data, off + 16, 0);
        }
        Ok(())
    }

    pub fn container(&self) -> &Container {
        self.c
    }

    fn vol_mut(&mut self, index: usize) -> Result<&mut VolTxn> {
        if !self.vols.contains_key(&index) {
            let c: &Container = self.c;
            let vol = c.volume(index)?;
            if let Some(r) = vol.write_blocker() {
                return Err(Error::Unsupported(format!("volume \"{}\" cannot be written: {r}", vol.name)));
            }
            let nx_tree = rd64(&self.nx_omap_phys, 0x30);
            let ov = c
                .omap_lookup(nx_tree, vol.oid, c.xid())?
                .ok_or_else(|| Error::Corrupt("volume superblock missing from object map".into()))?;
            let src = VirtSrc { c, omap_tree: vol.omap_tree, xid: c.xid() };
            let fs = WTree::open(&src, vol.root_tree, vol.fs_kind(), true)?;
            let omap = WTree::open(&PhysSrc(c), vol.omap_tree, TreeKind::Omap, false)?;
            let (_, omap_phys) = c.read_object(vol.omap_oid, "volume object map", Some(OT_OMAP))?;
            let sb = vol.sb.clone();
            self.vols.insert(
                index,
                VolTxn { vol, sb, sb_omap_xid: ov.xid, fs, omap, omap_phys, extref: None, counters: VolCounters::default(), dirty: false },
            );
        }
        Ok(self.vols.get_mut(&index).unwrap())
    }

    // -----------------------------------------------------------------------
    // fs-tree helpers (transaction-aware)
    // -----------------------------------------------------------------------

    fn fs_get(&mut self, vi: usize, key: &[u8]) -> Result<Option<Vec<u8>>> {
        self.vol_mut(vi)?;
        let c: &Container = self.c;
        let v = self.vols.get_mut(&vi).unwrap();
        let src = VirtSrc { c, omap_tree: v.vol.omap_tree, xid: c.xid() };
        v.fs.get(&src, key)
    }

    fn fs_put(&mut self, vi: usize, key: Vec<u8>, val: Vec<u8>) -> Result<bool> {
        self.vol_mut(vi)?;
        let c: &Container = self.c;
        let v = self.vols.get_mut(&vi).unwrap();
        v.dirty = true;
        let src = VirtSrc { c, omap_tree: v.vol.omap_tree, xid: c.xid() };
        v.fs.insert(&src, key, val)
    }

    fn fs_del(&mut self, vi: usize, key: &[u8]) -> Result<Option<Vec<u8>>> {
        self.vol_mut(vi)?;
        let c: &Container = self.c;
        let v = self.vols.get_mut(&vi).unwrap();
        v.dirty = true;
        let src = VirtSrc { c, omap_tree: v.vol.omap_tree, xid: c.xid() };
        v.fs.remove(&src, key)
    }

    /// All records of (id, type) in key order.
    fn fs_records(&mut self, vi: usize, id: u64, ty: u8, start: Option<Vec<u8>>) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
        self.vol_mut(vi)?;
        let c: &Container = self.c;
        let v = self.vols.get_mut(&vi).unwrap();
        let src = VirtSrc { c, omap_tree: v.vol.omap_tree, xid: c.xid() };
        let start = start.unwrap_or_else(|| keys::simple_key(id, ty));
        v.fs.range(&src, &start, &|k| keys::fs_key_id_type(k) == (id, ty))
    }

    fn extref_tree(&mut self, vi: usize) -> Result<&mut WTree> {
        self.vol_mut(vi)?;
        let c: &Container = self.c;
        let v = self.vols.get_mut(&vi).unwrap();
        if v.extref.is_none() {
            let root = rd64(&v.sb, apsb::EXTENTREF_TREE_OID);
            let ty = rd32(&v.sb, apsb::EXTENTREF_TREE_TYPE);
            if ty & OBJ_STORAGETYPE_MASK != OBJ_PHYSICAL {
                return Err(Error::Unsupported("non-physical extent reference tree".into()));
            }
            v.extref = Some(WTree::open(&PhysSrc(c), root, TreeKind::ExtentRef, false)?);
        }
        Ok(v.extref.as_mut().unwrap())
    }

    pub fn volume_info(&mut self, vi: usize) -> Result<Volume> {
        Ok(self.vol_mut(vi)?.vol.clone())
    }

    pub fn inode(&mut self, vi: usize, ino: u64) -> Result<Inode> {
        let v = self
            .fs_get(vi, &keys::simple_key(ino, J_TYPE_INODE))?
            .ok_or_else(|| Error::NotFound(format!("inode {ino}")))?;
        Inode::parse(ino, &v)
    }

    fn put_inode(&mut self, vi: usize, inode: &Inode) -> Result<()> {
        self.fs_put(vi, keys::simple_key(inode.id, J_TYPE_INODE), inode.to_bytes())?;
        Ok(())
    }

    pub fn list_dir(&mut self, vi: usize, ino: u64) -> Result<Vec<DirEntry>> {
        let hashed = self.vol_mut(vi)?.vol.hashed();
        let recs = self.fs_records(vi, ino, J_TYPE_DIR_REC, Some(keys::drec_scan_start(ino, hashed)))?;
        recs.iter().map(|(k, v)| DirEntry::parse(k, v, hashed)).collect()
    }

    /// Name lookup honouring the volume's case/normalisation sensitivity.
    pub fn lookup(&mut self, vi: usize, parent: u64, name: &str) -> Result<Option<DirEntry>> {
        let (hashed, ci, ni) = {
            let v = &self.vol_mut(vi)?.vol;
            (v.hashed(), v.case_insensitive(), v.normalization_insensitive() || v.hashed())
        };
        let key = keys::drec_key(parent, name, hashed, ci);
        if let Some(val) = self.fs_get(vi, &key)? {
            return Ok(Some(DirEntry::parse(&key, &val, hashed)?));
        }
        if hashed {
            let h = keys::name_hash(name, ci);
            let mut start = keys::simple_key(parent, J_TYPE_DIR_REC);
            start.extend_from_slice(&h.to_le_bytes());
            for (k, v) in self.fs_records(vi, parent, J_TYPE_DIR_REC, Some(start))? {
                if rd32(&k, 8) & !DREC_LEN_MASK != h {
                    break;
                }
                let e = DirEntry::parse(&k, &v, hashed)?;
                if keys::names_equal(&e.name, name, ci, ni) {
                    return Ok(Some(e));
                }
            }
            return Ok(None);
        }
        if ni || ci {
            for e in self.list_dir(vi, parent)? {
                if keys::names_equal(&e.name, name, ci, ni) {
                    return Ok(Some(e));
                }
            }
        }
        Ok(None)
    }

    pub fn resolve(&mut self, vi: usize, path: &str) -> Result<u64> {
        let mut ino = ROOT_DIR_INO;
        for comp in path.split('/').filter(|s| !s.is_empty() && *s != ".") {
            ino = self.lookup(vi, ino, comp)?.ok_or_else(|| Error::NotFound(path.to_string()))?.file_id;
        }
        Ok(ino)
    }

    fn sb_add(&mut self, vi: usize, off: usize, delta: i64) -> Result<()> {
        let v = self.vol_mut(vi)?;
        let cur = rd64(&v.sb, off) as i64;
        wr64(&mut v.sb, off, (cur + delta).max(0) as u64);
        Ok(())
    }

    fn counter_for_mode(mode: u16) -> usize {
        match mode & S_IFMT {
            S_IFREG => apsb::NUM_FILES,
            S_IFDIR => apsb::NUM_DIRECTORIES,
            S_IFLNK => apsb::NUM_SYMLINKS,
            _ => apsb::NUM_OTHER,
        }
    }

    fn check_parent(&mut self, vi: usize, parent: u64) -> Result<Inode> {
        let p = self.inode(vi, parent)?;
        if !p.is_dir() {
            return Err(Error::NotADirectory(format!("inode {parent}")));
        }
        if p.internal_flags & (INODE_MAINTAIN_DIR_STATS | INODE_DIR_STATS_ORIGIN) != 0 {
            return Err(Error::Unsupported("directories that maintain directory statistics".into()));
        }
        if parent == PRIV_DIR_INO || parent == SNAP_DIR_INO || parent == PURGEABLE_DIR_INO {
            return Err(Error::Unsupported("writing into private system directories".into()));
        }
        Ok(p)
    }

    fn touch_parent(&mut self, vi: usize, mut p: Inode, delta_children: i32) -> Result<()> {
        let now = now_ns();
        p.nchildren_or_nlink = (p.nchildren_or_nlink as i64 + delta_children as i64).max(0) as u32;
        p.mod_time = now;
        p.change_time = now;
        p.write_generation_counter = p.write_generation_counter.wrapping_add(1);
        self.put_inode(vi, &p)
    }

    /// Creates a new inode + directory entry. Returns the new inode.
    fn create_node(&mut self, vi: usize, parent: u64, name: &str, mode: u16, meta: &NewFileMeta) -> Result<Inode> {
        validate_name(name)?;
        let p = self.check_parent(vi, parent)?;
        if self.lookup(vi, parent, name)?.is_some() {
            return Err(Error::Exists(name.to_string()));
        }
        let (hashed, ci) = {
            let v = &self.vol_mut(vi)?.vol;
            (v.hashed(), v.case_insensitive())
        };
        let ino = {
            let v = self.vol_mut(vi)?;
            let n = rd64(&v.sb, apsb::NEXT_OBJ_ID);
            if n >= 0x0800_0000_0000_0000 {
                return Err(Error::Unsupported("inode number space exhausted".into()));
            }
            wr64(&mut v.sb, apsb::NEXT_OBJ_ID, n + 1);
            n
        };
        let now = now_ns();
        let is_dir = mode & S_IFMT == S_IFDIR;
        let mut name_x = name.as_bytes().to_vec();
        name_x.push(0);
        let inode = Inode {
            id: ino,
            parent_id: parent,
            private_id: ino,
            create_time: meta.create_time,
            mod_time: meta.mod_time,
            change_time: now,
            access_time: meta.mod_time,
            internal_flags: INODE_NO_RSRC_FORK,
            nchildren_or_nlink: if is_dir { 0 } else { 1 },
            default_protection_class: 0,
            write_generation_counter: 0,
            bsd_flags: 0,
            owner: meta.uid,
            group: meta.gid,
            mode,
            pad1: 0,
            uncompressed_size: 0,
            xfields: vec![XField { ty: INO_EXT_TYPE_NAME, flags: XF_DO_NOT_COPY, data: name_x }],
        };
        self.put_inode(vi, &inode)?;
        let de = DirEntry {
            parent,
            name: name.to_string(),
            name_bytes: name.as_bytes().to_vec(),
            file_id: ino,
            date_added: now,
            flags: dtype_from_mode(mode),
            xfields: Vec::new(),
            key: Vec::new(),
        };
        self.fs_put(vi, keys::drec_key(parent, name, hashed, ci), de.to_val())?;
        self.touch_parent(vi, p, 1)?;
        self.sb_add(vi, Self::counter_for_mode(mode), 1)?;
        Ok(inode)
    }

    pub fn mkdir(&mut self, vi: usize, parent: u64, name: &str, meta: &NewFileMeta) -> Result<u64> {
        Ok(self.create_node(vi, parent, name, S_IFDIR | (meta.mode & 0o7777), meta)?.id)
    }

    pub fn symlink(&mut self, vi: usize, parent: u64, name: &str, target: &str) -> Result<u64> {
        if target.is_empty() || target.len() > 1023 {
            return Err(Error::InvalidName("symlink target".into()));
        }
        let meta = NewFileMeta { mode: 0o755, ..NewFileMeta::file() };
        let inode = self.create_node(vi, parent, name, S_IFLNK | 0o755, &meta)?;
        let mut t = target.as_bytes().to_vec();
        t.push(0);
        let x = Xattr { name: XATTR_SYMLINK.into(), flags: XATTR_DATA_EMBEDDED | XATTR_FILE_SYSTEM_OWNED, data: XattrData::Embedded(t) };
        self.fs_put(vi, keys::xattr_key(inode.id, XATTR_SYMLINK), x.to_val())?;
        Ok(inode.id)
    }

    /// Blocks that must remain free after an allocation of `n` blocks.
    fn reserve_margin(&self, n: u64) -> u64 {
        64 + n / 128
    }

    fn check_space(&mut self, vi: usize, blocks: u64) -> Result<()> {
        let margin = self.reserve_margin(blocks);
        if self.sm.free_blocks() < blocks + margin {
            return Err(Error::NoSpace);
        }
        let v = self.vol_mut(vi)?;
        let quota = rd64(&v.sb, apsb::QUOTA_BLOCKS);
        if quota != 0 {
            let alloc = (rd64(&v.sb, apsb::ALLOC_COUNT) as i64 + v.counters.alloc) as u64;
            if alloc + blocks + margin > quota {
                return Err(Error::NoSpace);
            }
        }
        Ok(())
    }

    /// Writes `len` bytes from `src` as a new data stream for `stream_id`.
    /// Returns the dstream description. Data blocks are written immediately
    /// (they are free space until the transaction commits).
    fn write_stream(&mut self, vi: usize, stream_id: u64, src: &mut dyn Read, len: u64, progress: Progress) -> Result<Dstream> {
        let bs = self.c.block_size as u64;
        let blocks = len.div_ceil(bs);
        self.check_space(vi, blocks)?;
        let mut logical = 0u64;
        let mut remaining = blocks;
        let mut buf: Vec<u8> = Vec::new();
        let mut done = 0u64;
        while remaining > 0 {
            let c: &Container = self.c;
            let (paddr, n) = self.sm.alloc(c, remaining.min(8192))?;
            {
                let v = self.vols.get_mut(&vi).unwrap();
                v.counters.alloc += n as i64;
                v.counters.alloced += n;
            }
            // Write in pieces of at most 8 MiB.
            let mut written = 0u64;
            while written < n {
                let chunk_blocks = (n - written).min((8 << 20) / bs);
                let bytes = (chunk_blocks * bs) as usize;
                buf.clear();
                buf.resize(bytes, 0);
                let want = ((len - done) as usize).min(bytes);
                let mut got = 0;
                while got < want {
                    let r = src.read(&mut buf[got..want])?;
                    if r == 0 {
                        return Err(Error::Other("source file ended early (was it modified during the copy?)".into()));
                    }
                    got += r;
                }
                self.c.write_block(paddr + written, &buf)?;
                done += want as u64;
                written += chunk_blocks;
                if !progress(done) {
                    return Err(Error::Cancelled);
                }
            }
            let fe = FileExtent { logical, length: n * bs, flags: 0, phys: paddr, crypto_id: 0 };
            self.fs_put(vi, keys::file_extent_key(stream_id, logical), fe.to_val())?;
            let pe = PhysExtent { paddr, len: n, kind: KIND_NEW, owner: stream_id, refcnt: 1 };
            self.extref_tree(vi)?;
            {
                let c: &Container = self.c;
                let v = self.vols.get_mut(&vi).unwrap();
                v.extref.as_mut().unwrap().insert(&PhysSrc(c), keys::extentref_key(paddr), pe.to_val())?;
            }
            logical += n * bs;
            remaining -= n;
        }
        Ok(Dstream { size: len, alloced_size: blocks * bs, default_crypto_id: 0, total_bytes_written: len, total_bytes_read: 0 })
    }

    /// Creates a regular file with the contents of `src`.
    pub fn create_file(&mut self, vi: usize, parent: u64, name: &str, src: &mut dyn Read, len: u64, meta: &NewFileMeta, progress: Progress) -> Result<u64> {
        let mut inode = self.create_node(vi, parent, name, S_IFREG | (meta.mode & 0o7777), meta)?;
        if len > 0 {
            let ds = self.write_stream(vi, inode.private_id, src, len, progress)?;
            inode.set_xfield(INO_EXT_TYPE_DSTREAM, XF_SYSTEM_FIELD, ds.to_bytes());
            self.put_inode(vi, &inode)?;
            self.fs_put(vi, keys::simple_key(inode.private_id, J_TYPE_DSTREAM_ID), 1u32.to_le_bytes().to_vec())?;
        }
        Ok(inode.id)
    }

    /// Replaces the contents of an existing regular file.
    pub fn overwrite_file(&mut self, vi: usize, ino: u64, src: &mut dyn Read, len: u64, mod_time: u64, progress: Progress) -> Result<()> {
        let mut inode = self.inode(vi, ino)?;
        if !inode.is_file() {
            return Err(Error::IsADirectory(format!("inode {ino}")));
        }
        if inode.is_compressed() {
            return Err(Error::Unsupported("overwriting compressed files (delete and re-create instead)".into()));
        }
        if inode.nlink() > 1 {
            return Err(Error::Unsupported("overwriting hard-linked files".into()));
        }
        self.drop_stream(vi, &inode)?;
        inode.remove_xfield(INO_EXT_TYPE_DSTREAM);
        inode.remove_xfield(INO_EXT_TYPE_SPARSE_BYTES);
        inode.internal_flags &= !(INODE_IS_SPARSE | INODE_WAS_CLONED);
        // A fresh private stream id avoids interference with clones.
        let new_stream = {
            let v = self.vol_mut(vi)?;
            if inode.private_id == inode.id {
                inode.id
            } else {
                let n = rd64(&v.sb, apsb::NEXT_OBJ_ID);
                wr64(&mut v.sb, apsb::NEXT_OBJ_ID, n + 1);
                n
            }
        };
        inode.private_id = new_stream;
        if len > 0 {
            let ds = self.write_stream(vi, new_stream, src, len, progress)?;
            inode.set_xfield(INO_EXT_TYPE_DSTREAM, XF_SYSTEM_FIELD, ds.to_bytes());
            self.fs_put(vi, keys::simple_key(new_stream, J_TYPE_DSTREAM_ID), 1u32.to_le_bytes().to_vec())?;
        }
        let now = now_ns();
        inode.mod_time = mod_time;
        inode.change_time = now;
        inode.write_generation_counter = inode.write_generation_counter.wrapping_add(1);
        self.put_inode(vi, &inode)
    }

    /// Decrements references to a physical range, freeing blocks that reach zero.
    fn release_phys(&mut self, vi: usize, paddr: u64, nblocks: u64) -> Result<()> {
        let mut p = paddr;
        let end = paddr + nblocks;
        while p < end {
            self.extref_tree(vi)?;
            let c: &Container = self.c;
            let v = self.vols.get_mut(&vi).unwrap();
            let t = v.extref.as_mut().unwrap();
            let src = PhysSrc(c);
            let (k, val) = t
                .get_floor(&src, &keys::extentref_key(p))?
                .ok_or_else(|| Error::Corrupt(format!("block {p:#x} has no extent reference")))?;
            let pe = PhysExtent::parse(&k, &val)?;
            if keys::fs_key_id_type(&k).1 != J_TYPE_EXTENT || pe.paddr > p || pe.paddr + pe.len <= p {
                return Err(Error::Corrupt(format!("block {p:#x} has no extent reference")));
            }
            if pe.kind != KIND_NEW || pe.refcnt == 0 {
                return Err(Error::Unsupported("extent references from snapshots".into()));
            }
            let seg_end = end.min(pe.paddr + pe.len);
            t.remove(&src, &k)?;
            if pe.paddr < p {
                let left = PhysExtent { len: p - pe.paddr, ..pe };
                t.insert(&src, keys::extentref_key(left.paddr), left.to_val())?;
            }
            if pe.refcnt > 1 {
                let mid = PhysExtent { paddr: p, len: seg_end - p, refcnt: pe.refcnt - 1, ..pe };
                t.insert(&src, keys::extentref_key(mid.paddr), mid.to_val())?;
            } else {
                self.sm.free_later(p, seg_end - p);
                v.counters.alloc -= (seg_end - p) as i64;
                v.counters.freed += seg_end - p;
            }
            if seg_end < pe.paddr + pe.len {
                let right = PhysExtent { paddr: seg_end, len: pe.paddr + pe.len - seg_end, ..pe };
                t.insert(&src, keys::extentref_key(right.paddr), right.to_val())?;
            }
            p = seg_end;
        }
        Ok(())
    }

    /// Removes all extents of a stream id (dstream refcount already handled).
    fn free_extents(&mut self, vi: usize, stream_id: u64) -> Result<()> {
        let bs = self.c.block_size as u64;
        let recs = self.fs_records(vi, stream_id, J_TYPE_FILE_EXTENT, None)?;
        for (k, v) in recs {
            let fe = FileExtent::parse(&k, &v)?;
            self.fs_del(vi, &k)?;
            if fe.phys != 0 && fe.length > 0 {
                self.release_phys(vi, fe.phys, fe.length.div_ceil(bs))?;
            }
        }
        Ok(())
    }

    /// Drops an inode's data stream reference (and its blocks when unshared).
    fn drop_stream(&mut self, vi: usize, inode: &Inode) -> Result<()> {
        let sid = inode.private_id;
        let key = keys::simple_key(sid, J_TYPE_DSTREAM_ID);
        match self.fs_get(vi, &key)? {
            Some(v) => {
                let refcnt = u32_at(&v, 0)?;
                if refcnt > 1 {
                    self.fs_put(vi, key, (refcnt - 1).to_le_bytes().to_vec())?;
                    return Ok(());
                }
                self.fs_del(vi, &key)?;
                self.free_extents(vi, sid)
            }
            None => {
                // Streams without a dstream-id record should not have extents,
                // but clean up defensively.
                self.free_extents(vi, sid)
            }
        }
    }

    /// Removes a directory entry and, when it was the last link, the inode.
    pub fn remove(&mut self, vi: usize, parent: u64, name: &str) -> Result<()> {
        let e = self.lookup(vi, parent, name)?.ok_or_else(|| Error::NotFound(name.into()))?;
        let p = self.check_parent(vi, parent)?;
        let inode = self.inode(vi, e.file_id)?;
        if e.file_id < MIN_USER_INO {
            return Err(Error::Unsupported("removing special system inodes".into()));
        }
        if inode.is_dir() {
            if inode.nchildren_or_nlink != 0 || !self.list_dir(vi, e.file_id)?.is_empty() {
                return Err(Error::DirectoryNotEmpty(name.into()));
            }
            if inode.internal_flags & (INODE_MAINTAIN_DIR_STATS | INODE_DIR_STATS_ORIGIN) != 0 {
                return Err(Error::Unsupported("directories with directory statistics".into()));
            }
        } else if inode.nlink() > 1 || e.sibling_id().is_some() {
            return Err(Error::Unsupported("removing hard-linked files".into()));
        }
        // Remove the directory entry.
        self.fs_del(vi, &e.key)?;
        self.touch_parent(vi, p, -1)?;
        // Extended attributes (and their streams).
        let bs = self.c.block_size as u64;
        let _ = bs;
        for (k, v) in self.fs_records(vi, e.file_id, J_TYPE_XATTR, None)? {
            let x = Xattr::parse(&k, &v)?;
            self.fs_del(vi, &k)?;
            if let XattrData::Stream { obj_id, .. } = x.data {
                self.free_extents(vi, obj_id)?;
            }
        }
        // Any remaining per-inode records (sibling links etc.) mean we do not
        // fully understand this inode: refuse.
        for ty in [J_TYPE_SIBLING_LINK, J_TYPE_DIR_STATS, J_TYPE_FILE_INFO, J_TYPE_CRYPTO_STATE] {
            if !self.fs_records(vi, e.file_id, ty, None)?.is_empty() {
                return Err(Error::Unsupported("inode has records this tool cannot remove".into()));
            }
        }
        if inode.dstream().is_some() || inode.is_file() {
            self.drop_stream(vi, &inode)?;
        }
        self.fs_del(vi, &keys::simple_key(e.file_id, J_TYPE_INODE))?;
        self.sb_add(vi, Self::counter_for_mode(inode.mode), -1)?;
        Ok(())
    }

    /// Recursively removes a file or directory tree.
    pub fn remove_recursive(&mut self, vi: usize, parent: u64, name: &str, progress: &mut dyn FnMut(&str) -> bool) -> Result<()> {
        let e = self.lookup(vi, parent, name)?.ok_or_else(|| Error::NotFound(name.into()))?;
        if e.is_dir() {
            for child in self.list_dir(vi, e.file_id)? {
                if !progress(&child.name) {
                    return Err(Error::Cancelled);
                }
                self.remove_recursive(vi, e.file_id, &child.name, progress)?;
            }
        }
        self.remove(vi, parent, &e.name)
    }

    /// Renames and/or moves an entry.
    pub fn rename(&mut self, vi: usize, parent: u64, name: &str, new_parent: u64, new_name: &str) -> Result<()> {
        validate_name(new_name)?;
        let e = self.lookup(vi, parent, name)?.ok_or_else(|| Error::NotFound(name.into()))?;
        let (hashed, ci, ni) = {
            let v = &self.vol_mut(vi)?.vol;
            (v.hashed(), v.case_insensitive(), v.normalization_insensitive() || v.hashed())
        };
        if parent == new_parent && e.name == new_name {
            return Ok(());
        }
        if let Some(t) = self.lookup(vi, new_parent, new_name)? {
            let same = t.file_id == e.file_id && parent == new_parent && keys::names_equal(&e.name, new_name, ci, ni);
            if !same {
                return Err(Error::Exists(new_name.into()));
            }
        }
        let mut inode = self.inode(vi, e.file_id)?;
        if !inode.is_dir() && (inode.nlink() > 1 || e.sibling_id().is_some()) {
            return Err(Error::Unsupported("renaming hard-linked files".into()));
        }
        if inode.is_dir() && new_parent != parent {
            // Refuse moving a directory into its own subtree.
            let mut cur = new_parent;
            for _ in 0..4096 {
                if cur == e.file_id {
                    return Err(Error::InvalidName("cannot move a directory into itself".into()));
                }
                if cur == ROOT_DIR_INO || cur < MIN_USER_INO {
                    break;
                }
                cur = self.inode(vi, cur)?.parent_id;
            }
        }
        let dst = self.check_parent(vi, new_parent)?;
        self.check_parent(vi, parent)?;
        self.fs_del(vi, &e.key)?;
        let mut ne = e.clone();
        ne.name = new_name.to_string();
        self.fs_put(vi, keys::drec_key(new_parent, new_name, hashed, ci), ne.to_val())?;
        let now = now_ns();
        inode.parent_id = new_parent;
        let mut nx = new_name.as_bytes().to_vec();
        nx.push(0);
        inode.set_xfield(INO_EXT_TYPE_NAME, XF_DO_NOT_COPY, nx);
        inode.change_time = now;
        self.put_inode(vi, &inode)?;
        if parent == new_parent {
            self.touch_parent(vi, dst, 0)?;
        } else {
            let src_p = self.inode(vi, parent)?;
            self.touch_parent(vi, src_p, -1)?;
            let dst = self.inode(vi, new_parent)?;
            self.touch_parent(vi, dst, 1)?;
        }
        Ok(())
    }

    /// Sets the modification time and permission bits of an inode.
    pub fn set_attrs(&mut self, vi: usize, ino: u64, mode: Option<u16>, mod_time: Option<u64>) -> Result<()> {
        let mut i = self.inode(vi, ino)?;
        if let Some(m) = mode {
            i.mode = (i.mode & S_IFMT) | (m & 0o7777);
        }
        if let Some(t) = mod_time {
            i.mod_time = t;
        }
        i.change_time = now_ns();
        self.put_inode(vi, &i)
    }

    pub fn free_blocks(&self) -> u64 {
        self.sm.free_blocks()
    }

    // -----------------------------------------------------------------------
    // Commit
    // -----------------------------------------------------------------------

    /// Writes everything and makes it durable with a new checkpoint.
    pub fn commit(mut self) -> Result<()> {
        let xid = self.xid;
        let bs = self.c.block_size as usize;
        let c: &Container = self.c;
        let nx_tree_paddr_old = rd64(&self.nx_omap_phys, 0x30);
        let _ = nx_tree_paddr_old;

        // --- volumes -------------------------------------------------------
        for (_, v) in self.vols.iter_mut() {
            if !v.dirty && !v.fs.is_changed() {
                continue;
            }
            let fs_src = VirtSrc { c, omap_tree: v.vol.omap_tree, xid: c.xid() };
            let mut changes = Vec::new();
            {
                let mut ctx = Ctx { c, sm: &mut self.sm, xid, next_oid: &mut self.next_oid, counters: Some(&mut v.counters) };
                let root = v.fs.flush(&fs_src, &mut ctx, &mut changes)?;
                if root != v.vol.root_tree {
                    return Err(Error::Other("internal error: file-system tree root moved".into()));
                }
            }
            // Apply object-map changes produced by the fs tree.
            let psrc = PhysSrc(c);
            for ch in changes {
                match ch {
                    OmapChange::Remove { oid, xid: ox } => {
                        if v.omap.remove(&psrc, &keys::omap_key(oid, ox))?.is_none() {
                            return Err(Error::Corrupt(format!("object map entry {oid:#x}/{ox} missing")));
                        }
                    }
                    OmapChange::Insert { oid, xid: nx, paddr } => {
                        let mut val = Vec::with_capacity(16);
                        val.extend_from_slice(&0u32.to_le_bytes());
                        val.extend_from_slice(&(bs as u32).to_le_bytes());
                        val.extend_from_slice(&paddr.to_le_bytes());
                        v.omap.insert(&psrc, keys::omap_key(oid, nx), val)?;
                    }
                }
            }
            // Extent reference tree.
            if let Some(t) = v.extref.as_mut() {
                let mut ctx = Ctx { c, sm: &mut self.sm, xid, next_oid: &mut self.next_oid, counters: Some(&mut v.counters) };
                let mut none = Vec::new();
                let root = t.flush(&psrc, &mut ctx, &mut none)?;
                wr64(&mut v.sb, apsb::EXTENTREF_TREE_OID, root);
            }
            // Volume object map tree + object.
            {
                let mut ctx = Ctx { c, sm: &mut self.sm, xid, next_oid: &mut self.next_oid, counters: Some(&mut v.counters) };
                let mut none = Vec::new();
                let root = v.omap.flush(&psrc, &mut ctx, &mut none)?;
                let old = rd64(&v.sb, apsb::OMAP_OID);
                let pa = ctx.alloc_block()?;
                ctx.free_block(old);
                let mut om = v.omap_phys.clone();
                wr64(&mut om, 0x30, root);
                ObjHeader { cksum: 0, oid: pa, xid, type_and_flags: OT_OMAP | OBJ_PHYSICAL, subtype: 0 }.write(&mut om);
                checksum::seal_object(&mut om);
                c.write_block(pa, &om)?;
                wr64(&mut v.sb, apsb::OMAP_OID, pa);
            }
            // Superblock counters and history.
            let alloc = rd64(&v.sb, apsb::ALLOC_COUNT) as i64 + v.counters.alloc;
            if alloc < 0 {
                return Err(Error::Corrupt("volume allocation count would become negative".into()));
            }
            wr64(&mut v.sb, apsb::ALLOC_COUNT, alloc as u64);
            let ta = rd64(&v.sb, apsb::TOTAL_ALLOCED) + v.counters.alloced;
            wr64(&mut v.sb, apsb::TOTAL_ALLOCED, ta);
            let tf = rd64(&v.sb, apsb::TOTAL_FREED) + v.counters.freed;
            wr64(&mut v.sb, apsb::TOTAL_FREED, tf);
            let now = now_ns();
            wr64(&mut v.sb, apsb::LAST_MOD_TIME, now);
            let mb = apsb::MODIFIED_BY;
            let sz = apsb::MODIFIED_BY_SIZE;
            let first_is_us = cstr(&v.sb[mb..mb + 32]) == MODIFIED_BY;
            if !first_is_us {
                v.sb.copy_within(mb..mb + 7 * sz, mb + sz);
                v.sb[mb..mb + 32].fill(0);
                let id = MODIFIED_BY.as_bytes();
                v.sb[mb..mb + id.len()].copy_from_slice(id);
            }
            wr64(&mut v.sb, mb + 32, now);
            wr64(&mut v.sb, mb + 40, xid);
            // Write the superblock (virtual object in the container omap).
            let pa = self.sm.alloc_one(c)?;
            self.sm.free_later(v.vol.paddr, 1);
            ObjHeader { cksum: 0, oid: v.vol.oid, xid, type_and_flags: OT_FS | OBJ_VIRTUAL, subtype: 0 }.write(&mut v.sb);
            checksum::seal_object(&mut v.sb);
            c.write_block(pa, &v.sb)?;
            self.nx_omap_changes.push(OmapChange::Remove { oid: v.vol.oid, xid: v.sb_omap_xid });
            self.nx_omap_changes.push(OmapChange::Insert { oid: v.vol.oid, xid, paddr: pa });
        }

        // --- reserve accounting ---------------------------------------------
        {
            let mut res_total = 0u64;
            let mut res_alloc = 0u64;
            for (idx, oid) in c.volume_oids() {
                let (alloc, reserve) = match self.vols.get(&idx) {
                    Some(v) => (rd64(&v.sb, apsb::ALLOC_COUNT), rd64(&v.sb, apsb::RESERVE_BLOCKS)),
                    None => {
                        let _ = oid;
                        let vol = c.volume(idx)?;
                        (vol.alloc_blocks(), vol.reserve_blocks())
                    }
                };
                if reserve != 0 {
                    res_total += reserve;
                    res_alloc += alloc.min(reserve);
                }
            }
            wr64(&mut self.sm.data, sm::FS_RESERVE_BLOCK_COUNT, res_total);
            wr64(&mut self.sm.data, sm::FS_RESERVE_ALLOC_COUNT, res_alloc);
        }

        // --- container object map -----------------------------------------
        {
            let psrc = PhysSrc(c);
            for ch in std::mem::take(&mut self.nx_omap_changes) {
                match ch {
                    OmapChange::Remove { oid, xid: ox } => {
                        if self.nx_omap.remove(&psrc, &keys::omap_key(oid, ox))?.is_none() {
                            return Err(Error::Corrupt(format!("container object map entry {oid:#x}/{ox} missing")));
                        }
                    }
                    OmapChange::Insert { oid, xid: nx_, paddr } => {
                        let mut val = Vec::with_capacity(16);
                        val.extend_from_slice(&0u32.to_le_bytes());
                        val.extend_from_slice(&(bs as u32).to_le_bytes());
                        val.extend_from_slice(&paddr.to_le_bytes());
                        self.nx_omap.insert(&psrc, keys::omap_key(oid, nx_), val)?;
                    }
                }
            }
        }
        let mut nx_omap_oid = c.omap_oid();
        if self.nx_omap.is_changed() {
            let psrc = PhysSrc(c);
            let mut ctx = Ctx { c, sm: &mut self.sm, xid, next_oid: &mut self.next_oid, counters: None };
            let mut none = Vec::new();
            let root = self.nx_omap.flush(&psrc, &mut ctx, &mut none)?;
            let pa = ctx.alloc_block()?;
            ctx.free_block(nx_omap_oid);
            let mut om = self.nx_omap_phys.clone();
            wr64(&mut om, 0x30, root);
            ObjHeader { cksum: 0, oid: pa, xid, type_and_flags: OT_OMAP | OBJ_PHYSICAL, subtype: 0 }.write(&mut om);
            checksum::seal_object(&mut om);
            c.write_block(pa, &om)?;
            nx_omap_oid = pa;
        }

        // --- space manager ---------------------------------------------------
        self.sm.commit(c, xid)?;
        let sm_oid = self.sm.oid;
        for e in self.ephemeral.iter_mut() {
            if e.oid == sm_oid {
                if self.sm.data.len() != e.data.len() {
                    return Err(Error::Other("internal error: space manager size changed".into()));
                }
                e.data = self.sm.data.clone();
            }
        }

        // --- checkpoint --------------------------------------------------------
        let next_oid = self.next_oid;
        let eph = std::mem::take(&mut self.ephemeral);
        write_checkpoint(self.c, xid, next_oid, nx_omap_oid, eph)
    }
}

/// Writes ephemeral objects, checkpoint maps and the new superblock, with
/// write barriers in between, then updates the in-memory container.
fn write_checkpoint(c: &mut Container, xid: u64, next_oid: u64, omap_oid: u64, mut eph: Vec<EphemeralObject>) -> Result<()> {
    let bs = c.block_size as usize;
    let sb_old = c.sb.clone();
    let desc_base = rd64(&sb_old, nx::XP_DESC_BASE);
    let desc_blocks = rd32(&sb_old, nx::XP_DESC_BLOCKS);
    let desc_len_old = rd32(&sb_old, nx::XP_DESC_LEN);
    let data_base = rd64(&sb_old, nx::XP_DATA_BASE);
    let data_blocks = rd32(&sb_old, nx::XP_DATA_BLOCKS);
    let data_len_old = rd32(&sb_old, nx::XP_DATA_LEN);
    let data_index_old = rd32(&sb_old, nx::XP_DATA_INDEX);

    // Place ephemeral objects right after the current checkpoint's data.
    let need: u32 = eph.iter().map(|e| e.size / bs as u32).sum();
    if need + data_len_old > data_blocks {
        return Err(Error::Unsupported("checkpoint data area too small".into()));
    }
    let data_start = (data_index_old + data_len_old) % data_blocks;
    let per_map = (bs - 0x28) / 40;
    let nmaps = eph.len().div_ceil(per_map).max(1) as u32;
    if nmaps + 1 + desc_len_old > desc_blocks {
        return Err(Error::Unsupported("checkpoint descriptor area too small".into()));
    }
    // The new descriptors go right after the superblock in use.
    let desc_start = (c.sb_desc_index + 1) % desc_blocks;

    let mut di = data_start;
    let mut mappings = Vec::with_capacity(eph.len());
    for e in eph.iter_mut() {
        let paddr = data_base + di as u64;
        ObjHeader { cksum: 0, oid: e.oid, xid, type_and_flags: e.type_and_flags, subtype: e.subtype }.write(&mut e.data);
        checksum::seal_object(&mut e.data);
        let nblk = e.size as usize / bs;
        for k in 0..nblk {
            c.write_block(data_base + di as u64, &e.data[k * bs..(k + 1) * bs])?;
            di = (di + 1) % data_blocks;
        }
        mappings.push((e.type_and_flags, e.subtype, e.size, e.fs_oid, e.oid, paddr));
    }

    let mut map_blocks = Vec::new();
    for (m, chunk) in mappings.chunks(per_map.max(1)).enumerate() {
        let idx = (desc_start + m as u32) % desc_blocks;
        let paddr = desc_base + idx as u64;
        let mut b = vec![0u8; bs];
        let last = m as u32 + 1 == nmaps;
        wr32(&mut b, 0x20, if last { CHECKPOINT_MAP_LAST } else { 0 });
        wr32(&mut b, 0x24, chunk.len() as u32);
        for (i, &(t, st, size, fs_oid, oid, pa)) in chunk.iter().enumerate() {
            let o = 0x28 + i * 40;
            wr32(&mut b, o, t);
            wr32(&mut b, o + 4, st);
            wr32(&mut b, o + 8, size);
            wr32(&mut b, o + 12, 0);
            wr64(&mut b, o + 16, fs_oid);
            wr64(&mut b, o + 24, oid);
            wr64(&mut b, o + 32, pa);
        }
        ObjHeader { cksum: 0, oid: paddr, xid, type_and_flags: OT_CHECKPOINT_MAP | OBJ_PHYSICAL, subtype: 0 }.write(&mut b);
        checksum::seal_object(&mut b);
        map_blocks.push((paddr, b));
    }
    if map_blocks.is_empty() {
        let idx = desc_start;
        let paddr = desc_base + idx as u64;
        let mut b = vec![0u8; bs];
        wr32(&mut b, 0x20, CHECKPOINT_MAP_LAST);
        ObjHeader { cksum: 0, oid: paddr, xid, type_and_flags: OT_CHECKPOINT_MAP | OBJ_PHYSICAL, subtype: 0 }.write(&mut b);
        checksum::seal_object(&mut b);
        map_blocks.push((paddr, b));
    }
    for (pa, b) in &map_blocks {
        c.write_block(*pa, b)?;
    }

    // Barrier: all objects of the new checkpoint must be durable before the
    // superblock that makes them reachable.
    c.dev.flush()?;

    let sb_idx = (desc_start + nmaps) % desc_blocks;
    let mut sb = sb_old.clone();
    wr64(&mut sb, nx::NEXT_OID, next_oid);
    wr64(&mut sb, nx::NEXT_XID, xid + 1);
    wr32(&mut sb, nx::XP_DESC_INDEX, desc_start);
    wr32(&mut sb, nx::XP_DESC_LEN, nmaps + 1);
    wr32(&mut sb, nx::XP_DESC_NEXT, (sb_idx + 1) % desc_blocks);
    wr32(&mut sb, nx::XP_DATA_INDEX, data_start);
    wr32(&mut sb, nx::XP_DATA_LEN, need);
    wr32(&mut sb, nx::XP_DATA_NEXT, (data_start + need) % data_blocks);
    wr64(&mut sb, nx::OMAP_OID, omap_oid);
    let h = ObjHeader::parse(&sb_old)?;
    ObjHeader { cksum: 0, oid: 1, xid, type_and_flags: h.type_and_flags, subtype: h.subtype }.write(&mut sb);
    checksum::seal_object(&mut sb);
    c.write_block(desc_base + sb_idx as u64, &sb)?;
    c.dev.flush()?;
    // Block zero mirrors the latest superblock.
    c.write_block_zero(&sb)?;
    c.dev.flush()?;

    c.sb = sb;
    c.sb_desc_index = sb_idx;
    c.ephemeral = eph;
    c.invalidate_cache();
    Ok(())
}
