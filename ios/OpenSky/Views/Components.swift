import OpenSkyCore
import SwiftUI

// MARK: - Tema (día / noche roja)

enum Palette {
    /// Rojo de visión nocturna: no deslumbra ni borra la adaptación a la oscuridad.
    static let nightRed = Color(red: 0.92, green: 0.16, blue: 0.12)
    static let nightDim = Color(red: 0.55, green: 0.08, blue: 0.06)
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
    /// Modo nocturno: todo en rojo sobre negro.
    func nightTheme(_ on: Bool) -> some View {
        self
            .environment(\.nightMode, on)
            .tint(on ? Palette.nightRed : Color.accentColor)
            .foregroundStyle(on ? AnyShapeStyle(Palette.nightRed) : AnyShapeStyle(.primary))
            .preferredColorScheme(on ? .dark : nil)
    }

    /// Fondo de las listas: negro de noche.
    func screenBackground(_ night: Bool) -> some View {
        self
            .scrollContentBackground(night ? .hidden : .automatic)
            .background(night ? Color.black : Color.clear)
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
    guard let value else { return "—" }
    return String(format: "%.\(decimals)f°", value)
}

func cardinal(_ azDeg: Double) -> String {
    ManualHeading.shared.cardinalName(azDeg: azDeg)
}

// MARK: - Piezas de interfaz

/// Indicador de estado en vivo: punto de color y texto.
struct StatusChip: View {
    enum Tone { case ok, busy, off }
    let text: String
    let tone: Tone
    @Environment(\.nightMode) private var night

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
        if night { return tone == .off ? Palette.nightDim : Palette.nightRed }
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
