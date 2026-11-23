import SwiftUI
import WidgetKit

/// Extensión de widgets: Live Activity de la sesión y control DETENER del Centro de control.
@main
struct OpenSkyWidgetsBundle: WidgetBundle {
    var body: some Widget {
        MountLiveActivity()
        StopMountControl()
    }
}
