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
    @ObservationIgnored private var night = false

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
            MainActor.assumeIsolated {
                guard let self else { return }
                self.applyScreenSetting(state.settings.keepScreenOn)
                if state.settings.nightMode != self.night {
                    self.night = state.settings.nightMode
                    self.live.update(mount: self.graph.control.state.value.mount, night: self.night)
                }
            }
        })
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--demo") { startDemo(night: args.contains("--night")) }
    }

    /// Solo para las capturas automáticas de CI (`--demo`): simulador conectado, sitio en
    /// Chihuahua, posición de inicio confirmada y alineación en marcha.
    private func startDemo(night: Bool) {
        let more = graph.more
        more.setSetting(key: "night", value: night ? "true" : "false")
        more.setSetting(key: "manual_loc", value: "true")
        more.setSetting(key: "lat", value: "28.6353")
        more.setSetting(key: "lon", value: "-106.0889")
        more.setSetting(key: "elev", value: "1430")
        graph.home.connect(mode: ConnectionMode.simulator)
        let g = graph
        Task {
            try? await Task.sleep(for: .seconds(2))
            g.home.confirmHome()
            try? await Task.sleep(for: .seconds(1))
            g.alignmentScreen.begin(mode: AlignmentMode.twoStar)
        }
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
        live.update(mount: mount, night: night)
    }

    private func applyScreenSetting(_ keepOn: Bool) {
        UIApplication.shared.isIdleTimerDisabled = keepOn && active
    }
}
