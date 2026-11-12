import Foundation

/// 一组完整的调色与质感参数。所有数值都是无量纲的，与图像分辨率无关，
/// 因此预览与全分辨率导出得到的效果一致。
struct LookSettings: Equatable, Codable, Sendable {
    // 光影
    var exposure: Double = 0        // -1...1  → ±2 EV
    var contrast: Double = 0        // -1...1
    var highlights: Double = 0      // -1...1
    var shadows: Double = 0         // -1...1
    var filmCurve: Double = 0       //  0...1  胶片 S 曲线
    var fade: Double = 0            //  0...1  抬高黑位（哑光）
    // 色彩
    var temperature: Double = 0     // -1...1  冷 ↔ 暖
    var tint: Double = 0            // -1...1  绿 ↔ 品红
    var saturation: Double = 0      // -1...1
    var vibrance: Double = 0        // -1...1
    var monochrome: Double = 0      //  0...1  黑白
    var splitAmount: Double = 0     //  0...1  分离色调
    var splitShadowHue: Double = 0.53
    var splitHighlightHue: Double = 0.09
    // 细节
    var clarity: Double = 0         //  0...1  局部对比
    var sharpness: Double = 0       //  0...1  锐化（默认关闭）
    // 质感
    var grain: Double = 0           //  0...1
    var grainSize: Double = 0.35    //  0...1
    var vignette: Double = 0        // -1...1
    var halation: Double = 0        //  0...1  高光红晕
    var bloom: Double = 0           //  0...1  柔雾
    var lightLeak: Double = 0       //  0...1  漏光

    static let identity = LookSettings()

    var isIdentity: Bool { self == .identity }

    /// 预设按强度缩放（色相保持不变）
    func scaled(by t: Double) -> LookSettings {
        var s = self
        for key in Self.numericKeys { s[keyPath: key] = self[keyPath: key] * t }
        s.grainSize = grainSize
        return s
    }

    /// 在预设之上叠加用户的微调
    func adding(_ other: LookSettings) -> LookSettings {
        var s = self
        for key in Self.numericKeys { s[keyPath: key] = self[keyPath: key] + other[keyPath: key] }
        if other.grainSize != LookSettings.identity.grainSize { s.grainSize = other.grainSize }
        return s
    }

    private static let numericKeys: [WritableKeyPath<LookSettings, Double>] = [
        \.exposure, \.contrast, \.highlights, \.shadows, \.filmCurve, \.fade,
        \.temperature, \.tint, \.saturation, \.vibrance, \.monochrome, \.splitAmount,
        \.clarity, \.sharpness, \.grain, \.vignette, \.halation, \.bloom, \.lightLeak,
    ]
}

/// 原创的调色预设
enum LookPreset: String, CaseIterable, Identifiable, Codable, Sendable {
    case original, sunlit, lime, silver, noir, faded, cinema, neon, mist, retro, frost, rich

    var id: String { rawValue }

    var title: String {
        switch self {
        case .original: "原片"
        case .sunlit: "暖阳 400"
        case .lime: "青柠"
        case .silver: "银盐"
        case .noir: "高反差黑白"
        case .faded: "褪色胶片"
        case .cinema: "电影"
        case .neon: "夜霓虹"
        case .mist: "柔雾"
        case .retro: "复古"
        case .frost: "冷调"
        case .rich: "浓郁"
        }
    }

    var settings: LookSettings {
        var s = LookSettings()
        switch self {
        case .original:
            break
        case .sunlit:
            s.temperature = 0.28; s.fade = 0.12; s.filmCurve = 0.45; s.saturation = -0.05; s.vibrance = 0.12
            s.splitAmount = 0.18; s.splitShadowHue = 0.5; s.splitHighlightHue = 0.09
            s.grain = 0.28; s.grainSize = 0.35; s.halation = 0.22
        case .lime:
            s.temperature = -0.08; s.tint = -0.12; s.contrast = 0.1; s.saturation = 0.08; s.filmCurve = 0.4
            s.splitAmount = 0.32; s.splitShadowHue = 0.52; s.splitHighlightHue = 0.16; s.grain = 0.15
        case .silver:
            s.monochrome = 1; s.contrast = 0.2; s.filmCurve = 0.6; s.grain = 0.38; s.grainSize = 0.25; s.vignette = 0.25
        case .noir:
            s.monochrome = 1; s.contrast = 0.55; s.filmCurve = 0.85; s.clarity = 0.35; s.shadows = -0.15
            s.grain = 0.22; s.grainSize = 0.3; s.vignette = 0.4
        case .faded:
            s.fade = 0.42; s.contrast = -0.15; s.saturation = -0.25; s.temperature = 0.1
            s.splitAmount = 0.2; s.splitShadowHue = 0.58; s.splitHighlightHue = 0.11; s.grain = 0.32; s.grainSize = 0.45
        case .cinema:
            s.contrast = 0.18; s.filmCurve = 0.5; s.saturation = -0.1; s.highlights = -0.15
            s.splitAmount = 0.48; s.splitShadowHue = 0.52; s.splitHighlightHue = 0.075
            s.vignette = 0.22; s.halation = 0.12; s.grain = 0.12
        case .neon:
            s.temperature = -0.25; s.tint = 0.22; s.vibrance = 0.35; s.contrast = 0.2
            s.halation = 0.45; s.bloom = 0.28; s.splitAmount = 0.25; s.splitShadowHue = 0.68; s.splitHighlightHue = 0.9
        case .mist:
            s.bloom = 0.6; s.fade = 0.15; s.contrast = -0.12; s.highlights = -0.2; s.temperature = 0.06; s.saturation = -0.08
        case .retro:
            s.temperature = 0.35; s.tint = 0.08; s.fade = 0.3; s.saturation = -0.2; s.filmCurve = 0.3
            s.grain = 0.48; s.grainSize = 0.55; s.vignette = 0.42; s.lightLeak = 0.38; s.halation = 0.15
        case .frost:
            s.temperature = -0.32; s.saturation = -0.12; s.contrast = 0.1; s.highlights = -0.1; s.fade = 0.08
            s.splitAmount = 0.15; s.splitShadowHue = 0.6; s.splitHighlightHue = 0.55
        case .rich:
            s.vibrance = 0.42; s.contrast = 0.25; s.filmCurve = 0.4; s.shadows = 0.12; s.clarity = 0.15; s.vignette = 0.15
        }
        return s
    }
}

/// 调整面板中的工具
enum AdjustmentTool: String, CaseIterable, Identifiable, Sendable {
    case exposure, contrast, highlights, shadows, filmCurve, fade
    case temperature, tint, saturation, vibrance, monochrome, split
    case clarity, sharpness
    case grain, grainSize, vignette, halation, bloom, lightLeak

    var id: String { rawValue }

    var title: String {
        switch self {
        case .exposure: "曝光"
        case .contrast: "对比度"
        case .highlights: "高光"
        case .shadows: "阴影"
        case .filmCurve: "胶片曲线"
        case .fade: "褪色"
        case .temperature: "色温"
        case .tint: "色调"
        case .saturation: "饱和度"
        case .vibrance: "自然饱和度"
        case .monochrome: "黑白"
        case .split: "分离色调"
        case .clarity: "清晰度"
        case .sharpness: "锐化"
        case .grain: "颗粒"
        case .grainSize: "颗粒大小"
        case .vignette: "暗角"
        case .halation: "光晕"
        case .bloom: "柔雾"
        case .lightLeak: "漏光"
        }
    }

    var symbol: String {
        switch self {
        case .exposure: "plusminus.circle"
        case .contrast: "circle.lefthalf.filled"
        case .highlights: "sun.max"
        case .shadows: "moon"
        case .filmCurve: "chart.xyaxis.line"
        case .fade: "circle.dotted"
        case .temperature: "thermometer.medium"
        case .tint: "paintpalette"
        case .saturation: "drop"
        case .vibrance: "drop.halffull"
        case .monochrome: "camera.filters"
        case .split: "paintbrush"
        case .clarity: "triangle"
        case .sharpness: "diamond"
        case .grain: "circle.grid.3x3.fill"
        case .grainSize: "circle.grid.2x2"
        case .vignette: "circle.circle"
        case .halation: "flame"
        case .bloom: "cloud.fog"
        case .lightLeak: "sun.horizon"
        }
    }

    var keyPath: WritableKeyPath<LookSettings, Double> {
        switch self {
        case .exposure: \.exposure
        case .contrast: \.contrast
        case .highlights: \.highlights
        case .shadows: \.shadows
        case .filmCurve: \.filmCurve
        case .fade: \.fade
        case .temperature: \.temperature
        case .tint: \.tint
        case .saturation: \.saturation
        case .vibrance: \.vibrance
        case .monochrome: \.monochrome
        case .split: \.splitAmount
        case .clarity: \.clarity
        case .sharpness: \.sharpness
        case .grain: \.grain
        case .grainSize: \.grainSize
        case .vignette: \.vignette
        case .halation: \.halation
        case .bloom: \.bloom
        case .lightLeak: \.lightLeak
        }
    }

    /// 用户可调的范围（叠加在预设之上）
    var range: ClosedRange<Double> {
        switch self {
        case .exposure, .contrast, .highlights, .shadows, .temperature, .tint, .saturation, .vibrance, .vignette: -1 ... 1
        default: 0 ... 1
        }
    }

    var defaultValue: Double { self == .grainSize ? LookSettings.identity.grainSize : 0 }
    var isBipolar: Bool { range.lowerBound < 0 }
}
