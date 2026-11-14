# iPhone「屏幕记忆」工具可行性评估

> 日期：2026-11-14 ｜ 结论：**可行**，但必须走"用户手动触发截屏"这条路，iOS 不允许 App 在后台偷偷读屏。

## 一、一句话结论

| 需求 | 能否实现 | 实现方式 |
|---|---|---|
| 任何时候手动触发，获取当前屏幕内容 | ✅ 能 | 「轻点背面」/ 操作按钮 / 控制中心 → 快捷指令「截屏」→ 交给 App |
| 识别屏幕上的文字 | ✅ 能，完全本地 | Vision 框架 OCR（支持简体/繁体中文、英文） |
| 自动整理成"记忆"（摘要、标签、提取时间地点） | ✅ 能 | iOS 26 的 Foundation Models（端侧大模型，需支持 Apple 智能的机型）；老机型用规则 + 系统 `NSDataDetector` 识别日期 |
| 记忆库 + 搜索 | ✅ 能 | SQLite FTS5 全文检索 + 向量语义检索；可同步进系统 Spotlight |
| 定时任务 / 日程安排 | ✅ 能 | EventKit 写入日历和提醒事项；本地通知；快捷指令"自动化"做每日回顾 |
| 后台自动、持续记录屏幕（类似 Rewind/Recall） | ⚠️ 基本不行 | 只有 ReplayKit 屏幕直播扩展能做，状态栏常驻红色录屏标识、内存上限约 50MB、耗电，体验差，不建议 |
| 直接读取其他 App 内部数据（微信聊天记录等） | ❌ 不能 | 沙盒限制，只能通过截屏 OCR 或"分享"拿到内容 |

---

## 二、触发方式（关键环节）

iOS 的快捷指令里有一个系统动作 **「截屏」(Take Screenshot)**，它截取的是**触发瞬间的当前屏幕**（包括别的 App 的画面），并且**不会存进相册**。这是整个方案的基石。

可绑定的触发入口，任何 App 里都能用：

1. **轻点背面**（设置 → 辅助功能 → 触控 → 轻点背面，双击/三击）—— 最顺手，iPhone 8 及以上都有
2. **操作按钮**（iPhone 15 Pro 及以后）
3. **控制中心 / 锁屏按钮**（iOS 18+ 可放快捷指令控件）
4. **Siri**："嘿 Siri，记住这个"
5. **分享扩展**：在 Safari、备忘录、小红书等任意 App 点"分享"→ 本 App，直接拿到文字/链接/图片（比截图更精确）

```
轻点背面两下
   └─ 快捷指令「记住屏幕」
        ├─ 截屏（不进相册）
        └─ 调用 App 提供的 App Intent：SaveMemory(image)
              └─ App 在后台完成：OCR → 摘要/提取 → 入库 → (可选)建日程
```

App 通过 **App Intents** 把"保存记忆"暴露给快捷指令，执行时无需把 App 切到前台，用户几乎无感（顶部会出现一条快捷指令运行横幅）。

---

## 三、技术架构

```
┌──────────── 采集层 ────────────┐
│ 快捷指令截屏 │ 分享扩展 │ 相册导入 │ 手动输入 │
└──────────────┬─────────────────┘
               ▼
┌──────────── 理解层（全部端侧） ──────────────┐
│ Vision OCR（VNRecognizeTextRequest，zh-Hans）│
│ NSDataDetector：日期、电话、地址、链接        │
│ Foundation Models（iOS 26+）：                │
│   一句话摘要 / 标签 / 结构化提取（@Generable）│
│   如：{事件, 开始时间, 地点, 待办}            │
└──────────────┬──────────────────────────────┘
               ▼
┌──────────── 存储层 ────────────┐
│ SwiftData / SQLite              │
│  - 原图缩略图、OCR 全文、摘要、标签、来源 App、时间 │
│  - FTS5 全文索引（中文需分词，可用 NLTokenizer） │
│  - 向量：NLEmbedding 句向量 → 语义搜索           │
│ Core Spotlight：记忆出现在系统搜索里            │
│ iCloud（CloudKit）可选同步                       │
└──────────────┬─────────────────┘
               ▼
┌──────────── 行动层 ────────────┐
│ EventKit：一键/自动写入日历、提醒事项 │
│ UserNotifications：到点提醒（最多 64 条待发） │
│ 快捷指令自动化：每天 21:00 推送"今日记忆回顾" │
│ BGTaskScheduler：空闲时补做索引/摘要（时间不保证）│
└────────────────────────────────┘
```

### 核心代码示意（Swift）

```swift
import AppIntents, Vision, EventKit

struct SaveMemoryIntent: AppIntent {
    static var title: LocalizedStringResource = "记住屏幕"
    static var openAppWhenRun = false

    @Parameter(title: "截图") var image: IntentFile

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let text = try await OCR.recognize(image.data)          // Vision，本地
        let info = try await Extractor.extract(text)             // 摘要 + 日期/事件
        try await MemoryStore.shared.save(image: image.data, text: text, info: info)
        if let event = info.event { try await Scheduler.addToCalendar(event) }
        return .result(dialog: "已记住：\(info.summary)")
    }
}

enum OCR {
    static func recognize(_ data: Data) async throws -> String {
        let req = VNRecognizeTextRequest()
        req.recognitionLanguages = ["zh-Hans", "en-US"]
        req.recognitionLevel = .accurate
        try VNImageRequestHandler(data: data).perform([req])
        return (req.results ?? []).compactMap { $0.topCandidates(1).first?.string }
                                  .joined(separator: "\n")
    }
}
```

iOS 26 端侧模型的结构化提取：

```swift
import FoundationModels

@Generable struct MemoryInfo {
    @Guide(description: "20 字以内的中文摘要") var summary: String
    @Guide(description: "3 个以内标签") var tags: [String]
    var event: EventInfo?          // 屏幕上若有约定时间的事件
}
@Generable struct EventInfo { var title: String; var start: String; var location: String? }

let session = LanguageModelSession()
let info = try await session.respond(to: "从以下屏幕文字中提取：\n\(text)",
                                     generating: MemoryInfo.self).content
```

---

## 四、限制与风险

| 问题 | 影响 | 对策 |
|---|---|---|
| 不能后台自动读屏 | 无法做"全自动时间线" | 定位为"一键记住"，触发成本做到极低（轻点背面） |
| 快捷指令运行时有横幅、首次需授权 | 轻微打扰 | 引导页一次性配置好，提供可一键导入的快捷指令链接 |
| App Intent 后台执行有时间和内存限制 | 大图 + 大模型可能超时 | Intent 里只做 OCR + 入库，摘要等重活放到下次打开 App 或 BGTask |
| Foundation Models 只在支持 Apple 智能的机型（iPhone 15 Pro 及以后）可用，国行 Apple 智能情况需确认 | 老机型/部分地区无端侧大模型 | 降级：NSDataDetector 抽日期 + 关键词标签；或可选接入云端大模型（需用户明确同意） |
| 本地通知最多 64 条待发；BGTask 时间不确定 | "定时任务"不能当精确闹钟 | 精确时间交给系统日历/提醒事项（EventKit），App 只负责创建 |
| 隐私：截图可能含密码、聊天、银行信息 | 用户信任、上架审核 | 默认全部端侧处理、不上传；支持 Face ID 锁定；敏感词自动打码；一键删除 |
| 上架审核 | 需说明用途 | 方案只用公开 API，不涉及私有接口，正常可过审 |

---

## 五、建议路线

**第 0 步：零代码验证（今天就能做，30 分钟）**

纯快捷指令实现，先自己用一周，验证"我到底会不会经常按"：

1. 新建快捷指令「记住屏幕」：
   `截屏` → `从图像中提取文本` →（iOS 26 且支持 Apple 智能）`使用模型`：让它输出"摘要 + 是否有日程，若有给出时间" →
   `附加到备忘录`（某个"记忆库"备忘录，带当前日期）→
   `如果` 提取到日期 → `添加新日历事件`
2. 设置 → 辅助功能 → 触控 → 轻点背面 → 轻点两下 → 选「记住屏幕」
3. 快捷指令 → 自动化 → 每天 21:00 → 显示当天记忆汇总通知
4. 搜索直接用备忘录 / 系统 Spotlight

**第 1 步：原生 App MVP（2–4 周，需要 Mac + Xcode）**

- App Intent「记住屏幕」+ 分享扩展
- Vision OCR + SwiftData 入库 + 全文搜索 + 时间线列表
- 日期识别 → 一键加日历/提醒

**第 2 步：智能化**

- Foundation Models 摘要/标签/结构化事件提取
- 语义搜索（"上周看到的那家日料店"）
- Spotlight 索引、桌面小组件（今日待办 / 随机回顾）、每日/每周回顾

**第 3 步（可选）**：iCloud 多设备同步、Mac 端（macOS 可以做真正的后台定时截屏，补齐 iPhone 的短板）。

---

## 六、与需求报告的关系

在 `report/REPORT.md` 中，「记忆与长期上下文」「文件/照片/知识库整理」「个人排程 + 执行教练」「隐私/本地运行」都是高频主题。本工具正好落在交叉点：**端侧处理、手动捕获、把看到的东西变成可搜索的记忆和可执行的日程**——差异化在"一键 + 全本地 + 直接落到日历"，而不是再做一个聊天助手。
