package com.opensky.virtuoso.app

import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.control.Activity
import com.opensky.virtuoso.control.HeadingReference
import com.opensky.virtuoso.control.StartPosition
import com.opensky.virtuoso.data.AppSettings
import com.opensky.virtuoso.data.MemoryKeyValueStore
import com.opensky.virtuoso.data.SettingsCodec
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import com.opensky.virtuoso.platform.ByteArrayBlob
import com.opensky.virtuoso.platform.ByteArraySink
import com.opensky.virtuoso.platform.PlatformLock
import com.opensky.virtuoso.sensors.PhoneMounting
import com.opensky.virtuoso.sensors.TubeOrientationEngine
import com.opensky.virtuoso.testing.assertEquals
import com.opensky.virtuoso.testing.assertFalse
import com.opensky.virtuoso.testing.assertNotNull
import com.opensky.virtuoso.testing.assertNull
import com.opensky.virtuoso.testing.assertTrue
import com.opensky.virtuoso.testing.isoMillis
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/** Plataforma falsa en memoria: la capa compartida se prueba igual en la JVM y en nativo. */
class FakePlatform : AppPlatform {
    val kv = MemoryKeyValueStore()
    val memFiles = MemoryTextFiles()
    val fakeDevice = FakeDevice()
    override val settings = kv
    override val files: TextFiles = memFiles
    override val catalogFiles: CatalogFiles = MemoryCatalogFiles()
    override val device: DeviceServices = fakeDevice
    override val appVersion = "1.0.test"
    override val deviceDescription = "Prueba"
}

class FakeDevice : DeviceServices {
    var started = 0
    var stopped = 0
    var locationRequests = 0
    override val motionAvailable = true
    override fun startMotion() { started++ }
    override fun stopMotion() { stopped++ }
    override fun requestLocation() { locationRequests++ }
    override fun magneticDeclinationDeg(): Double? = 8.5
    override fun localIpv4Addresses(): List<String> = listOf("192.168.4.2 (en0)")
}

class MemoryTextFiles : TextFiles {
    private val lock = PlatformLock()
    val map = mutableMapOf<String, String>()
    override fun read(name: String) = lock.withLock { map[name] }
    override fun write(name: String, text: String) { lock.withLock { map[name] = text } }
    override fun append(name: String, text: String) { lock.withLock { map[name] = (map[name] ?: "") + text } }
    override fun size(name: String) = lock.withLock { map[name]?.length?.toLong() ?: 0L }
    override fun rename(from: String, to: String) { lock.withLock { map.remove(from)?.let { map[to] = it } } }
    override fun delete(name: String) { lock.withLock { map.remove(name) } }
    override fun path(name: String) = "/memoria/$name"
}

class MemoryCatalogFiles : CatalogFiles {
    private val files = mutableMapOf<String, ByteArray>()
    override val directory = "/memoria/tycho2"
    override fun isInstalled() = Tycho2Catalog.FILE_NAMES.all { it in files }
    override fun open() = Tycho2Catalog.open { ByteArrayBlob(files.getValue(it)) }
    override fun copyIn(sourcePath: String, name: String) = error("sin archivos en memoria")
    override fun addSourceFile(converter: Tycho2Converter, path: String) {
        // "ruta" = una línea de tyc2.dat (para la prueba)
        converter.addTextLine(path)
    }
    override fun write(converter: Tycho2Converter): Tycho2Converter.Result {
        val sinks = mutableMapOf<String, ByteArraySink>()
        val r = converter.write { name -> ByteArraySink().also { sinks[name] = it } }
        sinks.forEach { (k, v) -> files[k] = v.toByteArray() }
        return r
    }
    override fun delete() = files.clear()
}

class AppLayerTest {

    private fun graph(manualSite: Boolean = true): Pair<AppGraph, FakePlatform> {
        val p = FakePlatform()
        if (manualSite) {
            p.kv.putAll(SettingsCodec.encode(AppSettings(useManualLocation = true, manualLatitude = 28.5933, manualLongitude = -106.1616, manualElevationM = 1505.0)))
        }
        return AppGraph(p).also { lastGraph = it } to p
    }

    private var lastGraph: AppGraph? = null

    private suspend fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(25) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val g = lastGraph
            throw AssertionError("Plazo vencido. Sesión=${g?.session?.state?.value} montura=${g?.home?.stateFlow?.value?.mount} " +
                "mensajes: inicio=${g?.home?.stateFlow?.value?.message} alinear=${g?.alignmentScreen?.stateFlow?.value?.message} " +
                "objetivos=${g?.targetsScreen?.stateFlow?.value?.message} filas=${g?.targetsScreen?.stateFlow?.value?.rows?.size}")
        }
    }

    @Test fun settingsRoundTripAndSetByKey() {
        val custom = AppSettings(mountHost = "10.0.0.5", minAltitudeDeg = 12.5, invertAltitude = true, phoneMounting = PhoneMounting.BACK_CAMERA_ALONG_TUBE)
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)::get))
        assertEquals(AppSettings(), SettingsCodec.decode { null })
        assertEquals(AppSettings(mountPort = 11880), SettingsCodec.decode { k -> if (k == "mount_port") "basura" else null })
        val (g, p) = graph(manualSite = false)
        g.more.setSetting("min_alt", "7.5")
        assertEquals(7.5, g.settings.current.minAltitudeDeg, 0.0)
        assertEquals("7.5", p.kv.snapshot()["min_alt"])
        g.scope.cancel()
    }

    @Test fun simulatorSessionGotoAndAlignment() = runBlocking {
        val (g, _) = graph()
        try {
            g.home.connect(ConnectionMode.SIMULATOR)
            waitFor { g.home.stateFlow.value.message?.startsWith("Conectado") == true }
            assertNotNull(g.session.controller.value)
            assertEquals(ConnectionMode.SIMULATOR, g.home.stateFlow.value.session.mode)
            g.home.confirmHome()
            waitFor { g.home.stateFlow.value.mount?.startPosition == StartPosition.HOME }
            waitFor { g.home.stateFlow.value.message?.contains("Posición de inicio") == true }
            assertTrue(g.home.stateFlow.value.locationKnown || g.session.siteKnown())

            // Objetivos: el sistema solar se lista con altura y acimut.
            waitFor { g.targetsScreen.stateFlow.value.rows.isNotEmpty() }
            assertEquals(TargetCategory.SOLAR_SYSTEM, g.targetsScreen.stateFlow.value.category)
            g.targetsScreen.setQuery("vega")
            waitFor { g.targetsScreen.stateFlow.value.rows.firstOrNull()?.target?.name == "Vega" }

            // Alineación de una estrella con la lista de sugerencias.
            g.alignmentScreen.begin(AlignmentMode.ONE_STAR)
            waitFor { g.alignmentScreen.stateFlow.value.suggestions.isNotEmpty() }
            val star = g.alignmentScreen.stateFlow.value.suggestions.first().star
            g.alignmentScreen.gotoStar(star)
            waitFor(30_000) { g.alignmentScreen.stateFlow.value.message?.contains("Centra") == true }
            g.alignmentScreen.confirmCentered(force = true)
            waitFor(10_000) { g.alignment.state.value.alignmentSamples.size == 1 }
            assertTrue(g.alignment.state.value.isComplete)

            // Mando: DETENER deja la montura quieta.
            g.control.stopAll()
            waitFor { g.control.stateFlow.value.mount?.activity == Activity.IDLE }

            g.home.disconnect()
            waitFor { g.session.controller.value == null }
        } finally {
            g.scope.cancel()
        }
    }

    @Test fun manualStartPreviewUsesDeclination() = runBlocking {
        val (g, _) = graph()
        try {
            g.home.prepareManualStart("120", HeadingReference.MAGNETIC, measureAltitude = false)
            waitFor { g.home.stateFlow.value.manualPreview != null }
            val p = g.home.stateFlow.value.manualPreview!!
            assertEquals(128.5, p.trueAzDeg, 1e-9) // 120° magnético + 8.5° de declinación
            g.home.prepareManualStart("400", HeadingReference.TRUE, measureAltitude = false)
            waitFor { g.home.stateFlow.value.manualError != null }
            g.home.cancelManualStart()
            waitFor { g.home.stateFlow.value.manualPreview == null && g.home.stateFlow.value.manualError == null }
        } finally {
            g.scope.cancel()
        }
    }

    @Test fun tubeOrientationFromGravityAndField() = runBlocking {
        val engine = TubeOrientationEngine()
        // Teléfono plano (pantalla arriba), borde superior hacia el norte magnético: gravedad −Z.
        // Campo terrestre: 25 µT al norte (+Y del teléfono) y 40 µT hacia abajo (−Z).
        engine.onSample(0.0, 0.0, -1.0, 0.0, 25.0, -40.0, com.opensky.virtuoso.sensors.CompassAccuracy.HIGH)
        var o = engine.orientation.value!!
        assertEquals(0.0, o.altitudeDeg, 1e-6)
        assertTrue("az=${o.magneticAzimuthDeg}", abs(o.magneticAzimuthDeg) < 1e-6 || abs(o.magneticAzimuthDeg - 360) < 1e-6)
        assertFalse(o.interference)
        // Girado 90° a la derecha (borde superior al este): el norte queda hacia −X del teléfono.
        engine.reset()
        engine.onSample(0.0, 0.0, -1.0, -25.0, 0.0, -40.0, com.opensky.virtuoso.sensors.CompassAccuracy.HIGH)
        o = engine.orientation.value!!
        assertEquals(90.0, o.magneticAzimuthDeg, 1e-6)
        // Tubo (borde superior) inclinado 30° hacia arriba, apuntando al norte.
        engine.reset()
        val t = 30.0 * kotlin.math.PI / 180
        // En ejes del teléfono: arriba = (0, sin t, cos t) → gravedad = −arriba; el norte horizontal = (0, cos t, −sin t).
        val gx = 0.0; val gy = -sin(t); val gz = -cos(t)
        val mx = 0.0; val my = 25.0 * cos(t) - 40.0 * sin(t); val mz = -25.0 * sin(t) - 40.0 * cos(t)
        engine.onSample(gx, gy, gz, mx, my, mz, com.opensky.virtuoso.sensors.CompassAccuracy.HIGH)
        o = engine.orientation.value!!
        assertEquals(30.0, o.altitudeDeg, 1e-6)
        // Campo de 500 µT (un imán cerca): interferencia.
        engine.onSample(0.0, 0.0, -1.0, 0.0, 300.0, -400.0, com.opensky.virtuoso.sensors.CompassAccuracy.LOW)
        assertTrue(engine.orientation.value!!.interference)
    }

    @Test fun diagnosticsExportAndStores() = runBlocking {
        val (g, p) = graph()
        try {
            com.opensky.virtuoso.control.Diagnostics.log("prueba de registro")
            g.targetsScreen.saveUserObject("Mi nebulosa", "05 35 17", "-05 23 28", "4,0")
            waitFor { g.userObjects.objects.value.size == 1 }
            assertEquals(4.0, g.userObjects.objects.value.single().magnitude!!, 1e-9)
            assertTrue(p.memFiles.map["user_objects.json"]!!.contains("Mi nebulosa"))
            g.more.exportDiagnostics()
            waitFor { g.more.stateFlow.value.exportedFile != null }
            val name = g.more.stateFlow.value.exportedFile!!.substringAfterLast('/')
            val text = p.memFiles.map[name]!!
            assertTrue(text.contains("OpenSky Virtuoso — diagnóstico"))
            assertTrue(text.contains("prueba de registro"))
            assertEquals(listOf("192.168.4.2 (en0)"), withTimeout(8_000) { while (g.more.stateFlow.value.ipAddresses.isEmpty()) delay(25); g.more.stateFlow.value.ipAddresses })
            assertNull(g.more.stateFlow.value.message)
        } finally {
            g.scope.cancel()
        }
    }

    @Test fun watcherDeliversCurrentAndUpdates() = runBlocking {
        val (g, _) = graph()
        try {
            StateWatcher.dispatcherOverride = kotlinx.coroutines.Dispatchers.Unconfined
            val seen = com.opensky.virtuoso.testing.SyncList<Int>()
            val w = g.control.state.watch { seen += it.padRateIndex }
            g.control.setRate(2)
            waitFor { seen.isNotEmpty() && seen.last() == 2 }
            w.cancel()
            StateWatcher.dispatcherOverride = null
            assertNotNull(g.control.state.value)
            assertEquals(isoMillis("2026-01-01T00:00:00Z") > 0, true)
        } finally {
            g.scope.cancel()
        }
    }
}
