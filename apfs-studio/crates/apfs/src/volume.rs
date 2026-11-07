//! Volume (APSB) access: directory listing, path resolution, file reading.

use crate::btree::{self, NodeLoader};
use crate::bytes::*;
use crate::compress;
use crate::container::{Container, PhysLoader, VirtLoader};
use crate::error::{Error, Result};
use crate::format::*;
use crate::keys::{self, TreeKind};
use crate::records::*;
use std::sync::Arc;

#[derive(Debug, Clone)]
pub struct Snapshot {
    pub name: String,
    pub xid: u64,
    pub create_time: u64,
    pub sblock_oid: u64,
}

/// A mounted (read-side) view of a volume, optionally at a snapshot.
#[derive(Debug, Clone)]
pub struct Volume {
    pub index: usize,
    pub oid: u64,
    pub paddr: u64,
    pub sb: Vec<u8>,
    pub name: String,
    pub uuid: uuid::Uuid,
    pub role: u16,
    pub omap_oid: u64,
    pub omap_tree: u64,
    pub omap_flags: u32,
    pub omap_snap_count: u32,
    pub root_tree: u64,
    pub root_tree_virtual: bool,
    pub fext_tree: u64,
    /// xid at which virtual objects are resolved.
    pub read_xid: u64,
    pub snapshot: Option<Snapshot>,
}

impl Volume {
    pub fn open(c: &Container, nx_omap_tree: u64, oid: u64, index: usize) -> Result<Self> {
        let v = c
            .omap_lookup(nx_omap_tree, oid, c.xid())?
            .ok_or_else(|| Error::Corrupt(format!("volume superblock {oid:#x} missing from container object map")))?;
        let (h, sb) = c.read_object(v.paddr, "volume superblock", Some(OT_FS))?;
        if h.oid != oid {
            return Err(Error::Corrupt("volume superblock object id mismatch".into()));
        }
        Self::from_sb(c, sb, oid, v.paddr, index, c.xid(), None)
    }

    fn from_sb(c: &Container, sb: Vec<u8>, oid: u64, paddr: u64, index: usize, read_xid: u64, snapshot: Option<Snapshot>) -> Result<Self> {
        if u32_at(&sb, apsb::MAGIC)? != APFS_MAGIC {
            return Err(Error::Corrupt("volume superblock has a bad magic".into()));
        }
        let name = cstr(slice(&sb, apsb::VOLNAME, 256)?);
        let uuid = uuid::Uuid::from_slice(slice(&sb, apsb::UUID, 16)?).unwrap_or_default();
        let role = u16_at(&sb, apsb::ROLE)?;
        let omap_oid = u64_at(&sb, apsb::OMAP_OID)?;
        let (_, om) = c.read_object(omap_oid, "volume object map", Some(OT_OMAP))?;
        let omap_flags = u32_at(&om, 0x20)?;
        let omap_snap_count = u32_at(&om, 0x24)?;
        let omap_tree = u64_at(&om, 0x30)?;
        let root_tree_type = u32_at(&sb, apsb::ROOT_TREE_TYPE)?;
        let root_tree = u64_at(&sb, apsb::ROOT_TREE_OID)?;
        let fext_tree = u64_at(&sb, apsb::FEXT_TREE_OID)?;
        Ok(Self {
            index,
            oid,
            paddr,
            sb,
            name,
            uuid,
            role,
            omap_oid,
            omap_tree,
            omap_flags,
            omap_snap_count,
            root_tree,
            root_tree_virtual: root_tree_type & OBJ_STORAGETYPE_MASK == OBJ_VIRTUAL,
            fext_tree,
            read_xid,
            snapshot,
        })
    }

    // --- superblock fields --------------------------------------------------

    pub fn incompat(&self) -> u64 {
        rd64(&self.sb, apsb::INCOMPAT)
    }
    pub fn features(&self) -> u64 {
        rd64(&self.sb, apsb::FEATURES)
    }
    pub fn fs_flags(&self) -> u64 {
        rd64(&self.sb, apsb::FS_FLAGS)
    }
    pub fn case_insensitive(&self) -> bool {
        self.incompat() & APFS_INCOMPAT_CASE_INSENSITIVE != 0
    }
    pub fn normalization_insensitive(&self) -> bool {
        self.incompat() & APFS_INCOMPAT_NORMALIZATION_INSENSITIVE != 0
    }
    /// Directory records use hashed keys.
    pub fn hashed(&self) -> bool {
        self.case_insensitive() || self.normalization_insensitive()
    }
    pub fn encrypted(&self) -> bool {
        self.fs_flags() & APFS_FS_UNENCRYPTED == 0
    }
    pub fn sealed(&self) -> bool {
        self.incompat() & APFS_INCOMPAT_SEALED_VOLUME != 0
    }
    pub fn alloc_blocks(&self) -> u64 {
        rd64(&self.sb, apsb::ALLOC_COUNT)
    }
    pub fn reserve_blocks(&self) -> u64 {
        rd64(&self.sb, apsb::RESERVE_BLOCKS)
    }
    pub fn quota_blocks(&self) -> u64 {
        rd64(&self.sb, apsb::QUOTA_BLOCKS)
    }
    pub fn num_files(&self) -> u64 {
        rd64(&self.sb, apsb::NUM_FILES)
    }
    pub fn num_directories(&self) -> u64 {
        rd64(&self.sb, apsb::NUM_DIRECTORIES)
    }
    pub fn num_symlinks(&self) -> u64 {
        rd64(&self.sb, apsb::NUM_SYMLINKS)
    }
    pub fn num_snapshots(&self) -> u64 {
        rd64(&self.sb, apsb::NUM_SNAPSHOTS)
    }
    pub fn next_obj_id(&self) -> u64 {
        rd64(&self.sb, apsb::NEXT_OBJ_ID)
    }
    pub fn last_mod_time(&self) -> u64 {
        rd64(&self.sb, apsb::LAST_MOD_TIME)
    }
    pub fn formatted_by(&self) -> String {
        cstr(&self.sb[apsb::FORMATTED_BY..apsb::FORMATTED_BY + 32])
    }
    pub fn modified_by(&self) -> Vec<(String, u64, u64)> {
        (0..8)
            .map(|i| {
                let o = apsb::MODIFIED_BY + i * apsb::MODIFIED_BY_SIZE;
                (cstr(&self.sb[o..o + 32]), rd64(&self.sb, o + 32), rd64(&self.sb, o + 40))
            })
            .filter(|m| !m.0.is_empty())
            .collect()
    }
    pub fn role_name(&self) -> &'static str {
        role_name(self.role)
    }
    pub fn fs_kind(&self) -> TreeKind {
        TreeKind::Fs { hashed: self.hashed() }
    }

    /// Reasons why reading is impossible, if any.
    pub fn read_blocker(&self) -> Option<String> {
        if self.encrypted() {
            return Some("volume is encrypted (FileVault); unlocking is not supported".into());
        }
        None
    }

    /// Reasons why writing must be refused, if any.
    pub fn write_blocker(&self) -> Option<String> {
        if self.snapshot.is_some() {
            return Some("snapshots are read-only".into());
        }
        if let Some(r) = self.read_blocker() {
            return Some(r);
        }
        if self.sealed() {
            return Some("sealed system volumes are read-only".into());
        }
        if self.num_snapshots() != 0 || self.omap_snap_count != 0 {
            return Some("volume has snapshots (e.g. Time Machine); writing would corrupt them".into());
        }
        if self.omap_flags & OMAP_MANUALLY_MANAGED != 0 {
            return Some("unexpected manually-managed volume object map".into());
        }
        if !self.root_tree_virtual {
            return Some("physical file-system tree".into());
        }
        let known = APFS_INCOMPAT_CASE_INSENSITIVE | APFS_INCOMPAT_NORMALIZATION_INSENSITIVE | APFS_INCOMPAT_EXTENT_PREALLOC_FLAG;
        let other = self.incompat() & !known;
        if other != 0 {
            return Some(format!("volume uses incompatible features {other:#x}"));
        }
        if rd64(&self.sb, apsb::RO_COMPAT) != 0 {
            return Some("volume has read-only-compatible features".into());
        }
        if rd64(&self.sb, apsb::REVERT_TO_XID) != 0 || rd64(&self.sb, apsb::REVERT_TO_SBLOCK) != 0 {
            return Some("a snapshot revert is pending".into());
        }
        if rd64(&self.sb, apsb::ER_STATE_OID) != 0 {
            return Some("encryption rolling is in progress".into());
        }
        if self.fext_tree != 0 {
            return Some("volume has a file extent tree".into());
        }
        None
    }

    // --- tree access ---------------------------------------------------------

    pub fn loader<'a>(&self, c: &'a Container) -> Box<dyn NodeLoader + 'a> {
        if self.root_tree_virtual {
            Box::new(VirtLoader { c, omap_tree: self.omap_tree, xid: self.read_xid })
        } else {
            Box::new(PhysLoader(c))
        }
    }

    fn check_readable(&self) -> Result<()> {
        match self.read_blocker() {
            Some(r) => Err(Error::Unsupported(r)),
            None => Ok(()),
        }
    }

    pub fn fs_lookup(&self, c: &Container, key: &[u8]) -> Result<Option<Vec<u8>>> {
        self.check_readable()?;
        let l = self.loader(c);
        btree::lookup(l.as_ref(), self.root_tree, self.fs_kind(), key)
    }

    /// Scans all fs records of (id, type).
    pub fn fs_scan(&self, c: &Container, id: u64, ty: u8, start: Option<Vec<u8>>, f: &mut dyn FnMut(&[u8], &[u8]) -> Result<bool>) -> Result<()> {
        self.check_readable()?;
        let l = self.loader(c);
        let start = start.unwrap_or_else(|| keys::simple_key(id, ty));
        btree::scan(l.as_ref(), self.root_tree, self.fs_kind(), Some(&start), &mut |k, v| {
            let (kid, kty) = keys::fs_key_id_type(k);
            if kid != id || kty != ty {
                return Ok(false);
            }
            f(k, v)
        })
    }

    pub fn inode(&self, c: &Container, ino: u64) -> Result<Inode> {
        let v = self
            .fs_lookup(c, &keys::simple_key(ino, J_TYPE_INODE))?
            .ok_or_else(|| Error::NotFound(format!("inode {ino}")))?;
        Inode::parse(ino, &v)
    }

    pub fn list_dir(&self, c: &Container, ino: u64) -> Result<Vec<DirEntry>> {
        let hashed = self.hashed();
        let mut out = Vec::new();
        self.fs_scan(c, ino, J_TYPE_DIR_REC, Some(keys::drec_scan_start(ino, hashed)), &mut |k, v| {
            out.push(DirEntry::parse(k, v, hashed)?);
            Ok(true)
        })?;
        Ok(out)
    }

    /// Looks up a child by name using the volume's case/normalisation rules.
    pub fn lookup_child(&self, c: &Container, parent: u64, name: &str) -> Result<Option<DirEntry>> {
        let hashed = self.hashed();
        let ci = self.case_insensitive();
        let key = keys::drec_key(parent, name, hashed, ci);
        if let Some(v) = self.fs_lookup(c, &key)? {
            return Ok(Some(DirEntry::parse(&key, &v, hashed)?));
        }
        // Fall back to a linear scan: the stored name may differ in
        // normalisation or case but still be considered equal.
        let ni = self.normalization_insensitive() || hashed;
        let mut found = None;
        if hashed {
            let h = keys::name_hash(name, ci);
            let mut start = keys::simple_key(parent, J_TYPE_DIR_REC);
            start.extend_from_slice(&h.to_le_bytes());
            self.fs_scan(c, parent, J_TYPE_DIR_REC, Some(start), &mut |k, v| {
                if rd32(k, 8) & !DREC_LEN_MASK != h {
                    return Ok(false);
                }
                let e = DirEntry::parse(k, v, hashed)?;
                if keys::names_equal(&e.name, name, ci, ni) {
                    found = Some(e);
                    return Ok(false);
                }
                Ok(true)
            })?;
            if found.is_some() {
                return Ok(found);
            }
        }
        for e in self.list_dir(c, parent)? {
            if keys::names_equal(&e.name, name, ci, ni) {
                return Ok(Some(e));
            }
        }
        Ok(None)
    }

    /// Resolves an absolute path ("/a/b") to an inode number.
    pub fn resolve(&self, c: &Container, path: &str) -> Result<u64> {
        let mut ino = ROOT_DIR_INO;
        for comp in path.split('/').filter(|s| !s.is_empty() && *s != ".") {
            let e = self
                .lookup_child(c, ino, comp)?
                .ok_or_else(|| Error::NotFound(path.to_string()))?;
            ino = e.file_id;
        }
        Ok(ino)
    }

    pub fn xattrs(&self, c: &Container, ino: u64) -> Result<Vec<Xattr>> {
        let mut out = Vec::new();
        self.fs_scan(c, ino, J_TYPE_XATTR, None, &mut |k, v| {
            out.push(Xattr::parse(k, v)?);
            Ok(true)
        })?;
        Ok(out)
    }

    pub fn xattr(&self, c: &Container, ino: u64, name: &str) -> Result<Option<Xattr>> {
        match self.fs_lookup(c, &keys::xattr_key(ino, name))? {
            Some(v) => Ok(Some(Xattr::parse(&keys::xattr_key(ino, name), &v)?)),
            None => Ok(None),
        }
    }

    /// File extents of a data stream, sorted by logical address.
    pub fn extents(&self, c: &Container, stream_id: u64) -> Result<Vec<FileExtent>> {
        let mut out = Vec::new();
        self.fs_scan(c, stream_id, J_TYPE_FILE_EXTENT, None, &mut |k, v| {
            out.push(FileExtent::parse(k, v)?);
            Ok(true)
        })?;
        if out.is_empty() && self.fext_tree != 0 {
            let l = PhysLoader(c);
            let mut start = stream_id.to_le_bytes().to_vec();
            start.extend_from_slice(&0u64.to_le_bytes());
            btree::scan(&l, self.fext_tree, TreeKind::Fext, Some(&start), &mut |k, v| {
                if rd64(k, 0) != stream_id {
                    return Ok(false);
                }
                out.push(FileExtent::parse_fext(k, v)?);
                Ok(true)
            })?;
        }
        Ok(out)
    }

    pub fn read_xattr(&self, c: &Container, x: &Xattr) -> Result<Vec<u8>> {
        match &x.data {
            XattrData::Embedded(v) => Ok(v.clone()),
            XattrData::Stream { obj_id, dstream } => {
                if dstream.size > 64 << 20 {
                    return Err(Error::Unsupported("reading extended attributes larger than 64 MiB at once".into()));
                }
                let s = Stream::new(self, c, *obj_id, dstream.size)?;
                let mut buf = vec![0u8; dstream.size as usize];
                s.read_exact_at(c, 0, &mut buf)?;
                Ok(buf)
            }
        }
    }

    pub fn readlink(&self, c: &Container, ino: u64) -> Result<String> {
        let x = self
            .xattr(c, ino, XATTR_SYMLINK)?
            .ok_or_else(|| Error::Corrupt(format!("symlink {ino} has no target")))?;
        Ok(cstr(&self.read_xattr(c, &x)?))
    }

    /// Opens a file for reading (handles compression transparently).
    pub fn open_file(&self, c: &Container, inode: &Inode) -> Result<FileReader> {
        if inode.is_dir() {
            return Err(Error::IsADirectory(format!("inode {}", inode.id)));
        }
        if inode.is_compressed() {
            if let Some(x) = self.xattr(c, inode.id, XATTR_DECMPFS)? {
                let raw = self.read_xattr(c, &x)?;
                let h = compress::parse_header(&raw)?;
                if compress::uses_resource_fork(h.algo) {
                    let rx = self
                        .xattr(c, inode.id, XATTR_RSRC_FORK)?
                        .ok_or_else(|| Error::Corrupt("compressed file lacks its resource fork".into()))?;
                    let (fork_id, fork_len, inline) = match &rx.data {
                        XattrData::Stream { obj_id, dstream } => (*obj_id, dstream.size, None),
                        XattrData::Embedded(v) => (0, v.len() as u64, Some(v.clone())),
                    };
                    let fork = match inline {
                        Some(v) => ForkSource::Inline(Arc::new(v)),
                        None => ForkSource::Stream(Stream::new(self, c, fork_id, fork_len)?),
                    };
                    let head = |off: u64, len: usize| -> Result<Vec<u8>> {
                        if off + len as u64 > fork_len {
                            return Err(Error::Corrupt("resource fork too short".into()));
                        }
                        let mut b = vec![0u8; len];
                        fork.read(c, off, &mut b)?;
                        Ok(b)
                    };
                    let table = compress::fork_block_table(h.algo, &head, fork_len, h.size)?;
                    return Ok(FileReader {
                        size: h.size,
                        kind: ReaderKind::Fork { algo: h.algo, fork, table, cache: None },
                    });
                }
                if h.size > compress::MAX_UNIT as u64 {
                    return Err(Error::Corrupt(format!("inline compressed file claims {} bytes", h.size)));
                }
                let data = if h.algo == compress::CMP_SPARSE {
                    vec![0u8; h.size as usize]
                } else {
                    compress::decode_unit(h.algo, &h.payload, h.size as usize)?
                };
                return Ok(FileReader { size: h.size, kind: ReaderKind::Inline(Arc::new(data)) });
            }
        }
        let size = inode.dstream().map(|d| d.size).unwrap_or(0);
        if size == 0 {
            return Ok(FileReader { size: 0, kind: ReaderKind::Inline(Arc::new(Vec::new())) });
        }
        Ok(FileReader { size, kind: ReaderKind::Stream(Stream::new(self, c, inode.private_id, size)?) })
    }

    /// User-visible size of a file, reading the decmpfs header for
    /// compressed files that do not record their uncompressed size.
    pub fn file_size(&self, c: &Container, inode: &Inode) -> u64 {
        if inode.is_compressed() && inode.internal_flags & INODE_HAS_UNCOMPRESSED_SIZE == 0 {
            if let Ok(Some(x)) = self.xattr(c, inode.id, XATTR_DECMPFS) {
                if let Ok(raw) = self.read_xattr(c, &x) {
                    if let Ok(h) = compress::parse_header(&raw) {
                        return h.size;
                    }
                }
            }
        }
        inode.size()
    }

    /// Compression algorithm name for compressed files.
    pub fn compression(&self, c: &Container, inode: &Inode) -> Option<&'static str> {
        if !inode.is_compressed() {
            return None;
        }
        let x = self.xattr(c, inode.id, XATTR_DECMPFS).ok()??;
        let raw = self.read_xattr(c, &x).ok()?;
        compress::parse_header(&raw).ok().map(|h| compress::algo_name(h.algo))
    }

    // --- snapshots -------------------------------------------------------------

    pub fn snapshots(&self, c: &Container) -> Result<Vec<Snapshot>> {
        let tree = u64_at(&self.sb, apsb::SNAP_META_TREE_OID)?;
        if tree == 0 {
            return Ok(Vec::new());
        }
        let l = PhysLoader(c);
        let mut meta = Vec::new();
        btree::scan(&l, tree, TreeKind::SnapMeta, None, &mut |k, v| {
            let (id, ty) = keys::fs_key_id_type(k);
            if ty == J_TYPE_SNAP_METADATA && v.len() >= 50 {
                let nlen = rd16(v, 48) as usize;
                let name = cstr(slice(v, 50, nlen)?);
                meta.push(Snapshot { name, xid: id, create_time: rd64(v, 16), sblock_oid: rd64(v, 8) });
            }
            Ok(true)
        })?;
        Ok(meta)
    }

    pub fn open_snapshot(&self, c: &Container, snap: &Snapshot) -> Result<Volume> {
        let (_, sb) = c.read_object(snap.sblock_oid, "snapshot volume superblock", Some(OT_FS))?;
        let mut v = Self::from_sb(c, sb, self.oid, snap.sblock_oid, self.index, snap.xid, Some(snap.clone()))?;
        // Snapshots share the live volume's object map.
        v.omap_tree = self.omap_tree;
        Ok(v)
    }
}

/// Reader for a raw data stream (list of extents).
#[derive(Clone)]
pub struct Stream {
    pub id: u64,
    pub size: u64,
    pub extents: Arc<Vec<FileExtent>>,
    block_size: u64,
}

impl Stream {
    pub fn new(v: &Volume, c: &Container, id: u64, size: u64) -> Result<Self> {
        let ex = v.extents(c, id)?;
        let mut expect = 0u64;
        for e in &ex {
            if e.logical != expect {
                return Err(Error::Corrupt(format!("data stream {id}: extents are not contiguous at {}", e.logical)));
            }
            if e.flags & 0x01 != 0 {
                return Err(Error::Unsupported("encrypted file extents".into()));
            }
            expect = expect.checked_add(e.length).ok_or_else(|| Error::Corrupt("extent overflow".into()))?;
        }
        Ok(Self { id, size, extents: Arc::new(ex), block_size: c.block_size as u64 })
    }

    /// Reads up to buf.len() bytes at `off`; returns bytes read (0 at EOF).
    pub fn read_at(&self, c: &Container, off: u64, buf: &mut [u8]) -> Result<usize> {
        if off >= self.size || buf.is_empty() {
            return Ok(0);
        }
        let want = (buf.len() as u64).min(self.size - off) as usize;
        let mut done = 0usize;
        while done < want {
            let pos = off + done as u64;
            let idx = self.extents.partition_point(|e| e.logical + e.length <= pos);
            let remain = want - done;
            if idx >= self.extents.len() {
                // Beyond the last extent but within size: sparse tail.
                buf[done..want].fill(0);
                break;
            }
            let e = self.extents[idx];
            let in_ext = pos - e.logical;
            let n = ((e.length - in_ext) as usize).min(remain);
            if e.phys == 0 || e.flags & 0x02 != 0 {
                buf[done..done + n].fill(0);
            } else {
                let byte = e.phys * self.block_size + in_ext;
                let start_blk = byte / self.block_size;
                let in_blk = (byte % self.block_size) as usize;
                // Read whole blocks then copy the window (block-aligned device I/O).
                let span = (in_blk + n).div_ceil(self.block_size as usize) * self.block_size as usize;
                if in_blk == 0 && n % self.block_size as usize == 0 {
                    c.read_blocks(start_blk, &mut buf[done..done + n])?;
                } else {
                    let mut tmp = vec![0u8; span];
                    c.read_blocks(start_blk, &mut tmp)?;
                    buf[done..done + n].copy_from_slice(&tmp[in_blk..in_blk + n]);
                }
            }
            done += n;
        }
        Ok(want)
    }

    pub fn read_exact_at(&self, c: &Container, off: u64, buf: &mut [u8]) -> Result<()> {
        let n = self.read_at(c, off, buf)?;
        if n != buf.len() {
            return Err(Error::Corrupt("unexpected end of data stream".into()));
        }
        Ok(())
    }
}

#[derive(Clone)]
pub enum ForkSource {
    Inline(Arc<Vec<u8>>),
    Stream(Stream),
}

impl ForkSource {
    fn read(&self, c: &Container, off: u64, buf: &mut [u8]) -> Result<()> {
        match self {
            ForkSource::Inline(v) => {
                let s = slice(v, off as usize, buf.len())?;
                buf.copy_from_slice(s);
                Ok(())
            }
            ForkSource::Stream(s) => s.read_exact_at(c, off, buf),
        }
    }
}

#[derive(Clone)]
pub enum ReaderKind {
    Inline(Arc<Vec<u8>>),
    Stream(Stream),
    Fork { algo: u32, fork: ForkSource, table: Vec<(u64, u32)>, cache: Option<(usize, Arc<Vec<u8>>)> },
}

/// Random-access reader over a file's logical (decompressed) content.
#[derive(Clone)]
pub struct FileReader {
    pub size: u64,
    kind: ReaderKind,
}

impl FileReader {
    pub fn is_compressed(&self) -> bool {
        !matches!(self.kind, ReaderKind::Stream(_))
    }

    pub fn read_at(&mut self, c: &Container, off: u64, buf: &mut [u8]) -> Result<usize> {
        if off >= self.size {
            return Ok(0);
        }
        let want = (buf.len() as u64).min(self.size - off) as usize;
        let size = self.size;
        match &mut self.kind {
            ReaderKind::Inline(d) => {
                let s = off as usize;
                let avail = d.len().saturating_sub(s).min(want);
                buf[..avail].copy_from_slice(&d[s..s + avail]);
                buf[avail..want].fill(0);
                Ok(want)
            }
            ReaderKind::Stream(s) => s.read_at(c, off, &mut buf[..want]),
            ReaderKind::Fork { algo, fork, table, cache } => {
                let mut done = 0usize;
                while done < want {
                    let pos = off + done as u64;
                    let bi = (pos / compress::BLOCK as u64) as usize;
                    let block = match cache {
                        Some((i, b)) if *i == bi => b.clone(),
                        _ => {
                            let (boff, blen) = *table
                                .get(bi)
                                .ok_or_else(|| Error::Corrupt("compressed block index out of range".into()))?;
                            if blen as usize > compress::BLOCK * 2 + 64 {
                                return Err(Error::Corrupt("compressed block too large".into()));
                            }
                            let mut raw = vec![0u8; blen as usize];
                            fork.read(c, boff, &mut raw)?;
                            let expect = (size - bi as u64 * compress::BLOCK as u64).min(compress::BLOCK as u64) as usize;
                            let d = Arc::new(compress::decode_unit(*algo, &raw, expect)?);
                            *cache = Some((bi, d.clone()));
                            d
                        }
                    };
                    let inb = (pos % compress::BLOCK as u64) as usize;
                    let n = (block.len() - inb).min(want - done);
                    buf[done..done + n].copy_from_slice(&block[inb..inb + n]);
                    done += n;
                }
                Ok(want)
            }
        }
    }

    /// Reads the whole file into memory (use only for small files).
    pub fn read_all(&mut self, c: &Container) -> Result<Vec<u8>> {
        if self.size > 1 << 31 {
            return Err(Error::Unsupported("file too large to buffer".into()));
        }
        let mut v = vec![0u8; self.size as usize];
        let mut off = 0usize;
        while off < v.len() {
            let n = self.read_at(c, off as u64, &mut v[off..])?;
            if n == 0 {
                break;
            }
            off += n;
        }
        Ok(v)
    }
}
