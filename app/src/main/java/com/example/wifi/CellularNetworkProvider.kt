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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.proxy.OutboundNetworkBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Acquires and retains the host device's cellular data [Network] while Wi-Fi Direct is active,
 * and implements [OutboundNetworkBinder] so all upstream SOCKS5 traffic routes strictly over mobile data.
 */
class CellularNetworkProvider(context: Context) : OutboundNetworkBinder {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _cellularNetwork = MutableStateFlow<Network?>(null)
    val cellularNetwork: StateFlow<Network?> = _cellularNetwork.asStateFlow()

    private val _hasCellularInternet = MutableStateFlow(false)
    val hasCellularInternet: StateFlow<Boolean> = _hasCellularInternet.asStateFlow()

    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _cellularNetwork.value = network
            _hasCellularInternet.value = true
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (hasInternet) {
                _cellularNetwork.value = network
                _hasCellularInternet.value = true
            }
        }

        override fun onLost(network: Network) {
            if (_cellularNetwork.value == network) {
                _cellularNetwork.value = null
                _hasCellularInternet.value = false
            }
        }

        override fun onUnavailable() {
            _cellularNetwork.value = null
            _hasCellularInternet.value = false
        }
    }

    init {
        val active = connectivityManager.activeNetwork
        val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        if (caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            _cellularNetwork.value = active
            _hasCellularInternet.value = true
        }
    }

    /**
     * Requests the cellular internet [Network] so Android keeps mobile data active alongside
     * the local Wi-Fi Direct group.
     */
    @Synchronized
    fun startMonitoring() {
        if (callbackRegistered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.requestNetwork(request, networkCallback)
            callbackRegistered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "requestNetwork failed, falling back to registerNetworkCallback", e)
            runCatching {
                connectivityManager.registerNetworkCallback(request, networkCallback)
                callbackRegistered = true
            }
        }
    }

    @Synchronized
    fun stopMonitoring() {
        if (!callbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
    }

    override val networkHandle: Long?
        get() = _cellularNetwork.value?.networkHandle

    @Throws(IOException::class)
    override fun bindSocket(socket: Socket) {
        val network = _cellularNetwork.value
            ?: throw IOException("No active cellular network available for outbound TCP binding")
        network.bindSocket(socket)
    }

    @Throws(IOException::class)
    override fun bindDatagramSocket(socket: DatagramSocket) {
        val network = _cellularNetwork.value
            ?: throw IOException("No active cellular network available for outbound UDP binding")
        network.bindSocket(socket)
    }

    @Throws(IOException::class)
    override fun resolveAllByName(host: String): Array<InetAddress> {
        val network = _cellularNetwork.value
            ?: throw IOException("No active cellular network available for DNS lookup")
        return network.getAllByName(host)
    }

    companion object {
        private const val TAG = "CellularNetworkProvider"
    }
}
