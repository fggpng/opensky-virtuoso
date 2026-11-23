import OpenSkyCore
import SwiftUI

/// Mando: cruceta con pulsar/soltar, velocidad, modo, seguimiento y DETENER.
struct ControlView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.control.value
        let model = host.graph.control
        ScrollView {
            VStack(spacing: 20) {
                status(s)
                HandPad(
                    labels: s.padMode == PadMode.raDec ? ("N", "S", "E", "O") : ("Arriba", "Abajo", "Izq.", "Der."),
                    onPress: { model.pressPad(direction: $0) },
                    onRelease: { model.releasePad() },
                    onStop: { model.stopAll() }
                )
                .frame(maxWidth: 360)
                .disabled(s.mount == nil)
                rate(s, model)
                tracking(s, model)
                Button(role: .destructive) {
                    model.stopAll()
                } label: {
                    Label("DETENER TODO", systemImage: "stop.fill")
                        .font(.title3.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                }
                .buttonStyle(.glassProminent)
                .tint(.red)
            }
            .padding()
        }
        .background(night ? Color.black : Color(.systemGroupedBackground))
        .navigationTitle("Mando")
        .messageBanner(s.message) { model.clearMessage() }
        .onDisappear { model.releasePad() }
    }

    @ViewBuilder private func status(_ s: ControlState) -> some View {
        VStack(spacing: 6) {
            if let m = s.mount {
                StatGrid(stats: [
                    Stat(label: "Altura", value: deg(m.pointing?.altDeg)),
                    Stat(label: "Acimut", value: azimuthText(m.pointing?.azDeg), detail: m.pointing.map { cardinal($0.azDeg) }),
                    Stat(label: "Estado", value: activityText(m)),
                ])
                if let target = m.target {
                    Text(target.name).font(.headline)
                }
                if let tracking = m.tracking {
                    Label("Seguimiento \(tracking.label.lowercased())", systemImage: "scope").font(.subheadline)
                }
                if let exp = s.maxExposureSec.double {
                    Hint("Exposición máxima aquí por rotación de campo: \(FieldRotation.shared.format(seconds: KotlinDouble(value: exp)))")
                }
                if let msg = m.message {
                    Hint(msg)
                }
            } else {
                WarningRow(text: "Conecta la montura en Inicio para moverla.")
            }
        }
        .padding()
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
    }

    @ViewBuilder private func rate(_ s: ControlState, _ model: ControlModel) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            let rates = s.padRates.map { $0.doubleValue }
            let index = Int(s.padRateIndex)
            HStack {
                Text("Velocidad").font(.headline)
                Spacer()
                Text(index < rates.count ? String(format: "×%.0f", rates[index]) : "—").font(.headline.monospacedDigit())
            }
            Slider(
                value: Binding(get: { Double(index) }, set: { model.setRate(index: Int32($0.rounded())) }),
                in: 0...Double(max(rates.count - 1, 1)),
                step: 1
            )
            Picker("Modo", selection: Binding(get: { s.padMode }, set: { model.setMode(mode: $0) })) {
                Text("Alt-Az").tag(PadMode.altAz)
                Text("AR-Dec").tag(PadMode.raDec)
            }
            .pickerStyle(.segmented)
        }
        .padding()
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
    }

    @ViewBuilder private func tracking(_ s: ControlState, _ model: ControlModel) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Seguimiento").font(.headline)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(s.trackingRates, id: \.self) { rate in
                    TrackingButton(title: rate.label, active: s.mount?.tracking == rate) {
                        model.startTracking(rate: rate)
                    }
                }
            }
            Button { model.stopTracking() } label: {
                Text("Detener seguimiento").frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .disabled(s.mount?.tracking == nil)
        }
        .padding()
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .disabled(s.mount == nil)
    }
}

/// Velocidad de seguimiento; la activa, destacada.
private struct TrackingButton: View {
    let title: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        if active {
            Button(action: action) { label }
                .buttonStyle(.glassProminent)
                .accessibilityAddTraits(.isSelected)
        } else {
            Button(action: action) { label }
                .buttonStyle(.glass)
        }
    }

    private var label: some View {
        Text(title).frame(maxWidth: .infinity)
    }
}

/// Cruceta: cada flecha mueve mientras se mantiene pulsada; el centro detiene.
struct HandPad: View {
    let labels: (String, String, String, String)
    let onPress: (PadDirection) -> Void
    let onRelease: () -> Void
    let onStop: () -> Void

    var body: some View {
        Grid(horizontalSpacing: 14, verticalSpacing: 14) {
            GridRow {
                Color.clear.frame(width: 1, height: 1)
                PadButton(systemImage: "chevron.up", label: labels.0, direction: PadDirection.up, onPress: onPress, onRelease: onRelease)
                Color.clear.frame(width: 1, height: 1)
            }
            GridRow {
                PadButton(systemImage: "chevron.left", label: labels.2, direction: PadDirection.left, onPress: onPress, onRelease: onRelease)
                Button(action: onStop) {
                    Image(systemName: "stop.fill")
                        .font(.title.weight(.bold))
                        .frame(width: 84, height: 84)
                }
                .buttonStyle(.glassProminent)
                .tint(.red)
                .clipShape(Circle())
                .accessibilityLabel("Detener")
                PadButton(systemImage: "chevron.right", label: labels.3, direction: PadDirection.right, onPress: onPress, onRelease: onRelease)
            }
            GridRow {
                Color.clear.frame(width: 1, height: 1)
                PadButton(systemImage: "chevron.down", label: labels.1, direction: PadDirection.down, onPress: onPress, onRelease: onRelease)
                Color.clear.frame(width: 1, height: 1)
            }
        }
    }
}

/// Flecha del mando: «pulsar» al tocar y «soltar» al levantar el dedo (o si el gesto se cancela).
struct PadButton: View {
    let systemImage: String
    let label: String
    let direction: PadDirection
    let onPress: (PadDirection) -> Void
    let onRelease: () -> Void
    @State private var pressed = false

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: systemImage).font(.title.weight(.bold))
            Text(label).font(.caption2.weight(.semibold))
        }
        .frame(width: 84, height: 84)
        .glassEffect(pressed ? .regular.tint(.accentColor).interactive() : .regular.interactive(), in: .circle)
        .scaleEffect(pressed ? 0.94 : 1)
        .animation(.snappy(duration: 0.15), value: pressed)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !pressed {
                        pressed = true
                        onPress(direction)
                    }
                }
                .onEnded { _ in
                    if pressed {
                        pressed = false
                        onRelease()
                    }
                }
        )
        .onDisappear {
            if pressed {
                pressed = false
                onRelease()
            }
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: pressed)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isButton)
    }
}
