//! Consistency checker ("fsck"). It walks every reachable structure of a
//! container and cross-checks allocation bitmaps, B-tree invariants and
//! file-system semantics. Used to validate writes and offered to users.

use crate::btree::Node;
use crate::bytes::*;
use crate::checksum;
use crate::container::{Container, EphLoader};
use crate::error::{Error, Result};
use crate::format::*;
use crate::keys::{self, compare, TreeKind};
use crate::records::*;
use crate::volume::Volume;
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;

#[derive(Debug, Default, Clone)]
pub struct CheckReport {
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    pub blocks_checked: u64,
    pub inodes_checked: u64,
}

impl CheckReport {
    pub fn ok(&self) -> bool {
        self.errors.is_empty()
    }
    fn err(&mut self, s: impl Into<String>) {
        if self.errors.len() < 500 {
            self.errors.push(s.into());
        }
    }
    fn warn(&mut self, s: impl Into<String>) {
        if self.warnings.len() < 500 {
            self.warnings.push(s.into());
        }
    }
}

struct Usage {
    owner: Vec<u8>,
}

const U_FREE: u8 = 0;

impl Usage {
    fn new(n: u64) -> Self {
        Self { owner: vec![U_FREE; n as usize] }
    }
    fn mark(&mut self, r: &mut CheckReport, p: u64, n: u64, tag: u8, what: &str) {
        for b in p..p.saturating_add(n) {
            match self.owner.get_mut(b as usize) {
                None => {
                    r.err(format!("{what}: block {b:#x} is beyond the end of the container"));
                    return;
                }
                Some(o) if *o != U_FREE => {
                    r.err(format!("{what}: block {b:#x} is used twice"));
                    return;
                }
                Some(o) => *o = tag,
            }
        }
    }
}

type Resolver<'a> = dyn Fn(u64) -> Result<(u64, Arc<Node>, Option<u64>)> + 'a;

/// Walks and validates a B-tree. `resolve` maps oid -> (paddr, node, omap xid).
fn walk_tree(
    r: &mut CheckReport,
    name: &str,
    root_oid: u64,
    kind: TreeKind,
    storage: u32,
    resolve: &Resolver,
    on_node: &mut dyn FnMut(u64, u64, u64),
    on_rec: &mut dyn FnMut(&[u8], &[u8]),
) -> Result<()> {
    let (pa, root, _) = resolve(root_oid)?;
    if !root.is_root() {
        r.err(format!("{name}: root node is not flagged as root"));
    }
    let info = root.info()?;
    let mut keys = 0u64;
    let mut nodes = 0u64;
    struct St<'b> {
        r: &'b mut CheckReport,
        keys: u64,
        nodes: u64,
        prev: Option<Vec<u8>>,
    }
    fn rec(
        st: &mut St,
        name: &str,
        node: &Node,
        pa: u64,
        oid: u64,
        kind: TreeKind,
        storage: u32,
        resolve: &Resolver,
        on_node: &mut dyn FnMut(u64, u64, u64),
        on_rec: &mut dyn FnMut(&[u8], &[u8]),
        expect_first: Option<&[u8]>,
        depth: usize,
    ) -> Result<()> {
        if depth > 16 {
            st.r.err(format!("{name}: tree too deep"));
            return Ok(());
        }
        st.nodes += 1;
        on_node(oid, pa, node.hdr.xid);
        let t = node.hdr.obj_type();
        let want_t = if node.is_root() { OT_BTREE } else { OT_BTREE_NODE };
        if t != want_t {
            st.r.err(format!("{name}: node {oid:#x} has object type {t:#x}"));
        }
        if node.hdr.storage() != storage {
            st.r.err(format!("{name}: node {oid:#x} has wrong storage type"));
        }
        if node.hdr.oid != oid {
            st.r.err(format!("{name}: node at {pa:#x} has oid {:#x}, expected {oid:#x}", node.hdr.oid));
        }
        if depth > 0 && node.is_root() {
            st.r.err(format!("{name}: non-root node {oid:#x} flagged as root"));
        }
        if node.len() == 0 && !node.is_root() {
            st.r.err(format!("{name}: empty non-root node {oid:#x}"));
        }
        for i in 0..node.len() {
            let k = node.key(i)?;
            if i == 0 {
                if let Some(f) = expect_first {
                    if compare(kind, f, k) != Ordering::Equal {
                        st.r.err(format!("{name}: index key does not match first key of child {oid:#x}"));
                    }
                }
            }
            if node.is_leaf() {
                if let Some(p) = &st.prev {
                    if compare(kind, p, k) != Ordering::Less {
                        st.r.err(format!("{name}: keys out of order in node {oid:#x}"));
                    }
                }
                st.prev = Some(k.to_vec());
                st.keys += 1;
                on_rec(k, node.val(i)?);
            } else {
                if i > 0 && compare(kind, node.key(i - 1)?, k) != Ordering::Less {
                    st.r.err(format!("{name}: index keys out of order in node {oid:#x}"));
                }
                let child = node.child_oid(i)?;
                match resolve(child) {
                    Ok((cpa, cn, _)) => {
                        if cn.level + 1 != node.level {
                            st.r.err(format!("{name}: level mismatch below node {oid:#x}"));
                            continue;
                        }
                        rec(st, name, &cn, cpa, child, kind, storage, resolve, on_node, on_rec, Some(k), depth + 1)?;
                    }
                    Err(e) => st.r.err(format!("{name}: child {child:#x} of {oid:#x}: {e}")),
                }
            }
        }
        Ok(())
    }
    let mut st = St { r, keys: 0, nodes: 0, prev: None };
    rec(&mut st, name, &root, pa, root_oid, kind, storage, resolve, on_node, on_rec, None, 0)?;
    keys += st.keys;
    nodes += st.nodes;
    if info.key_count != keys {
        r.err(format!("{name}: info reports {} keys, found {keys}", info.key_count));
    }
    if info.node_count != nodes {
        r.err(format!("{name}: info reports {} nodes, found {nodes}", info.node_count));
    }
    Ok(())
}

fn phys_resolver<'a>(c: &'a Container, kind: TreeKind) -> impl Fn(u64) -> Result<(u64, Arc<Node>, Option<u64>)> + 'a {
    move |oid| Ok((oid, c.node_at(oid, kind)?, None))
}

/// Runs a full check of the container.
pub fn check(c: &Container) -> CheckReport {
    let mut r = CheckReport::default();
    if let Err(e) = check_inner(c, &mut r) {
        r.err(format!("check aborted: {e}"));
    }
    r
}

const T_META: u8 = 1;
const T_IP: u8 = 2;
const T_DATA: u8 = 3;
const T_FQ: u8 = 4;

fn check_inner(c: &Container, r: &mut CheckReport) -> Result<()> {
    let bs = c.block_size as u64;
    let sb = c.superblock().to_vec();
    let mut usage = Usage::new(c.block_count);
    usage.mark(r, 0, 1, T_META, "block zero");
    let desc_base = rd64(&sb, nx::XP_DESC_BASE);
    let desc_blocks = rd32(&sb, nx::XP_DESC_BLOCKS) as u64;
    let data_base = rd64(&sb, nx::XP_DATA_BASE);
    let data_blocks = rd32(&sb, nx::XP_DATA_BLOCKS) as u64;
    usage.mark(r, desc_base, desc_blocks, T_META, "checkpoint descriptor area");
    usage.mark(r, data_base, data_blocks, T_META, "checkpoint data area");
    let di = rd32(&sb, nx::XP_DATA_INDEX);
    let dl = rd32(&sb, nx::XP_DATA_LEN);
    if (di + dl) % rd32(&sb, nx::XP_DATA_BLOCKS) != rd32(&sb, nx::XP_DATA_NEXT) {
        r.err("checkpoint data next index is inconsistent");
    }
    let next_oid = rd64(&sb, nx::NEXT_OID);
    let xid = c.xid();
    for e in &c.ephemeral {
        if e.oid >= next_oid {
            r.err(format!("ephemeral object {:#x} uses an unassigned oid", e.oid));
        }
        let h = ObjHeader::parse(&e.data)?;
        if h.xid != xid {
            r.err(format!("ephemeral object {:#x} is not part of the latest transaction", e.oid));
        }
    }
    let total: u64 = c.ephemeral.iter().map(|e| e.size as u64 / bs).sum();
    if total != dl as u64 {
        r.err(format!("checkpoint data length {dl} does not match ephemeral objects ({total} blocks)"));
    }

    // --- space manager -----------------------------------------------------
    let sm = c.ephemeral(c.spaceman_oid()).ok_or_else(|| Error::Corrupt("space manager missing".into()))?;
    let d = &sm.data;
    let ip_base = rd64(d, sm::IP_BASE);
    let ip_count = rd64(d, sm::IP_BLOCK_COUNT);
    let ring_base = rd64(d, sm::IP_BM_BASE);
    let ring_len = rd32(d, sm::IP_BM_BLOCK_COUNT) as u64;
    let ip_bm_size = rd32(d, sm::IP_BM_SIZE_IN_BLOCKS) as usize;
    usage.mark(r, ring_base, ring_len, T_META, "internal pool bitmap ring");
    usage.mark(r, ip_base, ip_count, T_IP, "internal pool");
    let chunk_count = rd64(d, sm::DEV + sm::D_CHUNK_COUNT);
    let cib_count = rd32(d, sm::DEV + sm::D_CIB_COUNT) as u64;
    let cab_count = rd32(d, sm::DEV + sm::D_CAB_COUNT) as u64;
    if (chunk_count + cib_count + cab_count) * 3 != ip_count {
        r.warn("internal pool size is not 3x the space manager structures");
    }
    // IP ring list.
    let head = rd16(d, sm::IP_BM_FREE_HEAD) as u64;
    let tail = rd16(d, sm::IP_BM_FREE_TAIL) as u64;
    let next_off = rd32(d, sm::IP_BM_FREE_NEXT_OFFSET) as usize;
    let bm_off = rd32(d, sm::IP_BITMAP_OFFSET) as usize;
    {
        let mut cur = head;
        let mut count = 1;
        let mut ok = true;
        while cur != tail {
            if cur >= ring_len || count > ring_len {
                r.err("internal pool bitmap free list is broken");
                ok = false;
                break;
            }
            cur = u16_at(d, next_off + cur as usize * 2)? as u64;
            count += 1;
        }
        if ok {
            if u16_at(d, next_off + tail as usize * 2)? != SPACEMAN_IP_BM_INDEX_INVALID {
                r.err("internal pool free list tail is not terminated");
            }
            if ring_len - count != ip_bm_size as u64 {
                r.err(format!("internal pool ring has {} used slots, expected {ip_bm_size}", ring_len - count));
            }
            for i in 0..ip_bm_size {
                let s = u16_at(d, bm_off + i * 2)?;
                if u16_at(d, next_off + s as usize * 2)? != SPACEMAN_IP_BM_INDEX_INVALID {
                    r.err("current internal pool bitmap is on the free list");
                }
            }
        }
    }
    let mut ip_bm = vec![0u8; ip_bm_size * bs as usize];
    for i in 0..ip_bm_size {
        let s = u16_at(d, bm_off + i * 2)? as u64;
        let b = c.read_block_raw(ring_base + s)?;
        ip_bm[i * bs as usize..(i + 1) * bs as usize].copy_from_slice(&b);
    }
    let mut ip_used = vec![false; ip_count as usize];
    let mut mark_ip = |r: &mut CheckReport, pa: u64, what: &str| {
        if pa < ip_base || pa >= ip_base + ip_count {
            r.err(format!("{what} at {pa:#x} lies outside the internal pool"));
            return;
        }
        let i = (pa - ip_base) as usize;
        if ip_used[i] {
            r.err(format!("{what} at {pa:#x}: internal pool block used twice"));
        }
        ip_used[i] = true;
    };
    // Chunk info.
    let addr_off = rd32(d, sm::DEV + sm::D_ADDR_OFFSET) as usize;
    let mut cibs = Vec::new();
    if cab_count == 0 {
        for i in 0..cib_count as usize {
            cibs.push(u64_at(d, addr_off + i * 8)?);
        }
    } else {
        for i in 0..cab_count as usize {
            let pa = u64_at(d, addr_off + i * 8)?;
            mark_ip(r, pa, "CIB address block");
            let (_, b) = c.read_object(pa, "CIB address block", Some(OT_SPACEMAN_CAB))?;
            for j in 0..u32_at(&b, 0x24)? as usize {
                cibs.push(u64_at(&b, 0x28 + j * 8)?);
            }
        }
    }
    let mut main_bm: Vec<u8> = Vec::with_capacity((chunk_count * bs) as usize);
    let mut sum_free = 0u64;
    let bpc = rd32(d, sm::BLOCKS_PER_CHUNK) as u64;
    for (ci, &pa) in cibs.iter().enumerate() {
        mark_ip(r, pa, "chunk-info block");
        let (h, b) = c.read_object(pa, "chunk-info block", Some(OT_SPACEMAN_CIB))?;
        if h.xid > xid {
            r.err("chunk-info block is newer than the checkpoint");
        }
        if u32_at(&b, 0x20)? as usize != ci {
            r.err("chunk-info block index mismatch");
        }
        for s in 0..u32_at(&b, 0x24)? as usize {
            let e = 0x28 + s * 32;
            let blocks = rd32(&b, e + 16) as u64;
            let free = (rd32(&b, e + 20) & 0xFFFFF) as u64;
            let bm_pa = rd64(&b, e + 24);
            let bm = if bm_pa == 0 {
                vec![0u8; bs as usize]
            } else {
                mark_ip(r, bm_pa, "allocation bitmap");
                c.read_block_raw(bm_pa)?
            };
            let used = (0..blocks).filter(|&i| bm[(i / 8) as usize] & (1 << (i % 8)) != 0).count() as u64;
            if blocks - used != free {
                r.err(format!("chunk at {:#x}: free count {free} but bitmap shows {}", rd64(&b, e + 8), blocks - used));
            }
            for i in blocks..bpc {
                if bm[(i / 8) as usize] & (1 << (i % 8)) != 0 {
                    r.err("allocation bitmap has bits set beyond the chunk");
                    break;
                }
            }
            sum_free += free;
            main_bm.extend_from_slice(&bm);
        }
    }
    if sum_free != rd64(d, sm::DEV + sm::D_FREE_COUNT) {
        r.err(format!("space manager free count {} != sum of chunks {sum_free}", rd64(d, sm::DEV + sm::D_FREE_COUNT)));
    }
    // Free queues.
    for q in 0..3usize {
        let off = sm::FQ + q * sm::FQ_SIZE;
        let count = rd64(d, off);
        let tree = rd64(d, off + 8);
        if tree == 0 {
            continue;
        }
        let mut n = 0u64;
        let mut oldest = 0u64;
        let res = crate::btree::scan(&EphLoader(c), tree, TreeKind::FreeQueue, None, &mut |k, v| {
            let fx = rd64(k, 0);
            let pa = rd64(k, 8);
            let len = if v.len() >= 8 { rd64(v, 0) } else { 1 };
            n += len;
            if oldest == 0 || fx < oldest {
                oldest = fx;
            }
            if q == SFQ_IP {
                for b in pa..pa + len {
                    mark_ip(r, b, "free-queue (IP) block");
                }
            } else {
                usage.mark(r, pa, len, T_FQ, "free-queue block");
            }
            Ok(true)
        });
        if let Err(e) = res {
            r.err(format!("free queue {q}: {e}"));
        }
        if n != count {
            r.err(format!("free queue {q}: count {count} but records sum to {n}"));
        }
        if rd64(d, off + 16) != oldest {
            r.err(format!("free queue {q}: oldest xid is wrong"));
        }
    }
    // IP bitmap vs usage.
    for i in 0..ip_count as usize {
        let set = ip_bm[i / 8] & (1 << (i % 8)) != 0;
        if set != ip_used[i] {
            if set {
                r.warn(format!("internal pool block {:#x} allocated but unused (leak)", ip_base + i as u64));
            } else {
                r.err(format!("internal pool block {:#x} in use but marked free", ip_base + i as u64));
            }
        }
    }

    // --- container object map ----------------------------------------------
    let (_, nxo) = c.read_object(c.omap_oid(), "container object map", Some(OT_OMAP))?;
    usage.mark(r, c.omap_oid(), 1, T_META, "container object map");
    let nx_tree = rd64(&nxo, 0x30);
    let mut nx_map: HashMap<u64, Vec<(u64, u64)>> = HashMap::new();
    {
        let res = phys_resolver(c, TreeKind::Omap);
        let mut nodes = Vec::new();
        walk_tree(r, "container object map", nx_tree, TreeKind::Omap, OBJ_PHYSICAL, &res, &mut |_, pa, _| nodes.push(pa), &mut |k, v| {
            nx_map.entry(rd64(k, 0)).or_default().push((rd64(k, 8), rd64(v, 8)));
        })?;
        for pa in nodes {
            usage.mark(r, pa, 1, T_META, "container object map node");
        }
    }

    // --- volumes ---------------------------------------------------------------
    for (idx, oid) in c.volume_oids() {
        let vol = match c.volume(idx) {
            Ok(v) => v,
            Err(e) => {
                r.err(format!("volume #{idx}: {e}"));
                continue;
            }
        };
        if let Some(list) = nx_map.get(&oid) {
            for &(_, pa) in list {
                usage.mark(r, pa, 1, T_META, "volume superblock");
            }
        }
        check_volume(c, &vol, &mut usage, r)?;
    }
    // Other container-level references.
    let efi = rd64(&sb, 0x4f8);
    if efi != 0 {
        if let Ok((_, b)) = c.read_object(efi, "EFI jumpstart", None) {
            usage.mark(r, efi, 1, T_META, "EFI jumpstart");
            let n = u32_at(&b, 0x2c).unwrap_or(0) as usize;
            for i in 0..n.min(100) {
                let o = 0xb0 + i * 16;
                if let (Ok(s), Ok(l)) = (u64_at(&b, o), u64_at(&b, o + 8)) {
                    usage.mark(r, s, l, T_META, "EFI driver");
                }
            }
        }
    }
    for off in [nx::KEYLOCKER, 0x570] {
        let s = rd64(&sb, off);
        let l = rd64(&sb, off + 8);
        if s != 0 && l != 0 {
            usage.mark(r, s, l, T_META, "keybag");
        }
    }

    // --- compare with allocation bitmap -------------------------------------
    let mut leaks = 0u64;
    let mut first_leak = None;
    for b in 0..c.block_count {
        let set = main_bm.get((b / 8) as usize).map(|x| x & (1 << (b % 8)) != 0).unwrap_or(false);
        let used = usage.owner[b as usize] != U_FREE;
        if used && !set {
            r.err(format!("block {b:#x} is in use but marked free in the allocation bitmap"));
        } else if set && !used {
            leaks += 1;
            first_leak.get_or_insert(b);
        }
    }
    if leaks > 0 {
        r.warn(format!("{leaks} block(s) allocated but unreferenced (first at {:#x})", first_leak.unwrap()));
    }
    r.blocks_checked = c.block_count;
    Ok(())
}

#[derive(Default)]
struct InodeInfo {
    mode: u16,
    nlink_or_children: u32,
    parent: u64,
    name: Option<String>,
    private_id: u64,
    dstream: Option<Dstream>,
    flags: u64,
    links: u32,
    children: u32,
    first_link: Option<(u64, String)>,
    has_symlink_xattr: bool,
}

#[derive(Default)]
struct StreamInfo {
    refcnt: Option<u32>,
    refs: u32,
    bytes: u64,
    next: u64,
    is_xattr: bool,
    size: u64,
    alloced: u64,
}

fn check_volume(c: &Container, vol: &Volume, usage: &mut Usage, r: &mut CheckReport) -> Result<()> {
    let name = format!("volume \"{}\"", vol.name);
    let bs = c.block_size as u64;
    let mut vol_blocks = 0u64;
    usage.mark(r, vol.omap_oid, 1, T_META, &format!("{name} object map"));
    vol_blocks += 1;
    // Object map.
    let mut omap: HashMap<u64, Vec<(u64, u64, u32)>> = HashMap::new();
    {
        let res = phys_resolver(c, TreeKind::Omap);
        let mut nodes = Vec::new();
        walk_tree(r, &format!("{name} object map"), vol.omap_tree, TreeKind::Omap, OBJ_PHYSICAL, &res, &mut |_, pa, _| nodes.push(pa), &mut |k, v| {
            omap.entry(rd64(k, 0)).or_default().push((rd64(k, 8), rd64(v, 8), rd32(v, 0)));
        })?;
        for pa in nodes {
            usage.mark(r, pa, 1, T_META, &format!("{name} object map node"));
            vol_blocks += 1;
        }
    }
    if vol.encrypted() {
        r.warn(format!("{name} is encrypted; contents not checked"));
        // Still account the mapped blocks.
        for list in omap.values() {
            for &(_, pa, _) in list {
                usage.mark(r, pa, 1, T_META, &format!("{name} metadata"));
            }
        }
        return Ok(());
    }
    if vol.num_snapshots() > 0 {
        r.warn(format!("{name} has snapshots; snapshot contents are not checked in depth"));
    }
    let mut used_omap: HashSet<(u64, u64)> = HashSet::new();
    let xid = vol.read_xid;
    let fs_kind = vol.fs_kind();
    let hashed = vol.hashed();
    let ci = vol.case_insensitive();
    let mut inodes: HashMap<u64, InodeInfo> = HashMap::new();
    let mut streams: HashMap<u64, StreamInfo> = HashMap::new();
    let mut counts = [0u64; 4];
    let mut fs_nodes = Vec::new();
    let mut drecs: Vec<(u64, u64, String, u16)> = Vec::new();
    let mut xattr_streams: Vec<(u64, Dstream)> = Vec::new();
    let mut extents: Vec<(u64, FileExtent)> = Vec::new();
    {
        let omap_ref = &omap;
        let used = std::cell::RefCell::new(&mut used_omap);
        let res = |oid: u64| -> Result<(u64, Arc<Node>, Option<u64>)> {
            let list = omap_ref.get(&oid).ok_or_else(|| Error::Corrupt(format!("oid {oid:#x} not in object map")))?;
            let (ex, pa, fl) = list.iter().filter(|e| e.0 <= xid).max_by_key(|e| e.0).copied().ok_or_else(|| Error::Corrupt(format!("oid {oid:#x} has no mapping")))?;
            if fl & OMAP_VAL_DELETED != 0 {
                return Err(Error::Corrupt(format!("oid {oid:#x} is deleted")));
            }
            used.borrow_mut().insert((oid, ex));
            let n = c.node_at(pa, fs_kind)?;
            if n.hdr.xid != ex {
                return Err(Error::Corrupt(format!("node {oid:#x}: xid {} differs from object map ({ex})", n.hdr.xid)));
            }
            Ok((pa, n, Some(ex)))
        };
        let storage = if vol.root_tree_virtual { OBJ_VIRTUAL } else { OBJ_PHYSICAL };
        walk_tree(r, &format!("{name} catalog"), vol.root_tree, fs_kind, storage, &res, &mut |_, pa, _| fs_nodes.push(pa), &mut |k, v| {
            let (id, ty) = keys::fs_key_id_type(k);
            match ty {
                J_TYPE_INODE => match Inode::parse(id, v) {
                    Ok(i) => {
                        let e = inodes.entry(id).or_default();
                        e.mode = i.mode;
                        e.nlink_or_children = i.nchildren_or_nlink;
                        e.parent = i.parent_id;
                        e.name = i.name();
                        e.private_id = i.private_id;
                        e.dstream = i.dstream();
                        e.flags = i.internal_flags;
                        if let Some(ds) = e.dstream {
                            let s = streams.entry(i.private_id).or_default();
                            s.refs += 1;
                            s.size = ds.size;
                            s.alloced = ds.alloced_size;
                        }
                    }
                    Err(err) => drecs.push((0, id, format!("<bad inode: {err}>"), 0xffff)),
                },
                J_TYPE_DIR_REC => match DirEntry::parse(k, v, hashed) {
                    Ok(e) => {
                        if hashed {
                            let disk = rd32(k, 8) & !DREC_LEN_MASK;
                            if disk != keys::name_hash(&e.name, ci) {
                                drecs.push((0, id, format!("<bad hash for {:?}>", e.name), 0xfffe));
                            }
                        }
                        let dt = e.dtype();
                        drecs.push((id, e.file_id, e.name, dt));
                    }
                    Err(err) => drecs.push((0, id, format!("<bad dentry: {err}>"), 0xffff)),
                },
                J_TYPE_XATTR => {
                    if let Ok(x) = Xattr::parse(k, v) {
                        if x.name == XATTR_SYMLINK {
                            inodes.entry(id).or_default().has_symlink_xattr = true;
                        }
                        if let XattrData::Stream { obj_id, dstream } = x.data {
                            xattr_streams.push((obj_id, dstream));
                        }
                    }
                }
                J_TYPE_DSTREAM_ID => {
                    streams.entry(id).or_default().refcnt = u32_at(v, 0).ok();
                }
                J_TYPE_FILE_EXTENT => {
                    if let Ok(fe) = FileExtent::parse(k, v) {
                        extents.push((id, fe));
                    }
                }
                _ => {}
            }
        })?;
    }
    for pa in fs_nodes {
        usage.mark(r, pa, 1, T_META, &format!("{name} catalog node"));
        vol_blocks += 1;
    }
    for (id, ds) in xattr_streams {
        let s = streams.entry(id).or_default();
        s.is_xattr = true;
        s.refs += 1;
        s.size = ds.size;
        s.alloced = ds.alloced_size;
    }
    // Extents per stream must be contiguous from 0.
    let mut data_ranges: Vec<(u64, u64, u64)> = Vec::new();
    for (id, fe) in &extents {
        let s = streams.entry(*id).or_default();
        if fe.logical != s.next {
            r.err(format!("{name}: stream {id} extents are not contiguous at {:#x}", fe.logical));
        }
        if fe.length % bs != 0 || fe.length == 0 {
            r.err(format!("{name}: stream {id} has an extent with invalid length"));
        }
        s.next = fe.logical + fe.length;
        s.bytes += fe.length;
        if fe.phys != 0 {
            data_ranges.push((fe.phys, fe.length / bs, *id));
        }
    }
    for (id, s) in &streams {
        if s.refs == 0 && s.bytes > 0 {
            r.err(format!("{name}: stream {id} has extents but no owner"));
            continue;
        }
        if !s.is_xattr && s.refs > 0 && s.refcnt != Some(s.refs) && !(s.refcnt.is_none() && s.bytes == 0 && s.size == 0) {
            r.err(format!("{name}: stream {id} reference count {:?} but {} references", s.refcnt, s.refs));
        }
        if s.refs > 0 && s.bytes != s.alloced {
            r.err(format!("{name}: stream {id} allocated size {} but extents cover {}", s.alloced, s.bytes));
        }
        if s.refs > 0 && s.size > s.bytes {
            r.err(format!("{name}: stream {id} size {} exceeds its extents ({})", s.size, s.bytes));
        }
    }
    // Directory entries vs inodes.
    for (parent, child, ename, dtype) in &drecs {
        if *dtype == 0xffff || *dtype == 0xfffe {
            r.err(format!("{name}: record for {child}: {ename}"));
            continue;
        }
        let Some(ci_) = inodes.get_mut(child) else {
            r.err(format!("{name}: entry {ename:?} points to missing inode {child}"));
            continue;
        };
        if ci_.mode != 0 && dtype_from_mode(ci_.mode) != *dtype {
            r.err(format!("{name}: entry {ename:?} type does not match inode mode"));
        }
        if *parent != PURGEABLE_DIR_INO {
            ci_.links += 1;
        }
        if ci_.first_link.is_none() {
            ci_.first_link = Some((*parent, ename.clone()));
        }
        if *parent != ROOT_DIR_PARENT {
            match inodes.get_mut(parent) {
                Some(p) => p.children += 1,
                None => r.err(format!("{name}: entry {ename:?} has missing parent {parent}")),
            }
        }
    }
    let next_id = vol.next_obj_id();
    for (id, i) in &inodes {
        r.inodes_checked += 1;
        if i.mode == 0 {
            r.err(format!("{name}: inode {id} referenced but missing"));
            continue;
        }
        if *id >= next_id {
            r.err(format!("{name}: inode {id} >= next object id {next_id}"));
        }
        let ft = i.mode & S_IFMT;
        match ft {
            S_IFREG => counts[0] += 1,
            S_IFDIR if *id >= MIN_USER_INO => counts[1] += 1,
            S_IFLNK => counts[2] += 1,
            S_IFDIR => {}
            _ => counts[3] += 1,
        }
        if i.first_link.as_ref().map(|f| f.0) == Some(PRIV_DIR_INO) {
            continue; // orphans
        }
        if i.links == 0 {
            r.err(format!("{name}: inode {id} has no directory entries"));
        }
        if ft == S_IFDIR {
            if i.nlink_or_children != i.children {
                r.err(format!("{name}: directory {id} child count {} but {} entries", i.nlink_or_children, i.children));
            }
        } else if i.nlink_or_children != i.links {
            r.err(format!("{name}: inode {id} link count {} but {} entries", i.nlink_or_children, i.links));
        }
        if i.links == 1 {
            if let (Some(n), Some((p, en))) = (&i.name, &i.first_link) {
                if n != en || *p != i.parent {
                    r.err(format!("{name}: inode {id} name/parent ({n:?}/{}) differ from its entry ({en:?}/{p})", i.parent));
                }
            } else if i.name.is_none() {
                r.err(format!("{name}: inode {id} has no name xfield"));
            }
        }
        if (ft == S_IFLNK) != i.has_symlink_xattr {
            r.err(format!("{name}: inode {id} symlink target inconsistency"));
        }
        if i.dstream.is_some() && ft != S_IFREG {
            r.err(format!("{name}: inode {id} has a data stream but is not a regular file"));
        }
        let _ = i.private_id;
        let _ = i.flags;
    }
    let sbc = [vol.num_files(), vol.num_directories(), vol.num_symlinks(), rd64(&vol.sb, apsb::NUM_OTHER)];
    let labels = ["files", "directories", "symlinks", "other objects"];
    for k in 0..4 {
        if sbc[k] != counts[k] {
            r.err(format!("{name}: superblock counts {} {}, found {}", sbc[k], labels[k], counts[k]));
        }
    }
    // Extent references.
    let ext_root = rd64(&vol.sb, apsb::EXTENTREF_TREE_OID);
    let mut pexts: Vec<PhysExtent> = Vec::new();
    {
        let res = phys_resolver(c, TreeKind::ExtentRef);
        let mut nodes = Vec::new();
        walk_tree(r, &format!("{name} extent references"), ext_root, TreeKind::ExtentRef, OBJ_PHYSICAL, &res, &mut |_, pa, _| nodes.push(pa), &mut |k, v| {
            if let Ok(p) = PhysExtent::parse(k, v) {
                pexts.push(p);
            }
        })?;
        for pa in nodes {
            usage.mark(r, pa, 1, T_META, &format!("{name} extent reference node"));
            vol_blocks += 1;
        }
    }
    let mut prev_end = 0;
    for p in &pexts {
        if p.paddr < prev_end {
            r.err(format!("{name}: overlapping physical extent records at {:#x}", p.paddr));
        }
        prev_end = p.paddr + p.len;
        if p.refcnt == 0 {
            r.err(format!("{name}: physical extent {:#x} has zero references", p.paddr));
        }
        if p.kind == KIND_NEW {
            usage.mark(r, p.paddr, p.len, T_DATA, &format!("{name} file data"));
            vol_blocks += p.len;
        }
    }
    // Every file extent must be covered by physical extent records with
    // enough references.
    let mut refs: HashMap<u64, u32> = HashMap::new();
    for (pa, n, _) in &data_ranges {
        for b in *pa..pa + n {
            *refs.entry(b).or_default() += 1;
        }
    }
    let mut covered: HashMap<u64, u32> = HashMap::new();
    for p in &pexts {
        for b in p.paddr..p.paddr + p.len {
            covered.insert(b, p.refcnt);
        }
    }
    if vol.num_snapshots() == 0 {
        for (b, n) in &refs {
            match covered.get(b) {
                None => {
                    r.err(format!("{name}: data block {b:#x} has no extent reference"));
                    break;
                }
                Some(rc) if rc != n => {
                    r.err(format!("{name}: data block {b:#x} has {n} references but refcount {rc}"));
                    break;
                }
                _ => {}
            }
        }
        for b in covered.keys() {
            if !refs.contains_key(b) {
                r.err(format!("{name}: extent reference for block {b:#x} without any file extent"));
                break;
            }
        }
    }
    // Snapshot metadata tree.
    let snap_root = rd64(&vol.sb, apsb::SNAP_META_TREE_OID);
    if snap_root != 0 {
        let res = phys_resolver(c, TreeKind::SnapMeta);
        let mut nodes = Vec::new();
        walk_tree(r, &format!("{name} snapshot metadata"), snap_root, TreeKind::SnapMeta, OBJ_PHYSICAL, &res, &mut |_, pa, _| nodes.push(pa), &mut |_, _| {})?;
        for pa in nodes {
            usage.mark(r, pa, 1, T_META, &format!("{name} snapshot metadata node"));
            vol_blocks += 1;
        }
    }
    // Unused object map entries.
    if vol.num_snapshots() == 0 {
        for (oid, list) in &omap {
            for &(x, pa, _) in list {
                if !used_omap.contains(&(*oid, x)) {
                    r.err(format!("{name}: object map entry {oid:#x}/{x} (block {pa:#x}) is never used"));
                }
            }
        }
        if vol.alloc_blocks() != vol_blocks {
            r.err(format!("{name}: superblock reports {} allocated blocks, found {vol_blocks}", vol.alloc_blocks()));
        }
    } else {
        // Account snapshot blocks so they do not show up as leaks.
        for list in omap.values() {
            for &(x, pa, _) in list {
                if !used_omap.contains(&(0, x)) && pa < c.block_count && usage.owner[pa as usize] == U_FREE {
                    usage.owner[pa as usize] = T_META;
                }
            }
        }
    }
    let _ = checksum::fletcher64;
    Ok(())
}
