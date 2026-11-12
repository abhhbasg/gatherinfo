import SwiftUI

struct CameraScreen: View {
    @Environment(StackLibrary.self) private var library
    @State private var camera = CameraModel()
    @State private var openStack: StackManifest?
    @State private var pendingOpen: StackManifest?
    @State private var showLibrary = false
    @State private var reticle: (id: UUID, point: CGPoint)?
    @State private var latestThumbnail: UIImage?
    @State private var isSaving = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch camera.isAuthorized {
            case .some(true): cameraLayout
            case .some(false): permissionDenied
            case .none: ProgressView()
            }
        }
        .statusBarHidden()
        .task { await camera.start() }
        .task(id: library.stacks.first?.id) {
            if let first = library.stacks.first {
                latestThumbnail = await library.thumbnail(for: first, maxPixelSize: 200)
            } else {
                latestThumbnail = nil
            }
        }
        .onChange(of: openStack) { _, stack in
            if stack == nil { camera.resume() } else { camera.stop() }
        }
        .task(id: reticle?.id) {
            guard reticle != nil else { return }
            try? await Task.sleep(for: .seconds(1.6))
            withAnimation(.easeOut(duration: 0.3)) { reticle = nil }
        }
        .fullScreenCover(item: $openStack) { stack in
            RefocusEditorView(manifest: stack)
        }
        .sheet(isPresented: $showLibrary, onDismiss: {
            if let pendingOpen {
                openStack = pendingOpen
                self.pendingOpen = nil
            }
        }) {
            LibraryView { stack in
                pendingOpen = stack
                showLibrary = false
            }
        }
        .alert("出错了", isPresented: Binding(
            get: { camera.errorMessage != nil },
            set: { if !$0 { camera.errorMessage = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(camera.errorMessage ?? "")
        }
    }

    // MARK: - 取景布局

    private var cameraLayout: some View {
        VStack(spacing: 0) {
            CameraPreview(camera: camera) { point in
                withAnimation { reticle = (UUID(), point) }
            }
            .aspectRatio(3 / 4, contentMode: .fit)
            .overlay {
                if let reticle {
                    GeometryReader { _ in
                        FocusReticle()
                            .id(reticle.id)
                            .position(reticle.point)
                    }
                }
            }
            .overlay(alignment: .top) { topBar.padding(.top, 12) }
            .overlay(alignment: .bottom) { statusChip.padding(.bottom, 14) }
            .clipShape(.rect(cornerRadius: 24, style: .continuous))
            .padding(.top, 8)

            Spacer(minLength: 0)
            bottomBar
            Spacer(minLength: 0)
        }
    }

    private var topBar: some View {
        GlassEffectContainer(spacing: 12) {
            HStack(spacing: 10) {
                if camera.isConfigured {
                    // RAW 开关：关闭全部计算摄影，拍摄 Bayer RAW（DNG）
                    Button {
                        Task { await camera.setFormat(camera.format == .raw ? .processed : .raw) }
                    } label: {
                        GlassChip(
                            title: camera.format == .raw ? "RAW" : "计算摄影",
                            systemImage: camera.format == .raw ? "camera.aperture" : "wand.and.sparkles",
                            tint: camera.format == .raw ? .yellow : nil
                        )
                        .overlay {
                            if camera.isSwitchingFormat { ProgressView().controlSize(.small) }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(camera.state != .idle || camera.isSwitchingFormat)
                    .accessibilityHint("切换是否使用系统的计算摄影处理")

                    if camera.supportsDepth {
                        GlassChip(title: "景深", systemImage: "cube.transparent", tint: .yellow)
                    }
                }
                Spacer()
                Menu {
                    Picker("每次拍摄的帧数", selection: $camera.frameCount) {
                        ForEach(CameraModel.frameCountOptions, id: \.self) { count in
                            Text("\(count) 帧").tag(count)
                        }
                    }
                } label: {
                    GlassChip(
                        title: camera.supportsSweep ? "\(camera.frameCount) 帧" : "单帧",
                        systemImage: "square.3.layers.3d.down.right"
                    )
                }
                .disabled(!camera.supportsSweep || camera.state != .idle)
            }
            .padding(.horizontal, 14)
        }
    }

    @ViewBuilder
    private var statusChip: some View {
        Group {
            if case let .sweeping(done, total) = camera.state {
                HStack(spacing: 10) {
                    ProgressView(value: Double(done), total: Double(total))
                        .frame(width: 90)
                    Text("扫描焦平面 \(done)/\(total)")
                        .monospacedDigit()
                }
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .glassEffect(.regular, in: .capsule)
            } else if isSaving {
                GlassChip(title: "正在存储堆栈…", systemImage: "tray.and.arrow.down")
            } else if camera.isConfigured {
                Text(camera.format == .raw
                    ? "RAW · 无计算摄影 · 请保持稳定"
                    : "请保持稳定 · 拍摄后点按任意位置重新对焦")
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect(.clear, in: .capsule)
            }
        }
        .transition(.opacity.combined(with: .scale(scale: 0.9)))
        .animation(.spring(duration: 0.3), value: camera.state)
    }

    private var bottomBar: some View {
        GlassEffectContainer(spacing: 24) {
            HStack {
                Button {
                    if let first = library.stacks.first { openStack = first }
                } label: {
                    Group {
                        if let latestThumbnail {
                            Image(uiImage: latestThumbnail)
                                .resizable()
                                .scaledToFill()
                        } else {
                            Image(systemName: "photo")
                                .font(.title3)
                        }
                    }
                    .frame(width: 54, height: 54)
                    .clipShape(.circle)
                    .padding(4)
                    .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .circle)
                .disabled(library.stacks.isEmpty)

                Spacer()

                ShutterButton(progress: sweepProgress, isEnabled: camera.isConfigured && camera.state == .idle && !isSaving && !camera.isSwitchingFormat) {
                    capture()
                }

                Spacer()

                GlassIconButton(systemName: "square.grid.2x2", size: 62) {
                    showLibrary = true
                }
            }
            .padding(.horizontal, 28)
        }
    }

    private var sweepProgress: Double? {
        if case let .sweeping(done, total) = camera.state { return Double(done) / Double(max(total, 1)) }
        return nil
    }

    private var permissionDenied: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.aperture")
                .font(.system(size: 54))
            Text("需要相机权限")
                .font(.title2.bold())
            Text("请在“设置”中允许“后对焦”使用相机。")
                .foregroundStyle(.secondary)
            Button("打开设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.glassProminent)
        }
        .padding(32)
        .glassEffect(.regular, in: .rect(cornerRadius: 32, style: .continuous))
        .padding()
    }

    // MARK: - 拍摄

    private func capture() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        Task {
            do {
                let result = try await camera.captureStack()
                isSaving = true
                defer { isSaving = false }
                openStack = try await library.add(result)
            } catch {
                camera.errorMessage = error.localizedDescription
            }
        }
    }
}
