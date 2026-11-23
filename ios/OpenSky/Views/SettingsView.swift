import OpenSkyCore
import SwiftUI
import UIKit

/// Ajustes: conexión, seguridad, lugar, hora, atmósfera y fuentes de datos (los mismos que en Android).
struct SettingsView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.more.value.settings
        let model = host.graph.more
        Form {
            Section {
                SettingField(title: "Dirección", key: "mount_host", value: s.mountHost, keyboard: .numbersAndPunctuation)
                SettingField(title: "Puerto", key: "mount_port", value: "\(s.mountPort)", keyboard: .numberPad)
                Picker("Transporte", selection: Binding(get: { s.transport.name }, set: { model.setSetting(key: "transport", value: $0) })) {
                    Text("UDP (Wi-Fi de la montura)").tag("UDP")
                    Text("TCP (puente serie-Wi-Fi)").tag("TCP")
                }
                .nightAwarePickerStyle(night)
                SettingField(title: "Red Wi-Fi", key: "ssid_prefix", value: s.ssidPrefix)
            } header: {
                Text("Conexión")
            } footer: {
                Text("La Virtuoso GTi de fábrica usa UDP en 192.168.4.1:11880.")
            }

            Section {
                SettingField(title: "Altura mínima (°)", key: "min_alt", value: num(s.minAltitudeDeg), keyboard: .numbersAndPunctuation)
                SettingField(title: "Altura máxima (°)", key: "max_alt", value: num(s.maxAltitudeDeg), keyboard: .decimalPad)
                SettingField(title: "Distancia al Sol (°)", key: "sun_avoid", value: num(s.sunAvoidanceDeg), keyboard: .decimalPad)
                SettingField(title: "Velocidad GoTo (°/s)", key: "max_rate", value: num(s.maxSlewRateDegPerSec), keyboard: .decimalPad)
                Toggle("Invertir acimut", isOn: bool(s.invertAzimuth, "inv_az"))
                Toggle("Invertir altura", isOn: bool(s.invertAltitude, "inv_alt"))
            } header: {
                Text("Seguridad y mecánica")
            }

            Section {
                Toggle("Coordenadas manuales", isOn: bool(s.useManualLocation, "manual_loc"))
                if s.useManualLocation {
                    SettingField(title: "Latitud (°)", key: "lat", value: num(s.manualLatitude, 5), keyboard: .numbersAndPunctuation)
                    SettingField(title: "Longitud (°, oeste −)", key: "lon", value: num(s.manualLongitude, 5), keyboard: .numbersAndPunctuation)
                    SettingField(title: "Altitud (m)", key: "elev", value: num(s.manualElevationM, 0), keyboard: .numbersAndPunctuation)
                }
            } header: {
                Text("Lugar")
            } footer: {
                Text("Sin coordenadas manuales se usa el GPS del iPhone.")
            }

            Section {
                Toggle("Zona horaria del iPhone", isOn: bool(s.useSystemTimeZone, "sys_tz"))
                if !s.useSystemTimeZone {
                    SettingField(title: "UTC + (horas)", key: "utc_offset", value: num(s.manualUtcOffsetHours), keyboard: .numbersAndPunctuation)
                    Toggle("Horario de verano", isOn: bool(s.dstEnabled, "dst"))
                }
                SettingField(title: "Servidor NTP", key: "ntp", value: s.ntpServer, keyboard: .URL)
            } header: {
                Text("Hora")
            }

            Section {
                SettingField(title: "Presión (hPa)", key: "pressure", value: num(s.pressureHpa, 0), keyboard: .decimalPad)
                SettingField(title: "Temperatura (°C)", key: "temp", value: num(s.temperatureC), keyboard: .numbersAndPunctuation)
            } header: {
                Text("Atmósfera (refracción)")
            }

            Section {
                SettingField(title: "Barlow (×)", key: "barlow_factor", value: num(s.barlowFactor), keyboard: .decimalPad)
                SettingField(title: "Cometas (MPC)", key: "comet_url", value: s.cometUrl, keyboard: .URL)
            } header: {
                Text("Equipo y datos")
            }
        }
        .screenBackground(night)
        .keyboardDoneButton()
        .navigationTitle("Ajustes")
    }

    private func num(_ v: Double, _ decimals: Int = 1) -> String {
        String(format: "%.\(decimals)f", v)
    }

    private func bool(_ value: Bool, _ key: String) -> Binding<Bool> {
        Binding(get: { value }, set: { host.graph.more.setSetting(key: key, value: $0 ? "true" : "false") })
    }
}
