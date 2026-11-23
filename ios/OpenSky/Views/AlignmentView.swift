import OpenSkyCore
import SwiftUI

/// Alinear: elegir modo, ir a cada estrella (o a la Luna), centrarla y confirmar.
struct AlignmentView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.alignment.value
        let model = host.graph.alignmentScreen
        let a = s.alignment
        List {
            if s.mount == nil {
                Section { WarningRow(text: "Conecta la montura en Inicio para alinear.") }
            }
            if let mode = a.mode {
                progress(s, mode)
                if a.isMoonStep {
                    moonStep(s, model)
                } else if mode == AlignmentMode.brightestOne || mode == AlignmentMode.brightestTwo {
                    brightest(s, model)
                } else if !a.isComplete {
                    starList(s, model)
                }
                if !a.alignmentSamples.isEmpty || !a.syncSamples.isEmpty {
                    samples(a)
                }
            }
            modes(s, model)
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Alinear")
        .toolbar {
            if a.mode != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Reiniciar", role: .destructive) { model.reset() }
                }
            }
        }
        .alert(
            "¿Seguro que es esa estrella?",
            isPresented: Binding(get: { s.doubtful != nil }, set: { if !$0 { model.dismissDoubtful() } }),
            presenting: s.doubtful
        ) { _ in
            Button("Registrar de todos modos", role: .destructive) { model.confirmCentered(force: true) }
            Button("Revisar", role: .cancel) { model.dismissDoubtful() }
        } message: { d in
            Text(String(format: "El tubo está a %.1f° de donde el modelo esperaba %@ (límite %.1f°). Lo más común es haber centrado otra estrella.", d.deviationDeg, d.star.name, d.limitDeg))
        }
        .messageBanner(s.message) { model.clearMessage() }
    }

    // MARK: Progreso

    @ViewBuilder private func progress(_ s: AlignmentScreenState, _ mode: AlignmentMode) -> some View {
        let a = s.alignment
        Section {
            let done = a.alignmentSamples.count
            LabeledContent("Progreso", value: "\(done)/\(mode.starsRequired) · \(mode.label)")
            if a.isComplete {
                Label("Alineación completa", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
            }
            if let rms = a.quality.rmsArcmin.double {
                LabeledContent("Error RMS", value: String(format: "%.1f′", rms))
            }
            if let p = a.quality.precisionPercent.double {
                LabeledContent("Precisión", value: String(format: "%.1f %%", p))
            }
            Hint(a.quality.method)
        }
    }

    // MARK: Luna + 1 estrella

    @ViewBuilder private func moonStep(_ s: AlignmentScreenState, _ model: AlignmentModel) -> some View {
        Section {
            if let moon = s.moon {
                if moon.usable, let h = moon.horizontal {
                    StatGrid(stats: [
                        Stat(label: "Altura", value: deg(h.altDeg)),
                        Stat(label: "Acimut", value: azimuthText(h.azDeg), detail: cardinal(h.azDeg)),
                        Stat(label: "Iluminada", value: moon.illuminatedPercent.double.map { String(format: "%.0f %%", $0) } ?? "—"),
                    ])
                } else if let reason = moon.unavailableReason {
                    WarningRow(text: reason)
                }
            } else {
                ProgressView()
            }
            Button { model.gotoMoon() } label: { Label("GoTo a la Luna", systemImage: "moon") }
                .disabled(s.busy || s.mount == nil)
            Hint("Si no aparece en el ocular, búscala a simple vista y llévala al centro con el mando. Centra el disco completo, no un borde.")
            ProminentButton(title: "Luna centrada en el ocular/cámara ✓", systemImage: "checkmark") {
                model.confirmCentered(force: false)
            }
            .disabled(s.busy || s.mount == nil)
        } header: {
            Text("Paso 1: la Luna")
        }
    }

    // MARK: Estrella más brillante

    @ViewBuilder private func brightest(_ s: AlignmentScreenState, _ model: AlignmentModel) -> some View {
        Section {
            Hint("Apunta a mano (con el mando) a la estrella más brillante de una zona del cielo y pulsa «Identificar».")
            Button { model.identifyBrightest() } label: { Label("Identificar", systemImage: "sparkle.magnifyingglass") }
                .disabled(s.mount == nil)
            if let id = s.identified {
                LabeledContent(id.star.name, value: String(format: "a %.1f° del tubo", id.separationDeg))
                ProminentButton(title: "Estrella centrada ✓", systemImage: "checkmark") { model.confirmCentered(force: false) }
                    .disabled(s.busy)
            }
        } header: {
            Text("Estrella más brillante")
        }
    }

    // MARK: Lista de estrellas

    @ViewBuilder private func starList(_ s: AlignmentScreenState, _ model: AlignmentModel) -> some View {
        Section {
            if s.suggestions.isEmpty {
                Hint("Sin sugerencias todavía (hace falta la ubicación).")
            }
            ForEach(s.suggestions, id: \.star.id) { sug in
                Button {
                    model.gotoStar(star: sug.star)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(sug.star.name).font(.body.weight(.semibold))
                            Text(sug.star.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("Alt " + deg(sug.horizontal.altDeg, decimals: 0)).monospacedDigit().foregroundStyle(.tint)
                            Text("Az \(azimuthText(sug.horizontal.azDeg)) \(cardinal(sug.horizontal.azDeg))")
                                .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                        if s.current?.id == sug.star.id {
                            Image(systemName: "scope").foregroundStyle(.tint)
                        }
                    }
                    .rowLabel()
                }
                .disabled(s.busy || s.mount == nil)
            }
            Button("Actualizar la lista") { model.refreshSuggestions() }
            if let current = s.current {
                ProminentButton(title: "\(current.name) centrada ✓", systemImage: "checkmark") { model.confirmCentered(force: false) }
                    .disabled(s.busy)
            }
        } header: {
            Text(s.alignment.alignmentSamples.isEmpty ? "Elige una estrella" : "Siguiente estrella")
        } footer: {
            Text("La app hace GoTo a la estrella; céntrala con el mando (pestaña Mando) y confirma.")
        }
    }

    // MARK: Puntos registrados

    @ViewBuilder private func samples(_ a: AlignmentState) -> some View {
        Section("Puntos registrados") {
            ForEach(Array(a.alignmentSamples.enumerated()), id: \.offset) { _, sample in
                LabeledContent(sample.name, value: "Alt \(deg(sample.sky.altDeg)) · Az \(azimuthText(sample.sky.azDeg, decimals: 1))")
            }
            ForEach(Array(a.syncSamples.enumerated()), id: \.offset) { _, sample in
                LabeledContent("Sync · \(sample.name)", value: "Alt " + deg(sample.sky.altDeg))
            }
        }
    }

    // MARK: Modos

    @ViewBuilder private func modes(_ s: AlignmentScreenState, _ model: AlignmentModel) -> some View {
        Section {
            ForEach(s.modes, id: \.self) { mode in
                Button {
                    model.begin(mode: mode)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(mode.label).font(.body.weight(.semibold))
                            if s.alignment.mode == mode { Spacer(); Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                        Text(mode.details).font(.caption).foregroundStyle(.secondary)
                    }
                    .rowLabel()
                }
                .disabled(s.mount == nil)
            }
        } header: {
            Text(s.alignment.mode == nil ? "Elige el modo de alineación" : "Cambiar de modo")
        }
    }
}
