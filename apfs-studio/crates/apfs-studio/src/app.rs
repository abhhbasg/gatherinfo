//! Main window.

use crate::fmt;
use crate::theme::{self, Icon, Palette};
use crate::worker::*;
use crossbeam_channel::{Receiver, Sender};
use eframe::egui::{self, Align, Color32, CornerRadius, Key, Layout, RichText, Sense, Stroke, Vec2};
use egui_extras::{Column, TableBuilder};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Clone, PartialEq)]
struct Nav {
    vref: VolRef,
    /// (name, inode) from the root ("", 2) downwards.
    stack: Vec<(String, u64)>,
}

impl Nav {
    fn ino(&self) -> u64 {
        self.stack.last().map(|s| s.1).unwrap_or(2)
    }
    fn path(&self) -> String {
        let p: Vec<&str> = self.stack.iter().skip(1).map(|s| s.0.as_str()).collect();
        format!("/{}", p.join("/"))
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SortCol {
    Name,
    Size,
    Modified,
    Kind,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ToastKind {
    Info,
    Success,
    Error,
}

struct Toast {
    title: String,
    text: String,
    kind: ToastKind,
    born: Instant,
}

enum Dialog {
    NewFolder { name: String, focus: bool },
    Rename { old: String, new: String, focus: bool },
    Delete { names: Vec<String>, has_dir: bool },
    EnableWrite { src: SourceId, ctr: usize, label: String, blockers: Vec<(String, String)>, verify: bool },
    Import { paths: Vec<PathBuf>, conflict: Conflict },
    OpenDiskPath { path: String },
    Check { ok: bool, summary: String, errors: Vec<String>, warnings: Vec<String> },
    About,
}

pub struct App {
    cmd: Sender<Cmd>,
    ev: Receiver<Event>,
    cancel: Arc<AtomicBool>,
    pal: Palette,
    sources: Vec<SourceInfo>,
    nav: Option<Nav>,
    back: Vec<Nav>,
    fwd: Vec<Nav>,
    entries: Vec<EntryInfo>,
    view: Vec<usize>,
    loading: bool,
    list_err: Option<String>,
    req: u64,
    selected: BTreeSet<u64>,
    anchor: Option<usize>,
    sort: (SortCol, bool),
    filter: String,
    details: Option<DetailInfo>,
    show_details: bool,
    busy: bool,
    progress: Option<ProgressInfo>,
    toasts: Vec<Toast>,
    dialog: Option<Dialog>,
    last_check_target: Option<(SourceId, usize)>,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>, initial: Vec<PathBuf>) -> Self {
        theme::install_fonts(&cc.egui_ctx);
        let dark = cc.storage.and_then(|s| s.get_string("theme")).map(|t| t != "light").unwrap_or_else(|| {
            !matches!(cc.egui_ctx.system_theme(), Some(egui::Theme::Light))
        });
        let pal = if dark { theme::DARK } else { theme::LIGHT };
        theme::apply(&cc.egui_ctx, &pal);
        let (ctx_tx, ctx_rx) = crossbeam_channel::unbounded();
        let (ev_tx, ev_rx) = crossbeam_channel::unbounded();
        let cancel = Arc::new(AtomicBool::new(false));
        let ctx = cc.egui_ctx.clone();
        Worker::spawn(ctx_rx, ev_tx, cancel.clone(), Box::new(move || ctx.request_repaint()));
        for p in initial {
            let _ = ctx_tx.send(Cmd::OpenImage(p));
        }
        Self {
            cmd: ctx_tx,
            ev: ev_rx,
            cancel,
            pal,
            sources: Vec::new(),
            nav: None,
            back: Vec::new(),
            fwd: Vec::new(),
            entries: Vec::new(),
            view: Vec::new(),
            loading: false,
            list_err: None,
            req: 0,
            selected: BTreeSet::new(),
            anchor: None,
            sort: (SortCol::Name, true),
            filter: String::new(),
            details: None,
            show_details: true,
            busy: false,
            progress: None,
            toasts: Vec::new(),
            dialog: None,
            last_check_target: None,
        }
    }

    fn send(&self, c: Cmd) {
        let _ = self.cmd.send(c);
    }

    fn toast(&mut self, title: impl Into<String>, text: impl Into<String>, kind: ToastKind) {
        self.toasts.push(Toast { title: title.into(), text: text.into(), kind, born: Instant::now() });
        if self.toasts.len() > 5 {
            self.toasts.remove(0);
        }
    }

    // --- state helpers -------------------------------------------------------

    fn container_info(&self, src: SourceId, ctr: usize) -> Option<&ContainerInfo> {
        self.sources.iter().find(|s| s.id == src)?.containers.get(ctr)
    }

    fn volume_info(&self, v: VolRef) -> Option<&VolumeInfo> {
        self.container_info(v.src, v.ctr)?.volumes.iter().find(|x| x.index == v.vol)
    }

    /// Writable = container opened RW and volume has no blocker.
    fn can_write(&self) -> Result<(), String> {
        let Some(nav) = &self.nav else { return Err("未选择卷".into()) };
        let c = self.container_info(nav.vref.src, nav.vref.ctr).ok_or("容器不可用")?;
        if !c.writable {
            return Err("容器为只读模式（在左侧栏点击“启用写入”）".into());
        }
        if let Some(v) = self.volume_info(nav.vref) {
            if let Some(b) = &v.write_blocker {
                return Err(b.clone());
            }
        }
        if self.busy {
            return Err("正在执行其他操作".into());
        }
        Ok(())
    }

    fn navigate(&mut self, nav: Nav, record: bool) {
        if record {
            if let Some(cur) = self.nav.clone() {
                if cur != nav {
                    self.back.push(cur);
                    self.fwd.clear();
                }
            }
        }
        self.nav = Some(nav);
        self.selected.clear();
        self.anchor = None;
        self.details = None;
        self.filter.clear();
        self.refresh();
    }

    fn refresh(&mut self) {
        if let Some(nav) = &self.nav {
            self.req += 1;
            self.loading = true;
            self.send(Cmd::List { vref: nav.vref, ino: nav.ino(), req: self.req });
        }
    }

    fn open_volume(&mut self, vref: VolRef) {
        if let Some(v) = self.volume_info(vref) {
            if let Some(b) = v.read_blocker.clone() {
                self.toast("无法打开卷", b, ToastKind::Error);
                return;
            }
        }
        self.navigate(Nav { vref, stack: vec![(String::new(), 2)] }, true);
    }

    fn enter(&mut self, e: &EntryInfo) {
        if e.kind != Kind::Dir {
            return;
        }
        if let Some(mut nav) = self.nav.clone() {
            nav.stack.push((e.name.clone(), e.ino));
            self.navigate(nav, true);
        }
    }

    fn go_up(&mut self) {
        if let Some(mut nav) = self.nav.clone() {
            if nav.stack.len() > 1 {
                nav.stack.pop();
                self.navigate(nav, true);
            }
        }
    }

    fn rebuild_view(&mut self) {
        let f = self.filter.to_lowercase();
        let mut v: Vec<usize> = (0..self.entries.len()).filter(|&i| f.is_empty() || self.entries[i].name.to_lowercase().contains(&f)).collect();
        let (col, asc) = self.sort;
        let e = &self.entries;
        v.sort_by(|&a, &b| {
            let (x, y) = (&e[a], &e[b]);
            // Folders first, always.
            let dirs = (y.kind == Kind::Dir).cmp(&(x.kind == Kind::Dir));
            if dirs != std::cmp::Ordering::Equal {
                return dirs;
            }
            let o = match col {
                SortCol::Name => natural_cmp(&x.name, &y.name),
                SortCol::Size => x.size.cmp(&y.size),
                SortCol::Modified => x.mtime.cmp(&y.mtime),
                SortCol::Kind => kind_label(x).cmp(&kind_label(y)).then_with(|| natural_cmp(&x.name, &y.name)),
            };
            if asc {
                o
            } else {
                o.reverse()
            }
        });
        self.view = v;
    }

    fn selected_entries(&self) -> Vec<&EntryInfo> {
        self.entries.iter().filter(|e| self.selected.contains(&e.ino)).collect()
    }

    fn request_details(&mut self) {
        if self.selected.len() == 1 {
            if let (Some(nav), Some(&ino)) = (&self.nav, self.selected.iter().next()) {
                self.send(Cmd::Details { vref: nav.vref, ino });
            }
        } else {
            self.details = None;
        }
    }

    // --- events -----------------------------------------------------------------

    fn poll(&mut self) {
        while let Ok(ev) = self.ev.try_recv() {
            match ev {
                Event::Sources(s) => {
                    self.sources = s;
                    // Drop navigation to sources that disappeared.
                    if let Some(nav) = &self.nav {
                        if self.volume_info(nav.vref).is_none() {
                            self.nav = None;
                            self.entries.clear();
                            self.view.clear();
                        }
                    }
                    if self.nav.is_none() {
                        // Auto-open the first readable volume.
                        let first = self.sources.iter().flat_map(|s| {
                            s.containers.iter().flat_map(move |c| c.volumes.iter().filter(|v| v.read_blocker.is_none()).map(move |v| VolRef { src: s.id, ctr: c.index, vol: v.index }))
                        }).next();
                        if let Some(v) = first {
                            self.open_volume(v);
                        }
                    }
                }
                Event::Listing { req, vref, ino, result } => {
                    if req != self.req || self.nav.as_ref().map(|n| (n.vref, n.ino())) != Some((vref, ino)) {
                        continue;
                    }
                    self.loading = false;
                    match result {
                        Ok(v) => {
                            self.entries = v;
                            self.list_err = None;
                            let live: BTreeSet<u64> = self.entries.iter().map(|e| e.ino).collect();
                            self.selected.retain(|i| live.contains(i));
                        }
                        Err(e) => {
                            self.entries.clear();
                            self.list_err = Some(e);
                        }
                    }
                    self.rebuild_view();
                }
                Event::Details(r) => match r {
                    Ok(d) => {
                        if self.selected.len() == 1 && self.selected.contains(&d.ino) {
                            self.details = Some(d);
                        }
                    }
                    Err(e) => self.toast("读取属性失败", e, ToastKind::Error),
                },
                Event::Progress(p) => self.progress = Some(p),
                Event::Busy(b) => {
                    self.busy = b;
                    if !b {
                        self.progress = None;
                    }
                }
                Event::Finished { title, result, refresh } => {
                    match result {
                        Ok(m) => self.toast(title, m, ToastKind::Success),
                        Err(e) => self.toast(title, e, ToastKind::Error),
                    }
                    if let (Some(v), Some(nav)) = (refresh, &self.nav) {
                        if nav.vref == v {
                            self.refresh();
                        }
                    }
                }
                Event::CheckDone { title: _, ok, errors, warnings, summary } => {
                    self.dialog = Some(Dialog::Check { ok, summary, errors, warnings });
                }
            }
        }
    }

    // --- actions ------------------------------------------------------------------

    fn act_open_image(&mut self) {
        if let Some(files) = rfd::FileDialog::new()
            .set_title("打开 APFS 磁盘镜像")
            .add_filter("磁盘镜像", &["img", "dmg", "bin", "raw", "iso", "dd", "apfs", "vhd"])
            .add_filter("所有文件", &["*"])
            .pick_files()
        {
            for f in files {
                self.send(Cmd::OpenImage(f));
            }
        }
    }

    fn act_scan(&mut self) {
        self.send(Cmd::ScanDisks);
    }

    fn act_export(&mut self) {
        let Some(nav) = &self.nav else { return };
        let items: Vec<(u64, String)> = self.selected_entries().iter().map(|e| (e.ino, e.name.clone())).collect();
        let items = if items.is_empty() {
            // Export the current folder.
            let name = nav.stack.last().map(|s| s.0.clone()).filter(|s| !s.is_empty()).unwrap_or_else(|| self.volume_info(nav.vref).map(|v| v.name.clone()).unwrap_or_else(|| "APFS".into()));
            vec![(nav.ino(), name)]
        } else {
            items
        };
        if let Some(dest) = rfd::FileDialog::new().set_title("选择导出位置").pick_folder() {
            self.send(Cmd::Export { vref: nav.vref, items, dest });
        }
    }

    fn act_import(&mut self, paths: Vec<PathBuf>) {
        if let Err(e) = self.can_write() {
            self.toast("无法导入", e, ToastKind::Error);
            return;
        }
        if !paths.is_empty() {
            self.dialog = Some(Dialog::Import { paths, conflict: Conflict::KeepBoth });
        }
    }

    fn act_delete(&mut self) {
        if let Err(e) = self.can_write() {
            self.toast("无法删除", e, ToastKind::Error);
            return;
        }
        let sel = self.selected_entries();
        let has_dir = sel.iter().any(|e| e.kind == Kind::Dir);
        let names: Vec<String> = sel.iter().map(|e| e.name.clone()).collect();
        if !names.is_empty() {
            self.dialog = Some(Dialog::Delete { names, has_dir });
        }
    }

    fn act_rename(&mut self) {
        if let Err(e) = self.can_write() {
            self.toast("无法重命名", e, ToastKind::Error);
            return;
        }
        let sel = self.selected_entries();
        if sel.len() == 1 {
            let n = sel[0].name.clone();
            self.dialog = Some(Dialog::Rename { old: n.clone(), new: n, focus: true });
        }
    }

    fn act_new_folder(&mut self) {
        if let Err(e) = self.can_write() {
            self.toast("无法新建文件夹", e, ToastKind::Error);
            return;
        }
        self.dialog = Some(Dialog::NewFolder { name: "新建文件夹".into(), focus: true });
    }

    fn act_enable_write(&mut self, src: SourceId, ctr: usize) {
        let Some(c) = self.container_info(src, ctr) else { return };
        let blockers = c.volumes.iter().filter_map(|v| v.write_blocker.clone().map(|b| (v.name.clone(), b))).collect();
        self.dialog = Some(Dialog::EnableWrite { src, ctr, label: c.label.clone(), blockers, verify: true });
    }

    // --- UI pieces -------------------------------------------------------------------

    fn title_bar(&mut self, ctx: &egui::Context) {
        let p = self.pal;
        egui::TopBottomPanel::top("titlebar")
            .exact_height(52.0)
            .frame(egui::Frame::new().fill(p.sidebar).inner_margin(egui::Margin::symmetric(14, 8)).stroke(Stroke::new(1.0_f32, p.border)))
            .show(ctx, |ui| {
                ui.horizontal_centered(|ui| {
                    let (r, _) = ui.allocate_exact_size(Vec2::splat(26.0), Sense::hover());
                    theme::paint_logo(ui.painter(), r);
                    ui.add_space(4.0);
                    ui.label(RichText::new("APFS Studio").size(17.0).strong());
                    ui.label(RichText::new("在 Windows 上读写 Mac 磁盘").size(12.5).color(p.weak));
                    ui.add_space(16.0);
                    if primary_button(ui, &p, "+  打开镜像").on_hover_text("打开 .img / .dmg（原始格式）等磁盘镜像").clicked() {
                        self.act_open_image();
                    }
                    if ghost_button(ui, &p, "⟳  扫描磁盘").on_hover_text("查找连接到电脑的 APFS 磁盘（需要管理员权限）").clicked() {
                        self.act_scan();
                    }
                    #[cfg(not(windows))]
                    if ghost_button(ui, &p, "打开设备…").clicked() {
                        self.dialog = Some(Dialog::OpenDiskPath { path: "/dev/".into() });
                    }
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ghost_button(ui, &p, "关于").clicked() {
                            self.dialog = Some(Dialog::About);
                        }
                        let t = if p.dark { "☀  浅色" } else { "🌙  深色" };
                        if ghost_button(ui, &p, t).clicked() {
                            self.pal = if p.dark { theme::LIGHT } else { theme::DARK };
                            theme::apply(ctx, &self.pal);
                        }
                        #[cfg(windows)]
                        if !crate::platform::is_elevated() && ghost_button(ui, &p, "🛡  以管理员身份重启").on_hover_text("访问物理磁盘需要管理员权限").clicked() {
                            crate::platform::relaunch_elevated();
                        }
                    });
                });
            });
    }

    fn sidebar(&mut self, ctx: &egui::Context) {
        let p = self.pal;
        egui::SidePanel::left("sidebar")
            .resizable(true)
            .default_width(290.0)
            .width_range(230.0..=460.0)
            .frame(egui::Frame::new().fill(p.sidebar).inner_margin(egui::Margin::symmetric(12, 12)).stroke(Stroke::new(1.0_f32, p.border)))
            .show(ctx, |ui| {
                ui.label(RichText::new("位置").size(12.0).color(p.weak).strong());
                ui.add_space(4.0);
                egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                    if self.sources.is_empty() {
                        ui.add_space(8.0);
                        ui.label(RichText::new("尚未打开任何磁盘。\n可以把镜像文件拖到窗口中。").color(p.weak));
                    }
                    let sources = self.sources.clone();
                    for s in &sources {
                        self.source_card(ui, s);
                        ui.add_space(10.0);
                    }
                });
            });
    }

    fn source_card(&mut self, ui: &mut egui::Ui, s: &SourceInfo) {
        let p = self.pal;
        egui::Frame::new().fill(p.card).corner_radius(CornerRadius::same(10)).stroke(Stroke::new(1.0_f32, p.border)).inner_margin(egui::Margin::same(10)).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                theme::icon(ui, Icon::Disk, 20.0, &p);
                ui.vertical(|ui| {
                    ui.add(egui::Label::new(RichText::new(&s.label).strong()).truncate()).on_hover_text(&s.path);
                    ui.label(RichText::new(format!("{} · {}", if s.is_disk { "物理磁盘" } else { "镜像文件" }, fmt::size(s.size))).size(12.0).color(p.weak));
                });
                ui.with_layout(Layout::right_to_left(Align::TOP), |ui| {
                    if ui.add(egui::Button::new(RichText::new("×").size(12.0)).frame(false)).on_hover_text("关闭").clicked() {
                        self.send(Cmd::Close(s.id));
                    }
                });
            });
            for c in &s.containers {
                ui.add_space(6.0);
                ui.separator();
                ui.horizontal(|ui| {
                    ui.label(RichText::new(format!("容器 · {}", c.label)).size(12.0).color(p.weak));
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if c.error.is_none() {
                            badge(ui, &p, if c.writable { "读写" } else { "只读" }, if c.writable { p.success } else { p.weak });
                        }
                    });
                });
                if let Some(e) = &c.error {
                    ui.label(RichText::new(e).color(p.danger).size(12.0));
                    continue;
                }
                if c.total > 0 {
                    let used = c.total.saturating_sub(c.free);
                    let frac = used as f32 / c.total as f32;
                    usage_bar(ui, &p, frac);
                    ui.label(RichText::new(format!("已用 {} / {}，可用 {}", fmt::size(used), fmt::size(c.total), fmt::size(c.free))).size(11.5).color(p.weak));
                }
                for w in &c.warnings {
                    ui.label(RichText::new(format!("⚠ {w}")).size(11.5).color(p.warning));
                }
                for v in &c.volumes {
                    let vref = VolRef { src: s.id, ctr: c.index, vol: v.index };
                    let active = self.nav.as_ref().map(|n| n.vref) == Some(vref);
                    let resp = volume_row(ui, &p, v, active);
                    if resp.clicked() {
                        self.open_volume(vref);
                    }
                    resp.on_hover_ui(|ui| {
                        ui.label(RichText::new(&v.name).strong());
                        ui.label(format!("UUID：{}", v.uuid));
                        if !v.formatted_by.is_empty() {
                            ui.label(format!("格式化工具：{}", v.formatted_by));
                        }
                        ui.label(format!("{} 个文件，{} 个文件夹，{} 个快照", v.files, v.dirs, v.snapshots));
                        ui.label(format!("大小写：{}", if v.case_insensitive { "不区分" } else { "区分" }));
                        if let Some(b) = &v.read_blocker {
                            ui.colored_label(p.danger, format!("不可读取：{b}"));
                        } else if let Some(b) = &v.write_blocker {
                            ui.colored_label(p.warning, format!("不可写入：{b}"));
                        }
                    });
                }
                ui.add_space(4.0);
                ui.horizontal(|ui| {
                    if c.writable {
                        if small_button(ui, &p, "切换为只读").clicked() {
                            self.send(Cmd::SetWritable { src: s.id, ctr: c.index, writable: false, verify: false });
                        }
                    } else if small_button(ui, &p, "✏ 启用写入").clicked() {
                        self.act_enable_write(s.id, c.index);
                    }
                    if small_button(ui, &p, "✔ 检查").on_hover_text("完整校验容器的一致性（只读）").clicked() {
                        self.last_check_target = Some((s.id, c.index));
                        self.send(Cmd::Check { src: s.id, ctr: c.index });
                    }
                });
            }
        });
    }

    fn status_bar(&mut self, ctx: &egui::Context) {
        let p = self.pal;
        egui::TopBottomPanel::bottom("status")
            .exact_height(30.0)
            .frame(egui::Frame::new().fill(p.sidebar).inner_margin(egui::Margin::symmetric(12, 4)).stroke(Stroke::new(1.0_f32, p.border)))
            .show(ctx, |ui| {
                ui.horizontal_centered(|ui| {
                    if let Some(nav) = &self.nav {
                        let n = self.view.len();
                        let sel = self.selected.len();
                        let mut t = format!("{n} 项");
                        if sel > 0 {
                            let bytes: u64 = self.selected_entries().iter().map(|e| e.size).sum();
                            t += &format!("，已选择 {sel} 项（{}）", fmt::size(bytes));
                        }
                        ui.label(RichText::new(t).size(12.0).color(p.weak));
                        if let Some(c) = self.container_info(nav.vref.src, nav.vref.ctr) {
                            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                let mode = match (c.writable, self.volume_info(nav.vref).and_then(|v| v.write_blocker.clone())) {
                                    (true, None) => ("● 可写入", p.success),
                                    (true, Some(_)) => ("● 此卷只读", p.warning),
                                    _ => ("● 只读", p.weak),
                                };
                                ui.label(RichText::new(mode.0).size(12.0).color(mode.1));
                                ui.label(RichText::new(format!("可用 {}", fmt::size(c.free))).size(12.0).color(p.weak));
                            });
                        }
                    } else {
                        ui.label(RichText::new("就绪").size(12.0).color(p.weak));
                    }
                });
            });
    }

    fn details_panel(&mut self, ctx: &egui::Context) {
        if !self.show_details || self.nav.is_none() {
            return;
        }
        let p = self.pal;
        egui::SidePanel::right("details")
            .resizable(true)
            .default_width(270.0)
            .width_range(220.0..=480.0)
            .frame(egui::Frame::new().fill(p.panel).inner_margin(egui::Margin::same(14)).stroke(Stroke::new(1.0_f32, p.border)))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("详细信息").size(15.0).strong());
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ui.add(egui::Button::new("×").frame(false)).clicked() {
                            self.show_details = false;
                        }
                    });
                });
                ui.add_space(6.0);
                let sel = self.selected_entries();
                if sel.len() != 1 {
                    let (count, bytes) = (sel.len(), sel.iter().map(|e| e.size).sum::<u64>());
                    ui.add_space(20.0);
                    ui.vertical_centered(|ui| {
                        if count == 0 {
                            if let Some(nav) = &self.nav {
                                if let Some(v) = self.volume_info(nav.vref) {
                                    theme::icon(ui, Icon::Volume, 48.0, &p);
                                    ui.label(RichText::new(&v.name).size(16.0).strong());
                                    ui.label(RichText::new(format!("{} · 已用 {}", if v.role.is_empty() { "APFS 卷".to_string() } else { format!("{} 卷", v.role) }, fmt::size(v.used))).color(p.weak));
                                    ui.add_space(8.0);
                                    ui.label(RichText::new("选择一个项目以查看详细信息").color(p.weak).size(12.0));
                                }
                            }
                        } else {
                            ui.label(RichText::new(format!("已选择 {count} 项")).size(16.0).strong());
                            ui.label(RichText::new(fmt::size(bytes)).color(p.weak));
                        }
                    });
                    return;
                }
                let e = sel[0].clone();
                ui.vertical_centered(|ui| {
                    theme::icon(ui, entry_icon(&e), 56.0, &p);
                    ui.add(egui::Label::new(RichText::new(disp(&e.name)).size(15.0).strong()).wrap());
                    ui.label(RichText::new(kind_label(&e)).color(p.weak).size(12.0));
                });
                ui.add_space(10.0);
                egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                    if let Some(d) = self.details.clone().filter(|d| d.ino == e.ino) {
                        egui::Grid::new("props").striped(false).num_columns(2).spacing([10.0, 6.0]).show(ui, |ui| {
                            for (k, v) in &d.props {
                                ui.label(RichText::new(k).color(p.weak).size(12.5));
                                ui.add(egui::Label::new(RichText::new(v).size(12.5)).wrap());
                                ui.end_row();
                            }
                        });
                        if !d.xattrs.is_empty() {
                            ui.add_space(10.0);
                            ui.label(RichText::new("扩展属性").strong());
                            for (n, l) in &d.xattrs {
                                ui.horizontal(|ui| {
                                    ui.add(egui::Label::new(RichText::new(n).size(12.0).monospace()).truncate());
                                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                        ui.label(RichText::new(fmt::size(*l)).size(12.0).color(p.weak));
                                    });
                                });
                            }
                        }
                    } else {
                        ui.spinner();
                    }
                });
            });
    }

    fn toolbar(&mut self, ui: &mut egui::Ui) {
        let p = self.pal;
        let Some(nav) = self.nav.clone() else { return };
        ui.horizontal(|ui| {
            if icon_button(ui, &p, "←", !self.back.is_empty(), "后退").clicked() {
                if let Some(b) = self.back.pop() {
                    if let Some(cur) = self.nav.clone() {
                        self.fwd.push(cur);
                    }
                    self.navigate(b, false);
                }
            }
            if icon_button(ui, &p, "→", !self.fwd.is_empty(), "前进").clicked() {
                if let Some(f) = self.fwd.pop() {
                    if let Some(cur) = self.nav.clone() {
                        self.back.push(cur);
                    }
                    self.navigate(f, false);
                }
            }
            if icon_button(ui, &p, "↑", nav.stack.len() > 1, "上一级 (Backspace)").clicked() {
                self.go_up();
            }
            if icon_button(ui, &p, "⟳", true, "刷新 (F5)").clicked() {
                self.refresh();
            }
            ui.add_space(6.0);
            // Breadcrumb.
            let vol_name = self.volume_info(nav.vref).map(|v| v.name.clone()).unwrap_or_default();
            egui::Frame::new().fill(p.card).corner_radius(CornerRadius::same(8)).stroke(Stroke::new(1.0_f32, p.border)).inner_margin(egui::Margin::symmetric(8, 3)).show(ui, |ui| {
                ui.set_width((ui.available_width() - 250.0).max(200.0));
                ui.horizontal(|ui| {
                    ui.spacing_mut().item_spacing.x = 2.0;
                    theme::icon(ui, Icon::Volume, 16.0, &p);
                    let n = nav.stack.len();
                    for (i, (name, _)) in nav.stack.iter().enumerate() {
                        let label = if i == 0 { vol_name.clone() } else { disp(name) };
                        let last = i + 1 == n;
                        let txt = RichText::new(label).color(if last { p.text } else { p.weak });
                        if ui.add(egui::Button::new(if last { txt.strong() } else { txt }).frame(false)).clicked() && !last {
                            let mut nn = nav.clone();
                            nn.stack.truncate(i + 1);
                            self.navigate(nn, true);
                        }
                        if !last {
                            ui.label(RichText::new("›").color(p.weak));
                        }
                    }
                });
            });
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                let r = ui.add(egui::TextEdit::singleline(&mut self.filter).hint_text("🔍  筛选当前文件夹").desired_width(220.0));
                if r.changed() {
                    self.rebuild_view();
                }
            });
        });
        ui.add_space(6.0);
        // Action bar.
        let writable = self.can_write();
        let has_sel = !self.selected.is_empty();
        ui.horizontal(|ui| {
            if action_button(ui, &p, "⬇  导出", true, if has_sel { "将所选项目复制到这台电脑" } else { "将当前文件夹复制到这台电脑" }).clicked() {
                self.act_export();
            }
            let wtip = writable.as_ref().err().cloned();
            let tip = |ok: &str| wtip.clone().unwrap_or_else(|| ok.to_string());
            if action_button(ui, &p, "⬆  导入文件", writable.is_ok(), &tip("将电脑上的文件复制到当前文件夹")).clicked() {
                if let Some(f) = rfd::FileDialog::new().set_title("选择要导入的文件").pick_files() {
                    self.act_import(f);
                }
            }
            if action_button(ui, &p, "⬆  导入文件夹", writable.is_ok(), &tip("将整个文件夹复制到当前文件夹")).clicked() {
                if let Some(f) = rfd::FileDialog::new().set_title("选择要导入的文件夹").pick_folders() {
                    self.act_import(f);
                }
            }
            if action_button(ui, &p, "+  新建文件夹", writable.is_ok(), &tip("在当前位置新建文件夹")).clicked() {
                self.act_new_folder();
            }
            if action_button(ui, &p, "✏  重命名", writable.is_ok() && self.selected.len() == 1, &tip("重命名所选项目 (F2)")).clicked() {
                self.act_rename();
            }
            if action_button(ui, &p, "🗑  删除", writable.is_ok() && has_sel, &tip("删除所选项目 (Delete)")).clicked() {
                self.act_delete();
            }
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if ui.add(egui::Button::new(RichText::new("ℹ  详情").size(13.5)).selected(self.show_details).corner_radius(CornerRadius::same(8)).min_size(Vec2::new(0.0, 30.0))).on_hover_text("显示/隐藏详细信息面板").clicked() {
                    self.show_details = !self.show_details;
                }
            });
        });
    }

    fn file_table(&mut self, ui: &mut egui::Ui) {
        let p = self.pal;
        if let Some(e) = &self.list_err {
            empty_state(ui, &p, "无法读取此文件夹", e);
            return;
        }
        if self.loading && self.entries.is_empty() {
            ui.centered_and_justified(|ui| {
                ui.spinner();
            });
            return;
        }
        if self.view.is_empty() {
            if self.filter.is_empty() {
                empty_state(ui, &p, "此文件夹为空", if self.can_write().is_ok() { "把文件拖到这里即可复制到 Mac 磁盘" } else { "" });
            } else {
                empty_state(ui, &p, "没有匹配的项目", "");
            }
            return;
        }
        let row_h = 30.0;
        let mut clicked: Option<(usize, egui::Modifiers)> = None;
        let mut double: Option<usize> = None;
        let mut context: Option<usize> = None;
        let mut sort_click: Option<SortCol> = None;
        let mods = ui.input(|i| i.modifiers);
        let view = self.view.clone();
        let sort = self.sort;
        let header = |ui: &mut egui::Ui, title: &str, col: SortCol, sc: &mut Option<SortCol>| {
            let arrow = if sort.0 == col { if sort.1 { "  ▲" } else { "  ▼" } } else { "" };
            let r = ui.add(egui::Button::new(RichText::new(format!("{title}{arrow}")).size(12.5).color(p.weak).strong()).frame(false));
            if r.clicked() {
                *sc = Some(col);
            }
        };
        let avail = ui.available_width();
        egui::Frame::new().fill(p.panel).show(ui, |ui| {
            TableBuilder::new(ui)
                .striped(true)
                .resizable(true)
                .sense(Sense::click())
                .cell_layout(Layout::left_to_right(Align::Center))
                .column(Column::remainder().at_least(220.0).clip(true))
                .column(Column::initial(96.0).at_least(70.0))
                .column(Column::initial(150.0).at_least(110.0))
                .column(Column::initial((avail * 0.14).clamp(90.0, 160.0)).at_least(80.0))
                .header(26.0, |mut h| {
                    h.col(|ui| header(ui, "名称", SortCol::Name, &mut sort_click));
                    h.col(|ui| {
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| header(ui, "大小", SortCol::Size, &mut sort_click));
                    });
                    h.col(|ui| header(ui, "修改日期", SortCol::Modified, &mut sort_click));
                    h.col(|ui| header(ui, "类型", SortCol::Kind, &mut sort_click));
                })
                .body(|body| {
                    body.rows(row_h, view.len(), |mut row| {
                        let vi = row.index();
                        let e = &self.entries[view[vi]];
                        let sel = self.selected.contains(&e.ino);
                        row.set_selected(sel);
                        row.col(|ui| {
                            ui.add_space(4.0);
                            theme::icon(ui, entry_icon(e), 20.0, &p);
                            ui.add_space(2.0);
                            let mut t = RichText::new(disp(&e.name));
                            if e.name.starts_with('.') {
                                t = t.color(p.weak);
                            }
                            ui.add(egui::Label::new(t).truncate().selectable(false));
                            if e.compressed {
                                ui.label(RichText::new("压缩").size(10.5).color(p.weak));
                            }
                            if let Some(t) = &e.link_target {
                                ui.label(RichText::new(format!("→ {t}")).size(12.0).color(p.weak));
                            }
                        });
                        row.col(|ui| {
                            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                let s = match e.kind {
                                    Kind::Dir => format!("{} 项", e.children),
                                    Kind::File => fmt::size(e.size),
                                    _ => "—".into(),
                                };
                                ui.add(egui::Label::new(RichText::new(s).color(p.weak).size(13.0)).selectable(false));
                            });
                        });
                        row.col(|ui| {
                            ui.add(egui::Label::new(RichText::new(fmt::datetime(e.mtime)).color(p.weak).size(13.0)).selectable(false));
                        });
                        row.col(|ui| {
                            ui.add(egui::Label::new(RichText::new(kind_label(e)).color(p.weak).size(13.0)).selectable(false).truncate());
                        });
                        let r = row.response();
                        if r.double_clicked() {
                            double = Some(vi);
                        } else if r.clicked() {
                            clicked = Some((vi, mods));
                        }
                        if r.secondary_clicked() {
                            context = Some(vi);
                        }
                        r.context_menu(|ui| {
                            self.row_menu(ui);
                        });
                    });
                });
        });
        if let Some(c) = sort_click {
            if self.sort.0 == c {
                self.sort.1 = !self.sort.1;
            } else {
                self.sort = (c, true);
            }
            self.rebuild_view();
        }
        if let Some((vi, m)) = clicked {
            let ino = self.entries[self.view[vi]].ino;
            if m.command || m.ctrl {
                if !self.selected.remove(&ino) {
                    self.selected.insert(ino);
                }
                self.anchor = Some(vi);
            } else if m.shift {
                let a = self.anchor.unwrap_or(vi);
                let (lo, hi) = (a.min(vi), a.max(vi));
                self.selected = (lo..=hi).map(|k| self.entries[self.view[k]].ino).collect();
            } else {
                self.selected = [ino].into();
                self.anchor = Some(vi);
            }
            self.request_details();
        }
        if let Some(vi) = context {
            let ino = self.entries[self.view[vi]].ino;
            if !self.selected.contains(&ino) {
                self.selected = [ino].into();
                self.anchor = Some(vi);
                self.request_details();
            }
        }
        if let Some(vi) = double {
            let e = self.entries[self.view[vi]].clone();
            if e.kind == Kind::Dir {
                self.enter(&e);
            } else {
                self.selected = [e.ino].into();
                self.act_export();
            }
        }
    }

    fn row_menu(&mut self, ui: &mut egui::Ui) {
        let w = self.can_write().is_ok();
        let n = self.selected.len().max(1);
        if self.selected.len() == 1 {
            if let Some(e) = self.selected_entries().first().map(|e| (*e).clone()) {
                if e.kind == Kind::Dir && ui.button("打开").clicked() {
                    self.enter(&e);
                    ui.close_menu();
                }
            }
        }
        if ui.button(format!("导出 {n} 项到电脑…")).clicked() {
            self.act_export();
            ui.close_menu();
        }
        ui.separator();
        if ui.add_enabled(w && self.selected.len() <= 1, egui::Button::new("重命名")).clicked() {
            self.act_rename();
            ui.close_menu();
        }
        if ui.add_enabled(w, egui::Button::new(RichText::new("删除").color(self.pal.danger))).clicked() {
            self.act_delete();
            ui.close_menu();
        }
        ui.separator();
        if ui.button("属性").clicked() {
            self.show_details = true;
            self.request_details();
            ui.close_menu();
        }
    }

    fn welcome(&mut self, ui: &mut egui::Ui) {
        let p = self.pal;
        ui.vertical_centered(|ui| {
            ui.add_space((ui.available_height() * 0.18).max(20.0));
            let (r, _) = ui.allocate_exact_size(Vec2::splat(72.0), Sense::hover());
            theme::paint_logo(ui.painter(), r);
            ui.add_space(14.0);
            ui.label(RichText::new("欢迎使用 APFS Studio").size(26.0).strong());
            ui.add_space(4.0);
            ui.label(RichText::new("浏览、导出并写入 Mac 格式（APFS）的磁盘与镜像").size(15.0).color(p.weak));
            ui.add_space(22.0);
            ui.horizontal(|ui| {
                let w = 460.0;
                ui.add_space(((ui.available_width() - w) / 2.0).max(0.0));
                if primary_button(ui, &p, "+  打开磁盘镜像").clicked() {
                    self.act_open_image();
                }
                ui.add_space(8.0);
                if ghost_button(ui, &p, "⟳  扫描已连接的磁盘").clicked() {
                    self.act_scan();
                }
            });
            ui.add_space(26.0);
            egui::Frame::new().fill(p.card).corner_radius(CornerRadius::same(12)).stroke(Stroke::new(1.0_f32, p.border)).inner_margin(egui::Margin::same(18)).show(ui, |ui| {
                ui.set_max_width(560.0);
                for (t, d) in [
                    ("安全优先", "默认只读打开；写入采用写时复制与检查点提交，断电也不会损坏原有数据。"),
                    ("完整读取", "支持 zlib / LZVN / LZFSE 压缩文件、符号链接、扩展属性与快照浏览。"),
                    ("直接写入", "导入文件与文件夹、新建、重命名、删除，可随时运行一致性检查。"),
                ] {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("●").color(p.accent));
                        ui.label(RichText::new(t).strong());
                        ui.label(RichText::new(d).color(p.weak));
                    });
                }
            });
            ui.add_space(14.0);
            ui.label(RichText::new("提示：可以直接把 .img / .dmg 文件拖入窗口").size(12.5).color(p.weak));
        });
    }

    fn dialogs(&mut self, ctx: &egui::Context) {
        let p = self.pal;
        let Some(mut d) = self.dialog.take() else { return };
        let mut close = false;
        let enter = ctx.input(|i| i.key_pressed(Key::Enter));
        let esc = ctx.input(|i| i.key_pressed(Key::Escape));
        let modal = egui::Modal::new(egui::Id::new("dialog")).frame(
            egui::Frame::new().fill(p.card).corner_radius(CornerRadius::same(14)).stroke(Stroke::new(1.0_f32, p.border)).inner_margin(egui::Margin::same(20)).shadow(ctx.style().visuals.window_shadow),
        );
        let resp = modal.show(ctx, |ui| {
            ui.set_width(440.0);
            match &mut d {
                Dialog::NewFolder { name, focus } => {
                    ui.label(RichText::new("新建文件夹").size(18.0).strong());
                    ui.add_space(10.0);
                    let r = ui.add(egui::TextEdit::singleline(name).desired_width(f32::INFINITY));
                    if *focus {
                        r.request_focus();
                        if r.has_focus() {
                            *focus = false;
                        }
                    }
                    ui.add_space(14.0);
                    let (ok, cancel) = dialog_buttons(ui, &p, "创建", false);
                    if (ok || enter) && !name.trim().is_empty() {
                        if let Some(nav) = &self.nav {
                            self.send(Cmd::Mkdir { vref: nav.vref, parent: nav.ino(), name: name.trim().to_string() });
                        }
                        close = true;
                    }
                    close |= cancel || esc;
                }
                Dialog::Rename { old, new, focus } => {
                    ui.label(RichText::new("重命名").size(18.0).strong());
                    ui.label(RichText::new(format!("原名称：{old}")).color(p.weak));
                    ui.add_space(10.0);
                    let r = ui.add(egui::TextEdit::singleline(new).desired_width(f32::INFINITY));
                    if *focus {
                        r.request_focus();
                        if r.has_focus() {
                            *focus = false;
                        }
                    }
                    ui.add_space(14.0);
                    let (ok, cancel) = dialog_buttons(ui, &p, "重命名", false);
                    if (ok || enter) && !new.trim().is_empty() && new.trim() != old {
                        if let Some(nav) = &self.nav {
                            self.send(Cmd::Rename { vref: nav.vref, parent: nav.ino(), old: old.clone(), new: new.trim().to_string() });
                        }
                        close = true;
                    }
                    close |= cancel || esc;
                }
                Dialog::Delete { names, has_dir } => {
                    ui.label(RichText::new("确认删除").size(18.0).strong());
                    ui.add_space(6.0);
                    let what = if names.len() == 1 { format!("“{}”", disp(&names[0])) } else { format!("这 {} 个项目", names.len()) };
                    let extra = if *has_dir { "文件夹会连同其中的所有内容一起删除。" } else { "" };
                    ui.label(format!("将从 Mac 磁盘上永久删除{what}。{extra}此操作无法撤销。"));
                    ui.add_space(14.0);
                    let (ok, cancel) = dialog_buttons(ui, &p, "删除", true);
                    if ok {
                        if let Some(nav) = &self.nav {
                            self.send(Cmd::Delete { vref: nav.vref, parent: nav.ino(), names: names.clone() });
                        }
                        self.selected.clear();
                        close = true;
                    }
                    close |= cancel || esc;
                }
                Dialog::EnableWrite { src, ctr, label, blockers, verify } => {
                    ui.label(RichText::new("启用写入").size(18.0).strong());
                    ui.add_space(6.0);
                    ui.label(format!("容器：{label}"));
                    ui.add_space(6.0);
                    egui::Frame::new().fill(p.accent_soft).corner_radius(CornerRadius::same(8)).inner_margin(egui::Margin::same(10)).show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.label("APFS Studio 采用写时复制：修改总是写入空闲空间，最后一步才提交新的检查点。即使中途断电或拔出磁盘，磁盘也会保持修改前的状态。");
                    });
                    ui.add_space(6.0);
                    ui.label(RichText::new("请务必先在 Mac 上“推出”磁盘，并确保重要数据已有备份。写入期间不要拔出磁盘。").color(p.warning));
                    if !blockers.is_empty() {
                        ui.add_space(6.0);
                        ui.label(RichText::new("以下卷仍将保持只读：").strong());
                        for (n, b) in blockers.iter() {
                            ui.label(RichText::new(format!("• {n}：{b}")).size(12.5).color(p.weak));
                        }
                    }
                    ui.add_space(8.0);
                    ui.checkbox(verify, "启用前先完整检查容器一致性（推荐）");
                    ui.add_space(10.0);
                    let (ok, cancel) = dialog_buttons(ui, &p, "我已了解，启用写入", false);
                    if ok {
                        self.send(Cmd::SetWritable { src: *src, ctr: *ctr, writable: true, verify: *verify });
                        close = true;
                    }
                    close |= cancel || esc;
                }
                Dialog::Import { paths, conflict } => {
                    ui.label(RichText::new("导入到 Mac 磁盘").size(18.0).strong());
                    ui.add_space(6.0);
                    let dest = self.nav.as_ref().map(|n| n.path()).unwrap_or_default();
                    ui.label(format!("将 {} 个项目复制到 {dest}", paths.len()));
                    for pth in paths.iter().take(4) {
                        ui.label(RichText::new(format!("• {}", pth.display())).size(12.5).color(p.weak));
                    }
                    if paths.len() > 4 {
                        ui.label(RichText::new(format!("… 以及另外 {} 项", paths.len() - 4)).size(12.5).color(p.weak));
                    }
                    ui.add_space(10.0);
                    ui.label(RichText::new("遇到同名文件时：").strong());
                    ui.radio_value(conflict, Conflict::KeepBoth, "保留两者（自动重命名新文件）");
                    ui.radio_value(conflict, Conflict::Overwrite, "覆盖已有文件");
                    ui.radio_value(conflict, Conflict::Skip, "跳过");
                    ui.add_space(14.0);
                    let (ok, cancel) = dialog_buttons(ui, &p, "开始导入", false);
                    if ok || enter {
                        if let Some(nav) = &self.nav {
                            self.send(Cmd::Import { vref: nav.vref, parent: nav.ino(), paths: paths.clone(), conflict: *conflict });
                        }
                        close = true;
                    }
                    close |= cancel || esc;
                }
                Dialog::OpenDiskPath { path } => {
                    ui.label(RichText::new("打开设备").size(18.0).strong());
                    ui.add_space(8.0);
                    ui.add(egui::TextEdit::singleline(path).desired_width(f32::INFINITY));
                    ui.add_space(14.0);
                    let (ok, cancel) = dialog_buttons(ui, &p, "打开", false);
                    if ok || enter {
                        self.send(Cmd::OpenDisk(PathBuf::from(path.trim())));
                        close = true;
                    }
                    close |= cancel || esc;
                }
                Dialog::Check { ok, summary, errors, warnings } => {
                    ui.set_width(560.0);
                    ui.horizontal(|ui| {
                        let (c, t) = if *ok { (p.success, "✔ 未发现问题") } else { (p.danger, "✖ 发现问题") };
                        ui.label(RichText::new(t).size(19.0).strong().color(c));
                    });
                    ui.label(RichText::new(summary.as_str()).color(p.weak));
                    ui.add_space(8.0);
                    egui::ScrollArea::vertical().max_height(320.0).show(ui, |ui| {
                        for e in errors.iter() {
                            ui.label(RichText::new(format!("错误：{e}")).color(p.danger).size(12.5));
                        }
                        for w in warnings.iter() {
                            ui.label(RichText::new(format!("提示：{w}")).color(p.warning).size(12.5));
                        }
                    });
                    ui.add_space(12.0);
                    ui.allocate_ui_with_layout(Vec2::new(ui.available_width(), 34.0), Layout::right_to_left(Align::Center), |ui| {
                        if primary_button(ui, &p, "完成").clicked() || esc || enter {
                            close = true;
                        }
                    });
                }
                Dialog::About => {
                    ui.horizontal(|ui| {
                        let (r, _) = ui.allocate_exact_size(Vec2::splat(44.0), Sense::hover());
                        theme::paint_logo(ui.painter(), r);
                        ui.vertical(|ui| {
                            ui.label(RichText::new("APFS Studio").size(19.0).strong());
                            ui.label(RichText::new(format!("版本 {}", env!("CARGO_PKG_VERSION"))).color(p.weak));
                        });
                    });
                    ui.add_space(10.0);
                    ui.label("独立实现的 APFS 读写工具。读取支持压缩文件、扩展属性与快照；写入通过写时复制与检查点提交保证断电安全。");
                    ui.add_space(6.0);
                    ui.label(RichText::new("不支持：解锁 FileVault 加密卷、写入含快照/封存的卷、Fusion 融合硬盘写入。遇到这些情况会自动保持只读。").color(p.weak).size(12.5));
                    ui.add_space(14.0);
                    ui.allocate_ui_with_layout(Vec2::new(ui.available_width(), 34.0), Layout::right_to_left(Align::Center), |ui| {
                        if primary_button(ui, &p, "好").clicked() || esc || enter {
                            close = true;
                        }
                    });
                }
            }
        });
        if resp.should_close() && !matches!(d, Dialog::EnableWrite { .. } | Dialog::Delete { .. }) {
            close = true;
        }
        if !close {
            self.dialog = Some(d);
        }
    }

    fn overlays(&mut self, ctx: &egui::Context) {
        let p = self.pal;
        // Progress card.
        if self.busy {
            egui::Area::new(egui::Id::new("progress")).anchor(egui::Align2::CENTER_BOTTOM, Vec2::new(0.0, -46.0)).order(egui::Order::Foreground).show(ctx, |ui| {
                egui::Frame::new().fill(p.card).corner_radius(CornerRadius::same(12)).stroke(Stroke::new(1.0_f32, p.border)).shadow(ctx.style().visuals.popup_shadow).inner_margin(egui::Margin::same(14)).show(ui, |ui| {
                    ui.set_width(420.0);
                    let pr = self.progress.clone().unwrap_or_default();
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(RichText::new(if pr.title.is_empty() { "正在处理…".to_string() } else { pr.title.clone() }).strong());
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            if ghost_button(ui, &p, "取消").clicked() {
                                self.cancel.store(true, Ordering::SeqCst);
                            }
                        });
                    });
                    if !pr.current.is_empty() {
                        ui.add(egui::Label::new(RichText::new(&pr.current).size(12.0).color(p.weak)).truncate());
                    }
                    if pr.total > 0 {
                        let f = (pr.done as f32 / pr.total as f32).clamp(0.0, 1.0);
                        ui.add(egui::ProgressBar::new(f).desired_height(8.0).fill(p.accent).corner_radius(CornerRadius::same(4)));
                        ui.label(RichText::new(format!("{} / {}  ({:.0}%)", fmt::size(pr.done), fmt::size(pr.total), f * 100.0)).size(12.0).color(p.weak));
                    }
                });
            });
        }
        // Toasts.
        self.toasts.retain(|t| t.born.elapsed() < Duration::from_secs(if t.kind == ToastKind::Error { 12 } else { 6 }));
        if !self.toasts.is_empty() {
            ctx.request_repaint_after(Duration::from_millis(300));
        }
        let mut dismiss = None;
        egui::Area::new(egui::Id::new("toasts")).anchor(egui::Align2::RIGHT_BOTTOM, Vec2::new(-16.0, -42.0)).order(egui::Order::Foreground).show(ctx, |ui| {
            for (i, t) in self.toasts.iter().enumerate().rev() {
                let c = match t.kind {
                    ToastKind::Info => p.accent,
                    ToastKind::Success => p.success,
                    ToastKind::Error => p.danger,
                };
                let r = egui::Frame::new().fill(p.card).corner_radius(CornerRadius::same(10)).stroke(Stroke::new(1.0_f32, p.border)).shadow(ctx.style().visuals.popup_shadow).inner_margin(egui::Margin::same(12)).show(ui, |ui| {
                    ui.set_width(340.0);
                    ui.horizontal(|ui| {
                        let (rr, _) = ui.allocate_exact_size(Vec2::new(4.0, 34.0), Sense::hover());
                        ui.painter().rect_filled(rr, CornerRadius::same(2), c);
                        ui.vertical(|ui| {
                            ui.label(RichText::new(&t.title).strong());
                            ui.add(egui::Label::new(RichText::new(&t.text).size(12.5).color(p.weak)).wrap());
                        });
                    });
                });
                if r.response.interact(Sense::click()).clicked() {
                    dismiss = Some(i);
                }
                ui.add_space(8.0);
            }
        });
        if let Some(i) = dismiss {
            self.toasts.remove(i);
        }
        // Drag-and-drop hint.
        let hovering = ctx.input(|i| !i.raw.hovered_files.is_empty());
        if hovering {
            let screen = ctx.screen_rect();
            let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("drop")));
            painter.rect_filled(screen, CornerRadius::ZERO, Color32::from_black_alpha(120));
            let card = egui::Rect::from_center_size(screen.center(), Vec2::new(420.0, 120.0));
            painter.rect_filled(card, CornerRadius::same(16), p.card);
            painter.rect_stroke(card, CornerRadius::same(16), Stroke::new(2.0_f32, p.accent), egui::StrokeKind::Inside);
            let msg = if self.nav.is_some() && self.can_write().is_ok() {
                format!("松开即可导入到 {}", self.nav.as_ref().map(|n| n.path()).unwrap_or_default())
            } else {
                "松开以打开磁盘镜像".to_string()
            };
            painter.text(card.center(), egui::Align2::CENTER_CENTER, msg, egui::FontId::proportional(17.0), p.text);
        }
    }

    fn handle_drops(&mut self, ctx: &egui::Context) {
        let dropped: Vec<PathBuf> = ctx.input(|i| i.raw.dropped_files.iter().filter_map(|f| f.path.clone()).collect());
        if dropped.is_empty() {
            return;
        }
        let looks_like_image = |p: &PathBuf| {
            p.is_file() && p.extension().map(|e| matches!(e.to_string_lossy().to_lowercase().as_str(), "img" | "dmg" | "bin" | "raw" | "dd" | "apfs" | "iso")).unwrap_or(false)
        };
        if self.nav.is_some() && self.can_write().is_ok() {
            self.act_import(dropped);
        } else if dropped.iter().all(looks_like_image) || self.nav.is_none() {
            for d in dropped {
                self.send(Cmd::OpenImage(d));
            }
        } else {
            let why = self.can_write().err().unwrap_or_default();
            self.toast("无法导入", why, ToastKind::Error);
        }
    }

    fn shortcuts(&mut self, ctx: &egui::Context) {
        if self.dialog.is_some() || ctx.wants_keyboard_input() {
            return;
        }
        let (del, f2, back, f5, sel_all, enter) = ctx.input(|i| {
            (
                i.key_pressed(Key::Delete),
                i.key_pressed(Key::F2),
                i.key_pressed(Key::Backspace),
                i.key_pressed(Key::F5),
                i.modifiers.command && i.key_pressed(Key::A),
                i.key_pressed(Key::Enter),
            )
        });
        if del {
            self.act_delete();
        }
        if f2 {
            self.act_rename();
        }
        if back {
            self.go_up();
        }
        if f5 {
            self.refresh();
        }
        if sel_all {
            self.selected = self.view.iter().map(|&i| self.entries[i].ino).collect();
        }
        if enter && self.selected.len() == 1 {
            if let Some(e) = self.selected_entries().first().map(|e| (*e).clone()) {
                self.enter(&e);
            }
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll();
        self.handle_drops(ctx);
        self.shortcuts(ctx);
        self.title_bar(ctx);
        self.status_bar(ctx);
        self.sidebar(ctx);
        self.details_panel(ctx);
        let p = self.pal;
        egui::CentralPanel::default().frame(egui::Frame::new().fill(p.panel).inner_margin(egui::Margin::symmetric(16, 12))).show(ctx, |ui| {
            if self.nav.is_none() {
                self.welcome(ui);
                return;
            }
            self.toolbar(ui);
            ui.add_space(8.0);
            self.file_table(ui);
        });
        self.dialogs(ctx);
        self.overlays(ctx);
    }

    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        storage.set_string("theme", if self.pal.dark { "dark".into() } else { "light".into() });
    }
}

// ---------------------------------------------------------------------------
// Widgets
// ---------------------------------------------------------------------------

fn primary_button(ui: &mut egui::Ui, p: &Palette, text: &str) -> egui::Response {
    ui.add(egui::Button::new(RichText::new(text).color(Color32::WHITE).strong()).fill(p.accent).corner_radius(CornerRadius::same(8)).min_size(Vec2::new(0.0, 32.0)))
}

fn ghost_button(ui: &mut egui::Ui, p: &Palette, text: &str) -> egui::Response {
    ui.add(egui::Button::new(RichText::new(text)).fill(p.card).stroke(Stroke::new(1.0_f32, p.border)).corner_radius(CornerRadius::same(8)).min_size(Vec2::new(0.0, 32.0)))
}

fn small_button(ui: &mut egui::Ui, p: &Palette, text: &str) -> egui::Response {
    ui.add(egui::Button::new(RichText::new(text).size(12.5)).fill(p.hover).corner_radius(CornerRadius::same(6)))
}

fn action_button(ui: &mut egui::Ui, p: &Palette, text: &str, enabled: bool, tip: &str) -> egui::Response {
    let b = egui::Button::new(RichText::new(text).size(13.5)).fill(p.card).stroke(Stroke::new(1.0_f32, p.border)).corner_radius(CornerRadius::same(8)).min_size(Vec2::new(0.0, 30.0));
    let r = ui.add_enabled(enabled, b);
    if enabled {
        r.on_hover_text(tip)
    } else {
        r.on_disabled_hover_text(tip)
    }
}

fn icon_button(ui: &mut egui::Ui, p: &Palette, text: &str, enabled: bool, tip: &str) -> egui::Response {
    let b = egui::Button::new(RichText::new(text).size(15.0)).fill(p.card).stroke(Stroke::new(1.0_f32, p.border)).corner_radius(CornerRadius::same(8)).min_size(Vec2::new(32.0, 30.0));
    ui.add_enabled(enabled, b).on_hover_text(tip)
}

fn badge(ui: &mut egui::Ui, _p: &Palette, text: &str, color: Color32) {
    egui::Frame::new().fill(color.gamma_multiply(0.18)).corner_radius(CornerRadius::same(6)).inner_margin(egui::Margin::symmetric(6, 1)).show(ui, |ui| {
        ui.label(RichText::new(text).size(11.0).color(color).strong());
    });
}

fn usage_bar(ui: &mut egui::Ui, p: &Palette, frac: f32) {
    let (r, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 6.0), Sense::hover());
    ui.painter().rect_filled(r, CornerRadius::same(3), p.hover);
    let mut f = r;
    f.set_width(r.width() * frac.clamp(0.0, 1.0));
    let c = if frac > 0.9 { p.danger } else if frac > 0.75 { p.warning } else { p.accent };
    ui.painter().rect_filled(f, CornerRadius::same(3), c);
}

fn volume_row(ui: &mut egui::Ui, p: &Palette, v: &VolumeInfo, active: bool) -> egui::Response {
    let h = 40.0;
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), h), Sense::click());
    let fill = if active {
        p.accent_soft
    } else if resp.hovered() {
        p.hover
    } else {
        Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, CornerRadius::same(8), fill);
    let icon_rect = egui::Rect::from_min_size(rect.min + Vec2::new(8.0, 10.0), Vec2::splat(20.0));
    theme::paint_icon(ui.painter(), icon_rect, if v.read_blocker.is_some() { Icon::Lock } else { Icon::Volume }, p);
    let x = icon_rect.right() + 10.0;
    ui.painter().text(egui::pos2(x, rect.top() + 12.0), egui::Align2::LEFT_CENTER, &v.name, egui::FontId::proportional(14.0), if v.read_blocker.is_some() { p.weak } else { p.text });
    let sub = format!("{}{}", if v.role.is_empty() { String::new() } else { format!("{} · ", v.role) }, fmt::size(v.used));
    ui.painter().text(egui::pos2(x, rect.top() + 28.0), egui::Align2::LEFT_CENTER, sub, egui::FontId::proportional(11.5), p.weak);
    if v.write_blocker.is_some() && v.read_blocker.is_none() {
        ui.painter().text(egui::pos2(rect.right() - 10.0, rect.center().y), egui::Align2::RIGHT_CENTER, "只读", egui::FontId::proportional(11.0), p.weak);
    }
    resp
}

fn empty_state(ui: &mut egui::Ui, p: &Palette, title: &str, sub: &str) {
    ui.vertical_centered(|ui| {
        ui.add_space((ui.available_height() * 0.3).max(10.0));
        theme::icon(ui, Icon::Folder, 56.0, p);
        ui.add_space(8.0);
        ui.label(RichText::new(title).size(17.0).strong());
        if !sub.is_empty() {
            ui.add(egui::Label::new(RichText::new(sub).color(p.weak)).wrap());
        }
    });
}

fn dialog_buttons(ui: &mut egui::Ui, p: &Palette, ok: &str, danger: bool) -> (bool, bool) {
    let mut r = (false, false);
    ui.allocate_ui_with_layout(Vec2::new(ui.available_width(), 34.0), Layout::right_to_left(Align::Center), |ui| {
        let fill = if danger { p.danger } else { p.accent };
        if ui.add(egui::Button::new(RichText::new(ok).color(Color32::WHITE).strong()).fill(fill).corner_radius(CornerRadius::same(8)).min_size(Vec2::new(80.0, 32.0))).clicked() {
            r.0 = true;
        }
        if ghost_button(ui, p, "取消").clicked() {
            r.1 = true;
        }
    });
    r
}

fn entry_icon(e: &EntryInfo) -> Icon {
    match e.kind {
        Kind::Dir => Icon::Folder,
        Kind::Symlink => Icon::Symlink,
        Kind::Other => Icon::Other,
        Kind::File => theme::icon_for_name(&e.name),
    }
}

fn kind_label(e: &EntryInfo) -> String {
    match e.kind {
        Kind::Dir => "文件夹".into(),
        Kind::Symlink => "符号链接".into(),
        Kind::Other => fmt::kind_name(e.mode).into(),
        Kind::File => fmt::file_type_label(&e.name),
    }
}

/// Names are displayed in NFC so decomposed (NFD) accents render correctly.
fn disp(name: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    if name.is_ascii() {
        name.to_string()
    } else {
        name.nfc().collect()
    }
}

/// Case-insensitive natural ordering ("file2" < "file10").
fn natural_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    let (mut x, mut y) = (a.chars().peekable(), b.chars().peekable());
    loop {
        match (x.peek().copied(), y.peek().copied()) {
            (None, None) => return a.cmp(b),
            (None, _) => return std::cmp::Ordering::Less,
            (_, None) => return std::cmp::Ordering::Greater,
            (Some(c), Some(d)) if c.is_ascii_digit() && d.is_ascii_digit() => {
                let mut n1 = String::new();
                while let Some(&c) = x.peek().filter(|c| c.is_ascii_digit()) {
                    n1.push(c);
                    x.next();
                }
                let mut n2 = String::new();
                while let Some(&d) = y.peek().filter(|c| c.is_ascii_digit()) {
                    n2.push(d);
                    y.next();
                }
                let t1 = n1.trim_start_matches('0');
                let t2 = n2.trim_start_matches('0');
                let o = t1.len().cmp(&t2.len()).then_with(|| t1.cmp(t2));
                if o != std::cmp::Ordering::Equal {
                    return o;
                }
            }
            (Some(c), Some(d)) => {
                let o = c.to_lowercase().cmp(d.to_lowercase());
                if o != std::cmp::Ordering::Equal {
                    return o;
                }
                x.next();
                y.next();
            }
        }
    }
}
