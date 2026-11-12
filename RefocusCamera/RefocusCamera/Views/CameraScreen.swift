import SwiftUI

struct CameraScreen: View {
    @Environment(StackLibrary.self) private var library
    @State private var camera = CameraModel()
    @State private var openStack: StackManifest?
    @State private var pendingOpen: StackManifest?
    @State private var showLibrary = false
    @State private var showModeGuide = false
    @State private var reticle: (id: UUID, point: CGPoint)?
    @State private var latestThumbnail: UIImage?
    @State private var isSaving = false
    @State private var flash = false
    /// 切换模式后短暂显示该模式的说明
    @State private var announcedMode: CaptureMode?
    @Namespace private var zoomNamespace

    private static let placeholderID = UUID()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch camera.isAuthorized {
            case .some(true): cameraLayout.transition(.opacity)
            case .some(false): permissionDenied.transition(.scale(scale: 0.94).combined(with: .opacity))
            case .none: ProgressView()
            }
        }
        .animation(.glassSpring, value: camera.isAuthorized)
        .statusBarHidden()
        .task { await camera.start() }
        .task(id: library.stacks.first?.id) {
            if let first = library.stacks.first {
                let image = await library.thumbnail(for: first, maxPixelSize: 200)
                withAnimation(.glassSpring) { latestThumbnail = image }
            } else {
                latestThumbnail = nil
            }
        }
        .task(id: reticle?.id) {
            guard reticle != nil else { return }
            try? await Task.sleep(for: .seconds(1.6))
            withAnimation(.easeOut(duration: 0.3)) { reticle = nil }
        }
        .task(id: announcedMode) {
            guard announcedMode != nil else { return }
            try? await Task.sleep(for: .seconds(2.4))
            withAnimation(.glassSpring) { announcedMode = nil }
        }
        .onChange(of: openStack) { _, stack in
            if stack == nil { camera.resume() } else { camera.stop() }
        }
        .fullScreenCover(item: $openStack) { stack in
            RefocusEditorView(manifest: stack)
                .navigationTransition(.zoom(sourceID: stack.id, in: zoomNamespace))
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
        .sheet(isPresented: $showModeGuide) {
            ModeGuideView(
                current: camera.displayedMode,
                unavailable: camera.unavailableModes,
                depthAvailable: camera.supportsDepth || camera.displayedMode.supportsDepth
            ) { select($0) }
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
            viewfinder
                .padding(.top, 8)

            Spacer(minLength: 10)

            VStack(spacing: 14) {
                TraitBar(traits: camera.displayedMode.traits(depthAvailable: camera.supportsDepth))
                    .padding(.horizontal, 20)
                ModeSelector(
                    selection: camera.displayedMode,
                    unavailable: camera.unavailableModes,
                    isEnabled: camera.isConfigured && camera.state == .idle && !camera.isSwitchingMode
                ) { select($0) }
                .padding(.horizontal, 16)
            }
            .opacity(camera.isConfigured ? 1 : 0)
            .offset(y: camera.isConfigured ? 0 : 20)
            .animation(.glassSpring.delay(0.1), value: camera.isConfigured)

            Spacer(minLength: 10)
            bottomBar
            Spacer(minLength: 8)
        }
    }

    private var viewfinder: some View {
        CameraPreview(camera: camera) { point in
            withAnimation { reticle = (UUID(), point) }
        }
        .aspectRatio(3 / 4, contentMode: .fit)
        .overlay {
            // 切换镜头时模糊遮盖，避免画面跳变
            if camera.isSwitchingMode {
                Rectangle()
                    .fill(.ultraThinMaterial)
                    .overlay {
                        Image(systemName: camera.displayedMode.symbol)
                            .font(.system(size: 44, weight: .semibold))
                            .symbolEffect(.pulse, options: .repeating)
                            .foregroundStyle(.white.opacity(0.85))
                    }
                    .transition(.opacity)
            }
        }
        .overlay {
            if let reticle {
                GeometryReader { _ in
                    FocusReticle()
                        .id(reticle.id)
                        .position(reticle.point)
                }
            }
        }
        .overlay { Color.white.opacity(flash ? 0.55 : 0).allowsHitTesting(false) }
        .overlay(alignment: .top) { topBar.padding(.top, 12) }
        .overlay(alignment: .bottom) { statusArea.padding(.bottom, 14) }
        .clipShape(.rect(cornerRadius: 28, style: .continuous))
        .scaleEffect(camera.isSwitchingMode ? 0.985 : 1)
        .animation(.glassSpring, value: camera.isSwitchingMode)
        .simultaneousGesture(
            DragGesture(minimumDistance: 30).onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                step(value.translation.width < 0 ? 1 : -1)
            }
        )
    }

    private var topBar: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 8) {
                if camera.isConfigured {
                    Button { showModeGuide = true } label: {
                        GlassChip(
                            title: camera.displayedMode.title,
                            systemImage: camera.displayedMode.symbol,
                            tint: camera.displayedMode.isRAW ? .yellow : nil
                        )
                    }
                    .buttonStyle(PressableStyle())
                    .accessibilityHint("查看并对比所有拍摄模式")

                    if camera.supportsDepth, !camera.isSwitchingMode {
                        GlassChip(title: "景深", systemImage: "cube.transparent", tint: .yellow)
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                Spacer()
                if camera.isConfigured {
                    Menu {
                        Picker("每次拍摄的帧数", selection: $camera.frameCount) {
                            ForEach(camera.displayedMode.frameCountOptions, id: \.self) { count in
                                Text("\(count) 帧").tag(count)
                            }
                        }
                    } label: {
                        GlassChip(
                            title: camera.supportsSweep ? "\(camera.frameCount) 帧" : "单帧",
                            systemImage: "square.3.layers.3d.down.right"
                        )
                        .contentTransition(.numericText())
                    }
                    .disabled(!camera.supportsSweep || camera.state != .idle || camera.isSwitchingMode)
                }
            }
            .padding(.horizontal, 14)
        }
        .animation(.glassSpring, value: camera.displayedMode)
        .animation(.glassSpring, value: camera.supportsDepth)
        .animation(.glassSpring, value: camera.frameCount)
    }

    @ViewBuilder
    private var statusArea: some View {
        ZStack {
            if case let .sweeping(done, total) = camera.state {
                HStack(spacing: 10) {
                    ProgressView(value: Double(done), total: Double(max(total, 1)))
                        .tint(.yellow)
                        .frame(width: 96)
                    Text("扫描焦平面 \(done)/\(total)")
                        .monospacedDigit()
                        .contentTransition(.numericText())
                }
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .glassEffect(.regular, in: .capsule)
                .transition(.blurReplace)
                .id("sweep")
            } else if isSaving {
                GlassChip(title: "正在存储堆栈…", systemImage: "tray.and.arrow.down")
                    .transition(.blurReplace)
                    .id("saving")
            } else if let announcedMode {
                Text(announcedMode.summary)
                    .font(.caption.weight(.medium))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .glassEffect(.regular, in: .rect(cornerRadius: 18, style: .continuous))
                    .padding(.horizontal, 24)
                    .transition(.blurReplace)
                    .id(announcedMode)
            } else if camera.isConfigured {
                Text("请保持稳定 · 拍摄后点按任意位置重新对焦")
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect(.clear, in: .capsule)
                    .transition(.blurReplace)
                    .id("hint")
            }
        }
        .animation(.glassSpring, value: camera.state)
        .animation(.glassSpring, value: isSaving)
        .animation(.glassSpring, value: announcedMode)
    }

    private var bottomBar: some View {
        GlassEffectContainer(spacing: 24) {
            HStack {
                Button {
                    if let first = library.stacks.first { openStack = first }
                } label: {
                    ZStack {
                        if let latestThumbnail {
                            Image(uiImage: latestThumbnail)
                                .resizable()
                                .scaledToFill()
                                .transition(.scale(scale: 0.5).combined(with: .opacity))
                                .id(library.stacks.first?.id)
                        } else {
                            Image(systemName: "photo.stack")
                                .font(.title3)
                        }
                    }
                    .frame(width: 54, height: 54)
                    .clipShape(.circle)
                    .matchedTransitionSource(id: library.stacks.first?.id ?? Self.placeholderID, in: zoomNamespace)
                    .padding(4)
                    .contentShape(.circle)
                }
                .buttonStyle(PressableStyle())
                .glassEffect(.regular.interactive(), in: .circle)
                .disabled(library.stacks.isEmpty)
                .keyframeAnimator(initialValue: 1.0, trigger: library.stacks.first?.id) { content, scale in
                    content.scaleEffect(scale)
                } keyframes: { _ in
                    SpringKeyframe(1.18, duration: 0.18, spring: .snappy)
                    SpringKeyframe(1.0, duration: 0.4, spring: .bouncy)
                }

                Spacer()

                ShutterButton(
                    mode: camera.displayedMode,
                    progress: sweepProgress,
                    isEnabled: camera.isConfigured && camera.state == .idle && !isSaving && !camera.isSwitchingMode
                ) {
                    capture()
                }
                .sensoryFeedback(.impact(weight: .light, intensity: 0.6), trigger: sweepDone)

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

    private var sweepDone: Int {
        if case let .sweeping(done, _) = camera.state { return done }
        return 0
    }

    private var permissionDenied: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.aperture")
                .font(.system(size: 54))
                .symbolEffect(.breathe)
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

    // MARK: - 操作

    private func select(_ mode: CaptureMode) {
        guard mode != camera.displayedMode else { return }
        withAnimation(.glassSpring) { announcedMode = mode }
        Task { await camera.setMode(mode) }
    }

    /// 左右滑动取景框切换模式
    private func step(_ delta: Int) {
        let all = CaptureMode.allCases.filter { !camera.unavailableModes.contains($0) }
        guard camera.state == .idle, !camera.isSwitchingMode,
              let index = all.firstIndex(of: camera.displayedMode) else { return }
        let next = index + delta
        guard all.indices.contains(next) else { return }
        select(all[next])
    }

    private func capture() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        withAnimation(.glassSpring) { announcedMode = nil }
        Task {
            do {
                let result = try await camera.captureStack()
                withAnimation(.easeOut(duration: 0.06)) { flash = true }
                withAnimation(.easeIn(duration: 0.4).delay(0.06)) { flash = false }
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                isSaving = true
                defer { isSaving = false }
                let manifest = try await library.add(result)
                // 等缩略图更新完再展开，让编辑器从缩略图“放大”出来
                try? await Task.sleep(for: .milliseconds(350))
                openStack = manifest
            } catch {
                camera.errorMessage = error.localizedDescription
            }
        }
    }
}
