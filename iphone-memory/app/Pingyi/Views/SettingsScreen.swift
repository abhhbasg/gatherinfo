import SwiftUI
import SwiftData

struct SettingsScreen: View {
    @Environment(\.modelContext) private var context
    @Query private var memories: [Memory]

    @AppStorage("autoCalendar") private var autoCalendar = false
    @AppStorage("dailyReview") private var dailyReview = false
    @AppStorage("reviewHour") private var reviewHour = 21
    @AppStorage("reviewMinute") private var reviewMinute = 0
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        GuideView()
                    } label: {
                        SettingRow(symbol: "hand.tap.fill", color: .indigo,
                                   title: "设置「轻点背面」", subtitle: "双击手机背面，记住任意屏幕")
                    }
                    SettingRow(symbol: "waveform", color: .pink,
                               title: "Siri", subtitle: "“用屏忆记住屏幕”")
                } header: {
                    Text("快速捕获")
                }

                Section {
                    SettingRow(symbol: "apple.intelligence", color: .purple,
                               title: "端侧模型", subtitle: Extractor.modelStatusText)
                    Toggle(isOn: $autoCalendar) {
                        SettingRow(symbol: "calendar.badge.plus", color: .orange,
                                   title: "自动加入日历", subtitle: "识别到时间时直接建日程")
                    }
                    .onChange(of: autoCalendar) { _, on in
                        guard on else { return }
                        Task {
                            let granted = (try? await Scheduler.store.requestWriteOnlyAccessToEvents()) ?? false
                            if !granted { autoCalendar = false }
                        }
                    }
                } header: {
                    Text("智能整理")
                } footer: {
                    Text("文字识别和整理都在 iPhone 本地完成，不会上传任何内容。")
                }

                Section {
                    Toggle(isOn: $dailyReview) {
                        SettingRow(symbol: "bell.badge.fill", color: .red,
                                   title: "每日回顾", subtitle: "定时提醒你整理当天的记忆")
                    }
                    if dailyReview {
                        DatePicker("提醒时间", selection: reviewTime, displayedComponents: .hourAndMinute)
                    }
                } header: {
                    Text("定时任务")
                }
                .onChange(of: dailyReview) { _, _ in rescheduleReview() }
                .onChange(of: reviewHour) { _, _ in rescheduleReview() }
                .onChange(of: reviewMinute) { _, _ in rescheduleReview() }

                Section {
                    LabeledContent("记忆数量", value: "\(memories.count)")
                    LabeledContent("存储位置", value: "仅本机")
                    Button("清空所有记忆", role: .destructive) { confirmClear = true }
                } header: {
                    Text("数据")
                }

                Section {
                    LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmbientBackground())
            .navigationTitle("设置")
            .confirmationDialog("清空全部 \(memories.count) 条记忆？此操作无法撤销。",
                                isPresented: $confirmClear, titleVisibility: .visible) {
                Button("全部清空", role: .destructive) {
                    for m in memories { context.delete(m) }
                    try? context.save()
                    Spotlight.removeAll()
                }
            }
        }
    }

    private var reviewTime: Binding<Date> {
        Binding {
            Calendar.current.date(bySettingHour: reviewHour, minute: reviewMinute, second: 0, of: .now) ?? .now
        } set: { date in
            let c = Calendar.current.dateComponents([.hour, .minute], from: date)
            reviewHour = c.hour ?? 21
            reviewMinute = c.minute ?? 0
        }
    }

    private func rescheduleReview() {
        if dailyReview {
            Task { await Scheduler.scheduleDailyReview(hour: reviewHour, minute: reviewMinute) }
        } else {
            Scheduler.cancelDailyReview()
        }
    }
}

struct SettingRow: View {
    let symbol: String
    let color: Color
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(color.gradient, in: .rect(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
