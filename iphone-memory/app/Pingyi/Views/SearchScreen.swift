import SwiftUI
import SwiftData

struct SearchScreen: View {
    @Query(sort: \Memory.createdAt, order: .reverse) private var memories: [Memory]
    @State private var query = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if query.isEmpty {
                        if !topTags.isEmpty {
                            SectionTitle(text: "标签")
                            FlowTags(tags: topTags) { query = $0 }
                        }
                        SectionTitle(text: "试试搜索")
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(["取件码", "航班", "会议", "餐厅"], id: \.self) { s in
                                Button { query = s } label: {
                                    Label(s, systemImage: "magnifyingglass")
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.vertical, 12)
                                        .padding(.horizontal, 16)
                                }
                                .buttonStyle(.plain)
                                if s != "餐厅" { Divider().padding(.leading, 44) }
                            }
                        }
                        .glassCard()
                    } else if results.isEmpty {
                        ContentUnavailableView.search(text: query).padding(.top, 60)
                    } else {
                        SectionTitle(text: "\(results.count) 条结果")
                        ForEach(results) { MemoryLink(memory: $0) }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(AmbientBackground())
            .navigationTitle("搜索")
            .searchable(text: $query, prompt: "文字、标签、日程")
            .navigationDestination(for: Memory.self) { MemoryDetailView(memory: $0) }
        }
    }

    private var results: [Memory] { memories.filter { $0.matches(query) } }

    private var topTags: [String] {
        var counts: [String: Int] = [:]
        for m in memories { for t in m.tags { counts[t, default: 0] += 1 } }
        return counts.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }
            .prefix(12).map(\.key)
    }
}

struct FlowTags: View {
    let tags: [String]
    let onTap: (String) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(tags, id: \.self) { tag in
                Button { onTap(tag) } label: {
                    Text("#\(tag)")
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .capsule)
            }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, rows.count - 1))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for i in row.indices {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            let extra = rows[rows.count - 1].indices.isEmpty ? size.width : size.width + spacing
            if rows[rows.count - 1].width + extra > width, !rows[rows.count - 1].indices.isEmpty {
                rows.append(Row())
            }
            let add = rows[rows.count - 1].indices.isEmpty ? size.width : size.width + spacing
            rows[rows.count - 1].indices.append(i)
            rows[rows.count - 1].width += add
            rows[rows.count - 1].height = max(rows[rows.count - 1].height, size.height)
        }
        return rows.filter { !$0.indices.isEmpty }
    }
}
