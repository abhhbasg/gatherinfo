//! Display formatting helpers.

use apfs::format::*;
use chrono::{Local, TimeZone};

pub fn size(n: u64) -> String {
    const U: [&str; 6] = ["B", "KB", "MB", "GB", "TB", "PB"];
    if n < 1024 {
        return format!("{n} B");
    }
    let mut v = n as f64;
    let mut u = 0;
    while v >= 1024.0 && u < U.len() - 1 {
        v /= 1024.0;
        u += 1;
    }
    if v >= 100.0 {
        format!("{v:.0} {}", U[u])
    } else if v >= 10.0 {
        format!("{v:.1} {}", U[u])
    } else {
        format!("{v:.2} {}", U[u])
    }
}

pub fn thousands(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, ch) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out
}

pub fn datetime(ns: u64) -> String {
    if ns == 0 {
        return "—".into();
    }
    let secs = (ns / 1_000_000_000) as i64;
    let nsec = (ns % 1_000_000_000) as u32;
    match Local.timestamp_opt(secs, nsec) {
        chrono::LocalResult::Single(t) | chrono::LocalResult::Ambiguous(t, _) => t.format("%Y-%m-%d %H:%M").to_string(),
        chrono::LocalResult::None => "—".into(),
    }
}

pub fn kind_name(mode: u16) -> &'static str {
    match mode & S_IFMT {
        S_IFDIR => "文件夹",
        S_IFREG => "文件",
        S_IFLNK => "符号链接",
        S_IFIFO => "管道",
        S_IFSOCK => "套接字",
        S_IFCHR => "字符设备",
        S_IFBLK => "块设备",
        _ => "未知",
    }
}

pub fn file_type_label(name: &str) -> String {
    match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() && ext.len() <= 8 && !ext.is_empty() => format!("{} 文件", ext.to_uppercase()),
        _ => "文件".into(),
    }
}

pub fn mode_string(mode: u16) -> String {
    let t = match mode & S_IFMT {
        S_IFDIR => 'd',
        S_IFLNK => 'l',
        S_IFIFO => 'p',
        S_IFSOCK => 's',
        S_IFCHR => 'c',
        S_IFBLK => 'b',
        _ => '-',
    };
    let mut s = String::new();
    s.push(t);
    for shift in [6, 3, 0] {
        let b = (mode >> shift) & 7;
        s.push(if b & 4 != 0 { 'r' } else { '-' });
        s.push(if b & 2 != 0 { 'w' } else { '-' });
        s.push(if b & 1 != 0 { 'x' } else { '-' });
    }
    format!("{s} ({:o})", mode & 0o7777)
}

/// Makes an APFS file name safe for NTFS/Windows. Counts replacements.
pub fn safe_windows_name(name: &str, renamed: &mut u64) -> String {
    let mut out: String = name
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            c if (c as u32) < 32 => '_',
            c => c,
        })
        .collect();
    while out.ends_with('.') || out.ends_with(' ') {
        out.pop();
        out.push('_');
        if out.chars().all(|c| c == '_') {
            break;
        }
    }
    let upper = out.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = ["CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"];
    if reserved.contains(&upper.as_str()) {
        out.insert(0, '_');
    }
    if out.is_empty() {
        out = "_".into();
    }
    if out != name {
        *renamed += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names() {
        let mut n = 0;
        assert_eq!(safe_windows_name("a:b?.txt", &mut n), "a_b_.txt");
        assert_eq!(safe_windows_name("CON.txt", &mut n), "_CON.txt");
        assert_eq!(safe_windows_name("trail.", &mut n), "trail_");
        assert_eq!(safe_windows_name("ok.txt", &mut n), "ok.txt");
        assert_eq!(n, 3);
        assert_eq!(size(1536), "1.50 KB");
        assert_eq!(thousands(1234567), "1,234,567");
    }
}
