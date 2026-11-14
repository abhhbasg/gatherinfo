import Foundation
import SwiftData

@MainActor
enum MemoryProcessor {
    static var autoCalendar: Bool {
        UserDefaults.standard.bool(forKey: "autoCalendar")
    }

    /// 图片 → OCR → 提炼 → 入库 → (可选)加入日历
    @discardableResult
    static func ingest(imageData: Data, source: MemorySource) async -> Memory {
        let text = await OCR.recognize(imageData)
        let info = await Extractor.extract(from: text)
        let memory = Memory(text: text, summary: info.summary, tags: info.tags, source: source,
                            imageData: imageData, thumbnailData: OCR.thumbnail(imageData),
                            eventTitle: info.eventTitle, eventDate: info.eventDate)
        await save(memory)
        return memory
    }

    @discardableResult
    static func ingest(text: String, source: MemorySource) async -> Memory {
        let info = await Extractor.extract(from: text)
        let memory = Memory(text: text, summary: info.summary, tags: info.tags, source: source,
                            eventTitle: info.eventTitle, eventDate: info.eventDate)
        await save(memory)
        return memory
    }

    private static func save(_ memory: Memory) async {
        let ctx = Store.context
        ctx.insert(memory)
        if autoCalendar, memory.hasEvent, Scheduler.canWriteEventsSilently {
            try? await Scheduler.addToCalendar(memory)
        }
        try? ctx.save()
        Spotlight.index(memory)
    }

    static func delete(_ memory: Memory) {
        Spotlight.remove(memory.id)
        Store.context.delete(memory)
        try? Store.context.save()
    }

    static func memory(id: UUID) -> Memory? {
        var d = FetchDescriptor<Memory>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? Store.context.fetch(d).first
    }
}
