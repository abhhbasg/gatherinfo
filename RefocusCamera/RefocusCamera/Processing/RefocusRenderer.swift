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

    var fNumber: Double { 16 * pow(1.4 / 16, blur) }
    var needsComposition: Bool { mode == .allInFocus || blur > 0.01 }
}

/// 把焦点堆栈按参数合成最终画面。使用 actor 串行化 Core Image 渲染。
actor RefocusRenderer {
    let stack: AnalyzedStack
    private let context = CIContext(options: [.cacheIntermediates: true])
    private var allInFocusDisplay: CIImage?

    init(stack: AnalyzedStack) {
        self.stack = stack
    }

    /// 屏幕分辨率预览
    func renderDisplay(_ params: RefocusParameters) -> CGImage? {
        if !params.needsComposition, stack.displayImages.indices.contains(params.frameIndex) {
            return stack.displayImages[params.frameIndex]
        }
        let image = compose(params, frames: stack.displayFrames, extent: stack.displayExtent, cachedAllInFocus: &allInFocusDisplay)
        return context.createCGImage(image, from: stack.displayExtent, format: .RGBA8, colorSpace: ImageUtil.displayP3)
    }

    /// 全分辨率 HEIF，用于存储到照片
    func exportHEIF(_ params: RefocusParameters) -> Data? {
        var cache: CIImage?
        let image = compose(params, frames: stack.fullFrames, extent: stack.fullExtent, cachedAllInFocus: &cache)
        return context.heifRepresentation(of: image, format: .RGBA8, colorSpace: ImageUtil.displayP3, options: [
            CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.92,
        ])
    }

    // MARK: - 合成

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
