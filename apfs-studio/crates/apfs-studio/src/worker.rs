//! Background worker: owns every open device/container and performs all
//! disk I/O so the UI never blocks.

use apfs::device::{DeviceRef, FileDevice};
use apfs::format::*;
use apfs::partition::{self, ContainerLocation};
use apfs::records::Inode;
use apfs::{check, Container, Error, NewFileMeta, Txn, Volume};
use crossbeam_channel::{Receiver, Sender};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub type SourceId = usize;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct VolRef {
    pub src: SourceId,
    pub ctr: usize,
    pub vol: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Conflict {
    Skip,
    Overwrite,
    KeepBoth,
}

pub enum Cmd {
    OpenImage(PathBuf),
    OpenDisk(PathBuf),
    ScanDisks,
    Close(SourceId),
    SetWritable { src: SourceId, ctr: usize, writable: bool, verify: bool },
    List { vref: VolRef, ino: u64, req: u64 },
    Details { vref: VolRef, ino: u64 },
    Export { vref: VolRef, items: Vec<(u64, String)>, dest: PathBuf },
    Import { vref: VolRef, parent: u64, paths: Vec<PathBuf>, conflict: Conflict },
    Mkdir { vref: VolRef, parent: u64, name: String },
    Rename { vref: VolRef, parent: u64, old: String, new: String },
    Delete { vref: VolRef, parent: u64, names: Vec<String> },
    Check { src: SourceId, ctr: usize },
}

#[derive(Clone, Debug)]
pub struct VolumeInfo {
    pub index: usize,
    pub name: String,
    pub role: String,
    pub used: u64,
    pub files: u64,
    pub dirs: u64,
    pub snapshots: u64,
    pub case_insensitive: bool,
    pub encrypted: bool,
    pub read_blocker: Option<String>,
    pub write_blocker: Option<String>,
    pub error: Option<String>,
    pub uuid: String,
    pub formatted_by: String,
}

#[derive(Clone, Debug)]
pub struct ContainerInfo {
    pub index: usize,
    pub label: String,
    pub uuid: String,
    pub block_size: u32,
    pub total: u64,
    pub free: u64,
    pub writable: bool,
    pub error: Option<String>,
    pub warnings: Vec<String>,
    pub volumes: Vec<VolumeInfo>,
}

#[derive(Clone, Debug)]
pub struct SourceInfo {
    pub id: SourceId,
    pub label: String,
    pub path: String,
    pub is_disk: bool,
    pub size: u64,
    pub containers: Vec<ContainerInfo>,
}

#[derive(Clone, Debug, Copy, PartialEq, Eq)]
pub enum Kind {
    Dir,
    File,
    Symlink,
    Other,
}

#[derive(Clone, Debug)]
pub struct EntryInfo {
    pub name: String,
    pub ino: u64,
    pub kind: Kind,
    pub size: u64,
    pub mtime: u64,
    pub ctime: u64,
    pub mode: u16,
    pub children: u32,
    pub compressed: bool,
    pub link_target: Option<String>,
}

#[derive(Clone, Debug)]
pub struct DetailInfo {
    pub ino: u64,
    pub name: String,
    pub props: Vec<(String, String)>,
    pub xattrs: Vec<(String, u64)>,
}

#[derive(Clone, Debug, Default)]
pub struct ProgressInfo {
    pub title: String,
    pub current: String,
    pub done: u64,
    pub total: u64,
}

pub enum Event {
    Sources(Vec<SourceInfo>),
    Listing { req: u64, vref: VolRef, ino: u64, result: Result<Vec<EntryInfo>, String> },
    Details(Result<DetailInfo, String>),
    Progress(ProgressInfo),
    Finished { title: String, result: Result<String, String>, refresh: Option<VolRef> },
    CheckDone { title: String, ok: bool, errors: Vec<String>, warnings: Vec<String>, summary: String },
    Busy(bool),
}

struct Ctr {
    loc: ContainerLocation,
    c: Option<Container>,
    error: Option<String>,
}

struct Source {
    id: SourceId,
    path: PathBuf,
    label: String,
    is_disk: bool,
    dev: DeviceRef,
    ctrs: Vec<Ctr>,
}

pub struct Worker {
    rx: Receiver<Cmd>,
    tx: Sender<Event>,
    repaint: Box<dyn Fn() + Send>,
    cancel: Arc<AtomicBool>,
    sources: Vec<Source>,
    next_id: SourceId,
}

fn e2s(e: Error) -> String {
    crate::i18n::error_text(&e)
}

impl Worker {
    pub fn spawn(rx: Receiver<Cmd>, tx: Sender<Event>, cancel: Arc<AtomicBool>, repaint: Box<dyn Fn() + Send>) {
        std::thread::Builder::new()
            .name("apfs-worker".into())
            .spawn(move || {
                let mut w = Worker { rx, tx, repaint, cancel, sources: Vec::new(), next_id: 1 };
                while let Ok(cmd) = w.rx.recv() {
                    w.cancel.store(false, Ordering::SeqCst);
                    // Never let a bug in one command take the whole app down.
                    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| w.handle(cmd)));
                    if let Err(p) = r {
                        let msg = p
                            .downcast_ref::<String>()
                            .cloned()
                            .or_else(|| p.downcast_ref::<&str>().map(|s| s.to_string()))
                            .unwrap_or_else(|| "unknown".into());
                        w.send(Event::Busy(false));
                        w.send(Event::Finished { title: "内部错误".into(), result: Err(format!("程序内部错误：{msg}")), refresh: None });
                    }
                }
            })
            .expect("spawn worker");
    }

    fn send(&self, e: Event) {
        let _ = self.tx.send(e);
        (self.repaint)();
    }

    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst)
    }

    fn handle(&mut self, cmd: Cmd) {
        match cmd {
            Cmd::OpenImage(p) => {
                self.busy(true);
                let r = self.open_source(&p, false);
                self.busy(false);
                if let Err(e) = r {
                    self.finish("打开镜像", Err(e), None);
                }
                self.publish();
            }
            Cmd::OpenDisk(p) => {
                self.busy(true);
                let r = self.open_source(&p, true);
                self.busy(false);
                if let Err(e) = r {
                    self.finish("打开磁盘", Err(e), None);
                }
                self.publish();
            }
            Cmd::ScanDisks => {
                self.busy(true);
                let r = self.scan_disks();
                self.busy(false);
                match r {
                    Ok(0) => self.finish("扫描磁盘", Ok("没有发现包含 APFS 容器的磁盘".into()), None),
                    Ok(n) => self.finish("扫描磁盘", Ok(format!("发现 {n} 个包含 APFS 的磁盘")), None),
                    Err(e) => self.finish("扫描磁盘", Err(e), None),
                }
                self.publish();
            }
            Cmd::Close(id) => {
                self.sources.retain(|s| s.id != id);
                self.publish();
            }
            Cmd::SetWritable { src, ctr, writable, verify } => {
                self.busy(true);
                if writable && verify {
                    self.send(Event::Progress(ProgressInfo { title: "启用写入前检查容器一致性…".into(), ..Default::default() }));
                    let report = self.container(src, ctr).map(check::check);
                    match report {
                        Ok(r) if !r.ok() => {
                            self.busy(false);
                            let summary = format!(
                                "容器存在 {} 个一致性问题，为避免扩大损坏，已拒绝启用写入。建议先在 Mac 上用“磁盘工具 → 急救”修复。",
                                r.errors.len()
                            );
                            self.send(Event::CheckDone { title: "启用写入".into(), ok: false, errors: r.errors, warnings: r.warnings, summary });
                            return;
                        }
                        Err(e) => {
                            self.busy(false);
                            self.finish("启用写入", Err(e), None);
                            return;
                        }
                        _ => {}
                    }
                }
                let r = self.set_writable(src, ctr, writable);
                self.busy(false);
                match r {
                    Ok(()) => self.finish(
                        if writable { "启用写入" } else { "切换为只读" },
                        Ok(if writable { "容器已以读写模式打开".into() } else { "容器已切换为只读".into() }),
                        None,
                    ),
                    Err(e) => self.finish("启用写入", Err(e), None),
                }
                self.publish();
            }
            Cmd::List { vref, ino, req } => {
                let r = self.list(vref, ino).map_err(e2s);
                self.send(Event::Listing { req, vref, ino, result: r });
            }
            Cmd::Details { vref, ino } => {
                let r = self.details(vref, ino).map_err(e2s);
                self.send(Event::Details(r));
            }
            Cmd::Export { vref, items, dest } => {
                self.busy(true);
                let r = self.export(vref, &items, &dest);
                self.busy(false);
                self.finish("导出", r, None);
            }
            Cmd::Import { vref, parent, paths, conflict } => {
                self.busy(true);
                let r = self.import(vref, parent, &paths, conflict);
                self.busy(false);
                self.finish("导入", r, Some(vref));
                self.publish();
            }
            Cmd::Mkdir { vref, parent, name } => {
                let r = self.with_txn(vref, |tx, vi| tx.mkdir(vi, parent, &name, &NewFileMeta::dir()).map(|_| ()));
                self.finish("新建文件夹", r.map(|_| format!("已创建 “{name}”")), Some(vref));
            }
            Cmd::Rename { vref, parent, old, new } => {
                let r = self.with_txn(vref, |tx, vi| tx.rename(vi, parent, &old, parent, &new));
                self.finish("重命名", r.map(|_| format!("“{old}” → “{new}”")), Some(vref));
            }
            Cmd::Delete { vref, parent, names } => {
                self.busy(true);
                let cancel = self.cancel.clone();
                let tx_ = self.tx.clone();
                let n = names.len();
                let r = self.with_txn(vref, |tx, vi| {
                    let mut count = 0u64;
                    for name in &names {
                        tx.remove_recursive(vi, parent, name, &mut |item| {
                            count += 1;
                            if count % 64 == 0 {
                                let _ = tx_.send(Event::Progress(ProgressInfo { title: "正在删除".into(), current: item.to_string(), done: count, total: 0 }));
                            }
                            !cancel.load(Ordering::SeqCst)
                        })?;
                    }
                    Ok(())
                });
                self.busy(false);
                self.finish("删除", r.map(|_| format!("已删除 {n} 项")), Some(vref));
                self.publish();
            }
            Cmd::Check { src, ctr } => {
                self.busy(true);
                self.send(Event::Progress(ProgressInfo { title: "正在检查容器一致性…".into(), ..Default::default() }));
                let res = self.container(src, ctr).map(|c| check::check(c));
                self.busy(false);
                match res {
                    Ok(r) => {
                        let summary = format!(
                            "检查了 {} 个块、{} 个文件节点：{} 个错误，{} 个警告",
                            r.blocks_checked,
                            r.inodes_checked,
                            r.errors.len(),
                            r.warnings.len()
                        );
                        self.send(Event::CheckDone { title: "一致性检查".into(), ok: r.ok(), errors: r.errors, warnings: r.warnings, summary });
                    }
                    Err(e) => self.finish("一致性检查", Err(e), None),
                }
            }
        }
    }

    fn busy(&self, b: bool) {
        self.send(Event::Busy(b));
    }

    fn finish(&self, title: &str, r: Result<String, String>, refresh: Option<VolRef>) {
        self.send(Event::Finished { title: title.into(), result: r, refresh });
    }

    // -------------------------------------------------------------------
    // Sources
    // -------------------------------------------------------------------

    fn open_source(&mut self, p: &Path, is_disk: bool) -> Result<(), String> {
        if self.sources.iter().any(|s| s.path == p) {
            return Ok(());
        }
        let dev: DeviceRef = Arc::new(FileDevice::open(p, false).map_err(|e| {
            if is_disk {
                format!("{}（访问物理磁盘需要以管理员身份运行）", e2s(e))
            } else {
                e2s(e)
            }
        })?);
        let locs = partition::find_containers(dev.as_ref());
        if locs.is_empty() {
            return Err(format!("“{}” 中没有找到 APFS 容器", p.display()));
        }
        let ctrs = locs
            .into_iter()
            .map(|loc| match partition::open_location(&dev, &loc).and_then(|d| Container::open(d, false)) {
                Ok(c) => Ctr { loc, c: Some(c), error: None },
                Err(e) => Ctr { loc, c: None, error: Some(e2s(e)) },
            })
            .collect();
        let label = if is_disk {
            p.display().to_string()
        } else {
            p.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| p.display().to_string())
        };
        let id = self.next_id;
        self.next_id += 1;
        self.sources.push(Source { id, path: p.to_path_buf(), label, is_disk, dev, ctrs });
        Ok(())
    }

    #[cfg(windows)]
    fn scan_disks(&mut self) -> Result<usize, String> {
        let disks = apfs::windisk::list_physical_disks();
        if disks.is_empty() {
            return Err("无法访问物理磁盘。请以管理员身份运行 APFS Studio。".into());
        }
        let mut n = 0;
        for d in disks {
            let path = PathBuf::from(&d.path);
            if self.sources.iter().any(|s| s.path == path) {
                n += 1;
                continue;
            }
            let Ok(f) = FileDevice::open(&path, false) else { continue };
            if partition::find_containers(&f).is_empty() {
                continue;
            }
            if self.open_source(&path, true).is_ok() {
                if let Some(s) = self.sources.last_mut() {
                    s.label = format!("{} — 磁盘 {}", d.model, d.index);
                }
                n += 1;
            }
        }
        Ok(n)
    }

    #[cfg(not(windows))]
    fn scan_disks(&mut self) -> Result<usize, String> {
        let mut n = 0;
        let Ok(rd) = std::fs::read_dir("/dev") else { return Ok(0) };
        let mut paths: Vec<PathBuf> = rd
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .filter(|p| {
                let s = p.file_name().map(|f| f.to_string_lossy().into_owned()).unwrap_or_default();
                (s.starts_with("sd") || s.starts_with("nvme") || s.starts_with("disk")) && !s.chars().last().unwrap_or('a').is_ascii_digit()
                    || (s.starts_with("nvme") && s.contains('n') && !s.contains('p'))
            })
            .collect();
        paths.sort();
        for p in paths {
            let Ok(f) = FileDevice::open(&p, false) else { continue };
            if partition::find_containers(&f).is_empty() {
                continue;
            }
            if self.open_source(&p, true).is_ok() {
                n += 1;
            }
        }
        Ok(n)
    }

    fn set_writable(&mut self, src: SourceId, ctr: usize, writable: bool) -> Result<(), String> {
        let s = self.sources.iter_mut().find(|s| s.id == src).ok_or("来源不存在")?;
        if writable && !s.dev.writable() {
            let dev: DeviceRef = Arc::new(FileDevice::open(&s.path, true).map_err(|e| {
                if s.is_disk {
                    format!("{}（写入物理磁盘需要管理员权限）", e2s(e))
                } else {
                    e2s(e)
                }
            })?);
            // Re-open every container on the new handle; only `ctr` becomes writable.
            for (i, c) in s.ctrs.iter_mut().enumerate() {
                if i == ctr {
                    continue;
                }
                if c.c.is_some() {
                    if let Ok(nc) = partition::open_location(&dev, &c.loc).and_then(|d| Container::open(d, false)) {
                        c.c = Some(nc);
                    }
                }
            }
            s.dev = dev;
        }
        let c = s.ctrs.get_mut(ctr).ok_or("容器不存在")?;
        let d = partition::open_location(&s.dev, &c.loc).map_err(e2s)?;
        let nc = Container::open(d, writable).map_err(e2s)?;
        c.c = Some(nc);
        c.error = None;
        Ok(())
    }

    fn container(&self, src: SourceId, ctr: usize) -> Result<&Container, String> {
        let s = self.sources.iter().find(|s| s.id == src).ok_or("来源已关闭")?;
        let c = s.ctrs.get(ctr).ok_or("容器不存在")?;
        c.c.as_ref().ok_or_else(|| c.error.clone().unwrap_or_else(|| "容器无法打开".into()))
    }

    fn container_mut(&mut self, src: SourceId, ctr: usize) -> Result<&mut Container, String> {
        let s = self.sources.iter_mut().find(|s| s.id == src).ok_or("来源已关闭")?;
        let c = s.ctrs.get_mut(ctr).ok_or("容器不存在")?;
        let err = c.error.clone();
        c.c.as_mut().ok_or_else(|| err.unwrap_or_else(|| "容器无法打开".into()))
    }

    fn publish(&self) {
        let mut out = Vec::new();
        for s in &self.sources {
            let mut ctrs = Vec::new();
            for (i, c) in s.ctrs.iter().enumerate() {
                let mut ci = ContainerInfo {
                    index: i,
                    label: c.loc.label.clone(),
                    uuid: String::new(),
                    block_size: 0,
                    total: c.loc.length,
                    free: 0,
                    writable: false,
                    error: c.error.clone(),
                    warnings: Vec::new(),
                    volumes: Vec::new(),
                };
                if let Some(cn) = &c.c {
                    ci.uuid = cn.uuid().to_string();
                    ci.block_size = cn.block_size;
                    ci.writable = cn.is_writable();
                    ci.warnings = cn.report.warnings.clone();
                    if let Ok(sp) = cn.space_info() {
                        ci.total = sp.total_bytes();
                        ci.free = sp.free_bytes();
                    }
                    for (vi, v) in cn.volumes() {
                        ci.volumes.push(match v {
                            Ok(v) => VolumeInfo {
                                index: vi,
                                name: if v.name.is_empty() { format!("卷 {vi}") } else { v.name.clone() },
                                role: crate::i18n::role(v.role_name()).to_string(),
                                used: v.alloc_blocks() * cn.block_size as u64,
                                files: v.num_files(),
                                dirs: v.num_directories(),
                                snapshots: v.num_snapshots(),
                                case_insensitive: v.case_insensitive(),
                                encrypted: v.encrypted(),
                                read_blocker: v.read_blocker().map(|r| crate::i18n::blocker(&r)),
                                write_blocker: v.write_blocker().map(|r| crate::i18n::blocker(&r)),
                                error: None,
                                uuid: v.uuid.to_string(),
                                formatted_by: v.formatted_by(),
                            },
                            Err(e) => VolumeInfo {
                                index: vi,
                                name: format!("卷 {vi}"),
                                role: String::new(),
                                used: 0,
                                files: 0,
                                dirs: 0,
                                snapshots: 0,
                                case_insensitive: false,
                                encrypted: false,
                                read_blocker: Some(e2s(e)),
                                write_blocker: Some("卷无法打开".into()),
                                error: Some("卷无法打开".into()),
                                uuid: String::new(),
                                formatted_by: String::new(),
                            },
                        });
                    }
                }
                ctrs.push(ci);
            }
            out.push(SourceInfo { id: s.id, label: s.label.clone(), path: s.path.display().to_string(), is_disk: s.is_disk, size: s.dev.len(), containers: ctrs });
        }
        self.send(Event::Sources(out));
    }

    // -------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------

    fn vol(&self, vref: VolRef) -> Result<(&Container, Volume), Error> {
        let c = self.container(vref.src, vref.ctr).map_err(Error::Other)?;
        let v = c.volume(vref.vol)?;
        Ok((c, v))
    }

    fn entry_info(c: &Container, v: &Volume, name: String, ino: u64, i: &Inode) -> EntryInfo {
        let kind = if i.is_dir() {
            Kind::Dir
        } else if i.is_file() {
            Kind::File
        } else if i.is_symlink() {
            Kind::Symlink
        } else {
            Kind::Other
        };
        EntryInfo {
            name,
            ino,
            kind,
            size: if kind == Kind::File { v.file_size(c, i) } else { 0 },
            mtime: i.mod_time,
            ctime: i.create_time,
            mode: i.mode,
            children: if kind == Kind::Dir { i.nchildren_or_nlink } else { 0 },
            compressed: i.is_compressed(),
            link_target: if kind == Kind::Symlink { v.readlink(c, ino).ok() } else { None },
        }
    }

    fn list(&self, vref: VolRef, ino: u64) -> Result<Vec<EntryInfo>, Error> {
        let (c, v) = self.vol(vref)?;
        let mut out = Vec::new();
        for e in v.list_dir(c, ino)? {
            match v.inode(c, e.file_id) {
                Ok(i) => out.push(Self::entry_info(c, &v, e.name, e.file_id, &i)),
                Err(_) => out.push(EntryInfo {
                    name: e.name,
                    ino: e.file_id,
                    kind: Kind::Other,
                    size: 0,
                    mtime: 0,
                    ctime: 0,
                    mode: 0,
                    children: 0,
                    compressed: false,
                    link_target: None,
                }),
            }
        }
        Ok(out)
    }

    fn details(&self, vref: VolRef, ino: u64) -> Result<DetailInfo, Error> {
        let (c, v) = self.vol(vref)?;
        let i = v.inode(c, ino)?;
        let mut props = Vec::new();
        let t = |ns: u64| crate::fmt::datetime(ns);
        props.push(("节点号".into(), format!("{ino}")));
        props.push(("类型".into(), crate::fmt::kind_name(i.mode).into()));
        if i.is_file() {
            let sz = v.file_size(c, &i);
            props.push(("大小".into(), format!("{} ({} 字节)", crate::fmt::size(sz), crate::fmt::thousands(sz))));
            if let Some(d) = i.dstream() {
                props.push(("占用空间".into(), crate::fmt::size(d.alloced_size)));
            }
            if let Some(a) = v.compression(c, &i) {
                props.push(("压缩".into(), a.to_string()));
            }
            props.push(("硬链接数".into(), format!("{}", i.nlink())));
        }
        if i.is_dir() {
            props.push(("子项".into(), format!("{}", i.nchildren_or_nlink)));
        }
        if i.is_symlink() {
            props.push(("链接目标".into(), v.readlink(c, ino).unwrap_or_else(|e| format!("<{}>", e2s(e)))));
        }
        props.push(("权限".into(), crate::fmt::mode_string(i.mode)));
        props.push(("所有者".into(), format!("uid {} / gid {}", i.owner, i.group)));
        props.push(("创建时间".into(), t(i.create_time)));
        props.push(("修改时间".into(), t(i.mod_time)));
        props.push(("变更时间".into(), t(i.change_time)));
        props.push(("访问时间".into(), t(i.access_time)));
        let xattrs = v.xattrs(c, ino)?.into_iter().map(|x| {
            let l = x.len();
            (x.name, l)
        }).collect();
        Ok(DetailInfo { ino, name: i.name().unwrap_or_default(), props, xattrs })
    }

    // -------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------

    fn export(&self, vref: VolRef, items: &[(u64, String)], dest: &Path) -> Result<String, String> {
        let (c, v) = self.vol(vref).map_err(e2s)?;
        // Pre-scan for progress totals.
        let mut total = 0u64;
        let mut count = 0u64;
        for (ino, _) in items {
            scan_totals(c, &v, *ino, &mut total, &mut count, 0).map_err(e2s)?;
        }
        let mut st = ExportState { done: 0, total, files: 0, skipped: Vec::new(), renamed: 0 };
        for (ino, name) in items {
            let target = dest.join(crate::fmt::safe_windows_name(name, &mut st.renamed));
            self.export_one(c, &v, *ino, name, &target, &mut st, 0).map_err(e2s)?;
        }
        let mut msg = format!("已导出 {} 个文件（{}）到 {}", st.files, crate::fmt::size(st.done), dest.display());
        if st.renamed > 0 {
            msg += &format!("；{} 个名称含 Windows 不允许的字符，已替换为 “_”", st.renamed);
        }
        if !st.skipped.is_empty() {
            msg += &format!("；跳过 {} 个符号链接/特殊文件", st.skipped.len());
        }
        Ok(msg)
    }

    #[allow(clippy::too_many_arguments)]
    fn export_one(&self, c: &Container, v: &Volume, ino: u64, name: &str, target: &Path, st: &mut ExportState, depth: usize) -> Result<(), Error> {
        if self.cancelled() {
            return Err(Error::Cancelled);
        }
        if depth > 256 {
            return Err(Error::Corrupt("directory nesting too deep".into()));
        }
        let i = v.inode(c, ino)?;
        if i.is_dir() {
            std::fs::create_dir_all(target)?;
            for e in v.list_dir(c, ino)? {
                let child = target.join(crate::fmt::safe_windows_name(&e.name, &mut st.renamed));
                self.export_one(c, v, e.file_id, &e.name, &child, st, depth + 1)?;
            }
            return Ok(());
        }
        if !i.is_file() {
            st.skipped.push(name.to_string());
            return Ok(());
        }
        let mut r = v.open_file(c, &i)?;
        let tmp = target.with_extension(format!("{}apfs-part", target.extension().map(|e| format!("{}.", e.to_string_lossy())).unwrap_or_default()));
        let res = (|| -> Result<(), Error> {
            let mut f = std::io::BufWriter::with_capacity(1 << 20, std::fs::File::create(&tmp)?);
            let mut buf = vec![0u8; 4 << 20];
            let mut off = 0u64;
            loop {
                if self.cancelled() {
                    return Err(Error::Cancelled);
                }
                let n = r.read_at(c, off, &mut buf)?;
                if n == 0 {
                    break;
                }
                f.write_all(&buf[..n])?;
                off += n as u64;
                st.done += n as u64;
                self.send(Event::Progress(ProgressInfo { title: "正在导出".into(), current: name.to_string(), done: st.done, total: st.total }));
            }
            let f = f.into_inner().map_err(|e| Error::Io(e.into_error()))?;
            let mt = std::time::UNIX_EPOCH + std::time::Duration::from_nanos(i.mod_time);
            let _ = f.set_modified(mt);
            f.sync_all()?;
            Ok(())
        })();
        match res {
            Ok(()) => {
                if target.exists() {
                    let _ = std::fs::remove_file(target);
                }
                std::fs::rename(&tmp, target)?;
                st.files += 1;
                Ok(())
            }
            Err(e) => {
                let _ = std::fs::remove_file(&tmp);
                Err(e)
            }
        }
    }

    // -------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------

    fn with_txn<T>(&mut self, vref: VolRef, f: impl FnOnce(&mut Txn, usize) -> Result<T, Error>) -> Result<T, String> {
        let c = self.container_mut(vref.src, vref.ctr)?;
        if !c.is_writable() {
            return Err("容器处于只读模式。请先在左侧栏中启用写入。".into());
        }
        let mut tx = Txn::begin(c).map_err(e2s)?;
        let r = f(&mut tx, vref.vol).map_err(e2s)?;
        tx.commit().map_err(e2s)?;
        Ok(r)
    }

    fn import(&mut self, vref: VolRef, parent: u64, paths: &[PathBuf], conflict: Conflict) -> Result<String, String> {
        let mut total = 0u64;
        let mut files = 0u64;
        for p in paths {
            local_totals(p, &mut total, &mut files);
        }
        let mut st = ImportState { done: 0, total, files: 0, skipped: 0, pending_bytes: 0, pending_items: 0 };
        let cancel = self.cancel.clone();
        let tx_ev = self.tx.clone();
        let c = self.container_mut(vref.src, vref.ctr)?;
        if !c.is_writable() {
            return Err("容器处于只读模式。请先在左侧栏中启用写入。".into());
        }
        let mut ctx = ImportCtx { vi: vref.vol, conflict, cancel, ev: tx_ev, st: &mut st };
        let mut live = Some(Txn::begin(c).map_err(e2s)?);
        let mut failure = None;
        for p in paths {
            let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "untitled".into());
            let t = live.take().expect("transaction");
            match import_item(t, &mut ctx, parent, p, &name) {
                Ok(t) => live = Some(t),
                Err(e) => {
                    failure = Some(e);
                    break;
                }
            }
        }
        match (failure, live) {
            (None, Some(tx)) => {
                tx.commit().map_err(e2s)?;
                let mut msg = format!("已导入 {} 个文件（{}）", st.files, crate::fmt::size(st.done));
                if st.skipped > 0 {
                    msg += &format!("，跳过 {} 个已存在的项目", st.skipped);
                }
                Ok(msg)
            }
            (Some(e), _) => {
                // The current batch is discarded; earlier batches stay committed.
                Err(format!("{}（未完成的部分没有写入磁盘）", e2s(e)))
            }
            (None, None) => Err("内部错误：事务丢失".into()),
        }
    }
}

struct ExportState {
    done: u64,
    total: u64,
    files: u64,
    skipped: Vec<String>,
    renamed: u64,
}

struct ImportState {
    done: u64,
    total: u64,
    files: u64,
    skipped: u64,
    pending_bytes: u64,
    pending_items: u64,
}

struct ImportCtx<'s> {
    vi: usize,
    conflict: Conflict,
    cancel: Arc<AtomicBool>,
    ev: Sender<Event>,
    st: &'s mut ImportState,
}

fn scan_totals(c: &Container, v: &Volume, ino: u64, bytes: &mut u64, count: &mut u64, depth: usize) -> Result<(), Error> {
    if depth > 256 {
        return Ok(());
    }
    let i = v.inode(c, ino)?;
    if i.is_dir() {
        for e in v.list_dir(c, ino)? {
            scan_totals(c, v, e.file_id, bytes, count, depth + 1)?;
        }
    } else if i.is_file() {
        *bytes += v.file_size(c, &i);
        *count += 1;
    }
    Ok(())
}

fn local_totals(p: &Path, bytes: &mut u64, files: &mut u64) {
    let Ok(md) = std::fs::symlink_metadata(p) else { return };
    if md.is_dir() {
        if let Ok(rd) = std::fs::read_dir(p) {
            for e in rd.flatten() {
                local_totals(&e.path(), bytes, files);
            }
        }
    } else {
        *bytes += md.len();
        *files += 1;
    }
}

fn unique_name(tx: &mut Txn, vi: usize, parent: u64, name: &str) -> Result<String, Error> {
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    for k in 2..10000 {
        let cand = format!("{stem} ({k}){ext}");
        if tx.lookup(vi, parent, &cand)?.is_none() {
            return Ok(cand);
        }
    }
    Err(Error::Exists(name.into()))
}

/// Imports one local item; may commit and restart the transaction to bound
/// memory use and to make progress durable. Returns the live transaction.
fn import_item<'a>(mut tx: Txn<'a>, ctx: &mut ImportCtx, parent: u64, p: &Path, name: &str) -> Result<Txn<'a>, Error> {
    if ctx.cancel.load(Ordering::SeqCst) {
        return Err(Error::Cancelled);
    }
    let vi = ctx.vi;
    let md = std::fs::symlink_metadata(p)?;
    let mtime = md.modified().map(system_time_to_ns).unwrap_or_else(|_| now_ns());
    let ctime = md.created().map(system_time_to_ns).unwrap_or(mtime);
    if md.is_dir() {
        let ino = match tx.lookup(vi, parent, name)? {
            Some(e) if e.is_dir() => e.file_id,
            Some(_) => {
                let n = unique_name(&mut tx, vi, parent, name)?;
                tx.mkdir(vi, parent, &n, &NewFileMeta { mod_time: mtime, create_time: ctime, ..NewFileMeta::dir() })?
            }
            None => tx.mkdir(vi, parent, name, &NewFileMeta { mod_time: mtime, create_time: ctime, ..NewFileMeta::dir() })?,
        };
        let mut items: Vec<_> = std::fs::read_dir(p)?.collect::<std::io::Result<Vec<_>>>()?;
        items.sort_by_key(|e| e.file_name());
        for it in items {
            let n = it.file_name().to_string_lossy().into_owned();
            tx = import_item(tx, ctx, ino, &it.path(), &n)?;
        }
        return Ok(tx);
    }
    if md.file_type().is_symlink() {
        if let Ok(t) = std::fs::read_link(p) {
            if tx.lookup(vi, parent, name)?.is_none() {
                tx.symlink(vi, parent, name, &t.to_string_lossy().replace('\\', "/"))?;
            }
        }
        return Ok(tx);
    }
    let len = md.len();
    let mut target_name = name.to_string();
    let mut overwrite = None;
    if let Some(e) = tx.lookup(vi, parent, name)? {
        match ctx.conflict {
            Conflict::Skip => {
                ctx.st.skipped += 1;
                ctx.st.done += len;
                return Ok(tx);
            }
            Conflict::Overwrite if !e.is_dir() => overwrite = Some(e.file_id),
            _ => target_name = unique_name(&mut tx, vi, parent, name)?,
        }
    }
    let mut f = std::io::BufReader::with_capacity(1 << 20, std::fs::File::open(p)?);
    let base = ctx.st.done;
    let total = ctx.st.total;
    let ev = ctx.ev.clone();
    let cancel = ctx.cancel.clone();
    let shown = name.to_string();
    let mut prog = |d: u64| {
        let _ = ev.send(Event::Progress(ProgressInfo { title: "正在导入".into(), current: shown.clone(), done: base + d, total }));
        !cancel.load(Ordering::SeqCst)
    };
    let meta = NewFileMeta { mod_time: mtime, create_time: ctime, ..NewFileMeta::file() };
    match overwrite {
        Some(ino) => tx.overwrite_file(vi, ino, &mut f, len, mtime, &mut prog)?,
        None => {
            tx.create_file(vi, parent, &target_name, &mut f, len, &meta, &mut prog)?;
        }
    }
    ctx.st.done += len;
    ctx.st.files += 1;
    ctx.st.pending_bytes += len;
    ctx.st.pending_items += 1;
    let _ = ctx.ev.send(Event::Progress(ProgressInfo { title: "正在导入".into(), current: name.to_string(), done: ctx.st.done, total }));
    // Commit periodically so that large imports are durable in steps.
    if ctx.st.pending_items >= 256 || ctx.st.pending_bytes >= 1 << 30 {
        ctx.st.pending_items = 0;
        ctx.st.pending_bytes = 0;
        tx = tx.commit_continue()?;
    }
    Ok(tx)
}
