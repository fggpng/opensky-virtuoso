package com.opensky.virtuoso.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.opensky.virtuoso.astronomy.ephemeris.MpcParser
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyElements
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyType
import com.opensky.virtuoso.database.targets.UserObject
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import com.opensky.virtuoso.database.tycho2.Tycho2Format
import com.opensky.virtuoso.network.web.SbdbResult
import com.opensky.virtuoso.network.web.SmallBodyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Escritura atómica: archivo temporal + rename (sin JSON a medias si se cierra la app). */
private fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { delete(); tmp.renameTo(this) }
}

/** Objetos de usuario (coordenadas J2000) persistidos como JSON. */
class UserObjectRepository(context: Context, scope: CoroutineScope) {
    private val file = File(context.filesDir, "user_objects.json")
    private val mutex = Mutex()
    private val _objects = MutableStateFlow<List<UserObject>>(emptyList())
    val objects: StateFlow<List<UserObject>> = _objects.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { _objects.value = load() }
    }

    private fun load(): List<UserObject> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            UserObject(
                id = o.getString("id"), name = o.getString("name"),
                raHours = o.getDouble("ra"), decDeg = o.getDouble("dec"),
                magnitude = if (o.has("mag") && !o.isNull("mag")) o.getDouble("mag") else null,
                notes = o.optString("notes"),
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun persist(list: List<UserObject>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        list.forEach { u ->
            arr.put(JSONObject().put("id", u.id).put("name", u.name).put("ra", u.raHours).put("dec", u.decDeg)
                .put("mag", u.magnitude ?: JSONObject.NULL).put("notes", u.notes))
        }
        file.writeAtomically(arr.toString(2))
        _objects.value = list
    }

    suspend fun upsert(obj: UserObject) = mutex.withLock {
        val id = obj.id.ifBlank { UUID.randomUUID().toString() }
        val list = _objects.value.filterNot { it.id == id } + obj.copy(id = id)
        persist(list.sortedBy { it.name.lowercase() })
    }

    suspend fun delete(id: String) = mutex.withLock { persist(_objects.value.filterNot { it.id == id }) }
}

/** Cometas y asteroides: descarga MPC, importación de archivos MPC y búsqueda en el JPL SBDB. */
class SmallBodyRepository(private val context: Context, scope: CoroutineScope) {
    private val file = File(context.filesDir, "small_bodies.json")
    private val mutex = Mutex()
    private val _bodies = MutableStateFlow<List<SmallBodyElements>>(emptyList())
    val bodies: StateFlow<List<SmallBodyElements>> = _bodies.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { _bodies.value = load() }
    }

    private fun load(): List<SmallBodyElements> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SmallBodyElements(
                designation = o.getString("des"), name = o.optString("name"),
                type = SmallBodyType.valueOf(o.getString("type")),
                q = o.getDouble("q"), e = o.getDouble("e"), inclDeg = o.getDouble("i"),
                nodeDeg = o.getDouble("node"), argPeriDeg = o.getDouble("w"),
                perihelionJdTt = o.getDouble("tp"), epochJdTt = o.getDouble("epoch"),
                absMag = if (o.isNull("h")) null else o.getDouble("h"),
                slope = if (o.isNull("g")) null else o.getDouble("g"),
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun persist(list: List<SmallBodyElements>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("des", b.designation).put("name", b.name).put("type", b.type.name)
                .put("q", b.q).put("e", b.e).put("i", b.inclDeg).put("node", b.nodeDeg).put("w", b.argPeriDeg)
                .put("tp", b.perihelionJdTt).put("epoch", b.epochJdTt)
                .put("h", b.absMag ?: JSONObject.NULL).put("g", b.slope ?: JSONObject.NULL))
        }
        file.writeAtomically(arr.toString())
        _bodies.value = list
    }

    private suspend fun merge(incoming: List<SmallBodyElements>, replaceType: SmallBodyType? = null): Int = mutex.withLock {
        val new = incoming.distinctBy { it.designation } // claves únicas para la lista de la UI
        val keep = _bodies.value.filter { old -> (replaceType == null || old.type != replaceType) && new.none { it.designation == old.designation } }
        persist((keep + new).sortedBy { it.displayName })
        new.size
    }

    /** Reemplaza todos los cometas por los del archivo del MPC. */
    suspend fun downloadComets(url: String): Int = merge(SmallBodyService(cometUrl = url).downloadComets(), SmallBodyType.COMET)

    /** Importa un archivo en formato MPC (cometas o MPCORB). */
    suspend fun importMpcFile(uri: Uri): Int {
        val parsed = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().useLines { MpcParser.parseAny(it) }
        }
        return merge(parsed)
    }

    suspend fun lookupJpl(query: String): SbdbResult {
        val result = SmallBodyService().lookup(query)
        if (result is SbdbResult.Found) merge(listOf(result.elements))
        return result
    }

    suspend fun remove(designation: String) = mutex.withLock { persist(_bodies.value.filterNot { it.designation == designation }) }
}

sealed class CatalogStatus {
    data object NotInstalled : CatalogStatus()
    data class Working(val phase: String, val count: Int) : CatalogStatus()
    data class Ready(val stars: Int) : CatalogStatus()
    data class Error(val message: String) : CatalogStatus()
}

/**
 * Gestión del Tycho-2: importa los 3 binarios ya convertidos (tycho2.dat/.idx/.ref) o los
 * archivos oficiales del CDS (tyc2.dat[.NN][.gz], suppl_1.dat[.gz]) y los convierte en el teléfono.
 */
class CatalogRepository(private val context: Context, scope: CoroutineScope) {
    val directory = File(context.filesDir, "tycho2")
    private val _catalog = MutableStateFlow<Tycho2Catalog?>(null)
    val catalog: StateFlow<Tycho2Catalog?> = _catalog.asStateFlow()
    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.NotInstalled)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { openIfInstalled() }
    }

    private fun openIfInstalled() {
        if (!Tycho2Catalog.isInstalled(directory)) { _status.value = CatalogStatus.NotInstalled; return }
        runCatching { Tycho2Catalog.open(directory) }
            .onSuccess { _catalog.value = it; _status.value = CatalogStatus.Ready(it.starCount) }
            .onFailure { _status.value = CatalogStatus.Error(it.message ?: "Catálogo dañado") }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "archivo"

    suspend fun import(uris: List<Uri>) = withContext(Dispatchers.IO) {
        try {
            _catalog.value?.close(); _catalog.value = null
            directory.mkdirs()
            val named = uris.map { displayName(it).lowercase() to it }
            val binaries = named.filter { (n, _) -> n in setOf(Tycho2Format.DAT_FILE, Tycho2Format.IDX_FILE, Tycho2Format.REF_FILE) }
            if (binaries.size == 3) {
                for ((name, uri) in binaries) {
                    _status.value = CatalogStatus.Working("Copiando $name", 0)
                    context.contentResolver.openInputStream(uri)!!.use { input -> File(directory, name).outputStream().use { input.copyTo(it, 1 shl 16) } }
                }
            } else {
                val converter = Tycho2Converter { phase, n -> _status.value = CatalogStatus.Working(phase, n) }
                for ((name, uri) in named.sortedBy { it.first }) {
                    _status.value = CatalogStatus.Working("Leyendo $name", converter.starCount)
                    context.contentResolver.openInputStream(uri)!!.use { converter.addStream(it, gzip = name.endsWith(".gz")) }
                }
                converter.write(directory)
            }
            openIfInstalled()
        } catch (e: Exception) {
            _status.value = CatalogStatus.Error(e.message ?: e.toString())
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        _catalog.value?.close(); _catalog.value = null
        directory.deleteRecursively()
        _status.value = CatalogStatus.NotInstalled
    }
}
