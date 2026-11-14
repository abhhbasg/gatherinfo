import SwiftUI

struct GuideView: View {
    @Environment(\.openURL) private var openURL

    private let steps: [(String, String, String)] = [
        ("square.on.square", "新建快捷指令", "打开「快捷指令」App，点右上角 +，命名为「记住屏幕」。"),
        ("camera.viewfinder", "添加「截屏」", "搜索并添加动作「截屏」。截图不会存进相册。"),
        ("sparkles", "添加「记住屏幕」", "搜索「屏忆」，添加「记住屏幕」，截图参数选择上一步的「截屏」。"),
        ("hand.tap", "绑定轻点背面", "设置 › 辅助功能 › 触控 › 轻点背面 › 轻点两下，选择「记住屏幕」。"),
        ("checkmark.seal", "完成", "在任何 App 里双击手机背面，屏忆就会识别并记下当前屏幕。"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("一次设置，随时一键记住屏幕上的任何内容。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)

                ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                    HStack(alignment: .top, spacing: 14) {
                        ZStack {
                            Circle().fill(Color.indigo.gradient)
                            Text("\(index + 1)").font(.headline).foregroundStyle(.white)
                        }
                        .frame(width: 34, height: 34)
                        VStack(alignment: .leading, spacing: 4) {
                            Label(step.1, systemImage: step.0).font(.headline)
                            Text(step.2).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(16)
                    .glassCard()
                }

                VStack(alignment: .leading, spacing: 8) {
                    Label("更多触发方式", systemImage: "lightbulb").font(.headline)
                    Text("• 操作按钮（iPhone 15 Pro 及以后）：设置 › 操作按钮 › 快捷指令\n• 控制中心：添加「快捷指令」控件\n• Siri：“用屏忆记住屏幕”\n• 每日回顾：快捷指令 › 自动化 › 特定时间，运行「搜索记忆」")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(16)
                .glassCard(tint: .yellow)

                GlassEffectContainer(spacing: 10) {
                    HStack(spacing: 10) {
                        Button {
                            if let url = URL(string: "shortcuts://create-shortcut") { openURL(url) }
                        } label: {
                            Label("打开快捷指令", systemImage: "square.on.square").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glassProminent)

                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        } label: {
                            Label("打开设置", systemImage: "gear").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glass)
                    }
                    .controlSize(.large)
                }
                .padding(.top, 4)
            }
            .padding(16)
        }
        .background(AmbientBackground())
        .navigationTitle("设置方法")
        .navigationBarTitleDisplayMode(.inline)
    }
}
