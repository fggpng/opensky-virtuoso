package com.opensky.virtuoso.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.opensky.virtuoso.OpenSkyApp
import com.opensky.virtuoso.R
import com.opensky.virtuoso.control.Activity
import com.opensky.virtuoso.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (tipo connectedDevice). Mantiene vivos el socket con la montura,
 * el bucle de seguimiento y los servidores LX200/Stellarium aunque el usuario pase a Stellarium
 * en el mismo teléfono. Muestra el estado y un botón de parada de emergencia.
 */
class TelescopeService : LifecycleService() {

    private val container get() = (application as OpenSkyApp).container
    private var observing = false

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_MOUNT) {
            lifecycleScope.launch { container.session.controller.value?.stopAll() }
            return START_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Conectado a la montura"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (observing) return START_STICKY
        observing = true
        lifecycleScope.launch {
            container.session.controller
                .flatMapLatest { c -> c?.state ?: flowOf(null) }
                .combine(container.session.servers) { st, servers -> st to servers.sumOf { it.clients.value } }
                .collect { (st, clients) ->
                    if (st == null) {
                        stopSelf()
                        return@collect
                    }
                    val text = buildString {
                        append(when (st.activity) {
                            Activity.SLEWING -> "GoTo → ${st.target?.name ?: ""}"
                            Activity.TRACKING -> "Siguiendo ${st.target?.name ?: "posición"} (${st.tracking?.label ?: ""})"
                            Activity.MANUAL -> "Movimiento manual"
                            Activity.IDLE -> "En espera"
                            Activity.DISCONNECTED -> "Desconectado"
                        })
                        if (clients > 0) append(" · $clients cliente(s) Stellarium/LX200")
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                }
        }
        return START_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TelescopeService::class.java).setAction(ACTION_STOP_MOUNT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_telescope)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "DETENER MONTURA", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mount"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP_MOUNT = "com.opensky.virtuoso.STOP_MOUNT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TelescopeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelescopeService::class.java))
        }
    }
}
