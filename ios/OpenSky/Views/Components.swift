import OpenSkyCore
import SwiftUI
import UIKit

// MARK: - Tema (día / noche roja)

enum Palette {
    /// Rojo del modo nocturno: solo el canal rojo, el que menos borra la adaptación a la oscuridad.
    static let night = Color(red: 1, green: 0, blue: 0)
}

private struct NightModeKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var nightMode: Bool {
        get { self[NightModeKey.self] }
        set { self[NightModeKey.self] = newValue }
    }
}

extension View {
    /// Modo nocturno: la interfaz oscura de siempre pasada a escala de rojos, como el filtro «Tinte de
    /// color» de Accesibilidad. Es un filtro sobre todo lo que dibuja la app, así que también tiñe la
    /// barra de pestañas, los títulos, los interruptores y los deslizadores. La barra de estado (que
    /// iOS no deja teñir) y el indicador de inicio se ocultan.
    func nightTheme(_ on: Bool) -> some View {
        self
            .environment(\.nightMode, on)
            .preferredColorScheme(on ? .dark : nil)
            .grayscale(on ? 1 : 0)
            .brightness(on ? -0.06 : 0) // fondos de las tarjetas casi negros: más contraste, menos luz
            .colorMultiply(on ? Palette.night : .white)
            .statusBarHidden(on)
            .persistentSystemOverlays(on ? .hidden : .automatic)
    }

    /// Fondo de las listas: negro de noche.
    func screenBackground(_ night: Bool) -> some View {
        self
            .scrollContentBackground(night ? .hidden : .automatic)
            .background(night ? Color.black : Color.clear)
    }

    /// Selector en menú de día; de noche, en una lista dentro de la app: los menús los dibuja el
    /// sistema fuera del filtro rojo.
    @ViewBuilder func nightAwarePickerStyle(_ night: Bool) -> some View {
        if night {
            pickerStyle(.navigationLink)
        } else {
            pickerStyle(.menu)
        }
    }

    /// Botón «Listo» sobre el teclado: los teclados numéricos no tienen tecla de retorno.
    func keyboardDoneButton() -> some View {
        self
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Listo") {
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    }
                }
            }
            .scrollDismissesKeyboard(.interactively)
    }
}

// MARK: - Conversión de tipos de Kotlin

extension Optional where Wrapped == KotlinDouble {
    var double: Double? { self?.doubleValue }
}

extension Optional where Wrapped == KotlinFloat {
    var double: Double? { self.map { Double($0.floatValue) } }
}

func deg(_ value: Double?, decimals: Int = 1) -> String {
    guard let value, value.isFinite else { return "—" }
    return unsignedZero(String(format: "%.\(decimals)f°", value))
}

/// Altura con signo («+12°», «-3°»); el cero, sin signo.
func signedDeg(_ value: Double, decimals: Int = 0) -> String {
    guard value.isFinite else { return "—" }
    return unsignedZero(String(format: "%+.\(decimals)f°", value))
}

/// Acimut redondeado dentro de [0°, 360°): nunca «-0°» ni «360°».
func azimuthText(_ value: Double?, decimals: Int = 0) -> String {
    guard let value, value.isFinite else { return "—" }
    let scale = pow(10, Double(decimals))
    var rounded = ((value * scale).rounded() / scale).truncatingRemainder(dividingBy: 360)
    if rounded < 0 { rounded += 360 }
    if rounded == 0 || rounded >= 360 { rounded = 0 }
    return String(format: "%.\(decimals)f°", rounded)
}

/// «-0.0°» → «0.0°»: un valor que se redondea a cero no lleva signo.
private func unsignedZero(_ text: String) -> String {
    guard let first = text.first, first == "-" || first == "+",
          !text.contains(where: { $0 >= "1" && $0 <= "9" }) else { return text }
    return String(text.dropFirst())
}

func cardinal(_ azDeg: Double) -> String {
    ManualHeading.shared.cardinalName(azDeg: azDeg)
}

// MARK: - Piezas de interfaz

/// Filas de lista que son botones (estrellas, objetivos, modos): texto normal en vez del color de
/// acento, y atenuado mientras el botón está desactivado. Usa `Color.primary` (el color del texto):
/// el estilo jerárquico `.primary` dentro de un botón sería el propio color de acento.
private struct RowLabelStyle: ViewModifier {
    @Environment(\.isEnabled) private var enabled

    func body(content: Content) -> some View {
        content.foregroundStyle(enabled ? Color.primary : Color.secondary)
    }
}

extension View {
    func rowLabel() -> some View {
        modifier(RowLabelStyle())
    }
}

/// Indicador de estado en vivo: punto de color y texto.
struct StatusChip: View {
    enum Tone { case ok, busy, off }
    let text: String
    let tone: Tone

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(color)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.14), in: Capsule())
        .accessibilityElement(children: .combine)
    }

    private var color: Color {
        switch tone {
        case .ok: return .green
        case .busy: return .orange
        case .off: return .red
        }
    }
}

/// Dato con rótulo pequeño (cuadrícula de instrumento).
struct Stat: Identifiable {
    let label: String
    let value: String
    var detail: String? = nil
    var id: String { label }
}

struct StatGrid: View {
    let stats: [Stat]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(stats.enumerated()), id: \.offset) { index, stat in
                if index > 0 { Divider().frame(height: 34) }
                VStack(spacing: 3) {
                    Text(stat.label.uppercased())
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Text(stat.value)
                        .font(.title3.weight(.semibold).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    if let detail = stat.detail {
                        Text(detail).font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 10)
    }
}

/// Aviso en una fila (triángulo + texto).
struct WarningRow: View {
    let text: String
    var body: some View {
        Label {
            Text(text).font(.callout)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        }
    }
}

/// Texto de ayuda gris.
struct Hint: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary)
    }
}

/// Mensaje temporal del núcleo (equivalente al «snackbar» de Android).
struct MessageBanner: ViewModifier {
    let message: String?
    let onDismiss: () -> Void
    @State private var visible: String?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let text = visible {
                    Text(text)
                        .font(.callout)
                        .multilineTextAlignment(.leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .glassEffect(.regular, in: .rect(cornerRadius: 18))
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .onTapGesture { dismiss() }
                }
            }
            .animation(.snappy, value: visible)
            .task(id: message) {
                guard let message else { return }
                visible = message
                try? await Task.sleep(for: .seconds(5))
                if visible == message { dismiss() }
            }
    }

    private func dismiss() {
        visible = nil
        onDismiss()
    }
}

extension View {
    func messageBanner(_ message: String?, onDismiss: @escaping () -> Void) -> some View {
        modifier(MessageBanner(message: message, onDismiss: onDismiss))
    }
}

/// Botón grande y destacado de ancho completo.
struct ProminentButton: View {
    let title: String
    var systemImage: String? = nil
    var role: ButtonRole? = nil
    let action: () -> Void

    var body: some View {
        Button(role: role, action: action) {
            Group {
                if let systemImage {
                    Label(title, systemImage: systemImage)
                } else {
                    Text(title)
                }
            }
            .font(.body.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
        .buttonStyle(.glassProminent)
    }
}
