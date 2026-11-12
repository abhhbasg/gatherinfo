import AVFoundation
import Observation
import UIKit

struct CapturedFrame: Sendable {
    let data: Data
    let lensPosition: Float
    let fileExtension: String
}

/// 一次快门得到的焦点堆栈：同一构图、不同镜头位置的多张照片。
struct CaptureResult: Sendable {
    let frames: [CapturedFrame]
    let referenceIndex: Int
    let hasDepth: Bool
}

enum CaptureState: Equatable {
    case idle
    case sweeping(done: Int, total: Int)
}

enum CameraError: LocalizedError {
    case noCamera
    case cannotConfigure
    case busy
    case captureFailed

    var errorDescription: String? {
        switch self {
        case .noCamera: "找不到可用的后置相机。"
        case .cannotConfigure: "无法配置相机。"
        case .busy: "相机正忙。"
        case .captureFailed: "拍摄失败，请重试。"
        }
    }
}

/// 负责 AVCaptureSession 与“焦平面扫描”拍摄。
///
/// 一次快门会锁定曝光与白平衡，然后把镜头从最近对焦距离逐步推到最远，
/// 每个位置拍一张照片；中间那一帧额外携带深度（视差）数据。
/// 之后在编辑器里点按任意位置，就能挑出该处最清晰的那一帧 —— 也就是“先拍摄，后对焦”。
@Observable
final class CameraModel {
    private(set) var isAuthorized: Bool?
    private(set) var isConfigured = false
    private(set) var supportsDepth = false
    private(set) var supportsSweep = false
    private(set) var state: CaptureState = .idle
    var frameCount: Int = 9
    var errorMessage: String?

    @ObservationIgnored let session = AVCaptureSession()
    @ObservationIgnored private let sessionQueue = DispatchQueue(label: "refocus.camera.session")
    @ObservationIgnored private let photoOutput = AVCapturePhotoOutput()
    @ObservationIgnored private var device: AVCaptureDevice?
    @ObservationIgnored private weak var previewLayer: AVCaptureVideoPreviewLayer?
    @ObservationIgnored private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    @ObservationIgnored private var rotationObservation: NSKeyValueObservation?

    static let frameCountOptions = [5, 9, 15]

    // MARK: - 生命周期

    func start() async {
        let granted: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: granted = true
        case .notDetermined: granted = await AVCaptureDevice.requestAccess(for: .video)
        default: granted = false
        }
        await MainActor.run { self.isAuthorized = granted }
        guard granted else { return }

        do {
            let caps = try await onSessionQueue { () -> (Bool, Bool) in
                let caps = try self.configureIfNeeded()
                if !self.session.isRunning { self.session.startRunning() }
                return caps
            }
            await MainActor.run {
                self.supportsDepth = caps.0
                self.supportsSweep = caps.1
                self.isConfigured = true
                self.setUpRotationCoordinator()
            }
        } catch {
            await MainActor.run { self.errorMessage = error.localizedDescription }
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func resume() {
        sessionQueue.async {
            if self.isConfiguredOnQueue, !self.session.isRunning { self.session.startRunning() }
        }
    }

    // MARK: - 配置

    @ObservationIgnored private var isConfiguredOnQueue = false

    /// 返回 (是否支持深度, 是否支持手动镜头位置)
    private func configureIfNeeded() throws -> (Bool, Bool) {
        if isConfiguredOnQueue, let device {
            return (photoOutput.isDepthDataDeliveryEnabled, device.isLockingFocusWithCustomLensPositionSupported)
        }
        guard let device = Self.pickDevice() else { throw CameraError.noCamera }

        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo

        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw CameraError.cannotConfigure }
        session.addInput(input)
        guard session.canAddOutput(photoOutput) else { throw CameraError.cannotConfigure }
        session.addOutput(photoOutput)

        // LiDAR 等设备需要选择带深度格式的 activeFormat 才能输出深度。
        if device.activeFormat.supportedDepthDataFormats.isEmpty {
            let candidates = device.formats.filter {
                !$0.supportedDepthDataFormats.isEmpty &&
                    CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            }
            if let best = candidates.max(by: { Self.area($0.supportedMaxPhotoDimensions.last) < Self.area($1.supportedMaxPhotoDimensions.last) }) {
                try device.lockForConfiguration()
                device.activeFormat = best
                device.unlockForConfiguration()
            }
        }

        // 每张最多约 1200 万像素，避免一个堆栈占用过多内存。
        let dims = device.activeFormat.supportedMaxPhotoDimensions
        if let chosen = dims.filter({ Self.area($0) <= 12_600_000 }).max(by: { Self.area($0) < Self.area($1) }) ?? dims.first {
            photoOutput.maxPhotoDimensions = chosen
        }
        photoOutput.maxPhotoQualityPrioritization = .quality
        // 零延迟快门会使用按下快门之前缓存的画面 —— 那时镜头还没移动到位，必须关闭。
        if photoOutput.isZeroShutterLagSupported { photoOutput.isZeroShutterLagEnabled = false }
        if photoOutput.isResponsiveCaptureSupported { photoOutput.isResponsiveCaptureEnabled = false }
        photoOutput.isDepthDataDeliveryEnabled = photoOutput.isDepthDataDeliverySupported

        try device.lockForConfiguration()
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        device.unlockForConfiguration()

        self.device = device
        isConfiguredOnQueue = true
        return (photoOutput.isDepthDataDeliveryEnabled, device.isLockingFocusWithCustomLensPositionSupported)
    }

    private static func pickDevice() -> AVCaptureDevice? {
        // 优先使用能输出深度、同时允许手动设置镜头位置的设备。
        let preferred: [AVCaptureDevice.DeviceType] = [.builtInLiDARDepthCamera, .builtInDualWideCamera, .builtInDualCamera]
        for type in preferred {
            if let device = AVCaptureDevice.default(type, for: .video, position: .back),
               device.isLockingFocusWithCustomLensPositionSupported {
                return device
            }
        }
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    private static func area(_ d: CMVideoDimensions?) -> Int {
        guard let d else { return 0 }
        return Int(d.width) * Int(d.height)
    }

    // MARK: - 预览与旋转

    func attach(previewLayer layer: AVCaptureVideoPreviewLayer) {
        previewLayer = layer
        if isConfigured { setUpRotationCoordinator() }
    }

    private func setUpRotationCoordinator() {
        guard rotationCoordinator == nil, let device, let layer = previewLayer else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: layer)
        layer.connection?.videoRotationAngle = coordinator.videoRotationAngleForHorizonLevelPreview
        rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { [weak layer] coordinator, _ in
            DispatchQueue.main.async {
                layer?.connection?.videoRotationAngle = coordinator.videoRotationAngleForHorizonLevelPreview
            }
        }
        rotationCoordinator = coordinator
    }

    /// 取景时点按对焦（只影响预览；拍摄时会扫描整个对焦范围）。
    func focusPreview(at devicePoint: CGPoint) {
        sessionQueue.async {
            guard let device = self.device, (try? device.lockForConfiguration()) != nil else { return }
            if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusPointOfInterest = devicePoint
                device.focusMode = .continuousAutoFocus
            }
            if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposurePointOfInterest = devicePoint
                device.exposureMode = .continuousAutoExposure
            }
            device.unlockForConfiguration()
        }
    }

    // MARK: - 焦平面扫描拍摄

    func captureStack() async throws -> CaptureResult {
        guard state == .idle, let device, isConfigured else { throw CameraError.busy }
        let sweep = supportsSweep
        let depth = supportsDepth
        let total = sweep ? frameCount : 1
        let referenceIndex = total / 2
        let angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 90

        await MainActor.run { self.state = .sweeping(done: 0, total: total) }

        do {
            try await onSessionQueue { try self.prepareForSweep(angle: angle, lockFocus: sweep) }
            var frames: [CapturedFrame] = []
            for index in 0 ..< total {
                let position: Float = total > 1 ? Float(index) / Float(total - 1) : device.lensPosition
                if sweep { await setLensPosition(position) }
                let data = try await takePhoto(withDepth: depth && index == referenceIndex)
                frames.append(CapturedFrame(data: data, lensPosition: sweep ? position : device.lensPosition, fileExtension: currentFileExtension))
                await MainActor.run { self.state = .sweeping(done: index + 1, total: total) }
            }
            try? await onSessionQueue { try self.restoreAutomaticModes() }
            await MainActor.run { self.state = .idle }
            return CaptureResult(frames: frames, referenceIndex: referenceIndex, hasDepth: depth)
        } catch {
            try? await onSessionQueue { try self.restoreAutomaticModes() }
            await MainActor.run { self.state = .idle }
            throw error
        }
    }

    private var currentFileExtension: String {
        photoOutput.availablePhotoCodecTypes.contains(.hevc) ? "heic" : "jpg"
    }

    private func prepareForSweep(angle: CGFloat, lockFocus: Bool) throws {
        if let connection = photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
        guard let device else { return }
        try device.lockForConfiguration()
        // 锁定曝光和白平衡，保证每一帧的亮度与色彩一致，便于后续合成。
        if device.isExposureModeSupported(.locked) { device.exposureMode = .locked }
        if device.isWhiteBalanceModeSupported(.locked) { device.whiteBalanceMode = .locked }
        device.unlockForConfiguration()
    }

    private func restoreAutomaticModes() throws {
        guard let device else { return }
        try device.lockForConfiguration()
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
        device.unlockForConfiguration()
    }

    private func setLensPosition(_ position: Float) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            sessionQueue.async {
                guard let device = self.device, (try? device.lockForConfiguration()) != nil else {
                    continuation.resume()
                    return
                }
                device.setFocusModeLocked(lensPosition: position) { _ in continuation.resume() }
                device.unlockForConfiguration()
            }
        }
        // 给传感器留出一帧的时间，确保拍到的是镜头到位后的画面。
        try? await Task.sleep(for: .milliseconds(40))
    }

    private func takePhoto(withDepth: Bool) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            sessionQueue.async {
                let output = self.photoOutput
                let settings: AVCapturePhotoSettings
                if output.availablePhotoCodecTypes.contains(.hevc) {
                    settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc])
                } else {
                    settings = AVCapturePhotoSettings()
                }
                settings.maxPhotoDimensions = output.maxPhotoDimensions
                settings.photoQualityPrioritization = .balanced
                if withDepth, output.isDepthDataDeliveryEnabled {
                    settings.isDepthDataDeliveryEnabled = true
                    settings.embedsDepthDataInPhoto = true
                    settings.isDepthDataFiltered = true
                }
                let delegate = PhotoCaptureDelegate(continuation: continuation)
                output.capturePhoto(with: settings, delegate: delegate)
            }
        }
    }

    // MARK: - 工具

    private func onSessionQueue<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            sessionQueue.async {
                continuation.resume(with: Result { try work() })
            }
        }
    }
}

/// AVCapturePhotoOutput 只弱引用 delegate，所以由 delegate 自己持有自己直到拍摄结束。
private final class PhotoCaptureDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    private var continuation: CheckedContinuation<Data, Error>?
    private var retainedSelf: PhotoCaptureDelegate?

    init(continuation: CheckedContinuation<Data, Error>) {
        self.continuation = continuation
        super.init()
        retainedSelf = self
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            finish(.failure(error))
        } else if let data = photo.fileDataRepresentation() {
            finish(.success(data))
        } else {
            finish(.failure(CameraError.captureFailed))
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        finish(.failure(error ?? CameraError.captureFailed)) // 已经完成时为空操作
        retainedSelf = nil
    }

    private func finish(_ result: Result<Data, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(with: result)
    }
}
