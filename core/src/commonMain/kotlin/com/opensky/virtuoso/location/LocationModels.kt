package com.opensky.virtuoso.location

data class LocationFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val accuracyM: Float?,
    val timeMillis: Long,
    val provider: String?,
)

enum class TimeSource(val label: String) {
    UNVERIFIED("Reloj del sistema (sin verificar)"),
    NTP("NTP"),
    NETWORK_CLOCK("Hora de red del sistema"),
    GNSS("GNSS (satélites)"),
}

/** Estado de la sincronización horaria. [offsetMillis] se suma al reloj del sistema. */
data class TimeStatus(
    val source: TimeSource = TimeSource.UNVERIFIED,
    val offsetMillis: Long = 0,
    val uncertaintyMillis: Long? = null,
    val lastSyncMillis: Long? = null,
    val detail: String = "Hora aún no verificada",
) {
    val verified: Boolean get() = source != TimeSource.UNVERIFIED
}
