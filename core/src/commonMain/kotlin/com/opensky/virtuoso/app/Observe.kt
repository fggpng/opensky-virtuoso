package com.opensky.virtuoso.app

import com.opensky.virtuoso.platform.epochMillisNow
import com.opensky.virtuoso.platform.uiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Puente para SwiftUI: valor actual y avisos de cambio en el hilo principal. Swift lo ve como
 * `StateWatcher<HomeState>` y lo envuelve en un objeto `@Observable`.
 */
class StateWatcher<T : Any>(private val flow: StateFlow<T>) {
    val value: T get() = flow.value

    /** Llama a [onChange] con el valor actual y con cada cambio, hasta [Watch.cancel]. */
    fun watch(onChange: (T) -> Unit): Watch {
        val job = watchScope.launch(uiDispatcher) { flow.collect { onChange(it) } }
        return Watch(job)
    }

    private companion object {
        val watchScope = CoroutineScope(SupervisorJob())
    }
}

/** Suscripción de [StateWatcher.watch]. */
class Watch internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/** Marca de tiempo cada [periodMs] (refresco de relojes y listas). */
internal fun ticker(periodMs: Long): Flow<Long> = flow {
    while (true) {
        emit(epochMillisNow())
        delay(periodMs)
    }
}

/**
 * Estado derivado: se recalcula con [compute] cada vez que cambia cualquiera de [triggers]
 * (los valores se leen dentro de [compute], normalmente de StateFlows).
 */
internal fun <T> CoroutineScope.derived(vararg triggers: Flow<*>, compute: () -> T): StateFlow<T> {
    val out = MutableStateFlow(compute())
    launch {
        merge(*triggers.map { f -> f.map { } }.toTypedArray()).collect { out.value = compute() }
    }
    return out.asStateFlow()
}
