import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// Live Activity: pantalla bloqueada y Dynamic Island (iPhone 16 Pro Max) con el estado de la
/// montura y un botón DETENER que actúa sin abrir la app.
struct MountLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MountActivityAttributes.self) { context in
            LockScreenView(context: context)
                .activityBackgroundTint(Color.black.opacity(0.55))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.state.status, systemImage: context.state.symbol)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let pos = context.state.positionText {
                        Text(pos).font(.caption.monospacedDigit()).lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.target ?? context.attributes.mountName)
                        .font(.headline)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        if let alert = context.state.alert {
                            Label(alert, systemImage: "exclamationmark.triangle.fill")
                                .font(.caption)
                                .foregroundStyle(.orange)
                                .lineLimit(2)
                        }
                        Spacer()
                        StopButton()
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.symbol)
                    .foregroundStyle(context.state.moving ? Color.green : Color.secondary)
            } compactTrailing: {
                if let alt = context.state.altitudeDeg {
                    Text(String(format: "%.0f°", alt)).font(.caption.monospacedDigit())
                } else {
                    Text(context.state.moving ? "●" : "○")
                }
            } minimal: {
                Image(systemName: context.state.symbol)
                    .foregroundStyle(context.state.moving ? Color.green : Color.secondary)
            }
            .keylineTint(.red)
        }
    }
}

private struct LockScreenView: View {
    let context: ActivityViewContext<MountActivityAttributes>

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: context.state.symbol)
                .font(.title2)
                .foregroundStyle(context.state.moving ? Color.green : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(context.state.target ?? context.attributes.mountName)
                    .font(.headline)
                    .lineLimit(1)
                Text(context.state.status).font(.subheadline)
                if let pos = context.state.positionText {
                    Text(pos).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                if let alert = context.state.alert {
                    Text(alert).font(.caption).foregroundStyle(.orange).lineLimit(2)
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
