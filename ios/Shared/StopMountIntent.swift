import AppIntents
import Foundation

/// DETENER: el botón de la Live Activity, el control del Centro de control, el botón de Acción
/// (como atajo) y Siri. Es un LiveActivityIntent, así que iOS siempre lo ejecuta en el proceso de
/// la app, que es la que tiene la conexión con la montura.
struct StopMountIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Detener telescopio"
    static let description: IntentDescription? = IntentDescription("Detiene al instante todos los motores de la montura.")

    init() {}

    func perform() async throws -> some IntentResult {
        #if APP_TARGET
        await MainActor.run { AppHost.shared.stopAll() }
        #endif
        return .result()
    }
}
