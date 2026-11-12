import Foundation
import ImageIO
import UIKit

/// 拍摄时从设备读取的参数（视频帧没有 EXIF，靠它补全水印信息）
struct CaptureMetadata: Codable, Hashable, Sendable {
    var iso: Float?
    var exposureSeconds: Double?
    var aperture: Float?
    var focalLength35: Int?
}

/// 水印上显示的信息
struct WatermarkInfo: Sendable {
    var model: String
    var focalLength: String?
    var aperture: String?
    var shutter: String?
    var iso: String?
    var date: Date
    var modeTitle: String?
    var frameCount: Int

    var parameters: [String] { [focalLength, aperture, shutter, iso].compactMap { $0 } }

    func dateString(_ style: DateStyle) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        switch style {
        case .full: f.dateFormat = "yyyy.MM.dd  HH:mm"
        case .imprint: f.dateFormat = "''yy MM dd"
        case .compact: f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        }
        return f.string(from: date)
    }

    enum DateStyle { case full, imprint, compact }

    /// 优先读取参考帧的 EXIF，缺失时用拍摄时记录的设备参数补全。
    static func load(manifest: StackManifest, referenceURL: URL) -> WatermarkInfo {
        var tiff: [CFString: Any] = [:]
        var exif: [CFString: Any] = [:]
        if let source = CGImageSourceCreateWithURL(referenceURL as CFURL, nil),
           let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] {
            tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
            exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        }
        let meta = manifest.metadata

        let model = (tiff[kCGImagePropertyTIFFModel] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "iPhone"
        let focal = (exif[kCGImagePropertyExifFocalLenIn35mmFilm] as? Int) ?? meta?.focalLength35
        let fNumber = (exif[kCGImagePropertyExifFNumber] as? Double) ?? meta?.aperture.map(Double.init)
        let exposure = (exif[kCGImagePropertyExifExposureTime] as? Double) ?? meta?.exposureSeconds
        let iso = (exif[kCGImagePropertyExifISOSpeedRatings] as? [Int])?.first ?? meta?.iso.map { Int($0.rounded()) }

        return WatermarkInfo(
            model: model,
            focalLength: focal.map { "\($0)mm" },
            aperture: fNumber.map { String(format: "ƒ/%.1f", $0) },
            shutter: exposure.map(formatShutter),
            iso: iso.map { "ISO \($0)" },
            date: manifest.createdAt,
            modeTitle: manifest.captureMode?.title,
            frameCount: manifest.frames.count
        )
    }

    private static func formatShutter(_ seconds: Double) -> String {
        guard seconds > 0 else { return "" }
        if seconds >= 1 { return String(format: "%.1f″", seconds) }
        return "1/\(Int((1 / seconds).rounded()))s"
    }
}

enum WatermarkStyle: String, CaseIterable, Identifiable, Codable, Sendable {
    case none, frameBar, gallery, film, instant, signature, viewfinder

    var id: String { rawValue }

    var title: String {
        switch self {
        case .none: "无"
        case .frameBar: "参数底栏"
        case .gallery: "画廊"
        case .film: "胶片边框"
        case .instant: "相纸"
        case .signature: "签名"
        case .viewfinder: "取景器"
        }
    }

    /// 是否在照片外加边框（边框颜色可选）
    var hasFrame: Bool { self == .frameBar || self == .gallery || self == .instant }
}

enum WatermarkFont: String, CaseIterable, Identifiable, Codable, Sendable {
    case sans, serif, mono, rounded

    var id: String { rawValue }

    var title: String {
        switch self {
        case .sans: "无衬线"
        case .serif: "衬线"
        case .mono: "等宽"
        case .rounded: "圆体"
        }
    }

    var design: UIFontDescriptor.SystemDesign {
        switch self {
        case .sans: .default
        case .serif: .serif
        case .mono: .monospaced
        case .rounded: .rounded
        }
    }
}

enum WatermarkTone: String, CaseIterable, Codable, Sendable {
    case light, dark
}

struct WatermarkSettings: Codable, Equatable, Sendable {
    var style: WatermarkStyle = .none
    var author: String = ""
    var font: WatermarkFont = .sans
    var tone: WatermarkTone = .light
    var showModel = true
    var showParameters = true
    var showDate = true

    private static let storageKey = "watermark.settings"

    /// 上次使用的水印设置（署名、字体等会被记住）
    static func load() -> WatermarkSettings {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let settings = try? JSONDecoder().decode(WatermarkSettings.self, from: data) else { return WatermarkSettings() }
        return settings
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        }
    }

    var trimmedAuthor: String? {
        let t = author.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
