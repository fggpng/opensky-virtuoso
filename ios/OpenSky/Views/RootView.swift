import OpenSkyCore
import SwiftUI

enum AppTab: String, Hashable {
    case home, align, targets, control, more

    /// Pestaña inicial: `--tab=align` (capturas automáticas en CI); si no, Inicio.
    static var initial: AppTab {
        let arg = ProcessInfo.processInfo.arguments.first { $0.hasPrefix("--tab=") }
        return arg.flatMap { AppTab(rawValue: String($0.dropFirst(6))) } ?? .home
    }
}

/// Barra de pestañas de iOS 27 (Liquid Glass del sistema): Inicio, Alinear, Objetivos, Mando y Más.
struct RootView: View {
    @Environment(AppHost.self) private var host
    @State private var tab: AppTab = AppTab.initial

    var body: some View {
        let night = host.home.value.settings.nightMode
        TabView(selection: $tab) {
            Tab("Inicio", systemImage: "house", value: AppTab.home) {
                NavigationStack { HomeView(openTab: { tab = $0 }) }
            }
            Tab("Alinear", systemImage: "scope", value: AppTab.align) {
                NavigationStack { AlignmentView() }
            }
            Tab("Objetivos", systemImage: "sparkles", value: AppTab.targets) {
                NavigationStack { TargetsView() }
            }
            Tab("Mando", systemImage: "dpad", value: AppTab.control) {
                NavigationStack { ControlView() }
            }
            Tab("Más", systemImage: "ellipsis.circle", value: AppTab.more) {
                NavigationStack { MoreView() }
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .nightTheme(night)
    }
}
