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
    let mode: CaptureMode
    let metadata: CaptureMetadata?
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
    case modeUnsupported(CaptureMode)

    var errorDescription: String? {
        switch self {
        case .noCamera: "找不到可用的后置相机。"
        case .cannotConfigure: "无法配置相机。"
        case .busy: "相机正忙。"
        case .captureFailed: "拍摄失败，请重试。"
        case let .modeUnsupported(mode): "这台设备不支持“\(mode.title)”模式。"
        }
    }
}

/// 负责 AVCaptureSession 与“焦平面扫描”拍摄。
///
/// 一次快门会锁定曝光与白平衡，然后把镜头从最近对焦距离逐步推到最远，
/// 每个位置拍一张照片（或从视频流抓一帧）。
/// 之后在编辑器里点按任意位置，就能挑出该处最清晰的那一帧 —— 也就是“先拍摄，后对焦”。
@Observable
final class CameraModel {
    private(set) var isAuthorized: Bool?
    private(set) var isConfigured = false
    private(set) var supportsDepth = false
    private(set) var supportsSweep = false
    /// 当前生效的模式
    private(set) var mode: CaptureMode = .computational
    /// 用户刚刚选择、正在切换中的模式（界面立即响应它）
    private(set) var pendingMode: CaptureMode?
    private(set) var unavailableModes: Set<CaptureMode> = []
    private(set) var state: CaptureState = .idle
    var frameCount: Int = CaptureMode.computational.defaultFrameCount
    var errorMessage: String?

    var displayedMode: CaptureMode { pendingMode ?? mode }
    var isSwitchingMode: Bool { pendingMode != nil }

    @ObservationIgnored let session = AVCaptureSession()
    @ObservationIgnored private let sessionQueue = DispatchQueue(label: "refocus.camera.session")
    @ObservationIgnored private let videoQueue = DispatchQueue(label: "refocus.camera.video", qos: .userInitiated)
    @ObservationIgnored private let photoOutput = AVCapturePhotoOutput()
    @ObservationIgnored private let videoOutput = AVCaptureVideoDataOutput()
    @ObservationIgnored private let videoCollector = VideoSweepCollector()
    @ObservationIgnored private var device: AVCaptureDevice?
    @ObservationIgnored private var input: AVCaptureDeviceInput?
    @ObservationIgnored private var activeMode: CaptureMode = .computational
    @ObservationIgnored private var rawPixelFormat: OSType?
    @ObservationIgnored private var isConfiguredOnQueue = false
    @ObservationIgnored private weak var previewLayer: AVCaptureVideoPreviewLayer?
    @ObservationIgnored private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    @ObservationIgnored private var rotationObservation: NSKeyValueObservation?

    private struct Capabilities {
        var depth: Bool
        var sweep: Bool
    }

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
            let mode = mode
            let caps = try await onSessionQueue { () -> Capabilities in
                let caps = try self.configureIfNeeded(mode: mode)
                if !self.session.isRunning { self.session.startRunning() }
                return caps
            }
            await MainActor.run {
                self.apply(caps)
                self.isConfigured = true
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

    /// 切换拍摄模式。界面会立即显示新模式（pendingMode），失败时自动回退。
    func setMode(_ newMode: CaptureMode) async {
        guard newMode != displayedMode, isConfigured, state == .idle, pendingMode == nil else { return }
        let previous = mode
        await MainActor.run { self.pendingMode = newMode }
        do {
            let caps = try await onSessionQueue { try self.switchDevice(for: newMode) }
            await MainActor.run {
                self.mode = newMode
                if !newMode.frameCountOptions.contains(self.frameCount) { self.frameCount = newMode.defaultFrameCount }
                self.apply(caps)
                self.pendingMode = nil
            }
        } catch {
            let caps = try? await onSessionQueue { try self.switchDevice(for: previous) }
            await MainActor.run {
                if let caps { self.apply(caps) }
                self.unavailableModes.insert(newMode)
                self.pendingMode = nil
                self.errorMessage = error.localizedDescription
            }
        }
    }

    private func apply(_ caps: Capabilities) {
        supportsDepth = caps.depth
        supportsSweep = caps.sweep
        rotationCoordinator = nil
        rotationObservation = nil
        setUpRotationCoordinator()
    }

    // MARK: - 配置（均在 sessionQueue 上执行）

    private func configureIfNeeded(mode: CaptureMode) throws -> Capabilities {
        if isConfiguredOnQueue { return currentCapabilities() }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        guard session.canAddOutput(photoOutput) else { throw CameraError.cannotConfigure }
        session.addOutput(photoOutput)
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(videoCollector, queue: videoQueue)
        let caps = try useDevice(for: mode)
        isConfiguredOnQueue = true
        return caps
    }

    private func switchDevice(for mode: CaptureMode) throws -> Capabilities {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        return try useDevice(for: mode)
    }

    /// 为模式选择设备并设置输出。调用方负责 begin/commitConfiguration。
    private func useDevice(for mode: CaptureMode) throws -> Capabilities {
        guard let device = Self.pickDevice(for: mode) else { throw CameraError.noCamera }

        if device != self.device {
            if let input { session.removeInput(input) }
            let newInput = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(newInput) else {
                if let input, session.canAddInput(input) { session.addInput(input) }
                throw CameraError.cannotConfigure
            }
            session.addInput(newInput)
            input = newInput
            self.device = device
        }

        // 视频输出只在“高速”模式下挂到会话上
        let hasVideoOutput = session.outputs.contains(videoOutput)
        if mode.usesVideo, !hasVideoOutput {
            guard session.canAddOutput(videoOutput) else { throw CameraError.modeUnsupported(mode) }
            session.addOutput(videoOutput)
        } else if !mode.usesVideo, hasVideoOutput {
            session.removeOutput(videoOutput)
        }

        // LiDAR 等设备需要选择带深度格式的 activeFormat 才能输出深度。
        if mode.supportsDepth, device.activeFormat.supportedDepthDataFormats.isEmpty {
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

        // 每张最多约 1200 万像素，避免一个堆栈占用过多内存（Bayer RAW / ProRAW 也支持 1200 万像素）。
        let dims = device.activeFormat.supportedMaxPhotoDimensions
        if let chosen = dims.filter({ Self.area($0) <= 12_600_000 }).max(by: { Self.area($0) < Self.area($1) }) ?? dims.first {
            photoOutput.maxPhotoDimensions = chosen
        }
        photoOutput.maxPhotoQualityPrioritization = .quality
        // 零延迟快门会使用按下快门之前缓存的画面 —— 那时镜头还没移动到位，必须关闭。
        if photoOutput.isZeroShutterLagSupported { photoOutput.isZeroShutterLagEnabled = false }
        if photoOutput.isResponsiveCaptureSupported { photoOutput.isResponsiveCaptureEnabled = false }

        // RAW 格式：ProRAW 与 Bayer RAW 互斥，取决于 isAppleProRAWEnabled
        if photoOutput.isAppleProRAWSupported {
            photoOutput.isAppleProRAWEnabled = mode == .proRAW
        } else if mode == .proRAW {
            throw CameraError.modeUnsupported(mode)
        }
        switch mode {
        case .proRAW:
            guard let format = photoOutput.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isAppleProRAWPixelFormat($0) }) else {
                throw CameraError.modeUnsupported(mode)
            }
            rawPixelFormat = format
        case .bayerRAW:
            guard let format = photoOutput.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }) else {
                throw CameraError.modeUnsupported(mode)
            }
            rawPixelFormat = format
        default:
            rawPixelFormat = nil
        }
        photoOutput.isDepthDataDeliveryEnabled = mode.supportsDepth && photoOutput.isDepthDataDeliverySupported

        try device.lockForConfiguration()
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        if device.isGeometricDistortionCorrectionSupported {
            device.isGeometricDistortionCorrectionEnabled = mode != .minimal
        }
        if mode.usesVideo,
           let fastest = device.activeFormat.videoSupportedFrameRateRanges.max(by: { $0.maxFrameRate < $1.maxFrameRate }) {
            // 只限制最短帧时长，暗光下仍允许自动曝光降低帧率
            device.activeVideoMinFrameDuration = fastest.minFrameDuration
        } else {
            device.activeVideoMinFrameDuration = .invalid
        }
        device.unlockForConfiguration()

        activeMode = mode
        return currentCapabilities()
    }

    private func currentCapabilities() -> Capabilities {
        Capabilities(
            depth: photoOutput.isDepthDataDeliveryEnabled,
            sweep: device?.isLockingFocusWithCustomLensPositionSupported ?? false
        )
    }

    private static func pickDevice(for mode: CaptureMode) -> AVCaptureDevice? {
        if mode.prefersDepthDevice {
            // 优先使用能输出深度、同时允许手动设置镜头位置的设备。
            let preferred: [AVCaptureDevice.DeviceType] = [.builtInLiDARDepthCamera, .builtInDualWideCamera, .builtInDualCamera]
            for type in preferred {
                if let device = AVCaptureDevice.default(type, for: .video, position: .back),
                   device.isLockingFocusWithCustomLensPositionSupported {
                    return device
                }
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
        guard state == .idle, pendingMode == nil, let device, isConfigured else { throw CameraError.busy }
        let mode = mode
        let sweep = supportsSweep
        let depth = supportsDepth
        let total = sweep ? frameCount : 1
        let angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 90

        await MainActor.run { self.state = .sweeping(done: 0, total: total) }

        do {
            let metadata = try await onSessionQueue { try self.prepareForSweep(angle: angle) }
            let frames: [CapturedFrame]
            if mode.usesVideo {
                frames = try await captureVideoSweep(total: total, sweep: sweep, device: device)
            } else {
                frames = try await capturePhotoSweep(total: total, sweep: sweep, depth: depth, device: device)
            }
            try? await onSessionQueue { try self.restoreAutomaticModes() }
            await MainActor.run { self.state = .idle }
            return CaptureResult(frames: frames, referenceIndex: frames.count / 2, hasDepth: depth && !mode.usesVideo, mode: mode, metadata: metadata)
        } catch {
            try? await onSessionQueue { try self.restoreAutomaticModes() }
            await MainActor.run { self.state = .idle }
            throw error
        }
    }

    private func capturePhotoSweep(total: Int, sweep: Bool, depth: Bool, device: AVCaptureDevice) async throws -> [CapturedFrame] {
        let referenceIndex = total / 2
        var frames: [CapturedFrame] = []
        for index in 0 ..< total {
            let position: Float = total > 1 ? Float(index) / Float(total - 1) : device.lensPosition
            if sweep { await setLensPosition(position) }
            let (data, ext) = try await takePhoto(withDepth: depth && index == referenceIndex)
            frames.append(CapturedFrame(data: data, lensPosition: sweep ? position : device.lensPosition, fileExtension: ext))
            await MainActor.run { self.state = .sweeping(done: index + 1, total: total) }
        }
        return frames
    }

    private func captureVideoSweep(total: Int, sweep: Bool, device: AVCaptureDevice) async throws -> [CapturedFrame] {
        if sweep { await setLensPosition(0) }
        let collector = videoCollector
        let timeout = Task {
            try await Task.sleep(for: .seconds(Double(total) / 10 + 4))
            collector.cancel(with: CameraError.captureFailed)
        }
        defer { timeout.cancel() }
        let frames = try await collector.collect(count: sweep ? total : 1, device: device) { [weak self] done in
            Task { @MainActor in self?.state = .sweeping(done: done, total: total) }
        }
        return frames.map { CapturedFrame(data: $0.data, lensPosition: $0.lensPosition, fileExtension: "heic") }
    }

    /// 锁定曝光与白平衡，并返回锁定后的拍摄参数（用于水印）
    private func prepareForSweep(angle: CGFloat) throws -> CaptureMetadata? {
        for output in [photoOutput as AVCaptureOutput, videoOutput] {
            if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        }
        guard let device else { return nil }
        try device.lockForConfiguration()
        // 锁定曝光和白平衡，保证每一帧的亮度与色彩一致，便于后续合成。
        if device.isExposureModeSupported(.locked) { device.exposureMode = .locked }
        if device.isWhiteBalanceModeSupported(.locked) { device.whiteBalanceMode = .locked }
        device.unlockForConfiguration()

        // 由水平视场角换算 35mm 等效焦距（36mm 宽的画幅）
        let fov = Double(device.activeFormat.videoFieldOfView) * .pi / 180
        let focal35 = fov > 0 ? Int((18 / tan(fov / 2)).rounded()) : nil
        let seconds = CMTimeGetSeconds(device.exposureDuration)
        return CaptureMetadata(
            iso: device.iso,
            exposureSeconds: seconds.isFinite ? seconds : nil,
            aperture: device.lensAperture,
            focalLength35: focal35
        )
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

    /// 返回照片数据与文件扩展名
    private func takePhoto(withDepth: Bool) async throws -> (Data, String) {
        try await withCheckedThrowingContinuation { continuation in
            sessionQueue.async {
                let output = self.photoOutput
                let mode = self.activeMode
                let settings: AVCapturePhotoSettings
                let ext: String

                if let raw = self.rawPixelFormat {
                    // 只要 RAW，不要系统处理过的版本
                    settings = AVCapturePhotoSettings(rawPixelFormatType: raw)
                    settings.photoQualityPrioritization = mode == .bayerRAW ? .speed : .balanced
                    if let codec = settings.availableRawEmbeddedThumbnailPhotoCodecTypes.first {
                        settings.rawEmbeddedThumbnailPhotoFormat = [
                            AVVideoCodecKey: codec,
                            AVVideoWidthKey: 640,
                            AVVideoHeightKey: 640,
                        ]
                    }
                    ext = "dng"
                } else {
                    if output.availablePhotoCodecTypes.contains(.hevc) {
                        settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc])
                        ext = "heic"
                    } else {
                        settings = AVCapturePhotoSettings()
                        ext = "jpg"
                    }
                    if mode == .minimal {
                        // 速度优先 = 不做 Deep Fusion 之类的多帧合成；同时不融合多颗镜头的画面
                        settings.photoQualityPrioritization = .speed
                        // （带深度的那一帧保持默认，避免与深度输出的约束冲突）
                        if output.isVirtualDeviceFusionSupported, !withDepth { settings.isAutoVirtualDeviceFusionEnabled = false }
                    } else {
                        settings.photoQualityPrioritization = .balanced
                    }
                    if withDepth, output.isDepthDataDeliveryEnabled {
                        settings.isDepthDataDeliveryEnabled = true
                        settings.embedsDepthDataInPhoto = true
                        settings.isDepthDataFiltered = true
                    }
                }
                settings.maxPhotoDimensions = output.maxPhotoDimensions

                let delegate = PhotoCaptureDelegate { result in
                    continuation.resume(with: result.map { ($0, ext) })
                }
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
    private var completion: ((Result<Data, Error>) -> Void)?
    private var retainedSelf: PhotoCaptureDelegate?

    init(completion: @escaping (Result<Data, Error>) -> Void) {
        self.completion = completion
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
        guard let completion else { return }
        self.completion = nil
        completion(result)
    }
}
