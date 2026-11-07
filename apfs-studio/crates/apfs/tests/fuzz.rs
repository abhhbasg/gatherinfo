//! Robustness: random corruption must produce errors, never panics or hangs.
mod common;

use apfs::device::{DeviceRef, MemDevice};
use apfs::{check, Container, NewFileMeta, Txn};
use std::sync::Arc;

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
}

fn exercise(dev: DeviceRef) {
    let Ok(c) = Container::open(dev.clone(), false) else { return };
    let _ = check::check(&c);
    for (_, v) in c.volumes() {
        let Ok(v) = v else { continue };
        let mut stack = vec![(2u64, 0usize)];
        let mut seen = 0;
        while let Some((ino, depth)) = stack.pop() {
            if depth > 20 || seen > 2000 {
                break;
            }
            let Ok(list) = v.list_dir(&c, ino) else { continue };
            for e in list {
                seen += 1;
                let Ok(i) = v.inode(&c, e.file_id) else { continue };
                if i.is_dir() {
                    stack.push((e.file_id, depth + 1));
                } else if let Ok(mut r) = v.open_file(&c, &i) {
                    let mut buf = vec![0u8; 65536];
                    let _ = r.read_at(&c, 0, &mut buf);
                }
                let _ = v.xattrs(&c, e.file_id);
            }
        }
    }
    // Writes on a corrupted container must fail cleanly too.
    if let Ok(mut c) = Container::open(dev, true) {
        if let Ok(mut tx) = Txn::begin(&mut c) {
            let data = vec![1u8; 10000];
            let _ = tx.create_file(0, 2, "fuzz", &mut &data[..], data.len() as u64, &NewFileMeta::file(), &mut |_| true);
            let _ = tx.remove(0, 2, "empty");
            let _ = tx.commit();
        }
    }
}

#[test]
fn corrupted_images_never_panic() {
    for img in ["case_insensitive.bin", "case_sensitive.bin", "case_insensitive_beta.bin", "jhfs_converted.bin"] {
        fuzz_image(img, 1500);
    }
}

fn fuzz_image(name: &str, rounds: usize) {
    let Some(base) = common::mem_copy(name) else { return };
    let original = base.snapshot();
    let bs = 4096usize;
    // Only metadata-bearing blocks are interesting: those with a valid checksum.
    let meta: Vec<usize> = (0..original.len() / bs)
        .filter(|&b| apfs::checksum::verify_object(&original[b * bs..(b + 1) * bs]))
        .collect();
    let mut rng = Rng(0xfeed_beef_1234_5678);
    for round in 0..rounds {
        let mut img = original.clone();
        let nflips = 1 + rng.next() % 6;
        for _ in 0..nflips {
            let b = meta[(rng.next() % meta.len() as u64) as usize];
            let off = b * bs + (rng.next() % bs as u64) as usize;
            img[off] ^= 1 << (rng.next() % 8);
            // Half the time, re-seal the checksum so corruption reaches the parsers.
            if rng.next() % 2 == 0 {
                apfs::checksum::seal_object(&mut img[b * bs..(b + 1) * bs]);
            }
        }
        let dev: DeviceRef = Arc::new(MemDevice::new(img, true));
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| exercise(dev)));
        assert!(r.is_ok(), "{name}: panic in round {round}");
    }
}
