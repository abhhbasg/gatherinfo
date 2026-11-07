//! Visual design: palettes, fonts and painter-drawn icons.

use eframe::egui::{self, Color32, CornerRadius, FontData, FontDefinitions, FontFamily, Pos2, Rect, Shadow, Stroke, Vec2};
use std::sync::Arc;

#[derive(Clone, Copy)]
pub struct Palette {
    pub bg: Color32,
    pub panel: Color32,
    pub sidebar: Color32,
    pub card: Color32,
    pub hover: Color32,
    pub border: Color32,
    pub text: Color32,
    pub weak: Color32,
    pub accent: Color32,
    pub accent_soft: Color32,
    pub danger: Color32,
    pub success: Color32,
    pub warning: Color32,
    pub folder: Color32,
    pub dark: bool,
}

pub const DARK: Palette = Palette {
    bg: Color32::from_rgb(0x0f, 0x11, 0x15),
    panel: Color32::from_rgb(0x15, 0x18, 0x1e),
    sidebar: Color32::from_rgb(0x11, 0x14, 0x19),
    card: Color32::from_rgb(0x1b, 0x1f, 0x27),
    hover: Color32::from_rgb(0x24, 0x29, 0x33),
    border: Color32::from_rgb(0x2a, 0x2f, 0x39),
    text: Color32::from_rgb(0xe6, 0xe8, 0xeb),
    weak: Color32::from_rgb(0x95, 0x9e, 0xab),
    accent: Color32::from_rgb(0x4f, 0x8c, 0xff),
    accent_soft: Color32::from_rgb(0x1f, 0x33, 0x58),
    danger: Color32::from_rgb(0xef, 0x53, 0x50),
    success: Color32::from_rgb(0x34, 0xc7, 0x59),
    warning: Color32::from_rgb(0xf5, 0xa5, 0x24),
    folder: Color32::from_rgb(0x5a, 0xa9, 0xff),
    dark: true,
};

pub const LIGHT: Palette = Palette {
    bg: Color32::from_rgb(0xf5, 0xf6, 0xf8),
    panel: Color32::from_rgb(0xff, 0xff, 0xff),
    sidebar: Color32::from_rgb(0xee, 0xf0, 0xf4),
    card: Color32::from_rgb(0xff, 0xff, 0xff),
    hover: Color32::from_rgb(0xe8, 0xec, 0xf2),
    border: Color32::from_rgb(0xdf, 0xe3, 0xe9),
    text: Color32::from_rgb(0x15, 0x19, 0x22),
    weak: Color32::from_rgb(0x66, 0x6f, 0x7d),
    accent: Color32::from_rgb(0x25, 0x63, 0xeb),
    accent_soft: Color32::from_rgb(0xdb, 0xe7, 0xff),
    danger: Color32::from_rgb(0xdc, 0x26, 0x26),
    success: Color32::from_rgb(0x16, 0xa3, 0x4a),
    warning: Color32::from_rgb(0xd9, 0x77, 0x06),
    folder: Color32::from_rgb(0x3b, 0x82, 0xf6),
    dark: false,
};

pub fn apply(ctx: &egui::Context, p: &Palette) {
    let mut v = if p.dark { egui::Visuals::dark() } else { egui::Visuals::light() };
    v.panel_fill = p.panel;
    v.window_fill = p.card;
    v.extreme_bg_color = if p.dark { Color32::from_rgb(0x0c, 0x0e, 0x12) } else { Color32::WHITE };
    v.faint_bg_color = if p.dark { Color32::from_rgb(0x18, 0x1c, 0x23) } else { Color32::from_rgb(0xf8, 0xf9, 0xfb) };
    v.code_bg_color = p.hover;
    v.override_text_color = Some(p.text);
    v.hyperlink_color = p.accent;
    v.window_stroke = Stroke::new(1.0_f32, p.border);
    v.window_corner_radius = CornerRadius::same(12);
    v.menu_corner_radius = CornerRadius::same(10);
    v.window_shadow = Shadow { offset: [0, 10], blur: 30, spread: 0, color: Color32::from_black_alpha(if p.dark { 140 } else { 50 }) };
    v.popup_shadow = Shadow { offset: [0, 6], blur: 18, spread: 0, color: Color32::from_black_alpha(if p.dark { 120 } else { 40 }) };
    v.selection.bg_fill = p.accent_soft;
    v.selection.stroke = Stroke::new(1.0_f32, p.accent);
    let r = CornerRadius::same(7);
    v.widgets.noninteractive.bg_fill = p.panel;
    v.widgets.noninteractive.weak_bg_fill = p.panel;
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, p.border);
    v.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, p.text);
    v.widgets.noninteractive.corner_radius = r;
    v.widgets.inactive.bg_fill = p.hover;
    v.widgets.inactive.weak_bg_fill = if p.dark { Color32::from_rgb(0x1f, 0x23, 0x2c) } else { Color32::from_rgb(0xf0, 0xf2, 0xf5) };
    v.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, p.border);
    v.widgets.inactive.fg_stroke = Stroke::new(1.0_f32, p.text);
    v.widgets.inactive.corner_radius = r;
    v.widgets.hovered.bg_fill = p.hover;
    v.widgets.hovered.weak_bg_fill = p.hover;
    v.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, p.border);
    v.widgets.hovered.fg_stroke = Stroke::new(1.5_f32, p.text);
    v.widgets.hovered.corner_radius = r;
    v.widgets.active.bg_fill = p.accent_soft;
    v.widgets.active.weak_bg_fill = p.accent_soft;
    v.widgets.active.bg_stroke = Stroke::new(1.0_f32, p.accent);
    v.widgets.active.fg_stroke = Stroke::new(1.5_f32, p.text);
    v.widgets.active.corner_radius = r;
    v.widgets.open = v.widgets.active;
    v.striped = true;
    ctx.set_visuals(v);

    let mut style = (*ctx.style()).clone();
    style.spacing.item_spacing = Vec2::new(8.0, 6.0);
    style.spacing.button_padding = Vec2::new(10.0, 5.0);
    style.spacing.interact_size.y = 28.0;
    style.spacing.window_margin = egui::Margin::same(16);
    style.spacing.menu_margin = egui::Margin::same(6);
    style.spacing.scroll = egui::style::ScrollStyle::thin();
    use egui::{FontId, TextStyle};
    style.text_styles = [
        (TextStyle::Small, FontId::new(12.0, FontFamily::Proportional)),
        (TextStyle::Body, FontId::new(14.0, FontFamily::Proportional)),
        (TextStyle::Button, FontId::new(14.0, FontFamily::Proportional)),
        (TextStyle::Heading, FontId::new(20.0, FontFamily::Proportional)),
        (TextStyle::Monospace, FontId::new(13.0, FontFamily::Monospace)),
    ]
    .into();
    ctx.set_style(style);
}

/// Adds system UI and CJK fonts so Chinese/Japanese file names render.
pub fn install_fonts(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();
    let candidates: &[(&str, &str)] = &[
        ("segoe", r"C:\Windows\Fonts\segoeui.ttf"),
        ("yahei", r"C:\Windows\Fonts\msyh.ttc"),
        ("yahei", r"C:\Windows\Fonts\msyh.ttf"),
        ("simhei", r"C:\Windows\Fonts\simhei.ttf"),
        ("jhenghei", r"C:\Windows\Fonts\msjh.ttc"),
        ("meiryo", r"C:\Windows\Fonts\meiryo.ttc"),
        ("malgun", r"C:\Windows\Fonts\malgun.ttf"),
        ("segoe_sym", r"C:\Windows\Fonts\seguisym.ttf"),
        ("noto_cjk", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
        ("wqy", "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
        ("pingfang", "/System/Library/Fonts/PingFang.ttc"),
    ];
    let mut loaded: Vec<String> = Vec::new();
    for (name, path) in candidates {
        if loaded.iter().any(|l| l == name) {
            continue;
        }
        if let Ok(bytes) = std::fs::read(path) {
            fonts.font_data.insert((*name).to_owned(), Arc::new(FontData::from_owned(bytes)));
            loaded.push((*name).to_string());
        }
    }
    let prop = fonts.families.entry(FontFamily::Proportional).or_default();
    // Segoe UI first for Latin text, then the bundled default, then CJK fallbacks.
    if loaded.iter().any(|l| l == "segoe") {
        prop.insert(0, "segoe".into());
    }
    for l in &loaded {
        if l != "segoe" {
            prop.push(l.clone());
        }
    }
    let mono = fonts.families.entry(FontFamily::Monospace).or_default();
    for l in &loaded {
        if l != "segoe" {
            mono.push(l.clone());
        }
    }
    ctx.set_fonts(fonts);
}

// ---------------------------------------------------------------------------
// Icons (vector, painter-drawn so they stay crisp at any DPI)
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Icon {
    Folder,
    File,
    Image,
    Archive,
    Code,
    Doc,
    Media,
    Symlink,
    Other,
    Disk,
    Volume,
    Lock,
}

pub fn icon_for_name(name: &str) -> Icon {
    let ext = name.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default();
    match ext.as_str() {
        "png" | "jpg" | "jpeg" | "gif" | "heic" | "webp" | "bmp" | "tiff" | "svg" | "raw" | "dng" => Icon::Image,
        "zip" | "7z" | "rar" | "gz" | "tar" | "xz" | "bz2" | "dmg" | "iso" | "pkg" => Icon::Archive,
        "rs" | "c" | "h" | "cpp" | "py" | "js" | "ts" | "json" | "xml" | "html" | "css" | "sh" | "swift" | "go" | "java" | "plist" => Icon::Code,
        "txt" | "md" | "pdf" | "doc" | "docx" | "pages" | "rtf" | "xls" | "xlsx" | "numbers" | "ppt" | "pptx" | "key" | "csv" => Icon::Doc,
        "mp3" | "m4a" | "wav" | "flac" | "aac" | "mp4" | "mov" | "mkv" | "avi" | "m4v" => Icon::Media,
        _ => Icon::File,
    }
}

pub fn paint_icon(painter: &egui::Painter, rect: Rect, icon: Icon, p: &Palette) {
    let s = rect.width().min(rect.height());
    let c = rect.center();
    let r = Rect::from_center_size(c, Vec2::splat(s));
    let u = s / 16.0;
    match icon {
        Icon::Folder => {
            let tab = Rect::from_min_max(Pos2::new(r.left() + u, r.top() + 2.5 * u), Pos2::new(r.left() + 7.0 * u, r.top() + 5.0 * u));
            painter.rect_filled(tab, CornerRadius::same((1.5 * u) as u8), p.folder.gamma_multiply(0.75));
            let body = Rect::from_min_max(Pos2::new(r.left() + u, r.top() + 4.0 * u), Pos2::new(r.right() - u, r.bottom() - 2.0 * u));
            painter.rect_filled(body, CornerRadius::same((2.0 * u) as u8), p.folder);
            let shine = Rect::from_min_max(Pos2::new(body.left(), body.top() + 1.5 * u), Pos2::new(body.right(), body.top() + 2.3 * u));
            painter.rect_filled(shine, CornerRadius::ZERO, Color32::from_white_alpha(40));
        }
        Icon::File | Icon::Image | Icon::Archive | Icon::Code | Icon::Doc | Icon::Media | Icon::Symlink | Icon::Other => {
            let (fill, mark) = match icon {
                Icon::Image => (Color32::from_rgb(0x8b, 0x5c, 0xf6), true),
                Icon::Archive => (Color32::from_rgb(0xf5, 0x9e, 0x0b), true),
                Icon::Code => (Color32::from_rgb(0x10, 0xb9, 0x81), true),
                Icon::Doc => (Color32::from_rgb(0x3b, 0x82, 0xf6), true),
                Icon::Media => (Color32::from_rgb(0xec, 0x48, 0x99), true),
                _ => (p.weak, false),
            };
            let page = Rect::from_min_max(Pos2::new(r.left() + 3.0 * u, r.top() + u), Pos2::new(r.right() - 3.0 * u, r.bottom() - u));
            let bg = if p.dark { Color32::from_rgb(0x2b, 0x31, 0x3c) } else { Color32::from_rgb(0xf3, 0xf5, 0xf8) };
            painter.rect_filled(page, CornerRadius::same((1.8 * u) as u8), bg);
            painter.rect_stroke(page, CornerRadius::same((1.8 * u) as u8), Stroke::new(1.0_f32, fill.gamma_multiply(0.9)), egui::StrokeKind::Inside);
            // Folded corner.
            let fc = Pos2::new(page.right() - 3.5 * u, page.top());
            let pts = vec![fc, Pos2::new(page.right(), page.top() + 3.5 * u), Pos2::new(fc.x, page.top() + 3.5 * u)];
            painter.add(egui::Shape::convex_polygon(pts, fill.gamma_multiply(0.55), Stroke::NONE));
            if mark {
                let band = Rect::from_min_max(Pos2::new(page.left() + 1.5 * u, page.bottom() - 4.5 * u), Pos2::new(page.right() - 1.5 * u, page.bottom() - 2.0 * u));
                painter.rect_filled(band, CornerRadius::same((0.8 * u) as u8), fill);
            } else {
                for k in 0..3 {
                    let y = page.top() + (6.0 + 2.2 * k as f32) * u;
                    painter.line_segment([Pos2::new(page.left() + 2.0 * u, y), Pos2::new(page.right() - 2.0 * u, y)], Stroke::new(u * 0.8, p.weak.gamma_multiply(0.6)));
                }
            }
            if icon == Icon::Symlink {
                let a = Pos2::new(r.left() + 2.0 * u, r.bottom() - 2.0 * u);
                let b = Pos2::new(r.left() + 7.0 * u, r.bottom() - 7.0 * u);
                painter.circle_filled(Pos2::new(r.left() + 4.5 * u, r.bottom() - 4.5 * u), 3.6 * u, p.panel);
                painter.arrow(a, b - a, Stroke::new(1.6 * u, p.accent));
            }
        }
        Icon::Disk => {
            let body = Rect::from_min_max(Pos2::new(r.left() + u, r.top() + 4.0 * u), Pos2::new(r.right() - u, r.bottom() - 4.0 * u));
            painter.rect_filled(body, CornerRadius::same((2.5 * u) as u8), p.weak.gamma_multiply(0.85));
            painter.circle_filled(Pos2::new(body.right() - 3.0 * u, body.center().y), 1.2 * u, p.success);
            painter.line_segment([Pos2::new(body.left() + 2.5 * u, body.center().y), Pos2::new(body.center().x + u, body.center().y)], Stroke::new(u, p.panel));
        }
        Icon::Volume => {
            let body = Rect::from_min_max(Pos2::new(r.left() + 1.5 * u, r.top() + 2.0 * u), Pos2::new(r.right() - 1.5 * u, r.bottom() - 2.0 * u));
            painter.rect_filled(body, CornerRadius::same((3.0 * u) as u8), p.accent);
            painter.circle_stroke(body.center(), 3.5 * u, Stroke::new(1.3 * u, Color32::from_white_alpha(220)));
            painter.circle_filled(body.center(), 1.0 * u, Color32::WHITE);
        }
        Icon::Lock => {
            let body = Rect::from_min_max(Pos2::new(r.left() + 3.0 * u, r.top() + 7.0 * u), Pos2::new(r.right() - 3.0 * u, r.bottom() - 1.5 * u));
            painter.circle_stroke(Pos2::new(c.x, r.top() + 7.0 * u), 3.2 * u, Stroke::new(1.6 * u, p.warning));
            painter.rect_filled(body, CornerRadius::same((1.5 * u) as u8), p.warning);
        }
    }
}

/// Allocates space and paints an icon inline.
pub fn icon(ui: &mut egui::Ui, icon: Icon, size: f32, p: &Palette) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), egui::Sense::hover());
    paint_icon(ui.painter(), rect, icon, p);
    resp
}

/// The application logo (also used to build the window icon).
pub fn paint_logo(painter: &egui::Painter, rect: Rect) {
    let rr = CornerRadius::same((rect.width() * 0.24) as u8);
    painter.rect_filled(rect, rr, Color32::from_rgb(0x25, 0x63, 0xeb));
    let inner = rect.shrink(rect.width() * 0.2);
    painter.rect_filled(Rect::from_min_max(inner.min, Pos2::new(inner.max.x, inner.center().y - inner.height() * 0.06)), CornerRadius::same((rect.width() * 0.08) as u8), Color32::from_rgb(0x93, 0xc5, 0xfd));
    painter.rect_filled(Rect::from_min_max(Pos2::new(inner.min.x, inner.center().y + inner.height() * 0.06), inner.max), CornerRadius::same((rect.width() * 0.08) as u8), Color32::WHITE);
}

/// Procedural RGBA window icon.
pub fn window_icon() -> egui::IconData {
    let n = 64usize;
    let mut rgba = vec![0u8; n * n * 4];
    let radius = 14.0f32;
    for y in 0..n {
        for x in 0..n {
            let fx = x as f32 + 0.5;
            let fy = y as f32 + 0.5;
            let dx = (radius - fx).max(fx - (n as f32 - radius)).max(0.0);
            let dy = (radius - fy).max(fy - (n as f32 - radius)).max(0.0);
            let inside = (dx * dx + dy * dy).sqrt() <= radius;
            let i = (y * n + x) * 4;
            if !inside {
                continue;
            }
            let (mut r, mut g, mut b) = (0x25u8, 0x63u8, 0xebu8);
            let m = 13usize;
            if x >= m && x < n - m {
                if y >= m && y < n / 2 - 2 {
                    (r, g, b) = (0x93, 0xc5, 0xfd);
                } else if y >= n / 2 + 2 && y < n - m {
                    (r, g, b) = (0xff, 0xff, 0xff);
                }
            }
            rgba[i..i + 4].copy_from_slice(&[r, g, b, 255]);
        }
    }
    egui::IconData { rgba, width: n as u32, height: n as u32 }
}
