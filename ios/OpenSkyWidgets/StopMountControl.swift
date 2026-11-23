import AppIntents
import SwiftUI
import WidgetKit

/// Control del Centro de control (y de la pantalla bloqueada o el botón de Acción): DETENER.
struct StopMountControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.opensky.virtuoso.stop") {
            ControlWidgetButton(action: StopMountIntent()) {
                Label("Detener telescopio", systemImage: "stop.circle.fill")
            }
        }
        .displayName("Detener telescopio")
        .description("Detiene al instante los motores de la montura.")
    }
}
