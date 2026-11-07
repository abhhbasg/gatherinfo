#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod app;
mod fmt;
mod i18n;
mod platform;
mod theme;
mod worker;

fn main() -> eframe::Result<()> {
    let initial: Vec<std::path::PathBuf> = std::env::args_os().skip(1).map(Into::into).collect();
    let viewport = eframe::egui::ViewportBuilder::default()
        .with_title("APFS Studio")
        .with_inner_size([1240.0, 780.0])
        .with_min_inner_size([860.0, 540.0])
        .with_drag_and_drop(true)
        .with_icon(std::sync::Arc::new(theme::window_icon()));
    let options = eframe::NativeOptions { viewport: viewport.clone(), centered: true, ..Default::default() };
    #[cfg(windows)]
    {
        // Direct3D (with the WARP software fallback) works even on machines
        // without OpenGL drivers; fall back to OpenGL if it is unavailable.
        let wgpu_opts = eframe::NativeOptions { renderer: eframe::Renderer::Wgpu, ..options };
        let first = initial.clone();
        match eframe::run_native("APFS Studio", wgpu_opts, Box::new(move |cc| Ok(Box::new(app::App::new(cc, first))))) {
            Ok(()) => return Ok(()),
            Err(_) => {
                let glow = eframe::NativeOptions { viewport, centered: true, renderer: eframe::Renderer::Glow, ..Default::default() };
                return eframe::run_native("APFS Studio", glow, Box::new(move |cc| Ok(Box::new(app::App::new(cc, initial)))));
            }
        }
    }
    #[cfg(not(windows))]
    eframe::run_native("APFS Studio", options, Box::new(move |cc| Ok(Box::new(app::App::new(cc, initial)))))
}
