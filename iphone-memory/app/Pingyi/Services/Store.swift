import Foundation
import SwiftData

@MainActor
enum Store {
    static let container: ModelContainer = {
        do {
            return try ModelContainer(for: Memory.self)
        } catch {
            fatalError("无法创建数据库：\(error)")
        }
    }()

    static var context: ModelContext { container.mainContext }
}
