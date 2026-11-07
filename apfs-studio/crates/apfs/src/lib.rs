//! A robust, dependency-light implementation of Apple File System (APFS)
//! reading and transactional, crash-safe writing.

pub mod error;
pub mod bytes;
pub mod checksum;
pub mod device;
pub mod partition;
pub mod format;
pub mod keys;
pub mod btree;
pub mod records;
pub mod compress;
pub mod container;
pub mod volume;
pub mod wbtree;
pub mod spaceman;
pub mod txn;
pub mod check;
#[cfg(windows)]
pub mod windisk;

pub use container::Container;
pub use error::{Error, Result};
pub use txn::{NewFileMeta, Txn};
pub use volume::Volume;
