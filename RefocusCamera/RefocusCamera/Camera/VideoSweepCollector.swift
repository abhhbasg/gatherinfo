import AVFoundation
import CoreImage

/// “高速”模式：从视频流逐帧抓取画面，每抓到一帧就立即把镜头推向下一个位置，
/// 因此整个对焦范围可以在不到一秒内扫完。
///
/// 视频缓冲区来自系统的缓冲池，长时间持有会阻塞取景，所以每帧先深拷贝，再在后台编码为 HEIC。
final class VideoSweepCollector: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    struct Frame {
        let data: Data
        let lensPosition: Float
    }

    private let lock = NSLock()
    private var total = 0
    private var captured = 0
    private var device: AVCaptureDevice?
    private var progress: ((Int) -> Void)?
    private var completion: ((Result<[Frame], Error>) -> Void)?
    private var results: [Int: Frame] = [:]
    private let group = DispatchGroup()
    private let encodeQueue = DispatchQueue(label: "refocus.video.encode", qos: .userInitiated)
    private let context = CIContext(options: [.cacheIntermediates: false])

    func collect(count: Int, device: AVCaptureDevice, progress: @escaping (Int) -> Void) async throws -> [Frame] {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            self.total = max(1, count)
            self.captured = 0
            self.results = [:]
            self.device = device
            self.progress = progress
            self.completion = { continuation.resume(with: $0) }
            lock.unlock()
        }
    }

    /// 超时或会话中断时调用
    func cancel(with error: Error) {
        lock.lock()
        let completion = completion
        self.completion = nil
        lock.unlock()
        completion?(.failure(error))
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        lock.lock()
        guard completion != nil, captured < total, let device,
              let buffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let copy = buffer.deepCopy()
        else {
            lock.unlock()
            return
        }
        let index = captured
        captured += 1
        let total = total
        let isLast = captured == total
        let progress = progress
        let lensPosition = device.lensPosition
        lock.unlock()

        // 立即把镜头推向下一个位置，下一帧就会落在新的焦平面上。
        if !isLast, total > 1, (try? device.lockForConfiguration()) != nil {
            device.setFocusModeLocked(lensPosition: Float(index + 1) / Float(total - 1), completionHandler: nil)
            device.unlockForConfiguration()
        }
        progress?(index + 1)

        group.enter()
        encodeQueue.async {
            let image = CIImage(cvPixelBuffer: copy)
            let data = self.context.heifRepresentation(of: image, format: .RGBA8, colorSpace: ImageUtil.displayP3, options: [:])
            self.lock.lock()
            if let data { self.results[index] = Frame(data: data, lensPosition: lensPosition) }
            self.lock.unlock()
            self.group.leave()
        }
        if isLast {
            group.notify(queue: encodeQueue) { self.finish() }
        }
    }

    private func finish() {
        lock.lock()
        let frames = results.keys.sorted().compactMap { results[$0] }
            .sorted { $0.lensPosition < $1.lensPosition }
        let completion = completion
        self.completion = nil
        results = [:]
        lock.unlock()
        completion?(frames.isEmpty ? .failure(CameraError.captureFailed) : .success(frames))
    }
}

extension CVPixelBuffer {
    /// 拷贝像素数据到一个独立的缓冲区，让系统缓冲池尽快回收原缓冲区。
    func deepCopy() -> CVPixelBuffer? {
        let width = CVPixelBufferGetWidth(self)
        let height = CVPixelBufferGetHeight(self)
        let format = CVPixelBufferGetPixelFormatType(self)
        let attributes: [CFString: Any] = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary]
        var output: CVPixelBuffer?
        guard CVPixelBufferCreate(nil, width, height, format, attributes as CFDictionary, &output) == kCVReturnSuccess,
              let copy = output
        else { return nil }

        CVPixelBufferLockBaseAddress(self, .readOnly)
        CVPixelBufferLockBaseAddress(copy, [])
        defer {
            CVPixelBufferUnlockBaseAddress(copy, [])
            CVPixelBufferUnlockBaseAddress(self, .readOnly)
        }

        if CVPixelBufferIsPlanar(self) {
            for plane in 0 ..< CVPixelBufferGetPlaneCount(self) {
                guard let src = CVPixelBufferGetBaseAddressOfPlane(self, plane),
                      let dst = CVPixelBufferGetBaseAddressOfPlane(copy, plane) else { return nil }
                let rows = CVPixelBufferGetHeightOfPlane(self, plane)
                let srcStride = CVPixelBufferGetBytesPerRowOfPlane(self, plane)
                let dstStride = CVPixelBufferGetBytesPerRowOfPlane(copy, plane)
                let rowBytes = min(srcStride, dstStride)
                for row in 0 ..< rows {
                    memcpy(dst.advanced(by: row * dstStride), src.advanced(by: row * srcStride), rowBytes)
                }
            }
        } else {
            guard let src = CVPixelBufferGetBaseAddress(self), let dst = CVPixelBufferGetBaseAddress(copy) else { return nil }
            let srcStride = CVPixelBufferGetBytesPerRow(self)
            let dstStride = CVPixelBufferGetBytesPerRow(copy)
            for row in 0 ..< height {
                memcpy(dst.advanced(by: row * dstStride), src.advanced(by: row * srcStride), min(srcStride, dstStride))
            }
        }
        CVBufferPropagateAttachments(self, copy)
        return copy
    }
}
