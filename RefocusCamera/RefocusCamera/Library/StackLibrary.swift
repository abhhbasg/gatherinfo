import Foundation
import ImageIO
import Observation
import UIKit

/// 一组焦点堆栈的描述，与帧文件一起存放在 Documents/FocusStacks/<id>/manifest.json。
struct StackManifest: Codable, Identifiable, Hashable {
    struct Frame: Codable, Hashable {
        var file: String
        /// 0 = 最近对焦距离，1 = 最远
        var lensPosition: Float
    }

    var id: UUID
    var createdAt: Date
    var frames: [Frame]
    /// 用作对齐基准、并携带深度数据的那一帧
    var referenceIndex: Int
    var hasDepth: Bool
    /// 拍摄模式（CaptureMode.rawValue）；旧版本的堆栈没有这个字段
    var mode: String?

    var captureMode: CaptureMode? { mode.flatMap(CaptureMode.init(rawValue:)) }

    /// Bayer RAW 堆栈（未经计算摄影处理）
    var isRAW: Bool { frames.first?.file.lowercased().hasSuffix(".dng") ?? false }
}

@Observable
final class StackLibrary {
    private(set) var stacks: [StackManifest] = []

    @ObservationIgnored let root: URL

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        root = docs.appendingPathComponent("FocusStacks", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        reload()
    }

    func folder(for stack: StackManifest) -> URL {
        root.appendingPathComponent(stack.id.uuidString, isDirectory: true)
    }

    func url(for frame: StackManifest.Frame, in stack: StackManifest) -> URL {
        folder(for: stack).appendingPathComponent(frame.file)
    }

    func reload() {
        let decoder = JSONDecoder()
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        stacks = dirs.compactMap { dir in
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("manifest.json")) else { return nil }
            return try? decoder.decode(StackManifest.self, from: data)
        }
        .sorted { $0.createdAt > $1.createdAt }
    }

    /// 把一次拍摄写入磁盘并返回新的清单。
    func add(_ capture: CaptureResult) async throws -> StackManifest {
        let id = UUID()
        let folder = root.appendingPathComponent(id.uuidString, isDirectory: true)
        let manifest: StackManifest = try await Task.detached(priority: .userInitiated) {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var frames: [StackManifest.Frame] = []
            for (index, frame) in capture.frames.enumerated() {
                let name = String(format: "frame_%02d.%@", index, frame.fileExtension)
                try frame.data.write(to: folder.appendingPathComponent(name), options: .atomic)
                frames.append(.init(file: name, lensPosition: frame.lensPosition))
            }
            let manifest = StackManifest(
                id: id,
                createdAt: Date(),
                frames: frames,
                referenceIndex: min(max(capture.referenceIndex, 0), frames.count - 1),
                hasDepth: capture.hasDepth,
                mode: capture.mode.rawValue
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = .prettyPrinted
            try encoder.encode(manifest).write(to: folder.appendingPathComponent("manifest.json"), options: .atomic)
            return manifest
        }.value
        stacks.insert(manifest, at: 0)
        return manifest
    }

    func delete(_ stack: StackManifest) {
        try? FileManager.default.removeItem(at: folder(for: stack))
        stacks.removeAll { $0.id == stack.id }
    }

    func thumbnail(for stack: StackManifest, maxPixelSize: Int = 400) async -> UIImage? {
        guard stack.frames.indices.contains(stack.referenceIndex) else { return nil }
        let url = url(for: stack.frames[stack.referenceIndex], in: stack)
        // RAW 优先使用拍摄时嵌入的缩略图，避免完整显影
        let fromImageKey = stack.isRAW ? kCGImageSourceCreateThumbnailFromImageIfAbsent : kCGImageSourceCreateThumbnailFromImageAlways
        return await Task.detached(priority: .utility) { () -> UIImage? in
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                fromImageKey: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        }.value
    }
}
