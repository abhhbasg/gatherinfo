//! On-disk constants and structure offsets (Apple File System Reference).
#![allow(dead_code)]

use crate::bytes::*;
use crate::checksum;
use crate::error::{Error, Result};

pub const OBJ_HDR_SIZE: usize = 32;

pub const OBJECT_TYPE_MASK: u32 = 0x0000_ffff;
pub const OBJ_STORAGETYPE_MASK: u32 = 0xc000_0000;
pub const OBJ_VIRTUAL: u32 = 0;
pub const OBJ_EPHEMERAL: u32 = 0x8000_0000;
pub const OBJ_PHYSICAL: u32 = 0x4000_0000;
pub const OBJ_NOHEADER: u32 = 0x2000_0000;
pub const OBJ_ENCRYPTED: u32 = 0x1000_0000;

pub const OT_NX_SUPERBLOCK: u32 = 0x01;
pub const OT_BTREE: u32 = 0x02;
pub const OT_BTREE_NODE: u32 = 0x03;
pub const OT_SPACEMAN: u32 = 0x05;
pub const OT_SPACEMAN_CAB: u32 = 0x06;
pub const OT_SPACEMAN_CIB: u32 = 0x07;
pub const OT_SPACEMAN_BITMAP: u32 = 0x08;
pub const OT_SPACEMAN_FREE_QUEUE: u32 = 0x09;
pub const OT_EXTENT_LIST_TREE: u32 = 0x0a;
pub const OT_OMAP: u32 = 0x0b;
pub const OT_CHECKPOINT_MAP: u32 = 0x0c;
pub const OT_FS: u32 = 0x0d;
pub const OT_FSTREE: u32 = 0x0e;
pub const OT_BLOCKREFTREE: u32 = 0x0f;
pub const OT_SNAPMETATREE: u32 = 0x10;
pub const OT_NX_REAPER: u32 = 0x11;
pub const OT_OMAP_SNAPSHOT: u32 = 0x13;
pub const OT_FEXT_TREE: u32 = 0x1f;

pub const NX_MAGIC: u32 = 0x4253_584E; // "NXSB"
pub const APFS_MAGIC: u32 = 0x4253_5041; // "APSB"

pub const NX_INCOMPAT_FUSION: u64 = 0x100;

// B-tree node flags
pub const BTNODE_ROOT: u16 = 0x1;
pub const BTNODE_LEAF: u16 = 0x2;
pub const BTNODE_FIXED_KV_SIZE: u16 = 0x4;
pub const BTNODE_HASHED: u16 = 0x8;
pub const BTNODE_NOHEADER: u16 = 0x10;
pub const BTOFF_INVALID: u16 = 0xffff;

// B-tree info flags
pub const BTREE_UINT64_KEYS: u32 = 0x1;
pub const BTREE_SEQUENTIAL_INSERT: u32 = 0x2;
pub const BTREE_ALLOW_GHOSTS: u32 = 0x4;
pub const BTREE_EPHEMERAL: u32 = 0x8;
pub const BTREE_PHYSICAL: u32 = 0x10;
pub const BTREE_NONPERSISTENT: u32 = 0x20;
pub const BTREE_KV_NONALIGNED: u32 = 0x40;
pub const BTREE_HASHED: u32 = 0x80;
pub const BTREE_NOHEADER: u32 = 0x100;

pub const BTREE_NODE_HDR: usize = 56; // obj hdr + 24
pub const BTREE_INFO_SIZE: usize = 40;

// omap
pub const OMAP_MANUALLY_MANAGED: u32 = 1;
pub const OMAP_VAL_DELETED: u32 = 1;
pub const OMAP_VAL_ENCRYPTED: u32 = 4;
pub const OMAP_VAL_NOHEADER: u32 = 8;

// File system record types
pub const J_TYPE_SNAP_METADATA: u8 = 1;
pub const J_TYPE_EXTENT: u8 = 2;
pub const J_TYPE_INODE: u8 = 3;
pub const J_TYPE_XATTR: u8 = 4;
pub const J_TYPE_SIBLING_LINK: u8 = 5;
pub const J_TYPE_DSTREAM_ID: u8 = 6;
pub const J_TYPE_CRYPTO_STATE: u8 = 7;
pub const J_TYPE_FILE_EXTENT: u8 = 8;
pub const J_TYPE_DIR_REC: u8 = 9;
pub const J_TYPE_DIR_STATS: u8 = 10;
pub const J_TYPE_SNAP_NAME: u8 = 11;
pub const J_TYPE_SIBLING_MAP: u8 = 12;
pub const J_TYPE_FILE_INFO: u8 = 13;

pub const OBJ_ID_MASK: u64 = 0x0fff_ffff_ffff_ffff;
pub const OBJ_TYPE_SHIFT: u32 = 60;

pub const ROOT_DIR_PARENT: u64 = 1;
pub const ROOT_DIR_INO: u64 = 2;
pub const PRIV_DIR_INO: u64 = 3;
pub const SNAP_DIR_INO: u64 = 6;
pub const PURGEABLE_DIR_INO: u64 = 7;
pub const MIN_USER_INO: u64 = 16;

// Volume flags / features
pub const APFS_FS_UNENCRYPTED: u64 = 0x1;
pub const APFS_FS_ONEKEY: u64 = 0x8;
pub const APFS_INCOMPAT_CASE_INSENSITIVE: u64 = 0x1;
pub const APFS_INCOMPAT_DATALESS_SNAPS: u64 = 0x2;
pub const APFS_INCOMPAT_ENC_ROLLED: u64 = 0x4;
pub const APFS_INCOMPAT_NORMALIZATION_INSENSITIVE: u64 = 0x8;
pub const APFS_INCOMPAT_INCOMPLETE_RESTORE: u64 = 0x10;
pub const APFS_INCOMPAT_SEALED_VOLUME: u64 = 0x20;
pub const APFS_INCOMPAT_PFK: u64 = 0x40;
pub const APFS_INCOMPAT_EXTENT_PREALLOC_FLAG: u64 = 0x80;
pub const APFS_INCOMPAT_SECONDARY_FSROOT: u64 = 0x100;
pub const APFS_FEATURE_HARDLINK_MAP_RECORDS: u64 = 0x2;

// Inode internal flags
pub const INODE_IS_APFS_PRIVATE: u64 = 0x1;
pub const INODE_MAINTAIN_DIR_STATS: u64 = 0x2;
pub const INODE_DIR_STATS_ORIGIN: u64 = 0x4;
pub const INODE_PROT_CLASS_EXPLICIT: u64 = 0x8;
pub const INODE_WAS_CLONED: u64 = 0x10;
pub const INODE_HAS_SECURITY_EA: u64 = 0x40;
pub const INODE_BEING_TRUNCATED: u64 = 0x80;
pub const INODE_HAS_FINDER_INFO: u64 = 0x100;
pub const INODE_IS_SPARSE: u64 = 0x200;
pub const INODE_WAS_EVER_CLONED: u64 = 0x400;
pub const INODE_HAS_RSRC_FORK: u64 = 0x4000;
pub const INODE_NO_RSRC_FORK: u64 = 0x8000;
pub const INODE_HAS_UNCOMPRESSED_SIZE: u64 = 0x40000;
pub const INODE_IS_PURGEABLE: u64 = 0x80000;

pub const BSD_COMPRESSED: u32 = 0x20;

// Inode xfield types
pub const INO_EXT_TYPE_SNAP_XID: u8 = 1;
pub const INO_EXT_TYPE_DELTA_TREE_OID: u8 = 2;
pub const INO_EXT_TYPE_DOCUMENT_ID: u8 = 3;
pub const INO_EXT_TYPE_NAME: u8 = 4;
pub const INO_EXT_TYPE_PREV_FSIZE: u8 = 5;
pub const INO_EXT_TYPE_FINDER_INFO: u8 = 7;
pub const INO_EXT_TYPE_DSTREAM: u8 = 8;
pub const INO_EXT_TYPE_DIR_STATS_KEY: u8 = 10;
pub const INO_EXT_TYPE_FS_UUID: u8 = 11;
pub const INO_EXT_TYPE_SPARSE_BYTES: u8 = 13;
pub const INO_EXT_TYPE_RDEV: u8 = 14;
pub const INO_EXT_TYPE_PURGEABLE_FLAGS: u8 = 15;
pub const DREC_EXT_TYPE_SIBLING_ID: u8 = 1;

pub const XF_DATA_DEPENDENT: u8 = 0x01;
pub const XF_DO_NOT_COPY: u8 = 0x02;
pub const XF_CHILDREN_INHERIT: u8 = 0x08;
pub const XF_USER_FIELD: u8 = 0x10;
pub const XF_SYSTEM_FIELD: u8 = 0x20;

pub const DREC_LEN_MASK: u32 = 0x3ff;
pub const DREC_TYPE_MASK: u16 = 0xf;

// dtype values (mode >> 12)
pub const DT_FIFO: u16 = 1;
pub const DT_CHR: u16 = 2;
pub const DT_DIR: u16 = 4;
pub const DT_BLK: u16 = 6;
pub const DT_REG: u16 = 8;
pub const DT_LNK: u16 = 10;
pub const DT_SOCK: u16 = 12;
pub const DT_WHT: u16 = 14;

pub const S_IFMT: u16 = 0o170000;
pub const S_IFIFO: u16 = 0o010000;
pub const S_IFCHR: u16 = 0o020000;
pub const S_IFDIR: u16 = 0o040000;
pub const S_IFBLK: u16 = 0o060000;
pub const S_IFREG: u16 = 0o100000;
pub const S_IFLNK: u16 = 0o120000;
pub const S_IFSOCK: u16 = 0o140000;

// xattr flags
pub const XATTR_DATA_STREAM: u16 = 0x1;
pub const XATTR_DATA_EMBEDDED: u16 = 0x2;
pub const XATTR_FILE_SYSTEM_OWNED: u16 = 0x4;
pub const XATTR_MAX_EMBEDDED_SIZE: usize = 3804;

pub const XATTR_SYMLINK: &str = "com.apple.fs.symlink";
pub const XATTR_DECMPFS: &str = "com.apple.decmpfs";
pub const XATTR_RSRC_FORK: &str = "com.apple.ResourceFork";
pub const XATTR_SECURITY: &str = "com.apple.system.Security";
pub const XATTR_FINDER_INFO: &str = "com.apple.FinderInfo";

pub const FILE_EXTENT_LEN_MASK: u64 = 0x00ff_ffff_ffff_ffff;
pub const FILE_EXTENT_FLAG_SHIFT: u32 = 56;
pub const PEXT_LEN_MASK: u64 = 0x0fff_ffff_ffff_ffff;
pub const PEXT_KIND_SHIFT: u32 = 60;
pub const KIND_NEW: u64 = 1;
pub const KIND_UPDATE: u64 = 2;

pub const CRYPTO_SW_ID: u64 = 4;

pub const CHECKPOINT_MAP_LAST: u32 = 1;

// Spaceman
pub const SD_MAIN: usize = 0;
pub const SD_TIER2: usize = 1;
pub const SFQ_IP: usize = 0;
pub const SFQ_MAIN: usize = 1;
pub const SFQ_TIER2: usize = 2;
pub const SPACEMAN_IP_BM_INDEX_INVALID: u16 = 0xffff;

// Volume roles
pub const VOL_ROLE_SYSTEM: u16 = 0x0001;
pub const VOL_ROLE_USER: u16 = 0x0002;
pub const VOL_ROLE_RECOVERY: u16 = 0x0004;
pub const VOL_ROLE_VM: u16 = 0x0008;
pub const VOL_ROLE_PREBOOT: u16 = 0x0010;
pub const VOL_ROLE_INSTALLER: u16 = 0x0020;
pub const VOL_ROLE_DATA: u16 = 1 << 6;
pub const VOL_ROLE_BASEBAND: u16 = 2 << 6;
pub const VOL_ROLE_UPDATE: u16 = 3 << 6;
pub const VOL_ROLE_XART: u16 = 4 << 6;
pub const VOL_ROLE_HARDWARE: u16 = 5 << 6;
pub const VOL_ROLE_BACKUP: u16 = 6 << 6;
pub const VOL_ROLE_ENTERPRISE: u16 = 9 << 6;
pub const VOL_ROLE_PRELOGIN: u16 = 11 << 6;

pub fn role_name(role: u16) -> &'static str {
    match role {
        0 => "",
        VOL_ROLE_SYSTEM => "System",
        VOL_ROLE_USER => "User",
        VOL_ROLE_RECOVERY => "Recovery",
        VOL_ROLE_VM => "VM",
        VOL_ROLE_PREBOOT => "Preboot",
        VOL_ROLE_INSTALLER => "Installer",
        VOL_ROLE_DATA => "Data",
        VOL_ROLE_BASEBAND => "Baseband",
        VOL_ROLE_UPDATE => "Update",
        VOL_ROLE_XART => "xART",
        VOL_ROLE_HARDWARE => "Hardware",
        VOL_ROLE_BACKUP => "Backup",
        VOL_ROLE_ENTERPRISE => "Enterprise",
        VOL_ROLE_PRELOGIN => "Prelogin",
        _ => "Other",
    }
}

// --- NX superblock field offsets ---
pub mod nx {
    pub const MAGIC: usize = 0x20;
    pub const BLOCK_SIZE: usize = 0x24;
    pub const BLOCK_COUNT: usize = 0x28;
    pub const FEATURES: usize = 0x30;
    pub const RO_COMPAT: usize = 0x38;
    pub const INCOMPAT: usize = 0x40;
    pub const UUID: usize = 0x48;
    pub const NEXT_OID: usize = 0x58;
    pub const NEXT_XID: usize = 0x60;
    pub const XP_DESC_BLOCKS: usize = 0x68;
    pub const XP_DATA_BLOCKS: usize = 0x6c;
    pub const XP_DESC_BASE: usize = 0x70;
    pub const XP_DATA_BASE: usize = 0x78;
    pub const XP_DESC_NEXT: usize = 0x80;
    pub const XP_DATA_NEXT: usize = 0x84;
    pub const XP_DESC_INDEX: usize = 0x88;
    pub const XP_DESC_LEN: usize = 0x8c;
    pub const XP_DATA_INDEX: usize = 0x90;
    pub const XP_DATA_LEN: usize = 0x94;
    pub const SPACEMAN_OID: usize = 0x98;
    pub const OMAP_OID: usize = 0xa0;
    pub const REAPER_OID: usize = 0xa8;
    pub const MAX_FILE_SYSTEMS: usize = 0xb4;
    pub const FS_OID: usize = 0xb8;
    pub const MAX_FS: usize = 100;
    pub const KEYLOCKER: usize = 0x510;
    pub const FUSION_UUID: usize = 0x500;
}

// --- Volume superblock field offsets ---
pub mod apsb {
    pub const MAGIC: usize = 0x20;
    pub const FS_INDEX: usize = 0x24;
    pub const FEATURES: usize = 0x28;
    pub const RO_COMPAT: usize = 0x30;
    pub const INCOMPAT: usize = 0x38;
    pub const UNMOUNT_TIME: usize = 0x40;
    pub const RESERVE_BLOCKS: usize = 0x48;
    pub const QUOTA_BLOCKS: usize = 0x50;
    pub const ALLOC_COUNT: usize = 0x58;
    pub const ROOT_TREE_TYPE: usize = 0x74;
    pub const EXTENTREF_TREE_TYPE: usize = 0x78;
    pub const SNAP_META_TREE_TYPE: usize = 0x7c;
    pub const OMAP_OID: usize = 0x80;
    pub const ROOT_TREE_OID: usize = 0x88;
    pub const EXTENTREF_TREE_OID: usize = 0x90;
    pub const SNAP_META_TREE_OID: usize = 0x98;
    pub const REVERT_TO_XID: usize = 0xa0;
    pub const REVERT_TO_SBLOCK: usize = 0xa8;
    pub const NEXT_OBJ_ID: usize = 0xb0;
    pub const NUM_FILES: usize = 0xb8;
    pub const NUM_DIRECTORIES: usize = 0xc0;
    pub const NUM_SYMLINKS: usize = 0xc8;
    pub const NUM_OTHER: usize = 0xd0;
    pub const NUM_SNAPSHOTS: usize = 0xd8;
    pub const TOTAL_ALLOCED: usize = 0xe0;
    pub const TOTAL_FREED: usize = 0xe8;
    pub const UUID: usize = 0xf0;
    pub const LAST_MOD_TIME: usize = 0x100;
    pub const FS_FLAGS: usize = 0x108;
    pub const FORMATTED_BY: usize = 0x110;
    pub const MODIFIED_BY: usize = 0x140;
    pub const MODIFIED_BY_SIZE: usize = 48;
    pub const VOLNAME: usize = 0x2c0;
    pub const NEXT_DOC_ID: usize = 0x3c0;
    pub const ROLE: usize = 0x3c4;
    pub const ER_STATE_OID: usize = 0x3d0;
    pub const SNAP_META_EXT_OID: usize = 0x3e8;
    pub const INTEGRITY_META_OID: usize = 0x400;
    pub const FEXT_TREE_OID: usize = 0x408;
    pub const FEXT_TREE_TYPE: usize = 0x410;
    pub const DOC_ID_TREE_OID: usize = 0x430;
}

// --- Spaceman offsets ---
pub mod sm {
    pub const BLOCK_SIZE: usize = 0x20;
    pub const BLOCKS_PER_CHUNK: usize = 0x24;
    pub const CHUNKS_PER_CIB: usize = 0x28;
    pub const CIBS_PER_CAB: usize = 0x2c;
    pub const DEV: usize = 0x30;
    pub const DEV_SIZE: usize = 0x30;
    // within device
    pub const D_BLOCK_COUNT: usize = 0x0;
    pub const D_CHUNK_COUNT: usize = 0x8;
    pub const D_CIB_COUNT: usize = 0x10;
    pub const D_CAB_COUNT: usize = 0x14;
    pub const D_FREE_COUNT: usize = 0x18;
    pub const D_ADDR_OFFSET: usize = 0x20;
    pub const FLAGS: usize = 0x90;
    pub const IP_BM_TX_MULTIPLIER: usize = 0x94;
    pub const IP_BLOCK_COUNT: usize = 0x98;
    pub const IP_BM_SIZE_IN_BLOCKS: usize = 0xa0;
    pub const IP_BM_BLOCK_COUNT: usize = 0xa4;
    pub const IP_BM_BASE: usize = 0xa8;
    pub const IP_BASE: usize = 0xb0;
    pub const FS_RESERVE_BLOCK_COUNT: usize = 0xb8;
    pub const FS_RESERVE_ALLOC_COUNT: usize = 0xc0;
    pub const FQ: usize = 0xc8;
    pub const FQ_SIZE: usize = 0x28;
    pub const IP_BM_FREE_HEAD: usize = 0x140;
    pub const IP_BM_FREE_TAIL: usize = 0x142;
    pub const IP_BM_XID_OFFSET: usize = 0x144;
    pub const IP_BITMAP_OFFSET: usize = 0x148;
    pub const IP_BM_FREE_NEXT_OFFSET: usize = 0x14c;
}

/// Parsed object header.
#[derive(Debug, Clone, Copy, Default)]
pub struct ObjHeader {
    pub cksum: u64,
    pub oid: u64,
    pub xid: u64,
    pub type_and_flags: u32,
    pub subtype: u32,
}

impl ObjHeader {
    pub fn parse(b: &[u8]) -> Result<Self> {
        Ok(Self {
            cksum: u64_at(b, 0)?,
            oid: u64_at(b, 8)?,
            xid: u64_at(b, 16)?,
            type_and_flags: u32_at(b, 24)?,
            subtype: u32_at(b, 28)?,
        })
    }
    pub fn obj_type(&self) -> u32 {
        self.type_and_flags & OBJECT_TYPE_MASK
    }
    pub fn storage(&self) -> u32 {
        self.type_and_flags & OBJ_STORAGETYPE_MASK
    }
    pub fn write(&self, b: &mut [u8]) {
        wr64(b, 8, self.oid);
        wr64(b, 16, self.xid);
        wr32(b, 24, self.type_and_flags);
        wr32(b, 28, self.subtype);
    }
}

/// Validates the checksum and (optionally) expected type of a freshly read object.
pub fn check_object(buf: &[u8], what: &str, paddr: u64, expect_type: Option<u32>) -> Result<ObjHeader> {
    if !checksum::verify_object(buf) {
        return Err(Error::Corrupt(format!("{what} at block {paddr:#x}: bad checksum")));
    }
    let h = ObjHeader::parse(buf)?;
    if let Some(t) = expect_type {
        if h.obj_type() != t {
            return Err(Error::Corrupt(format!(
                "{what} at block {paddr:#x}: unexpected object type {:#x} (expected {t:#x})",
                h.obj_type()
            )));
        }
    }
    Ok(h)
}

/// Seconds/nanoseconds helpers: APFS timestamps are nanoseconds since 1970 UTC.
pub fn now_ns() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0)
}

pub fn system_time_to_ns(t: std::time::SystemTime) -> u64 {
    t.duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0)
}
