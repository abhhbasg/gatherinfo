import SwiftUI
import SwiftData
import PhotosUI

struct TimelineScreen: View {
    @Environment(AppModel.self) private var model
    @Query(sort: \Memory.createdAt, order: .reverse) private var memories: [Memory]

    @State private var showPicker = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var showComposer = false
    @State private var showGuide = false

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.timelinePath) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    StatsHeader(memories: memories)
                        .padding(.bottom, 6)

                    if memories.isEmpty {
                        EmptyTimeline { showGuide = true }
                    }

                    let pinned = memories.filter(\.isPinned)
                    if !pinned.isEmpty {
                        SectionTitle(text: "置顶", symbol: "pin.fill")
                        ForEach(pinned) { MemoryLink(memory: $0) }
                    }

                    ForEach(sections, id: \.0) { day, items in
                        SectionTitle(text: DayLabel.text(day))
                        ForEach(items) { MemoryLink(memory: $0) }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(AmbientBackground())
            .navigationTitle("屏忆")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("从相册导入截图", systemImage: "photo.on.rectangle") { showPicker = true }
                        Button("粘贴剪贴板", systemImage: "doc.on.clipboard") {
                            Task { await model.pasteClipboard() }
                        }
                        Button("写一条", systemImage: "square.and.pencil") { showComposer = true }
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .navigationDestination(for: Memory.self) { MemoryDetailView(memory: $0) }
            .photosPicker(isPresented: $showPicker, selection: $pickerItem, matching: .images)
            .onChange(of: pickerItem) { _, item in
                guard let item else { return }
                pickerItem = nil
                Task { await model.importPhoto(item) }
            }
            .sheet(isPresented: $showComposer) { ComposerSheet() }
            .sheet(isPresented: $showGuide) { NavigationStack { GuideView() } }
        }
    }

    private var sections: [(Date, [Memory])] {
        let cal = Calendar.current
        let groups = Dictionary(grouping: memories.filter { !$0.isPinned }) { cal.startOfDay(for: $0.createdAt) }
        return groups.keys.sorted(by: >).map { ($0, groups[$0] ?? []) }
    }
}

struct SectionTitle: View {
    let text: String
    var symbol: String?

    var body: some View {
        HStack(spacing: 6) {
            if let symbol { Image(systemName: symbol).font(.caption) }
            Text(text)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.secondary)
        .padding(.top, 10)
        .padding(.leading, 4)
    }
}

struct MemoryLink: View {
    let memory: Memory

    var body: some View {
        NavigationLink(value: memory) {
            MemoryCard(memory: memory)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(memory.isPinned ? "取消置顶" : "置顶", systemImage: memory.isPinned ? "pin.slash" : "pin") {
                memory.isPinned.toggle()
            }
            Button("拷贝文字", systemImage: "doc.on.doc") { UIPasteboard.general.string = memory.text }
            Button("删除", systemImage: "trash", role: .destructive) {
                withAnimation { MemoryProcessor.delete(memory) }
            }
        }
    }
}

struct MemoryCard: View {
    let memory: Memory

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Thumbnail(memory: memory)
            VStack(alignment: .leading, spacing: 6) {
                Text(memory.summary)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(snippet)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                HStack(spacing: 6) {
                    if let date = memory.eventDate {
                        EventBadge(date: date, done: memory.addedToCalendar || memory.addedToReminders)
                    }
                    ForEach(memory.tags.prefix(memory.hasEvent ? 1 : 2), id: \.self) { TagChip(tag: $0) }
                    Spacer(minLength: 0)
                    Text(memory.createdAt.formatted(.dateTime.hour().minute()))
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                .padding(.top, 2)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(.rect(cornerRadius: 24))
        .glassCard()
    }

    private var snippet: String {
        memory.text.replacingOccurrences(of: "\n", with: " ")
    }
}

struct StatsHeader: View {
    let memories: [Memory]

    var body: some View {
        GlassEffectContainer(spacing: 12) {
            HStack(spacing: 12) {
                StatTile(value: todayCount, label: "今天记住", symbol: "sparkles", color: .indigo)
                StatTile(value: upcomingCount, label: "待办日程", symbol: "calendar.badge.clock", color: .orange)
                StatTile(value: memories.count, label: "全部记忆", symbol: "square.stack.3d.up", color: .teal)
            }
        }
    }

    private var todayCount: Int {
        memories.filter { Calendar.current.isDateInToday($0.createdAt) }.count
    }

    private var upcomingCount: Int {
        memories.filter { ($0.eventDate ?? .distantPast) > .now }.count
    }
}

struct StatTile: View {
    let value: Int
    let label: String
    let symbol: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: symbol)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(color)
            Text("\(value)")
                .font(.title.weight(.bold))
                .contentTransition(.numericText())
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassEffect(.regular.tint(color.opacity(0.10)), in: .rect(cornerRadius: 22))
    }
}

struct EmptyTimeline: View {
    let onGuide: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "camera.viewfinder")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(.indigo)
                .padding(18)
                .glassEffect(.regular, in: .circle)
            Text("还没有记忆").font(.title3.weight(.semibold))
            Text("设置好「轻点背面」后，在任何 App 里双击手机背面，就能记住当前屏幕。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("查看设置方法", action: onGuide)
                .buttonStyle(.glassProminent)
                .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .glassCard(cornerRadius: 28)
        .padding(.top, 8)
    }
}

struct ComposerSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .focused($focused)
                .scrollContentBackground(.hidden)
                .padding(12)
                .glassCard()
                .padding(16)
                .background(AmbientBackground())
                .navigationTitle("写一条")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消", systemImage: "xmark") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("保存", systemImage: "checkmark") {
                            let t = text
                            dismiss()
                            Task { await model.addText(t) }
                        }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }
}
