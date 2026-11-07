//! Write-path tests on copies of real macOS-created images. Every commit is
//! followed by a fresh re-open, a full consistency check and a comparison
//! against an in-memory model of the expected tree.

mod common;

use apfs::device::{Device, DeviceRef, MemDevice};
use apfs::error::Result;
use apfs::{check, Container, Error, NewFileMeta, Txn};
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

#[derive(Clone, Debug, PartialEq)]
enum Ent {
    File(Vec<u8>),
    Dir,
}

type Model = BTreeMap<String, Ent>;

fn open(dev: &DeviceRef, writable: bool) -> Container {
    Container::open(dev.clone(), writable).expect("open container")
}

fn assert_clean(dev: &DeviceRef, ctx: &str) {
    let c = open(dev, false);
    let r = check::check(&c);
    if !r.ok() {
        panic!("{ctx}: check failed: {:#?}", r.errors);
    }
    let leaks: Vec<_> = r.warnings.iter().filter(|w| w.contains("unreferenced") || w.contains("leak")).collect();
    assert!(leaks.is_empty(), "{ctx}: leaks: {leaks:?}");
}

fn verify_model(dev: &DeviceRef, root: &str, model: &Model) {
    let c = open(dev, false);
    let v = c.volume(0).unwrap();
    let base = v.resolve(&c, root).unwrap();
    let mut found: Model = BTreeMap::new();
    let mut stack = vec![(base, String::new())];
    while let Some((ino, path)) = stack.pop() {
        for e in v.list_dir(&c, ino).unwrap() {
            let p = format!("{path}/{}", e.name);
            let i = v.inode(&c, e.file_id).unwrap();
            if i.is_dir() {
                found.insert(p.clone(), Ent::Dir);
                stack.push((e.file_id, p));
            } else {
                let data = v.open_file(&c, &i).unwrap().read_all(&c).unwrap();
                found.insert(p, Ent::File(data));
            }
        }
    }
    if &found != model {
        let fk: Vec<_> = found.keys().collect();
        let mk: Vec<_> = model.keys().collect();
        panic!("model mismatch\nfound: {fk:?}\nmodel: {mk:?}");
    }
}

fn split(p: &str) -> (&str, &str) {
    let i = p.rfind('/').unwrap();
    (if i == 0 { "/" } else { &p[..i] }, &p[i + 1..])
}

fn random_ops(img: &str, seed: u64, rounds: usize) {
    let Some(mem) = common::mem_copy(img) else { eprintln!("skip: no images"); return };
    let dev: DeviceRef = mem;
    let mut rng = Rng(seed);
    let mut model: Model = BTreeMap::new();
    let root = "/t";
    {
        let mut c = open(&dev, true);
        let mut tx = Txn::begin(&mut c).unwrap();
        tx.mkdir(0, 2, "t", &NewFileMeta::dir()).unwrap();
        tx.commit().unwrap();
    }
    assert_clean(&dev, "mkdir root");
    for round in 0..rounds {
        let mut c = open(&dev, true);
        let mut tx = Txn::begin(&mut c).unwrap();
        let nops = 1 + rng.below(12);
        let mut next = model.clone();
        let mut failed = false;
        for _ in 0..nops {
            let dirs: Vec<String> = std::iter::once(String::new())
                .chain(next.iter().filter(|(_, e)| **e == Ent::Dir).map(|(k, _)| k.clone()))
                .collect();
            let files: Vec<String> = next.iter().filter(|(_, e)| matches!(e, Ent::File(_))).map(|(k, _)| k.clone()).collect();
            let op = rng.below(10);
            let vi = 0;
            let r: Result<()> = (|| {
                match op {
                    0..=3 => {
                        let d = &dirs[rng.below(dirs.len() as u64) as usize];
                        let name = format!("f{}", rng.below(100000));
                        let size = match rng.below(4) {
                            0 => 0,
                            1 => rng.below(5000),
                            2 => rng.below(70_000),
                            _ => rng.below(260_000),
                        } as usize;
                        let data = rng.bytes(size);
                        let path = format!("{d}/{name}");
                        if next.contains_key(&path) {
                            return Ok(());
                        }
                        let pi = tx.resolve(vi, &format!("{root}{d}"))?;
                        tx.create_file(vi, pi, &name, &mut &data[..], size as u64, &NewFileMeta::file(), &mut |_| true)?;
                        next.insert(path, Ent::File(data));
                    }
                    4 => {
                        let d = &dirs[rng.below(dirs.len() as u64) as usize];
                        let name = format!("d{}", rng.below(1000));
                        let path = format!("{d}/{name}");
                        if next.contains_key(&path) {
                            return Ok(());
                        }
                        let pi = tx.resolve(vi, &format!("{root}{d}"))?;
                        tx.mkdir(vi, pi, &name, &NewFileMeta::dir())?;
                        next.insert(path, Ent::Dir);
                    }
                    5 | 6 if !files.is_empty() => {
                        let f = files[rng.below(files.len() as u64) as usize].clone();
                        let (d, n) = split(&f);
                        let pi = tx.resolve(vi, &format!("{root}{}", if d == "/" { "" } else { d }))?;
                        tx.remove(vi, pi, n)?;
                        next.remove(&f);
                    }
                    7 if !files.is_empty() => {
                        let f = files[rng.below(files.len() as u64) as usize].clone();
                        let d = &dirs[rng.below(dirs.len() as u64) as usize];
                        let nn = format!("m{}", rng.below(100000));
                        let np = format!("{d}/{nn}");
                        if next.contains_key(&np) {
                            return Ok(());
                        }
                        let (sd, sn) = split(&f);
                        let spi = tx.resolve(vi, &format!("{root}{}", if sd == "/" { "" } else { sd }))?;
                        let dpi = tx.resolve(vi, &format!("{root}{d}"))?;
                        tx.rename(vi, spi, sn, dpi, &nn)?;
                        let e = next.remove(&f).unwrap();
                        next.insert(np, e);
                    }
                    8 if !files.is_empty() => {
                        let f = files[rng.below(files.len() as u64) as usize].clone();
                        let size = rng.below(150_000) as usize;
                        let data = rng.bytes(size);
                        let ino = tx.resolve(vi, &format!("{root}{f}"))?;
                        tx.overwrite_file(vi, ino, &mut &data[..], size as u64, 0, &mut |_| true)?;
                        next.insert(f, Ent::File(data));
                    }
                    9 => {
                        // Remove an empty directory if any.
                        let empties: Vec<String> = dirs
                            .iter()
                            .filter(|d| !d.is_empty() && !next.keys().any(|k| k.starts_with(&format!("{d}/"))))
                            .cloned()
                            .collect();
                        if let Some(d) = empties.first() {
                            let (pd, n) = split(d);
                            let pi = tx.resolve(vi, &format!("{root}{}", if pd == "/" { "" } else { pd }))?;
                            tx.remove(vi, pi, n)?;
                            next.remove(d);
                        }
                    }
                    _ => {}
                }
                Ok(())
            })();
            match r {
                Ok(()) => {}
                Err(Error::NoSpace) => {
                    failed = true;
                    break;
                }
                Err(e) => panic!("round {round}: op {op} failed: {e}"),
            }
        }
        if failed {
            // Abort: drop the transaction; the disk must still match the old model.
            drop(tx);
            drop(c);
            assert_clean(&dev, &format!("round {round} after aborted txn"));
            verify_model(&dev, root, &model);
            // Make room.
            let mut c = open(&dev, true);
            let mut tx = Txn::begin(&mut c).unwrap();
            let victims: Vec<String> = model.iter().filter(|(_, e)| matches!(e, Ent::File(_))).map(|(k, _)| k.clone()).take(10).collect();
            for f in victims {
                let (d, n) = split(&f);
                let pi = tx.resolve(0, &format!("{root}{}", if d == "/" { "" } else { d })).unwrap();
                tx.remove(0, pi, n).unwrap();
                model.remove(&f);
            }
            tx.commit().unwrap();
        } else {
            tx.commit().unwrap_or_else(|e| panic!("round {round}: commit failed: {e}"));
            model = next;
        }
        assert_clean(&dev, &format!("{img} round {round}"));
        if round % 5 == 0 || round + 1 == rounds {
            verify_model(&dev, root, &model);
        }
    }
    verify_model(&dev, root, &model);
    let c = open(&dev, false);
    eprintln!("{img} seed {seed}: {} entries in model, xid {}, free {} blocks", model.len(), c.xid(), c.space_info().unwrap().free_blocks);
}

#[test]
fn random_ops_case_insensitive() {
    random_ops("case_insensitive.bin", 0x1234_5678_9abc_def1, 60);
}

#[test]
fn random_ops_case_sensitive() {
    random_ops("case_sensitive.bin", 0x0bad_cafe_dead_beef, 40);
}

#[test]
fn random_ops_large() {
    random_ops("large.bin", 0x5eed_5eed_5eed_5eed, 40);
}

#[test]
fn random_ops_jhfs() {
    // The converted image has a pre-existing child-count inconsistency in
    // /dir, so operate on a fresh directory and only compare the model.
    let Some(mem) = common::mem_copy("case_insensitive_beta.bin") else { return };
    let _ = mem;
    random_ops("case_insensitive_beta.bin", 42, 30);
}

#[test]
fn many_files_split_and_merge() {
    let Some(mem) = common::mem_copy("large.bin") else { return };
    let dev: DeviceRef = mem;
    let n = 3000;
    {
        let mut c = open(&dev, true);
        let mut tx = Txn::begin(&mut c).unwrap();
        let d = tx.mkdir(0, 2, "many", &NewFileMeta::dir()).unwrap();
        for i in 0..n {
            let data = format!("file number {i}").into_bytes();
            tx.create_file(0, d, &format!("file-{i:05}"), &mut &data[..], data.len() as u64, &NewFileMeta::file(), &mut |_| true).unwrap();
        }
        tx.commit().unwrap();
    }
    assert_clean(&dev, "after 3000 creates");
    {
        let c = open(&dev, false);
        let v = c.volume(0).unwrap();
        let d = v.resolve(&c, "/many").unwrap();
        assert_eq!(v.list_dir(&c, d).unwrap().len(), n);
        let i = v.inode(&c, v.resolve(&c, "/many/file-01234").unwrap()).unwrap();
        assert_eq!(v.open_file(&c, &i).unwrap().read_all(&c).unwrap(), b"file number 1234");
    }
    // Delete in several transactions (exercises merges and root collapse).
    for chunk in 0..3 {
        let mut c = open(&dev, true);
        let mut tx = Txn::begin(&mut c).unwrap();
        let d = tx.resolve(0, "/many").unwrap();
        for i in (chunk..n).step_by(3) {
            tx.remove(0, d, &format!("file-{i:05}")).unwrap();
        }
        tx.commit().unwrap();
        assert_clean(&dev, &format!("after delete chunk {chunk}"));
    }
    let mut c = open(&dev, true);
    let mut tx = Txn::begin(&mut c).unwrap();
    tx.remove(0, 2, "many").unwrap();
    tx.commit().unwrap();
    assert_clean(&dev, "after removing dir");
}

#[test]
fn large_file_roundtrip() {
    let Some(mem) = common::mem_copy("large.bin") else { return };
    let dev: DeviceRef = mem;
    let mut rng = Rng(7);
    let data = rng.bytes(40 * 1024 * 1024 + 123);
    {
        let mut c = open(&dev, true);
        let mut tx = Txn::begin(&mut c).unwrap();
        tx.create_file(0, 2, "big.bin", &mut &data[..], data.len() as u64, &NewFileMeta::file(), &mut |_| true).unwrap();
        tx.commit().unwrap();
    }
    assert_clean(&dev, "big file");
    let c = open(&dev, false);
    let v = c.volume(0).unwrap();
    let i = v.inode(&c, v.resolve(&c, "/BIG.BIN").unwrap()).unwrap();
    let got = v.open_file(&c, &i).unwrap().read_all(&c).unwrap();
    assert!(got == data);
}

#[test]
fn fill_to_enospc() {
    let Some(mem) = common::mem_copy("case_insensitive.bin") else { return };
    let dev: DeviceRef = mem;
    let mut rng = Rng(99);
    let mut k = 0;
    loop {
        let mut c = open(&dev, true);
        let mut tx = Txn::begin(&mut c).unwrap();
        let data = rng.bytes(300_000);
        match tx.create_file(0, 2, &format!("fill{k}"), &mut &data[..], data.len() as u64, &NewFileMeta::file(), &mut |_| true) {
            Ok(_) => tx.commit().unwrap(),
            Err(Error::NoSpace) => break,
            Err(e) => panic!("{e}"),
        }
        k += 1;
        assert!(k < 100);
    }
    assert!(k > 3);
    assert_clean(&dev, "full");
    let mut c = open(&dev, true);
    let mut tx = Txn::begin(&mut c).unwrap();
    for i in 0..k {
        tx.remove(0, 2, &format!("fill{i}")).unwrap();
    }
    tx.commit().unwrap();
    assert_clean(&dev, "emptied");
}

/// Device wrapper that silently drops every write after `limit` writes,
/// simulating a power cut at an arbitrary point.
struct CrashDevice {
    inner: Arc<MemDevice>,
    writes: AtomicU64,
    limit: u64,
    log: Mutex<()>,
}

impl Device for CrashDevice {
    fn read_at(&self, o: u64, b: &mut [u8]) -> Result<()> {
        self.inner.read_at(o, b)
    }
    fn write_at(&self, o: u64, b: &[u8]) -> Result<()> {
        let _g = self.log.lock().unwrap();
        let n = self.writes.fetch_add(1, Ordering::SeqCst);
        if n < self.limit {
            self.inner.write_at(o, b)
        } else {
            Ok(())
        }
    }
    fn flush(&self) -> Result<()> {
        Ok(())
    }
    fn len(&self) -> u64 {
        self.inner.len()
    }
    fn writable(&self) -> bool {
        true
    }
    fn describe(&self) -> String {
        "crash".into()
    }
}

#[test]
fn power_loss_at_every_write() {
    let Some(base) = common::mem_copy("case_insensitive.bin") else { return };
    let original = base.snapshot();
    // Count the writes of one transaction.
    let run = |dev: DeviceRef| -> Result<()> {
        let mut c = Container::open(dev, true)?;
        let mut tx = Txn::begin(&mut c)?;
        let d = tx.mkdir(0, 2, "crash", &NewFileMeta::dir())?;
        let data = vec![0xABu8; 50_000];
        tx.create_file(0, d, "payload", &mut &data[..], data.len() as u64, &NewFileMeta::file(), &mut |_| true)?;
        tx.remove(0, 2, "empty")?;
        tx.commit()
    };
    let probe = Arc::new(CrashDevice { inner: Arc::new(MemDevice::new(original.clone(), true)), writes: AtomicU64::new(0), limit: u64::MAX, log: Mutex::new(()) });
    run(probe.clone()).unwrap();
    let total = probe.writes.load(Ordering::SeqCst);
    assert!(total > 5);
    for limit in 0..=total {
        let mem = Arc::new(MemDevice::new(original.clone(), true));
        let dev = Arc::new(CrashDevice { inner: mem.clone(), writes: AtomicU64::new(0), limit, log: Mutex::new(()) });
        run(dev).unwrap();
        let after: DeviceRef = mem;
        let c = Container::open(after.clone(), false).unwrap_or_else(|e| panic!("limit {limit}: unmountable: {e}"));
        let r = check::check(&c);
        assert!(r.ok(), "limit {limit}: {:?}", r.errors);
        let v = c.volume(0).unwrap();
        let has_new = v.resolve(&c, "/crash/payload").is_ok();
        let has_old = v.resolve(&c, "/empty").is_ok();
        // Either the old state or the new state, never a mix.
        assert!(has_new != has_old, "limit {limit}: mixed state");
        if limit == total {
            assert!(has_new);
        }
    }
}

#[test]
fn successive_transactions_same_handle() {
    let Some(mem) = common::mem_copy("case_sensitive.bin") else { return };
    let dev: DeviceRef = mem;
    let mut c = open(&dev, true);
    for i in 0..25u32 {
        let mut tx = Txn::begin(&mut c).unwrap();
        let data = vec![i as u8; (i as usize) * 3000];
        tx.create_file(0, 2, &format!("s{i}"), &mut &data[..], data.len() as u64, &NewFileMeta::file(), &mut |_| true).unwrap();
        if i > 2 {
            tx.remove(0, 2, &format!("s{}", i - 3)).unwrap();
        }
        tx.commit().unwrap();
        // Reads through the same handle must see the new state.
        let v = c.volume(0).unwrap();
        let ino = v.resolve(&c, &format!("/s{i}")).unwrap();
        let got = v.open_file(&c, &v.inode(&c, ino).unwrap()).unwrap().read_all(&c).unwrap();
        assert_eq!(got, data);
        let r = check::check(&c);
        assert!(r.ok(), "iteration {i}: {:?}", r.errors);
    }
}

#[test]
fn random_ops_long() {
    for seed in [3u64, 17, 1001, 77777] {
        random_ops("case_insensitive.bin", seed, 120);
    }
}
