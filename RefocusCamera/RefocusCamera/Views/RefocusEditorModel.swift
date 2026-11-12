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
    /// 照片在 displayImage 中的位置（归一化，左上为原点）；加边框的水印会让它小于整张图
    private(set) var photoRect = RenderedFrame.fullRect
    /// 每次画面更新 +1，用来驱动交叉淡化
    private(set) var imageVersion = 0
    private(set) var stack: AnalyzedStack?
    private(set) var isSaving = false
    private(set) var isRAW = false
    private(set) var presetThumbnails: [LookPreset: UIImage] = [:]
    private(set) var watermarkThumbnails: [WatermarkStyle: UIImage] = [:]
    var toast: String?

    @ObservationIgnored private var frameURLs: [URL] = []
    @ObservationIgnored private var renderer: RefocusRenderer?
    @ObservationIgnored private var isRendering = false
    @ObservationIgnored private var pendingRender: (params: RefocusParameters, animated: Bool)?
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var watermarkThumbTask: Task<Void, Never>?

    var hasDepth: Bool { stack?.depth != nil }
    var frameCount: Int { stack?.frameCount ?? 0 }
    var imageSize: CGSize { displayImage?.size ?? stack?.displayExtent.size ?? .zero }
    /// 未经滤镜与水印的当前帧（长按对比原图）
    var originalImage: UIImage? {
        guard let stack, stack.displayImages.indices.contains(params.frameIndex) else { return nil }
        return UIImage(cgImage: stack.displayImages[params.frameIndex])
    }

    var isEdited: Bool { !params.look.isIdentity || params.watermark.style != .none }

    func load(manifest: StackManifest, library: StackLibrary) async {
        guard stack == nil else { return }
        let urls = manifest.frames.map { library.url(for: $0, in: manifest) }
        frameURLs = urls
        isRAW = manifest.isRAW
        do {
            let (analyzed, info) = try await Task.detached(priority: .userInitiated) {
                let stack = try StackAnalyzer.analyze(manifest: manifest, frameURLs: urls) { value in
                    Task { @MainActor [weak self] in
                        if case .loading = self?.phase { self?.phase = .loading(value) }
                    }
                }
                let info = WatermarkInfo.load(manifest: manifest, referenceURL: urls[stack.referenceIndex])
                return (stack, info)
            }.value
            stack = analyzed
            let renderer = RefocusRenderer(stack: analyzed, info: info)
            self.renderer = renderer
            var initial = RefocusParameters(frameIndex: analyzed.referenceIndex)
            initial.watermark = WatermarkSettings.load()
            initial.watermark.style = .none   // 记住署名与字体，但每张照片默认不加水印
            params = initial
            displayImage = UIImage(cgImage: analyzed.displayImages[analyzed.referenceIndex])
            imageVersion += 1
            phase = .ready

            let thumbs = await renderer.presetThumbnails()
            withAnimation(.glassSpring) {
                presetThumbnails = thumbs.mapValues { UIImage(cgImage: $0) }
            }
            refreshWatermarkThumbnails()
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    // MARK: - 交互

    /// 点按画面：u, v 为整张显示图中的归一化坐标（左上为原点）
    func tap(u: CGFloat, v: CGFloat) {
        guard let stack else { return }
        // 换算到照片内部的坐标（去掉水印边框）
        let pu = (u - photoRect.minX) / photoRect.width
        let pv = (v - photoRect.minY) / photoRect.height
        guard (0 ... 1).contains(pu), (0 ... 1).contains(pv) else { return }
        params.focusPoint = CGPoint(x: pu, y: pv)
        if params.mode == .focus {
            params.frameIndex = stack.sharpestFrame(u: pu, v: pv)
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    func selectPreset(_ preset: LookPreset) {
        if params.preset != preset {
            params.preset = preset
            params.presetIntensity = 1
        }
    }

    func resetAdjustments() {
        params.adjustments = LookSettings()
    }

    /// 水印的署名、字体等变化后，重新生成样式缩略图并记住设置
    func watermarkSettingsChanged(_ old: WatermarkSettings, _ new: WatermarkSettings) {
        new.save()
        var a = old, b = new
        a.style = .none
        b.style = .none
        if a != b { refreshWatermarkThumbnails() }
    }

    private func refreshWatermarkThumbnails() {
        guard let renderer else { return }
        let settings = params.watermark
        watermarkThumbTask?.cancel()
        watermarkThumbTask = Task {
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled else { return }
            let thumbs = await renderer.watermarkThumbnails(settings)
            guard !Task.isCancelled else { return }
            withAnimation(.glassSpring) {
                watermarkThumbnails = thumbs.mapValues { UIImage(cgImage: $0) }
            }
        }
    }

    // MARK: - 渲染

    /// 参数变化后重新渲染。渲染进行中时只记住最新的参数，完成后再渲染一次，
    /// 这样拖动滑块时不会堆积过期的渲染任务。
    func render(animated: Bool) {
        pendingRender = (params, animated || (pendingRender?.animated ?? false))
        guard !isRendering, let renderer else { return }
        isRendering = true
        Task {
            while let next = pendingRender {
                pendingRender = nil
                guard let frame = await renderer.renderDisplay(next.params) else { continue }
                let image = UIImage(cgImage: frame.image)
                withAnimation(next.animated ? .easeInOut(duration: 0.38) : .linear(duration: 0.06)) {
                    displayImage = image
                    photoRect = frame.photoRect
                    imageVersion += 1
                }
            }
            isRendering = false
        }
    }

    // MARK: - 存储

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
        withAnimation(.glassSpring) { toast = message }
        toastTask = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            withAnimation(.glassSpring) { toast = nil }
        }
    }
}
