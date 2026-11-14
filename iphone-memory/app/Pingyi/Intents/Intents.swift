import AppIntents
import SwiftData
import UniformTypeIdentifiers

struct SaveScreenIntent: AppIntent {
    static let title: LocalizedStringResource = "记住屏幕"
    static let description = IntentDescription("识别截图中的文字，整理成一条记忆；如果包含时间，会提取为日程。搭配快捷指令「截屏」动作使用。")
    static let openAppWhenRun = false

    @Parameter(title: "截图", supportedContentTypes: [.image])
    var image: IntentFile

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let memory = await MemoryProcessor.ingest(imageData: image.data, source: .screenshot)
        return .result(dialog: IntentDialog(stringLiteral: dialogText(memory)))
    }
}

struct SaveTextIntent: AppIntent {
    static let title: LocalizedStringResource = "记一条文字"
    static let description = IntentDescription("把一段文字保存为记忆。")
    static let openAppWhenRun = false

    @Parameter(title: "内容", inputOptions: String.IntentInputOptions(multiline: true))
    var text: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let memory = await MemoryProcessor.ingest(text: text, source: .text)
        return .result(dialog: IntentDialog(stringLiteral: dialogText(memory)))
    }
}

struct SearchMemoriesIntent: AppIntent {
    static let title: LocalizedStringResource = "搜索记忆"
    static let openAppWhenRun = false

    @Parameter(title: "关键词")
    var query: String

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let all = (try? Store.context.fetch(FetchDescriptor<Memory>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)]))) ?? []
        let hits = all.filter { $0.matches(query) }.prefix(5)
        let text = hits.isEmpty ? "没有找到「\(query)」相关的记忆"
            : hits.map { "• \($0.summary)（\($0.createdAt.formatted(.dateTime.month().day()))）" }.joined(separator: "\n")
        return .result(value: text, dialog: IntentDialog(stringLiteral: text))
    }
}

@MainActor
private func dialogText(_ memory: Memory) -> String {
    var s = "已记住：\(memory.summary)"
    if let d = memory.eventDate {
        s += memory.addedToCalendar ? "\n已加入日历 \(d.formatted(.dateTime.month().day().hour().minute()))"
                                    : "\n发现日程 \(d.formatted(.dateTime.month().day().hour().minute()))"
    }
    return s
}

struct PingyiShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SaveScreenIntent(),
                    phrases: ["用\(.applicationName)记住屏幕", "\(.applicationName)记住这个"],
                    shortTitle: "记住屏幕", systemImageName: "camera.viewfinder")
        AppShortcut(intent: SaveTextIntent(),
                    phrases: ["用\(.applicationName)记一条"],
                    shortTitle: "记一条文字", systemImageName: "square.and.pencil")
        AppShortcut(intent: SearchMemoriesIntent(),
                    phrases: ["在\(.applicationName)里搜索"],
                    shortTitle: "搜索记忆", systemImageName: "magnifyingglass")
    }
    static let shortcutTileColor: ShortcutTileColor = .purple
}
