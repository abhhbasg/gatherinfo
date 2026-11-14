import Foundation
import FoundationModels

struct Extraction {
    var summary: String
    var tags: [String]
    var eventTitle: String?
    var eventDate: Date?
}

@Generable
struct ModelExtraction {
    @Guide(description: "用一句中文概括这段屏幕内容的要点，不超过 24 个字")
    var summary: String
    @Guide(description: "1 到 4 个简短的中文标签，例如：工作、购物、旅行、学习、餐厅、快递")
    var tags: [String]
    @Guide(description: "内容里是否包含一个有明确日期或时间的事件、约定、截止日期或待办")
    var hasEvent: Bool
    @Guide(description: "事件的简短标题，没有事件时为空字符串")
    var eventTitle: String
    @Guide(description: "事件开始时间，格式严格为 yyyy-MM-dd HH:mm；只有日期时用 09:00；没有事件时为空字符串")
    var eventStart: String
}

enum Extractor {
    static var isModelAvailable: Bool {
        if case .available = SystemLanguageModel.default.availability { return true }
        return false
    }

    static var modelStatusText: String {
        switch SystemLanguageModel.default.availability {
        case .available:
            return "Apple 智能已启用"
        case .unavailable(let reason):
            switch reason {
            case .deviceNotEligible: return "此设备不支持 Apple 智能"
            case .appleIntelligenceNotEnabled: return "请在设置中开启 Apple 智能"
            case .modelNotReady: return "模型下载中，稍后可用"
            @unknown default: return "Apple 智能暂不可用"
            }
        }
    }

    static func extract(from text: String, now: Date = .now) async -> Extraction {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return Extraction(summary: "未识别到文字", tags: [], eventTitle: nil, eventDate: nil)
        }
        if isModelAvailable, let result = try? await extractWithModel(trimmed, now: now) {
            return result
        }
        return heuristic(trimmed, now: now)
    }

    private static func extractWithModel(_ text: String, now: Date) async throws -> Extraction {
        let session = LanguageModelSession(instructions: """
        你是一个帮用户整理手机屏幕内容的助手。用户截下了屏幕，下面是识别出的文字。
        请提炼要点、打标签，并判断是否包含需要安排进日程的事件。只依据给出的文字，不要编造。
        """)
        let prompt = """
        当前时间：\(promptDate.string(from: now))
        屏幕文字：
        \(String(text.prefix(2400)))
        """
        let out = try await session.respond(to: prompt, generating: ModelExtraction.self).content
        var date: Date?
        if out.hasEvent { date = parseDate(out.eventStart) }
        let title = out.eventTitle.trimmingCharacters(in: .whitespaces)
        let tags = Array(out.tags.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.prefix(4))
        return Extraction(
            summary: out.summary.isEmpty ? firstLine(text) : out.summary,
            tags: tags,
            eventTitle: date == nil ? nil : (title.isEmpty ? out.summary : title),
            eventDate: date
        )
    }

    /// 没有端侧大模型时的降级方案：首行当摘要，系统数据检测器找日期。
    static func heuristic(_ text: String, now: Date) -> Extraction {
        var eventDate: Date?
        var eventTitle: String?
        if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.date.rawValue) {
            let ns = text as NSString
            let matches = detector.matches(in: text, range: NSRange(location: 0, length: ns.length))
            if let m = matches.first(where: { ($0.date ?? .distantPast) > now.addingTimeInterval(-3600) }), let d = m.date {
                eventDate = d
                let lineRange = ns.lineRange(for: m.range)
                eventTitle = ns.substring(with: lineRange).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return Extraction(summary: firstLine(text), tags: keywordTags(text),
                          eventTitle: eventTitle, eventDate: eventDate)
    }

    private static func firstLine(_ text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first { $0.count >= 4 } ?? text
        return String(line.prefix(30))
    }

    private static let keywordMap: [(String, [String])] = [
        ("工作", ["会议", "项目", "汇报", "deadline", "邮件", "客户"]),
        ("购物", ["¥", "￥", "优惠", "下单", "价格", "券"]),
        ("出行", ["航班", "高铁", "车次", "酒店", "机场", "登机"]),
        ("美食", ["餐厅", "菜单", "外卖", "预订"]),
        ("学习", ["课程", "考试", "作业", "论文"]),
        ("快递", ["快递", "取件码", "包裹", "驿站"]),
    ]

    private static func keywordTags(_ text: String) -> [String] {
        keywordMap.filter { _, words in words.contains { text.localizedCaseInsensitiveContains($0) } }
            .map(\.0).prefix(3).map { $0 }
    }

    private static let promptDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy-MM-dd HH:mm EEEE"
        return f
    }()

    static func parseDate(_ s: String) -> Date? {
        let str = s.trimmingCharacters(in: .whitespaces)
        guard !str.isEmpty else { return nil }
        for fmt in ["yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd"] {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = .current
            f.dateFormat = fmt
            if let d = f.date(from: str) {
                return fmt == "yyyy-MM-dd" ? Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: d) : d
            }
        }
        return nil
    }
}
