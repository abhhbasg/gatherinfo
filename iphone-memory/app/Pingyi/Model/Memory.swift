import Foundation
import SwiftData

@Model
final class Memory {
    var id: UUID = UUID()
    var createdAt: Date = Date()
    var text: String = ""
    var summary: String = ""
    var tags: [String] = []
    var source: String = MemorySource.screenshot.rawValue
    @Attribute(.externalStorage) var imageData: Data?
    var thumbnailData: Data?
    var eventTitle: String?
    var eventDate: Date?
    var addedToCalendar: Bool = false
    var addedToReminders: Bool = false
    var isPinned: Bool = false

    init(text: String, summary: String, tags: [String], source: MemorySource,
         imageData: Data? = nil, thumbnailData: Data? = nil,
         eventTitle: String? = nil, eventDate: Date? = nil, createdAt: Date = .now) {
        self.text = text
        self.summary = summary
        self.tags = tags
        self.source = source.rawValue
        self.imageData = imageData
        self.thumbnailData = thumbnailData
        self.eventTitle = eventTitle
        self.eventDate = eventDate
        self.createdAt = createdAt
    }

    var sourceKind: MemorySource { MemorySource(rawValue: source) ?? .screenshot }
    var hasEvent: Bool { eventDate != nil }

    func matches(_ query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return true }
        return q.split(separator: " ").allSatisfy { term in
            let t = String(term)
            return summary.localizedStandardContains(t)
                || text.localizedStandardContains(t)
                || tags.contains { $0.localizedStandardContains(t) }
                || (eventTitle?.localizedStandardContains(t) ?? false)
        }
    }
}

enum MemorySource: String, CaseIterable {
    case screenshot, photo, text, clipboard

    var label: String {
        switch self {
        case .screenshot: "屏幕"
        case .photo: "相册"
        case .text: "文字"
        case .clipboard: "剪贴板"
        }
    }

    var symbol: String {
        switch self {
        case .screenshot: "camera.viewfinder"
        case .photo: "photo"
        case .text: "text.quote"
        case .clipboard: "doc.on.clipboard"
        }
    }
}
