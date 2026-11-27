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

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.example.data.ConnectedDevice
import com.example.data.GameShareUiState
import com.example.data.ProxySettings
import com.example.data.SettingsRepository
import com.example.data.SharingStatus
import com.example.data.TrafficStats
import com.example.proxy.ProxyClientTelemetry
import com.example.proxy.ProxyConfig
import com.example.proxy.ProxyEngine
import com.example.wifi.CellularNetworkProvider
import com.example.wifi.WifiDirectManager
import com.example.wifi.WifiDirectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates the Wi-Fi Direct Group Owner lifecycle, cellular network binding,
 * SOCKS5 [ProxyEngine], wake/low-latency Wi-Fi locks, and 1-second speed telemetry.
 */
class GameShareCoordinator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val cellularProvider: CellularNetworkProvider,
    private val wifiDirectManager: WifiDirectManager,
    private val proxyEngine: ProxyEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(GameShareUiState())
    val uiState: StateFlow<GameShareUiState> = _uiState.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    private var speedSamplerJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var sharingRequested = false

    init {
        wifiDirectManager.register()
        cellularProvider.startMonitoring()

        scope.launch {
            combine(
                wifiDirectManager.state,
                cellularProvider.hasCellularInternet,
                proxyEngine.isRunning,
                proxyEngine.telemetry,
                proxyEngine.clientTelemetry,
            ) { p2p, hasCell, running, engineTel, clients ->
                Triple(p2p, hasCell, Triple(running, engineTel, clients))
            }.combine(_trafficStats) { (p2p, hasCell, engineTriple), traffic ->
                buildUiState(
                    p2p = p2p,
                    hasCellular = hasCell,
                    engineRunning = engineTriple.first,
                    udpRelayReady = engineTriple.second.udpRelayListening,
                    activeTcp = engineTriple.second.activeTcpConnections,
                    activeUdp = engineTriple.second.activeUdpAssociations,
                    proxyClients = engineTriple.third,
                    traffic = traffic,
                )
            }.combine(settingsRepository.settingsFlow) { state, settings ->
                state.copy(
                    socksPort = if (state.isSharing) state.socksPort else settings.port,
                )
            }.collect { combined ->
                _uiState.value = combined
            }
        }
    }

    fun updatePermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun startSharing(onStarted: ((Boolean) -> Unit)? = null) {
        if (sharingRequested || _uiState.value.isStartingOrStopping) {
            onStarted?.invoke(true)
            return
        }
        sharingRequested = true
        _uiState.update { it.copy(isStartingOrStopping = true, errorMessage = null) }

        cellularProvider.startMonitoring()
        acquireLocks()

        wifiDirectManager.createGroup { success, err ->
            if (!success) {
                sharingRequested = false
                releaseLocks()
                _uiState.update {
                    it.copy(
                        status = SharingStatus.ERROR,
                        isStartingOrStopping = false,
                        errorMessage = err ?: "Failed to create Wi-Fi Direct group.",
                    )
                }
                onStarted?.invoke(false)
                return@createGroup
            }

            scope.launch {
                // Give WifiP2pManager a brief moment to populate groupOwnerAddress if needed
                delay(250L)
                wifiDirectManager.refreshGroupAndConnectionInfo()
                val settings: ProxySettings = settingsRepository.settingsFlow.first()
                val bindHost = wifiDirectManager.state.value.groupOwnerAddress
                    .ifBlank { GameShareUiState.DEFAULT_P2P_HOST }

                try {
                    proxyEngine.start(
                        config = ProxyConfig(
                            bindHost = bindHost,
                            port = settings.port,
                            mtu = settings.mtu,
                            gamingMode = settings.gamingMode,
                            authEnabled = settings.authEnabled,
                            username = settings.username,
                            password = settings.password,
                        ),
                        networkBinder = cellularProvider,
                    )
                    startSpeedSampler()
                    _uiState.update {
                        it.copy(
                            socksHost = bindHost,
                            socksPort = settings.port,
                            isStartingOrStopping = false,
                            errorMessage = null,
                        )
                    }
                    onStarted?.invoke(true)
                } catch (e: Exception) {
                    sharingRequested = false
                    wifiDirectManager.removeGroup()
                    releaseLocks()
                    _uiState.update {
                        it.copy(
                            status = SharingStatus.ERROR,
                            isStartingOrStopping = false,
                            errorMessage = e.message ?: "Failed to bind SOCKS5 server.",
                        )
                    }
                    onStarted?.invoke(false)
                }
            }
        }
    }

    fun stopSharing(onStopped: (() -> Unit)? = null) {
        sharingRequested = false
        _uiState.update { it.copy(isStartingOrStopping = true, errorMessage = null) }

        speedSamplerJob?.cancel()
        speedSamplerJob = null
        _trafficStats.value = TrafficStats()

        scope.launch {
            proxyEngine.stop()
            wifiDirectManager.removeGroup {
                releaseLocks()
                _uiState.update {
                    it.copy(
                        status = SharingStatus.DISCONNECTED,
                        isStartingOrStopping = false,
                        errorMessage = null,
                    )
                }
                onStopped?.invoke()
            }
        }
    }

    private fun startSpeedSampler() {
        speedSamplerJob?.cancel()
        speedSamplerJob = scope.launch {
            var prevTx = 0L
            var prevRx = 0L
            var prevTimeNs = System.nanoTime()

            while (isActive) {
                delay(1_000L)
                val nowNs = System.nanoTime()
                val elapsedSec = ((nowNs - prevTimeNs).coerceAtLeast(1L)) / 1_000_000_000.0
                val tel = proxyEngine.telemetry.value

                val deltaTx = (tel.totalUploadBytes - prevTx).coerceAtLeast(0L)
                val deltaRx = (tel.totalDownloadBytes - prevRx).coerceAtLeast(0L)

                prevTx = tel.totalUploadBytes
                prevRx = tel.totalDownloadBytes
                prevTimeNs = nowNs

                val upMbps = (deltaTx * 8.0) / (elapsedSec * 1_000_000.0)
                val downMbps = (deltaRx * 8.0) / (elapsedSec * 1_000_000.0)

                _trafficStats.value = TrafficStats(
                    uploadMbps = upMbps,
                    downloadMbps = downMbps,
                    totalUploadBytes = tel.totalUploadBytes,
                    totalDownloadBytes = tel.totalDownloadBytes,
                )
            }
        }
    }

    private fun buildUiState(
        p2p: WifiDirectState,
        hasCellular: Boolean,
        engineRunning: Boolean,
        udpRelayReady: Boolean,
        activeTcp: Int,
        activeUdp: Int,
        proxyClients: List<ProxyClientTelemetry>,
        traffic: TrafficStats,
    ): GameShareUiState {
        val devices = mergeConnectedDevices(p2p, proxyClients)
        val current = _uiState.value
        val errorMsg = current.errorMessage ?: p2p.errorMessage

        val status = when {
            errorMsg != null && !engineRunning -> SharingStatus.ERROR
            !sharingRequested && !engineRunning -> SharingStatus.DISCONNECTED
            devices.isNotEmpty() || activeTcp > 0 || activeUdp > 0 -> SharingStatus.ACTIVE
            else -> SharingStatus.WAITING_FOR_DEVICES
        }

        return current.copy(
            status = status,
            hasCellularInternet = hasCellular,
            p2pNetworkName = p2p.networkName,
            p2pPassphrase = p2p.passphrase,
            socksHost = p2p.groupOwnerAddress.ifBlank { GameShareUiState.DEFAULT_P2P_HOST },
            udpRelayReady = udpRelayReady && engineRunning,
            activeUdpAssociations = activeUdp,
            connectedDevices = devices,
            trafficStats = traffic,
            errorMessage = errorMsg,
        )
    }

    private fun mergeConnectedDevices(
        p2p: WifiDirectState,
        proxyClients: List<ProxyClientTelemetry>,
    ): List<ConnectedDevice> {
        val byIp = LinkedHashMap<String, ConnectedDevice>()

        proxyClients.forEach { client ->
            if (client.activeConnections > 0 || client.bytesTransferred > 0L) {
                byIp[client.ipAddress] = ConnectedDevice(
                    ipAddress = client.ipAddress,
                    hostname = null,
                    macAddress = null,
                    activeConnections = client.activeConnections,
                    bytesTransferred = client.bytesTransferred,
                )
            }
        }

        val combined = mutableListOf<ConnectedDevice>()
        val unmatchedClients = byIp.values.toMutableList()

        p2p.peers.forEach { peer ->
            val matchedByIp = peer.ipAddress?.let { ip ->
                val existing = byIp.remove(ip)
                if (existing != null) unmatchedClients.remove(existing)
                existing
            }
            combined.add(
                ConnectedDevice(
                    ipAddress = peer.ipAddress ?: matchedByIp?.ipAddress ?: "P2P Peer",
                    hostname = peer.deviceName,
                    macAddress = peer.macAddress,
                    activeConnections = matchedByIp?.activeConnections ?: 0,
                    bytesTransferred = matchedByIp?.bytesTransferred ?: 0L,
                ),
            )
        }

        combined.addAll(unmatchedClients)
        return combined
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameShare::ProxyWakeLock")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "GameShare::LowLatencyWifiLock",
                ).apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
    }
}
