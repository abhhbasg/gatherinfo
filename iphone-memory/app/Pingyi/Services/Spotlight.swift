import Foundation
import CoreSpotlight
import UniformTypeIdentifiers

enum Spotlight {
    static func index(_ memory: Memory) {
        let attrs = CSSearchableItemAttributeSet(contentType: .text)
        attrs.title = memory.summary
        attrs.contentDescription = String(memory.text.prefix(300))
        attrs.keywords = memory.tags + ["屏忆", "记忆"]
        attrs.thumbnailData = memory.thumbnailData
        attrs.contentCreationDate = memory.createdAt
        let item = CSSearchableItem(uniqueIdentifier: memory.id.uuidString,
                                    domainIdentifier: "memory", attributeSet: attrs)
        CSSearchableIndex.default().indexSearchableItems([item]) { _ in }
    }

    static func remove(_ id: UUID) {
        CSSearchableIndex.default().deleteSearchableItems(withIdentifiers: [id.uuidString]) { _ in }
    }

    static func removeAll() {
        CSSearchableIndex.default().deleteAllSearchableItems { _ in }
    }
}
