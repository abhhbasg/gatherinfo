//! Chinese user-facing texts for library errors and states.

use apfs::Error;

pub fn error_text(e: &Error) -> String {
    match e {
        Error::Io(io) => {
            let code = io.raw_os_error();
            match code {
                Some(5) => "拒绝访问（请以管理员身份运行，或检查文件是否被占用）".into(),
                Some(32) | Some(33) => "文件正被其他程序占用".into(),
                Some(2) | Some(3) => "找不到指定的文件或路径".into(),
                Some(112) => "目标磁盘空间不足".into(),
                _ => format!("读写错误：{io}"),
            }
        }
        Error::Corrupt(s) => format!("检测到文件系统损坏：{s}"),
        Error::Unsupported(s) => format!("不支持的操作：{}", blocker(s)),
        Error::NotFound(s) => format!("找不到：{s}"),
        Error::Exists(s) => format!("已存在同名项目：{s}"),
        Error::NotADirectory(s) => format!("不是文件夹：{s}"),
        Error::IsADirectory(s) => format!("这是一个文件夹：{s}"),
        Error::DirectoryNotEmpty(s) => format!("文件夹不为空：{s}"),
        Error::InvalidName(s) => format!("名称无效：{s}"),
        Error::NoSpace => "APFS 容器空间不足".into(),
        Error::ReadOnly => "容器以只读方式打开".into(),
        Error::Cancelled => "操作已取消".into(),
        Error::Other(s) => s.clone(),
    }
}

pub fn role(r: &str) -> &str {
    match r {
        "System" => "系统",
        "User" => "用户",
        "Recovery" => "恢复",
        "VM" => "虚拟内存",
        "Preboot" => "预启动",
        "Installer" => "安装器",
        "Data" => "数据",
        "Update" => "更新",
        "Backup" => "备份",
        "Hardware" => "硬件",
        "xART" => "xART",
        "Baseband" => "基带",
        "Prelogin" => "登录前",
        "Enterprise" => "企业",
        other => other,
    }
}

/// Translates the reasons returned by `read_blocker` / `write_blocker`.
pub fn blocker(r: &str) -> String {
    let table: [(&str, &str); 14] = [
        ("snapshots are read-only", "快照为只读"),
        ("volume is encrypted", "卷已加密（FileVault），暂不支持解锁"),
        ("sealed system volumes are read-only", "已签名封存的系统卷只能读取"),
        ("volume has snapshots", "卷包含快照（例如时间机器），写入会破坏快照，因此禁止写入"),
        ("unexpected manually-managed", "卷对象映射格式异常"),
        ("physical file-system tree", "不支持的物理文件系统树"),
        ("volume uses incompatible features", "卷使用了未知的不兼容特性"),
        ("read-only-compatible", "卷含只读兼容特性"),
        ("a snapshot revert is pending", "存在挂起的快照还原操作"),
        ("encryption rolling", "正在进行加密转换"),
        ("file extent tree", "卷含文件区段树（封存卷）"),
        ("hard-linked", "暂不支持修改带硬链接的文件"),
        ("directory statistics", "暂不支持维护目录统计信息的文件夹"),
        ("reaper has pending work", "容器有未完成的后台清理任务，请先在 Mac 上挂载一次"),
    ];
    for (k, v) in table {
        if r.contains(k) {
            return v.to_string();
        }
    }
    r.to_string()
}
