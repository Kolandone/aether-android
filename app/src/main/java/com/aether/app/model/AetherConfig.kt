package com.aether.app.model

data class AetherConfig(
    val protocol: Protocol = Protocol.MASQUE,
    val scanMode: ScanMode = ScanMode.TURBO,
    val ipVersion: IpVersion = IpVersion.IPV4,
    val noize: String = "firewall",
    val useH2: Boolean = false,
    val fragment: Boolean = false,
    val fragmentSize: String = "16-32",
    val fragmentDelay: String = "2-10",
    val ech: String = "",
    val bindAddress: String = "127.0.0.1:1819",
    val peer: String = "",
    val wgPeer: String = "",
    val quickReconnect: Boolean = false,
    val keepalive: Int = 5,
    val noDataCheck: Boolean = false,
    val validateSecs: Int = 10,
    val reconnectSecs: Int = 2,
    val noProfileRetry: Boolean = false,
    val tlsGroups: String = "",
    val h2Peer: String = "",
    val wgReconnectSecs: Int = 2
) {
    enum class Protocol(val label: String) {
        MASQUE("MASQUE (QUIC/H3)"),
        WIREGUARD("WireGuard"),
        GOOL("Gool (WG-in-WG)")
    }

    enum class ScanMode(val label: String) {
        TURBO("turbo — fast first hit"),
        BALANCED("balanced — default"),
        THOROUGH("thorough — deep scan"),
        STEALTH("stealth — quiet"),
        IRONCLAD("ironclad — most reliable")
    }

    enum class IpVersion(val label: String) {
        IPV4("IPv4 only"),
        IPV6("IPv6 only"),
        DUAL("IPv4 + IPv6")
    }

    fun toArgs(): List<String> {
        val args = mutableListOf<String>()
        when (protocol) {
            Protocol.MASQUE -> args.add("--masque")
            Protocol.WIREGUARD -> args.add("--wg")
            Protocol.GOOL -> args.add("--gool")
        }
        when (ipVersion) {
            IpVersion.IPV4 -> args.add("-4")
            IpVersion.IPV6 -> args.add("-6")
            IpVersion.DUAL -> args.add("--dual")
        }
        args.addAll(listOf("--scan", scanMode.name.lowercase()))
        args.addAll(listOf("--bind", bindAddress))
        if (noize.isNotEmpty()) args.addAll(listOf("--noize", noize))
        if (peer.isNotEmpty()) args.addAll(listOf("--peer", peer))
        if (wgPeer.isNotEmpty()) args.addAll(listOf("--wg-peer", wgPeer))
        if (quickReconnect) args.add("--quick-reconnect")
        if (noDataCheck) args.add("--no-data-check")
        if (tlsGroups.isNotEmpty()) args.addAll(listOf("--tls-groups", tlsGroups))
        if (protocol == Protocol.MASQUE) {
            if (useH2) {
                args.add("--h2")
                if (h2Peer.isNotEmpty()) args.addAll(listOf("--h2-peer", h2Peer))
                if (fragment) {
                    args.add("--fragment")
                    args.addAll(listOf("--fragment-size", fragmentSize))
                    args.addAll(listOf("--fragment-delay", fragmentDelay))
                }
            }
            if (ech.isNotEmpty()) args.addAll(listOf("--ech", ech))
            args.addAll(listOf("--validate-secs", validateSecs.toString()))
            args.addAll(listOf("--reconnect-secs", reconnectSecs.toString()))
        }
        if (protocol == Protocol.WIREGUARD || protocol == Protocol.GOOL) {
            args.addAll(listOf("--keepalive", keepalive.toString()))
            args.addAll(listOf("--reconnect-secs", wgReconnectSecs.toString()))
            if (noProfileRetry) args.add("--no-profile-retry")
        }
        return args
    }
}
