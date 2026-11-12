import SwiftUI

/// 模式说明：对比每种模式的处理程度，点按即可切换。
struct ModeGuideView: View {
    let current: CaptureMode
    let unavailable: Set<CaptureMode>
    let depthAvailable: Bool
    let onSelect: (CaptureMode) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var appeared = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(Array(CaptureMode.allCases.enumerated()), id: \.element.id) { index, mode in
                        ModeCard(
                            mode: mode,
                            isCurrent: mode == current,
                            isUnavailable: unavailable.contains(mode),
                            depthAvailable: depthAvailable
                        ) {
                            onSelect(mode)
                            dismiss()
                        }
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 24)
                        .animation(.glassSpring.delay(Double(index) * 0.05), value: appeared)
                    }

                    legend
                        .opacity(appeared ? 1 : 0)
                        .animation(.easeOut(duration: 0.4).delay(0.3), value: appeared)
                }
                .padding(16)
            }
            .navigationTitle("拍摄模式")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成", systemImage: "checkmark") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.clear)
        .onAppear { appeared = true }
    }

    private var legend: some View {
        HStack(spacing: 16) {
            legendItem(color: .mint, text: "更接近原始")
            legendItem(color: .white, text: "常规")
            legendItem(color: .orange, text: "处理较重")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .padding(.top, 4)
    }

    private func legendItem(color: Color, text: String) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text)
        }
    }
}

private struct ModeCard: View {
    let mode: CaptureMode
    let isCurrent: Bool
    let isUnavailable: Bool
    let depthAvailable: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    Image(systemName: mode.symbol)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(isCurrent ? .yellow : .primary)
                        .frame(width: 40, height: 40)
                        .glassEffect(isCurrent ? .regular.tint(.yellow.opacity(0.3)) : .regular, in: .circle)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mode.longTitle)
                            .font(.headline)
                        Text(isUnavailable ? "此设备不支持" : mode.summary)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    if isCurrent {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.yellow)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                TraitBar(traits: mode.traits(depthAvailable: depthAvailable), usesGlass: false)
            }
            .padding(14)
            .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 24, style: .continuous))
            .opacity(isUnavailable ? 0.45 : 1)
        }
        .buttonStyle(PressableStyle(pressedScale: 0.97))
        .disabled(isUnavailable)
    }
}
