import Foundation
import EventKit
import UserNotifications

enum SchedulerError: LocalizedError {
    case denied(String)
    case noDate
    var errorDescription: String? {
        switch self {
        case .denied(let what): "没有\(what)权限，请到 设置 › 屏忆 中开启"
        case .noDate: "这条记忆没有识别出时间"
        }
    }
}

@MainActor
enum Scheduler {
    static let store = EKEventStore()

    static var canWriteEventsSilently: Bool {
        let s = EKEventStore.authorizationStatus(for: .event)
        return s == .writeOnly || s == .fullAccess
    }

    /// 加入系统日历，并提前 15 分钟提醒。
    static func addToCalendar(_ memory: Memory) async throws {
        guard let date = memory.eventDate else { throw SchedulerError.noDate }
        if !canWriteEventsSilently {
            let granted = try await store.requestWriteOnlyAccessToEvents()
            guard granted else { throw SchedulerError.denied("日历") }
        }
        let event = EKEvent(eventStore: store)
        event.title = memory.eventTitle ?? memory.summary
        event.startDate = date
        event.endDate = date.addingTimeInterval(3600)
        event.notes = "来自屏忆：\(memory.summary)\n\n\(memory.text.prefix(500))"
        event.calendar = store.defaultCalendarForNewEvents
        event.addAlarm(EKAlarm(relativeOffset: -15 * 60))
        try store.save(event, span: .thisEvent)
        memory.addedToCalendar = true
    }

    /// 加入提醒事项。
    static func addToReminders(_ memory: Memory, due: Date? = nil) async throws {
        if EKEventStore.authorizationStatus(for: .reminder) != .fullAccess {
            let granted = try await store.requestFullAccessToReminders()
            guard granted else { throw SchedulerError.denied("提醒事项") }
        }
        let reminder = EKReminder(eventStore: store)
        reminder.title = memory.eventTitle ?? memory.summary
        reminder.notes = memory.text.isEmpty ? nil : String(memory.text.prefix(500))
        reminder.calendar = store.defaultCalendarForNewReminders()
        if let date = due ?? memory.eventDate {
            reminder.dueDateComponents = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            reminder.addAlarm(EKAlarm(absoluteDate: date))
        }
        try store.save(reminder, commit: true)
        memory.addedToReminders = true
    }

    // MARK: 每日回顾通知

    static let dailyReviewID = "daily-review"

    static func requestNotificationPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func scheduleDailyReview(hour: Int, minute: Int) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [dailyReviewID])
        guard await requestNotificationPermission() else { return }
        let content = UNMutableNotificationContent()
        content.title = "今日回顾"
        content.body = "花一分钟看看今天记下的内容，把要做的事排进日程。"
        content.sound = .default
        var comps = DateComponents()
        comps.hour = hour
        comps.minute = minute
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: true)
        try? await center.add(UNNotificationRequest(identifier: dailyReviewID, content: content, trigger: trigger))
    }

    static func cancelDailyReview() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [dailyReviewID])
    }
}
