use std::fmt;

/// All errors produced by the APFS library.
#[derive(Debug)]
pub enum Error {
    Io(std::io::Error),
    /// On-disk structures are damaged or inconsistent.
    Corrupt(String),
    /// A valid feature that this implementation deliberately refuses to handle
    /// (e.g. writing to an encrypted or snapshotted volume).
    Unsupported(String),
    NotFound(String),
    Exists(String),
    NotADirectory(String),
    IsADirectory(String),
    DirectoryNotEmpty(String),
    InvalidName(String),
    NoSpace,
    ReadOnly,
    Cancelled,
    Other(String),
}

pub type Result<T> = std::result::Result<T, Error>;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(e) => write!(f, "I/O error: {e}"),
            Error::Corrupt(s) => write!(f, "file system corruption detected: {s}"),
            Error::Unsupported(s) => write!(f, "unsupported: {s}"),
            Error::NotFound(s) => write!(f, "not found: {s}"),
            Error::Exists(s) => write!(f, "already exists: {s}"),
            Error::NotADirectory(s) => write!(f, "not a directory: {s}"),
            Error::IsADirectory(s) => write!(f, "is a directory: {s}"),
            Error::DirectoryNotEmpty(s) => write!(f, "directory not empty: {s}"),
            Error::InvalidName(s) => write!(f, "invalid name: {s}"),
            Error::NoSpace => write!(f, "not enough free space in the container"),
            Error::ReadOnly => write!(f, "the container is opened read-only"),
            Error::Cancelled => write!(f, "operation cancelled"),
            Error::Other(s) => write!(f, "{s}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e)
    }
}

/// Shorthand to build a corruption error.
#[macro_export]
macro_rules! corrupt {
    ($($arg:tt)*) => { $crate::error::Error::Corrupt(format!($($arg)*)) };
}

/// Shorthand to build an unsupported error.
#[macro_export]
macro_rules! unsupported {
    ($($arg:tt)*) => { $crate::error::Error::Unsupported(format!($($arg)*)) };
}
