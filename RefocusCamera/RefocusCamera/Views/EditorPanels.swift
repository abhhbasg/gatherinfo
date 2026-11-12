import SwiftUI

enum EditorTab: String, CaseIterable, Identifiable {
    case focus, filters, adjust, watermark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .focus: "对焦"
        case .filters: "滤镜"
        case .adjust: "调整"
        case .watermark: "水印"
        }
    }

    var symbol: String {
        switch self {
        case .focus: "scope"
        case .filters: "camera.filters"
        case .adjust: "slider.horizontal.3"
        case .watermark: "signature"
        }
    }
}

// MARK: - 标签栏

/// 液态玻璃标签栏：选中项的胶囊在标签之间流动，图标带弹跳
struct EditorTabBar: View {
    @Binding var selection: EditorTab
    @Namespace private var namespace

    var body: some View {
        HStack(spacing: 2) {
            ForEach(EditorTab.allCases) { tab in
                let selected = tab == selection
                Button {
                    withAnimation(.glassSpring) { selection = tab }
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 16, weight: .semibold))
                            .symbolEffect(.bounce, value: selected)
                        Text(tab.title)
                            .font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(selected ? Color.yellow : .white.opacity(0.7))
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background {
                        if selected {
                            Capsule()
                                .fill(.yellow.opacity(0.16))
                                .matchedGeometryEffect(id: "tab", in: namespace)
                        }
                    }
                    .contentShape(.capsule)
                }
                .buttonStyle(PressableStyle(pressedScale: 0.94))
            }
        }
        .padding(4)
        .glassEffect(.regular.interactive(), in: .capsule)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

// MARK: - 刻度拨盘

/// 类似专业修图软件的刻度拨盘：左右拖动调整数值，刻度随之滚动，每过一格有触感。
struct RulerDial: View {
    @Binding var value: Double
    let range: ClosedRange<Double>
    var defaultValue: Double = 0
    var tickCount = 40

    @State private var dragStart: Double?

    private var span: Double { range.upperBound - range.lowerBound }
    private let spacing: CGFloat = 9

    var body: some View {
        let progress = CGFloat((value - range.lowerBound) / span)
        let total = CGFloat(tickCount) * spacing
        RulerTicks(
            offset: progress * total,
            tickCount: tickCount,
            spacing: spacing,
            zeroTick: range.contains(defaultValue) ? Int(((defaultValue - range.lowerBound) / span * Double(tickCount)).rounded()) : nil
        )
        .frame(height: 36)
        .overlay {
            Capsule()
                .fill(.yellow)
                .frame(width: 3, height: 28)
                .shadow(color: .yellow.opacity(0.6), radius: 4)
        }
        .mask {
            LinearGradient(stops: [
                .init(color: .clear, location: 0),
                .init(color: .black, location: 0.18),
                .init(color: .black, location: 0.82),
                .init(color: .clear, location: 1),
            ], startPoint: .leading, endPoint: .trailing)
        }
        .contentShape(.rect)
        .gesture(
            DragGesture(minimumDistance: 1)
                .onChanged { gesture in
                    let start = dragStart ?? value
                    if dragStart == nil { dragStart = value }
                    let delta = -Double(gesture.translation.width / total) * span
                    value = min(max(start + delta, range.lowerBound), range.upperBound)
                }
                .onEnded { _ in dragStart = nil }
        )
        .onTapGesture(count: 2) {
            withAnimation(.glassSpring) { value = defaultValue }
        }
        .sensoryFeedback(.selection, trigger: Int(((value - range.lowerBound) / span * Double(tickCount)).rounded()))
        .accessibilityElement()
        .accessibilityValue(Text("\(Int((value * 100).rounded()))"))
        .accessibilityAdjustableAction { direction in
            let step = span / Double(tickCount)
            value = min(max(value + (direction == .increment ? step : -step), range.lowerBound), range.upperBound)
        }
    }
}

/// 刻度本身；offset 可动画，重置时刻度会平滑滚回
private struct RulerTicks: View, Animatable {
    var offset: CGFloat
    let tickCount: Int
    let spacing: CGFloat
    let zeroTick: Int?

    var animatableData: CGFloat {
        get { offset }
        set { offset = newValue }
    }

    var body: some View {
        Canvas { ctx, size in
            let mid = size.width / 2
            for i in 0 ... tickCount {
                let x = mid + CGFloat(i) * spacing - offset
                guard x > -2, x < size.width + 2 else { continue }
                let major = i % 5 == 0
                let isZero = i == zeroTick
                let h: CGFloat = isZero ? 22 : (major ? 16 : 9)
                var path = Path()
                path.move(to: CGPoint(x: x, y: (size.height - h) / 2))
                path.addLine(to: CGPoint(x: x, y: (size.height + h) / 2))
                ctx.stroke(path, with: .color(.white.opacity(isZero ? 0.95 : (major ? 0.7 : 0.35))), lineWidth: isZero ? 2 : 1.2)
            }
        }
    }
}

// MARK: - 滤镜面板

struct FilterPanel: View {
    @Bindable var model: RefocusEditorModel

    var body: some View {
        VStack(spacing: 14) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(LookPreset.allCases) { preset in
                            PresetCard(
                                preset: preset,
                                image: model.presetThumbnails[preset],
                                isSelected: model.params.preset == preset
                            ) {
                                withAnimation(.glassSpring) {
                                    model.selectPreset(preset)
                                    proxy.scrollTo(preset, anchor: .center)
                                }
                            }
                            .id(preset)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                }
                .scrollClipDisabled()
            }

            if model.params.preset != .original {
                HStack(spacing: 12) {
                    Text("强度")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    RulerDial(value: $model.params.presetIntensity, range: 0 ... 1, defaultValue: 1)
                    Text("\(Int((model.params.presetIntensity * 100).rounded()))")
                        .font(.subheadline.monospacedDigit().weight(.semibold))
                        .contentTransition(.numericText(value: model.params.presetIntensity))
                        .frame(width: 34, alignment: .trailing)
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.glassSpring, value: model.params.preset)
        .sensoryFeedback(.selection, trigger: model.params.preset)
    }
}

private struct PresetCard: View {
    let preset: LookPreset
    let image: UIImage?
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(.white.opacity(0.08))
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .transition(.opacity)
                    }
                }
                .frame(width: 62, height: 80)
                .clipShape(.rect(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(isSelected ? Color.yellow : .white.opacity(0.12), lineWidth: isSelected ? 2 : 1)
                }
                .scaleEffect(isSelected ? 1.06 : 1)
                .shadow(color: isSelected ? .yellow.opacity(0.35) : .clear, radius: 8)

                Text(preset.title)
                    .font(.caption2.weight(isSelected ? .bold : .medium))
                    .foregroundStyle(isSelected ? Color.yellow : .white.opacity(0.8))
                    .lineLimit(1)
            }
            .frame(width: 70)
        }
        .buttonStyle(PressableStyle(pressedScale: 0.94))
    }
}

// MARK: - 调整面板

struct AdjustPanel: View {
    @Bindable var model: RefocusEditorModel
    @State private var tool: AdjustmentTool = .exposure

    var body: some View {
        VStack(spacing: 12) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AdjustmentTool.allCases) { t in
                            ToolButton(tool: t, value: value(of: t), isSelected: t == tool) {
                                withAnimation(.glassSpring) {
                                    tool = t
                                    proxy.scrollTo(t, anchor: .center)
                                }
                            }
                            .id(t)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 4)
                }
                .scrollClipDisabled()
            }
            .sensoryFeedback(.selection, trigger: tool)

            HStack {
                Text(tool.title)
                    .font(.subheadline.weight(.semibold))
                    .contentTransition(.interpolate)
                Spacer()
                Text(formatted(value(of: tool)))
                    .font(.subheadline.monospacedDigit().weight(.semibold))
                    .foregroundStyle(value(of: tool) == tool.defaultValue ? .secondary : Color.yellow)
                    .contentTransition(.numericText(value: value(of: tool)))
                if !model.params.adjustments.isIdentity {
                    Button("全部重置") {
                        withAnimation(.glassSpring) { model.resetAdjustments() }
                    }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.glass)
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .animation(.glassSpring, value: tool)
            .animation(.glassSpring, value: model.params.adjustments.isIdentity)

            RulerDial(value: binding(for: tool), range: tool.range, defaultValue: tool.defaultValue, tickCount: tool.isBipolar ? 40 : 30)
                .id(tool)
                .transition(.opacity)
        }
    }

    private func value(of tool: AdjustmentTool) -> Double {
        model.params.adjustments[keyPath: tool.keyPath]
    }

    private func binding(for tool: AdjustmentTool) -> Binding<Double> {
        Binding(
            get: { model.params.adjustments[keyPath: tool.keyPath] },
            set: { model.params.adjustments[keyPath: tool.keyPath] = $0 }
        )
    }

    private func formatted(_ v: Double) -> String {
        let n = Int((v * 100).rounded())
        return tool.isBipolar && n > 0 ? "+\(n)" : "\(n)"
    }
}

/// 工具按钮：外圈的黄色弧线显示当前数值
private struct ToolButton: View {
    let tool: AdjustmentTool
    let value: Double
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        let changed = value != tool.defaultValue
        let fraction = tool.isBipolar ? abs(value) : value
        Button(action: action) {
            ZStack {
                Circle()
                    .stroke(.white.opacity(0.12), lineWidth: 2)
                Circle()
                    .trim(from: 0, to: changed ? max(0.02, fraction) : 0)
                    .stroke(Color.yellow, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .scaleEffect(x: value < 0 ? -1 : 1)
                Image(systemName: tool.symbol)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(isSelected ? Color.yellow : (changed ? .white : .white.opacity(0.75)))
            }
            .frame(width: 50, height: 50)
            .glassEffect(isSelected ? .regular.tint(.yellow.opacity(0.22)).interactive() : .regular.interactive(), in: .circle)
            .scaleEffect(isSelected ? 1.08 : 1)
        }
        .buttonStyle(PressableStyle(pressedScale: 0.9))
        .animation(.glassSpring, value: isSelected)
        .animation(.glassSnappy, value: value)
        .accessibilityLabel(tool.title)
    }
}

// MARK: - 水印面板

struct WatermarkPanel: View {
    @Bindable var model: RefocusEditorModel
    @State private var editingAuthor = false
    @State private var authorDraft = ""

    var body: some View {
        VStack(spacing: 14) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(WatermarkStyle.allCases) { style in
                            WatermarkCard(
                                style: style,
                                image: model.watermarkThumbnails[style],
                                isSelected: model.params.watermark.style == style
                            ) {
                                withAnimation(.glassSpring) {
                                    model.params.watermark.style = style
                                    proxy.scrollTo(style, anchor: .center)
                                }
                            }
                            .id(style)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                }
                .scrollClipDisabled()
            }

            if model.params.watermark.style != .none {
                options
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.glassSpring, value: model.params.watermark.style)
        .sensoryFeedback(.selection, trigger: model.params.watermark.style)
        .alert("署名", isPresented: $editingAuthor) {
            TextField("你的名字或工作室", text: $authorDraft)
            Button("取消", role: .cancel) {}
            Button("好") { model.params.watermark.author = authorDraft }
        } message: {
            Text("会显示在水印上，并在下次使用时记住。")
        }
    }

    private var options: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ToggleChip(title: "机型", systemImage: "iphone", isOn: $model.params.watermark.showModel)
                ToggleChip(title: "参数", systemImage: "camera.aperture", isOn: $model.params.watermark.showParameters)
                ToggleChip(title: "日期", systemImage: "calendar", isOn: $model.params.watermark.showDate)

                Button {
                    authorDraft = model.params.watermark.author
                    editingAuthor = true
                } label: {
                    OptionChip(title: model.params.watermark.trimmedAuthor ?? "署名", systemImage: "signature", highlighted: model.params.watermark.trimmedAuthor != nil)
                }
                .buttonStyle(PressableStyle())

                Menu {
                    Picker("字体", selection: $model.params.watermark.font) {
                        ForEach(WatermarkFont.allCases) { font in
                            Text(font.title).tag(font)
                        }
                    }
                } label: {
                    OptionChip(title: model.params.watermark.font.title, systemImage: "textformat", highlighted: false)
                }

                if model.params.watermark.style.hasFrame {
                    Button {
                        withAnimation(.glassSpring) {
                            model.params.watermark.tone = model.params.watermark.tone == .light ? .dark : .light
                        }
                    } label: {
                        OptionChip(
                            title: model.params.watermark.tone == .light ? "白色边框" : "黑色边框",
                            systemImage: model.params.watermark.tone == .light ? "square" : "square.fill",
                            highlighted: false
                        )
                    }
                    .buttonStyle(PressableStyle())
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.horizontal, 4)
        }
        .scrollClipDisabled()
    }
}

private struct WatermarkCard: View {
    let style: WatermarkStyle
    let image: UIImage?
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(.white.opacity(0.08))
                    if style == .none {
                        Image(systemName: "nosign")
                            .font(.title3)
                            .foregroundStyle(.white.opacity(0.6))
                    } else if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .padding(4)
                            .transition(.opacity)
                    }
                }
                .frame(width: 72, height: 88)
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(isSelected ? Color.yellow : .white.opacity(0.12), lineWidth: isSelected ? 2 : 1)
                }
                .scaleEffect(isSelected ? 1.05 : 1)
                .shadow(color: isSelected ? .yellow.opacity(0.3) : .clear, radius: 8)

                Text(style.title)
                    .font(.caption2.weight(isSelected ? .bold : .medium))
                    .foregroundStyle(isSelected ? Color.yellow : .white.opacity(0.8))
            }
            .frame(width: 78)
        }
        .buttonStyle(PressableStyle(pressedScale: 0.94))
    }
}

private struct ToggleChip: View {
    let title: String
    let systemImage: String
    @Binding var isOn: Bool

    var body: some View {
        Button {
            withAnimation(.glassSnappy) { isOn.toggle() }
        } label: {
            OptionChip(title: title, systemImage: isOn ? "checkmark" : systemImage, highlighted: isOn)
        }
        .buttonStyle(PressableStyle())
        .sensoryFeedback(.selection, trigger: isOn)
    }
}

private struct OptionChip: View {
    let title: String
    let systemImage: String
    let highlighted: Bool

    var body: some View {
        Label {
            Text(title).lineLimit(1)
        } icon: {
            Image(systemName: systemImage)
                .contentTransition(.symbolEffect(.replace))
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(highlighted ? Color.yellow : .white.opacity(0.85))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background {
            Capsule().fill(highlighted ? Color.yellow.opacity(0.16) : .white.opacity(0.08))
        }
        .overlay {
            Capsule().strokeBorder(highlighted ? Color.yellow.opacity(0.45) : .white.opacity(0.1), lineWidth: 1)
        }
    }
}
