import CoreImage
import SwiftUI
import UIKit

/// 水印渲染：先按照片尺寸计算画布与照片位置，再用 UIKit 绘制边框、文字与图形，
/// 最后在 Core Image 中与照片合成。所有尺寸都按照片宽度换算，预览与导出一致。
enum WatermarkRenderer {
    struct Layout: Equatable {
        /// 画布尺寸（像素）
        let canvas: CGSize
        /// 照片在画布中的位置（左上为原点，像素）
        let photo: CGRect
    }

    struct Context {
        var settings: WatermarkSettings
        var info: WatermarkInfo
        /// 对焦点（归一化，左上为原点），取景器样式会标出来
        var focusPoint: CGPoint
        /// 当前帧序号，胶片边框会印在片边上
        var frameNumber: Int
    }

    // MARK: - 布局

    static func layout(for style: WatermarkStyle, photoSize size: CGSize) -> Layout {
        let w = size.width, h = size.height
        func rounded(_ v: CGFloat) -> CGFloat { v.rounded() }
        switch style {
        case .none, .signature, .viewfinder:
            return Layout(canvas: size, photo: CGRect(origin: .zero, size: size))
        case .frameBar:
            let bar = rounded(w * 0.115)
            return Layout(canvas: CGSize(width: w, height: h + bar), photo: CGRect(x: 0, y: 0, width: w, height: h))
        case .gallery:
            let m = rounded(w * 0.06), bottom = rounded(w * 0.22)
            return Layout(canvas: CGSize(width: w + 2 * m, height: h + m + bottom), photo: CGRect(x: m, y: m, width: w, height: h))
        case .film:
            let side = rounded(w * 0.05), band = rounded(w * 0.11)
            return Layout(canvas: CGSize(width: w + 2 * side, height: h + 2 * band), photo: CGRect(x: side, y: band, width: w, height: h))
        case .instant:
            let s = rounded(w * 0.055), bottom = rounded(w * 0.3)
            return Layout(canvas: CGSize(width: w + 2 * s, height: h + s + bottom), photo: CGRect(x: s, y: s, width: w, height: h))
        }
    }

    // MARK: - 合成

    /// 返回 (合成后的图像, 画布范围, 照片在画布中的归一化位置)
    static func composite(photo: CIImage, extent: CGRect, context: Context, overlay: CGImage?) -> (CIImage, CGRect, CGRect) {
        let style = context.settings.style
        let layout = layout(for: style, photoSize: extent.size)
        let canvasRect = CGRect(origin: .zero, size: layout.canvas)
        let background = CIImage(color: CIColor(color: backgroundColor(for: context.settings))).cropped(to: canvasRect)

        // UIKit 左上原点 → Core Image 左下原点
        let placed = photo.transformed(by: CGAffineTransform(
            translationX: layout.photo.minX - extent.minX,
            y: layout.canvas.height - layout.photo.maxY - extent.minY
        ))
        var result = placed.composited(over: background)
        if let overlay {
            result = CIImage(cgImage: overlay).composited(over: result)
        }
        let normalized = CGRect(
            x: layout.photo.minX / layout.canvas.width,
            y: layout.photo.minY / layout.canvas.height,
            width: layout.photo.width / layout.canvas.width,
            height: layout.photo.height / layout.canvas.height
        )
        return (result.cropped(to: canvasRect), canvasRect, normalized)
    }

    static func backgroundColor(for settings: WatermarkSettings) -> UIColor {
        switch settings.style {
        case .film: return UIColor(white: 0.04, alpha: 1)
        case .instant: return settings.tone == .light ? UIColor(red: 0.965, green: 0.952, blue: 0.925, alpha: 1) : UIColor(white: 0.11, alpha: 1)
        case .gallery: return settings.tone == .light ? UIColor(red: 0.98, green: 0.98, blue: 0.975, alpha: 1) : UIColor(white: 0.06, alpha: 1)
        default: return settings.tone == .light ? .white : UIColor(white: 0.06, alpha: 1)
        }
    }

    // MARK: - 绘制

    /// 绘制透明的覆盖层（边框上的文字、图形，以及照片上的水印）
    static func drawOverlay(photoSize: CGSize, context c: Context) -> CGImage? {
        let style = c.settings.style
        guard style != .none else { return nil }
        let layout = layout(for: style, photoSize: photoSize)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: layout.canvas, format: format)
        let image = renderer.image { ctx in
            let cg = ctx.cgContext
            switch style {
            case .none: break
            case .frameBar: drawFrameBar(cg, layout, c)
            case .gallery: drawGallery(cg, layout, c)
            case .film: drawFilm(cg, layout, c)
            case .instant: drawInstant(cg, layout, c)
            case .signature: drawSignature(cg, layout, c)
            case .viewfinder: drawViewfinder(cg, layout, c)
            }
        }
        return image.cgImage
    }

    private struct Palette {
        let primary: UIColor
        let secondary: UIColor
        let tertiary: UIColor

        static func forTone(_ tone: WatermarkTone) -> Palette {
            tone == .light
                ? Palette(primary: UIColor(white: 0.07, alpha: 1), secondary: UIColor(white: 0.45, alpha: 1), tertiary: UIColor(white: 0.62, alpha: 1))
                : Palette(primary: UIColor(white: 0.95, alpha: 1), secondary: UIColor(white: 0.6, alpha: 1), tertiary: UIColor(white: 0.42, alpha: 1))
        }
    }

    // 参数底栏：左侧机型与日期，右侧拍摄参数与署名，中间细分隔线
    private static func drawFrameBar(_ cg: CGContext, _ l: Layout, _ c: Context) {
        let w = l.photo.width
        let barTop = l.photo.maxY
        let bar = l.canvas.height - barTop
        let p = Palette.forTone(c.settings.tone)
        let s = c.settings
        let inset = w * 0.045

        let leftPrimary = s.showModel ? c.info.model : (s.trimmedAuthor ?? c.info.modeTitle ?? "")
        let leftSecondary = s.showDate ? c.info.dateString(.full) : (c.info.modeTitle.map { "\($0) · \(c.info.frameCount) 帧" } ?? "")
        drawText(leftPrimary, font: font(s.font, w * 0.03, .bold), color: p.primary, kern: w * 0.0006,
                 at: CGPoint(x: inset, y: barTop + bar * 0.4), anchor: UnitPoint(x: 0, y: 0.5))
        drawText(leftSecondary, font: font(s.font, w * 0.0185, .regular), color: p.secondary,
                 at: CGPoint(x: inset, y: barTop + bar * 0.67), anchor: UnitPoint(x: 0, y: 0.5))

        let rightPrimary = s.showParameters ? c.info.parameters.joined(separator: "  ") : ""
        let rightSecondary = s.trimmedAuthor.map { "© \($0)" } ?? (c.info.modeTitle.map { "\($0) · 先拍摄后对焦" } ?? "")
        let rightX = l.canvas.width - inset
        let r1 = drawText(rightPrimary, font: font(s.font, w * 0.025, .semibold), color: p.primary, kern: w * 0.0004,
                          at: CGPoint(x: rightX, y: barTop + bar * 0.4), anchor: UnitPoint(x: 1, y: 0.5))
        let r2 = drawText(rightSecondary, font: font(s.font, w * 0.0175, .regular), color: p.secondary,
                          at: CGPoint(x: rightX, y: barTop + bar * 0.67), anchor: UnitPoint(x: 1, y: 0.5))

        // 分隔线与光圈标志
        let blockLeft = min(r1.minX, r2.minX)
        if blockLeft.isFinite, blockLeft < rightX {
            let x = blockLeft - w * 0.028
            cg.setFillColor(p.tertiary.withAlphaComponent(0.6).cgColor)
            cg.fill(CGRect(x: x, y: barTop + bar * 0.26, width: max(1, w * 0.0018), height: bar * 0.48))
            drawSymbol("camera.aperture", pointSize: w * 0.032, color: p.primary, center: CGPoint(x: x - w * 0.034, y: barTop + bar * 0.5))
        }
    }

    // 画廊：四周留白，下方居中排版
    private static func drawGallery(_ cg: CGContext, _ l: Layout, _ c: Context) {
        let w = l.photo.width
        let p = Palette.forTone(c.settings.tone)
        let s = c.settings
        let top = l.photo.maxY
        let bottom = l.canvas.height - top
        let cx = l.canvas.width / 2

        let title = s.showModel ? c.info.model : (s.trimmedAuthor ?? "")
        drawText(title.uppercased(), font: font(s.font, w * 0.03, .semibold), color: p.primary, kern: w * 0.004,
                 at: CGPoint(x: cx, y: top + bottom * 0.36), anchor: .center)
        if s.showParameters {
            drawText(c.info.parameters.joined(separator: "   ·   "), font: font(s.font, w * 0.0175, .regular), color: p.secondary,
                     kern: w * 0.0012, at: CGPoint(x: cx, y: top + bottom * 0.58), anchor: .center)
        }
        let footer = [s.showModel ? s.trimmedAuthor.map { "© \($0)" } : nil, s.showDate ? c.info.dateString(.full) : nil].compactMap { $0 }
        if !footer.isEmpty {
            drawText(footer.joined(separator: "     "), font: font(s.font, w * 0.0145, .regular), color: p.tertiary,
                     kern: w * 0.002, at: CGPoint(x: cx, y: top + bottom * 0.77), anchor: .center)
        }
        // 细线装饰
        cg.setFillColor(p.tertiary.withAlphaComponent(0.5).cgColor)
        cg.fill(CGRect(x: cx - w * 0.03, y: top + bottom * 0.47, width: w * 0.06, height: max(1, w * 0.0012)))
    }

    // 胶片边框：片孔、片边编号与日期印字
    private static func drawFilm(_ cg: CGContext, _ l: Layout, _ c: Context) {
        let w = l.photo.width
        let s = c.settings
        let band = l.photo.minY
        let amber = UIColor(red: 0.98, green: 0.6, blue: 0.2, alpha: 0.95)
        let holeColor = UIColor(red: 0.9, green: 0.88, blue: 0.84, alpha: 0.92)

        // 片孔
        let holeW = w * 0.026, holeH = w * 0.038, gap = w * 0.068
        let count = Int(l.canvas.width / gap) + 1
        let offset = (l.canvas.width - CGFloat(count - 1) * gap - holeW) / 2
        for row in [band * 0.3, l.canvas.height - band * 0.3] {
            for i in 0 ..< count {
                let rect = CGRect(x: offset + CGFloat(i) * gap, y: row - holeH / 2, width: holeW, height: holeH)
                cg.setFillColor(holeColor.cgColor)
                cg.addPath(UIBezierPath(roundedRect: rect, cornerRadius: w * 0.006).cgPath)
                cg.fillPath()
            }
        }

        // 片边印字
        let mono = UIFont.monospacedSystemFont(ofSize: w * 0.017, weight: .semibold)
        let n = c.frameNumber
        let topY = band * 0.76, bottomY = l.canvas.height - band * 0.76
        drawText("RFC 400  ▸  \(c.info.modeTitle ?? "")", font: mono, color: amber, kern: w * 0.002,
                 at: CGPoint(x: l.photo.minX, y: topY), anchor: UnitPoint(x: 0, y: 0.5))
        drawText("◀ \(n)A        \(n + 1)        \(n + 1)A ▶", font: mono, color: amber, kern: w * 0.002,
                 at: CGPoint(x: l.photo.maxX, y: topY), anchor: UnitPoint(x: 1, y: 0.5))
        let bottomLeft = s.showModel ? c.info.model.uppercased() : (s.trimmedAuthor ?? "")
        drawText(bottomLeft, font: mono, color: amber, kern: w * 0.003,
                 at: CGPoint(x: l.photo.minX, y: bottomY), anchor: UnitPoint(x: 0, y: 0.5))
        if s.showParameters {
            drawText(c.info.parameters.joined(separator: "  "), font: mono, color: amber, kern: w * 0.002,
                     at: CGPoint(x: l.photo.maxX, y: bottomY), anchor: UnitPoint(x: 1, y: 0.5))
        }

        // 照片右下角的日期印字（带辉光）
        if s.showDate {
            let imprint = UIFont.monospacedDigitSystemFont(ofSize: w * 0.038, weight: .bold)
            cg.saveGState()
            cg.setShadow(offset: .zero, blur: w * 0.01, color: UIColor(red: 1, green: 0.45, blue: 0.1, alpha: 0.9).cgColor)
            drawText(c.info.dateString(.imprint), font: imprint, color: UIColor(red: 1, green: 0.55, blue: 0.16, alpha: 0.92),
                     kern: w * 0.004, at: CGPoint(x: l.photo.maxX - w * 0.05, y: l.photo.maxY - w * 0.05), anchor: UnitPoint(x: 1, y: 1))
            cg.restoreGState()
        }
        if let author = s.trimmedAuthor, s.showModel {
            drawText("© \(author)", font: mono, color: amber.withAlphaComponent(0.7), kern: w * 0.002,
                     at: CGPoint(x: l.canvas.width / 2, y: bottomY), anchor: .center)
        }
    }

    // 相纸：宽下边，手写感的斜体题字
    private static func drawInstant(_ cg: CGContext, _ l: Layout, _ c: Context) {
        let w = l.photo.width
        let s = c.settings
        let p = Palette.forTone(c.settings.tone)
        // 照片边缘的细微压痕
        cg.setStrokeColor(UIColor.black.withAlphaComponent(c.settings.tone == .light ? 0.1 : 0.4).cgColor)
        cg.setLineWidth(max(1, w * 0.0018))
        cg.stroke(l.photo.insetBy(dx: -w * 0.0009, dy: -w * 0.0009))

        let top = l.photo.maxY
        let bottom = l.canvas.height - top
        let caption = s.trimmedAuthor ?? (s.showDate ? c.info.dateString(.full) : (s.showModel ? c.info.model : ""))
        let captionFont = font(s.font == .sans ? .serif : s.font, w * 0.052, .regular, italic: true)
        drawText(caption, font: captionFont, color: p.primary.withAlphaComponent(0.85),
                 at: CGPoint(x: l.canvas.width / 2, y: top + bottom * 0.42), anchor: .center)
        var details: [String] = []
        if s.showModel, s.trimmedAuthor != nil { details.append(c.info.model) }
        if s.showParameters { details.append(contentsOf: c.info.parameters) }
        if s.showDate, s.trimmedAuthor != nil { details.append(c.info.dateString(.full)) }
        if !details.isEmpty {
            drawText(details.joined(separator: "  ·  "), font: font(s.font, w * 0.017, .regular), color: p.secondary,
                     kern: w * 0.001, at: CGPoint(x: l.canvas.width / 2, y: top + bottom * 0.7), anchor: .center)
        }
    }

    // 签名：照片右下角的斜体署名与参数
    private static func drawSignature(_ cg: CGContext, _ l: Layout, _ c: Context) {
        let w = l.photo.width
        let s = c.settings
        let margin = w * 0.045
        let x = l.photo.maxX - margin
        var y = l.photo.maxY - margin
        cg.setShadow(offset: CGSize(width: 0, height: w * 0.001), blur: w * 0.008, color: UIColor.black.withAlphaComponent(0.45).cgColor)

        var details: [String] = []
        if s.showModel { details.append(c.info.model) }
        if s.showParameters { details.append(contentsOf: c.info.parameters) }
        if s.showDate { details.append(c.info.dateString(.full)) }
        if !details.isEmpty {
            let r = drawText(details.joined(separator: "  "), font: UIFont.monospacedSystemFont(ofSize: w * 0.0155, weight: .medium),
                             color: UIColor.white.withAlphaComponent(0.82), kern: w * 0.0015,
                             at: CGPoint(x: x, y: y), anchor: UnitPoint(x: 1, y: 1))
            y = r.minY - w * 0.01
        }
        let name = s.trimmedAuthor ?? c.info.model
        let signatureFont = font(s.font == .sans ? .serif : s.font, w * 0.048, .medium, italic: true)
        drawText(name, font: signatureFont, color: UIColor.white.withAlphaComponent(0.94),
                 at: CGPoint(x: x, y: y), anchor: UnitPoint(x: 1, y: 1))
    }

    // 取景器：四角框线、对焦框与等宽数据
    private static func drawViewfinder(_ cg: CGContext, _ l: Layout, _ c: Context) {
        let w = l.photo.width, h = l.photo.height
        let s = c.settings
        let inset = w * 0.04, arm = w * 0.07, line = max(1.5, w * 0.0035)
        let white = UIColor.white.withAlphaComponent(0.88)
        cg.setShadow(offset: .zero, blur: w * 0.006, color: UIColor.black.withAlphaComponent(0.5).cgColor)
        cg.setStrokeColor(white.cgColor)
        cg.setLineWidth(line)
        cg.setLineCap(.round)
        let r = l.photo.insetBy(dx: inset, dy: inset)
        let corners: [(CGPoint, CGFloat, CGFloat)] = [
            (CGPoint(x: r.minX, y: r.minY), 1, 1), (CGPoint(x: r.maxX, y: r.minY), -1, 1),
            (CGPoint(x: r.minX, y: r.maxY), 1, -1), (CGPoint(x: r.maxX, y: r.maxY), -1, -1),
        ]
        for (corner, dx, dy) in corners {
            cg.move(to: CGPoint(x: corner.x, y: corner.y + dy * arm))
            cg.addLine(to: corner)
            cg.addLine(to: CGPoint(x: corner.x + dx * arm, y: corner.y))
        }
        cg.strokePath()

        // 对焦框
        let f = CGPoint(x: l.photo.minX + c.focusPoint.x * w, y: l.photo.minY + c.focusPoint.y * h)
        let box = w * 0.11
        cg.setStrokeColor(UIColor(red: 1, green: 0.84, blue: 0.2, alpha: 0.95).cgColor)
        cg.setLineWidth(max(1, w * 0.0025))
        cg.stroke(CGRect(x: f.x - box / 2, y: f.y - box / 2, width: box, height: box))
        let ticks: [(CGFloat, CGFloat)] = [(0, -1), (0, 1), (-1, 0), (1, 0)]
        for (dx, dy) in ticks {
            cg.move(to: CGPoint(x: f.x + dx * box / 2, y: f.y + dy * box / 2))
            cg.addLine(to: CGPoint(x: f.x + dx * (box / 2 + w * 0.015), y: f.y + dy * (box / 2 + w * 0.015)))
        }
        cg.strokePath()

        let mono = UIFont.monospacedSystemFont(ofSize: w * 0.02, weight: .semibold)
        let pad = inset + w * 0.025
        drawText(c.info.modeTitle?.uppercased() ?? "PHOTO", font: mono, color: white, kern: w * 0.002,
                 at: CGPoint(x: l.photo.minX + pad, y: l.photo.minY + pad), anchor: UnitPoint(x: 0, y: 0))
        drawText("\(c.info.frameCount)F  ●", font: mono, color: white, kern: w * 0.002,
                 at: CGPoint(x: l.photo.maxX - pad, y: l.photo.minY + pad), anchor: UnitPoint(x: 1, y: 0))
        if s.showParameters {
            drawText(c.info.parameters.joined(separator: "  "), font: mono, color: white, kern: w * 0.0015,
                     at: CGPoint(x: l.photo.minX + pad, y: l.photo.maxY - pad), anchor: UnitPoint(x: 0, y: 1))
        }
        let right = [s.trimmedAuthor, s.showDate ? c.info.dateString(.compact) : nil, s.showModel && s.trimmedAuthor == nil ? c.info.model : nil]
            .compactMap { $0 }.joined(separator: "  ")
        drawText(right, font: mono, color: white, kern: w * 0.0015,
                 at: CGPoint(x: l.photo.maxX - pad, y: l.photo.maxY - pad), anchor: UnitPoint(x: 1, y: 1))
    }

    // MARK: - 文字工具

    static func font(_ font: WatermarkFont, _ size: CGFloat, _ weight: UIFont.Weight, italic: Bool = false) -> UIFont {
        var descriptor = UIFont.systemFont(ofSize: size, weight: weight).fontDescriptor
        if let designed = descriptor.withDesign(font.design) { descriptor = designed }
        if italic, let slanted = descriptor.withSymbolicTraits(descriptor.symbolicTraits.union(.traitItalic)) {
            descriptor = slanted
        }
        return UIFont(descriptor: descriptor, size: size)
    }

    /// 以 anchor（相对文字框的位置）对齐到 point 绘制文字，返回文字所占的矩形
    @discardableResult
    private static func drawText(_ text: String, font: UIFont, color: UIColor, kern: CGFloat = 0, at point: CGPoint, anchor: UnitPoint) -> CGRect {
        guard !text.isEmpty else { return CGRect(origin: point, size: .zero) }
        let attributed = NSAttributedString(string: text, attributes: [
            .font: font,
            .foregroundColor: color,
            .kern: kern,
        ])
        let size = attributed.size()
        let origin = CGPoint(x: point.x - size.width * anchor.x, y: point.y - size.height * anchor.y)
        attributed.draw(at: origin)
        return CGRect(origin: origin, size: size)
    }

    private static func drawSymbol(_ name: String, pointSize: CGFloat, color: UIColor, center: CGPoint) {
        let config = UIImage.SymbolConfiguration(pointSize: pointSize, weight: .regular)
        guard let image = UIImage(systemName: name, withConfiguration: config)?.withTintColor(color, renderingMode: .alwaysOriginal) else { return }
        let size = image.size
        image.draw(in: CGRect(x: center.x - size.width / 2, y: center.y - size.height / 2, width: size.width, height: size.height))
    }
}
