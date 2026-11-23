import Observation
import OpenSkyCore
import SwiftUI
import UIKit

/// Une el núcleo Kotlin (AppGraph) con SwiftUI y con los servicios del sistema: pantalla siempre
/// encendida, sesión con la pantalla bloqueada y Live Activity.
@MainActor
@Observable
final class AppHost {
    static let shared = AppHost()

    let device: DeviceBridge
    let graph: AppGraph
    let home: Screen<HomeState>
    let sensors: Screen<SensorState>
    let alignment: Screen<AlignmentScreenState>
    let targets: Screen<TargetsState>
    let control: Screen<ControlState>
    let more: Screen<MoreState>

    @ObservationIgnored private var watches: [Watch] = []
    private let live = LiveActivityController()
    @ObservationIgnored private var active = true

    private init() {
        let bridge = DeviceBridge()
        device = bridge
        graph = IosAppKt.createIosApp(device: bridge)
        bridge.attach(graph: graph)
        home = Screen(graph.home.state)
        sensors = Screen(graph.sensors.state)
        alignment = Screen(graph.alignmentScreen.state)
        targets = Screen(graph.targetsScreen.state)
        control = Screen(graph.control.state)
        more = Screen(graph.more.state)
        watches.append(graph.control.state.watch { [weak self] state in
            MainActor.assumeIsolated { self?.mountChanged(state.mount) }
        })
        watches.append(graph.home.state.watch { [weak self] state in
            MainActor.assumeIsolated { self?.applyScreenSetting(state.settings.keepScreenOn) }
        })
    }

    /// DETENER desde la Live Activity, el Centro de control, el botón de Acción o Siri.
    func stopAll() {
        graph.control.stopAll()
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        active = phase == .active
        applyScreenSetting(graph.home.state.value.settings.keepScreenOn)
        if phase == .background {
            // Un dedo que se queda en el pad al salir de la app no debe dejar el tubo girando.
            graph.control.releasePad()
        }
    }

    private func mountChanged(_ mount: MountState?) {
        let connected = mount != nil && mount?.link != LinkState.disconnected
        device.setKeepAlive(connected)
        live.update(mount: mount)
    }

    private func applyScreenSetting(_ keepOn: Bool) {
        UIApplication.shared.isIdleTimerDisabled = keepOn && active
    }
}
