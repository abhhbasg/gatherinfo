import SwiftUI
import SwiftData

@main
struct PingyiApp: App {
    @State private var model = AppModel()

    init() {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "seedDemo") { DemoData.seed() }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(.indigo)
        }
        .modelContainer(Store.container)
    }
}
