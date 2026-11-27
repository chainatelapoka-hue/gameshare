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

/**
 * User-configurable networking and security parameters for the GameShare SOCKS5 proxy.
 *
 * @property mtu Preferred MTU override in bytes (clamped between 1200 and 1500, default 1350).
 * @property port Local TCP listening port for the SOCKS5 server (default 1080).
 * @property authEnabled Whether RFC 1929 username/password authentication is required.
 * @property username SOCKS5 authentication username when [authEnabled] is true.
 * @property password SOCKS5 authentication password when [authEnabled] is true.
 * @property gamingMode Enables low-latency socket tuning (`TCP_NODELAY`, low keep-alive, `IPTOS_LOWDELAY`).
 */
data class ProxySettings(
    val mtu: Int = DEFAULT_MTU,
    val port: Int = DEFAULT_PORT,
    val authEnabled: Boolean = false,
    val username: String = DEFAULT_USERNAME,
    val password: String = "",
    val gamingMode: Boolean = true,
) {
    companion object {
        const val MIN_MTU = 1200
        const val MAX_MTU = 1500
        const val DEFAULT_MTU = 1350
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val DEFAULT_PORT = 1080
        const val DEFAULT_USERNAME = "gamer"
    }
}
