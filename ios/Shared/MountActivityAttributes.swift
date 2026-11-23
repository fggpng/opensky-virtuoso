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
        /// Modo nocturno de la app: la Live Activity también se dibuja en rojo.
        var night: Bool
    }

    var mountName: String
}

extension MountActivityAttributes.ContentState {
    /// «Alt 42.1° · Az 128°» o nil sin posición.
    var positionText: String? {
        guard let alt = altitudeDeg, let az = azimuthDeg, alt.isFinite, az.isFinite else { return nil }
        var azRounded = az.rounded().truncatingRemainder(dividingBy: 360)
        if azRounded < 0 { azRounded += 360 }
        if azRounded == 0 || azRounded >= 360 { azRounded = 0 }
        let altRounded = (alt * 10).rounded() / 10
        return String(format: "Alt %.1f° · Az %.0f°", altRounded == 0 ? 0 : altRounded, azRounded)
    }
}
