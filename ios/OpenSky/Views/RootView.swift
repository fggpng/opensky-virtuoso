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
        Group {
            if let screen = DebugScreen.requested {
                screen.content
            } else {
                tabs
            }
        }
        .nightTheme(night)
    }

    private var tabs: some View {
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
    }
}

/// Una pantalla interior abierta directamente, solo para las capturas automáticas de CI
/// (`--screen=settings`); sin ese argumento la app arranca siempre en las pestañas.
enum DebugScreen: String {
    case sensor, stellarium, catalog, diagnostics, about, settings, manual, help, target

    static var requested: DebugScreen? {
        let arg = ProcessInfo.processInfo.arguments.first { $0.hasPrefix("--screen=") }
        return arg.flatMap { DebugScreen(rawValue: String($0.dropFirst(9))) }
    }

    @MainActor @ViewBuilder var content: some View {
        switch self {
        case .sensor: NavigationStack { SensorView() }
        case .stellarium: NavigationStack { StellariumView() }
        case .catalog: NavigationStack { CatalogView() }
        case .diagnostics: NavigationStack { DiagnosticsView() }
        case .about: NavigationStack { AboutView() }
        case .settings: NavigationStack { SettingsView() }
        case .manual: ManualStartSheet()
        case .help: ConnectHelpSheet()
        case .target: DebugTargetScreen()
        }
    }
}

/// La hoja de un objetivo (la Luna, si está en la lista) para las capturas.
private struct DebugTargetScreen: View {
    @Environment(AppHost.self) private var host

    var body: some View {
        let rows = host.targets.value.rows
        if let row = rows.first(where: { $0.target.name == "Luna" }) ?? rows.first {
            TargetActionSheet(row: row)
        } else {
            ProgressView()
        }
    }
}
