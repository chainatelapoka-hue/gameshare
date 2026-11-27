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

package com.example.proxy

import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Runtime configuration passed to a [ProxyEngine] implementation on startup.
 *
 * Designed as a flat value object so it can be marshaled cleanly across JNI when swapping
 * [KotlinProxyEngine] for a native `epoll`-based C engine.
 */
data class ProxyConfig(
    val bindHost: String,
    val port: Int,
    val mtu: Int,
    val gamingMode: Boolean,
    val authEnabled: Boolean,
    val username: String,
    val password: String,
)

/**
 * Snapshot of cumulative byte counters and active association counts from [ProxyEngine].
 */
data class EngineTelemetry(
    val totalUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val activeTcpConnections: Int = 0,
    val activeUdpAssociations: Int = 0,
    val udpRelayListening: Boolean = false,
)

/**
 * Per-client IP telemetry tracked by [ProxyEngine].
 */
data class ProxyClientTelemetry(
    val ipAddress: String,
    val activeConnections: Int,
    val bytesTransferred: Long,
)

/**
 * Abstracts binding outbound sockets and DNS lookups to the host's cellular [android.net.Network].
 *
 * Keeping this behind an interface allows [KotlinProxyEngine] to be unit-tested on the JVM and
 * makes it straightforward to pass the underlying `Network.getNetworkHandle()` into a future JNI
 * engine (`android_setsocknetwork`).
 */
interface OutboundNetworkBinder {
    /** Binds an outbound TCP [Socket] to the cellular network before `connect()`. */
    @Throws(IOException::class)
    fun bindSocket(socket: Socket)

    /** Binds an outbound UDP [DatagramSocket] to the cellular network before sending packets. */
    @Throws(IOException::class)
    fun bindDatagramSocket(socket: DatagramSocket)

    /** Resolves [host] strictly over the cellular network DNS resolvers. */
    @Throws(IOException::class)
    fun resolveAllByName(host: String): Array<InetAddress>

    /** Optional OS network handle (`Network.getNetworkHandle()`) for native JNI engines. */
    val networkHandle: Long?
        get() = null
}

/**
 * Transport-agnostic SOCKS5 proxy engine contract.
 *
 * Implementations may use non-blocking Java NIO ([KotlinProxyEngine]) or a native `epoll` C/C++
 * backend bridged through JNI without altering the Wi-Fi Direct, Service, or UI layers.
 */
interface ProxyEngine {

    /** Emits whether the SOCKS5 server is currently accepting connections. */
    val isRunning: StateFlow<Boolean>

    /** Emits cumulative byte counters and active TCP/UDP session counts. */
    val telemetry: StateFlow<EngineTelemetry>

    /** Emits per-client IP socket counts and byte totals. */
    val clientTelemetry: StateFlow<List<ProxyClientTelemetry>>

    /**
     * Starts listening on [config.bindHost]:[config.port] and forwarding outbound traffic via
     * [networkBinder].
     */
    suspend fun start(config: ProxyConfig, networkBinder: OutboundNetworkBinder)

    /** Gracefully stops the listener, tears down active TCP/UDP sessions, and frees resources. */
    suspend fun stop()
}
