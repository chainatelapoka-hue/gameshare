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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Pure Kotlin non-blocking SOCKS5 proxy server (`java.nio.channels.Selector` + Coroutines +
 * bounded DNS worker pool).
 *
 * Implements:
 * - RFC 1928 method negotiation (`NO_AUTH` / `USER_PASS`)
 * - RFC 1929 username/password authentication
 * - RFC 1928 `CONNECT` (TCP) with IPv4, IPv6, and cellular-bound async domain resolution
 * - RFC 1928 §7 `UDP ASSOCIATE` (full bidirectional UDP relay for games & voice chat)
 * - Gaming Mode socket tuning (`TCP_NODELAY`, `SO_KEEPALIVE`, `IPTOS_LOWDELAY`)
 */
class KotlinProxyEngine : ProxyEngine {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    override val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    private val _clientTelemetry = MutableStateFlow<List<ProxyClientTelemetry>>(emptyList())
    override val clientTelemetry: StateFlow<List<ProxyClientTelemetry>> = _clientTelemetry.asStateFlow()

    private val totalTxBytes = AtomicLong(0L)
    private val totalRxBytes = AtomicLong(0L)
    private val activeTcpCount = AtomicInteger(0)
    private val activeUdpCount = AtomicInteger(0)

    private val clientStats = ConcurrentHashMap<String, ClientCounter>()
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val pendingTasks = ConcurrentLinkedQueue<Runnable>()
    private val sessions: MutableSet<ClientSession> = Collections.synchronizedSet(HashSet())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var telemetryJob: Job? = null
    private var selectorThread: Thread? = null
    private var dnsExecutor: ExecutorService? = null

    @Volatile
    private var selector: Selector? = null

    @Volatile
    private var serverChannel: ServerSocketChannel? = null

    @Volatile
    private var shouldRun = false

    private var activeConfig: ProxyConfig? = null
    private var activeBinder: OutboundNetworkBinder? = null
    private var boundBindAddress: InetAddress? = null

    private val udpScratchBuffer: ByteBuffer = ByteBuffer.allocate(MAX_UDP_PACKET_BYTES)

    override suspend fun start(config: ProxyConfig, networkBinder: OutboundNetworkBinder) {
        stop()

        activeConfig = config
        activeBinder = networkBinder
        totalTxBytes.set(0L)
        totalRxBytes.set(0L)
        activeTcpCount.set(0)
        activeUdpCount.set(0)
        clientStats.clear()
        dnsCache.clear()
        pendingTasks.clear()

        val newSelector = Selector.open()
        val newServer = ServerSocketChannel.open()
        newServer.configureBlocking(false)
        runCatching { newServer.setOption(StandardSocketOptions.SO_REUSEADDR, true) }

        val targetBindAddr = runCatching { InetAddress.getByName(config.bindHost) }
            .getOrElse { InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) }
        try {
            newServer.bind(InetSocketAddress(targetBindAddr, config.port), SERVER_BACKLOG)
            boundBindAddress = targetBindAddr
        } catch (e: IOException) {
            Log.w(TAG, "Bind to ${config.bindHost}:${config.port} failed, falling back to 0.0.0.0", e)
            val wildcard = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
            try {
                newServer.bind(InetSocketAddress(wildcard, config.port), SERVER_BACKLOG)
            } catch (fallbackError: IOException) {
                runCatching { newServer.close() }
                runCatching { newSelector.close() }
                throw fallbackError
            }
            boundBindAddress = wildcard
        }
        newServer.register(newSelector, SelectionKey.OP_ACCEPT)

        selector = newSelector
        serverChannel = newServer
        shouldRun = true

        val pool = Executors.newFixedThreadPool(DNS_WORKER_THREADS) { r ->
            Thread(r, "GameShare-DNS").apply { isDaemon = true }
        }
        dnsExecutor = pool

        val loopThread = Thread({ runSelectorLoop(newSelector, newServer) }, "GameShare-NIO")
        loopThread.priority = Thread.MAX_PRIORITY
        selectorThread = loopThread
        loopThread.start()

        _isRunning.value = true
        _telemetry.value = EngineTelemetry(udpRelayListening = true)

        telemetryJob = scope.launch {
            while (isActive && shouldRun) {
                publishTelemetry()
                delay(TELEMETRY_INTERVAL_MS)
            }
        }
    }

    override suspend fun stop() {
        shouldRun = false
        telemetryJob?.cancel()
        telemetryJob = null

        selector?.wakeup()
        runCatching { serverChannel?.close() }
        runCatching { selector?.close() }

        selectorThread?.join(1500)
        selectorThread = null

        dnsExecutor?.shutdownNow()
        dnsExecutor = null

        val snapshot = synchronized(sessions) { sessions.toList() }
        snapshot.forEach { closeSession(it) }
        sessions.clear()

        serverChannel = null
        selector = null
        activeConfig = null
        activeBinder = null
        boundBindAddress = null

        activeTcpCount.set(0)
        activeUdpCount.set(0)
        _isRunning.value = false
        _telemetry.value = EngineTelemetry(udpRelayListening = false)
        _clientTelemetry.value = emptyList()
    }

    private fun runSelectorLoop(sel: Selector, server: ServerSocketChannel) {
        try {
            while (shouldRun && sel.isOpen) {
                drainPendingTasks()
                sel.select(SELECTOR_TIMEOUT_MS)
                if (!shouldRun) break
                drainPendingTasks()

                val iterator = sel.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    if (!key.isValid) continue

                    try {
                        when (val att = key.attachment()) {
                            null -> if (key.isAcceptable) acceptClient(sel, server)
                            is ClientAttachment -> handleClientKey(key, att.session)
                            is UpstreamAttachment -> handleUpstreamKey(key, att.session)
                            is UdpLocalAttachment -> handleLocalUdpRead(att.session)
                            is UdpUpstreamAttachment -> handleUpstreamUdpRead(att.session)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Channel error in selector loop: ${e.message}")
                        when (val att = key.attachment()) {
                            is ClientAttachment -> closeSession(att.session)
                            is UpstreamAttachment -> closeSession(att.session)
                            is UdpLocalAttachment -> closeSession(att.session)
                            is UdpUpstreamAttachment -> closeSession(att.session)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (shouldRun) {
                Log.e(TAG, "Selector loop terminated unexpectedly", e)
            }
        }
    }

    private fun drainPendingTasks() {
        while (true) {
            val task = pendingTasks.poll() ?: break
            runCatching { task.run() }.onFailure {
                Log.w(TAG, "Pending selector task failed", it)
            }
        }
    }

    private fun acceptClient(sel: Selector, server: ServerSocketChannel) {
        val config = activeConfig ?: return
        while (true) {
            val clientChannel = server.accept() ?: break
            clientChannel.configureBlocking(false)
            applyTcpSocketOptions(clientChannel, config)

            val remoteIp = (clientChannel.remoteAddress as? InetSocketAddress)
                ?.address?.hostAddress ?: "unknown"
            val remoteInet = (clientChannel.remoteAddress as? InetSocketAddress)?.address

            val bufSize = max(DEFAULT_IO_BUFFER_BYTES, config.mtu * 4)
            val session = ClientSession(
                clientChannel = clientChannel,
                clientIp = remoteIp,
                clientAddress = remoteInet,
                clientInBuffer = ByteBuffer.allocate(bufSize),
                upstreamInBuffer = ByteBuffer.allocate(bufSize),
            )
            val key = clientChannel.register(sel, SelectionKey.OP_READ, ClientAttachment(session))
            session.clientKey = key
            sessions.add(session)

            val counter = clientStats.computeIfAbsent(remoteIp) { ClientCounter() }
            counter.activeConnections.incrementAndGet()
            activeTcpCount.incrementAndGet()
        }
    }

    private fun handleClientKey(key: SelectionKey, session: ClientSession) {
        if (session.closed) return

        if (key.isReadable) {
            val read = session.clientChannel.read(session.clientInBuffer)
            if (read == -1) {
                closeSession(session)
                return
            }
            if (read > 0) {
                processClientInput(session)
            }
        }

        if (key.isValid && key.isWritable) {
            flushQueue(
                channel = session.clientChannel,
                queue = session.clientOutQueue,
                key = key,
                onBytesWritten = null,
            )
            if (session.closeAfterClientFlush && session.clientOutQueue.isEmpty()) {
                closeSession(session)
                return
            }
            if (session.state == SessionState.RELAYING && session.clientOutQueueBytes < LOW_WATERMARK_BYTES) {
                enableOp(session.upstreamKey, SelectionKey.OP_READ)
            }
        }
    }

    private fun processClientInput(session: ClientSession) {
        val config = activeConfig ?: return
        val buf = session.clientInBuffer

        when (session.state) {
            SessionState.GREETING -> {
                buf.flip()
                if (buf.remaining() < 2) {
                    buf.compact()
                    return
                }
                buf.mark()
                val version = buf.get()
                val nMethods = buf.get().toInt() and 0xFF
                if (version != Socks5Protocol.VERSION_5) {
                    closeSession(session)
                    return
                }
                if (buf.remaining() < nMethods) {
                    buf.reset()
                    buf.compact()
                    return
                }
                val offered = ByteArray(nMethods)
                buf.get(offered)
                buf.compact()

                val desiredMethod = if (config.authEnabled) {
                    Socks5Protocol.METHOD_USER_PASS
                } else {
                    Socks5Protocol.METHOD_NO_AUTH
                }
                val supported = offered.any { it == desiredMethod }
                if (!supported) {
                    queueToClient(
                        session,
                        Socks5Protocol.buildMethodSelectionReply(Socks5Protocol.METHOD_NO_ACCEPTABLE),
                    )
                    session.closeAfterClientFlush = true
                    return
                }

                queueToClient(session, Socks5Protocol.buildMethodSelectionReply(desiredMethod))
                session.state = if (config.authEnabled) SessionState.AUTH else SessionState.REQUEST
                if (session.clientInBuffer.position() > 0) {
                    processClientInput(session)
                }
            }

            SessionState.AUTH -> {
                buf.flip()
                if (buf.remaining() < 2) {
                    buf.compact()
                    return
                }
                buf.mark()
                val authVer = buf.get()
                val uLen = buf.get().toInt() and 0xFF
                if (authVer != Socks5Protocol.AUTH_VERSION_1 || buf.remaining() < uLen + 1) {
                    if (authVer != Socks5Protocol.AUTH_VERSION_1) {
                        closeSession(session)
                        return
                    }
                    buf.reset()
                    buf.compact()
                    return
                }
                val userBytes = ByteArray(uLen)
                buf.get(userBytes)
                val pLen = buf.get().toInt() and 0xFF
                if (buf.remaining() < pLen) {
                    buf.reset()
                    buf.compact()
                    return
                }
                val passBytes = ByteArray(pLen)
                buf.get(passBytes)
                buf.compact()

                val expectedUser = config.username.toByteArray(StandardCharsets.UTF_8)
                val expectedPass = config.password.toByteArray(StandardCharsets.UTF_8)
                val valid = MessageDigest.isEqual(userBytes, expectedUser) &&
                    MessageDigest.isEqual(passBytes, expectedPass)

                queueToClient(session, Socks5Protocol.buildAuthReply(valid))
                if (!valid) {
                    session.closeAfterClientFlush = true
                    return
                }
                session.state = SessionState.REQUEST
                if (session.clientInBuffer.position() > 0) {
                    processClientInput(session)
                }
            }

            SessionState.REQUEST -> {
                buf.flip()
                if (buf.remaining() < 4) {
                    buf.compact()
                    return
                }
                buf.mark()
                val ver = buf.get()
                val cmd = buf.get()
                buf.get() // RSV
                val atyp = buf.get()

                if (ver != Socks5Protocol.VERSION_5) {
                    closeSession(session)
                    return
                }

                var domain: String? = null
                var directIpBytes: ByteArray? = null
                when (atyp) {
                    Socks5Protocol.ATYP_IPV4 -> {
                        if (buf.remaining() < 6) {
                            buf.reset()
                            buf.compact()
                            return
                        }
                        directIpBytes = ByteArray(4).also { buf.get(it) }
                    }

                    Socks5Protocol.ATYP_IPV6 -> {
                        if (buf.remaining() < 18) {
                            buf.reset()
                            buf.compact()
                            return
                        }
                        directIpBytes = ByteArray(16).also { buf.get(it) }
                    }

                    Socks5Protocol.ATYP_DOMAIN -> {
                        if (buf.remaining() < 1) {
                            buf.reset()
                            buf.compact()
                            return
                        }
                        val len = buf.get().toInt() and 0xFF
                        if (buf.remaining() < len + 2) {
                            buf.reset()
                            buf.compact()
                            return
                        }
                        val domainBytes = ByteArray(len)
                        buf.get(domainBytes)
                        domain = String(domainBytes, StandardCharsets.UTF_8)
                    }

                    else -> {
                        buf.compact()
                        queueToClient(
                            session,
                            Socks5Protocol.buildReply(Socks5Protocol.REP_ADDRESS_TYPE_NOT_SUPPORTED, null, 0),
                        )
                        session.closeAfterClientFlush = true
                        return
                    }
                }

                val targetPort = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
                buf.compact()

                when (cmd) {
                    Socks5Protocol.CMD_CONNECT -> {
                        if (directIpBytes != null) {
                            val addr = InetAddress.getByAddress(directIpBytes)
                            beginUpstreamTcpConnect(session, addr, targetPort)
                        } else if (domain != null) {
                            resolveAndConnectTcp(session, domain, targetPort)
                        }
                    }

                    Socks5Protocol.CMD_UDP_ASSOCIATE -> {
                        setupUdpAssociation(session)
                    }

                    else -> {
                        queueToClient(
                            session,
                            Socks5Protocol.buildReply(Socks5Protocol.REP_COMMAND_NOT_SUPPORTED, null, 0),
                        )
                        session.closeAfterClientFlush = true
                    }
                }
            }

            SessionState.RESOLVING -> {
                // Keep any pipelined bytes buffered in clientInBuffer until upstream is ready.
            }

            SessionState.RELAYING -> {
                buf.flip()
                val remaining = buf.remaining()
                if (remaining > 0) {
                    val copy = ByteArray(remaining)
                    buf.get(copy)
                    queueToUpstream(session, copy)
                }
                buf.clear()
                if (session.upstreamOutQueueBytes > HIGH_WATERMARK_BYTES) {
                    disableOp(session.clientKey, SelectionKey.OP_READ)
                }
            }

            SessionState.UDP_ASSOCIATED -> {
                // RFC 1928 §7: TCP control connection carries no data after association; drain quietly.
                buf.clear()
            }
        }
    }

    private fun resolveAndConnectTcp(session: ClientSession, domain: String, port: Int) {
        val binder = activeBinder ?: run {
            closeSession(session)
            return
        }
        session.state = SessionState.RESOLVING
        disableOp(session.clientKey, SelectionKey.OP_READ)

        val cached = getCachedDns(domain)
        if (cached != null) {
            beginUpstreamTcpConnect(session, cached, port)
            return
        }

        dnsExecutor?.execute {
            val resolved = try {
                val all = binder.resolveAllByName(domain)
                val preferred = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
                if (preferred != null) {
                    dnsCache[domain] = CachedDnsEntry(preferred, System.currentTimeMillis() + DNS_CACHE_TTL_MS)
                }
                preferred
            } catch (e: Exception) {
                Log.d(TAG, "Cellular DNS lookup failed for $domain: ${e.message}")
                null
            }

            postToSelector {
                if (session.closed || !shouldRun) return@postToSelector
                if (resolved != null) {
                    beginUpstreamTcpConnect(session, resolved, port)
                } else {
                    queueToClient(
                        session,
                        Socks5Protocol.buildReply(Socks5Protocol.REP_HOST_UNREACHABLE, null, 0),
                    )
                    session.closeAfterClientFlush = true
                }
            }
        }
    }

    private fun beginUpstreamTcpConnect(session: ClientSession, address: InetAddress, port: Int) {
        val sel = selector ?: return
        val config = activeConfig ?: return
        val binder = activeBinder ?: return

        var upstream: SocketChannel? = null
        try {
            val channel = SocketChannel.open()
            upstream = channel
            channel.configureBlocking(false)
            applyTcpSocketOptions(channel, config)
            binder.bindSocket(channel.socket())

            val connected = channel.connect(InetSocketAddress(address, port))
            val ops = if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT
            val upstreamKey = channel.register(sel, ops, UpstreamAttachment(session))
            session.upstreamChannel = channel
            session.upstreamKey = upstreamKey

            if (connected) {
                onUpstreamConnected(session)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Upstream TCP connect failed to $address:$port (${e.message})")
            runCatching { upstream?.close() }
            val rep = if (e is ConnectException) {
                Socks5Protocol.REP_CONNECTION_REFUSED
            } else {
                Socks5Protocol.REP_NETWORK_UNREACHABLE
            }
            queueToClient(session, Socks5Protocol.buildReply(rep, null, 0))
            session.closeAfterClientFlush = true
        }
    }

    private fun handleUpstreamKey(key: SelectionKey, session: ClientSession) {
        if (session.closed) return
        val upstream = session.upstreamChannel ?: return

        if (key.isConnectable) {
            try {
                if (upstream.finishConnect()) {
                    onUpstreamConnected(session)
                }
            } catch (e: IOException) {
                val rep = if (e is ConnectException) {
                    Socks5Protocol.REP_CONNECTION_REFUSED
                } else {
                    Socks5Protocol.REP_HOST_UNREACHABLE
                }
                queueToClient(session, Socks5Protocol.buildReply(rep, null, 0))
                session.closeAfterClientFlush = true
                return
            }
        }

        if (key.isValid && key.isReadable) {
            val buf = session.upstreamInBuffer
            buf.clear()
            val read = upstream.read(buf)
            if (read == -1) {
                if (session.clientOutQueue.isEmpty()) {
                    closeSession(session)
                } else {
                    session.closeAfterClientFlush = true
                    disableOp(key, SelectionKey.OP_READ)
                }
                return
            }
            if (read > 0) {
                val data = ByteArray(read)
                buf.flip()
                buf.get(data)
                totalRxBytes.addAndGet(read.toLong())
                recordClientBytes(session.clientIp, read.toLong())
                queueToClient(session, data)
                if (session.clientOutQueueBytes > HIGH_WATERMARK_BYTES) {
                    disableOp(key, SelectionKey.OP_READ)
                }
            }
        }

        if (key.isValid && key.isWritable) {
            flushQueue(
                channel = upstream,
                queue = session.upstreamOutQueue,
                key = key,
                onBytesWritten = { written ->
                    totalTxBytes.addAndGet(written)
                    recordClientBytes(session.clientIp, written)
                },
            )
            if (session.upstreamOutQueueBytes < LOW_WATERMARK_BYTES) {
                enableOp(session.clientKey, SelectionKey.OP_READ)
            }
        }
    }

    private fun onUpstreamConnected(session: ClientSession) {
        val upstream = session.upstreamChannel ?: return
        val localAddr = upstream.socket().localAddress
        val localPort = upstream.socket().localPort

        session.state = SessionState.RELAYING
        queueToClient(session, Socks5Protocol.buildReply(Socks5Protocol.REP_SUCCEEDED, localAddr, localPort))

        // Flush any pipelined client payload that arrived alongside the CONNECT request
        val pendingIn = session.clientInBuffer
        if (pendingIn.position() > 0) {
            pendingIn.flip()
            val copy = ByteArray(pendingIn.remaining())
            pendingIn.get(copy)
            pendingIn.clear()
            queueToUpstream(session, copy)
        }

        enableOp(session.clientKey, SelectionKey.OP_READ)
        enableOp(session.upstreamKey, SelectionKey.OP_READ)
        disableOp(session.upstreamKey, SelectionKey.OP_CONNECT)
    }

    private fun setupUdpAssociation(session: ClientSession) {
        val sel = selector ?: return
        val config = activeConfig ?: return
        val binder = activeBinder ?: return

        try {
            val localBindAddr = boundBindAddress ?: InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
            val localUdp = DatagramChannel.open().apply {
                configureBlocking(false)
                runCatching {
                    setOption(StandardSocketOptions.SO_RCVBUF, UDP_SOCKET_BUFFER_BYTES)
                    setOption(StandardSocketOptions.SO_SNDBUF, UDP_SOCKET_BUFFER_BYTES)
                    if (config.gamingMode) {
                        socket().trafficClass = IPTOS_LOWDELAY
                    }
                }
                bind(InetSocketAddress(localBindAddr, 0))
            }

            val upstreamUdp = DatagramChannel.open().apply {
                configureBlocking(false)
                runCatching {
                    setOption(StandardSocketOptions.SO_RCVBUF, UDP_SOCKET_BUFFER_BYTES)
                    setOption(StandardSocketOptions.SO_SNDBUF, UDP_SOCKET_BUFFER_BYTES)
                    if (config.gamingMode) {
                        socket().trafficClass = IPTOS_LOWDELAY
                    }
                }
                bind(InetSocketAddress(0))
                binder.bindDatagramSocket(socket())
            }

            session.localUdpChannel = localUdp
            session.upstreamUdpChannel = upstreamUdp
            session.localUdpKey = localUdp.register(sel, SelectionKey.OP_READ, UdpLocalAttachment(session))
            session.upstreamUdpKey = upstreamUdp.register(sel, SelectionKey.OP_READ, UdpUpstreamAttachment(session))
            session.state = SessionState.UDP_ASSOCIATED
            session.isUdpAssociation = true

            activeUdpCount.incrementAndGet()

            val replyAddress = runCatching { InetAddress.getByName(config.bindHost) }.getOrElse { localBindAddr }
            val udpPort = localUdp.socket().localPort
            queueToClient(
                session,
                Socks5Protocol.buildReply(Socks5Protocol.REP_SUCCEEDED, replyAddress, udpPort),
            )
            enableOp(session.clientKey, SelectionKey.OP_READ)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to establish UDP association", e)
            runCatching { session.localUdpChannel?.close() }
            runCatching { session.upstreamUdpChannel?.close() }
            queueToClient(
                session,
                Socks5Protocol.buildReply(Socks5Protocol.REP_GENERAL_FAILURE, null, 0),
            )
            session.closeAfterClientFlush = true
        }
    }

    private fun handleLocalUdpRead(session: ClientSession) {
        val localUdp = session.localUdpChannel ?: return
        val upstreamUdp = session.upstreamUdpChannel ?: return
        val binder = activeBinder ?: return

        while (true) {
            udpScratchBuffer.clear()
            val sender = try {
                localUdp.receive(udpScratchBuffer) as? InetSocketAddress
            } catch (_: IOException) {
                null
            } ?: break

            // Ensure packet comes from the same client peer that opened the TCP control socket
            val expectedClientIp = session.clientAddress
            if (expectedClientIp != null && !expectedClientIp.isAnyLocalAddress && sender.address != expectedClientIp) {
                continue
            }
            session.clientUdpEndpoint = sender

            val totalLen = udpScratchBuffer.position()
            val rawArray = udpScratchBuffer.array()
            val header = Socks5Protocol.parseUdpHeader(rawArray, totalLen) ?: continue
            if (header.payloadLength <= 0) continue

            val ipBytes = header.ipBytes
            if (ipBytes != null) {
                val destAddr = InetAddress.getByAddress(ipBytes)
                sendUpstreamUdp(
                    session = session,
                    upstreamUdp = upstreamUdp,
                    payload = ByteBuffer.wrap(rawArray, header.payloadOffset, header.payloadLength),
                    dest = InetSocketAddress(destAddr, header.port),
                    payloadSize = header.payloadLength,
                )
            } else if (header.domain != null) {
                val cached = getCachedDns(header.domain)
                if (cached != null) {
                    sendUpstreamUdp(
                        session = session,
                        upstreamUdp = upstreamUdp,
                        payload = ByteBuffer.wrap(rawArray, header.payloadOffset, header.payloadLength),
                        dest = InetSocketAddress(cached, header.port),
                        payloadSize = header.payloadLength,
                    )
                } else {
                    val payloadCopy = rawArray.copyOfRange(
                        header.payloadOffset,
                        header.payloadOffset + header.payloadLength,
                    )
                    val domain = header.domain
                    val port = header.port
                    dnsExecutor?.execute {
                        val resolved = try {
                            val all = binder.resolveAllByName(domain)
                            val preferred = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
                            if (preferred != null) {
                                dnsCache[domain] = CachedDnsEntry(
                                    preferred,
                                    System.currentTimeMillis() + DNS_CACHE_TTL_MS,
                                )
                            }
                            preferred
                        } catch (_: Exception) {
                            null
                        }
                        if (resolved != null) {
                            postToSelector {
                                if (!session.closed && shouldRun) {
                                    sendUpstreamUdp(
                                        session = session,
                                        upstreamUdp = upstreamUdp,
                                        payload = ByteBuffer.wrap(payloadCopy),
                                        dest = InetSocketAddress(resolved, port),
                                        payloadSize = payloadCopy.size,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendUpstreamUdp(
        session: ClientSession,
        upstreamUdp: DatagramChannel,
        payload: ByteBuffer,
        dest: InetSocketAddress,
        payloadSize: Int,
    ) {
        try {
            val sent = upstreamUdp.send(payload, dest)
            if (sent > 0) {
                totalTxBytes.addAndGet(payloadSize.toLong())
                recordClientBytes(session.clientIp, payloadSize.toLong())
            }
        } catch (e: IOException) {
            Log.d(TAG, "Upstream UDP send failed to $dest: ${e.message}")
        }
    }

    private fun handleUpstreamUdpRead(session: ClientSession) {
        val localUdp = session.localUdpChannel ?: return
        val upstreamUdp = session.upstreamUdpChannel ?: return

        while (true) {
            udpScratchBuffer.clear()
            val remoteSender = try {
                upstreamUdp.receive(udpScratchBuffer) as? InetSocketAddress
            } catch (_: IOException) {
                null
            } ?: break

            val clientEndpoint = session.clientUdpEndpoint ?: continue
            val payloadLen = udpScratchBuffer.position()
            if (payloadLen <= 0) continue

            val wrapped = Socks5Protocol.buildUdpDatagram(
                sourceAddress = remoteSender.address,
                sourcePort = remoteSender.port,
                payload = udpScratchBuffer.array(),
                payloadOffset = 0,
                payloadLength = payloadLen,
            )

            try {
                localUdp.send(ByteBuffer.wrap(wrapped), clientEndpoint)
                totalRxBytes.addAndGet(payloadLen.toLong())
                recordClientBytes(session.clientIp, payloadLen.toLong())
            } catch (e: IOException) {
                Log.d(TAG, "Local UDP send failed to $clientEndpoint: ${e.message}")
            }
        }
    }

    private fun queueToClient(session: ClientSession, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        session.clientOutQueue.addLast(ByteBuffer.wrap(bytes))
        session.clientOutQueueBytes += bytes.size
        enableOp(session.clientKey, SelectionKey.OP_WRITE)
    }

    private fun queueToUpstream(session: ClientSession, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        session.upstreamOutQueue.addLast(ByteBuffer.wrap(bytes))
        session.upstreamOutQueueBytes += bytes.size
        enableOp(session.upstreamKey, SelectionKey.OP_WRITE)
    }

    private fun flushQueue(
        channel: SocketChannel,
        queue: ArrayDeque<ByteBuffer>,
        key: SelectionKey,
        onBytesWritten: ((Long) -> Unit)?,
    ) {
        while (queue.isNotEmpty()) {
            val head = queue.peekFirst() ?: break
            val before = head.remaining()
            val written = channel.write(head)
            if (written > 0) {
                onBytesWritten?.invoke(written.toLong())
                val att = key.attachment()
                if (att is ClientAttachment) {
                    att.session.clientOutQueueBytes = max(0, att.session.clientOutQueueBytes - written)
                } else if (att is UpstreamAttachment) {
                    att.session.upstreamOutQueueBytes = max(0, att.session.upstreamOutQueueBytes - written)
                }
            }
            if (head.hasRemaining()) {
                break
            } else {
                queue.pollFirst()
            }
            if (written == 0 && before > 0) break
        }
        if (queue.isEmpty()) {
            disableOp(key, SelectionKey.OP_WRITE)
        }
    }

    private fun applyTcpSocketOptions(channel: SocketChannel, config: ProxyConfig) {
        runCatching {
            val sock = channel.socket()
            sock.tcpNoDelay = config.gamingMode
            sock.keepAlive = config.gamingMode
            if (config.gamingMode) {
                sock.trafficClass = IPTOS_LOWDELAY
            }
            val bufferTarget = max(DEFAULT_IO_BUFFER_BYTES, config.mtu * 8)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, bufferTarget)
            channel.setOption(StandardSocketOptions.SO_SNDBUF, bufferTarget)
        }
    }

    private fun enableOp(key: SelectionKey?, op: Int) {
        if (key != null && key.isValid) {
            val cur = key.interestOps()
            if ((cur and op) == 0) {
                key.interestOps(cur or op)
            }
        }
    }

    private fun disableOp(key: SelectionKey?, op: Int) {
        if (key != null && key.isValid) {
            val cur = key.interestOps()
            if ((cur and op) != 0) {
                key.interestOps(cur and op.inv())
            }
        }
    }

    private fun closeSession(session: ClientSession) {
        if (session.closed) return
        session.closed = true
        sessions.remove(session)

        runCatching { session.clientKey?.cancel() }
        runCatching { session.upstreamKey?.cancel() }
        runCatching { session.localUdpKey?.cancel() }
        runCatching { session.upstreamUdpKey?.cancel() }

        runCatching { session.clientChannel.close() }
        runCatching { session.upstreamChannel?.close() }
        runCatching { session.localUdpChannel?.close() }
        runCatching { session.upstreamUdpChannel?.close() }

        activeTcpCount.updateAndGet { max(0, it - 1) }
        if (session.isUdpAssociation) {
            activeUdpCount.updateAndGet { max(0, it - 1) }
        }
        clientStats[session.clientIp]?.activeConnections?.updateAndGet { max(0, it - 1) }
    }

    private fun recordClientBytes(ip: String, bytes: Long) {
        clientStats.computeIfAbsent(ip) { ClientCounter() }.bytesTransferred.addAndGet(bytes)
    }

    private fun getCachedDns(host: String): InetAddress? {
        val entry = dnsCache[host] ?: return null
        return if (System.currentTimeMillis() <= entry.expiresAtMs) {
            entry.address
        } else {
            dnsCache.remove(host)
            null
        }
    }

    private fun postToSelector(action: Runnable) {
        pendingTasks.add(action)
        selector?.wakeup()
    }

    private fun publishTelemetry() {
        _telemetry.value = EngineTelemetry(
            totalUploadBytes = totalTxBytes.get(),
            totalDownloadBytes = totalRxBytes.get(),
            activeTcpConnections = activeTcpCount.get().coerceAtLeast(0),
            activeUdpAssociations = activeUdpCount.get().coerceAtLeast(0),
            udpRelayListening = shouldRun,
        )
        _clientTelemetry.value = clientStats.entries.map { (ip, counter) ->
            ProxyClientTelemetry(
                ipAddress = ip,
                activeConnections = counter.activeConnections.get().coerceAtLeast(0),
                bytesTransferred = counter.bytesTransferred.get(),
            )
        }
    }

    private enum class SessionState {
        GREETING,
        AUTH,
        REQUEST,
        RESOLVING,
        RELAYING,
        UDP_ASSOCIATED,
    }

    private class ClientSession(
        val clientChannel: SocketChannel,
        val clientIp: String,
        val clientAddress: InetAddress?,
        val clientInBuffer: ByteBuffer,
        val upstreamInBuffer: ByteBuffer,
    ) {
        var state: SessionState = SessionState.GREETING
        var clientKey: SelectionKey? = null
        var upstreamChannel: SocketChannel? = null
        var upstreamKey: SelectionKey? = null

        var localUdpChannel: DatagramChannel? = null
        var localUdpKey: SelectionKey? = null
        var upstreamUdpChannel: DatagramChannel? = null
        var upstreamUdpKey: SelectionKey? = null

        @Volatile
        var clientUdpEndpoint: InetSocketAddress? = null
        var isUdpAssociation: Boolean = false

        val clientOutQueue: ArrayDeque<ByteBuffer> = ArrayDeque()
        var clientOutQueueBytes: Int = 0

        val upstreamOutQueue: ArrayDeque<ByteBuffer> = ArrayDeque()
        var upstreamOutQueueBytes: Int = 0

        var closeAfterClientFlush: Boolean = false
        var closed: Boolean = false
    }

    private class ClientCounter {
        val activeConnections = AtomicInteger(0)
        val bytesTransferred = AtomicLong(0L)
    }

    private data class CachedDnsEntry(val address: InetAddress, val expiresAtMs: Long)

    private class ClientAttachment(val session: ClientSession)
    private class UpstreamAttachment(val session: ClientSession)
    private class UdpLocalAttachment(val session: ClientSession)
    private class UdpUpstreamAttachment(val session: ClientSession)

    companion object {
        private const val TAG = "KotlinProxyEngine"
        private const val SERVER_BACKLOG = 128
        private const val SELECTOR_TIMEOUT_MS = 250L
        private const val TELEMETRY_INTERVAL_MS = 500L
        private const val DNS_WORKER_THREADS = 8
        private const val DNS_CACHE_TTL_MS = 60_000L
        private const val DEFAULT_IO_BUFFER_BYTES = 16 * 1024
        private const val MAX_UDP_PACKET_BYTES = 65_535
        private const val UDP_SOCKET_BUFFER_BYTES = 256 * 1024
        private const val HIGH_WATERMARK_BYTES = 256 * 1024
        private const val LOW_WATERMARK_BYTES = 64 * 1024
        private const val IPTOS_LOWDELAY = 0x10
    }
}
