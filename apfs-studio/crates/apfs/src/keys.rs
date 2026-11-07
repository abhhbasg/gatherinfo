//! Key encoding, ordering and file-name hashing for every B-tree kind.

use crate::bytes::*;
use crate::checksum::crc32c_update;
use crate::format::*;
use std::cmp::Ordering;
use unicode_normalization::UnicodeNormalization;

/// Which kind of B-tree a key belongs to; determines its ordering.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TreeKind {
    /// Object map: (oid, xid)
    Omap,
    /// File-system tree; `hashed` = directory records carry a name hash.
    Fs { hashed: bool },
    /// Extent reference tree: (paddr|type)
    ExtentRef,
    /// Snapshot metadata tree: fs-style keys.
    SnapMeta,
    /// Free queue: (xid, paddr)
    FreeQueue,
    /// Omap snapshot tree: xid
    OmapSnapshot,
    /// Fext tree (sealed volumes): (private_id, logical_addr)
    Fext,
}

impl TreeKind {
    pub fn fixed_kv(&self) -> Option<(usize, usize)> {
        match self {
            TreeKind::Omap => Some((16, 16)),
            TreeKind::FreeQueue => Some((16, 8)),
            TreeKind::OmapSnapshot => Some((8, 16)),
            TreeKind::Fext => Some((16, 16)),
            _ => None,
        }
    }
    pub fn subtype(&self) -> u32 {
        match self {
            TreeKind::Omap => OT_OMAP,
            TreeKind::Fs { .. } => OT_FSTREE,
            TreeKind::ExtentRef => OT_BLOCKREFTREE,
            TreeKind::SnapMeta => OT_SNAPMETATREE,
            TreeKind::FreeQueue => OT_SPACEMAN_FREE_QUEUE,
            TreeKind::OmapSnapshot => OT_OMAP_SNAPSHOT,
            TreeKind::Fext => OT_FEXT_TREE,
        }
    }
}

/// Decomposed fs-tree key used for comparisons.
struct FsKey<'a> {
    id: u64,
    ty: u8,
    num: u64,
    name: &'a [u8],
}

fn parse_fs_key(k: &[u8], hashed: bool) -> FsKey<'_> {
    if k.len() < 8 {
        return FsKey { id: 0, ty: 0, num: 0, name: &[] };
    }
    let hdr = rd64(k, 0);
    let id = hdr & OBJ_ID_MASK;
    let ty = (hdr >> OBJ_TYPE_SHIFT) as u8;
    let rest = &k[8..];
    match ty {
        J_TYPE_DIR_REC => {
            if hashed {
                if rest.len() >= 4 {
                    let nlh = rd32(rest, 0);
                    FsKey { id, ty, num: (nlh & !DREC_LEN_MASK) as u64, name: cstr_bytes(&rest[4..]) }
                } else {
                    FsKey { id, ty, num: 0, name: &[] }
                }
            } else if rest.len() >= 2 {
                FsKey { id, ty, num: 0, name: cstr_bytes(&rest[2..]) }
            } else {
                FsKey { id, ty, num: 0, name: &[] }
            }
        }
        J_TYPE_XATTR | J_TYPE_SNAP_NAME => {
            if rest.len() >= 2 {
                FsKey { id, ty, num: 0, name: cstr_bytes(&rest[2..]) }
            } else {
                FsKey { id, ty, num: 0, name: &[] }
            }
        }
        J_TYPE_FILE_EXTENT | J_TYPE_SIBLING_LINK => {
            let num = if rest.len() >= 8 { rd64(rest, 0) } else { 0 };
            FsKey { id, ty, num, name: &[] }
        }
        J_TYPE_FILE_INFO => {
            let num = if rest.len() >= 8 { rd64(rest, 0) & FILE_EXTENT_LEN_MASK } else { 0 };
            FsKey { id, ty, num, name: &[] }
        }
        _ => FsKey { id, ty, num: 0, name: &[] },
    }
}

fn cmp_fs(a: &[u8], b: &[u8], hashed: bool) -> Ordering {
    let ka = parse_fs_key(a, hashed);
    let kb = parse_fs_key(b, hashed);
    ka.id
        .cmp(&kb.id)
        .then(ka.ty.cmp(&kb.ty))
        .then(ka.num.cmp(&kb.num))
        .then_with(|| ka.name.cmp(kb.name))
}

fn pair(k: &[u8]) -> (u64, u64) {
    let a = if k.len() >= 8 { rd64(k, 0) } else { 0 };
    let b = if k.len() >= 16 { rd64(k, 8) } else { 0 };
    (a, b)
}

/// Total order of keys for a tree kind.
pub fn compare(kind: TreeKind, a: &[u8], b: &[u8]) -> Ordering {
    match kind {
        TreeKind::Omap | TreeKind::FreeQueue | TreeKind::Fext => pair(a).cmp(&pair(b)),
        TreeKind::OmapSnapshot => pair(a).0.cmp(&pair(b).0),
        TreeKind::Fs { hashed } => cmp_fs(a, b, hashed),
        TreeKind::SnapMeta => cmp_fs(a, b, false),
        TreeKind::ExtentRef => {
            let x = pair(a).0;
            let y = pair(b).0;
            (x & OBJ_ID_MASK).cmp(&(y & OBJ_ID_MASK)).then((x >> 60).cmp(&(y >> 60)))
        }
    }
}

// ---------------------------------------------------------------------------
// Key builders
// ---------------------------------------------------------------------------

pub fn hdr(id: u64, ty: u8) -> u64 {
    (id & OBJ_ID_MASK) | ((ty as u64) << OBJ_TYPE_SHIFT)
}

pub fn omap_key(oid: u64, xid: u64) -> Vec<u8> {
    let mut v = Vec::with_capacity(16);
    v.extend_from_slice(&oid.to_le_bytes());
    v.extend_from_slice(&xid.to_le_bytes());
    v
}

pub fn simple_key(id: u64, ty: u8) -> Vec<u8> {
    hdr(id, ty).to_le_bytes().to_vec()
}

pub fn file_extent_key(id: u64, logical: u64) -> Vec<u8> {
    let mut v = simple_key(id, J_TYPE_FILE_EXTENT);
    v.extend_from_slice(&logical.to_le_bytes());
    v
}

pub fn sibling_link_key(id: u64, sibling: u64) -> Vec<u8> {
    let mut v = simple_key(id, J_TYPE_SIBLING_LINK);
    v.extend_from_slice(&sibling.to_le_bytes());
    v
}

pub fn xattr_key(id: u64, name: &str) -> Vec<u8> {
    let mut v = simple_key(id, J_TYPE_XATTR);
    let nb = name.as_bytes();
    v.extend_from_slice(&((nb.len() + 1) as u16).to_le_bytes());
    v.extend_from_slice(nb);
    v.push(0);
    v
}

/// Directory record key. `name` must be raw on-disk bytes (no NUL).
pub fn drec_key(parent: u64, name: &str, hashed: bool, case_insensitive: bool) -> Vec<u8> {
    let mut v = simple_key(parent, J_TYPE_DIR_REC);
    let nb = name.as_bytes();
    let len = (nb.len() + 1) as u32;
    if hashed {
        let h = name_hash(name, case_insensitive);
        v.extend_from_slice(&(h | (len & DREC_LEN_MASK)).to_le_bytes());
    } else {
        v.extend_from_slice(&(len as u16).to_le_bytes());
    }
    v.extend_from_slice(nb);
    v.push(0);
    v
}

/// Smallest possible directory record key for a parent (used to start scans).
pub fn drec_scan_start(parent: u64, hashed: bool) -> Vec<u8> {
    let mut v = simple_key(parent, J_TYPE_DIR_REC);
    if hashed {
        v.extend_from_slice(&0u32.to_le_bytes());
    } else {
        v.extend_from_slice(&0u16.to_le_bytes());
    }
    v
}

pub fn extentref_key(paddr: u64) -> Vec<u8> {
    hdr(paddr, J_TYPE_EXTENT).to_le_bytes().to_vec()
}

pub fn fs_key_id_type(k: &[u8]) -> (u64, u8) {
    if k.len() < 8 {
        return (0, 0);
    }
    let h = rd64(k, 0);
    (h & OBJ_ID_MASK, (h >> OBJ_TYPE_SHIFT) as u8)
}

// ---------------------------------------------------------------------------
// Unicode handling
// ---------------------------------------------------------------------------

/// Normalised form used for hashing and case/normalisation-insensitive comparison:
/// (optionally case-folded) canonical decomposition.
pub fn normalize_name(name: &str, case_fold: bool) -> Vec<char> {
    if case_fold {
        let folded = caseless::default_case_fold_str(name);
        folded.nfd().collect()
    } else {
        name.nfd().collect()
    }
}

/// The 22-bit directory-entry name hash, already shifted into position.
pub fn name_hash(name: &str, case_insensitive: bool) -> u32 {
    let mut h: u32 = 0xFFFF_FFFF;
    for c in normalize_name(name, case_insensitive) {
        h = crc32c_update(h, &(c as u32).to_le_bytes());
    }
    (h & 0x3F_FFFF) << 10
}

/// Compares two names according to a volume's insensitivity settings.
pub fn names_equal(a: &str, b: &str, case_insensitive: bool, norm_insensitive: bool) -> bool {
    if !case_insensitive && !norm_insensitive {
        return a == b;
    }
    if a == b {
        return true;
    }
    normalize_name(a, case_insensitive) == normalize_name(b, case_insensitive)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fs_order() {
        let a = drec_key(2, "a", true, true);
        let b = simple_key(2, J_TYPE_INODE);
        assert_eq!(compare(TreeKind::Fs { hashed: true }, &b, &a), Ordering::Less);
        let c = simple_key(3, J_TYPE_INODE);
        assert_eq!(compare(TreeKind::Fs { hashed: true }, &a, &c), Ordering::Less);
        let s = drec_scan_start(2, true);
        assert_eq!(compare(TreeKind::Fs { hashed: true }, &s, &a), Ordering::Less);
    }
}
