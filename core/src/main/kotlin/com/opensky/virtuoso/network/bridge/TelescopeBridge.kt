package com.opensky.virtuoso.network.bridge

import com.opensky.virtuoso.astronomy.AstroEngine
import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.control.Activity
import com.opensky.virtuoso.control.GotoResult
import com.opensky.virtuoso.control.MountController
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.control.PadMode
import com.opensky.virtuoso.control.Site

/** Época de las coordenadas intercambiadas con programas externos. */
enum class CoordinateEpoch { J2000, JNOW }

/**
 * Fachada mínima de la montura para los servidores LX200 y Stellarium (independiente de la UI).
 * Las coordenadas se expresan siempre en J2000; cada servidor convierte a JNow si lo necesita.
 */
interface TelescopeBridge {
    fun now(): AstroTime
    fun site(): Site
    fun positionJ2000(): EquatorialCoord?
    fun horizontal(): HorizontalCoord?
    fun isSlewing(): Boolean

    /** Comprobación rápida y síncrona antes de aceptar un GoTo (null = aceptable). */
    fun precheckGoto(j2000: EquatorialCoord): String?

    /** @return null si se inició/completó el GoTo, o el motivo del rechazo. */
    suspend fun gotoJ2000(j2000: EquatorialCoord): String?
    suspend fun syncJ2000(j2000: EquatorialCoord): String?
    suspend fun stop()
    suspend fun move(direction: PadDirection, rateIndex: Int)
    suspend fun stopMove()

    fun toEpoch(j2000: EquatorialCoord, epoch: CoordinateEpoch): EquatorialCoord =
        if (epoch == CoordinateEpoch.J2000) j2000 else AstroEngine.j2000ToApparent(j2000, now())

    fun fromEpoch(eq: EquatorialCoord, epoch: CoordinateEpoch): EquatorialCoord =
        if (epoch == CoordinateEpoch.J2000) eq else AstroEngine.apparentToJ2000(eq, now())
}

/** Implementación sobre [MountController]. */
class MountControllerBridge(
    private val controller: MountController,
    private val clock: () -> AstroTime,
    private val siteProvider: () -> Site,
    private val minAltitudeDeg: () -> Double,
) : TelescopeBridge {
    override fun now() = clock()
    override fun site() = siteProvider()
    override fun positionJ2000() = controller.state.value.pointingJ2000
    override fun horizontal() = controller.state.value.pointing
    override fun isSlewing() = controller.state.value.activity == Activity.SLEWING

    override fun precheckGoto(j2000: EquatorialCoord): String? {
        val s = site()
        val hz = AstroEngine.j2000ToHorizontal(j2000, now(), s.location, s.weather)
        return if (hz.altDeg < minAltitudeDeg()) "Objeto bajo el horizonte" else null
    }

    override suspend fun gotoJ2000(j2000: EquatorialCoord): String? = when (val r = controller.gotoJ2000(j2000)) {
        is GotoResult.Success -> null
        is GotoResult.Rejected -> r.reason
        is GotoResult.Failed -> r.error
    }

    override suspend fun syncJ2000(j2000: EquatorialCoord): String? =
        runCatching { controller.syncJ2000(j2000) }.exceptionOrNull()?.message

    override suspend fun stop() = controller.stopAll()
    override suspend fun move(direction: PadDirection, rateIndex: Int) = controller.manualStart(direction, rateIndex, PadMode.RA_DEC)
    override suspend fun stopMove() = controller.manualStop()
}
