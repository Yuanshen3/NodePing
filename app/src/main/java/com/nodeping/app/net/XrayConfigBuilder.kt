package com.nodeping.app.net

import com.nodeping.app.model.Protocol
import com.nodeping.app.model.ProxyNode
import com.nodeping.app.util.Base64Util
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Builds an Xray (v2ray) config JSON from a node's original share-URI ([ProxyNode.raw]).
 *
 * The generated config exposes a local SOCKS + HTTP inbound on the loopback and routes
 * everything to a single outbound built from the node. Only ss/vmess/vless/trojan are
 * runnable by the embedded core; other protocols return null.
 *
 * Field mapping follows the de-facto v2rayN / v2rayNG share-link conventions.
 */
object XrayConfigBuilder {

    /**
     * @return an Xray config JSON string for [node], or null when the protocol is
     * unsupported or the URI cannot be parsed.
     */
    /**
     * @param withTun when true, adds a `tun` inbound (xray-core proxy/tun + gVisor netstack)
     * so the VpnService fd handed to [XrayCore.start] actually captures device traffic. The
     * fd itself arrives via the XRAY_TUN_FD env that startLoop() sets; the core only reads it
     * if a `tun` inbound is declared, so Stage A (local proxy) leaves this false.
     */
    fun build(node: ProxyNode, socksPort: Int, httpPort: Int, withTun: Boolean = false): String? {
        val outbound = try {
            when (node.protocol) {
                Protocol.SS -> ssOutbound(node.raw)
                Protocol.VMESS -> vmessOutbound(node.raw)
                Protocol.VLESS -> vlessOutbound(node.raw)
                Protocol.TROJAN -> trojanOutbound(node.raw)
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null

        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("inbounds", inbounds(socksPort, httpPort, withTun))
        root.put("outbounds", JSONArray().put(outbound).put(directOutbound()))
        root.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
        return root.toString()
    }

    // <APPEND-1>
    // ---- inbounds / direct ----
    private fun inbounds(socksPort: Int, httpPort: Int, withTun: Boolean): JSONArray {
        val socks = JSONObject()
            .put("tag", "socks-in").put("listen", "127.0.0.1").put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
            .put(
                "sniffing",
                JSONObject().put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls")),
            )
        val http = JSONObject()
            .put("tag", "http-in").put("listen", "127.0.0.1").put("port", httpPort)
            .put("protocol", "http").put("settings", JSONObject())
        val arr = JSONArray().put(socks).put(http)
        if (withTun) arr.put(tunInbound())
        return arr
    }

    /**
     * xray-core `tun` inbound. On Android the real fd comes from XRAY_TUN_FD (set by
     * startLoop); [name]/[ips]/[mtu] are metadata that must match the VpnService.Builder in
     * [com.nodeping.app.net.NodeVpnService] (10.10.10.2/32, mtu 1500). Sniffing lets the empty
     * routing table still see domains, so every device packet dispatches to the proxy outbound.
     */
    private fun tunInbound(): JSONObject {
        val settings = JSONObject()
            .put("name", "tun0")
            .put("mtu", 1500)
            .put("ips", JSONArray().put("10.10.10.2/32"))
        return JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", settings)
            .put(
                "sniffing",
                JSONObject().put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic")),
            )
    }

    private fun directOutbound(): JSONObject =
        JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject())

    // ---- shadowsocks ----
    private fun ssOutbound(uri: String): JSONObject? {
        var main = uri.removePrefix("ss://")
        main.indexOf('#').let { if (it >= 0) main = main.substring(0, it) }
        main.indexOf('?').let { if (it >= 0) main = main.substring(0, it) } // drop plugin query
        val method: String
        val password: String
        val host: String
        val port: Int
        val at = main.lastIndexOf('@')
        if (at >= 0) {
            val (m, p) = decodeUserinfo(main.substring(0, at)) ?: return null
            val (h, pt) = splitHostPort(main.substring(at + 1)) ?: return null
            method = m; password = p; host = h; port = pt
        } else {
            val decoded = Base64Util.decodeToString(main)
            val a2 = decoded.lastIndexOf('@')
            if (a2 < 0) return null
            val (m, p) = decodeUserinfo(decoded.substring(0, a2)) ?: return null
            val (h, pt) = splitHostPort(decoded.substring(a2 + 1)) ?: return null
            method = m; password = p; host = h; port = pt
        }
        val server = JSONObject().put("address", host).put("port", port)
            .put("method", method).put("password", password)
        return JSONObject().put("tag", "proxy").put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", JSONObject().put("network", "tcp"))
    }

    /** "method:password" (SIP002 base64url userinfo, or plain). */
    private fun decodeUserinfo(creds: String): Pair<String, String>? {
        val raw = if (creds.contains(':')) creds else Base64Util.decodeToString(creds)
        val i = raw.indexOf(':')
        if (i < 0) return null
        return raw.substring(0, i) to raw.substring(i + 1)
    }

    // <APPEND-2>
    // ---- vmess (base64 json) ----
    private fun vmessOutbound(uri: String): JSONObject? {
        val decoded = Base64Util.decodeToString(uri.removePrefix("vmess://")).trim()
        if (!decoded.startsWith("{")) return null
        val j = JSONObject(decoded)
        val host = j.optString("add")
        if (host.isBlank()) return null
        val port = j.optString("port").toIntOrNull() ?: j.optInt("port", 0)
        if (port !in 1..65535) return null
        val security = j.optString("scy").ifBlank { j.optString("security", "auto") }.ifBlank { "auto" }
        val user = JSONObject()
            .put("id", j.optString("id"))
            .put("alterId", j.optString("aid").toIntOrNull() ?: j.optInt("aid", 0))
            .put("security", security)
        val vnext = JSONObject().put("address", host).put("port", port)
            .put("users", JSONArray().put(user))
        val net = j.optString("net", "tcp").ifBlank { "tcp" }
        val tls = j.optString("tls")
        val stream = streamSettings(
            network = net,
            security = if (tls == "tls" || tls == "reality") tls else "none",
            headerType = j.optString("type", "none"),
            path = j.optString("path"),
            hostHeader = j.optString("host"),
            grpcService = j.optString("path"),
            sni = j.optString("sni").ifBlank { j.optString("host") },
            alpn = j.optString("alpn"),
            fingerprint = j.optString("fp"),
            pbk = "", sid = "", spx = "",
        )
        return JSONObject().put("tag", "proxy").put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", stream)
    }

    // ---- vless ----
    private fun vlessOutbound(uri: String): JSONObject? {
        val u = parseStd(uri, "vless://") ?: return null
        if (u.userinfo.isBlank()) return null
        val user = JSONObject().put("id", u.userinfo)
            .put("encryption", u.query["encryption"]?.ifBlank { null } ?: "none")
        u.query["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val vnext = JSONObject().put("address", u.host).put("port", u.port)
            .put("users", JSONArray().put(user))
        return JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", streamFromQuery(u.query, defaultSecurity = "none"))
    }

    // ---- trojan (TLS by default) ----
    private fun trojanOutbound(uri: String): JSONObject? {
        val u = parseStd(uri, "trojan://") ?: return null
        if (u.userinfo.isBlank()) return null
        val server = JSONObject().put("address", u.host).put("port", u.port)
            .put("password", u.userinfo)
        return JSONObject().put("tag", "proxy").put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", streamFromQuery(u.query, defaultSecurity = "tls"))
    }

    // <APPEND-3>
    private fun streamFromQuery(q: Map<String, String>, defaultSecurity: String): JSONObject {
        val sni = q["sni"]?.ifBlank { null } ?: q["host"].orEmpty()
        return streamSettings(
            network = q["type"]?.ifBlank { null } ?: "tcp",
            security = q["security"]?.ifBlank { null } ?: defaultSecurity,
            headerType = q["headerType"].orEmpty(),
            path = q["path"].orEmpty(),
            hostHeader = q["host"].orEmpty(),
            grpcService = q["serviceName"].orEmpty(),
            sni = sni,
            alpn = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            pbk = q["pbk"].orEmpty(),
            sid = q["sid"].orEmpty(),
            spx = q["spx"].orEmpty(),
        )
    }

    private fun streamSettings(
        network: String, security: String, headerType: String,
        path: String, hostHeader: String, grpcService: String,
        sni: String, alpn: String, fingerprint: String,
        pbk: String, sid: String, spx: String,
    ): JSONObject {
        val net = when (network) { "h2" -> "http"; "" -> "tcp"; else -> network }
        val stream = JSONObject().put("network", net)
        when (security) {
            "tls" -> {
                val tls = JSONObject()
                if (sni.isNotBlank()) tls.put("serverName", sni)
                if (fingerprint.isNotBlank()) tls.put("fingerprint", fingerprint)
                if (alpn.isNotBlank()) tls.put("alpn", splitToArray(alpn))
                stream.put("security", "tls").put("tlsSettings", tls)
            }
            "reality" -> {
                val r = JSONObject()
                if (sni.isNotBlank()) r.put("serverName", sni)
                if (fingerprint.isNotBlank()) r.put("fingerprint", fingerprint)
                if (pbk.isNotBlank()) r.put("publicKey", pbk)
                if (sid.isNotBlank()) r.put("shortId", sid)
                if (spx.isNotBlank()) r.put("spiderX", spx)
                stream.put("security", "reality").put("realitySettings", r)
            }
            else -> stream.put("security", "none")
        }
        when (net) {
            "ws" -> {
                val ws = JSONObject()
                if (path.isNotBlank()) ws.put("path", path)
                if (hostHeader.isNotBlank()) ws.put("headers", JSONObject().put("Host", hostHeader))
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().apply { if (grpcService.isNotBlank()) put("serviceName", grpcService) },
            )
            "http" -> {
                val h = JSONObject()
                if (path.isNotBlank()) h.put("path", path)
                if (hostHeader.isNotBlank()) h.put("host", JSONArray().put(hostHeader))
                stream.put("httpSettings", h)
            }
            "tcp" -> if (headerType == "http") {
                val req = JSONObject()
                if (path.isNotBlank()) req.put("path", JSONArray().put(path))
                if (hostHeader.isNotBlank()) {
                    req.put("headers", JSONObject().put("Host", JSONArray().put(hostHeader)))
                }
                stream.put(
                    "tcpSettings",
                    JSONObject().put("header", JSONObject().put("type", "http").put("request", req)),
                )
            }
        }
        return stream
    }

    // <APPEND-4>
    private data class Std(
        val userinfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
    )

    private fun parseStd(uri: String, scheme: String): Std? {
        var rest = uri.removePrefix(scheme)
        rest.indexOf('#').let { if (it >= 0) rest = rest.substring(0, it) }
        var query = emptyMap<String, String>()
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = parseQuery(rest.substring(q + 1))
            rest = rest.substring(0, q)
        }
        val at = rest.lastIndexOf('@')
        val userinfo = if (at >= 0) rest.substring(0, at) else ""
        val hp = (if (at >= 0) rest.substring(at + 1) else rest).substringBefore('/')
        val (h, p) = splitHostPort(hp) ?: return null
        return Std(urlDecode(userinfo), h, p, query)
    }

    private fun parseQuery(q: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i < 0) m[urlDecode(pair)] = ""
            else m[urlDecode(pair.substring(0, i))] = urlDecode(pair.substring(i + 1))
        }
        return m
    }

    private fun splitToArray(csv: String): JSONArray =
        JSONArray().apply { csv.split(',').forEach { if (it.isNotBlank()) put(it.trim()) } }

    /** Split "host:port" or "[ipv6]:port"; null if malformed. */
    private fun splitHostPort(input: String): Pair<String, Int>? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return if (host.isNotEmpty() && port in 1..65535) host to port else null
        }
        val colon = s.lastIndexOf(':')
        if (colon <= 0) return null
        val host = s.substring(0, colon)
        val port = s.substring(colon + 1).toIntOrNull() ?: return null
        return if (host.isNotEmpty() && port in 1..65535) host to port else null
    }

    /** Percent-decode, preserving a literal '+' (proxy paths/passwords are not form-encoded). */
    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }




}
