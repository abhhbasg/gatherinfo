import SwiftUI

@main
struct RefocusCameraApp: App {
    @State private var library = StackLibrary()

    var body: some Scene {
        WindowGroup {
            CameraScreen()
                .environment(library)
                .preferredColorScheme(.dark)
                .tint(.yellow)
        }
    }
}
