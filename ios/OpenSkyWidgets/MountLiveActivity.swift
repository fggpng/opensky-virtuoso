import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// Live Activity: pantalla bloqueada y Dynamic Island (iPhone 16 Pro Max) con el estado de la
/// montura y un botón DETENER que actúa sin abrir la app.
struct MountLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MountActivityAttributes.self) { context in
            let ink = Ink(night: context.state.night)
            LockScreenView(context: context)
                .activityBackgroundTint(context.state.night ? Color.black : Color.black.opacity(0.55))
                .activitySystemActionForegroundColor(ink.primary)
        } dynamicIsland: { context in
            let ink = Ink(night: context.state.night)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.state.status, systemImage: context.state.symbol)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(ink.primary)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let pos = context.state.positionText {
                        Text(pos).font(.caption.monospacedDigit()).foregroundStyle(ink.primary).lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.target ?? context.attributes.mountName)
                        .font(.headline)
                        .foregroundStyle(ink.primary)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        if let alert = context.state.alert {
                            Label(alert, systemImage: "exclamationmark.triangle.fill")
                                .font(.caption)
                                .foregroundStyle(ink.warning)
                                .lineLimit(2)
                        }
                        Spacer()
                        StopButton()
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.symbol)
                    .foregroundStyle(context.state.moving ? ink.active : ink.secondary)
            } compactTrailing: {
                if let alt = context.state.altitudeDeg {
                    Text(String(format: "%.0f°", alt.rounded() == 0 ? 0 : alt))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(ink.primary)
                } else {
                    Text(context.state.moving ? "●" : "○").foregroundStyle(ink.primary)
                }
            } minimal: {
                Image(systemName: context.state.symbol)
                    .foregroundStyle(context.state.moving ? ink.active : ink.secondary)
            }
            .keylineTint(.red)
        }
    }
}

/// Colores: los del sistema de día; de noche (modo nocturno de la app), solo rojo.
private struct Ink {
    let night: Bool
    private static let red = Color(red: 1, green: 0.1, blue: 0.08)
    private static let dimRed = Color(red: 0.62, green: 0.05, blue: 0.04)

    var primary: Color { night ? Self.red : .white }
    var secondary: Color { night ? Self.dimRed : .white.opacity(0.65) }
    var active: Color { night ? Self.red : .green }
    var warning: Color { night ? Self.red : .orange }
}

private struct LockScreenView: View {
    let context: ActivityViewContext<MountActivityAttributes>

    var body: some View {
        let ink = Ink(night: context.state.night)
        HStack(spacing: 14) {
            Image(systemName: context.state.symbol)
                .font(.title2)
                .foregroundStyle(context.state.moving ? ink.active : ink.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(context.state.target ?? context.attributes.mountName)
                    .font(.headline)
                    .foregroundStyle(ink.primary)
                    .lineLimit(1)
                Text(context.state.status).font(.subheadline).foregroundStyle(ink.primary)
                if let pos = context.state.positionText {
                    Text(pos).font(.caption.monospacedDigit()).foregroundStyle(ink.secondary)
                }
                if let alert = context.state.alert {
                    Text(alert).font(.caption).foregroundStyle(ink.warning).lineLimit(2)
                }
            }
            Spacer()
            StopButton()
        }
        .padding()
    }
}

/// DETENER desde la Live Activity: el intent se ejecuta en la app (LiveActivityIntent).
private struct StopButton: View {
    var body: some View {
        Button(intent: StopMountIntent()) {
            Label("Detener", systemImage: "stop.fill")
                .font(.subheadline.weight(.bold))
        }
        .tint(.red)
    }
}
