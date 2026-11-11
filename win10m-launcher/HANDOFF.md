# 交接文档：Win10M 桌面（安卓复刻）

给在本地电脑上接着做的会话。先读这一份，再读 `README.md` 和 `research/W10M_START_SPEC.md`。

## 1. 当前状态
- 分支：`claude/ecstatic-hawking-crhgx2`，最新提交 `a4ffb80`，已推送。
- 版本：第五版。`app/build/outputs/apk/debug/app-debug.apk` 可编译、单元测试和离屏截图测试全部通过。
- **从未在真机或模拟器上运行过。** 所有截图都是 Robolectric 离屏渲染，不是真机画面。
- 已实现的界面：开始屏幕（动态磁贴、拖动倾斜、编辑漂浮、所有应用→）、所有应用列表、操作中心（状态栏下拉）、三大按键（导航栏）、电话、短信、浏览器、人脉、日历、计算器、录音机、音乐、闹钟和时钟、照片、设置（仿 W10M 设置首页）、开始屏幕设置。

## 2. 哪些数值是真的，哪些是估计的
| 类别 | 来源 | 可信度 |
|---|---|---|
| 磁贴尺寸、字号阶梯、UWP 动画时长 | 微软官方文档（windows-dev-docs 仓库） | 高 |
| 进入 / 离开缓动曲线 | 官方 timing-and-easing.md | 高 |
| drill、drill-back、page-refresh 的缩放 / 位移 / 时长 | 官方 GIF 逐帧测量（`research/ref-motion-*.png`） | 中高 |
| 照片界面（40 顶栏、161 缩略图、2 间隙） | 官方照片截图测量（`research/ref-photos-semantic-zoom.png`） | 中高 |
| 磁贴间距 4、边距 8、列数 6/8、导航栏 48、状态栏 24、操作中心布局 | 印象估计（标"实测"） | **低，需要校准** |
| 闹钟、时钟、计算器、人脉、日历、录音机、音乐、设置 的全部尺寸 | 印象估计 | **低，需要校准** |
| 所有应用列表行距 44、图标 40 | 印象估计 | 低 |

所有估计数值都集中在 `app/src/main/java/com/w10m/launcher/Metro.kt`，以及各界面文件的 `dp(...)` / `sp(...)` 调用里。

## 3. 校准流程（目标：像素级）
1. **拿到参考截图。** 最好是 Windows 10 Mobile 原版（Lumia 950 XL 或模拟器，分辨率 1440×2560，即 4× 于 360 逻辑宽）。截图清单见第 4 节。
2. **同尺寸对齐。** 把参考图和我们的截图都缩放到 360 逻辑宽（即按 1/4 缩放 1440 宽的图，或者让复刻版在 1080 宽设备上渲染）。
3. **量。** 用脚本（PIL 或 OpenCV）找磁贴边界、文字基线、图标包围盒，与复刻版逐项比较。
4. **改数值。** 只改 `Metro.kt` 和对应界面的常量，不要散落新数字。
5. **重新渲染。** `./gradlew testDebugUnitTest` 会生成 `app/build/screens/*.png`。
6. **循环**，直到差异在 1–2 epx 以内。

## 4. 需要的参考截图清单（W10M 原版）
开始屏幕：默认布局、显示更多磁贴（8 列）、编辑模式（选中一个磁贴）、深色和浅色各一套。
所有应用：列表顶部、字母跳转网格。
操作中心：折叠、展开。
导航栏：正常态、按下态（返回键长按要录视频）。
闹钟和时钟：闹钟页、世界时钟、计时器、秒表、新建闹钟。
照片：收藏集、月份列表、相册、全屏查看。
人脉、短信（列表和会话）、电话（拨号盘和历史）、日历（月视图）、计算器（标准模式）、录音机、音乐、设置首页。

动画（60 帧录像，每个录 2–3 次）：打开应用、返回开始屏幕、滑到应用列表、拖动磁贴、动态磁贴翻转。

## 5. 本地环境
- JDK 17 或以上；Android SDK（platform 34、build-tools 34.0.0）；或直接装 Android Studio。
- 仓库里的 `gradle.properties`、`local.properties` 需要按本机路径改：`local.properties` 写 `sdk.dir=...`。
- `settings.gradle.kts` 和 `app/build.gradle.kts` 把 Maven 镜像地址指向了 `maven-central.storage-download.googleapis.com`，这是为了绕开沙箱的 429 限流。**在普通网络下可以删掉这一行，直接用 mavenCentral()。**
- 品牌图标包由 `tools/gen_icons.py` 生成。它需要 Simple Icons npm 包（`npm pack simple-icons`），把解压后的目录作为参数传入。生成结果已经提交，正常情况下不用重跑。
- 测试命令：`./gradlew testDebugUnitTest assembleDebug`。

## 6. 已知问题和待办
- 没有在真机上验证任何交互：拖动、下拉操作中心、长按返回、闹钟响铃、照片轮播、浏览器下载。
- 闹钟需要用户允许"闹钟和提醒"权限，安卓 13 以上还需要通知权限，否则响铃界面不出现。
- 音乐没有常驻通知栏播放服务，退到后台可能被系统停掉。
- 录音只存在应用私有目录。
- 默认磁贴布局只对首次安装生效；老用户需要手动固定新磁贴。
- 图标包里的"分类图标"（购物车、视频等）是通用图形，不是各应用自己的标志。京东、拼多多、爱奇艺、高德等应用用的是分类图标。
- 按键映射（包名到图标）是凭记忆写的，个别包名可能不对。
- Windows 标志是微软的注册商标。导航栏的开始键是自绘的透视四格窗，外观接近 Windows 标志；公开分发前建议换掉。
- 单元测试的截图是 Robolectric 离屏渲染，不代表真机效果。

## 7. 仓库内的重要文件
- `README.md`：功能清单和截图。
- `research/W10M_START_SPEC.md`：全部规格和来源。
- `research/SELAWIK-LICENSE.txt`、`research/SIMPLE-ICONS-LICENSE.md`：字体和图标许可证。
- `research/ref-*.png`：测量用的官方参考图。
- `docs/screens/`：各界面的渲染截图。
- `app/src/main/assets/icons/`：图标包（brands.tsv、glyphs.tsv、map.tsv）。
- `app/src/main/java/com/w10m/launcher/Metro.kt`：全部设计常量。
- `app/src/main/java/com/w10m/launcher/Motion.kt`：动画曲线和时长。
- `app/src/test/java/com/w10m/launcher/ScreenshotTest.kt`：所有界面的截图测试，改完界面可以立刻重新生成截图。
