import SwiftUI
import SwiftData

struct AgendaScreen: View {
    @Query(filter: #Predicate<Memory> { $0.eventDate != nil }, sort: [SortDescriptor(\Memory.createdAt)])
    private var stored: [Memory]

    private var events: [Memory] {
        stored.sorted { ($0.eventDate ?? .distantPast) < ($1.eventDate ?? .distantPast) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if events.isEmpty {
                        ContentUnavailableView("暂无日程",
                                               systemImage: "calendar",
                                               description: Text("屏幕上出现时间、约定或截止日期时，会自动整理到这里。"))
                            .padding(.top, 80)
                    }
                    if !upcoming.isEmpty {
                        SectionTitle(text: "即将到来")
                        ForEach(upcoming) { m in
                            NavigationLink(value: m) { AgendaRow(memory: m) }.buttonStyle(.plain)
                        }
                    }
                    if !past.isEmpty {
                        SectionTitle(text: "已过去")
                        ForEach(past) { m in
                            NavigationLink(value: m) { AgendaRow(memory: m) }.buttonStyle(.plain)
                                .opacity(0.7)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(AmbientBackground())
            .navigationTitle("日程")
            .navigationDestination(for: Memory.self) { MemoryDetailView(memory: $0) }
        }
    }

    private var upcoming: [Memory] { events.filter { ($0.eventDate ?? .distantPast) >= .now } }
    private var past: [Memory] { events.filter { ($0.eventDate ?? .distantPast) < .now }.reversed().map { $0 } }
}

struct AgendaRow: View {
    let memory: Memory

    var body: some View {
        let date = memory.eventDate ?? .now
        HStack(spacing: 14) {
            VStack(spacing: 2) {
                Text(date.formatted(.dateTime.month(.abbreviated)))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.orange)
                Text(date.formatted(.dateTime.day()))
                    .font(.title2.weight(.bold))
                Text(date.formatted(.dateTime.weekday(.abbreviated)))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .frame(width: 56, height: 70)
            .glassEffect(.regular.tint(.orange.opacity(0.12)), in: .rect(cornerRadius: 16))

            VStack(alignment: .leading, spacing: 5) {
                Text(memory.eventTitle ?? memory.summary)
                    .font(.headline)
                    .lineLimit(2)
                Label(date.formatted(.dateTime.hour().minute()), systemImage: "clock")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    if memory.addedToCalendar {
                        Label("日历", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    }
                    if memory.addedToReminders {
                        Label("提醒", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    }
                    if !memory.addedToCalendar && !memory.addedToReminders {
                        Label("未安排", systemImage: "circle.dashed").foregroundStyle(.orange)
                    }
                }
                .font(.caption.weight(.medium))
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(12)
        .contentShape(.rect(cornerRadius: 24))
        .glassCard()
    }
}
