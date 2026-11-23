package com.opensky.virtuoso.app

import com.opensky.virtuoso.data.KeyValueStore
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/** Plataforma iOS: archivos en Documentos, ajustes en UserDefaults y sensores desde Swift. */
class IosPlatform(override val device: DeviceServices) : AppPlatform {
    private val documents = appleDocumentsDirectory()

    override val settings: KeyValueStore = UserDefaultsStore()
    override val files: TextFiles = AppleTextFiles(documents)
    override val catalogFiles: CatalogFiles = AppleCatalogFiles("$documents/tycho2")

    override val appVersion: String = run {
        val info = NSBundle.mainBundle.infoDictionary
        val version = info?.get("CFBundleShortVersionString") as? String ?: "?"
        val build = info?.get("CFBundleVersion") as? String ?: "?"
        "$version ($build)"
    }

    override val deviceDescription: String =
        UIDevice.currentDevice.let { "${it.model} · ${it.systemName} ${it.systemVersion}" }
}

/** Punto de entrada para Swift: `IosAppKt.createIosApp(device: …)`. */
fun createIosApp(device: DeviceServices): AppGraph = AppGraph(IosPlatform(device))
