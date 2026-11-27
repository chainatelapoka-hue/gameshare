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

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Pure Kotlin wire-format constants and codecs for SOCKS5 (RFC 1928) and
 * Username/Password Authentication (RFC 1929).
 */
object Socks5Protocol {
    const val VERSION_5: Byte = 0x05
    const val AUTH_VERSION_1: Byte = 0x01

    // Authentication methods (RFC 1928 §3)
    const val METHOD_NO_AUTH: Byte = 0x00
    const val METHOD_USER_PASS: Byte = 0x02
    val METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()

    // Commands (RFC 1928 §4)
    const val CMD_CONNECT: Byte = 0x01
    const val CMD_BIND: Byte = 0x02
    const val CMD_UDP_ASSOCIATE: Byte = 0x03

    // Address types (RFC 1928 §5)
    const val ATYP_IPV4: Byte = 0x01
    const val ATYP_DOMAIN: Byte = 0x03
    const val ATYP_IPV6: Byte = 0x04

    // Reply codes (RFC 1928 §6)
    const val REP_SUCCEEDED: Byte = 0x00
    const val REP_GENERAL_FAILURE: Byte = 0x01
    const val REP_CONNECTION_NOT_ALLOWED: Byte = 0x02
    const val REP_NETWORK_UNREACHABLE: Byte = 0x03
    const val REP_HOST_UNREACHABLE: Byte = 0x04
    const val REP_CONNECTION_REFUSED: Byte = 0x05
    const val REP_TTL_EXPIRED: Byte = 0x06
    const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
    const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08

    /** Parsed destination from a SOCKS5 UDP relay header (RFC 1928 §7). */
    data class UdpHeader(
        val atyp: Byte,
        val ipBytes: ByteArray?,
        val domain: String?,
        val port: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    fun buildMethodSelectionReply(method: Byte): ByteArray = byteArrayOf(VERSION_5, method)

    fun buildAuthReply(success: Boolean): ByteArray = byteArrayOf(
        AUTH_VERSION_1,
        if (success) 0x00.toByte() else 0x01.toByte(),
    )

    /**
     * Serializes a SOCKS5 server reply (`VER | REP | RSV | ATYP | BND.ADDR | BND.PORT`).
     */
    fun buildReply(rep: Byte, bindAddress: InetAddress?, bindPort: Int): ByteArray {
        val rawAddr = bindAddress?.address ?: byteArrayOf(0, 0, 0, 0)
        val atyp = when (bindAddress) {
            is Inet6Address -> ATYP_IPV6
            else -> ATYP_IPV4
        }
        val addrBytes = if (atyp == ATYP_IPV4 && rawAddr.size != 4) {
            byteArrayOf(0, 0, 0, 0)
        } else {
            rawAddr
        }
        val result = ByteArray(4 + addrBytes.size + 2)
        result[0] = VERSION_5
        result[1] = rep
        result[2] = 0x00
        result[3] = atyp
        System.arraycopy(addrBytes, 0, result, 4, addrBytes.size)
        val portOffset = 4 + addrBytes.size
        result[portOffset] = ((bindPort ushr 8) and 0xFF).toByte()
        result[portOffset + 1] = (bindPort and 0xFF).toByte()
        return result
    }

    /**
     * Parses a SOCKS5 UDP encapsulation header per RFC 1928 §7:
     * `RSV(2) | FRAG(1) | ATYP(1) | DST.ADDR(var) | DST.PORT(2) | DATA(var)`.
     *
     * Returns `null` if truncated, malformed, or if `FRAG != 0` (fragmentation unsupported).
     */
    fun parseUdpHeader(packet: ByteArray, length: Int): UdpHeader? {
        if (length < 8) return null
        val frag = packet[2].toInt() and 0xFF
        if (frag != 0) return null

        val atyp = packet[3]
        return when (atyp) {
            ATYP_IPV4 -> {
                if (length < 10) return null
                val ip = packet.copyOfRange(4, 8)
                val port = ((packet[8].toInt() and 0xFF) shl 8) or (packet[9].toInt() and 0xFF)
                UdpHeader(
                    atyp = atyp,
                    ipBytes = ip,
                    domain = null,
                    port = port,
                    payloadOffset = 10,
                    payloadLength = length - 10,
                )
            }

            ATYP_IPV6 -> {
                if (length < 22) return null
                val ip = packet.copyOfRange(4, 20)
                val port = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)
                UdpHeader(
                    atyp = atyp,
                    ipBytes = ip,
                    domain = null,
                    port = port,
                    payloadOffset = 22,
                    payloadLength = length - 22,
                )
            }

            ATYP_DOMAIN -> {
                val domainLen = packet[4].toInt() and 0xFF
                val headerLen = 5 + domainLen + 2
                if (domainLen == 0 || length < headerLen) return null
                val domain = String(packet, 5, domainLen, StandardCharsets.UTF_8)
                val portOffset = 5 + domainLen
                val port = ((packet[portOffset].toInt() and 0xFF) shl 8) or
                    (packet[portOffset + 1].toInt() and 0xFF)
                UdpHeader(
                    atyp = atyp,
                    ipBytes = null,
                    domain = domain,
                    port = port,
                    payloadOffset = headerLen,
                    payloadLength = length - headerLen,
                )
            }

            else -> null
        }
    }

    /**
     * Wraps a raw UDP payload returned by an upstream server in the RFC 1928 §7 UDP relay header.
     */
    fun buildUdpDatagram(
        sourceAddress: InetAddress,
        sourcePort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): ByteArray {
        val rawAddr = sourceAddress.address
        val atyp = if (sourceAddress is Inet6Address || rawAddr.size == 16) ATYP_IPV6 else ATYP_IPV4
        val headerSize = 4 + rawAddr.size + 2
        val out = ByteArray(headerSize + payloadLength)
        out[0] = 0x00
        out[1] = 0x00
        out[2] = 0x00 // FRAG = 0
        out[3] = atyp
        System.arraycopy(rawAddr, 0, out, 4, rawAddr.size)
        val portOffset = 4 + rawAddr.size
        out[portOffset] = ((sourcePort ushr 8) and 0xFF).toByte()
        out[portOffset + 1] = (sourcePort and 0xFF).toByte()
        if (payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, out, headerSize, payloadLength)
        }
        return out
    }

    fun isIpv4(address: InetAddress): Boolean = address is Inet4Address
}
