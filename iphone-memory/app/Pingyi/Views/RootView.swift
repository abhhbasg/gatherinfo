import SwiftUI
import SwiftData
import CoreSpotlight

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.modelContext) private var context

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            Tab("记忆", systemImage: "square.stack.3d.up.fill", value: AppTab.timeline) {
                TimelineScreen()
            }
            Tab("日程", systemImage: "calendar", value: AppTab.agenda) {
                AgendaScreen()
            }
            Tab("设置", systemImage: "gearshape", value: AppTab.settings) {
                SettingsScreen()
            }
            Tab(value: AppTab.search, role: .search) {
                SearchScreen()
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .overlay(alignment: .top) {
            if model.isWorking {
                ToastView(text: "正在识别…", working: true)
                    .transition(.move(edge: .top).combined(with: .opacity))
            } else if let toast = model.toast {
                ToastView(text: toast)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.35), value: model.isWorking)
        .onContinueUserActivity(CSSearchableItemActionType) { activity in
            guard let idString = activity.userInfo?[CSSearchableItemActivityIdentifier] as? String,
                  let id = UUID(uuidString: idString),
                  let memory = MemoryProcessor.memory(id: id) else { return }
            model.tab = .timeline
            model.timelinePath = [memory]
        }
        .task { applyLaunchArguments() }
    }

    /// 便于自动截图：-tab agenda / search / settings / detail
    private func applyLaunchArguments() {
        guard let arg = UserDefaults.standard.string(forKey: "tab") else { return }
        if let tab = AppTab(rawValue: arg) { model.tab = tab }
        if arg == "detail" {
            var d = FetchDescriptor<Memory>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)])
            d.fetchLimit = 1
            if let first = try? context.fetch(d).first { model.timelinePath = [first] }
        }
    }
}
