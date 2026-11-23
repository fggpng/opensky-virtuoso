import ActivityKit
import Foundation
import OpenSkyCore

/// Live Activity mientras la montura está conectada: estado, objetivo y posición en la pantalla
/// bloqueada y en la Dynamic Island, con botón DETENER.
@MainActor
final class LiveActivityController {
    private var activity: ActivityKit.Activity<MountActivityAttributes>?
    private var last: MountActivityAttributes.ContentState?
    private var lastUpdate = Date.distantPast

    init() {
        // Una Live Activity de una ejecución anterior (la app se cerró) ya no refleja nada: fuera.
        let stale = ActivityKit.Activity<MountActivityAttributes>.activities
        Task {
            for activity in stale { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    func update(mount: MountState?) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard let mount, mount.link != LinkState.disconnected else {
            end()
            return
        }
        let state = Self.content(for: mount)
        if activity == nil {
            let attributes = MountActivityAttributes(mountName: mount.info?.modelName ?? "Montura")
            activity = try? ActivityKit.Activity.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: nil)
            )
            last = state
            lastUpdate = Date()
            return
        }
        guard state != last else { return }
        // Cambios de estado al instante; la posición, como mucho cada 5 s.
        let important = state.status != last?.status || state.target != last?.target || state.alert != last?.alert
        guard important || Date().timeIntervalSince(lastUpdate) > 5 else { return }
        last = state
        lastUpdate = Date()
        let current = activity
        Task { await current?.update(ActivityContent(state: state, staleDate: nil)) }
    }

    func end() {
        guard let current = activity else { return }
        activity = nil
        last = nil
        Task { await current.end(nil, dismissalPolicy: .immediate) }
    }

    private static func content(for m: MountState) -> MountActivityAttributes.ContentState {
        let a = m.activity
        let status: String
        let symbol: String
        if a == OpenSkyCore.Activity.slewing {
            status = "GoTo en curso"; symbol = "arrow.triangle.turn.up.right.circle.fill"
        } else if a == OpenSkyCore.Activity.tracking {
            status = "Siguiendo"; symbol = "scope"
        } else if a == OpenSkyCore.Activity.manual {
            status = "Moviendo con el mando"; symbol = "dpad.fill"
        } else if m.link == LinkState.lost || m.link == LinkState.retrying {
            status = "Reconectando…"; symbol = "wifi.exclamationmark"
        } else {
            status = "Quieta"; symbol = "pause.circle"
        }
        let moving = a == OpenSkyCore.Activity.slewing || a == OpenSkyCore.Activity.tracking || a == OpenSkyCore.Activity.manual
        return MountActivityAttributes.ContentState(
            status: status,
            symbol: symbol,
            target: m.target?.name,
            altitudeDeg: m.pointing?.altDeg,
            azimuthDeg: m.pointing?.azDeg,
            moving: moving,
            alert: m.alert
        )
    }
}
