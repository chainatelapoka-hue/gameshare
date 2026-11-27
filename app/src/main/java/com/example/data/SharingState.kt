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

package com.example.data

/** High-level lifecycle state of the Wi-Fi Direct + SOCKS5 sharing session. */
enum class SharingStatus {
    DISCONNECTED,
    WAITING_FOR_DEVICES,
    ACTIVE,
    ERROR,
}

/**
 * Represents a peer Android device connected to the Wi-Fi Direct group and/or active through the proxy.
 *
 * @property ipAddress Peer IPv4/IPv6 address on the P2P subnet.
 * @property hostname Human-readable device name reported by Wi-Fi Direct when available.
 * @property macAddress Peer hardware MAC address reported by `WifiP2pDevice`, if available.
 * @property activeConnections Current open TCP/UDP proxy sessions from this peer.
 * @property bytesTransferred Total bytes relayed for this peer during the current session.
 */
data class ConnectedDevice(
    val ipAddress: String,
    val hostname: String? = null,
    val macAddress: String? = null,
    val activeConnections: Int = 0,
    val bytesTransferred: Long = 0L,
)

/**
 * Real-time throughput telemetry sampled every 1 second.
 *
 * @property uploadMbps Current outbound cellular upload rate in megabits per second.
 * @property downloadMbps Current inbound cellular download rate in megabits per second.
 * @property totalUploadBytes Cumulative uploaded bytes in the active session.
 * @property totalDownloadBytes Cumulative downloaded bytes in the active session.
 */
data class TrafficStats(
    val uploadMbps: Double = 0.0,
    val downloadMbps: Double = 0.0,
    val totalUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L,
)

/**
 * Unified UI state observed by the GameShare dashboard.
 */
data class GameShareUiState(
    val status: SharingStatus = SharingStatus.DISCONNECTED,
    val isStartingOrStopping: Boolean = false,
    val hasCellularInternet: Boolean = false,
    val p2pNetworkName: String? = null,
    val p2pPassphrase: String? = null,
    val socksHost: String = DEFAULT_P2P_HOST,
    val socksPort: Int = ProxySettings.DEFAULT_PORT,
    val udpRelayReady: Boolean = false,
    val activeUdpAssociations: Int = 0,
    val connectedDevices: List<ConnectedDevice> = emptyList(),
    val trafficStats: TrafficStats = TrafficStats(),
    val errorMessage: String? = null,
    val permissionsGranted: Boolean = false,
) {
    val isSharing: Boolean
        get() = status == SharingStatus.WAITING_FOR_DEVICES || status == SharingStatus.ACTIVE

    val socksAddress: String
        get() = "$socksHost:$socksPort"

    companion object {
        const val DEFAULT_P2P_HOST = "192.168.49.1"
    }
}
