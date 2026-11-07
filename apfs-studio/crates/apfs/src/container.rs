//! Container (NX superblock) handling: checkpoint selection, ephemeral objects,
//! block cache and object maps.

use crate::btree::{self, Node, NodeLoader};
use crate::bytes::*;
use crate::checksum;
use crate::device::DeviceRef;
use crate::error::{Error, Result};
use crate::format::*;
use crate::keys::{self, TreeKind};
use crate::volume::Volume;
use lru::LruCache;
use std::num::NonZeroUsize;
use std::sync::{Arc, Mutex};

/// An ephemeral object loaded from the checkpoint data area.
#[derive(Debug, Clone)]
pub struct EphemeralObject {
    pub oid: u64,
    pub type_and_flags: u32,
    pub subtype: u32,
    pub size: u32,
    pub fs_oid: u64,
    pub data: Vec<u8>,
}

/// Non-fatal observations made while opening a container.
#[derive(Debug, Clone, Default)]
pub struct OpenReport {
    pub warnings: Vec<String>,
    /// Set when the newest checkpoint was unusable and an older one was mounted.
    pub used_fallback_checkpoint: bool,
}

pub struct Container {
    pub(crate) dev: DeviceRef,
    pub block_size: u32,
    pub block_count: u64,
    /// The checkpoint superblock in use (full block).
    pub(crate) sb: Vec<u8>,
    /// Index of `sb` inside the checkpoint descriptor ring.
    pub(crate) sb_desc_index: u32,
    pub(crate) ephemeral: Vec<EphemeralObject>,
    cache: Mutex<LruCache<u64, Arc<Node>>>,
    pub report: OpenReport,
    pub(crate) writable: bool,
}

impl Container {
    /// Opens a container on `dev`. When `writable` is false nothing is ever written.
    pub fn open(dev: DeviceRef, writable: bool) -> Result<Self> {
        if writable && !dev.writable() {
            return Err(Error::ReadOnly);
        }
        let mut probe = vec![0u8; 4096];
        if dev.len() < 4096 {
            return Err(Error::Corrupt("device too small for an APFS container".into()));
        }
        dev.read_at(0, &mut probe)?;
        if u32_at(&probe, nx::MAGIC)? != NX_MAGIC {
            return Err(Error::Corrupt("no APFS container superblock (NXSB magic missing at block 0)".into()));
        }
        let bs = u32_at(&probe, nx::BLOCK_SIZE)?;
        if !(4096..=65536).contains(&bs) || !bs.is_power_of_two() {
            return Err(Error::Corrupt(format!("invalid block size {bs}")));
        }
        let mut b0 = vec![0u8; bs as usize];
        dev.read_at(0, &mut b0)?;
        let mut report = OpenReport::default();
        if !checksum::verify_object(&b0) {
            report.warnings.push("block zero has a bad checksum; using checkpoint area".into());
        }
        let block_count = u64_at(&b0, nx::BLOCK_COUNT)?;
        if block_count.saturating_mul(bs as u64) > dev.len() {
            report.warnings.push(format!(
                "container claims {} bytes but the device has only {}",
                block_count * bs as u64,
                dev.len()
            ));
        }
        let desc_base = u64_at(&b0, nx::XP_DESC_BASE)?;
        let desc_blocks = u32_at(&b0, nx::XP_DESC_BLOCKS)?;
        if desc_base >> 63 != 0 || desc_blocks >> 31 != 0 {
            return Err(Error::Unsupported("non-contiguous checkpoint descriptor areas".into()));
        }
        if desc_blocks == 0 || desc_blocks > 1 << 20 {
            return Err(Error::Corrupt(format!("invalid checkpoint descriptor area size {desc_blocks}")));
        }

        let mut c = Container {
            dev,
            block_size: bs,
            block_count,
            sb: b0.clone(),
            sb_desc_index: 0,
            ephemeral: Vec::new(),
            cache: Mutex::new(LruCache::new(NonZeroUsize::new(8192).unwrap())),
            report,
            writable,
        };

        // Collect every valid superblock in the descriptor ring.
        let mut cands: Vec<(u64, u32, Vec<u8>)> = Vec::new();
        for i in 0..desc_blocks {
            let paddr = desc_base + i as u64;
            let Ok(b) = c.read_block_raw(paddr) else { continue };
            if !checksum::verify_object(&b) {
                continue;
            }
            let Ok(h) = ObjHeader::parse(&b) else { continue };
            if h.obj_type() != OT_NX_SUPERBLOCK || rd32(&b, nx::MAGIC) != NX_MAGIC {
                continue;
            }
            cands.push((h.xid, i, b));
        }
        if cands.is_empty() {
            return Err(Error::Corrupt("no valid checkpoint superblock found".into()));
        }
        cands.sort_by(|a, b| b.0.cmp(&a.0));
        let newest = cands[0].0;
        let mut last_err = None;
        for (xid, idx, sb) in cands {
            match c.load_checkpoint(&sb, idx) {
                Ok(eph) => {
                    if xid != newest {
                        c.report.used_fallback_checkpoint = true;
                        c.report.warnings.push(format!(
                            "newest checkpoint (xid {newest}) is damaged; mounted older checkpoint xid {xid}"
                        ));
                    }
                    c.sb = sb;
                    c.sb_desc_index = idx;
                    c.ephemeral = eph;
                    c.validate_superblock()?;
                    if writable {
                        c.check_writable()?;
                    }
                    return Ok(c);
                }
                Err(e) => {
                    c.report.warnings.push(format!("checkpoint xid {xid} rejected: {e}"));
                    last_err = Some(e);
                }
            }
        }
        Err(last_err.unwrap_or_else(|| Error::Corrupt("no usable checkpoint".into())))
    }

    fn validate_superblock(&self) -> Result<()> {
        let bs = rd32(&self.sb, nx::BLOCK_SIZE);
        if bs != self.block_size {
            return Err(Error::Corrupt("checkpoint superblock block size differs from block zero".into()));
        }
        Ok(())
    }

    fn check_writable(&self) -> Result<()> {
        if self.report.used_fallback_checkpoint {
            return Err(Error::Unsupported(
                "the newest checkpoint is damaged; refusing to write (repair the container on a Mac first)".into(),
            ));
        }
        let incompat = rd64(&self.sb, nx::INCOMPAT);
        if incompat & NX_INCOMPAT_FUSION != 0 {
            return Err(Error::Unsupported("writing to Fusion containers".into()));
        }
        if incompat & !0x3 != 0 {
            return Err(Error::Unsupported(format!("unknown container incompatible features {incompat:#x}")));
        }
        if rd64(&self.sb, nx::RO_COMPAT) != 0 {
            return Err(Error::Unsupported("container has read-only-compatible features".into()));
        }
        // nx_blocked_out_prange: a resize is in progress.
        if rd64(&self.sb, 0x4e0) != 0 {
            return Err(Error::Unsupported("container resize in progress".into()));
        }
        if self.block_count * self.block_size as u64 > self.dev.len() {
            return Err(Error::Unsupported("container is larger than the device".into()));
        }
        Ok(())
    }

    /// Loads and verifies all ephemeral objects of a checkpoint.
    fn load_checkpoint(&self, sb: &[u8], sb_idx: u32) -> Result<Vec<EphemeralObject>> {
        let bs = self.block_size as usize;
        let xid = rd64(sb, 16);
        let desc_base = rd64(sb, nx::XP_DESC_BASE);
        let desc_blocks = rd32(sb, nx::XP_DESC_BLOCKS);
        let desc_len = rd32(sb, nx::XP_DESC_LEN);
        let data_base = rd64(sb, nx::XP_DATA_BASE);
        let data_blocks = rd32(sb, nx::XP_DATA_BLOCKS);
        if desc_len == 0 || desc_len > desc_blocks || data_blocks == 0 {
            return Err(Error::Corrupt("bad checkpoint area lengths".into()));
        }
        if rd32(sb, nx::BLOCK_SIZE) as usize != bs {
            return Err(Error::Corrupt("block size mismatch".into()));
        }
        let nmaps = desc_len - 1;
        let first = (sb_idx + desc_blocks - nmaps) % desc_blocks;
        let mut out = Vec::new();
        for m in 0..nmaps {
            let idx = (first + m) % desc_blocks;
            let paddr = desc_base + idx as u64;
            let b = self.read_block_raw(paddr)?;
            let h = check_object(&b, "checkpoint map", paddr, Some(OT_CHECKPOINT_MAP))?;
            if h.xid != xid {
                return Err(Error::Corrupt(format!("checkpoint map at {paddr:#x} belongs to xid {} not {xid}", h.xid)));
            }
            let flags = u32_at(&b, 0x20)?;
            let count = u32_at(&b, 0x24)? as usize;
            if 0x28 + count * 40 > bs {
                return Err(Error::Corrupt("checkpoint map count too large".into()));
            }
            for i in 0..count {
                let e = 0x28 + i * 40;
                let type_and_flags = rd32(&b, e);
                let subtype = rd32(&b, e + 4);
                let size = rd32(&b, e + 8);
                let fs_oid = rd64(&b, e + 16);
                let oid = rd64(&b, e + 24);
                let paddr = rd64(&b, e + 32);
                if size == 0 || size as usize % bs != 0 || size > 64 * 1024 * 1024 {
                    return Err(Error::Corrupt(format!("ephemeral object {oid:#x} has invalid size {size}")));
                }
                if paddr < data_base || paddr >= data_base + data_blocks as u64 {
                    return Err(Error::Corrupt(format!("ephemeral object {oid:#x} outside checkpoint data area")));
                }
                let nblk = size as usize / bs;
                let mut data = vec![0u8; size as usize];
                let mut di = (paddr - data_base) as u32;
                for k in 0..nblk {
                    let blk = self.read_block_raw(data_base + di as u64)?;
                    data[k * bs..(k + 1) * bs].copy_from_slice(&blk);
                    di = (di + 1) % data_blocks;
                }
                if !checksum::verify_object(&data) {
                    return Err(Error::Corrupt(format!("ephemeral object {oid:#x} has a bad checksum")));
                }
                let oh = ObjHeader::parse(&data)?;
                if oh.oid != oid || oh.type_and_flags != type_and_flags {
                    return Err(Error::Corrupt(format!("ephemeral object {oid:#x} does not match its mapping")));
                }
                if oh.xid > xid {
                    return Err(Error::Corrupt(format!("ephemeral object {oid:#x} is newer than its checkpoint")));
                }
                out.push(EphemeralObject { oid, type_and_flags, subtype, size, fs_oid, data });
            }
            let last = flags & CHECKPOINT_MAP_LAST != 0;
            if last != (m + 1 == nmaps) {
                return Err(Error::Corrupt("checkpoint map LAST flag inconsistent".into()));
            }
        }
        // The space manager must be present.
        let sm_oid = rd64(sb, nx::SPACEMAN_OID);
        if !out.iter().any(|e| e.oid == sm_oid) {
            return Err(Error::Corrupt("space manager missing from checkpoint".into()));
        }
        // The container object map must be readable.
        let omap_oid = rd64(sb, nx::OMAP_OID);
        let ob = self.read_block_raw(omap_oid)?;
        check_object(&ob, "container object map", omap_oid, Some(OT_OMAP))?;
        let tree = rd64(&ob, 0x30);
        let tb = self.read_block_raw(tree)?;
        check_object(&tb, "container object map tree", tree, None)?;
        Ok(out)
    }

    // -----------------------------------------------------------------------
    // Block I/O
    // -----------------------------------------------------------------------

    pub fn device(&self) -> &DeviceRef {
        &self.dev
    }

    pub fn is_writable(&self) -> bool {
        self.writable
    }

    pub(crate) fn read_block_raw(&self, paddr: u64) -> Result<Vec<u8>> {
        let bs = self.block_size as u64;
        if paddr >= self.block_count.max(1) && self.block_count != 0 {
            return Err(Error::Corrupt(format!("block address {paddr:#x} beyond end of container")));
        }
        let mut b = vec![0u8; bs as usize];
        self.dev.read_at(paddr * bs, &mut b)?;
        Ok(b)
    }

    /// Reads `count` consecutive blocks (file data; not cached).
    pub fn read_blocks(&self, paddr: u64, buf: &mut [u8]) -> Result<()> {
        let bs = self.block_size as u64;
        let nblocks = (buf.len() as u64).div_ceil(bs);
        if paddr.checked_add(nblocks).map_or(true, |e| e > self.block_count) {
            return Err(Error::Corrupt(format!("extent at block {paddr:#x} runs past the end of the container")));
        }
        self.dev.read_at(paddr * bs, buf)
    }

    pub(crate) fn write_block(&self, paddr: u64, data: &[u8]) -> Result<()> {
        if !self.writable {
            return Err(Error::ReadOnly);
        }
        let bs = self.block_size as u64;
        if data.len() as u64 % bs != 0 {
            return Err(Error::Other("unaligned block write".into()));
        }
        let n = data.len() as u64 / bs;
        if paddr == 0 || paddr.checked_add(n).map_or(true, |e| e > self.block_count) {
            return Err(Error::Other(format!("refusing to write outside the container (block {paddr:#x})")));
        }
        {
            let mut c = self.cache.lock().unwrap();
            for i in 0..n {
                c.pop(&(paddr + i));
            }
        }
        self.dev.write_at(paddr * bs, data)
    }

    pub(crate) fn write_block_zero(&self, data: &[u8]) -> Result<()> {
        if !self.writable {
            return Err(Error::ReadOnly);
        }
        self.dev.write_at(0, data)
    }

    pub(crate) fn invalidate_cache(&self) {
        self.cache.lock().unwrap().clear();
    }

    /// Reads a physical object and verifies its checksum.
    pub fn read_object(&self, paddr: u64, what: &str, expect: Option<u32>) -> Result<(ObjHeader, Vec<u8>)> {
        let b = self.read_block_raw(paddr)?;
        let h = check_object(&b, what, paddr, expect)?;
        Ok((h, b))
    }

    /// Loads a B-tree node at a physical address with caching.
    pub fn node_at(&self, paddr: u64, kind: TreeKind) -> Result<Arc<Node>> {
        if let Some(n) = self.cache.lock().unwrap().get(&paddr) {
            return Ok(n.clone());
        }
        let b = self.read_block_raw(paddr)?;
        if !checksum::verify_object(&b) {
            return Err(Error::Corrupt(format!("B-tree node at block {paddr:#x}: bad checksum")));
        }
        let n = Arc::new(Node::parse(b, paddr, kind)?);
        if n.hdr.subtype != kind.subtype() {
            return Err(Error::Corrupt(format!(
                "B-tree node at block {paddr:#x}: subtype {:#x} does not match expected tree",
                n.hdr.subtype
            )));
        }
        self.cache.lock().unwrap().put(paddr, n.clone());
        Ok(n)
    }

    // -----------------------------------------------------------------------
    // Superblock accessors
    // -----------------------------------------------------------------------

    pub fn xid(&self) -> u64 {
        rd64(&self.sb, 16)
    }
    pub fn next_xid(&self) -> u64 {
        rd64(&self.sb, nx::NEXT_XID)
    }
    pub fn uuid(&self) -> uuid::Uuid {
        uuid::Uuid::from_slice(&self.sb[nx::UUID..nx::UUID + 16]).unwrap_or_default()
    }
    pub fn superblock(&self) -> &[u8] {
        &self.sb
    }
    pub fn omap_oid(&self) -> u64 {
        rd64(&self.sb, nx::OMAP_OID)
    }
    pub fn spaceman_oid(&self) -> u64 {
        rd64(&self.sb, nx::SPACEMAN_OID)
    }
    pub fn is_fusion(&self) -> bool {
        rd64(&self.sb, nx::INCOMPAT) & NX_INCOMPAT_FUSION != 0
    }

    pub fn ephemeral(&self, oid: u64) -> Option<&EphemeralObject> {
        self.ephemeral.iter().find(|e| e.oid == oid)
    }

    pub fn volume_oids(&self) -> Vec<(usize, u64)> {
        let max = (rd32(&self.sb, nx::MAX_FILE_SYSTEMS) as usize).clamp(1, nx::MAX_FS);
        (0..max)
            .map(|i| (i, rd64(&self.sb, nx::FS_OID + i * 8)))
            .filter(|&(_, oid)| oid != 0)
            .collect()
    }

    /// Container object map tree root.
    pub fn omap_tree(&self) -> Result<u64> {
        let (_, b) = self.read_object(self.omap_oid(), "container object map", Some(OT_OMAP))?;
        Ok(rd64(&b, 0x30))
    }

    /// Resolves a virtual object id through an object map tree.
    pub fn omap_lookup(&self, omap_tree: u64, oid: u64, xid: u64) -> Result<Option<OmapVal>> {
        let loader = PhysLoader(self);
        let key = keys::omap_key(oid, xid);
        match btree::lookup_floor(&loader, omap_tree, TreeKind::Omap, &key)? {
            Some((k, v)) if rd64(&k, 0) == oid => {
                if v.len() < 16 {
                    return Err(Error::Corrupt("object map value too short".into()));
                }
                let flags = rd32(&v, 0);
                if flags & OMAP_VAL_DELETED != 0 {
                    return Ok(None);
                }
                Ok(Some(OmapVal { xid: rd64(&k, 8), flags, size: rd32(&v, 4), paddr: rd64(&v, 8) }))
            }
            _ => Ok(None),
        }
    }

    /// Opens every volume in the container. Volumes that fail to open are
    /// returned as errors so a single damaged volume does not hide the others.
    pub fn volumes(&self) -> Vec<(usize, Result<Volume>)> {
        let omap_tree = match self.omap_tree() {
            Ok(t) => t,
            Err(e) => return vec![(0, Err(e))],
        };
        self.volume_oids()
            .into_iter()
            .map(|(idx, oid)| (idx, Volume::open(self, omap_tree, oid, idx)))
            .collect()
    }

    pub fn volume(&self, index: usize) -> Result<Volume> {
        let omap_tree = self.omap_tree()?;
        let oid = self
            .volume_oids()
            .into_iter()
            .find(|&(i, _)| i == index)
            .map(|(_, o)| o)
            .ok_or_else(|| Error::NotFound(format!("volume #{index}")))?;
        Volume::open(self, omap_tree, oid, index)
    }

    /// Free-space statistics from the space manager.
    pub fn space_info(&self) -> Result<SpaceInfo> {
        let sm = self
            .ephemeral(self.spaceman_oid())
            .ok_or_else(|| Error::Corrupt("space manager missing".into()))?;
        let d = &sm.data;
        let total = u64_at(d, sm::DEV + sm::D_BLOCK_COUNT)?;
        let free = u64_at(d, sm::DEV + sm::D_FREE_COUNT)?;
        Ok(SpaceInfo { block_size: self.block_size, total_blocks: total, free_blocks: free })
    }
}

#[derive(Debug, Clone, Copy)]
pub struct OmapVal {
    pub xid: u64,
    pub flags: u32,
    pub size: u32,
    pub paddr: u64,
}

#[derive(Debug, Clone, Copy)]
pub struct SpaceInfo {
    pub block_size: u32,
    pub total_blocks: u64,
    pub free_blocks: u64,
}

impl SpaceInfo {
    pub fn total_bytes(&self) -> u64 {
        self.total_blocks * self.block_size as u64
    }
    pub fn free_bytes(&self) -> u64 {
        self.free_blocks * self.block_size as u64
    }
}

/// Loads nodes of physical trees (oid == block address).
pub struct PhysLoader<'a>(pub &'a Container);

impl NodeLoader for PhysLoader<'_> {
    fn load(&self, oid: u64, kind: TreeKind) -> Result<Arc<Node>> {
        let n = self.0.node_at(oid, kind)?;
        if n.hdr.oid != oid {
            return Err(Error::Corrupt(format!("physical node at {oid:#x} has object id {:#x}", n.hdr.oid)));
        }
        Ok(n)
    }
}

/// Loads nodes of virtual trees through an object map.
pub struct VirtLoader<'a> {
    pub c: &'a Container,
    pub omap_tree: u64,
    pub xid: u64,
}

impl NodeLoader for VirtLoader<'_> {
    fn load(&self, oid: u64, kind: TreeKind) -> Result<Arc<Node>> {
        let v = self
            .c
            .omap_lookup(self.omap_tree, oid, self.xid)?
            .ok_or_else(|| Error::Corrupt(format!("virtual object {oid:#x} missing from object map")))?;
        if v.flags & OMAP_VAL_ENCRYPTED != 0 {
            return Err(Error::Unsupported("encrypted metadata".into()));
        }
        let n = self.c.node_at(v.paddr, kind)?;
        if n.hdr.oid != oid {
            return Err(Error::Corrupt(format!(
                "node at {:#x} has object id {:#x}, expected {oid:#x}",
                v.paddr, n.hdr.oid
            )));
        }
        Ok(n)
    }
}

/// Loads nodes of ephemeral trees (free queues) from the checkpoint.
pub struct EphLoader<'a>(pub &'a Container);

impl NodeLoader for EphLoader<'_> {
    fn load(&self, oid: u64, kind: TreeKind) -> Result<Arc<Node>> {
        let e = self.0.ephemeral(oid).ok_or_else(|| Error::Corrupt(format!("ephemeral node {oid:#x} missing")))?;
        Ok(Arc::new(Node::parse(e.data.clone(), 0, kind)?))
    }
}
