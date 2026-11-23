package com.opensky.virtuoso.app

import com.opensky.virtuoso.astronomy.ephemeris.MpcParser
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyElements
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyType
import com.opensky.virtuoso.control.OffsetAstroClock
import com.opensky.virtuoso.database.targets.UserObject
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import com.opensky.virtuoso.database.tycho2.Tycho2Format
import com.opensky.virtuoso.location.LocationFix
import com.opensky.virtuoso.location.TimeStatus
import com.opensky.virtuoso.location.TimeSync
import com.opensky.virtuoso.network.web.SbdbResult
import com.opensky.virtuoso.network.web.SmallBodyService
import com.opensky.virtuoso.platform.CivilDateTime
import com.opensky.virtuoso.platform.PlatformLock
import com.opensky.virtuoso.platform.epochMillisNow
import com.opensky.virtuoso.platform.fmt
import com.opensky.virtuoso.platform.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Última ubicación del GPS (la pone CoreLocation desde Swift). */
class LocationStore {
    private val _location = MutableStateFlow<LocationFix?>(null)
    val location: StateFlow<LocationFix?> = _location.asStateFlow()

    /** Fix nuevo del sistema. [accuracyM] < 0 = desconocida. */
    fun onFix(latitudeDeg: Double, longitudeDeg: Double, altitudeM: Double, accuracyM: Double, timeMillis: Long) {
        _location.value = LocationFix(latitudeDeg, longitudeDeg, altitudeM, accuracyM.takeIf { it >= 0 }?.toFloat(), timeMillis, "gps")
    }
}

/** Verificación de la hora: ajusta el reloj compartido con el desfase NTP. */
class TimeStore(private val clock: OffsetAstroClock) {
    private val _time = MutableStateFlow(TimeStatus())
    val time: StateFlow<TimeStatus> = _time.asStateFlow()

    suspend fun sync(primaryServer: String): TimeStatus {
        val status = TimeSync.sync(primaryServer)
        clock.offsetMillis = status.offsetMillis
        _time.value = status
        return status
    }
}

/**
 * Registro de diagnóstico en archivo rotativo (dos archivos de hasta [MAX_BYTES]): comandos a la
 * montura, GoTo, estrellas confirmadas, paradas y alarmas. Se escribe en segundo plano para no
 * frenar nunca el control de la montura.
 */
class DiagnosticLog(private val files: TextFiles, scope: CoroutineScope) {
    private val queue = Channel<String>(capacity = 20_000)
    private val writeLock = Mutex()

    init {
        scope.launch(ioDispatcher) {
            val batch = ArrayList<String>(512)
            while (true) {
                batch.clear()
                batch += queue.receive()
                while (batch.size < 512) batch += queue.tryReceive().getOrNull() ?: break
                writeLock.withLock { writeBatch(batch) }
            }
        }
    }

    fun log(message: String) {
        queue.trySend("${stamp(epochMillisNow())} | $message")
    }

    private fun writeBatch(lines: List<String>) {
        runCatching {
            if (files.size(CURRENT) > MAX_BYTES) {
                files.delete(PREVIOUS)
                files.rename(CURRENT, PREVIOUS)
            }
            files.append(CURRENT, lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** Une encabezado y registros en un .txt; devuelve su ruta para compartirlo. */
    suspend fun export(header: String): String = withContext(ioDispatcher) {
        delay(300) // deja que se escriba lo pendiente
        writeLock.withLock {
            val c = CivilDateTime.local(epochMillisNow())
            val name = "opensky-diagnostico-%04d%02d%02d-%02d%02d%02d.txt".fmt(c.year, c.month, c.day, c.hour, c.minute, c.second)
            val body = buildString {
                append(header)
                append("\n\n===== REGISTRO =====\n")
                files.read(PREVIOUS)?.let { append(it) }
                files.read(CURRENT)?.let { append(it) }
            }
            files.write(name, body)
            files.path(name)
        }
    }

    companion object {
        const val MAX_BYTES = 2_000_000L
        private const val CURRENT = "diagnostico-actual.log"
        private const val PREVIOUS = "diagnostico-anterior.log"

        fun stamp(epochMillis: Long): String = CivilDateTime.local(epochMillis).let {
            "%04d-%02d-%02d %02d:%02d:%02d.%03d".fmt(it.year, it.month, it.day, it.hour, it.minute, it.second, it.millis)
        }
    }
}

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

/** Objetos del usuario (coordenadas J2000) en un JSON (mismo formato que en Android). */
class UserObjectStore(private val files: TextFiles, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val _objects = MutableStateFlow<List<UserObject>>(emptyList())
    val objects: StateFlow<List<UserObject>> = _objects.asStateFlow()

    init {
        scope.launch(ioDispatcher) { _objects.value = load() }
    }

    private fun load(): List<UserObject> = runCatching {
        val text = files.read(FILE) ?: return emptyList()
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
            val o = e.jsonObject
            UserObject(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name") ?: return@mapNotNull null,
                raHours = o.num("ra") ?: return@mapNotNull null,
                decDeg = o.num("dec") ?: return@mapNotNull null,
                magnitude = o.num("mag"),
                notes = o.str("notes") ?: "",
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun persist(list: List<UserObject>) = withContext(ioDispatcher) {
        val arr = buildJsonArray {
            list.forEach { u ->
                add(buildJsonObject {
                    put("id", u.id); put("name", u.name); put("ra", u.raHours); put("dec", u.decDeg)
                    put("mag", u.magnitude); put("notes", u.notes)
                })
            }
        }
        files.write(FILE, json.encodeToString(JsonArray.serializer(), arr))
        _objects.value = list
    }

    suspend fun upsert(obj: UserObject) = mutex.withLock {
        val id = obj.id.ifBlank { "u" + Random.nextLong().toULong().toString(16) }
        val list = _objects.value.filterNot { it.id == id } + obj.copy(id = id)
        persist(list.sortedBy { it.name.lowercase() })
    }

    suspend fun delete(id: String) = mutex.withLock { persist(_objects.value.filterNot { it.id == id }) }

    private companion object {
        const val FILE = "user_objects.json"
    }
}

/** Cometas y asteroides: descarga del MPC, archivos MPC y búsqueda en el JPL SBDB. */
class SmallBodyStore(private val files: TextFiles, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val _bodies = MutableStateFlow<List<SmallBodyElements>>(emptyList())
    val bodies: StateFlow<List<SmallBodyElements>> = _bodies.asStateFlow()

    init {
        scope.launch(ioDispatcher) { _bodies.value = load() }
    }

    private fun load(): List<SmallBodyElements> = runCatching {
        val text = files.read(FILE) ?: return emptyList()
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
            val o = e.jsonObject
            SmallBodyElements(
                designation = o.str("des") ?: return@mapNotNull null,
                name = o.str("name") ?: "",
                type = SmallBodyType.entries.firstOrNull { it.name == o.str("type") } ?: return@mapNotNull null,
                q = o.num("q") ?: return@mapNotNull null,
                e = o.num("e") ?: return@mapNotNull null,
                inclDeg = o.num("i") ?: return@mapNotNull null,
                nodeDeg = o.num("node") ?: return@mapNotNull null,
                argPeriDeg = o.num("w") ?: return@mapNotNull null,
                perihelionJdTt = o.num("tp") ?: return@mapNotNull null,
                epochJdTt = o.num("epoch") ?: return@mapNotNull null,
                absMag = o.num("h"),
                slope = o.num("g"),
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun persist(list: List<SmallBodyElements>) = withContext(ioDispatcher) {
        val arr = buildJsonArray {
            list.forEach { b ->
                add(buildJsonObject {
                    put("des", b.designation); put("name", b.name); put("type", b.type.name)
                    put("q", b.q); put("e", b.e); put("i", b.inclDeg); put("node", b.nodeDeg); put("w", b.argPeriDeg)
                    put("tp", b.perihelionJdTt); put("epoch", b.epochJdTt); put("h", b.absMag); put("g", b.slope)
                })
            }
        }
        files.write(FILE, json.encodeToString(JsonArray.serializer(), arr))
        _bodies.value = list
    }

    private suspend fun merge(incoming: List<SmallBodyElements>, replaceType: SmallBodyType? = null): Int = mutex.withLock {
        val new = incoming.distinctBy { it.designation }
        val keep = _bodies.value.filter { old -> (replaceType == null || old.type != replaceType) && new.none { it.designation == old.designation } }
        persist((keep + new).sortedBy { it.displayName })
        new.size
    }

    /** Reemplaza todos los cometas por los del archivo del MPC. */
    suspend fun downloadComets(url: String): Int = merge(SmallBodyService(cometUrl = url).downloadComets(), SmallBodyType.COMET)

    /** Importa el texto de un archivo MPC (cometas o MPCORB). */
    suspend fun importMpcText(text: String): Int = merge(MpcParser.parseAny(text.lineSequence()))

    suspend fun lookupJpl(query: String): SbdbResult {
        val result = SmallBodyService().lookup(query)
        if (result is SbdbResult.Found) merge(listOf(result.elements))
        return result
    }

    suspend fun remove(designation: String) = mutex.withLock { persist(_bodies.value.filterNot { it.designation == designation }) }

    private companion object {
        const val FILE = "small_bodies.json"
    }
}

/** Estado del catálogo Tycho-2. */
sealed class CatalogStatus {
    data object NotInstalled : CatalogStatus()
    data class Working(val phase: String, val count: Int) : CatalogStatus()
    data class Ready(val stars: Int) : CatalogStatus()
    data class Error(val message: String) : CatalogStatus()

    /** Texto para la UI. */
    val label: String
        get() = when (this) {
            NotInstalled -> "No instalado"
            is Working -> "$phase… (%,d)".fmt(count)
            is Ready -> "Instalado: %,d estrellas".fmt(stars)
            is Error -> "Error: $message"
        }
}

/**
 * Tycho-2: importa los 3 binarios ya convertidos (tycho2.dat/.idx/.ref) o los archivos oficiales
 * del CDS (tyc2.dat[.NN][.gz], suppl_1.dat[.gz]) y los convierte en el teléfono.
 */
class CatalogStore(private val files: CatalogFiles, scope: CoroutineScope) {
    private val lock = PlatformLock()
    private val _catalog = MutableStateFlow<Tycho2Catalog?>(null)
    val catalog: StateFlow<Tycho2Catalog?> = _catalog.asStateFlow()
    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.NotInstalled)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    init {
        scope.launch(ioDispatcher) { openIfInstalled() }
    }

    private fun openIfInstalled() {
        if (!files.isInstalled()) { _status.value = CatalogStatus.NotInstalled; return }
        runCatching { files.open() }
            .onSuccess { _catalog.value = it; _status.value = CatalogStatus.Ready(it.starCount) }
            .onFailure { _status.value = CatalogStatus.Error(it.message ?: "Catálogo dañado") }
    }

    private fun closeCurrent() = lock.withLock {
        _catalog.value?.close()
        _catalog.value = null
    }

    /** [paths]: archivos elegidos por el usuario (copias locales); el nombre decide qué son. */
    suspend fun import(paths: List<String>) = withContext(ioDispatcher) {
        try {
            closeCurrent()
            val named = paths.map { it.substringAfterLast('/').lowercase() to it }
            val binaries = named.filter { (n, _) -> n in Tycho2Catalog.FILE_NAMES }
            if (binaries.size == 3) {
                for ((name, path) in binaries) {
                    _status.value = CatalogStatus.Working("Copiando $name", 0)
                    files.copyIn(path, name)
                }
            } else {
                val converter = Tycho2Converter { phase, n -> _status.value = CatalogStatus.Working(phase, n) }
                for ((name, path) in named.sortedBy { it.first }) {
                    _status.value = CatalogStatus.Working("Leyendo $name", converter.starCount)
                    files.addSourceFile(converter, path)
                }
                files.write(converter)
            }
            openIfInstalled()
        } catch (e: Exception) {
            _status.value = CatalogStatus.Error(e.message ?: e.toString())
        }
    }

    suspend fun delete() = withContext(ioDispatcher) {
        closeCurrent()
        files.delete()
        _status.value = CatalogStatus.NotInstalled
    }

    companion object {
        /** Nombres que reconoce la importación (para el selector de archivos). */
        val ACCEPTED_NAMES = listOf(Tycho2Format.DAT_FILE, Tycho2Format.IDX_FILE, Tycho2Format.REF_FILE, "tyc2.dat.NN.gz", "suppl_1.dat.gz")
    }
}
