import CoreImage
import Foundation
import ImageIO

struct RefocusParameters: Equatable, Sendable {
    enum Mode: String, CaseIterable, Sendable {
        /// 使用某一帧：对焦在点按的位置
        case focus
        /// 每个区域取最清晰的帧，合成全景深照片
        case allInFocus
    }

    var mode: Mode = .focus
    var frameIndex: Int
    /// 0 = 不加虚化（f/16），1 = 最大虚化（f/1.4）
    var blur: Double = 0
    /// 归一化坐标，左上为原点
    var focusPoint = CGPoint(x: 0.5, y: 0.5)

    // 滤镜
    var preset: LookPreset = .original
    var presetIntensity: Double = 1
    var adjustments = LookSettings()

    // 水印
    var watermark = WatermarkSettings()

    var fNumber: Double { 16 * pow(1.4 / 16, blur) }
    var look: LookSettings { preset.settings.scaled(by: presetIntensity).adding(adjustments) }
    var needsComposition: Bool {
        mode == .allInFocus || blur > 0.01 || !look.isIdentity || watermark.style != .none
    }
}

/// 渲染结果：图像，以及照片在其中的位置（加了边框的水印会让画布变大）
struct RenderedFrame: @unchecked Sendable {
    let image: CGImage
    /// 归一化，左上为原点
    let photoRect: CGRect

    static let fullRect = CGRect(x: 0, y: 0, width: 1, height: 1)
}

/// 把焦点堆栈按参数合成最终画面：对焦 → 景深虚化 → 滤镜 → 水印。
/// 使用 actor 串行化 Core Image 渲染。
actor RefocusRenderer {
    let stack: AnalyzedStack
    let info: WatermarkInfo
    private let context = CIContext(options: [.cacheIntermediates: true])
    private var allInFocusDisplay: CIImage?
    private var overlayCache: (key: OverlayKey, image: CGImage?)?

    private struct OverlayKey: Equatable {
        var settings: WatermarkSettings
        var size: CGSize
        var focus: CGPoint
        var frame: Int
    }

    init(stack: AnalyzedStack, info: WatermarkInfo) {
        self.stack = stack
        self.info = info
    }

    /// 屏幕分辨率预览
    func renderDisplay(_ params: RefocusParameters) -> RenderedFrame? {
        if !params.needsComposition, stack.displayImages.indices.contains(params.frameIndex) {
            return RenderedFrame(image: stack.displayImages[params.frameIndex], photoRect: RenderedFrame.fullRect)
        }
        let (image, extent, photoRect) = finalImage(params, frames: stack.displayFrames, extent: stack.displayExtent, cachedAllInFocus: &allInFocusDisplay, cacheOverlay: true)
        guard let cg = context.createCGImage(image, from: extent, format: .RGBA8, colorSpace: ImageUtil.displayP3) else { return nil }
        return RenderedFrame(image: cg, photoRect: photoRect)
    }

    /// 全分辨率 HEIF，用于存储到照片
    func exportHEIF(_ params: RefocusParameters) -> Data? {
        var cache: CIImage?
        let (image, _, _) = finalImage(params, frames: stack.fullFrames, extent: stack.fullExtent, cachedAllInFocus: &cache, cacheOverlay: false)
        return context.heifRepresentation(of: image, format: .RGBA8, colorSpace: ImageUtil.displayP3, options: [
            CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.92,
        ])
    }

    /// 每个预设的小缩略图（基于参考帧）
    func presetThumbnails(maxDimension: CGFloat = 180) -> [LookPreset: CGImage] {
        let (base, extent) = thumbnailBase(maxDimension: maxDimension)
        var result: [LookPreset: CGImage] = [:]
        for preset in LookPreset.allCases {
            let image = FilterPipeline.apply(preset.settings, to: base, extent: extent)
            if let cg = context.createCGImage(image, from: extent, format: .RGBA8, colorSpace: ImageUtil.displayP3) {
                result[preset] = cg
            }
        }
        return result
    }

    /// 每种水印样式的缩略图
    func watermarkThumbnails(_ settings: WatermarkSettings, maxDimension: CGFloat = 360) -> [WatermarkStyle: CGImage] {
        let (base, extent) = thumbnailBase(maxDimension: maxDimension)
        var result: [WatermarkStyle: CGImage] = [:]
        for style in WatermarkStyle.allCases {
            var s = settings
            s.style = style
            let wm = WatermarkRenderer.Context(settings: s, info: info, focusPoint: CGPoint(x: 0.5, y: 0.5), frameNumber: stack.referenceIndex + 1)
            let overlay = WatermarkRenderer.drawOverlay(photoSize: extent.size, context: wm)
            let (image, canvas, _) = WatermarkRenderer.composite(photo: base, extent: extent, context: wm, overlay: overlay)
            if let cg = context.createCGImage(image, from: canvas, format: .RGBA8, colorSpace: ImageUtil.displayP3) {
                result[style] = cg
            }
        }
        return result
    }

    // MARK: - 合成

    private func thumbnailBase(maxDimension: CGFloat) -> (CIImage, CGRect) {
        let frame = stack.displayFrames[stack.referenceIndex]
        let e = stack.displayExtent
        let scale = min(1, maxDimension / max(e.width, e.height))
        let extent = CGRect(x: 0, y: 0, width: (e.width * scale).rounded(), height: (e.height * scale).rounded())
        let base = frame.applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: scale, kCIInputAspectRatioKey: 1.0])
            .cropped(to: extent)
        return (base, extent)
    }

    private func finalImage(_ params: RefocusParameters, frames: [CIImage], extent: CGRect,
                            cachedAllInFocus: inout CIImage?, cacheOverlay: Bool) -> (CIImage, CGRect, CGRect) {
        var image = compose(params, frames: frames, extent: extent, cachedAllInFocus: &cachedAllInFocus)
        image = FilterPipeline.apply(params.look, to: image, extent: extent)

        guard params.watermark.style != .none else { return (image, extent, RenderedFrame.fullRect) }
        let wm = WatermarkRenderer.Context(
            settings: params.watermark,
            info: info,
            focusPoint: params.focusPoint,
            frameNumber: params.frameIndex + 1
        )
        let overlay: CGImage?
        if cacheOverlay {
            let key = OverlayKey(
                settings: params.watermark,
                size: extent.size,
                focus: params.watermark.style == .viewfinder ? params.focusPoint : .zero,
                frame: params.watermark.style == .film ? params.frameIndex : 0
            )
            if let cached = overlayCache, cached.key == key {
                overlay = cached.image
            } else {
                overlay = WatermarkRenderer.drawOverlay(photoSize: extent.size, context: wm)
                overlayCache = (key, overlay)
            }
        } else {
            overlay = WatermarkRenderer.drawOverlay(photoSize: extent.size, context: wm)
        }
        return WatermarkRenderer.composite(photo: image, extent: extent, context: wm, overlay: overlay)
    }

    private func compose(_ params: RefocusParameters, frames: [CIImage], extent: CGRect, cachedAllInFocus: inout CIImage?) -> CIImage {
        var base: CIImage
        switch params.mode {
        case .focus:
            base = frames[min(max(params.frameIndex, 0), frames.count - 1)]
        case .allInFocus:
            if let cached = cachedAllInFocus {
                base = cached
            } else {
                base = allInFocus(frames: frames, extent: extent)
                cachedAllInFocus = base
            }
        }

        if params.blur > 0.01, let depth = stack.depth, let mask = blurMask(depth: depth, focus: params.focusPoint, extent: extent) {
            let radius = params.blur * Double(extent.width) * 0.025
            base = base.clampedToExtent()
                .applyingFilter("CIMaskedVariableBlur", parameters: [
                    "inputMask": mask,
                    kCIInputRadiusKey: radius,
                ])
                .cropped(to: extent)
        }
        return base
    }

    /// 焦点堆栈合成：每个网格单元取最清晰的帧，遮罩羽化后逐帧叠加。
    private func allInFocus(frames: [CIImage], extent: CGRect) -> CIImage {
        let gw = stack.gridWidth, gh = stack.gridHeight
        let reference = min(stack.referenceIndex, frames.count - 1)
        var result = frames[reference]
        for index in frames.indices where index != reference {
            var hasAny = false
            let bytes = stack.sharpestIndex.map { value -> UInt8 in
                if Int(value) == index { hasAny = true; return 255 }
                return 0
            }
            guard hasAny, let mask = ImageUtil.grayImage(bytes, width: gw, height: gh) else { continue }
            let soft = ImageUtil.stretchMask(mask, sourceWidth: gw, sourceHeight: gh, to: extent, softness: 0.6)
            result = frames[index].applyingFilter("CIBlendWithMask", parameters: [
                kCIInputBackgroundImageKey: result,
                kCIInputMaskImageKey: soft,
            ])
        }
        return result.cropped(to: extent)
    }

    /// 合成虚化遮罩：与对焦点视差相差越大，虚化越强。
    private func blurMask(depth: DepthField, focus: CGPoint, extent: CGRect) -> CIImage? {
        let d0 = depth.sample(u: focus.x, v: focus.y)
        let tolerance: Float = 0.04
        let gain: Float = 4
        let bytes = depth.values.map { d -> UInt8 in
            let m = min(1, max(0, (abs(d - d0) - tolerance) * gain))
            return UInt8(m * 255)
        }
        guard let mask = ImageUtil.grayImage(bytes, width: depth.width, height: depth.height) else { return nil }
        return ImageUtil.stretchMask(mask, sourceWidth: depth.width, sourceHeight: depth.height, to: extent, softness: 1.5)
    }
}
