import OpenSkyCore
import SwiftUI

@main
struct OpenSkyApp: App {
    @State private var host = AppHost.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(host)
        }
        .onChange(of: scenePhase) { _, phase in
            host.scenePhaseChanged(phase)
        }
    }
}
