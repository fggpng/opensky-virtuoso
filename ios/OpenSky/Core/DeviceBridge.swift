import CoreLocation
import CoreMotion
import Darwin
import Foundation
import OpenSkyCore

/// Servicios del iPhone para el núcleo: sensores (CoreMotion), ubicación y declinación magnética
/// (CoreLocation) e IP local. Implementa el protocolo `DeviceServices` de Kotlin.
final class DeviceBridge: NSObject, DeviceServices, CLLocationManagerDelegate {
    private let motion = CMMotionManager()
    private let motionQueue = OperationQueue()
    private let locationManager = CLLocationManager()
    private weak var graph: AppGraph?
    private let lock = NSLock()
    private var declination: Double?
    private var keepAlive = false
    private var backgroundSession: CLBackgroundActivitySession?

    override init() {
        super.init()
        motionQueue.name = "OpenSky.motion"
        motionQueue.maxConcurrentOperationCount = 1
        motion.deviceMotionUpdateInterval = 1.0 / 30.0
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        locationManager.headingFilter = 1
    }

    func attach(graph: AppGraph) {
        self.graph = graph
        startLocationIfAuthorized()
    }

    // MARK: - DeviceServices

    var motionAvailable: Bool {
        motion.isDeviceMotionAvailable && CMMotionManager.availableAttitudeReferenceFrames().contains(.xMagneticNorthZVertical)
    }

    func startMotion() {
        DispatchQueue.main.async { [self] in
            guard motionAvailable, !motion.isDeviceMotionActive else { return }
            motion.startDeviceMotionUpdates(using: .xMagneticNorthZVertical, to: motionQueue) { [weak self] data, _ in
                guard let data, let engine = self?.graph?.orientation else { return }
                let g = data.gravity
                let f = data.magneticField
                engine.onSample(
                    gx: g.x, gy: g.y, gz: g.z,
                    mx: f.field.x, my: f.field.y, mz: f.field.z,
                    accuracy: DeviceBridge.accuracy(f.accuracy)
                )
            }
            if CLLocationManager.headingAvailable() { locationManager.startUpdatingHeading() }
        }
    }

    func stopMotion() {
        DispatchQueue.main.async { [self] in
            motion.stopDeviceMotionUpdates()
        }
    }

    func requestLocation() {
        DispatchQueue.main.async { [self] in
            switch locationManager.authorizationStatus {
            case .notDetermined:
                locationManager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse, .authorizedAlways:
                locationManager.requestLocation()
                locationManager.startUpdatingLocation()
            default:
                break
            }
        }
    }

    func magneticDeclinationDeg() -> KotlinDouble? {
        lock.lock(); defer { lock.unlock() }
        return declination.map { KotlinDouble(value: $0) }
    }

    func localIpv4Addresses() -> [String] {
        var result: [String] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ptr = cursor {
            let ifa = ptr.pointee
            cursor = ifa.ifa_next
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
                let name = String(cString: ifa.ifa_name)
                result.append("\(ip) (\(name))")
            }
        }
        return result
    }

    // MARK: - Sesión con la pantalla bloqueada

    /// Con la montura conectada, las actualizaciones de ubicación en segundo plano mantienen viva la
    /// app (el seguimiento sigue corrigiendo con la pantalla bloqueada; iOS muestra el aviso azul).
    func setKeepAlive(_ on: Bool) {
        guard on != keepAlive else { return }
        keepAlive = on
        if on {
            guard isAuthorized else { return }
            backgroundSession = CLBackgroundActivitySession()
            locationManager.allowsBackgroundLocationUpdates = true
            locationManager.pausesLocationUpdatesAutomatically = false
            locationManager.showsBackgroundLocationIndicator = true
            locationManager.startUpdatingLocation()
        } else {
            backgroundSession?.invalidate()
            backgroundSession = nil
            if isAuthorized { locationManager.allowsBackgroundLocationUpdates = false }
        }
    }

    private var isAuthorized: Bool {
        let s = locationManager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    private func startLocationIfAuthorized() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            locationManager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { locationManager.startUpdatingHeading() }
        default:
            break
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        startLocationIfAuthorized()
        if keepAlive {
            keepAlive = false
            setKeepAlive(true)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, loc.horizontalAccuracy >= 0 else { return }
        graph?.location.onFix(
            latitudeDeg: loc.coordinate.latitude,
            longitudeDeg: loc.coordinate.longitude,
            altitudeM: loc.verticalAccuracy >= 0 ? loc.altitude : 0,
            accuracyM: loc.horizontalAccuracy,
            timeMillis: Int64(loc.timestamp.timeIntervalSince1970 * 1000)
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Sin fix por ahora (interior, permiso denegado…): se reintenta con la siguiente lectura.
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.trueHeading >= 0, newHeading.headingAccuracy >= 0 else { return }
        var d = newHeading.trueHeading - newHeading.magneticHeading
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        lock.lock(); declination = d; lock.unlock()
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        true
    }

    // MARK: -

    private static func accuracy(_ a: CMMagneticFieldCalibrationAccuracy) -> CompassAccuracy {
        switch a {
        case .high: return CompassAccuracy.high
        case .medium: return CompassAccuracy.medium
        case .low: return CompassAccuracy.low
        default: return CompassAccuracy.unreliable
        }
    }
}
