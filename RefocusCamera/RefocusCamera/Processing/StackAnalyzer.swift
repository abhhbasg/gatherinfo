import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import simd
import Vision

/// 归一化视差图（0 = 最远，1 = 最近），第一行 = 图像顶部。
struct DepthField: Sendable {
    let width: Int
    let height: Int
    let values: [Float]

    /// 在归一化坐标（左上为原点）附近取平均值，避免单个像素的噪声。
    func sample(u: CGFloat, v: CGFloat, radius: Int = 3) -> Float {
        let cx = min(width - 1, max(0, Int(u * CGFloat(width))))
        let cy = min(height - 1, max(0, Int(v * CGFloat(height))))
        var sum: Float = 0
        var count: Float = 0
        for y in max(0, cy - radius) ... min(height - 1, cy + radius) {
            for x in max(0, cx - radius) ... min(width - 1, cx + radius) {
                sum += values[y * width + x]
                count += 1
            }
        }
        return count > 0 ? sum / count : 0.5
    }
}

/// 分析完成、可以随时重新对焦的焦点堆栈。
final class AnalyzedStack: @unchecked Sendable {
    let lensPositions: [Float]
    let referenceIndex: Int

    /// 已对齐的全分辨率帧（惰性解码，用于导出）
    let fullFrames: [CIImage]
    let fullExtent: CGRect
    /// 已对齐、已解码的屏幕分辨率帧（用于实时预览）
    let displayImages: [CGImage]
    let displayFrames: [CIImage]
    let displayExtent: CGRect

    /// 清晰度网格：energy[帧][y * gridWidth + x]
    let gridWidth: Int
    let gridHeight: Int
    let energy: [[Float]]
    /// 每个网格单元最清晰的帧（用于全景深合成）
    let sharpestIndex: [UInt8]
    let depth: DepthField?

    var frameCount: Int { fullFrames.count }

    init(lensPositions: [Float], referenceIndex: Int, fullFrames: [CIImage], fullExtent: CGRect,
         displayImages: [CGImage], displayExtent: CGRect, gridWidth: Int, gridHeight: Int,
         energy: [[Float]], sharpestIndex: [UInt8], depth: DepthField?) {
        self.lensPositions = lensPositions
        self.referenceIndex = referenceIndex
        self.fullFrames = fullFrames
        self.fullExtent = fullExtent
        self.displayImages = displayImages
        self.displayFrames = displayImages.map { CIImage(cgImage: $0) }
        self.displayExtent = displayExtent
        self.gridWidth = gridWidth
        self.gridHeight = gridHeight
        self.energy = energy
        self.sharpestIndex = sharpestIndex
        self.depth = depth
    }

    /// 在归一化坐标（左上为原点）处找出最清晰的那一帧。
    /// 先看 3×3 邻域；如果该处缺少纹理、各帧差别不明显，就逐步扩大范围。
    func sharpestFrame(u: CGFloat, v: CGFloat) -> Int {
        guard frameCount > 1 else { return 0 }
        let cx = min(gridWidth - 1, max(0, Int(u * CGFloat(gridWidth))))
        let cy = min(gridHeight - 1, max(0, Int(v * CGFloat(gridHeight))))
        var best = referenceIndex
        for radius in [1, 2, 4, 7] {
            var scores = [Float](repeating: 0, count: frameCount)
            for y in max(0, cy - radius) ... min(gridHeight - 1, cy + radius) {
                for x in max(0, cx - radius) ... min(gridWidth - 1, cx + radius) {
                    let cell = y * gridWidth + x
                    for f in 0 ..< frameCount { scores[f] += energy[f][cell] }
                }
            }
            guard let maxScore = scores.max(), let minScore = scores.min(), maxScore > 0 else { continue }
            best = scores.firstIndex(of: maxScore) ?? referenceIndex
            if (maxScore - minScore) / maxScore > 0.2 { return best }
        }
        return best
    }
}

enum StackAnalyzerError: LocalizedError {
    case unreadableFrame
    case renderFailed

    var errorDescription: String? {
        switch self {
        case .unreadableFrame: "无法读取堆栈中的照片。"
        case .renderFailed: "图像处理失败。"
        }
    }
}

enum StackAnalyzer {
    static let analysisWidth = 960
    static let cellSize = 16

    /// 读取、对齐并分析一个焦点堆栈。耗时操作，请在后台调用。
    static func analyze(
        manifest: StackManifest,
        frameURLs: [URL],
        displayMaxDimension: CGFloat = 2048,
        progress: @escaping (Double) -> Void
    ) throws -> AnalyzedStack {
        let context = CIContext(options: [.cacheIntermediates: false])
        let count = frameURLs.count
        let steps = Double(count * 3 + 1)
        var step = 0.0
        func advance() { step += 1; progress(min(1, step / steps)) }

        // 1. 读取（应用 EXIF 方向）
        var originals: [CIImage] = []
        for url in frameURLs {
            guard let image = loadImage(url) else { throw StackAnalyzerError.unreadableFrame }
            originals.append(image.atOrigin)
        }
        let referenceIndex = min(max(manifest.referenceIndex, 0), count - 1)
        let reference = originals[referenceIndex]
        let fullExtent = reference.extent

        // 2. 对齐：对焦时镜头会“呼吸”（视角轻微缩放），用单应性配准把每帧对齐到基准帧。
        let registrationScale = min(1, 1024 / max(fullExtent.width, fullExtent.height))
        let referenceSmall = reference.transformed(by: CGAffineTransform(scaleX: registrationScale, y: registrationScale))
        var aligned: [CIImage] = []
        for (index, image) in originals.enumerated() {
            var frame = image
            if frame.extent.size != fullExtent.size {
                frame = frame.transformed(by: CGAffineTransform(
                    scaleX: fullExtent.width / frame.extent.width,
                    y: fullExtent.height / frame.extent.height
                ))
            }
            if index != referenceIndex,
               let warped = align(frame, toReference: referenceSmall, scale: registrationScale, extent: fullExtent) {
                frame = warped.composited(over: frame)
            }
            aligned.append(frame.cropped(to: fullExtent))
            advance()
        }

        // 3. 解码为屏幕分辨率，供实时预览
        let displayScale = min(1, displayMaxDimension / max(fullExtent.width, fullExtent.height))
        let displayExtent = CGRect(
            x: 0, y: 0,
            width: (fullExtent.width * displayScale).rounded(),
            height: (fullExtent.height * displayScale).rounded()
        )
        var displayImages: [CGImage] = []
        for frame in aligned {
            let scaled = frame.clampedToExtent().applyingFilter("CILanczosScaleTransform", parameters: [
                kCIInputScaleKey: displayScale,
                kCIInputAspectRatioKey: 1.0,
            ])
            guard let cg = context.createCGImage(scaled, from: displayExtent, format: .RGBA8, colorSpace: ImageUtil.displayP3) else {
                throw StackAnalyzerError.renderFailed
            }
            displayImages.append(cg)
            advance()
        }

        // 4. 清晰度分析：拉普拉斯能量，按 16×16 网格累加
        let aw = min(analysisWidth, Int(displayExtent.width)) / cellSize * cellSize
        let ah = max(cellSize, Int((CGFloat(aw) * fullExtent.height / fullExtent.width / CGFloat(cellSize)).rounded()) * cellSize)
        let gw = aw / cellSize
        let gh = ah / cellSize
        var energy: [[Float]] = []
        for cg in displayImages {
            guard let rgba = ImageUtil.rgbaBytes(of: cg, width: aw, height: ah, colorSpace: ImageUtil.sRGB) else {
                throw StackAnalyzerError.renderFailed
            }
            energy.append(laplacianEnergy(rgba: rgba, width: aw, height: ah, gridWidth: gw, gridHeight: gh))
            advance()
        }
        let sharpest = sharpestIndexMap(energy: energy, gridWidth: gw, gridHeight: gh, fallback: referenceIndex)

        // 5. 深度（视差）
        var depth: DepthField?
        if manifest.hasDepth,
           let disparity = CIImage(contentsOf: frameURLs[referenceIndex], options: [.auxiliaryDisparity: true, .applyOrientationProperty: true]) {
            depth = makeDepthField(disparity.atOrigin, width: max(1, aw / 2), height: max(1, ah / 2), context: context)
        }
        advance()

        return AnalyzedStack(
            lensPositions: manifest.frames.map(\.lensPosition),
            referenceIndex: referenceIndex,
            fullFrames: aligned,
            fullExtent: fullExtent,
            displayImages: displayImages,
            displayExtent: displayExtent,
            gridWidth: gw,
            gridHeight: gh,
            energy: energy,
            sharpestIndex: sharpest,
            depth: depth
        )
    }

    // MARK: - 读取

    /// 读取一帧并应用方向。DNG 用 CIRAWFilter 自行显影：
    /// 关闭锐化、亮度降噪与局部色调映射，只做去马赛克、白平衡、镜头校正和全局色调曲线。
    static func loadImage(_ url: URL) -> CIImage? {
        guard url.pathExtension.lowercased() == "dng" else {
            return CIImage(contentsOf: url, options: [.applyOrientationProperty: true])
        }
        guard let raw = CIRAWFilter(imageURL: url) else { return nil }
        if raw.isSharpnessSupported { raw.sharpnessAmount = 0 }
        if raw.isDetailSupported { raw.detailAmount = 0 }
        if raw.isLuminanceNoiseReductionSupported { raw.luminanceNoiseReductionAmount = 0 }
        if raw.isLocalToneMapSupported { raw.localToneMapAmount = 0 }
        return raw.outputImage
    }

    // MARK: - 对齐

    private static func align(_ frame: CIImage, toReference referenceSmall: CIImage, scale: CGFloat, extent: CGRect) -> CIImage? {
        let small = frame.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let request = VNHomographicImageRegistrationRequest(targetedCIImage: small, options: [:])
        let handler = VNImageRequestHandler(ciImage: referenceSmall, options: [:])
        do { try handler.perform([request]) } catch { return nil }
        guard let observation = request.results?.first else { return nil }
        let h = observation.warpTransform

        func map(_ p: CGPoint) -> CGPoint {
            let v = h * simd_float3(Float(p.x * scale), Float(p.y * scale), 1)
            guard abs(v.z) > 1e-6 else { return p }
            return CGPoint(x: CGFloat(v.x / v.z) / scale, y: CGFloat(v.y / v.z) / scale)
        }

        let w = extent.width, ht = extent.height
        let corners = [CGPoint(x: 0, y: ht), CGPoint(x: w, y: ht), CGPoint(x: 0, y: 0), CGPoint(x: w, y: 0)]
        let mapped = corners.map(map)
        // 对焦呼吸只会带来很小的形变；结果离谱时放弃对齐，避免把画面扭坏。
        let maxShift = zip(corners, mapped).map { hypot($0.x - $1.x, $0.y - $1.y) }.max() ?? .infinity
        guard maxShift.isFinite, maxShift < 0.06 * max(w, ht) else { return nil }

        let filter = CIFilter.perspectiveTransform()
        filter.inputImage = frame
        filter.topLeft = mapped[0]
        filter.topRight = mapped[1]
        filter.bottomLeft = mapped[2]
        filter.bottomRight = mapped[3]
        return filter.outputImage
    }

    // MARK: - 清晰度

    private static func laplacianEnergy(rgba: [UInt8], width: Int, height: Int, gridWidth: Int, gridHeight: Int) -> [Float] {
        var luma = [Float](repeating: 0, count: width * height)
        rgba.withUnsafeBufferPointer { src in
            luma.withUnsafeMutableBufferPointer { dst in
                for i in 0 ..< width * height {
                    let r = Float(src[i * 4]), g = Float(src[i * 4 + 1]), b = Float(src[i * 4 + 2])
                    dst[i] = (0.299 * r + 0.587 * g + 0.114 * b) / 255
                }
            }
        }
        var cells = [Float](repeating: 0, count: gridWidth * gridHeight)
        luma.withUnsafeBufferPointer { l in
            cells.withUnsafeMutableBufferPointer { c in
                for y in 1 ..< height - 1 {
                    let row = y * width
                    let cy = min(gridHeight - 1, y / cellSize)
                    for x in 1 ..< width - 1 {
                        let i = row + x
                        let lap = 4 * l[i] - l[i - 1] - l[i + 1] - l[i - width] - l[i + width]
                        c[cy * gridWidth + min(gridWidth - 1, x / cellSize)] += lap * lap
                    }
                }
            }
        }
        return cells
    }

    private static func sharpestIndexMap(energy: [[Float]], gridWidth gw: Int, gridHeight gh: Int, fallback: Int) -> [UInt8] {
        let frames = energy.count
        guard frames > 1 else { return [UInt8](repeating: 0, count: gw * gh) }
        var map = [UInt8](repeating: UInt8(fallback), count: gw * gh)
        for y in 0 ..< gh {
            for x in 0 ..< gw {
                var best = fallback
                var bestScore: Float = -1
                for f in 0 ..< frames {
                    var s: Float = 0
                    for yy in max(0, y - 1) ... min(gh - 1, y + 1) {
                        for xx in max(0, x - 1) ... min(gw - 1, x + 1) { s += energy[f][yy * gw + xx] }
                    }
                    if s > bestScore { bestScore = s; best = f }
                }
                map[y * gw + x] = UInt8(best)
            }
        }
        // 3×3 众数滤波，去掉孤立的噪点单元
        var filtered = map
        var votes = [Int](repeating: 0, count: frames)
        for y in 0 ..< gh {
            for x in 0 ..< gw {
                for i in votes.indices { votes[i] = 0 }
                for yy in max(0, y - 1) ... min(gh - 1, y + 1) {
                    for xx in max(0, x - 1) ... min(gw - 1, x + 1) { votes[Int(map[yy * gw + xx])] += 1 }
                }
                if let top = votes.indices.max(by: { votes[$0] < votes[$1] }), votes[top] >= 5 {
                    filtered[y * gw + x] = UInt8(top)
                }
            }
        }
        return filtered
    }

    // MARK: - 深度

    private static func makeDepthField(_ disparity: CIImage, width: Int, height: Int, context: CIContext) -> DepthField? {
        let minMax = disparity.applyingFilter("CIAreaMinMaxRed", parameters: [kCIInputExtentKey: CIVector(cgRect: disparity.extent)])
        var px = [Float](repeating: 0, count: 4)
        context.render(minMax, toBitmap: &px, rowBytes: 16, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBAf, colorSpace: nil)
        let lo = px[0], hi = px[1]
        guard lo.isFinite, hi.isFinite, hi - lo > 1e-4 else { return nil }

        let s = CGFloat(1 / (hi - lo))
        let b = CGFloat(-lo) * s
        let normalized = disparity.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: s, y: 0, z: 0, w: 0),
            "inputGVector": CIVector(x: s, y: 0, z: 0, w: 0),
            "inputBVector": CIVector(x: s, y: 0, z: 0, w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
            "inputBiasVector": CIVector(x: b, y: b, z: b, w: 1),
        ]).applyingFilter("CIColorClamp")

        guard let rgba = ImageUtil.rgbaBytes(of: normalized, width: width, height: height, context: context, colorSpace: ImageUtil.linearSRGB) else {
            return nil
        }
        var values = [Float](repeating: 0, count: width * height)
        for i in values.indices { values[i] = Float(rgba[i * 4]) / 255 }
        return DepthField(width: width, height: height, values: values)
    }
}
