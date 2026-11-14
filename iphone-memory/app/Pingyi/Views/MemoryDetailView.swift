import SwiftUI

struct MemoryDetailView: View {
    @Bindable var memory: Memory
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model

    @State private var showImage = false
    @State private var errorText: String?
    @State private var confirmDelete = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let data = memory.imageData, let ui = UIImage(data: data) {
                    Button { showImage = true } label: {
                        Image(uiImage: ui)
                            .resizable()
                            .scaledToFill()
                            .frame(maxWidth: .infinity)
                            .frame(height: 260)
                            .clipShape(.rect(cornerRadius: 28))
                            .overlay(alignment: .bottomTrailing) {
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                                    .font(.footnote.weight(.semibold))
                                    .padding(10)
                                    .glassEffect(.regular.interactive(), in: .circle)
                                    .padding(12)
                            }
                    }
                    .buttonStyle(.plain)
                }

                header
                eventCard
                textCard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(AmbientBackground())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(memory.isPinned ? "取消置顶" : "置顶",
                       systemImage: memory.isPinned ? "pin.fill" : "pin") {
                    memory.isPinned.toggle()
                }
                ShareLink(item: shareText)
                Button("删除", systemImage: "trash", role: .destructive) { confirmDelete = true }
            }
        }
        .confirmationDialog("删除这条记忆？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                MemoryProcessor.delete(memory)
                dismiss()
            }
        }
        .alert("无法完成", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
            Button("好", role: .cancel) {}
        } message: {
            Text(errorText ?? "")
        }
        .fullScreenCover(isPresented: $showImage) {
            if let data = memory.imageData, let ui = UIImage(data: data) {
                ImageViewer(image: ui)
            }
        }
        .onDisappear { Spotlight.index(memory) }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("\(memory.sourceKind.label) · \(memory.createdAt.formatted(.dateTime.year().month().day().hour().minute()))",
                  systemImage: memory.sourceKind.symbol)
                .font(.footnote)
                .foregroundStyle(.secondary)
            TextField("摘要", text: $memory.summary, axis: .vertical)
                .font(.title2.weight(.bold))
            if !memory.tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(memory.tags, id: \.self) { TagChip(tag: $0) }
                    }
                }
            }
        }
        .padding(.top, 4)
    }

    @ViewBuilder
    private var eventCard: some View {
        if memory.eventDate != nil {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Label("日程", systemImage: "calendar.badge.clock")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.orange)
                    Spacer()
                    Button("移除", systemImage: "xmark.circle.fill") {
                        withAnimation { memory.eventDate = nil; memory.eventTitle = nil }
                    }
                    .labelStyle(.iconOnly)
                    .foregroundStyle(.secondary)
                }
                TextField("事件标题", text: Binding(get: { memory.eventTitle ?? "" },
                                                 set: { memory.eventTitle = $0 }))
                    .font(.headline)
                DatePicker("时间", selection: Binding(get: { memory.eventDate ?? .now },
                                                    set: { memory.eventDate = $0 }))
                    .font(.subheadline)
                GlassEffectContainer(spacing: 10) {
                    HStack(spacing: 10) {
                        Button {
                            Task { await perform { try await Scheduler.addToCalendar(memory) } }
                        } label: {
                            Label(memory.addedToCalendar ? "已加入日历" : "加入日历",
                                  systemImage: memory.addedToCalendar ? "checkmark" : "calendar.badge.plus")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glassProminent)
                        .tint(.orange)

                        Button {
                            Task { await perform { try await Scheduler.addToReminders(memory) } }
                        } label: {
                            Label(memory.addedToReminders ? "已加入提醒" : "加入提醒",
                                  systemImage: memory.addedToReminders ? "checkmark" : "checklist")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glass)
                    }
                    .controlSize(.large)
                }
            }
            .padding(16)
            .glassCard(tint: .orange)
        } else {
            Button {
                withAnimation {
                    let next = Calendar.current.nextDate(after: .now, matching: DateComponents(minute: 0),
                                                         matchingPolicy: .nextTime) ?? .now
                    memory.eventDate = next
                    memory.eventTitle = memory.summary
                }
            } label: {
                Label("安排进日程", systemImage: "calendar.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .controlSize(.large)
        }
    }

    private var textCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("识别文字").font(.subheadline.weight(.semibold))
                Spacer()
                Button("拷贝", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = memory.text
                    model.flash("已拷贝")
                }
                .font(.subheadline)
                .buttonStyle(.borderless)
            }
            Text(memory.text.isEmpty ? "没有识别到文字" : memory.text)
                .font(.body)
                .foregroundStyle(memory.text.isEmpty ? .secondary : .primary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
        .glassCard()
    }

    private var shareText: String {
        [memory.summary, memory.text].filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    private func perform(_ action: () async throws -> Void) async {
        do {
            try await action()
            model.flash("已添加")
        } catch {
            errorText = error.localizedDescription
        }
    }
}

struct ImageViewer: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            ScrollView([.horizontal, .vertical], showsIndicators: false) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: UIScreen.main.bounds.width * scale)
                    .frame(minHeight: UIScreen.main.bounds.height)
            }
            .gesture(MagnifyGesture().onChanged { scale = max(1, min(4, $0.magnification)) })
            Button("关闭", systemImage: "xmark") { dismiss() }
                .labelStyle(.iconOnly)
                .font(.title3.weight(.semibold))
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .padding()
        }
    }
}
