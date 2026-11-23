import AppIntents

/// Atajos que la app ofrece al sistema: aparecen en Atajos, en Siri y en
/// Ajustes › Botón de Acción › Atajo (iPhone 15 Pro y posteriores, como el 16 Pro Max).
struct OpenSkyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StopMountIntent(),
            phrases: [
                "Detén el telescopio con \(.applicationName)",
                "Para el telescopio con \(.applicationName)",
                "Detener \(.applicationName)",
            ],
            shortTitle: "Detener telescopio",
            systemImageName: "stop.circle.fill"
        )
    }
}
