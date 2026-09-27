package com.nodeping.app.model

/** Proxy protocol parsed from a subscription URI. Only what the ping test needs. */
enum class Protocol(val label: String) {
    SS("SS"),
    SSR("SSR"),
    VMESS("VMess"),
    VLESS("VLESS"),
    TROJAN("Trojan"),
    HYSTERIA2("Hysteria2"),
    TUIC("TUIC"),
    UNKNOWN("Unknown");

    /**
     * Whether the embedded Xray core can run this protocol as an outbound.
     * Xray supports ss/vmess/vless/trojan; ssr/hysteria2/tuic are not built in,
     * so those nodes are skipped (marked UNSUPPORTED) by the proxy / speed test.
     */
    val isProxySupported: Boolean
        get() = this == SS || this == VMESS || this == VLESS || this == TROJAN
}

/**
 * A single proxy node. For the latency (ping) feature we only require the
 * endpoint [host]/[port] plus a display [name]; [raw] keeps the original URI so
 * the model can be extended later (real speed test / export) without re-parsing.
 */
data class ProxyNode(
    val id: String,
    val protocol: Protocol,
    val name: String,
    val host: String,
    val port: Int,
    val raw: String,
)

/**
 * A saved subscription source, remembered across launches so the user never has to
 * re-paste. [input] is either an http(s) URL or raw node text; [nodeCount] is the last
 * parsed count (0 until first loaded), shown on the chip for quick disambiguation.
 */
data class Subscription(
    val id: String,
    val name: String,
    val input: String,
    val nodeCount: Int = 0,
)

/** Outcome states of a TCP-connect latency probe. */
enum class PingStatus {
    UNTESTED,
    TESTING,
    OK,
    TIMEOUT,
    DNS_FAIL,
    REFUSED,
    ERROR,
}

/** Result of pinging one [ProxyNode]. [latencyMs] is set only when [status] == OK. */
data class PingResult(
    val status: PingStatus = PingStatus.UNTESTED,
    val latencyMs: Int? = null,
    val error: String? = null,
)

/** Node + its current ping result, as rendered in the list. */
data class NodeRow(
    val node: ProxyNode,
    val ping: PingResult = PingResult(),
    val speed: SpeedResult = SpeedResult(),
)

/** Outcome states of a real download-throughput probe (traffic routed through the node). */
enum class SpeedStatus {
    UNTESTED,
    TESTING,
    OK,
    UNSUPPORTED,   // ssr / hysteria2 / tuic — not runnable by the embedded Xray core
    TIMEOUT,
    ERROR,
}

/** Result of a speed test for one node. [mbps] is set only when [status] == OK. */
data class SpeedResult(
    val status: SpeedStatus = SpeedStatus.UNTESTED,
    val mbps: Double? = null,
    val error: String? = null,
)
