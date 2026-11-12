import SwiftUI

// MARK: - 通用动画

extension Animation {
    /// 界面统一使用的弹簧参数
    static let glassSpring = Animation.spring(response: 0.42, dampingFraction: 0.8)
    static let glassSnappy = Animation.spring(response: 0.3, dampingFraction: 0.86)
}

// MARK: - 按钮

/// 圆形液态玻璃图标按钮
struct GlassIconButton: View {
    let systemName: String
    var size: CGFloat = 48
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size * 0.38, weight: .semibold))
                .frame(width: size, height: size)
                .contentShape(.circle)
        }
        .buttonStyle(PressableStyle())
        .glassEffect(prominent ? .regular.tint(.yellow.opacity(0.55)).interactive() : .regular.interactive(), in: .circle)
    }
}

/// 按下时轻微缩小，松开时回弹
struct PressableStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.92

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .animation(.glassSnappy, value: configuration.isPressed)
    }
}

/// 胶囊形的液态玻璃标签
struct GlassChip: View {
    let title: String
    let systemImage: String
    var tint: Color?

    var body: some View {
        Label {
            Text(title)
                .contentTransition(.interpolate)
        } icon: {
            Image(systemName: systemImage)
                .contentTransition(.symbolEffect(.replace))
        }
        .font(.footnote.weight(.semibold))
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .glassEffect(tint.map { Glass.regular.tint($0.opacity(0.45)) } ?? Glass.regular, in: .capsule)
    }
}

// MARK: - 快门

/// 快门：外圈是可交互的液态玻璃，内圈按下时收缩；扫描焦平面时显示进度环。
struct ShutterButton: View {
    var mode: CaptureMode
    var progress: Double?
    var isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if let progress {
                    Circle()
                        .stroke(.white.opacity(0.18), lineWidth: 4)
                        .frame(width: 80, height: 80)
                    Circle()
                        .trim(from: 0, to: progress)
                        .stroke(
                            AngularGradient(colors: [.yellow.opacity(0.6), .yellow], center: .center),
                            style: StrokeStyle(lineWidth: 4, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .frame(width: 80, height: 80)
                        .transition(.scale(scale: 1.15).combined(with: .opacity))
                }
            }
            .frame(width: 88, height: 88)
            .contentShape(.circle)
        }
        .buttonStyle(ShutterStyle(mode: mode, isCapturing: progress != nil))
        .glassEffect(.regular.interactive(), in: .circle)
        .disabled(!isEnabled)
        .opacity(isEnabled || progress != nil ? 1 : 0.55)
        .animation(.glassSpring, value: progress)
        .animation(.glassSpring, value: isEnabled)
        .accessibilityLabel("拍摄焦点堆栈")
    }
}

private struct ShutterStyle: ButtonStyle {
    let mode: CaptureMode
    let isCapturing: Bool

    func makeBody(configuration: Configuration) -> some View {
        ZStack {
            Circle()
                .fill(mode.isRAW ? Color.yellow.gradient : Color.white.gradient)
                .frame(width: 66, height: 66)
                .scaleEffect(isCapturing ? 0.78 : (configuration.isPressed ? 0.86 : 1))
                .shadow(color: .black.opacity(0.25), radius: 6, y: 2)
            Image(systemName: mode.symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.black.opacity(0.55))
                .contentTransition(.symbolEffect(.replace))
                .scaleEffect(isCapturing ? 0.8 : 1)
            configuration.label
        }
        .animation(.glassSnappy, value: configuration.isPressed)
        .animation(.glassSpring, value: isCapturing)
        .animation(.glassSpring, value: mode)
    }
}

// MARK: - 模式选择

/// 液态玻璃分段选择器：选中的胶囊在模式之间流动。
struct ModeSelector: View {
    let selection: CaptureMode
    let unavailable: Set<CaptureMode>
    let isEnabled: Bool
    let onSelect: (CaptureMode) -> Void

    @Namespace private var namespace

    var body: some View {
        HStack(spacing: 2) {
            ForEach(CaptureMode.allCases) { mode in
                let selected = mode == selection
                let missing = unavailable.contains(mode)
                Button {
                    onSelect(mode)
                } label: {
                    Text(mode.title)
                        .font(.footnote.weight(selected ? .bold : .semibold))
                        .strikethrough(missing, color: .white.opacity(0.5))
                        .foregroundStyle(selected ? Color.yellow : .white.opacity(missing ? 0.3 : 0.78))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        .frame(height: 36)
                        .background {
                            if selected {
                                Capsule()
                                    .fill(.yellow.opacity(0.18))
                                    .overlay(Capsule().strokeBorder(.yellow.opacity(0.5), lineWidth: 1))
                                    .matchedGeometryEffect(id: "selection", in: namespace)
                            }
                        }
                        .contentShape(.capsule)
                }
                .buttonStyle(PressableStyle(pressedScale: 0.94))
                .disabled(!isEnabled || missing)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(4)
        .glassEffect(.regular.interactive(), in: .capsule)
        .animation(.glassSpring, value: selection)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// 当前模式的处理规格：数值变化时从下方推入。
struct TraitBar: View {
    let traits: [ModeTrait]
    /// 放在其他玻璃上时关闭，避免玻璃叠玻璃
    var usesGlass = true

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(traits.enumerated()), id: \.element.id) { index, trait in
                VStack(spacing: 3) {
                    Text(trait.label)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.secondary)
                    Text(trait.value)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(trait.level.color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .id(trait.value)
                        .transition(.push(from: .bottom).combined(with: .opacity))
                }
                .frame(maxWidth: .infinity)
                .clipped()
                if index < traits.count - 1 {
                    Rectangle()
                        .fill(.white.opacity(0.14))
                        .frame(width: 1, height: 22)
                }
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 6)
        .background {
            if !usesGlass { Capsule().fill(.white.opacity(0.06)) }
        }
        .modifier(CapsuleGlass(enabled: usesGlass))
        .animation(.glassSpring, value: traits)
    }
}

private struct CapsuleGlass: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.glassEffect(.regular, in: .capsule)
        } else {
            content
        }
    }
}

// MARK: - 对焦与提示

/// 对焦指示框：弹入、轻微呼吸，然后淡出
struct FocusReticle: View {
    @State private var appeared = false
    @State private var breathe = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.yellow, lineWidth: 1.5)
            Circle()
                .fill(.yellow)
                .frame(width: 5, height: 5)
        }
        .frame(width: 76, height: 76)
        .glassEffect(.clear, in: .rect(cornerRadius: 18, style: .continuous))
        .scaleEffect(appeared ? (breathe ? 0.96 : 1) : 1.4)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.62)) { appeared = true }
            withAnimation(.easeInOut(duration: 0.5).repeatCount(3, autoreverses: true).delay(0.3)) { breathe = true }
        }
        .allowsHitTesting(false)
    }
}

/// 液态玻璃提示条
struct GlassToast: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .glassEffect(.regular, in: .capsule)
            .transition(.move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.9)))
    }
}
