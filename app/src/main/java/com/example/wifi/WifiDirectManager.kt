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

package com.example.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.GameShareUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Metadata for a peer device visible in the active `WifiP2pGroup`. */
data class P2pPeerInfo(
    val deviceName: String,
    val macAddress: String,
    val ipAddress: String?,
)

/** Observable state of the local Wi-Fi Direct Group Owner session. */
data class WifiDirectState(
    val isP2pEnabled: Boolean = true,
    val isGroupFormed: Boolean = false,
    val groupOwnerAddress: String = GameShareUiState.DEFAULT_P2P_HOST,
    val networkName: String? = null,
    val passphrase: String? = null,
    val peers: List<P2pPeerInfo> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Wraps [WifiP2pManager] to create and manage a Wi-Fi Direct Group Owner (`createGroup`)
 * without using any deprecated P2P broadcast extras.
 */
class WifiDirectManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private var receiverRegistered = false
    private var retryCreateOnBusy = false

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    )
                    val enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.update {
                        it.copy(
                            isP2pEnabled = enabled,
                            errorMessage = if (!enabled) "Enable Wi-Fi on this device to share via Wi-Fi Direct." else null,
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    refreshGroupAndConnectionInfo()
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    refreshGroupAndConnectionInfo()
                }
            }
        }
    }

    fun register() {
        val mgr = manager ?: return
        if (channel == null) {
            channel = mgr.initialize(appContext, Looper.getMainLooper()) {
                Log.w(TAG, "WifiP2pManager channel disconnected")
            }
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                appContext,
                p2pReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
    }

    /**
     * Creates a Wi-Fi Direct group making this device the Group Owner.
     * Caller must verify runtime permissions (`NEARBY_WIFI_DEVICES` / `ACCESS_FINE_LOCATION`) first.
     */
    @SuppressLint("MissingPermission")
    fun createGroup(onResult: (Boolean, String?) -> Unit) {
        register()
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            val err = "Wi-Fi Direct hardware is unavailable on this device."
            _state.update { it.copy(errorMessage = err) }
            onResult(false, err)
            return
        }

        retryCreateOnBusy = true
        _state.update { it.copy(errorMessage = null) }

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshGroupAndConnectionInfo()
                onResult(true, null)
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && retryCreateOnBusy) {
                    retryCreateOnBusy = false
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            createGroupInternal(mgr, ch, onResult)
                        }

                        override fun onFailure(removeReason: Int) {
                            createGroupInternal(mgr, ch, onResult)
                        }
                    })
                } else {
                    val msg = mapFailureReason(reason)
                    _state.update { it.copy(errorMessage = msg) }
                    onResult(false, msg)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroupInternal(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onResult: (Boolean, String?) -> Unit,
    ) {
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshGroupAndConnectionInfo()
                onResult(true, null)
            }

            override fun onFailure(reason: Int) {
                val msg = mapFailureReason(reason)
                _state.update { it.copy(errorMessage = msg) }
                onResult(false, msg)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun removeGroup(onComplete: (() -> Unit)? = null) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            _state.value = WifiDirectState()
            onComplete?.invoke()
            return
        }
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.update {
                    it.copy(
                        isGroupFormed = false,
                        networkName = null,
                        passphrase = null,
                        peers = emptyList(),
                        errorMessage = null,
                    )
                }
                onComplete?.invoke()
            }

            override fun onFailure(reason: Int) {
                _state.update {
                    it.copy(
                        isGroupFormed = false,
                        networkName = null,
                        passphrase = null,
                        peers = emptyList(),
                    )
                }
                onComplete?.invoke()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun refreshGroupAndConnectionInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                val ownerIp = info.groupOwnerAddress?.hostAddress ?: GameShareUiState.DEFAULT_P2P_HOST
                _state.update {
                    it.copy(
                        isGroupFormed = true,
                        groupOwnerAddress = ownerIp,
                        errorMessage = null,
                    )
                }
            }
        }

        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group != null) {
                val arpMap = readNeighborArpTable()
                val clients = group.clientList.map { device: WifiP2pDevice ->
                    val mac = device.deviceAddress?.lowercase()
                    P2pPeerInfo(
                        deviceName = device.deviceName.orEmpty().ifBlank { "Android Device" },
                        macAddress = device.deviceAddress.orEmpty(),
                        ipAddress = mac?.let { arpMap[it] },
                    )
                }
                _state.update {
                    it.copy(
                        isGroupFormed = true,
                        networkName = group.networkName,
                        passphrase = group.passphrase,
                        peers = clients,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isGroupFormed = false,
                        networkName = null,
                        passphrase = null,
                        peers = emptyList(),
                    )
                }
            }
        }
    }

    /**
     * Best-effort lookup of MAC -> IPv4 from `/proc/net/arp` (available on some Android builds;
     * safely returns empty map if restricted by SELinux on newer devices).
     */
    private fun readNeighborArpTable(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        runCatching {
            File("/proc/net/arp").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3].lowercase()
                        if (mac != "00:00:00:00:00:00" && ip.contains('.')) {
                            result[mac] = ip
                        }
                    }
                }
            }
        }
        return result
    }

    private fun mapFailureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Turn Wi-Fi off and on, then retry."
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Could not start Wi-Fi Direct group (code $reason). Ensure Wi-Fi and Nearby permissions are enabled."
            } else {
                "Could not start Wi-Fi Direct group (code $reason). Ensure Wi-Fi and Location are enabled."
            }
        }
    }

    companion object {
        private const val TAG = "WifiDirectManager"
    }
}
