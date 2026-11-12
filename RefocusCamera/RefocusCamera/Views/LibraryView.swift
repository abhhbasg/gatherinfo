import SwiftUI

/// 已拍摄的焦点堆栈。每个堆栈都可以随时重新打开并再次对焦。
struct LibraryView: View {
    var onOpen: (StackManifest) -> Void

    @Environment(StackLibrary.self) private var library
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 4)]

    var body: some View {
        NavigationStack {
            Group {
                if library.stacks.isEmpty {
                    ContentUnavailableView(
                        "还没有焦点堆栈",
                        systemImage: "square.3.layers.3d.down.right",
                        description: Text("按下快门，相机会从近到远扫描整个对焦范围。")
                    )
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 4) {
                            ForEach(library.stacks) { stack in
                                Button {
                                    onOpen(stack)
                                } label: {
                                    StackThumbnail(stack: stack)
                                }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button("删除", systemImage: "trash", role: .destructive) {
                                        withAnimation { library.delete(stack) }
                                    }
                                }
                            }
                        }
                        .padding(4)
                    }
                }
            }
            .navigationTitle("焦点堆栈")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成", systemImage: "checkmark") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct StackThumbnail: View {
    let stack: StackManifest
    @Environment(StackLibrary.self) private var library
    @State private var image: UIImage?

    var body: some View {
        Color.secondary.opacity(0.15)
            .aspectRatio(3 / 4, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                }
            }
            .clipShape(.rect(cornerRadius: 10, style: .continuous))
            .overlay(alignment: .bottomLeading) {
                Label("\(stack.frames.count)", systemImage: stack.hasDepth ? "cube.transparent" : "square.3.layers.3d")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .glassEffect(.regular, in: .capsule)
                    .padding(6)
            }
            .task(id: stack.id) {
                image = await library.thumbnail(for: stack, maxPixelSize: 360)
            }
    }
}
