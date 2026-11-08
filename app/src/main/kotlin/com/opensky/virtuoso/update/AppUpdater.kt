package com.opensky.virtuoso.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Una versión publicada en GitHub Releases por el flujo de compilación automática. */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Actualizador de la app distribuida fuera de Play Store.
 *
 * Cada `push` a `main` compila un APK firmado en GitHub Actions y lo publica como release
 * `v1.0.<N>`, donde N = número de ejecución = versionCode. La app consulta la última release,
 * la descarga y la instala con [PackageInstaller] (Android pide confirmación al usuario).
 * Requiere que el repositorio sea público: las releases de repos privados exigen autenticación.
 */
class AppUpdater(
    private val context: Context,
    val repo: String,
    val currentVersionCode: Int,
) {
    val isConfigured: Boolean get() = repo.contains('/')

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$repo/releases/latest").apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("Aún no hay versiones publicadas en $repo (o el repositorio es privado)")
                403, 429 -> throw IOException("GitHub limitó las consultas; inténtalo en unos minutos")
                else -> throw IOException("GitHub respondió $code")
            }
            parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    /** Descarga el APK a la caché de la app. [onProgress] recibe una fracción 0..1 (o null si no se conoce el tamaño). */
    suspend fun download(release: ReleaseInfo, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // solo se guarda la última
        val target = File(dir, "opensky-virtuoso-${release.versionCode}.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("Descarga fallida (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes.takeIf { it > 0 }
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(total?.let { (done.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            if (total != null && done != total) throw IOException("Descarga incompleta ($done de $total bytes)")
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** true si el usuario ya permitió a esta app instalar APKs («Instalar apps desconocidas»). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Pantalla de Ajustes de Android donde se concede el permiso de instalar apps desconocidas. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Entrega el APK al instalador del sistema; el resultado llega a [InstallResultReceiver]. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags,
            )
            InstallEvents.publish(null)
            session.commit(callback.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true // los APK se sirven desde otro dominio de GitHub
            setRequestProperty("User-Agent", "OpenSkyVirtuoso-updater")
        }

    private fun parse(json: JSONObject): ReleaseInfo {
        val tag = json.getString("tag_name")
        val code = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull()
            ?: throw IOException("Etiqueta de versión no reconocida: $tag")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return ReleaseInfo(
                    versionCode = code,
                    versionName = tag.removePrefix("v"),
                    notes = json.optString("body").trim(),
                    apkUrl = a.getString("browser_download_url"),
                    sizeBytes = a.optLong("size"),
                )
            }
        }
        throw IOException("La versión $tag no incluye un APK")
    }
}

/** Último mensaje del instalador del sistema (null = sin novedad). */
object InstallEvents {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun publish(text: String?) { _message.value = text }
}

/** Recibe el resultado de la sesión de instalación y, si hace falta, abre la confirmación de Android. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.publish("Actualización instalada")
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.publish("Instalación cancelada")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallEvents.publish(
                "La app instalada tiene otra firma (por ejemplo, la compilada en Android Studio). " +
                    "Desinstálala una vez y vuelve a instalar la versión de GitHub.",
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallEvents.publish("No hay espacio suficiente para instalar")
            else -> InstallEvents.publish(
                "No se pudo instalar (código $status): " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error desconocido"),
            )
        }
    }
}
