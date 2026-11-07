//! `apfs` — command-line access to APFS containers (images or raw disks).

use apfs::device::{DeviceRef, FileDevice};
use apfs::format::*;
use apfs::{partition, Container, Error, Result, Volume};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;

fn usage() -> ! {
    eprintln!(
        "apfs — APFS toolkit

USAGE: apfs <command> <image|device> [options] [args]

Read commands:
  info   <src>                         container / volume summary
  ls     <src> [-v N] [path]           list a directory
  tree   <src> [-v N] [path]           recursive listing
  cat    <src> [-v N] <path>           write file contents to stdout
  get    <src> [-v N] <path> <dest>    copy a file or directory out
  stat   <src> [-v N] <path>           inode details and xattrs
  check  <src>                         verify container consistency

Write commands (open the container read-write):
  put    <src> [-v N] <local> <path>   copy a local file/dir into the volume
  mkdir  <src> [-v N] <path>
  rm     <src> [-v N] [-r] <path>
  mv     <src> [-v N] <from> <to>
  symlink <src> [-v N] <target> <path>

Options:
  -v N       volume index inside the container (default: first volume)
  -p N       which APFS partition on a disk/image (default: first)
"
    );
    std::process::exit(2)
}

struct Args {
    cmd: String,
    src: PathBuf,
    vol: Option<usize>,
    part: usize,
    recursive: bool,
    rest: Vec<String>,
}

fn parse_args() -> Args {
    let mut it = std::env::args().skip(1);
    let cmd = it.next().unwrap_or_else(|| usage());
    let mut src = None;
    let mut vol = None;
    let mut part = 0;
    let mut recursive = false;
    let mut rest = Vec::new();
    while let Some(a) = it.next() {
        match a.as_str() {
            "-v" => vol = Some(it.next().and_then(|s| s.parse().ok()).unwrap_or_else(|| usage())),
            "-p" => part = it.next().and_then(|s| s.parse().ok()).unwrap_or_else(|| usage()),
            "-r" => recursive = true,
            "-h" | "--help" => usage(),
            _ if src.is_none() => src = Some(PathBuf::from(a)),
            _ => rest.push(a),
        }
    }
    Args { cmd, src: src.unwrap_or_else(|| usage()), vol, part, recursive, rest }
}

fn open(src: &Path, part: usize, writable: bool) -> Result<Container> {
    let dev: DeviceRef = Arc::new(FileDevice::open(src, writable)?);
    let locs = partition::find_containers(dev.as_ref());
    let loc = locs.get(part).ok_or_else(|| Error::NotFound("no APFS container found on this device".into()))?;
    let cdev = partition::open_location(&dev, loc)?;
    let c = Container::open(cdev, writable)?;
    for w in &c.report.warnings {
        eprintln!("warning: {w}");
    }
    Ok(c)
}

fn pick_volume(c: &Container, idx: Option<usize>) -> Result<Volume> {
    match idx {
        Some(i) => c.volume(i),
        None => {
            let mut first_err = None;
            for (_, v) in c.volumes() {
                match v {
                    Ok(v) => return Ok(v),
                    Err(e) => {
                        first_err.get_or_insert(e);
                    }
                }
            }
            Err(first_err.unwrap_or_else(|| Error::NotFound("container has no volumes".into())))
        }
    }
}

fn human(n: u64) -> String {
    let units = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut v = n as f64;
    let mut u = 0;
    while v >= 1024.0 && u < units.len() - 1 {
        v /= 1024.0;
        u += 1;
    }
    if u == 0 {
        format!("{n} B")
    } else {
        format!("{v:.1} {}", units[u])
    }
}

fn fmt_time(ns: u64) -> String {
    let secs = (ns / 1_000_000_000) as i64;
    let days = secs.div_euclid(86400);
    let rem = secs.rem_euclid(86400);
    // civil-from-days (Howard Hinnant)
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}", rem / 3600, rem % 3600 / 60)
}

fn kind_char(mode: u16) -> char {
    match mode & S_IFMT {
        S_IFDIR => 'd',
        S_IFLNK => 'l',
        S_IFREG => '-',
        S_IFIFO => 'p',
        S_IFSOCK => 's',
        S_IFCHR => 'c',
        S_IFBLK => 'b',
        _ => '?',
    }
}

fn cmd_info(c: &Container) -> Result<()> {
    let s = c.space_info()?;
    println!("Container  {}", c.uuid());
    println!("  block size   {}", c.block_size);
    println!("  size         {} ({} blocks)", human(s.total_bytes()), s.total_blocks);
    println!("  free         {}", human(s.free_bytes()));
    println!("  checkpoint   xid {}", c.xid());
    for (i, v) in c.volumes() {
        match v {
            Ok(v) => {
                println!("Volume #{i}  \"{}\"  {}", v.name, v.uuid);
                if !v.role_name().is_empty() {
                    println!("  role         {}", v.role_name());
                }
                println!(
                    "  case         {}",
                    if v.case_insensitive() { "insensitive" } else { "sensitive" }
                );
                println!("  used         {}", human(v.alloc_blocks() * c.block_size as u64));
                println!(
                    "  objects      {} files, {} dirs, {} symlinks, {} snapshots",
                    v.num_files(),
                    v.num_directories(),
                    v.num_symlinks(),
                    v.num_snapshots()
                );
                println!("  encrypted    {}", v.encrypted());
                println!("  formatted by {}", v.formatted_by());
                match v.write_blocker() {
                    None => println!("  writable     yes"),
                    Some(r) => println!("  writable     no ({r})"),
                }
            }
            Err(e) => println!("Volume #{i}: error: {e}"),
        }
    }
    Ok(())
}

fn cmd_ls(c: &Container, v: &Volume, path: &str, recursive: bool, depth: usize) -> Result<()> {
    let ino = v.resolve(c, path)?;
    let mut entries = v.list_dir(c, ino)?;
    entries.sort_by(|a, b| a.name.cmp(&b.name));
    for e in entries {
        let inode = v.inode(c, e.file_id);
        let indent = "  ".repeat(depth);
        match inode {
            Ok(i) => {
                let extra = if i.is_symlink() {
                    format!(" -> {}", v.readlink(c, i.id).unwrap_or_else(|e| format!("<{e}>")))
                } else {
                    String::new()
                };
                println!(
                    "{indent}{}{:o} {:>10} {} {}{}{}",
                    kind_char(i.mode),
                    i.mode & 0o7777,
                    if i.is_dir() { format!("{}", i.nchildren_or_nlink) } else { human(i.size()) },
                    fmt_time(i.mod_time),
                    e.name,
                    if i.is_compressed() { " [compressed]" } else { "" },
                    extra
                );
                if recursive && i.is_dir() {
                    let child = format!("{}/{}", path.trim_end_matches('/'), e.name);
                    cmd_ls(c, v, &child, true, depth + 1)?;
                }
            }
            Err(err) => println!("{indent}? {} (inode {}: {err})", e.name, e.file_id),
        }
    }
    Ok(())
}

fn extract(c: &Container, v: &Volume, ino: u64, dest: &Path) -> Result<()> {
    let i = v.inode(c, ino)?;
    if i.is_dir() {
        std::fs::create_dir_all(dest)?;
        for e in v.list_dir(c, ino)? {
            let safe: String = e.name.chars().map(|ch| if ch == '/' || ch == '\\' { '_' } else { ch }).collect();
            if safe == "." || safe == ".." {
                continue;
            }
            extract(c, v, e.file_id, &dest.join(safe))?;
        }
    } else if i.is_file() {
        let mut r = v.open_file(c, &i)?;
        let mut f = std::fs::File::create(dest)?;
        let mut buf = vec![0u8; 4 << 20];
        let mut off = 0u64;
        loop {
            let n = r.read_at(c, off, &mut buf)?;
            if n == 0 {
                break;
            }
            f.write_all(&buf[..n])?;
            off += n as u64;
        }
    } else if i.is_symlink() {
        let t = v.readlink(c, ino)?;
        std::fs::write(dest, t)?;
    }
    Ok(())
}

fn run() -> Result<()> {
    let a = parse_args();
    let write_cmds = ["put", "mkdir", "rm", "mv", "symlink"];
    let writable = write_cmds.contains(&a.cmd.as_str());
    let c = open(&a.src, a.part, writable)?;
    if writable {
        return write_cmd(c, &a);
    }
    let arg = |i: usize| -> String { a.rest.get(i).cloned().unwrap_or_else(|| usage()) };
    match a.cmd.as_str() {
        "info" => cmd_info(&c),
        "ls" | "tree" => {
            let v = pick_volume(&c, a.vol)?;
            let p = a.rest.first().cloned().unwrap_or_else(|| "/".into());
            cmd_ls(&c, &v, &p, a.cmd == "tree" || a.recursive, 0)
        }
        "cat" => {
            let v = pick_volume(&c, a.vol)?;
            let ino = v.resolve(&c, &arg(0))?;
            let i = v.inode(&c, ino)?;
            let mut r = v.open_file(&c, &i)?;
            let data = r.read_all(&c)?;
            std::io::stdout().write_all(&data)?;
            Ok(())
        }
        "get" => {
            let v = pick_volume(&c, a.vol)?;
            let ino = v.resolve(&c, &arg(0))?;
            extract(&c, &v, ino, Path::new(&arg(1)))
        }
        "stat" => {
            let v = pick_volume(&c, a.vol)?;
            let ino = v.resolve(&c, &arg(0))?;
            let i = v.inode(&c, ino)?;
            println!("{i:#?}");
            for x in v.xattrs(&c, ino)? {
                println!("xattr {} ({} bytes, flags {:#x})", x.name, x.len(), x.flags);
            }
            if let Some(d) = i.dstream() {
                for e in v.extents(&c, i.private_id)? {
                    println!("extent logical {:#x} len {:#x} phys {:#x}", e.logical, e.length, e.phys);
                }
                println!("dstream {d:?}");
            }
            Ok(())
        }
        "check" => {
            let r = apfs::check::check(&c);
            for w in &r.warnings {
                println!("warning: {w}");
            }
            for e in &r.errors {
                println!("ERROR: {e}");
            }
            println!(
                "{}: {} blocks, {} inodes checked, {} errors, {} warnings",
                if r.ok() { "OK" } else { "FAILED" },
                r.blocks_checked,
                r.inodes_checked,
                r.errors.len(),
                r.warnings.len()
            );
            if !r.ok() {
                std::process::exit(3);
            }
            Ok(())
        }
        _ => write_cmd(c, &a),
    }
}

fn split_path(p: &str) -> (String, String) {
    let t = p.trim_end_matches('/');
    match t.rfind('/') {
        Some(i) => (t[..i].to_string(), t[i + 1..].to_string()),
        None => ("/".to_string(), t.to_string()),
    }
}

fn put_tree(tx: &mut apfs::Txn, vi: usize, parent: u64, local: &Path, name: &str) -> Result<()> {
    let md = std::fs::symlink_metadata(local)?;
    let mtime = md.modified().map(apfs::format::system_time_to_ns).unwrap_or_else(|_| apfs::format::now_ns());
    if md.is_dir() {
        let mut meta = apfs::NewFileMeta::dir();
        meta.mod_time = mtime;
        let ino = match tx.lookup(vi, parent, name)? {
            Some(e) if e.is_dir() => e.file_id,
            Some(_) => return Err(Error::Exists(name.into())),
            None => tx.mkdir(vi, parent, name, &meta)?,
        };
        let mut items: Vec<_> = std::fs::read_dir(local)?.collect::<std::io::Result<Vec<_>>>()?;
        items.sort_by_key(|e| e.file_name());
        for it in items {
            let n = it.file_name().to_string_lossy().into_owned();
            put_tree(tx, vi, ino, &it.path(), &n)?;
        }
        Ok(())
    } else if md.file_type().is_symlink() {
        let t = std::fs::read_link(local)?;
        tx.symlink(vi, parent, name, &t.to_string_lossy())?;
        Ok(())
    } else {
        let mut f = std::fs::File::open(local)?;
        let mut meta = apfs::NewFileMeta::file();
        meta.mod_time = mtime;
        let len = md.len();
        match tx.lookup(vi, parent, name)? {
            Some(e) if !e.is_dir() => tx.overwrite_file(vi, e.file_id, &mut f, len, mtime, &mut |_| true),
            Some(_) => Err(Error::IsADirectory(name.into())),
            None => tx.create_file(vi, parent, name, &mut f, len, &meta, &mut |_| true).map(|_| ()),
        }
    }
}

fn write_cmd(mut c: Container, a: &Args) -> Result<()> {
    let vi = match a.vol {
        Some(v) => v,
        None => pick_volume(&c, None)?.index,
    };
    let arg = |i: usize| -> String { a.rest.get(i).cloned().unwrap_or_else(|| usage()) };
    let mut tx = apfs::Txn::begin(&mut c)?;
    match a.cmd.as_str() {
        "put" => {
            let local = PathBuf::from(arg(0));
            let dest = arg(1);
            // If dest is an existing directory, copy into it.
            let (parent, name) = match tx.resolve(vi, &dest) {
                Ok(ino) if tx.inode(vi, ino)?.is_dir() => {
                    (ino, local.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default())
                }
                _ => {
                    let (p, n) = split_path(&dest);
                    (tx.resolve(vi, &p)?, n)
                }
            };
            put_tree(&mut tx, vi, parent, &local, &name)?;
        }
        "mkdir" => {
            let (p, n) = split_path(&arg(0));
            let pi = tx.resolve(vi, &p)?;
            tx.mkdir(vi, pi, &n, &apfs::NewFileMeta::dir())?;
        }
        "rm" => {
            let (p, n) = split_path(&arg(0));
            let pi = tx.resolve(vi, &p)?;
            if a.recursive {
                tx.remove_recursive(vi, pi, &n, &mut |_| true)?;
            } else {
                tx.remove(vi, pi, &n)?;
            }
        }
        "mv" => {
            let (p, n) = split_path(&arg(0));
            let pi = tx.resolve(vi, &p)?;
            let dest = arg(1);
            let (np, nn) = match tx.resolve(vi, &dest) {
                Ok(ino) if tx.inode(vi, ino)?.is_dir() => (ino, n.clone()),
                _ => {
                    let (dp, dn) = split_path(&dest);
                    (tx.resolve(vi, &dp)?, dn)
                }
            };
            tx.rename(vi, pi, &n, np, &nn)?;
        }
        "symlink" => {
            let target = arg(0);
            let (p, n) = split_path(&arg(1));
            let pi = tx.resolve(vi, &p)?;
            tx.symlink(vi, pi, &n, &target)?;
        }
        _ => usage(),
    }
    tx.commit()
}

fn main() {
    if let Err(e) = run() {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}
