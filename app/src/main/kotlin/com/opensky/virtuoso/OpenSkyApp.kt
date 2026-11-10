package com.opensky.virtuoso

import android.app.Application
import com.opensky.virtuoso.di.AppContainer

class OpenSkyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installEmergencyStopOnCrash()
    }

    /**
     * Si la app falla de forma inesperada, antes de cerrarse intenta (máx. 1.5 s) detener la
     * montura: un pad a 800× que se queda «pulsado» haría girar el tubo sin control.
     */
    private fun installEmergencyStopOnCrash() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                com.opensky.virtuoso.control.Diagnostics.log("FALLO DE LA APP: ${error.javaClass.simpleName}: ${error.message}")
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1_500) { container.session.controller.value?.stopAll() }
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
