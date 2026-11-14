import SwiftUI

struct AmbientBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        MeshGradient(
            width: 3, height: 3,
            points: [
                [0, 0], [0.5, 0], [1, 0],
                [0, 0.5], [0.62, 0.42], [1, 0.5],
                [0, 1], [0.5, 1], [1, 1],
            ],
            colors: scheme == .dark ? dark : light
        )
        .ignoresSafeArea()
    }

    private var light: [Color] {
        [
            Color(red: 0.91, green: 0.90, blue: 1.00), Color(red: 0.96, green: 0.95, blue: 1.00), Color(red: 0.86, green: 0.93, blue: 1.00),
            Color(red: 0.98, green: 0.92, blue: 0.97), Color(red: 0.97, green: 0.97, blue: 1.00), Color(red: 0.89, green: 0.96, blue: 0.99),
            Color(red: 0.95, green: 0.93, blue: 1.00), Color(red: 0.99, green: 0.95, blue: 0.93), Color(red: 0.92, green: 0.94, blue: 1.00),
        ]
    }

    private var dark: [Color] {
        [
            Color(red: 0.13, green: 0.10, blue: 0.27), Color(red: 0.06, green: 0.07, blue: 0.14), Color(red: 0.06, green: 0.13, blue: 0.25),
            Color(red: 0.16, green: 0.08, blue: 0.22), Color(red: 0.05, green: 0.06, blue: 0.12), Color(red: 0.05, green: 0.12, blue: 0.19),
            Color(red: 0.09, green: 0.07, blue: 0.18), Color(red: 0.13, green: 0.07, blue: 0.14), Color(red: 0.05, green: 0.08, blue: 0.17),
        ]
    }
}

extension View {
    func glassCard(cornerRadius: CGFloat = 24, tint: Color? = nil) -> some View {
        self.glassEffect(tint.map { Glass.regular.tint($0.opacity(0.18)) } ?? .regular,
                         in: .rect(cornerRadius: cornerRadius))
    }
}

struct TagChip: View {
    let tag: String
    var selected = false

    var body: some View {
        Text("#\(tag)")
            .font(.caption.weight(.medium))
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .foregroundStyle(selected ? Color.white : Color.indigo)
            .background(selected ? Color.indigo : Color.indigo.opacity(0.12), in: .capsule)
    }
}

struct EventBadge: View {
    let date: Date
    var done = false

    var body: some View {
        Label(date.formatted(.dateTime.month(.defaultDigits).day().hour().minute()),
              systemImage: done ? "checkmark.circle.fill" : "calendar")
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .foregroundStyle(done ? Color.green : Color.orange)
            .background((done ? Color.green : Color.orange).opacity(0.14), in: .capsule)
    }
}

struct Thumbnail: View {
    let memory: Memory
    var width: CGFloat = 62
    var height: CGFloat = 84

    var body: some View {
        Group {
            if let data = memory.thumbnailData, let ui = UIImage(data: data) {
                Image(uiImage: ui).resizable().scaledToFill()
            } else {
                ZStack {
                    LinearGradient(colors: [.indigo.opacity(0.22), .cyan.opacity(0.18)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                    Image(systemName: memory.sourceKind.symbol)
                        .font(.title3)
                        .foregroundStyle(.indigo)
                }
            }
        }
        .frame(width: width, height: height)
        .clipShape(.rect(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.35), lineWidth: 0.5))
    }
}

struct ToastView: View {
    let text: String
    var working = false

    var body: some View {
        HStack(spacing: 10) {
            if working {
                ProgressView()
            } else {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            }
            Text(text).font(.subheadline.weight(.medium))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .glassEffect(.regular, in: .capsule)
    }
}

enum DayLabel {
    static func text(_ day: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(day) { return "今天" }
        if cal.isDateInYesterday(day) { return "昨天" }
        if cal.isDateInTomorrow(day) { return "明天" }
        let sameYear = cal.component(.year, from: day) == cal.component(.year, from: .now)
        return day.formatted(sameYear ? .dateTime.month().day().weekday(.wide) : .dateTime.year().month().day())
    }
}
