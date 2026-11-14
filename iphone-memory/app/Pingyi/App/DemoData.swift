#if DEBUG
import SwiftUI
import SwiftData

/// 仅用于模拟器截图：启动参数 -seedDemo YES
@MainActor
enum DemoData {
    static func seed() {
        let ctx = Store.context
        try? ctx.delete(model: Memory.self)
        let cal = Calendar.current
        func at(_ dayOffset: Int, _ h: Int, _ m: Int = 0) -> Date {
            let day = cal.date(byAdding: .day, value: dayOffset, to: .now) ?? .now
            return cal.date(bySettingHour: h, minute: m, second: 0, of: day) ?? day
        }
        let items: [(String, String, [String], MemorySource, String?, Date?, Date, Bool, Color)] = [
            ("周五和小林在鮨一吃饭", "小林：周五晚上 7 点鮨一见？我订好位了\n地址：静安区南京西路 1266 号 3F\n我：好呀，不见不散", ["美食", "朋友"], .screenshot, "鮨一晚餐", at(3, 19), at(0, 10, 24), false, .green),
            ("菜鸟驿站取件码 6-2-3018", "【菜鸟驿站】您的顺丰包裹已到小区东门驿站，取件码 6-2-3018，请于 21:00 前领取", ["快递"], .screenshot, nil, nil, at(0, 9, 12), false, .orange),
            ("产品评审会改到周三下午 3 点", "各位，本周产品评审会改到周三 15:00，会议室 B201，请提前看完 PRD v3", ["工作", "会议"], .screenshot, "产品评审会", at(1, 15), at(0, 8, 40), true, .blue),
            ("上海→东京 NH920 航班", "NH920 上海浦东 PVG → 东京羽田 HND\n12月20日 09:35 起飞 · 13:25 到达\n座位 23A · 登机口 D72", ["出行", "航班"], .screenshot, "NH920 飞东京", at(36, 9, 35), at(-1, 22, 5), false, .indigo),
            ("《深度工作》摘录：专注是新的智商", "在新经济中，能快速掌握困难事物、并以高质量产出的人将会脱颖而出。专注力是新的智商。", ["阅读", "摘录"], .photo, nil, nil, at(-1, 21, 30), false, .purple),
            ("降噪耳机三款价格对比", "A 款 ¥1,299 · B 款 ¥1,599 · C 款 ¥2,199\n双 11 叠加券再减 ¥200", ["购物"], .screenshot, nil, nil, at(-1, 13, 2), false, .pink),
            ("租房合同续签截止 11 月 30 日", "房东：合同 11 月 30 日到期，续签的话月底前把签好的合同拍照发我", ["生活", "租房"], .text, "续签租房合同", at(16, 10), at(-2, 18, 47), false, .teal),
        ]
        for (summary, text, tags, source, title, date, created, added, color) in items {
            let img = source == .text ? nil : mockScreenshot(text: text, color: color)
            let m = Memory(text: text, summary: summary, tags: tags, source: source,
                           imageData: img, thumbnailData: img.flatMap { OCR.thumbnail($0) },
                           eventTitle: title, eventDate: date, createdAt: created)
            m.addedToCalendar = added
            ctx.insert(m)
        }
        try? ctx.save()
    }

    private static func mockScreenshot(text: String, color: Color) -> Data? {
        let size = CGSize(width: 390, height: 640)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { c in
            UIColor.systemGroupedBackground.resolvedColor(with: .init(userInterfaceStyle: .light)).setFill()
            c.fill(CGRect(origin: .zero, size: size))
            UIColor(color).withAlphaComponent(0.85).setFill()
            c.fill(CGRect(x: 0, y: 0, width: size.width, height: 110))
            var y: CGFloat = 140
            for line in text.split(separator: "\n") {
                let rect = CGRect(x: 24, y: y, width: size.width - 48, height: 110)
                UIColor.white.setFill()
                UIBezierPath(roundedRect: rect, cornerRadius: 18).fill()
                let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 17), .foregroundColor: UIColor.darkGray]
                NSString(string: String(line)).draw(in: rect.insetBy(dx: 16, dy: 14), withAttributes: attrs)
                y += 126
            }
        }
        return image.jpegData(compressionQuality: 0.85)
    }
}
#endif
