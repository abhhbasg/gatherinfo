#![allow(dead_code)]
use apfs::device::{DeviceRef, FileDevice, MemDevice};
use apfs::{partition, Container};
use std::path::PathBuf;
use std::sync::Arc;

pub fn image_dir() -> Option<PathBuf> {
    std::env::var_os("APFS_TEST_IMAGES").map(PathBuf::from).filter(|p| p.is_dir())
}

pub fn image(name: &str) -> Option<PathBuf> {
    let p = image_dir()?.join(name);
    p.exists().then_some(p)
}

pub fn open_ro(name: &str) -> Option<Container> {
    let p = image(name)?;
    let dev: DeviceRef = Arc::new(FileDevice::open(&p, false).unwrap());
    let loc = partition::find_containers(dev.as_ref()).remove(0);
    Some(Container::open(partition::open_location(&dev, &loc).unwrap(), false).unwrap())
}

/// Loads an image into memory as a writable device.
pub fn mem_copy(name: &str) -> Option<Arc<MemDevice>> {
    let p = image(name)?;
    Some(Arc::new(MemDevice::new(std::fs::read(p).unwrap(), true)))
}
