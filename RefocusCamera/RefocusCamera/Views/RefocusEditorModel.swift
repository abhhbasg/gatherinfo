import Observation
import Photos
import SwiftUI
import UIKit

@MainActor
@Observable
final class RefocusEditorModel {
    enum Phase: Equatable {
        case loading(Double)
        case ready
        case failed(String)
    }

    var phase: Phase = .loading(0)
    var params = RefocusParameters(frameIndex: 0)
    private(set) var displayImage: UIImage?
    /// 每次画面更新 +1，用来驱动交叉淡化
    private(set) var imageVersion = 0
    private(set) var stack: AnalyzedStack?
    private(set) var isSaving = false
    var toast: String?

    private(set) var isRAW = false

    @ObservationIgnored private var frameURLs: [URL] = []
    @ObservationIgnored private var renderer: RefocusRenderer?
    @ObservationIgnored private var renderTask: Task<Void, Never>?
    @ObservationIgnored private var toastTask: Task<Void, Never>?

    var hasDepth: Bool { stack?.depth != nil }
    var frameCount: Int { stack?.frameCount ?? 0 }
    var imageSize: CGSize { stack?.displayExtent.size ?? .zero }

    func load(manifest: StackManifest, library: StackLibrary) async {
        guard stack == nil else { return }
        let urls = manifest.frames.map { library.url(for: $0, in: manifest) }
        frameURLs = urls
        isRAW = manifest.isRAW
        do {
            let analyzed = try await Task.detached(priority: .userInitiated) {
                try StackAnalyzer.analyze(manifest: manifest, frameURLs: urls) { value in
                    Task { @MainActor [weak self] in
                        if case .loading = self?.phase { self?.phase = .loading(value) }
                    }
                }
            }.value
            stack = analyzed
            renderer = RefocusRenderer(stack: analyzed)
            params = RefocusParameters(frameIndex: analyzed.referenceIndex)
            displayImage = UIImage(cgImage: analyzed.displayImages[analyzed.referenceIndex])
            imageVersion += 1
            phase = .ready
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    /// 点按画面：u, v 为归一化坐标（左上为原点）
    func tap(u: CGFloat, v: CGFloat) {
        guard let stack else { return }
        params.focusPoint = CGPoint(x: u, y: v)
        if params.mode == .focus {
            params.frameIndex = stack.sharpestFrame(u: u, v: v)
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    /// 参数变化后重新渲染。连续拖动滑块时会取消过期的渲染。
    func render(animated: Bool) {
        guard let renderer else { return }
        let params = params
        renderTask?.cancel()
        renderTask = Task {
            guard let cg = await renderer.renderDisplay(params), !Task.isCancelled else { return }
            withAnimation(animated ? .easeInOut(duration: 0.35) : .linear(duration: 0.08)) {
                displayImage = UIImage(cgImage: cg)
                imageVersion += 1
            }
        }
    }

    /// 存储合成后的全分辨率 HEIF
    func save() async {
        guard let renderer, !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        guard await requestPhotosAccess() else { return }
        guard let data = await renderer.exportHEIF(params) else {
            show(toast: "导出失败")
            return
        }
        await addToPhotos(data, success: "已存储到照片")
    }

    /// 存储当前焦平面那一帧的原始 DNG（未经任何处理，可在“照片”或 Lightroom 中自行显影）
    func saveOriginalRAW() async {
        guard isRAW, !isSaving, frameURLs.indices.contains(params.frameIndex) else { return }
        isSaving = true
        defer { isSaving = false }
        guard await requestPhotosAccess() else { return }
        guard let data = try? Data(contentsOf: frameURLs[params.frameIndex]) else {
            show(toast: "读取 DNG 失败")
            return
        }
        await addToPhotos(data, success: "已存储原始 DNG")
    }

    private func requestPhotosAccess() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            show(toast: "没有写入照片图库的权限")
            return false
        }
        return true
    }

    private func addToPhotos(_ data: Data, success: String) async {
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
            }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            show(toast: success)
        } catch {
            show(toast: "存储失败：\(error.localizedDescription)")
        }
    }

    private func show(toast message: String) {
        toastTask?.cancel()
        withAnimation(.spring(duration: 0.4)) { toast = message }
        toastTask = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            withAnimation(.spring(duration: 0.4)) { toast = nil }
        }
    }
}
