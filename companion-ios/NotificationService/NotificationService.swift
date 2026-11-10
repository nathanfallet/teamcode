import UserNotifications

/// Shows a push's real text: the relay sends a generic alert ("New activity in TeamCode") with `mutable-content` and the
/// payload encrypted for this device in `p`; this extension decrypts it with the key the app shares through the Keychain.
/// A payload it cannot read (another key, a key not made yet) keeps the generic alert. docs/notifications.md, "Push".
final class NotificationService: UNNotificationServiceExtension {
    private var handler: ((UNNotificationContent) -> Void)?
    private var generic: UNNotificationContent?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        handler = contentHandler
        generic = request.content
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent,
              let payload = request.content.userInfo["p"] as? String, payload.count <= 4_096,
              let key = PushCrypto.privateKey(create: false),
              let json = try? PushCrypto.decrypt(payload, with: key),
              let push = try? JSONDecoder().decode(PushCrypto.Payload.self, from: json),
              !push.title.isEmpty
        else { return contentHandler(request.content) }
        content.title = String(push.title.prefix(200))
        content.body = String(push.body.prefix(1_000))
        // One thread per session in Notification Center.
        content.threadIdentifier = push.sessionId ?? push.origin
        var info = content.userInfo
        info.removeValue(forKey: "p")
        info["link"] = PushCrypto.link(for: push)
        content.userInfo = info
        contentHandler(content)
    }

    /// Out of time: the generic alert is shown.
    override func serviceExtensionTimeWillExpire() {
        if let handler, let generic { handler(generic) }
    }
}
