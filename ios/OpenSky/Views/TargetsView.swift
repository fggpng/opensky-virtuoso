import OpenSkyCore
import SwiftUI
import UniformTypeIdentifiers

/// Objetivos: catálogos con su altura y acimut ahora, búsqueda, GoTo y sync.
struct TargetsView: View {
    @Environment(AppHost.self) private var host
    @Environment(\.nightMode) private var night
    @State private var query = ""
    @State private var selected: TargetRow?
    @State private var showJpl = false
    @State private var jplText = ""
    @State private var showNewObject = false
    @State private var importMpc = false

    var body: some View {
        let s = host.targets.value
        let model = host.graph.targetsScreen
        List {
            Section {
                Picker("Catálogo", selection: Binding(get: { s.category }, set: { model.setCategory(c: $0) })) {
                    ForEach(s.categories, id: \.self) { c in
                        Text(c.label).tag(c)
                    }
                }
                .pickerStyle(.menu)
                if s.busy { HStack { ProgressView(); Text("Trabajando…") } }
            }
            if s.category == TargetCategory.catalogStar && query.isEmpty {
                Section { Hint("Escribe una designación (TYC 1234-567-1 o HIP 91262) para buscar en el Tycho-2.") }
            }
            Section {
                ForEach(s.rows, id: \.target.id) { row in
                    Button { selected = row } label: { TargetRowView(row: row) }
                        .swipeActions(edge: .trailing) {
                            if let u = row.target as? UserTarget {
                                Button("Borrar", role: .destructive) { model.deleteUserObject(id: u.obj.id) }
                            } else if let b = row.target as? SmallBodyTarget {
                                Button("Quitar", role: .destructive) { model.removeSmallBody(designation: b.elements.designation) }
                            }
                        }
                }
                if s.rows.isEmpty && !s.busy {
                    Hint(query.isEmpty ? "Nada en esta lista todavía." : "Sin resultados para «\(query)».")
                }
            } header: {
                Text(query.isEmpty ? s.category.label : "Resultados")
            }
        }
        .listStyle(.insetGrouped)
        .screenBackground(night)
        .navigationTitle("Objetivos")
        .searchable(text: $query, prompt: "M31, Vega, Júpiter, NGC 7000…")
        .onChange(of: query) { _, q in model.setQuery(q: q) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { model.downloadComets() } label: { Label("Descargar cometas (MPC)", systemImage: "arrow.down.circle") }
                    Button { showJpl = true } label: { Label("Buscar en el JPL…", systemImage: "magnifyingglass") }
                    Button { importMpc = true } label: { Label("Importar archivo MPC…", systemImage: "doc") }
                    Button { showNewObject = true } label: { Label("Nuevo objeto…", systemImage: "plus") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog(selected?.target.name ?? "", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), presenting: selected) { row in
            Button("GoTo") { model.goto(target: row.target, confirmed: false) }
            Button("Sync (el tubo ya está centrado en él)") { model.sync(target: row.target) }
            Button("Cancelar", role: .cancel) {}
        } message: { row in
            Text(row.target.subtitle)
        }
        .alert(
            "¿Apuntar cerca del Sol?",
            isPresented: Binding(get: { s.pendingConfirmation != nil }, set: { if !$0 { model.dismissConfirmation() } }),
            presenting: s.pendingConfirmation
        ) { p in
            Button("Tengo filtro solar: apuntar", role: .destructive) { model.goto(target: p.target, confirmed: true) }
            Button("Cancelar", role: .cancel) { model.dismissConfirmation() }
        } message: { p in
            Text(p.reason)
        }
        .alert("Buscar en el JPL", isPresented: $showJpl) {
            TextField("Ceres, 2P, C/2023 A3…", text: $jplText)
            Button("Buscar") { model.lookupJpl(text: jplText) }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Añade un asteroide o cometa con su órbita del JPL Small-Body Database (necesita Internet).")
        }
        .sheet(isPresented: $showNewObject) { NewObjectSheet().nightTheme(night) }
        .fileImporter(isPresented: $importMpc, allowedContentTypes: [.plainText, .text, .data], allowsMultipleSelection: false) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let text = (try? String(contentsOf: url, encoding: .utf8)) ?? (try? String(contentsOf: url, encoding: .isoLatin1))
            if let text { model.importMpcText(text: text) }
        }
        .messageBanner(s.message) { model.clearMessage() }
    }
}

struct TargetRowView: View {
    let row: TargetRow

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.target.name).font(.body.weight(.semibold)).lineLimit(1)
                Text(row.target.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if let exp = row.maxExposureLabel {
                    Text("Exposición máx. \(exp)").font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(String(format: "Alt %+.0f°", row.altDeg))
                    .font(.callout.weight(.semibold).monospacedDigit())
                    .foregroundStyle(row.visible ? AnyShapeStyle(.tint) : AnyShapeStyle(Color.red))
                Text(String(format: "Az %.0f° %@", row.azDeg, cardinal(row.azDeg)))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                if let mag = row.magnitude.double {
                    Text(String(format: "mag %.1f", mag)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
        }
        .contentShape(Rectangle())
    }
}

/// Objeto propio por coordenadas J2000.
struct NewObjectSheet: View {
    @Environment(AppHost.self) private var host
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var ra = ""
    @State private var dec = ""
    @State private var mag = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Nombre", text: $name)
                    TextField("AR (horas: 05 35 17 o 5.588)", text: $ra).keyboardType(.numbersAndPunctuation)
                    TextField("Dec (grados: -05 23 28 o -5.39)", text: $dec).keyboardType(.numbersAndPunctuation)
                    TextField("Magnitud (opcional)", text: $mag).keyboardType(.decimalPad)
                } footer: {
                    Text("Coordenadas J2000 (las de los catálogos y de Stellarium).")
                }
            }
            .navigationTitle("Nuevo objeto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        host.graph.targetsScreen.saveUserObject(name: name, raText: ra, decText: dec, magText: mag)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
