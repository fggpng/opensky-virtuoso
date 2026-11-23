import ActivityKit
import Foundation

/// Live Activity de la sesión con la montura (pantalla bloqueada y Dynamic Island).
/// Se comparte entre la app y la extensión de widgets.
struct MountActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// «Siguiendo», «GoTo en curso», «Quieta»…
        var status: String
        /// Símbolo SF del estado.
        var symbol: String
        var target: String?
        var altitudeDeg: Double?
        var azimuthDeg: Double?
        var moving: Bool
        var alert: String?
    }

    var mountName: String
}

extension MountActivityAttributes.ContentState {
    /// «Alt 42.1° · Az 128°» o nil sin posición.
    var positionText: String? {
        guard let alt = altitudeDeg, let az = azimuthDeg else { return nil }
        return String(format: "Alt %.1f° · Az %.0f°", alt, az)
    }
}
