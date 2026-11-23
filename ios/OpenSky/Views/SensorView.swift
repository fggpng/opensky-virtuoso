import OpenSkyCore
import SwiftUI

/// Orientación con los sensores del iPhone apoyado en el tubo: inicializa los encoders.
struct SensorView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.sensors.value
        let model = host.graph.sensors
        List {
            if !s.available {
                Section { WarningRow(text: "Este dispositivo no tiene brújula o sensores de movimiento.") }
            }
            Section {
                if let o = s.orientation {
                    StatGrid(stats: [
                        Stat(label: "Acimut", value: azimuthText(o.trueAzimuthDeg, decimals: 1), detail: cardinal(o.trueAzimuthDeg)),
                        Stat(label: "Altura", value: deg(o.altitudeDeg)),
                    ])
                    LabeledContent("Acimut magnético", value: azimuthText(o.magneticAzimuthDeg, decimals: 1))
                    LabeledContent("Declinación", value: signedDeg(o.declinationDeg, decimals: 1))
                    LabeledContent("Brújula", value: o.accuracyLabel)
                    if let f = o.fieldStrengthUt.double {
                        LabeledContent("Campo magnético", value: String(format: "%.0f µT", f))
                    }
                    if o.interference {
                        WarningRow(text: "Interferencia magnética (metal, motores o imanes cerca): el acimut puede tener varios grados de error.")
                    }
                } else {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Esperando a los sensores…")
                    }
                }
            } header: {
                Text("El tubo apunta a")
            }
            Section {
                Picker("Cómo está el teléfono", selection: Binding(
                    get: { s.mounting },
                    set: { model.setMounting(m: $0) }
                )) {
                    ForEach(s.mountings, id: \.self) { m in
                        Text(m.label).tag(m)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Cómo está el teléfono")
            }
            Section {
                Button {
                    model.applyInitialOrientation()
                } label: {
                    HStack {
                        Label("Usar esta orientación como inicio", systemImage: "scope")
                        if s.busy { Spacer(); ProgressView() }
                    }
                }
                .disabled(s.busy || s.mount == nil)
            } footer: {
                Text("Deja el teléfono quieto sobre el tubo 3 s. La altura es fiable (±0.5°); el acimut de la brújula puede errar varios grados cerca del tubo de acero: la alineación con estrellas lo corrige.")
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Sensores")
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .messageBanner(s.message) { model.clearMessage() }
    }
}
