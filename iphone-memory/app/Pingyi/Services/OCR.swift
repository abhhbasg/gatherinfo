import Foundation
import UIKit
import Vision

enum OCR {
    /// 本地识别图片中的文字（简体、繁体中文与英文）。
    static func recognize(_ data: Data) async -> String {
        await Task.detached(priority: .userInitiated) {
            guard let cg = UIImage(data: data)?.cgImage else { return "" }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"]
            let handler = VNImageRequestHandler(cgImage: cg, options: [:])
            do {
                try handler.perform([request])
            } catch {
                return ""
            }
            let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
            return lines.joined(separator: "\n")
        }.value
    }

    static func thumbnail(_ data: Data, maxSide: CGFloat = 480) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let thumb = image.preparingThumbnail(of: size) ?? image
        return thumb.jpegData(compressionQuality: 0.75)
    }
}
