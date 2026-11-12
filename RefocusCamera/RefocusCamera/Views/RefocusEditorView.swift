import AVFoundation
import SwiftUI

/// 先拍摄、后对焦：点按画面任意位置，切换到该处最清晰的焦平面；
/// 也可以拖动焦平面滑块，或用深度数据加上可调光圈的虚化。
struct RefocusEditorView: View {
    let manifest: StackManifest

    @Environment(StackLibrary.self) private var library
    @Environment(\.dismiss) private var dismiss
    @State private var model = RefocusEditorModel()
    @State private var reticle: (id: UUID, point: CGPoint)?
    /// 分析期间显示的模糊缩略图，让缩放转场落在连续的画面上
    @State private var placeholder: UIImage?
    @Namespace private var glassNamespace

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if model.phase != .ready, let placeholder {
                Image(uiImage: placeholder)
                    .resizable()
                    .scaledToFit()
                    .blur(radius: 18)
                    .opacity(0.7)
                    .ignoresSafeArea()
                    .transition(.opacity)
            }

            switch model.phase {
            case let .loading(progress):
                loadingView(progress)
                    .transition(.blurReplace)
            case let .failed(message):
                failedView(message)
            case .ready:
                canvas
                    .transition(.opacity)
            }

            VStack(spacing: 12) {
                topBar
                if let toast = model.toast {
                    GlassToast(message: toast)
                }
                Spacer()
                if model.phase == .ready {
                    controls
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
        .statusBarHidden()
        .animation(.glassSpring, value: model.phase)
        .animation(.glassSpring, value: model.toast)
        .task {
            placeholder = await library.thumbnail(for: manifest, maxPixelSize: 600)
        }
        .task { await model.load(manifest: manifest, library: library) }
        .onChange(of: model.params) { old, new in
            model.render(animated: old.frameIndex != new.frameIndex || old.mode != new.mode)
        }
        .task(id: reticle?.id) {
            guard reticle != nil else { return }
            try? await Task.sleep(for: .seconds(1.4))
            withAnimation(.easeOut(duration: 0.3)) { reticle = nil }
        }
    }

    // MARK: - 画面

    private var canvas: some View {
        GeometryReader { geo in
            let rect = AVMakeRect(aspectRatio: model.imageSize, insideRect: CGRect(origin: .zero, size: geo.size))
            ZStack(alignment: .topLeading) {
                if let image = model.displayImage {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                        .id(model.imageVersion)
                        .transition(RackFocusTransition())
                }
                if let reticle {
                    FocusReticle()
                        .id(reticle.id)
                        .position(reticle.point)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(.rect)
            .onTapGesture(coordinateSpace: .local) { location in
                guard rect.contains(location) else { return }
                let u = (location.x - rect.minX) / rect.width
                let v = (location.y - rect.minY) / rect.height
                withAnimation { reticle = (UUID(), location) }
                model.tap(u: u, v: v)
            }
        }
        .ignoresSafeArea()
    }

    // MARK: - 顶栏

    private var topBar: some View {
        GlassEffectContainer(spacing: 12) {
            HStack(spacing: 10) {
                GlassIconButton(systemName: "xmark") { dismiss() }

                Spacer()

                if model.phase == .ready {
                    GlassChip(
                        title: infoTitle,
                        systemImage: manifest.captureMode?.symbol ?? "square.3.layers.3d.down.right",
                        tint: manifest.captureMode?.isRAW == true ? .yellow : nil
                    )
                    .transition(.scale(scale: 0.7).combined(with: .opacity))
                    .glassEffectID("info", in: glassNamespace)
                }

                Spacer()

                if model.isRAW {
                    // RAW 堆栈：点按存储合成结果，长按可选择存储当前帧的原始 DNG
                    Menu {
                        Button("存储合成照片（HEIF）", systemImage: "photo") {
                            Task { await model.save() }
                        }
                        Button("存储当前焦平面的原始 DNG", systemImage: "camera.aperture") {
                            Task { await model.saveOriginalRAW() }
                        }
                    } label: {
                        saveLabel
                    } primaryAction: {
                        Task { await model.save() }
                    }
                    .menuStyle(.button)
                    .buttonStyle(.glassProminent)
                    .disabled(model.phase != .ready || model.isSaving)
                } else {
                    Button {
                        Task { await model.save() }
                    } label: {
                        saveLabel
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(model.phase != .ready || model.isSaving)
                }
            }
        }
    }

    private var saveLabel: some View {
        Group {
            if model.isSaving {
                ProgressView()
            } else {
                Label("存储", systemImage: "square.and.arrow.down")
            }
        }
        .font(.subheadline.weight(.semibold))
        .frame(minWidth: 72, minHeight: 34)
    }

    private var infoTitle: String {
        var parts: [String] = []
        if let mode = manifest.captureMode { parts.append(mode.title) }
        parts.append("\(model.frameCount) 帧")
        if model.hasDepth { parts.append("景深") }
        return parts.joined(separator: " · ")
    }

    // MARK: - 控制面板

    private var controls: some View {
        GlassEffectContainer(spacing: 16) {
            VStack(spacing: 16) {
                Picker("模式", selection: $model.params.mode) {
                    Text("单点对焦").tag(RefocusParameters.Mode.focus)
                    Text("全景深").tag(RefocusParameters.Mode.allInFocus)
                }
                .pickerStyle(.segmented)
                .disabled(model.frameCount < 2)

                if model.params.mode == .focus, model.frameCount > 1 {
                    focusPlaneSlider
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }

                if model.hasDepth {
                    apertureSlider
                } else if model.frameCount > 1 {
                    Text("“\(manifest.captureMode?.title ?? "此")”模式没有深度数据，因此不提供光圈虚化。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else if model.frameCount < 2 {
                    Text("该设备不支持手动镜头位置或深度数据，仅保存了一张照片。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Text(hint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .contentTransition(.opacity)
            }
            .padding(18)
            .glassEffect(.regular, in: .rect(cornerRadius: 30, style: .continuous))
            .glassEffectID("controls", in: glassNamespace)
        }
        .animation(.glassSpring, value: model.params.mode)
        .sensoryFeedback(.selection, trigger: model.params.frameIndex)
        .sensoryFeedback(.selection, trigger: model.params.mode)
    }

    private var focusPlaneSlider: some View {
        HStack(spacing: 12) {
            Image(systemName: "camera.macro")
                .accessibilityLabel("近")
            Slider(
                value: Binding(
                    get: { Double(model.params.frameIndex) },
                    set: { model.params.frameIndex = Int($0.rounded()) }
                ),
                in: 0 ... Double(max(model.frameCount - 1, 1)),
                step: 1
            ) {
                Text("焦平面")
            }
            Image(systemName: "mountain.2")
                .accessibilityLabel("远")
        }
        .font(.subheadline)
    }

    private var apertureSlider: some View {
        HStack(spacing: 12) {
            Image(systemName: "camera.aperture")
            Slider(value: $model.params.blur, in: 0 ... 1) {
                Text("光圈")
            }
            Text(String(format: "ƒ/%.1f", model.params.fNumber))
                .font(.subheadline.monospacedDigit().weight(.semibold))
                .frame(width: 56, alignment: .trailing)
                .contentTransition(.numericText())
        }
        .font(.subheadline)
    }

    private var hint: String {
        switch model.params.mode {
        case .focus:
            model.hasDepth ? "点按画面重新对焦 · 调小 ƒ 值增加背景虚化" : "点按画面任意位置重新对焦"
        case .allInFocus:
            model.hasDepth ? "已合成全景深 · 点按选择虚化的对焦主体" : "已把每个区域最清晰的部分合成在一起"
        }
    }

    // MARK: - 状态

    private func loadingView(_ progress: Double) -> some View {
        VStack(spacing: 16) {
            ZStack {
                Circle()
                    .stroke(.white.opacity(0.15), lineWidth: 5)
                Circle()
                    .trim(from: 0, to: max(0.02, progress))
                    .stroke(.yellow, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.glassSpring, value: progress)
                Text("\(Int(progress * 100))%")
                    .font(.subheadline.monospacedDigit().weight(.semibold))
                    .contentTransition(.numericText(value: progress))
                    .animation(.glassSnappy, value: Int(progress * 100))
            }
            .frame(width: 72, height: 72)
            Text(progress < 0.35 ? "正在对齐焦点堆栈…" : (progress < 0.95 ? "正在分析每个焦平面…" : "即将完成…"))
                .font(.subheadline.weight(.medium))
                .contentTransition(.opacity)
                .animation(.easeInOut, value: progress < 0.35)
        }
        .padding(28)
        .glassEffect(.regular, in: .rect(cornerRadius: 30, style: .continuous))
    }

    private func failedView(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
            Text(message)
                .multilineTextAlignment(.center)
            Button("关闭") { dismiss() }
                .buttonStyle(.glass)
        }
        .padding(24)
        .glassEffect(.regular, in: .rect(cornerRadius: 26, style: .continuous))
    }
}

/// 模仿“拉焦”：离开的画面逐渐失焦，新画面清晰地浮现
private struct RackFocusTransition: Transition {
    func body(content: Content, phase: TransitionPhase) -> some View {
        content
            .blur(radius: phase == .didDisappear ? 8 : 0)
            .opacity(phase.isIdentity ? 1 : 0)
    }
}
