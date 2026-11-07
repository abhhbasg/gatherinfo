mod common;
use apfs::bytes::rd32;
use apfs::format::DREC_LEN_MASK;
use apfs::keys;

#[test]
fn name_hashes_match_disk() {
    for img in ["case_insensitive.bin", "case_sensitive.bin", "jhfs_converted.bin", "case_insensitive_beta.bin"] {
        let Some(c) = common::open_ro(img) else { eprintln!("skip {img}"); continue };
        let v = c.volume(0).unwrap();
        if !v.hashed() { eprintln!("{img}: unhashed"); continue; }
        let mut stack = vec![2u64];
        let mut n = 0;
        while let Some(d) = stack.pop() {
            for e in v.list_dir(&c, d).unwrap() {
                let disk = rd32(&e.key, 8) & !DREC_LEN_MASK;
                let mine = keys::name_hash(&e.name, v.case_insensitive());
                assert_eq!(disk, mine, "{img}: hash mismatch for {:?}", e.name);
                n += 1;
                if e.is_dir() { stack.push(e.file_id); }
            }
        }
        eprintln!("{img}: {n} hashes ok");
    }
}

#[test]
fn file_contents() {
    let Some(c) = common::open_ro("case_insensitive.bin") else { return };
    let v = c.volume(0).unwrap();
    let rd = |p: &str| {
        let i = v.inode(&c, v.resolve(&c, p).unwrap()).unwrap();
        v.open_file(&c, &i).unwrap().read_all(&c).unwrap()
    };
    assert_eq!(rd("dir/file"), "\u{F8FF} File System\n".as_bytes().to_vec());
    let z = rd("dir/compressed-zlib-fork");
    assert_eq!(rd("dir/compressed-lzvn-fork"), z);
    assert_eq!(rd("dir/compressed-lzfse-fork"), z);
    assert_eq!(rd("dir/compressed-lzvn-xattr"), rd("dir/compressed-zlib-xattr"));
    assert_eq!(v.readlink(&c, v.resolve(&c, "symlink-file").unwrap()).unwrap(), "dir/file");
    let x = v.xattr(&c, v.resolve(&c, "dir/xattr-small").unwrap(), "xattr-small").unwrap().unwrap();
    assert_eq!(v.read_xattr(&c, &x).unwrap(), b"Small xattr data");
}
