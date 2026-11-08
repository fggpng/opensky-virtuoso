package com.opensky.virtuoso.di

import android.content.Context
import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.control.OffsetAstroClock
import com.opensky.virtuoso.data.CatalogRepository
import com.opensky.virtuoso.data.SettingsRepository
import com.opensky.virtuoso.data.SmallBodyRepository
import com.opensky.virtuoso.data.UserObjectRepository
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.location.LocationTimeManager
import com.opensky.virtuoso.network.wifi.SynScanWifiManager
import com.opensky.virtuoso.sensors.OrientationSensorEngine
import com.opensky.virtuoso.service.TelescopeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Inyección de dependencias manual (patrón recomendado por la guía de arquitectura de Android
 * para apps de este tamaño): un único grafo de objetos con ámbito de aplicación. Evita KSP/kapt
 * y mantiene el dominio (:core) libre de anotaciones.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context, appScope)
    val clock = OffsetAstroClock()
    val wifi = SynScanWifiManager(context)
    val sensors = OrientationSensorEngine(context)
    val locationTime = LocationTimeManager(context, clock)
    val userObjects = UserObjectRepository(context, appScope)
    val smallBodies = SmallBodyRepository(context, appScope)
    val catalogs = CatalogRepository(context, appScope)
    val updater = com.opensky.virtuoso.update.AppUpdater(
        context.applicationContext, com.opensky.virtuoso.BuildConfig.UPDATE_REPO, com.opensky.virtuoso.BuildConfig.VERSION_CODE,
    )
    val alignment = AlignmentManager()

    val targets = TargetCatalog(
        tycho2 = { catalogs.catalog.value },
        smallBodies = { smallBodies.bodies.value },
        userObjects = { userObjects.objects.value },
    )

    val session = TelescopeSession(appScope, settings, wifi, locationTime, alignment, clock)

    init {
        // Declinación magnética y campo esperado según el lugar (GPS o manual).
        appScope.launch {
            locationTime.location.filterNotNull().collect { sensors.updateLocation(it.latitudeDeg, it.longitudeDeg, it.altitudeM) }
        }
        appScope.launch {
            settings.settings.map { it.phoneMounting }.distinctUntilChanged().collect { sensors.mounting = it }
        }
        appScope.launch {
            settings.settings.map { Triple(it.useManualLocation, it.manualLatitude, it.manualLongitude) }.distinctUntilChanged()
                .collect { (manual, lat, lon) -> if (manual) sensors.updateLocation(lat, lon, settings.current.manualElevationM) }
        }
        appScope.launch {
            settings.settings.map { it.referenceFieldDiameterArcmin }.distinctUntilChanged()
                .collect { alignment.referenceFieldRadiusArcmin = it / 2 }
        }
    }
}
