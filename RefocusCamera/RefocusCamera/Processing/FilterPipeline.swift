import CoreImage
import UIKit

/// 把 LookSettings 转换为 Core Image 滤镜链。
///
/// 所有与尺寸有关的半径都按图像宽度换算，预览与全分辨率导出效果一致。
/// 色调与色彩运算在 sRGB 伽马空间里做（更符合直觉），模糊类的光学效果在线性空间里做（更真实）。
enum FilterPipeline {
    static func apply(_ s: LookSettings, to input: CIImage, extent: CGRect) -> CIImage {
        guard !s.isIdentity else { return input }
        let unit = extent.width / 2000   // 以 2000 px 宽为基准
        var image = input

        // 1. 曝光（线性空间）
        if s.exposure != 0 {
            image = image.applyingFilter("CIExposureAdjust", parameters: [kCIInputEVKey: s.exposure * 2])
        }

        // 2. 高光 / 阴影的局部压缩与提亮
        if s.shadows > 0 || s.highlights < 0 {
            image = image.applyingFilter("CIHighlightShadowAdjust", parameters: [
                "inputShadowAmount": max(0, s.shadows),
                "inputHighlightAmount": 1 + min(0, s.highlights),
                kCIInputRadiusKey: 8 * unit,
            ])
        }

        // —— 进入 sRGB 伽马空间 ——
        image = image.applyingFilter("CILinearToSRGBToneCurve")

        // 3. 色温 / 色调：直接缩放通道，结果可预测
        if s.temperature != 0 || s.tint != 0 {
            let t = CGFloat(s.temperature), g = CGFloat(s.tint)
            image = image.applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 1 + 0.12 * t + 0.03 * g, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: 1 - 0.07 * g + 0.01 * t, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: 1 - 0.14 * t + 0.03 * g, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            ])
        }

        // 4. 色调曲线：对比度、胶片 S 曲线、褪色、高光
        if s.contrast != 0 || s.filmCurve != 0 || s.fade != 0 || s.highlights > 0 || s.shadows < 0 {
            image = image.applyingFilter("CIToneCurve", parameters: toneCurve(s))
        }

        // 5. 饱和度 / 黑白 / 自然饱和度
        let saturation = max(0, (1 + s.saturation) * (1 - min(1, s.monochrome)))
        if saturation != 1 {
            image = image.applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: saturation])
        }
        if s.vibrance != 0, s.monochrome < 1 {
            image = image.applyingFilter("CIVibrance", parameters: ["inputAmount": s.vibrance])
        }

        // 6. 分离色调：暗部与亮部分别用柔光混合一个颜色
        if s.splitAmount > 0.001 {
            image = splitTone(image, extent: extent, amount: s.splitAmount, shadowHue: s.splitShadowHue, highlightHue: s.splitHighlightHue)
        }

        // —— 回到线性空间 ——
        image = image.applyingFilter("CISRGBToneCurveToLinear")

        // 7. 清晰度（大半径反锐化 = 局部对比）与锐化
        if s.clarity > 0 {
            image = image.clampedToExtent()
                .applyingFilter("CIUnsharpMask", parameters: [kCIInputRadiusKey: 30 * unit, kCIInputIntensityKey: s.clarity * 0.7])
                .cropped(to: extent)
        }
        if s.sharpness > 0 {
            image = image.clampedToExtent()
                .applyingFilter("CISharpenLuminance", parameters: [kCIInputSharpnessKey: s.sharpness * 0.8])
                .cropped(to: extent)
        }

        // 8. 光学质感
        if s.bloom > 0 { image = bloom(image, extent: extent, amount: s.bloom, unit: unit) }
        if s.halation > 0 { image = halation(image, extent: extent, amount: s.halation, unit: unit) }
        if s.lightLeak > 0 { image = lightLeak(image, extent: extent, amount: s.lightLeak) }

        // 9. 暗角
        if s.vignette != 0 {
            image = image.applyingFilter("CIVignetteEffect", parameters: [
                kCIInputCenterKey: CIVector(x: extent.midX, y: extent.midY),
                kCIInputRadiusKey: max(extent.width, extent.height) * 0.62,
                kCIInputIntensityKey: s.vignette * 0.9,
            ]).cropped(to: extent)
        }

        // 10. 颗粒（最后加，像真正的胶片一样覆盖在影像之上）
        if s.grain > 0 { image = grain(image, extent: extent, amount: s.grain, size: s.grainSize, unit: unit) }

        return image.cropped(to: extent)
    }

    // MARK: - 曲线

    private static func toneCurve(_ s: LookSettings) -> [String: Any] {
        let c = s.contrast, f = s.filmCurve, fade = s.fade
        var y0 = 0.0 + fade * 0.16
        var y1 = 0.25 - c * 0.07 - f * 0.06 + max(0, -s.shadows) * -0.05 + fade * 0.05
        var y2 = 0.5
        var y3 = 0.75 + c * 0.07 + f * 0.05 + max(0, s.highlights) * 0.08
        var y4 = 1.0 - fade * 0.05 - f * 0.02
        // 保持单调
        y0 = min(max(y0, 0), 0.4)
        y1 = min(max(y1, y0 + 0.01), 0.45)
        y2 = max(y2, y1 + 0.01)
        y3 = min(max(y3, y2 + 0.01), 0.98)
        y4 = min(max(y4, y3 + 0.01), 1)
        return [
            "inputPoint0": CIVector(x: 0, y: y0),
            "inputPoint1": CIVector(x: 0.25, y: y1),
            "inputPoint2": CIVector(x: 0.5, y: y2),
            "inputPoint3": CIVector(x: 0.75, y: y3),
            "inputPoint4": CIVector(x: 1, y: y4),
        ]
    }

    // MARK: - 分离色调

    private static func splitTone(_ image: CIImage, extent: CGRect, amount: Double, shadowHue: Double, highlightHue: Double) -> CIImage {
        func solid(_ hue: Double) -> CIImage {
            let color = UIColor(hue: hue, saturation: 0.7, brightness: 1, alpha: 1)
            return CIImage(color: CIColor(color: color)).cropped(to: extent)
        }
        let luma = image.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: 0.2126, y: 0.7152, z: 0.0722, w: 0),
            "inputGVector": CIVector(x: 0.2126, y: 0.7152, z: 0.0722, w: 0),
            "inputBVector": CIVector(x: 0.2126, y: 0.7152, z: 0.0722, w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
        ])
        let a = CGFloat(amount)
        let highlightMask = luma.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: a, y: 0, z: 0, w: 0),
            "inputGVector": CIVector(x: a, y: 0, z: 0, w: 0),
            "inputBVector": CIVector(x: a, y: 0, z: 0, w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
        ])
        let shadowMask = luma.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: -a, y: 0, z: 0, w: 0),
            "inputGVector": CIVector(x: -a, y: 0, z: 0, w: 0),
            "inputBVector": CIVector(x: -a, y: 0, z: 0, w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            "inputBiasVector": CIVector(x: a, y: a, z: a, w: 0),
        ])
        let shadowTinted = solid(shadowHue).applyingFilter("CISoftLightBlendMode", parameters: [kCIInputBackgroundImageKey: image])
        let highlightTinted = solid(highlightHue).applyingFilter("CISoftLightBlendMode", parameters: [kCIInputBackgroundImageKey: image])
        let withShadows = shadowTinted.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: image, kCIInputMaskImageKey: shadowMask,
        ])
        return highlightTinted.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: withShadows, kCIInputMaskImageKey: highlightMask,
        ]).cropped(to: extent)
    }

    // MARK: - 光学效果

    /// 柔雾：模糊后的画面以滤色叠加，模拟柔焦滤镜
    private static func bloom(_ image: CIImage, extent: CGRect, amount: Double, unit: CGFloat) -> CIImage {
        let blurred = image.clampedToExtent().applyingGaussianBlur(sigma: 26 * unit).cropped(to: extent)
        let screened = blurred.applyingFilter("CIScreenBlendMode", parameters: [kCIInputBackgroundImageKey: image])
        return image.applyingFilter("CIDissolveTransition", parameters: [
            kCIInputTargetImageKey: screened,
            kCIInputTimeKey: min(1, amount * 0.65),
        ]).cropped(to: extent)
    }

    /// 光晕：只取最亮的部分，染成橙红色后扩散，模拟胶片片基反射造成的 halation
    private static func halation(_ image: CIImage, extent: CGRect, amount: Double, unit: CGFloat) -> CIImage {
        let k: CGFloat = 4
        let threshold: CGFloat = 0.72
        let luma = CIVector(x: 0.2126 * k, y: 0.7152 * k, z: 0.0722 * k, w: 0)
        let bias = -threshold * k
        let bright = image.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": luma, "inputGVector": luma, "inputBVector": luma,
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            "inputBiasVector": CIVector(x: bias, y: bias, z: bias, w: 0),
        ]).applyingFilter("CIColorClamp")
        let a = CGFloat(amount)
        let glow = bright.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: 1.0 * a, y: 0, z: 0, w: 0),
            "inputGVector": CIVector(x: 0, y: 0.28 * a, z: 0, w: 0),
            "inputBVector": CIVector(x: 0, y: 0, z: 0.12 * a, w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
        ])
        .clampedToExtent()
        .applyingGaussianBlur(sigma: 14 * unit)
        .cropped(to: extent)
        return glow.applyingFilter("CIAdditionCompositing", parameters: [kCIInputBackgroundImageKey: image]).cropped(to: extent)
    }

    /// 漏光：从画面一角渗入的暖色光
    private static func lightLeak(_ image: CIImage, extent: CGRect, amount: Double) -> CIImage {
        let a = CGFloat(min(1, amount))
        let gradient = CIFilter(name: "CIRadialGradient", parameters: [
            kCIInputCenterKey: CIVector(x: extent.maxX - extent.width * 0.05, y: extent.maxY - extent.height * 0.12),
            "inputRadius0": 0,
            "inputRadius1": max(extent.width, extent.height) * 0.75,
            "inputColor0": CIColor(red: 1, green: 0.42, blue: 0.12, alpha: 0.85 * a),
            "inputColor1": CIColor(red: 1, green: 0.2, blue: 0.3, alpha: 0),
        ])?.outputImage?.cropped(to: extent)
        guard let gradient else { return image }
        return gradient.applyingFilter("CIScreenBlendMode", parameters: [kCIInputBackgroundImageKey: image]).cropped(to: extent)
    }

    /// 颗粒：单色噪声放大到颗粒尺寸，以柔光叠加（0.5 灰 = 不变）
    private static func grain(_ image: CIImage, extent: CGRect, amount: Double, size: Double, unit: CGFloat) -> CIImage {
        guard let noise = CIFilter(name: "CIRandomGenerator")?.outputImage else { return image }
        let scale = (0.7 + CGFloat(size) * 2.6) * max(unit, 0.25)
        let a = CGFloat(amount) * 0.42
        let mono = CIVector(x: a, y: 0, z: 0, w: 0)
        let grain = noise
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .applyingGaussianBlur(sigma: 0.35 * scale)
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": mono, "inputGVector": mono, "inputBVector": mono,
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
                "inputBiasVector": CIVector(x: 0.5 - a / 2, y: 0.5 - a / 2, z: 0.5 - a / 2, w: 0),
            ])
            .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
            .cropped(to: extent)
        return grain.applyingFilter("CISoftLightBlendMode", parameters: [kCIInputBackgroundImageKey: image]).cropped(to: extent)
    }
}
