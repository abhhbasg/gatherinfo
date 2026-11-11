# Windows 10 Mobile 开始屏幕 —— 复刻资料与像素规格

本文件是动手写代码前收集、整理的资料。所有尺寸单位为 **epx（effective pixel）**。
W10M 在主流手机（Lumia 950/640/650 等）上逻辑宽度为 **360 epx**，与 Android 主流手机的 360 dp 恰好一致，
因此本项目中 **1 epx = 1 dp**，文字 **1 epx = 1 sp**，可以直接按数值 1:1 搬运。

可信度标记：
- 【官方】 = 微软开发文档 / UWP 控件模板中有明确数值
- 【推导】 = 由官方数值换算得到
- 【实测】 = 依据 W10M 真机截图（1440×2560 @ 400%、720×1280 @ 200%）换算到 epx 的度量值，误差约 ±1 epx

---

## 1. 磁贴（Tiles）

### 1.1 官方资产尺寸
| 尺寸 | WP8 资产（WXGA 768 宽，scale 1.6） | WinRT/UWP 100% 资产 | 来源 |
|---|---|---|---|
| 小 Small | 159×159 | 71×71（Phone）/ 70×70 | 【官方】Tile design guidelines for Windows Phone (jj662929)；TileSize Enum；hh781198 |
| 中 Medium | 336×336 | 150×150 | 【官方】 |
| 宽 Wide | 691×336 | 310×150 | 【官方】 |
| 大 Large | — | 310×310（W10M 开始屏不提供） | 【官方】TileSize Enum |

由 WP8 资产换算：`691 = 2×336 + 19`，`336 = 2×159 + 18`，说明磁贴之间的间距在 WXGA 下约 18~19 px，
即 480 宽逻辑像素下 **12 px**（WP7/WP8 时代的经典间距）。【推导】

### 1.2 W10M 网格
W10M 相对 WP8.1 把间距缩窄、边距缩窄，让磁贴几乎铺满屏幕：

| 参数 | 数值 | 说明 |
|---|---|---|
| 网格列数（小磁贴单位） | **6**（「显示更多磁贴」关）/ **8**（开） | 【官方】设置 > 个性化 > 开始 > 显示更多磁贴 |
| 磁贴间距 gutter | **4 epx** | 【实测】1440 宽截图中约 16 px |
| 左右外边距 | **8 epx** | 【实测】 |
| 顶部（状态栏下方）留白 | **8 epx** | 【实测】 |
| 360 宽 6 列：小 / 中 / 宽 | **54 / 112 / 228** | 【推导】(360−2×8−5×4)/6 = 54；中 = 2×54+4；宽 = 4×54+3×4 |
| 360 宽 8 列：小 / 中 / 宽 | **39 / 82 / 168** | 【推导】(360−16−28)/8 = 39.5 → 实际按浮点布局 |

> 本项目在任何宽度下都按上式浮点计算，保证与屏幕宽度成比例（不同 Android 机型宽度 360~430 dp）。

### 1.3 磁贴内部
| 元素 | 规格 | 来源 |
|---|---|---|
| 背景 | 主题色（Accent）实色；或主题色 × (1 − 透明度) 叠在壁纸上；或「图片显示在磁贴上」镂空壁纸 | 【官方】设置 > 开始 > 背景/磁贴透明度 |
| 名称标签 | Segoe UI Regular **12 epx**，白色，左对齐，左 **8**，底 **6**（基线距底约 8）；仅中/宽磁贴显示 | 【实测】，字号同 UWP Caption 12 【官方】 |
| 图标 | 居中；中/宽磁贴约 **磁贴边长 × 0.43（≈48 epx）**；小磁贴约 **× 0.5（≈27 epx）** | 【实测】 |
| 角标 Badge | 数字 Segoe UI **12 epx**，白色，右下角，右 **8**、底 **6**；小磁贴放右下角且更紧凑（右 4、底 2） | 【实测】 |
| 圆角 | **0**（完全直角） | 【官方】Metro 设计语言 |

### 1.4 动态磁贴（Live Tile）
- 翻转：绕水平轴（X 轴）翻转，前后两面内容交替，单次约 **500 ms**，周期 **6~10 s 随机**，各磁贴相位错开。【实测】
- 日历磁贴：正面「星期 + 日期大号数字」，Segoe UI Light 大号数字；背面显示时间。【实测】

---

## 2. 交互与动画
| 交互 | 规格 | 来源 |
|---|---|---|
| 按下倾斜 Tilt | 按下时磁贴朝手指方向 3D 倾斜，最大约 **10°**，同时缩放到 **0.97**；抬起回弹 | 【官方】PointerDownThemeAnimation（WP 中的 TiltEffect） |
| 打开应用 Turnstile | 以屏幕左边缘为轴 Y 轴旋转飞出 0° → −80°，透明度 1 → 0，自上而下逐行延迟约 **20~40 ms**，被点磁贴最后飞出；总时长约 **250 ms** | 【官方】Turnstile Feather 动画（WP Toolkit） |
| 返回开始屏 | 反向 Turnstile：80° → 0° 飞入，逐行延迟 | 【官方】 |
| 长按进入编辑 | 被选磁贴保持 1.0，其他磁贴缩小到 **0.9** 并变暗（不透明度约 **0.6**）；被选磁贴右上角出现「取消固定」圆形按钮，右下角出现「调整大小」圆形箭头按钮（直径 **32 epx**，白底黑图标，部分压在磁贴边缘外） | 【实测】 |
| 编辑模式拖拽 | 拖动磁贴，其余磁贴实时重新排列（动画 ~**167 ms**） | 【实测】，时长同 UWP Fast 167 ms 【官方】 |
| 左右切换 | 开始屏 ⇄ 所有应用列表，水平跟手平移 | 【官方】 |

UWP 动画时长阶梯：Fast **167 ms**、Normal **250 ms**、Slow **333 ms**。【官方】

---

## 3. 所有应用列表（App list）
| 元素 | 规格 | 来源 |
|---|---|---|
| 页面边距 | 左右 **12 epx** | 【官方】UWP 手机端页面边距 12 epx |
| 顶部搜索框 | 高 **32**，边框 **2 epx**（暗色主题 #66FFFFFF，聚焦时白色），占位文字「搜索」15 epx，右侧放大镜 | 【官方】TextBox 模板（MinHeight 32，BorderThickness 2） |
| 「最近添加」 | 列表顶部，分组标题样式 | 【实测】 |
| 分组标题（字母） | 字母或「#」，Segoe UI 20 epx，点击打开字母跳转网格；高 **44** | 【实测】 |
| 应用行 | 行距 **44**；图标 **40×40**（主题色方块衬底，行间隔 4，与磁贴间距一致），左侧 12；名称 Segoe UI Regular **15 epx**，与图标间距 12 | 【实测】，15 = UWP Body 【官方】 |
| 字母跳转网格 | 全屏，背景黑 90%；**4 列**正方形格子，间距 **6**；有应用的字母主题色底白字，无应用的字母 #1F1F1F 底 #4CFFFFFF 字；字号 34 Light | 【实测】，34 = UWP Subheader 【官方】 |
| 长按应用 | 弹出 MenuFlyout：背景 #2B2B2B（暗）/#F2F2F2（亮），1 px 边框 #767676，菜单项高 **48**（触控）、文字 15 epx、左右内边距 12 | 【官方】MenuFlyoutPresenter 模板 |

---

## 4. 字体、字号阶梯（UWP Type ramp）【官方】
| 名称 | 字号 | 字重 |
|---|---|---|
| Header | 46 | Light |
| Subheader | 34 | Light |
| Title | 24 | SemiLight |
| Subtitle | 20 | Regular |
| Base | 15 | SemiBold |
| Body | 15 | Regular |
| Caption | 12 | Regular |

字体：Segoe UI 为微软专有字体，**不能随应用分发**。本项目使用微软以 **SIL OFL 1.1** 开源的
**Selawik**（专门作为 Segoe UI 的度量兼容替代品，字宽、行高与 Segoe UI 对齐），含 Light / SemiLight / Regular / SemiBold。
许可证见 `SELAWIK-LICENSE.txt`。

图标：Segoe MDL2 Assets 同样是专有字体，本项目中的系统图标（放大镜、图钉、箭头、齿轮等）全部是自绘矢量，
按 MDL2 的 1 epx 细线风格绘制。

---

## 5. 颜色【官方】
### 5.1 主题
| 资源 | 暗色 | 亮色 |
|---|---|---|
| 页面背景 | #000000 | #FFFFFF |
| 前景（BaseHigh） | #FFFFFF | #000000 |
| BaseMedium | #99FFFFFF | #99000000 |
| ChromeMedium（输入框/卡片底） | #1F1F1F | #E6E6E6 |
| ChromeHigh / 边框 | #767676 | #CCCCCC |
| Flyout 背景 | #2B2B2B | #F2F2F2 |

### 5.2 主题色（Windows 10 / W10M 48 色面板，默认 #0078D7）
```
FFB900 FF8C00 F7630C CA5010 DA3B01 EF6950
D13438 FF4343 E74856 E81123 EA005E C30052
E3008C BF0077 C239B3 9A0089 0078D7 0063B1
8E8CD8 6B69D6 8764B8 744DA9 B146C2 881798
0099BC 2D7D9A 00B7C3 038387 00B294 018574
00CC6A 10893E 7A7574 5D5A58 68768A 515C6B
567C73 486860 498205 107C10 767676 4C4A48
69797E 4A5459 647C64 525E54 847545 7E735F
```
默认主题色 0078D7 见微软无人值守安装参考（dn923359）。

---

## 5.3 导航键栏、电话、浏览器（第二版）
以下数值均为【实测】（按印象估计，待真机截图校准）：

| 元素 | 规格 |
|---|---|
| 导航键栏 | 高 **48**，黑底；返回 / 开始 / 搜索三键各占 1/3 宽，白色 1.4 epx 线条图标，按下变主题色 |
| 动态磁贴切换 | 新内容自下而上滑入，约 **600 ms** 减速曲线；通知面：标题 15、正文 12（80% 白）自动换行，应用名仍在左下 |
| 电话 · 顶栏 | 高 **48**，ChromeMedium 底；选中项白色图标 + 2 epx 主题色下划线 |
| 电话 · 按键 | 3 列，键高 **58**，间隔 **2**，ChromeMedium 底，按下变主题色；数字 28 SemiLight，字母 10；底行为主题色「呼叫」（占两列）+「保存」 |
| 浏览器 · 底栏 | 高 **48**，ChromeMedium 底；左 48 标签页键、中间 32 高地址框（静止时只显示域名，聚焦时白底 + 2 epx 主题色边框）、右 48「…」；栏顶 2 epx 主题色进度条 |
| 浏览器 · 菜单 | 自底栏向上展开，Flyout 底色，菜单项高 **44**、文字 15 |

## 6. Android 适配说明
- 应用图标：Android 13+ 自适应图标若带 **monochrome（单色）层**，绘制为白色单色图标放在主题色磁贴上——这正是 W10M「白色字形 + 主题色底」的样子；否则绘制原彩色图标。
- 角标：通过 NotificationListenerService 统计每个应用的通知数（需用户在系统设置里授权）。
- 壁纸：通过系统图片选择器选取图片（无需存储权限）。
- 状态栏、导航栏：透明，内容延伸到系统栏下方并按 inset 留白。

## 参考来源
- Tile design guidelines for Windows Phone — https://learn.microsoft.com/previous-versions/windows/apps/jj662929(v=vs.105)
- Tile and toast visual assets — https://learn.microsoft.com/previous-versions/windows/apps/hh781198(v=win.10)
- TileSize Enum — https://learn.microsoft.com/uwp/api/windows.ui.startscreen.tilesize
- LongListSelector design guidelines（字母跳转列表）— https://learn.microsoft.com/previous-versions/windows/apps/jj735577(v=vs.105)
- Windows 10 Mobile Tip: Customize Start — https://www.thurrott.com/mobile/windows-phone/4722/windows-10-mobile-tip-customize-start
- Enable four columns on Lumia 950 — https://www.windowscentral.com/enable-four-columns-start-screen-lumia-950
- Variable Live Tile transparency — https://mspoweruser.com/variable-live-tile-transparency-in-windows-10-mobile-demonstrated-picture
- 默认主题色 — https://msdn.microsoft.com/en-us/library/dn923359(v=vs.85)
- Selawik — https://github.com/microsoft/Selawik
