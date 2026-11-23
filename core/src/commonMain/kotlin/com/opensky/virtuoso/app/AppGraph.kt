package com.opensky.virtuoso.app

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.control.Diagnostics
import com.opensky.virtuoso.control.OffsetAstroClock
import com.opensky.virtuoso.data.SettingsStore
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.platform.PlatformLock
import com.opensky.virtuoso.sensors.TubeOrientation
import com.opensky.virtuoso.sensors.TubeOrientationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Grafo de objetos de la app (inyección de dependencias manual, como AppContainer en Android):
 * ajustes, reloj, ubicación, sesión con la montura, catálogos y los modelos de cada pantalla.
 * Vive lo que vive la app.
 */
class AppGraph(val platform: AppPlatform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Todo lo que escribe el núcleo (Diagnostics) va al registro de diagnóstico. */
    val diagnostics = DiagnosticLog(platform.files, scope).also { log -> Diagnostics.sink = log::log }
    val settings = SettingsStore(platform.settings)
    val clock = OffsetAstroClock()
    val location = LocationStore()
    val time = TimeStore(clock)
    val orientation = TubeOrientationEngine()
    val userObjects = UserObjectStore(platform.files, scope)
    val smallBodies = SmallBodyStore(platform.files, scope)
    val catalogs = CatalogStore(platform.catalogFiles, scope)
    val alignment = AlignmentManager()
    val targets = TargetCatalog(
        tycho2 = { catalogs.catalog.value },
        smallBodies = { smallBodies.bodies.value },
        userObjects = { userObjects.objects.value },
    )
    val session = MountSession(scope, settings, location, alignment, clock)

    private val motionLock = PlatformLock()
    private var motionUsers = 0

    init {
        scope.launch {
            settings.settings.map { it.phoneMounting }.distinctUntilChanged().collect { orientation.mounting = it }
        }
        scope.launch {
            settings.settings.map { it.opticalSetup().referenceRadiusArcmin }.distinctUntilChanged()
                .collect { alignment.referenceFieldRadiusArcmin = it }
        }
        scope.launch {
            // La declinación la da iOS (rumbo verdadero − magnético) cuando conoce la ubicación.
            ticker(5_000).collect {
                platform.device.magneticDeclinationDeg()?.let {
                    orientation.declinationDeg = it
                    orientation.declinationKnown = true
                }
            }
        }
    }

    /** Arranca los sensores de movimiento (con conteo: varias pantallas pueden pedirlos). */
    fun startMotion() {
        val first = motionLock.withLock { motionUsers++ == 0 }
        if (first) {
            orientation.reset()
            platform.device.startMotion()
        }
    }

    fun stopMotion() {
        val last = motionLock.withLock {
            if (motionUsers == 0) false else --motionUsers == 0
        }
        if (last) platform.device.stopMotion()
    }

    /** Promedia la orientación del tubo durante [durationMs] con los sensores encendidos. */
    suspend fun measureOrientation(durationMs: Long): TubeOrientation? {
        startMotion()
        try {
            return orientation.captureAverage(durationMs)
        } finally {
            stopMotion()
        }
    }

    // Modelos de pantalla (al final: usan todo lo anterior).
    val home = HomeModel(this)
    val sensors = SensorModel(this)
    val alignmentScreen = AlignmentModel(this)
    val targetsScreen = TargetsModel(this)
    val control = ControlModel(this)
    val more = MoreModel(this)
}
