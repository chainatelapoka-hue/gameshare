package com.example.proxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class Socks5EngineTest {

    private val loopbackBinder = object : OutboundNetworkBinder {
        override fun bindSocket(socket: Socket) = Unit
        override fun bindDatagramSocket(socket: DatagramSocket) = Unit
        override fun resolveAllByName(host: String): Array<InetAddress> =
            arrayOf(InetAddress.getByName(host))
    }

    @Test
    fun `parse and build UDP header roundtrip`() {
        val addr = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val payload = "ping-game-packet".toByteArray(StandardCharsets.UTF_8)
        val packet = Socks5Protocol.buildUdpDatagram(addr, 27015, payload, 0, payload.size)

        val parsed = Socks5Protocol.parseUdpHeader(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(Socks5Protocol.ATYP_IPV4, parsed!!.atyp)
        assertEquals(27015, parsed.port)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), parsed.ipBytes)
        val extracted = packet.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength)
        assertArrayEquals(payload, extracted)
    }

    @Test
    fun `parseUdpHeader rejects fragmented packets`() {
        val packet = byteArrayOf(0x00, 0x00, 0x01, 0x01, 127, 0, 0, 1, 0x00, 0x50, 0x41)
        assertNull(Socks5Protocol.parseUdpHeader(packet, packet.size))
    }

    @Test
    fun `KotlinProxyEngine relays TCP CONNECT with RFC 1929 auth`() = runBlocking {
        val echoServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val echoThread = Thread {
            echoServer.accept().use { s ->
                val buf = ByteArray(64)
                val n = s.getInputStream().read(buf)
                if (n > 0) s.getOutputStream().write(buf, 0, n)
            }
        }.apply { start() }

        val proxyPort = findFreePort()
        val engine = KotlinProxyEngine()
        engine.start(
            ProxyConfig(
                bindHost = "127.0.0.1",
                port = proxyPort,
                mtu = 1350,
                gamingMode = true,
                authEnabled = true,
                username = "gamer",
                password = "secret",
            ),
            loopbackBinder,
        )

        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3000
                val out = DataOutputStream(client.getOutputStream())
                val inp = DataInputStream(client.getInputStream())

                // Greeting offering NO_AUTH and USER_PASS
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                out.flush()
                assertEquals(0x05, inp.readByte().toInt() and 0xFF)
                assertEquals(0x02, inp.readByte().toInt() and 0xFF)

                // RFC 1929 auth
                val u = "gamer".toByteArray()
                val p = "secret".toByteArray()
                out.writeByte(0x01)
                out.writeByte(u.size)
                out.write(u)
                out.writeByte(p.size)
                out.write(p)
                out.flush()
                assertEquals(0x01, inp.readByte().toInt() and 0xFF)
                assertEquals(0x00, inp.readByte().toInt() and 0xFF)

                // CONNECT 127.0.0.1:echoServer.localPort
                val port = echoServer.localPort
                out.write(
                    byteArrayOf(
                        0x05, 0x01, 0x00, 0x01,
                        127, 0, 0, 1,
                        ((port ushr 8) and 0xFF).toByte(),
                        (port and 0xFF).toByte(),
                    ),
                )
                out.flush()

                val replyHeader = ByteArray(10)
                inp.readFully(replyHeader)
                assertEquals(0x05, replyHeader[0].toInt() and 0xFF)
                assertEquals(0x00, replyHeader[1].toInt() and 0xFF)

                // Echo data through tunnel
                val msg = "hello-gameshare-tcp".toByteArray()
                out.write(msg)
                out.flush()
                val echoed = ByteArray(msg.size)
                inp.readFully(echoed)
                assertArrayEquals(msg, echoed)
            }
        } finally {
            engine.stop()
            echoServer.close()
            echoThread.join(1000)
        }
    }

    @Test
    fun `KotlinProxyEngine relays UDP ASSOCIATE packets bidirectionally`() = runBlocking {
        val udpEcho = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val udpThread = Thread {
            val recvBuf = ByteArray(256)
            val pkt = DatagramPacket(recvBuf, recvBuf.size)
            runCatching {
                udpEcho.receive(pkt)
                udpEcho.send(DatagramPacket(pkt.data, pkt.length, pkt.address, pkt.port))
            }
        }.apply { start() }

        val proxyPort = findFreePort()
        val engine = KotlinProxyEngine()
        engine.start(
            ProxyConfig(
                bindHost = "127.0.0.1",
                port = proxyPort,
                mtu = 1350,
                gamingMode = true,
                authEnabled = false,
                username = "",
                password = "",
            ),
            loopbackBinder,
        )

        try {
            Socket("127.0.0.1", proxyPort).use { control ->
                control.soTimeout = 3000
                val out = DataOutputStream(control.getOutputStream())
                val inp = DataInputStream(control.getInputStream())

                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x05, inp.readByte().toInt() and 0xFF)
                assertEquals(0x00, inp.readByte().toInt() and 0xFF)

                // UDP ASSOCIATE 0.0.0.0:0
                out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()

                val reply = ByteArray(10)
                inp.readFully(reply)
                assertEquals(0x00, reply[1].toInt() and 0xFF)
                val relayPort = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)

                DatagramSocket(0, InetAddress.getLoopbackAddress()).use { clientUdp ->
                    clientUdp.soTimeout = 3000
                    val payload = "udp-voice-frame".toByteArray()
                    val wrapped = Socks5Protocol.buildUdpDatagram(
                        InetAddress.getLoopbackAddress(),
                        udpEcho.localPort,
                        payload,
                        0,
                        payload.size,
                    )
                    clientUdp.send(
                        DatagramPacket(wrapped, wrapped.size, InetAddress.getLoopbackAddress(), relayPort),
                    )

                    val respBuf = ByteArray(256)
                    val respPkt = DatagramPacket(respBuf, respBuf.size)
                    clientUdp.receive(respPkt)
                    val parsed = Socks5Protocol.parseUdpHeader(respPkt.data, respPkt.length)
                    assertNotNull(parsed)
                    val actual = respPkt.data.copyOfRange(
                        parsed!!.payloadOffset,
                        parsed.payloadOffset + parsed.payloadLength,
                    )
                    assertArrayEquals(payload, actual)
                }
            }
        } finally {
            engine.stop()
            udpEcho.close()
            udpThread.join(1000)
        }
    }

    private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
}
