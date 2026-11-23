package com.opensky.virtuoso.app

import com.opensky.virtuoso.data.AppSettings
import com.opensky.virtuoso.data.SettingsCodec
import com.opensky.virtuoso.testing.assertTrue
import java.io.File
import kotlin.test.Test

/**
 * La app de iOS cambia los ajustes por su clave (`setSetting(key:value:)`). Si una clave de Swift no
 * existe en [SettingsCodec], el cambio se ignoraría en silencio: esta prueba lo detecta.
 */
class SwiftContractTest {
    @Test fun everySettingKeyUsedBySwiftExists() {
        val iosDir = listOf(File("../ios"), File("ios")).firstOrNull { it.isDirectory } ?: return // sin la carpeta de iOS
        val keys = SettingsCodec.encode(AppSettings()).keys
        val pattern = Regex("""key:\s*"([a-z_]+)"""")
        val used = iosDir.walkTopDown().filter { it.extension == "swift" }
            .flatMap { f -> pattern.findAll(f.readText()).map { it.groupValues[1] to f.name } }
            .toList()
        assertTrue("no se encontraron ajustes en Swift", used.isNotEmpty())
        val unknown = used.filter { (k, _) -> k !in keys }
        assertTrue("claves desconocidas en Swift: $unknown", unknown.isEmpty())
    }
}
