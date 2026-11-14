import SwiftUI
import PhotosUI
import UIKit

enum AppTab: String, Hashable {
    case timeline, agenda, settings, search
}

@MainActor
@Observable
final class AppModel {
    var tab: AppTab = .timeline
    var timelinePath: [Memory] = []
    var isWorking = false
    var toast: String?

    func importPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            flash("无法读取这张图片")
            return
        }
        await run { await MemoryProcessor.ingest(imageData: data, source: .photo) }
    }

    func pasteClipboard() async {
        let pb = UIPasteboard.general
        if let image = pb.image, let data = image.pngData() {
            await run { await MemoryProcessor.ingest(imageData: data, source: .clipboard) }
        } else if let text = pb.string, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            await run { await MemoryProcessor.ingest(text: text, source: .clipboard) }
        } else {
            flash("剪贴板是空的")
        }
    }

    func addText(_ text: String) async {
        await run { await MemoryProcessor.ingest(text: text, source: .text) }
    }

    private func run(_ work: () async -> Memory) async {
        isWorking = true
        let memory = await work()
        isWorking = false
        flash(memory.hasEvent ? "已记住，并发现一个日程" : "已记住")
    }

    func flash(_ text: String) {
        withAnimation(.spring(duration: 0.35)) { toast = text }
        Task {
            try? await Task.sleep(for: .seconds(2.2))
            withAnimation(.easeOut) { if toast == text { toast = nil } }
        }
    }
}
