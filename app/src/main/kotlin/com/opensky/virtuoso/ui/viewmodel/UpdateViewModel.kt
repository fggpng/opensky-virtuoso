package com.opensky.virtuoso.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensky.virtuoso.BuildConfig
import com.opensky.virtuoso.di.AppContainer
import com.opensky.virtuoso.update.InstallEvents
import com.opensky.virtuoso.update.ReleaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

class UpdateViewModel(private val c: AppContainer) : ViewModel() {
    private val updater = c.updater

    val installedVersion: String = "${BuildConfig.VERSION_NAME} (compilación ${BuildConfig.VERSION_CODE})"
    val repo: String get() = updater.repo
    val isConfigured: Boolean get() = updater.isConfigured
    val installerMessage: StateFlow<String?> = InstallEvents.message

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Consulta GitHub. Con [silent] no muestra errores (comprobación al abrir la app). */
    fun check(silent: Boolean = false) {
        if (!updater.isConfigured || _state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching { updater.latest() }.fold(
                onSuccess = { r -> if (r.versionCode > updater.currentVersionCode) UpdateState.Available(r) else UpdateState.UpToDate },
                onFailure = { e -> if (silent) UpdateState.Idle else UpdateState.Error(e.message ?: "Error al consultar GitHub") },
            )
        }
    }

    fun downloadAndInstall(release: ReleaseInfo) {
        if (!updater.canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        viewModelScope.launch {
            _state.value = UpdateState.Downloading(release, 0f)
            runCatching {
                val apk = updater.download(release) { p -> _state.value = UpdateState.Downloading(release, p) }
                _state.value = UpdateState.Installing(release)
                updater.install(apk)
            }.onFailure { e -> _state.value = UpdateState.Error(e.message ?: "No se pudo descargar la actualización") }
        }
    }

    fun openInstallPermission(release: ReleaseInfo) {
        c.appContext.startActivity(updater.installPermissionIntent())
        _state.value = UpdateState.Available(release) // al volver, el usuario pulsa de nuevo «Descargar e instalar»
    }

    fun clearInstallerMessage() = InstallEvents.publish(null)
}
