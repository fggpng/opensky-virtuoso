import Observation
import OpenSkyCore

/// Estado de una pantalla del núcleo Kotlin, observable desde SwiftUI. El núcleo avisa en el hilo
/// principal (Dispatchers.Main), así que basta con asumir el MainActor.
@MainActor
@Observable
final class Screen<S: AnyObject> {
    private(set) var value: S
    @ObservationIgnored private var watch: Watch?

    init(_ watcher: StateWatcher<S>) {
        value = watcher.value
        watch = watcher.watch { [weak self] newValue in
            MainActor.assumeIsolated { self?.value = newValue }
        }
    }
}
