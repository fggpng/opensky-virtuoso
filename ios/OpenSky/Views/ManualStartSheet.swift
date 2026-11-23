import OpenSkyCore
import SwiftUI

/// Posición de inicio escrita a mano: rumbo de la brújula (o de una app de cielo) y, si se quiere,
/// la altura medida con el teléfono apoyado en el tubo.
struct ManualStartSheet: View {
    @Environment(AppHost.self) private var host
    @Environment(\.dismiss) private var dismiss
    @State private var azText = ""
    @State private var referenceIndex = 0
    @State private var measure = true
    @FocusState private var focused: Bool

    var body: some View {
        let s = host.home.value
        let model = host.graph.home
        NavigationStack {
            Form {
                if let p = s.manualPreview {
                    preview(p)
                } else {
                    Section {
                        TextField("Rumbo (0–360°)", text: $azText)
                            .keyboardType(.decimalPad)
                            .focused($focused)
                            .font(.title2.monospacedDigit())
                        Picker("Medido con", selection: $referenceIndex) {
                            ForEach(Array(s.headingReferences.enumerated()), id: \.offset) { index, ref in
                                Text(ref.label).tag(index)
                            }
                        }
                        Toggle("Medir la altura con el teléfono apoyado en el tubo", isOn: $measure)
                    } header: {
                        Text("Hacia dónde apunta el tubo")
                    } footer: {
                        Text("Con una brújula, la app corrige la declinación magnética del sitio. Las apps de mapa del cielo (Stellarium, Star Walk…) ya dan el rumbo respecto al norte verdadero.")
                    }
                    if let error = s.manualError {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                    Section {
                        Button {
                            focused = false
                            let ref = s.headingReferences[min(referenceIndex, s.headingReferences.count - 1)]
                            model.prepareManualStart(azText: azText, reference: ref, measureAltitude: measure)
                        } label: {
                            HStack {
                                Text("Revisar")
                                if s.checking { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(s.checking)
                    }
                }
            }
            .navigationTitle("Escribir la posición")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") {
                        model.cancelManualStart()
                        dismiss()
                    }
                }
            }
        }
        .onAppear { focused = true }
    }

    @ViewBuilder private func preview(_ p: ManualStartPreview) -> some View {
        let model = host.graph.home
        Section {
            LabeledContent("Escrito", value: String(format: "%.1f° (%@)", p.inputAzDeg, p.reference.northName))
            if let d = p.declinationDeg.double, p.reference == HeadingReference.magnetic {
                LabeledContent("Declinación", value: String(format: "%+.1f°", d))
            }
            LabeledContent("Acimut verdadero", value: String(format: "%.1f° (%@)", p.trueAzDeg, cardinal(p.trueAzDeg)))
            LabeledContent("Altura", value: String(format: "%.1f° %@", p.altDeg, p.altitudeMeasured ? "(medida)" : "(horizontal)"))
            if let phone = p.phoneAzDeg.double {
                LabeledContent("Brújula del teléfono", value: String(format: "%.0f°", phone))
                if let diff = p.phoneDisagreementDeg.double {
                    LabeledContent("Diferencia", value: String(format: "%.0f°", diff))
                }
                if p.phoneInterference {
                    WarningRow(text: "Interferencia magnética cerca del teléfono: su brújula no es fiable aquí.")
                }
            }
            if p.suspicious {
                WarningRow(text: "El teléfono marca algo muy distinto de lo que escribiste. Revisa el número antes de aplicar.")
            }
        } header: {
            Text("Revisa antes de aplicar")
        }
        Section {
            Button {
                model.applyManualStart()
                dismiss()
            } label: {
                Label("Aplicar", systemImage: "checkmark.circle.fill")
            }
            Button("Corregir") { model.editManualStart() }
        } footer: {
            Text("Después afina en Alinear: con estrellas o con «Luna + 1 estrella», si la Luna está a la vista.")
        }
    }
}
