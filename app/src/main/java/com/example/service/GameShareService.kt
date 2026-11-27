/*
 * Copyright 2026 GameShare Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.GameShareApplication
import com.example.MainActivity
import com.example.R
import com.example.data.GameShareUiState
import com.example.data.SharingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground Service (`connectedDevice` type) keeping Wi-Fi Direct and the local SOCKS5 proxy
 * alive when the screen turns off or the user switches apps.
 */
class GameShareService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null

    private val coordinator: GameShareCoordinator
        get() = (application as GameShareApplication).coordinator

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                coordinator.stopSharing {
                    stopForegroundAndSelf()
                }
            }

            ACTION_TOGGLE -> {
                if (coordinator.uiState.value.isSharing) {
                    coordinator.stopSharing { stopForegroundAndSelf() }
                } else {
                    startAsForeground()
                    coordinator.startSharing { started ->
                        if (!started) stopForegroundAndSelf()
                    }
                }
            }

            ACTION_START, null -> {
                startAsForeground()
                coordinator.startSharing { started ->
                    if (!started) stopForegroundAndSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification(coordinator.uiState.value)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (stateObserverJob == null) {
            stateObserverJob = serviceScope.launch {
                coordinator.uiState.collect { state ->
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    private fun stopForegroundAndSelf() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: GameShareUiState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val actionLabel: String
        val actionIntent: PendingIntent
        if (state.isSharing) {
            actionLabel = getString(R.string.action_stop_sharing)
            actionIntent = PendingIntent.getService(
                this,
                REQUEST_TOGGLE,
                Intent(this, GameShareService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            actionLabel = getString(R.string.action_start_sharing)
            actionIntent = PendingIntent.getService(
                this,
                REQUEST_TOGGLE,
                Intent(this, GameShareService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val statusTitle = when (state.status) {
            SharingStatus.ACTIVE -> "GameShare Active • ${state.socksAddress}"
            SharingStatus.WAITING_FOR_DEVICES -> "Waiting for devices • ${state.socksAddress}"
            SharingStatus.ERROR -> "GameShare Error"
            SharingStatus.DISCONNECTED -> "GameShare Disconnected"
        }

        val speedLine = String.format(
            Locale.US,
            "%d device(s) • ↑ %.2f Mbps  ↓ %.2f Mbps",
            state.connectedDevices.size,
            state.trafficStats.uploadMbps,
            state.trafficStats.downloadMbps,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gameshare)
            .setContentTitle(statusTitle)
            .setContentText(speedLine)
            .setContentIntent(openAppIntent)
            .setOngoing(state.isSharing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, actionLabel, actionIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.action.START_SHARING"
        const val ACTION_STOP = "com.example.action.STOP_SHARING"
        const val ACTION_TOGGLE = "com.example.action.TOGGLE_SHARING"

        // Same value as ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE (API 29+). The constant
        // is inlined, so we don't load ServiceInfo on Android 8.1, where that class doesn't exist.
        private const val FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE = 1 shl 4

        private const val CHANNEL_ID = "gameshare_proxy_channel"
        private const val NOTIFICATION_ID = 1080
        private const val REQUEST_OPEN_APP = 100
        private const val REQUEST_TOGGLE = 101

        fun startSharing(context: Context) {
            val intent = Intent(context, GameShareService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopSharing(context: Context) {
            val intent = Intent(context, GameShareService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
