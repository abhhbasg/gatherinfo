import SwiftUI

/// 拍摄模式：从“系统完整处理”到“传感器原始数据”。
enum CaptureMode: String, CaseIterable, Identifiable, Codable, Sendable {
    /// HEIC，系统计算摄影（Smart HDR、多帧降噪、语义渲染），可附带深度
    case computational
    /// HEIC，速度优先：关闭多帧合成、多摄融合与畸变校正，保留深度
    case minimal
    /// Apple ProRAW：多帧合成后的线性 DNG
    case proRAW
    /// Bayer RAW：传感器原始数据，完全不经过计算摄影
    case bayerRAW
    /// 从视频流连续抓帧，扫焦最快
    case highSpeed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .computational: "智能"
        case .minimal: "少处理"
        case .proRAW: "ProRAW"
        case .bayerRAW: "RAW"
        case .highSpeed: "高速"
        }
    }

    var longTitle: String {
        switch self {
        case .computational: "智能 · 计算摄影"
        case .minimal: "少处理 · 速度优先"
        case .proRAW: "Apple ProRAW"
        case .bayerRAW: "RAW · 传感器原始数据"
        case .highSpeed: "高速 · 视频流扫焦"
        }
    }

    var symbol: String {
        switch self {
        case .computational: "wand.and.sparkles"
        case .minimal: "circle.lefthalf.filled"
        case .proRAW: "r.square.on.square"
        case .bayerRAW: "camera.aperture"
        case .highSpeed: "bolt.fill"
        }
    }

    var summary: String {
        switch self {
        case .computational: "系统完整的计算摄影：Smart HDR、多帧降噪与锐化，画面最“讨喜”。"
        case .minimal: "关闭多帧合成、多摄融合与畸变校正，保留景深与光圈虚化。"
        case .proRAW: "多帧合成后的 RAW，宽容度高；App 显影时不再额外锐化。"
        case .bayerRAW: "直接来自传感器：不锐化、不做多帧合成，在 App 内自行显影。"
        case .highSpeed: "边移动镜头边从视频流抓帧，扫焦最快、焦平面最密，适合手持。"
        }
    }

    var isRAW: Bool { self == .proRAW || self == .bayerRAW }
    var usesVideo: Bool { self == .highSpeed }
    var supportsDepth: Bool { self == .computational || self == .minimal }
    /// Bayer RAW 与视频扫焦使用广角物理镜头，其余使用双摄 / LiDAR 虚拟设备
    var prefersDepthDevice: Bool { self == .computational || self == .minimal || self == .proRAW }

    var frameCountOptions: [Int] { usesVideo ? [12, 24, 36] : [5, 9, 15] }
    var defaultFrameCount: Int { usesVideo ? 24 : 9 }

    func traits(depthAvailable: Bool) -> [ModeTrait] {
        let depth = supportsDepth && depthAvailable
        switch self {
        case .computational:
            return [
                .init(label: "锐化", value: "强", level: .high),
                .init(label: "降噪", value: "强", level: .high),
                .init(label: "多帧", value: "是", level: .high),
                .init(label: "深度", value: depth ? "有" : "无", level: depth ? .good : .neutral),
                .init(label: "格式", value: "HEIC", level: .neutral),
            ]
        case .minimal:
            return [
                .init(label: "锐化", value: "有", level: .neutral),
                .init(label: "降噪", value: "有", level: .neutral),
                .init(label: "多帧", value: "否", level: .good),
                .init(label: "深度", value: depth ? "有" : "无", level: depth ? .good : .neutral),
                .init(label: "格式", value: "HEIC", level: .neutral),
            ]
        case .proRAW:
            return [
                .init(label: "锐化", value: "未公开", level: .neutral),
                .init(label: "降噪", value: "有", level: .neutral),
                .init(label: "多帧", value: "是", level: .high),
                .init(label: "深度", value: "无", level: .neutral),
                .init(label: "格式", value: "DNG", level: .good),
            ]
        case .bayerRAW:
            return [
                .init(label: "锐化", value: "无", level: .good),
                .init(label: "降噪", value: "仅色度", level: .good),
                .init(label: "多帧", value: "否", level: .good),
                .init(label: "深度", value: "无", level: .neutral),
                .init(label: "格式", value: "DNG", level: .good),
            ]
        case .highSpeed:
            return [
                .init(label: "锐化", value: "有", level: .neutral),
                .init(label: "降噪", value: "有", level: .neutral),
                .init(label: "多帧", value: "否", level: .good),
                .init(label: "深度", value: "无", level: .neutral),
                .init(label: "格式", value: "视频帧", level: .neutral),
            ]
        }
    }
}

struct ModeTrait: Identifiable, Equatable {
    enum Level {
        /// 处理少 / 更“原始”
        case good
        case neutral
        /// 处理重
        case high

        var color: Color {
            switch self {
            case .good: .mint
            case .neutral: .white
            case .high: .orange
            }
        }
    }

    var id: String { label }
    let label: String
    let value: String
    let level: Level
}
