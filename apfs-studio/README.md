# APFS Studio

在 Windows 上读取和写入 Mac 磁盘（APFS 格式）的桌面工具，附带命令行版本。核心库完全自行实现（Rust），不依赖 macOS 或第三方 APFS 驱动。

## 功能

**读取**
- 打开物理磁盘（`\\.\PhysicalDriveN`，自动识别 GPT/MBR 分区）或原始磁盘镜像（`.img`、未压缩的 `.dmg` 等）
- 浏览全部卷、文件夹、符号链接、扩展属性；查看节点详细信息
- 透明解压 macOS 压缩文件：zlib、LZVN、LZFSE（内联与资源分支两种存储方式）
- 区分大小写/不区分大小写、规范化不敏感（NFD）的卷都可以按名称查找
- 导出文件或整个文件夹到 Windows，自动处理 Windows 不允许的文件名并保留修改时间
- 最新检查点损坏时自动回退到较早的有效检查点

**写入**
- 导入文件和文件夹（拖放或对话框），可选择遇到同名文件时覆盖/跳过/保留两者
- 新建文件夹、重命名、移动、删除（含递归删除）、创建符号链接、覆盖文件内容
- 自带一致性检查器（类似 fsck），可随时校验整个容器

## 安全设计

- **默认只读。** 必须在侧栏为某个容器手动“启用写入”，启用前默认先跑一次完整一致性检查，发现问题就拒绝写入。
- **写时复制 + 检查点提交。** 所有修改（B 树节点、对象映射、空间位图、数据块）都写入空闲块，原有结构一个字节都不改。最后依次落盘：新的临时对象和检查点映射 → 屏障 → 新检查点超级块 → 屏障 → 块 0。任何时刻断电，重新挂载后看到的要么是完整的旧状态，要么是完整的新状态。测试中对一次事务的**每一次**写操作位置都模拟了断电，全部通过。
- **不确定就拒绝。** 下列情况保持只读，不会冒险写入：
  - FileVault 加密卷（目前也无法读取内容）
  - 含快照的卷（例如时间机器），写入会破坏快照引用
  - 已签名封存的系统卷、Fusion 融合硬盘、正在调整大小或正在后台清理（reaper）的容器
  - 带硬链接的文件、维护目录统计信息的文件夹（只对这些具体对象拒绝修改）
- **损坏的数据只会产生错误，不会崩溃。** 所有磁盘读取都做边界检查；模糊测试对真实镜像做了上千轮随机破坏，没有一次崩溃或内存失控。

> 建议：写入前先在 Mac 上“推出”磁盘，并备份重要数据。写入完成后可点“检查”再确认一遍。

## 使用

### 图形界面

运行 `APFS-Studio.exe`：

1. **打开镜像**：选择镜像文件，或直接把镜像拖进窗口。
2. **扫描磁盘**：列出连接到电脑的 APFS 磁盘。访问物理磁盘需要管理员权限，可以点右上角“以管理员身份重启”。
3. 左侧选择卷浏览内容；双击文件夹进入，双击文件导出。
4. 需要写入时点容器下方的“启用写入”。之后可以把文件拖进窗口导入，或使用工具栏按钮。

快捷键：`Delete` 删除，`F2` 重命名，`Backspace` 返回上一级，`F5` 刷新，`Ctrl+A` 全选，`Enter` 打开。

### 命令行

```
apfs info   <镜像或设备>                     容器与卷概况
apfs ls     <源> [-v N] [路径]               列出文件夹
apfs tree   <源> [-v N] [路径]               递归列出
apfs cat    <源> [-v N] <路径>               输出文件内容
apfs get    <源> [-v N] <路径> <本地目标>    复制文件/文件夹到本机
apfs stat   <源> [-v N] <路径>               节点详情
apfs check  <源>                             一致性检查
apfs put    <源> [-v N] <本地路径> <目标>    复制本地文件/文件夹进卷
apfs mkdir  <源> [-v N] <路径>
apfs rm     <源> [-v N] [-r] <路径>
apfs mv     <源> [-v N] <原路径> <新路径>
apfs symlink <源> [-v N] <目标> <路径>
```

`-v N` 选择容器中的第 N 个卷，`-p N` 选择磁盘上的第 N 个 APFS 分区。Windows 下物理磁盘写作 `\\.\PhysicalDrive2`。

## 构建

需要 Rust 1.80+。

```
cd apfs-studio
cargo build --release                # 生成 target/release/APFS-Studio(.exe) 和 apfs(.exe)
```

在 Linux 上交叉编译 Windows 版：

```
rustup target add x86_64-pc-windows-gnu    # 另需安装 mingw-w64
cargo build --release --target x86_64-pc-windows-gnu -p apfs-studio -p apfs-cli
```

推送到 GitHub 后，`.github/workflows/apfs-studio.yml` 会在 Windows 上构建并上传可执行文件，同时在 Linux 上跑测试。

## 测试

测试使用 macOS 实际生成的 APFS 镜像（来自 [fox-it/dissect.apfs](https://github.com/fox-it/dissect.apfs) 测试集，Apache-2.0）：

```
export APFS_TEST_IMAGES=/path/to/images   # 内含 case_insensitive.bin 等
cargo test --release -p apfs
```

- `read`：文件内容（含三种压缩）、文件名哈希与磁盘逐一比对
- `write`：数百轮随机增删改与内存模型比对，每次提交后重新挂载并完整检查；填满磁盘；3000 个文件触发 B 树分裂/合并；40 MB 大文件；同一句柄连续事务；**逐写入点断电模拟**
- `fuzz`：随机破坏元数据后读取、检查、写入，确保不崩溃

## 代码结构

```
crates/apfs/          核心库
  device.rs           设备抽象（镜像文件、Windows 物理磁盘、分区窗口）
  partition.rs        GPT/MBR 中定位 APFS 容器
  container.rs        检查点选择与回退、临时对象、块缓存、对象映射
  btree.rs            B 树读取
  volume.rs           卷：目录、路径解析、文件读取、扩展属性、快照
  compress.rs         decmpfs 解压（zlib/LZVN/LZFSE）
  wbtree.rs           写时复制 B 树（分裂、合并、根折叠）
  spaceman.rs         空间管理：位图、CIB/CAB、内部池环形位图
  txn.rs              事务：文件系统操作与检查点提交
  check.rs            一致性检查器
crates/apfs-cli/      命令行工具
crates/apfs-studio/   图形界面（egui）
```

## 尚不支持

- 解锁 FileVault 加密卷
- 写入含快照的卷、封存系统卷、Fusion 融合硬盘
- 修改硬链接文件；LZBITMAP 压缩格式的读取
- 压缩格式的 `.dmg`（UDZO/ULFO 等）——请先在 Mac 上转换为可读写或只读原始格式，或直接连接物理磁盘
- 新建（格式化）APFS 容器
