import OpenSkyCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Más: sensores, Stellarium, catálogo Tycho-2, ajustes, diagnóstico y acerca de.
struct MoreView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.more.value
        List {
            Section {
                NavigationLink { SensorView() } label: { Label("Orientación por sensores", systemImage: "iphone.gen3.radiowaves.left.and.right") }
                NavigationLink { StellariumView() } label: { Label("Stellarium y SkySafari", systemImage: "antenna.radiowaves.left.and.right") }
                NavigationLink { CatalogView() } label: { Label("Catálogo Tycho-2", systemImage: "books.vertical") }
            }
            Section {
                NavigationLink { SettingsView() } label: { Label("Ajustes", systemImage: "gearshape") }
                NavigationLink { DiagnosticsView() } label: { Label("Diagnóstico", systemImage: "stethoscope") }
                NavigationLink { AboutView() } label: { Label("Acerca de", systemImage: "info.circle") }
            }
            Section {
                Toggle("Modo nocturno (rojo)", isOn: Binding(
                    get: { s.settings.nightMode },
                    set: { host.graph.more.setSetting(key: "night", value: $0 ? "true" : "false") }
                ))
                Toggle("Mantener la pantalla encendida", isOn: Binding(
                    get: { s.settings.keepScreenOn },
                    set: { host.graph.more.setSetting(key: "screen_on", value: $0 ? "true" : "false") }
                ))
            } footer: {
                Text("De noche la app se ve en rojo y oculta la barra de estado. Los menús y avisos del sistema no se pueden teñir desde la app: para que también queden en rojo, activa Configuración › Accesibilidad › Pantalla y tamaño del texto › Filtros de colores › Tinte de color (tono rojo, intensidad al máximo) y asígnalo a la Función rápida de accesibilidad (triple clic en el botón lateral).")
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Más")
        .messageBanner(s.message) { host.graph.more.clearMessage() }
    }
}

// MARK: - Stellarium

struct StellariumView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.more.value
        let model = host.graph.more
        List {
            Section {
                if s.mount == nil {
                    WarningRow(text: "Los servidores arrancan al conectar la montura.")
                }
                ForEach(s.servers, id: \.name) { server in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            StatusChip(text: server.running ? "Activo" : "Detenido", tone: server.running ? .ok : .off)
                            Text(verbatim: "\(server.name) · puerto \(server.port)").font(.headline)
                        }
                        Text(verbatim: "Clientes conectados: \(server.clients)").font(.caption).foregroundStyle(.secondary)
                        if let err = server.lastError { Text(err).font(.caption).foregroundStyle(.red) }
                    }
                }
                Button("Reiniciar servidores") { model.restartServers() }.disabled(s.mount == nil)
            } header: {
                Text("Servidores")
            }
            Section {
                if s.ipAddresses.isEmpty {
                    Hint("Sin red local.")
                }
                ForEach(s.ipAddresses, id: \.self) { ip in
                    Text(ip).font(.body.monospaced()).textSelection(.enabled)
                }
            } header: {
                Text("Dirección de este iPhone")
            } footer: {
                Text("En Stellarium (otro equipo en la misma Wi-Fi): Telescope Control › «External software or a remote computer», esta IP y el puerto de Stellarium. En SkySafari o Stellarium Mobile: Meade LX200 por Wi-Fi, esta IP y el puerto LX200. La app debe estar abierta en el iPhone.")
            }
            Section {
                Toggle("Servidor LX200", isOn: Binding(get: { s.settings.lx200Enabled }, set: { model.setSetting(key: "lx_on", value: $0 ? "true" : "false") }))
                SettingField(title: "Puerto LX200", key: "lx_port", value: "\(s.settings.lx200Port)", keyboard: .numberPad)
                Picker("Coordenadas LX200", selection: Binding(get: { s.settings.lx200Epoch.name }, set: { model.setSetting(key: "lx_epoch", value: $0) })) {
                    Text("JNow (fecha actual)").tag("JNOW")
                    Text("J2000").tag("J2000")
                }
                .nightAwarePickerStyle(night)
                Toggle("Servidor Stellarium", isOn: Binding(get: { s.settings.stellariumEnabled }, set: { model.setSetting(key: "st_on", value: $0 ? "true" : "false") }))
                SettingField(title: "Puerto Stellarium", key: "st_port", value: "\(s.settings.stellariumPort)", keyboard: .numberPad)
                Picker("Coordenadas Stellarium", selection: Binding(get: { s.settings.stellariumEpoch.name }, set: { model.setSetting(key: "st_epoch", value: $0) })) {
                    Text("J2000").tag("J2000")
                    Text("JNow (fecha actual)").tag("JNOW")
                }
                .nightAwarePickerStyle(night)
            } header: {
                Text("Configuración")
            } footer: {
                Text("Tras cambiar puertos o épocas, pulsa «Reiniciar servidores».")
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .keyboardDoneButton()
        .navigationTitle("Stellarium")
        .messageBanner(s.message) { model.clearMessage() }
    }
}

// MARK: - Tycho-2

struct CatalogView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night
    @State private var importing = false
    @State private var confirmDelete = false

    var body: some View {
        let s = host.more.value
        let model = host.graph.more
        List {
            Section {
                Text(s.catalogStatus.label)
                if s.catalogStatus.isWorking { ProgressView() }
            } header: {
                Text("Estado")
            }
            Section {
                Button { importing = true } label: { Label("Importar archivos…", systemImage: "square.and.arrow.down") }
                Button { model.testCatalog() } label: { Label("Probar una búsqueda", systemImage: "magnifyingglass") }
                    .disabled(!s.catalogStatus.isReady)
                Button(role: .destructive) { confirmDelete = true } label: { Label("Borrar el catálogo", systemImage: "trash") }
                    .disabled(!s.catalogStatus.isInstalled)
            } footer: {
                Text("Elige los tres archivos ya convertidos (tycho2.dat, tycho2.idx y tycho2.ref, de la herramienta catalog-builder) o los archivos oficiales del CDS (tyc2.dat.00.gz … tyc2.dat.19.gz y suppl_1.dat.gz) para convertirlos en el iPhone. También puedes copiarlos con la app Archivos a «En mi iPhone › OpenSky».")
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Catálogo Tycho-2")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .gzip], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            let paths = Self.copyToTemporary(urls)
            if !paths.isEmpty { model.importCatalog(paths: paths) }
        }
        .confirmationDialog("¿Borrar el catálogo Tycho-2?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { model.deleteCatalog() }
        }
        .messageBanner(s.message) { model.clearMessage() }
    }

    /// Copia los archivos elegidos (con acceso temporal de iOS) a una carpeta propia.
    private static func copyToTemporary(_ urls: [URL]) -> [String] {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("import-tycho2", isDirectory: true)
        try? fm.removeItem(at: dir)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var paths: [String] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let target = dir.appendingPathComponent(url.lastPathComponent)
            do {
                try fm.copyItem(at: url, to: target)
                paths.append(target.path)
            } catch {
                continue
            }
        }
        return paths
    }
}

// MARK: - Diagnóstico

struct DiagnosticsView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.more.value
        let model = host.graph.more
        List {
            Section {
                Button {
                    model.exportDiagnostics()
                } label: {
                    HStack {
                        Label("Preparar el registro", systemImage: "doc.text")
                        if s.busy { Spacer(); ProgressView() }
                    }
                }
                if let path = s.exportedFile {
                    ShareLink(item: URL(fileURLWithPath: path)) {
                        Label("Compartir el registro", systemImage: "square.and.arrow.up")
                    }
                }
            } footer: {
                Text("El registro guarda cada comando a la montura, GoTo, estrella confirmada, parada y alarma. Compártelo si algo no funciona como esperas.")
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Diagnóstico")
        .messageBanner(s.message) { model.clearMessage() }
    }
}

// MARK: - Acerca de

struct AboutView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night

    var body: some View {
        let s = host.more.value
        List {
            Section {
                LabeledContent("Versión", value: s.appVersion)
                LabeledContent("Montura", value: "Sky-Watcher Virtuoso GTi 150P")
            }
            Section {
                Link(destination: URL(string: "https://github.com/fggpng/opensky-virtuoso/releases")!) {
                    Label("Versiones nuevas en GitHub", systemImage: "arrow.down.app")
                }
            } footer: {
                Text("En iOS la app no se puede actualizar sola: descarga el .ipa nuevo y vuelve a instalarlo con Sideloadly o SideStore (se conservan tus ajustes y catálogos). Con un Apple ID gratuito la firma dura 7 días.")
            }
            Section {
                Text("Catálogo de cielo profundo: OpenNGC (CC-BY-SA-4.0). Estrellas: Tycho-2 (CDS). Cometas: Minor Planet Center. Asteroides: JPL Small-Body Database.")
                    .font(.footnote)
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Acerca de")
    }
}

// MARK: - Campo de ajuste

/// Campo de texto que guarda el ajuste [key] al terminar de editar.
struct SettingField: View {
    let title: String
    let key: String
    let value: String
    var keyboard: UIKeyboardType = .default
    @Environment(AppHost.self) private var host
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        LabeledContent(title) {
            TextField(title, text: $text)
                .multilineTextAlignment(.trailing)
                .keyboardType(keyboard)
                .focused($focused)
                .onSubmit(commit)
                .onChange(of: focused) { _, isFocused in if !isFocused { commit() } }
        }
        .onAppear { text = value }
        .onChange(of: value) { _, newValue in if !focused { text = newValue } }
    }

    private func commit() {
        let trimmed = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard !trimmed.isEmpty, trimmed != value else { return }
        host.graph.more.setSetting(key: key, value: trimmed)
    }
}
