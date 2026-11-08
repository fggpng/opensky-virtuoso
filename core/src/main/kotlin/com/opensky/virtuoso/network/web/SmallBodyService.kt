package com.opensky.virtuoso.network.web

import com.opensky.virtuoso.astronomy.ephemeris.MpcParser
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyElements
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Resultado de una búsqueda en el JPL Small-Body Database. */
sealed class SbdbResult {
    data class Found(val elements: SmallBodyElements) : SbdbResult()
    /** Código 300: varias coincidencias; reintentar con una designación primaria. */
    data class Ambiguous(val candidates: List<Pair<String, String>>) : SbdbResult()
    data class NotFound(val message: String) : SbdbResult()
}

/**
 * Descarga de elementos orbitales de cometas (MPC) y búsqueda puntual de asteroides/cometas en el
 * JPL SBDB API. Requiere Internet (la red móvil sigue siendo la predeterminada aunque el teléfono
 * esté unido a la Wi-Fi SynScan, ver SynScanWifiManager).
 */
class SmallBodyService(
    private val cometUrl: String = MpcParser.COMET_ELEMENTS_URL,
    private val sbdbUrl: String = "https://ssd-api.jpl.nasa.gov/sbdb.api",
    private val timeoutMs: Int = 20_000,
) {
    suspend fun downloadComets(): List<SmallBodyElements> = withContext(Dispatchers.IO) {
        httpGet(cometUrl).second.lineSequence().mapNotNull { MpcParser.parseCometLine(it) }.toList()
    }

    suspend fun lookup(query: String): SbdbResult = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val (_, body) = httpGet("$sbdbUrl?sstr=$q&phys-par=1&full-prec=1")
        parseSbdb(body)
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "OpenSkyVirtuosoController/1.0")
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400 && code != 404) throw java.io.IOException("HTTP $code al descargar $url")
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseSbdb(body: String): SbdbResult {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                return SbdbResult.NotFound("Respuesta no válida del SBDB")
            }
            val code = root["code"]?.jsonPrimitive?.content
            if (code == "300") {
                val list = (root["list"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val pdes = o["pdes"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    pdes to (o["name"]?.jsonPrimitive?.content ?: pdes)
                }
                return SbdbResult.Ambiguous(list)
            }
            val obj = root["object"]?.jsonObject ?: return SbdbResult.NotFound(root["message"]?.jsonPrimitive?.content ?: "Objeto no encontrado")
            val orbit = root["orbit"]?.jsonObject ?: return SbdbResult.NotFound("El SBDB no devolvió órbita")
            val elements = orbit["elements"]?.jsonArray.orEmpty().associate { e ->
                val o = e.jsonObject
                o["name"]!!.jsonPrimitive.content to (o["value"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            }
            val phys = (root["phys_par"] as? JsonArray).orEmpty().associate { e ->
                val o = e.jsonObject
                o["name"]!!.jsonPrimitive.content to (o["value"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            }
            val kind = obj["kind"]?.jsonPrimitive?.content ?: "a"
            val isComet = kind.startsWith("c")
            val fullname = obj["fullname"]?.jsonPrimitive?.content ?: "?"
            val des = obj["des"]?.jsonPrimitive?.content ?: fullname
            val epoch = orbit["epoch"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return SbdbResult.NotFound("Sin época")
            val e = elements["e"] ?: return SbdbResult.NotFound("Sin excentricidad")
            val incl = elements["i"] ?: return SbdbResult.NotFound("Sin inclinación")
            val node = elements["om"] ?: return SbdbResult.NotFound("Sin nodo")
            val peri = elements["w"] ?: return SbdbResult.NotFound("Sin argumento del perihelio")
            val type = if (isComet) SmallBodyType.COMET else SmallBodyType.ASTEROID
            val absMag = if (isComet) phys["M1"] else phys["H"]
            val slope = if (isComet) phys["K1"]?.div(2.5) else phys["G"]
            val q = elements["q"] ?: elements["a"]?.let { it * (1 - e) } ?: return SbdbResult.NotFound("Sin q ni a")
            val tp = elements["tp"]
            val el = if (tp != null) {
                SmallBodyElements(des, fullname, type, q, e, incl, node, peri, tp, epoch, absMag, slope)
            } else {
                val a = elements["a"] ?: return SbdbResult.NotFound("Sin tp ni a")
                val ma = elements["ma"] ?: return SbdbResult.NotFound("Sin anomalía media")
                SmallBodyElements.fromMeanAnomaly(des, fullname, type, a, e, incl, node, peri, ma, epoch, absMag, slope)
            }
            return SbdbResult.Found(el)
        }
    }
}
