import SwiftUI

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
        .buttonStyle(.plain)
        .glassEffect(prominent ? .regular.tint(.yellow.opacity(0.55)).interactive() : .regular.interactive(), in: .circle)
    }
}

/// 胶囊形的液态玻璃标签
struct GlassChip: View {
    let title: String
    let systemImage: String
    var tint: Color?

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.footnote.weight(.semibold))
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .glassEffect(tint.map { Glass.regular.tint($0.opacity(0.45)) } ?? Glass.regular, in: .capsule)
    }
}

/// 快门：外圈是可交互的液态玻璃，扫描焦平面时显示进度环。
struct ShutterButton: View {
    var progress: Double?
    var isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(.white)
                    .frame(width: 66, height: 66)
                    .scaleEffect(progress == nil ? 1 : 0.82)
                if let progress {
                    Circle()
                        .trim(from: 0, to: progress)
                        .stroke(.yellow, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 80, height: 80)
                }
            }
            .frame(width: 88, height: 88)
            .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .disabled(!isEnabled)
        .animation(.spring(duration: 0.35), value: progress)
        .accessibilityLabel("拍摄焦点堆栈")
    }
}

/// 对焦指示框
struct FocusReticle: View {
    @State private var appeared = false

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
        .scaleEffect(appeared ? 1 : 1.35)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            withAnimation(.spring(duration: 0.35, bounce: 0.3)) { appeared = true }
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
            .transition(.move(edge: .top).combined(with: .opacity))
    }
}
