# GameShare

**GameShare** is a 100% open-source (Apache-2.0), no-root Android application (Android 10+ / API 29+) that shares your phone's cellular internet connection with nearby Android devices over **Wi-Fi Direct (P2P)** using a high-performance local **SOCKS5 proxy** (`RFC 1928` & `RFC 1929`).

It is specifically engineered for **online multiplayer gaming**, **voice chat**, and **high-speed downloads** by supporting full **`UDP ASSOCIATE`** (`RFC 1928 §7`) alongside **`CONNECT`** (TCP), explicit cellular socket binding (`Network.bindSocket()`), `TCP_NODELAY` (Gaming Mode), and a persistent `connectedDevice` Foreground Service.

---

## Architecture Overview

- **Transport Layer (`wifi/`)**:
  - `WifiDirectManager` creates a Wi-Fi Direct Group (`WifiP2pManager.createGroup`), making the host phone the Group Owner (typically `192.168.49.1`).
  - `CellularNetworkProvider` acquires the active cellular `Network` (`TRANSPORT_CELLULAR` + `NET_CAPABILITY_INTERNET`) and exposes `OutboundNetworkBinder`. Every outbound upstream TCP `SocketChannel`, UDP `DatagramChannel`, and DNS query (`Network.getAllByName`) is bound explicitly to the cellular network so Wi-Fi Direct stays purely local-link transport.
- **Proxy Engine (`proxy/`)**:
  - `ProxyEngine` defines a clean, transport-agnostic engine interface (`start`, `stop`, `telemetry`, `clientTelemetry`, `isRunning`).
  - `KotlinProxyEngine` implements `ProxyEngine` using non-blocking `java.nio.channels` (`Selector`, `ServerSocketChannel`, `SocketChannel`, `DatagramChannel`) with a dedicated async DNS worker pool, supporting:
    - **SOCKS5 `CONNECT`** (`0x01`) for TCP traffic (HTTPS, game patches, downloads)
    - **SOCKS5 `UDP ASSOCIATE`** (`0x03`, RFC 1928 §7) for real-time UDP game packets and VoIP
    - **Optional RFC 1929 Username/Password Auth** (`0x02`)
    - **Gaming Mode** (`TCP_NODELAY`, `SO_KEEPALIVE`, `IPTOS_LOWDELAY`) & configurable MTU / port
- **Foreground Service (`service/`)**:
  - `GameShareService` (`foregroundServiceType="connectedDevice"`) + `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY` lock keeps the proxy responsive even with the screen off.
- **UI (`ui/`)**:
  - Single-screen Jetpack Compose Material 3 Dashboard (`Start / Stop Sharing`, live status pill, UDP Relay indicator, 1-second speed telemetry, P2P network + SOCKS5 copy card, expandable device details) + Settings screen.

---

## (a) How to Connect an Android Client Device

### Step 1: Start Sharing on the Host Device
1. Open **GameShare** on the host phone (with mobile data enabled) and grant the requested permissions (`NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`).
2. Tap **Start Sharing**.
3. Wait until the status pill changes to **Waiting for devices** and the **P2P Network & SOCKS5** info card displays:
   - **Network Name (SSID)**: e.g. `DIRECT-xx-Android_xxxx`
   - **Passphrase**: tap the eye icon to reveal or copy it
   - **SOCKS5 Address**: e.g. `192.168.49.1:1080`

### Step 2: Join the Wi-Fi Direct Group on the Client Device
You can connect the client Android phone in either of two ways:
- **Method A (Standard Wi-Fi — Simplest):**
  On the client phone, open **Settings → Network & internet → Wi-Fi**, select the `DIRECT-xx-...` network shown in GameShare, and enter the displayed passphrase.
- **Method B (Wi-Fi Direct Menu):**
  On the client phone, open **Settings → Network & internet → Wi-Fi → Wi-Fi preferences → Advanced → Wi-Fi Direct**, and tap the host device to pair.

### Step 3: Route Traffic Through the SOCKS5 Proxy
> **Important Note on Android's Built-In Wi-Fi Proxy:**  
> Android's native Wi-Fi *Advanced → Proxy* setting only supports **HTTP/HTTPS** proxies and does **not** forward **UDP** packets or raw TCP game sockets.

To get **full TCP + UDP gaming and voice chat** without root on the client device:
1. Install an open-source local-VPN SOCKS5 client / `tun2socks` app on the client device (for example: **SocksDroid**, **hev-socks5-tunnel** / **Tun2Socks**, or a compatible open-source SOCKS5 VPN wrapper).
2. Configure the client proxy settings with the values from GameShare's info card:
   - **Proxy Type:** `SOCKS5`
   - **Server / Host:** `192.168.49.1` (or the exact IP shown in GameShare)
   - **Port:** `1080` (or your configured port in Settings)
   - **UDP Forwarding:** **Enabled** (required for online games & voice chat)
   - **Username / Password:** Enter only if you enabled Authentication in GameShare Settings.
3. Connect the client's SOCKS5 VPN toggle. GameShare's status pill will switch to **Active**, and live **Upload / Download Mbps** + **Connected Devices** will update every second.

*(For browser-only or per-app HTTP/SOCKS testing without a VPN wrapper, apps like Firefox or Termux/curl can target `socks5h://192.168.49.1:1080` directly.)*

---

## (b) Roadmap: Swapping `KotlinProxyEngine` for a Native `epoll`-Based C Engine (1,000+ Concurrent Connections)

`GameShare` isolates all socket I/O behind the `com.example.proxy.ProxyEngine` interface so the Kotlin NIO engine can be swapped out cleanly for an ultra-low-overhead C/C++ engine via JNI without touching the UI, Foreground Service, or Wi-Fi Direct layers.

### Why Move to Native C + `epoll`?
While `java.nio.channels.Selector` handles dozens of gaming/download connections cleanly in Kotlin, scaling to **1,000+ concurrent connections** (heavy download managers, torrent swarms, multi-client lobbies) benefits from:
- Zero JVM GC pauses on the hot packet loop
- Edge-triggered `epoll_wait` (`EPOLLET`) + `SO_REUSEPORT` multi-reactor worker threads pinned per CPU core
- Zero-copy TCP forwarding via Linux `splice(2)`
- Batched UDP forwarding via `recvmmsg(2)` / `sendmmsg(2)` for high packet-per-second (PPS) gaming streams

### Migration Steps
1. **Add NDK + CMake Module (`app/src/main/cpp/`)**:
   - Configure `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }` in `app/build.gradle.kts` and link `android` (`libandroid.so`) + `log`.
2. **Implement `NativeProxyEngine : ProxyEngine`**:
   - Create `NativeProxyEngine.kt` implementing `ProxyEngine.start(config, networkBinder)`, `stop()`, and exposing `statsFlow`, `clientsFlow`, and `udpRelayActiveFlow`.
   - Pass `CellularNetwork.networkHandle` (`Network.getNetworkHandle()` from API 23+) across JNI as a `jlong`.
3. **Bind Outbound Sockets in C via Android NDK Multinetwork API**:
   - Include `<android/multinetwork.h>` in C.
   - Immediately after creating every outbound upstream `socket(AF_INET/AF_INET6, ...)` in C, call:
     ```c
     int rc = android_setsocknetwork((net_handle_t)cellular_net_handle, upstream_fd);
     ```
   - Resolve hostnames (`ATYP = 0x03`) using `android_getaddrinfofornetwork((net_handle_t)cellular_net_handle, ...)` on a dedicated non-blocking resolver thread pool so DNS never blocks the `epoll` reactor!
4. **Apply Gaming & MTU Socket Options in C**:
   - `TCP_NODELAY = 1`, `SO_KEEPALIVE = 1`, `TCP_KEEPIDLE` / `TCP_KEEPINTVL` (low keep-alive), `IP_TOS = IPTOS_LOWDELAY`, and `TCP_MAXSEG` clamping derived from `config.mtu` (1200–1500).
5. **Bridge Telemetry Lock-Free**:
   - Maintain `std::atomic<uint64_t>` / C11 `_Atomic uint64_t` counters for `tx_bytes` and `rx_bytes` and a lock-free or `pthread_rwlock` client table queried once per second by the Kotlin `GameShareCoordinator` via JNI (`nativeGetStats()`), feeding the existing `StateFlow`s.

---

## License

Licensed under the **Apache License, Version 2.0**. See [`LICENSE`](LICENSE) for the full license text.
