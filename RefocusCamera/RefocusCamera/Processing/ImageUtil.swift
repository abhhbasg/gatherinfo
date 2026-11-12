import CoreGraphics
import CoreImage
import Foundation

enum ImageUtil {
    static let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
    static let linearSRGB = CGColorSpace(name: CGColorSpace.linearSRGB)!
    static let displayP3 = CGColorSpace(name: CGColorSpace.displayP3)!

    /// 把 CIImage 精确缩放到 width×height，返回 RGBA8 像素（第一行 = 图像顶部）。
    static func rgbaBytes(of image: CIImage, width: Int, height: Int, context: CIContext, colorSpace: CGColorSpace) -> [UInt8]? {
        let extent = image.extent
        guard extent.width > 0, extent.height > 0 else { return nil }
        let scaled = image
            .transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
            .transformed(by: CGAffineTransform(scaleX: CGFloat(width) / extent.width, y: CGFloat(height) / extent.height))
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        guard let cg = context.createCGImage(scaled, from: rect, format: .RGBA8, colorSpace: colorSpace) else { return nil }
        return rgbaBytes(of: cg, width: width, height: height, colorSpace: colorSpace)
    }

    /// 通过 CGContext 读取像素，内存布局明确：第一行 = 图像顶部。
    static func rgbaBytes(of image: CGImage, width: Int, height: Int, colorSpace: CGColorSpace) -> [UInt8]? {
        var buffer = [UInt8](repeating: 0, count: width * height * 4)
        let ok = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(
                data: raw.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return ok ? buffer : nil
    }

    /// 由 8 位灰度数据（第一行 = 顶部）生成不做色彩管理的 CIImage，用作遮罩。
    static func grayImage(_ bytes: [UInt8], width: Int, height: Int) -> CIImage? {
        guard bytes.count == width * height,
              let provider = CGDataProvider(data: Data(bytes) as CFData),
              let cg = CGImage(
                  width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
                  space: CGColorSpaceCreateDeviceGray(),
                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                  provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
              )
        else { return nil }
        return CIImage(cgImage: cg, options: [.colorSpace: NSNull()])
    }

    /// 把灰度遮罩拉伸到目标尺寸并柔化边缘。
    static func stretchMask(_ mask: CIImage, sourceWidth: Int, sourceHeight: Int, to extent: CGRect, softness: CGFloat) -> CIImage {
        let sx = extent.width / CGFloat(sourceWidth)
        let sy = extent.height / CGFloat(sourceHeight)
        return mask
            .transformed(by: CGAffineTransform(scaleX: sx, y: sy))
            .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
            .clampedToExtent()
            .applyingGaussianBlur(sigma: max(sx, sy) * softness)
            .cropped(to: extent)
    }
}

extension CIImage {
    /// 把图像平移到原点 (0, 0)。
    var atOrigin: CIImage {
        transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
    }
}
