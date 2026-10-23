import Companion
import SwiftUI
import UIKit

/// Hosts the shared Compose UI (`:companion`, framework `Companion`).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            // "Open in app" on the viewer page: teamcode://open?url=<viewer URL>, checked by the app before anything opens.
            .onOpenURL { url in MainViewControllerKt.openLink(link: url.absoluteString) }
    }
}
