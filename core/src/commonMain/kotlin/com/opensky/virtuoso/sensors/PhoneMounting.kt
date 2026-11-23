package com.opensky.virtuoso.sensors

/** Cómo se apoya el teléfono sobre el tubo del telescopio. */
enum class PhoneMounting(val label: String) {
    /** Teléfono plano sobre el tubo con el borde superior hacia la boca del tubo (eje +Y). */
    TOP_EDGE_ALONG_TUBE("Plano sobre el tubo, borde superior hacia la boca"),
    /** Cámara trasera mirando en la dirección del tubo (eje −Z). */
    BACK_CAMERA_ALONG_TUBE("Cámara trasera apuntando como el tubo"),
}
