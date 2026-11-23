import OpenSkyCore
import SwiftUI
import UIKit

/// Inicio: equipo, conexión con la montura, posición de inicio, ubicación y hora.
struct HomeView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night
    let openTab: (AppTab) -> Void
    @State private var showManual = false
    @State private var showConnectHelp = false

    var body: some View {
        let s = host.home.value
        let model = host.graph.home
        List {
            Section {
                header(s)
            }
            if let alert = s.mount?.alert {
                Section {
                    Label(alert, systemImage: "exclamationmark.octagon.fill").foregroundStyle(.red)
                    Button("Entendido") { model.clearAlert() }
                }
            }
            equipment(s, model)
            connection(s, model)
            if s.mount != nil { startPosition(s, model) }
            location(s, model)
            time(s, model)
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("OpenSky")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showConnectHelp = true } label: { Image(systemName: "questionmark.circle") }
                    .accessibilityLabel("Cómo conectar la montura")
            }
        }
        .sheet(isPresented: $showManual) { ManualStartSheet().nightTheme(night) }
        .sheet(isPresented: $showConnectHelp) { ConnectHelpSheet().nightTheme(night) }
        .messageBanner(s.message) { model.clearMessage() }
    }

    // MARK: Cabecera

    @ViewBuilder private func header(_ s: HomeState) -> some View {
        let (text, tone) = linkStatus(s)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                StatusChip(text: text, tone: tone)
                Spacer()
                if let info = s.mount?.info {
                    Text("\(info.modelName) · fw \(info.firmwareVersion)").font(.caption).foregroundStyle(.secondary)
                }
            }
            if let p = s.mount?.pointing {
                StatGrid(stats: [
                    Stat(label: "Altura", value: deg(p.altDeg)),
                    Stat(label: "Acimut", value: azimuthText(p.azDeg), detail: cardinal(p.azDeg)),
                    Stat(label: "Estado", value: activityText(s.mount)),
                ])
            }
        }
    }

    private func linkStatus(_ s: HomeState) -> (String, StatusChip.Tone) {
        if s.session.connecting { return ("Conectando…", .busy) }
        guard let m = s.mount else { return ("Desconectada", .off) }
        if m.link == LinkState.connected { return ("Conectada", .ok) }
        if m.link == LinkState.retrying || m.link == LinkState.connecting { return ("Reintentando…", .busy) }
        if m.link == LinkState.lost { return ("Enlace perdido", .off) }
        return ("Desconectada", .off)
    }

    // MARK: Equipo

    @ViewBuilder private func equipment(_ s: HomeState, _ model: HomeModel) -> some View {
        Section {
            Picker("Configuración", selection: Binding(
                get: { s.settings.observingMode },
                set: { model.setObservingMode(mode: $0) }
            )) {
                ForEach(s.observingModes, id: \.self) { mode in
                    Text(mode.shortLabel).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            if s.settings.observingMode == ObservingMode.visual {
                Picker("Ocular", selection: Binding(
                    get: { s.settings.eyepieceId },
                    set: { model.setEyepiece(id: $0) }
                )) {
                    ForEach(s.eyepieces, id: \.id) { e in
                        Text(e.name).tag(e.id)
                    }
                }
                .nightAwarePickerStyle(night)
            }
            Hint(s.opticalSummary)
        } header: {
            Text("Equipo")
        }
    }

    // MARK: Conexión

    @ViewBuilder private func connection(_ s: HomeState, _ model: HomeModel) -> some View {
        Section {
            if s.session.connecting {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(s.session.step ?? "Conectando…")
                }
            } else if s.mount != nil {
                if let mode = s.session.mode { Text(mode.label).font(.subheadline) }
                Button("Desconectar", role: .destructive) { model.disconnect() }
            } else {
                Button {
                    model.connect(mode: ConnectionMode.mountWifi)
                } label: {
                    Label("Conectar por la Wi-Fi de la montura", systemImage: "wifi")
                }
                Button {
                    model.connect(mode: ConnectionMode.simulator)
                } label: {
                    Label("Probar con el simulador", systemImage: "cpu")
                }
                Hint("Une antes el iPhone a la red «\(s.settings.ssidPrefix)…» en Configuración › Wi-Fi. iOS dirá «Sin conexión a Internet»: es normal.")
                Button("Abrir Configuración") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
            if let error = s.session.error {
                Text(error).font(.callout).foregroundStyle(.red)
            }
        } header: {
            Text("Montura")
        }
    }

    // MARK: Posición de inicio

    @ViewBuilder private func startPosition(_ s: HomeState, _ model: HomeModel) -> some View {
        let unconfirmed = s.mount?.startPosition == StartPosition.unconfirmed && s.alignmentSamples == 0
        Section {
            LabeledContent("Estado", value: s.mount?.startPosition.label ?? "—")
            if !s.locationKnown {
                WarningRow(text: "Sin ubicación todavía: el GoTo está bloqueado hasta tener GPS o coordenadas manuales.")
            }
            if unconfirmed {
                WarningRow(text: "Sin confirmar: el GoTo está bloqueado hasta que la confirmes.")
            }
            Hint("En cada sesión, ya en el sitio: deja el tubo horizontal apuntando al norte, o mídelo con el teléfono, o escribe hacia dónde apunta.")
            Button { model.confirmHome() } label: {
                Label("El tubo está horizontal hacia el norte", systemImage: "location.north.line")
            }
            NavigationLink {
                SensorView()
            } label: {
                Label("Medir con el teléfono sobre el tubo", systemImage: "iphone.gen3.radiowaves.left.and.right")
            }
            Button { showManual = true } label: {
                Label("Escribir hacia dónde apunta el tubo", systemImage: "character.cursor.ibeam")
            }
            Button { model.checkAltitudeWithPhone() } label: {
                HStack {
                    Label("Comprobar la altura con el teléfono", systemImage: "level")
                    if s.checking { Spacer(); ProgressView() }
                }
            }
            .disabled(s.checking)
            Hint("Comprobar: apoya el teléfono plano sobre el tubo 2 s. Si la altura no coincide (más de 5°), el GoTo se bloquea.")
            if !unconfirmed {
                Button("Ir a Alinear") { openTab(.align) }
            }
        } header: {
            Text("Posición de inicio")
        }
    }

    // MARK: Ubicación

    @ViewBuilder private func location(_ s: HomeState, _ model: HomeModel) -> some View {
        Section {
            if s.settings.useManualLocation {
                LabeledContent("Manual", value: String(format: "%.4f°, %.4f°", s.settings.manualLatitude, s.settings.manualLongitude))
                LabeledContent("Altitud", value: String(format: "%.0f m", s.settings.manualElevationM))
            } else if let l = s.location {
                LabeledContent("GPS", value: String(format: "%.4f°, %.4f°", l.latitudeDeg, l.longitudeDeg))
                LabeledContent("Altitud", value: String(format: "%.0f m", l.altitudeM))
                if let acc = l.accuracyM.double {
                    LabeledContent("Precisión", value: String(format: "±%.0f m", acc))
                }
            } else {
                WarningRow(text: "Sin fix GPS todavía. Permite la ubicación o escribe coordenadas manuales en Más › Ajustes.")
            }
            Button { model.refreshLocation() } label: { Label("Actualizar GPS", systemImage: "location") }
        } header: {
            Text("Ubicación")
        }
    }

    // MARK: Hora

    @ViewBuilder private func time(_ s: HomeState, _ model: HomeModel) -> some View {
        Section {
            if let c = s.clock {
                LabeledContent("UTC", value: c.utc).monospacedDigit()
                LabeledContent("Local (" + c.zoneLabel + ")", value: c.local).monospacedDigit()
                LabeledContent("Tiempo sidéreo", value: c.lst).monospacedDigit()
            }
            Hint(s.time.detail)
            Button { model.syncTime() } label: { Label("Verificar hora (NTP)", systemImage: "clock.arrow.2.circlepath") }
        } header: {
            Text("Hora")
        }
    }
}

func activityText(_ m: MountState?) -> String {
    guard let m else { return "—" }
    let a = m.activity
    if a == OpenSkyCore.Activity.slewing { return "GoTo" }
    if a == OpenSkyCore.Activity.tracking { return "Siguiendo" }
    if a == OpenSkyCore.Activity.manual { return "Pad" }
    if a == OpenSkyCore.Activity.idle { return "Quieta" }
    return "—"
}

/// Cómo conectar la montura desde un iPhone.
struct ConnectHelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Wi-Fi de la montura") {
                    Text("1. Enciende la Virtuoso GTi y espera unos segundos.")
                    Text("2. En Configuración › Wi-Fi, únete a la red «SynScan_…» (de fábrica no tiene contraseña).")
                    Text("3. Vuelve a OpenSky y pulsa «Conectar por la Wi-Fi de la montura».")
                    Text("La primera vez iOS pide permiso para la «red local»: acéptalo, sin él la app no puede hablar con la montura.")
                }
                Section("Simulador") {
                    Text("Prueba la app sin la montura: una montura simulada responde a los mismos comandos.")
                }
                Section("Con la pantalla bloqueada") {
                    Text("Mientras la montura está conectada, la app sigue corrigiendo el seguimiento con la pantalla bloqueada (iOS muestra el indicador azul de ubicación). La Live Activity tiene un botón DETENER.")
                }
            }
            .navigationTitle("Cómo conectar")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() } }
            }
        }
    }
}
