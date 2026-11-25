import Foundation
import UserNotifications

enum Notifier {
    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func sms(from: String, body: String, thread: String) {
        let content = UNMutableNotificationContent()
        content.title = from
        content.body = body
        content.sound = .default
        content.threadIdentifier = thread
        content.userInfo = ["thread": thread]
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Reminds the user to refresh the app in SideStore before the signature expires.
    static func scheduleRenewalReminders(expiry: Date?) {
        let center = UNUserNotificationCenter.current()
        let ids = ["renew-2d", "renew-1d", "renew-3h"]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard let expiry else { return }
        let offsets: [(String, TimeInterval, String)] = [
            (ids[0], 2 * 86_400, "۲ روز تا تمدید پل مانده"),
            (ids[1], 86_400, "فردا اعتبار پل تمام می‌شود"),
            (ids[2], 3 * 3_600, "فقط چند ساعت تا بسته شدن پل مانده"),
        ]
        for (id, before, title) in offsets {
            let fire = expiry.addingTimeInterval(-before)
            guard fire > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = "LocalDevVPN را روشن کن و در SideStore پل را تمدید کن."
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: fire.timeIntervalSinceNow, repeats: false)
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
    }
}
