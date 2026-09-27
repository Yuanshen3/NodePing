package com.nodeping.app.data

import com.nodeping.app.model.Protocol
import com.nodeping.app.model.ProxyNode
import com.nodeping.app.util.Base64Util
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses a subscription payload into [ProxyNode]s. Only the endpoint (host/port),
 * a display name and the protocol are extracted — enough for the latency test.
 */
object SubscriptionParser {

    fun parse(raw: String): List<ProxyNode> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        // SIP008 JSON subscription.
        if (text.startsWith("{")) {
            parseSip008(text)?.let { if (it.isNotEmpty()) return it }
        }

        // Body is either a plain list of URIs or a single base64 blob of them.
        val body = if (text.contains("://")) text else Base64Util.decodeToString(text)

        val nodes = ArrayList<ProxyNode>()
        var index = 0
        for (line in body.split('\n', '\r')) {
            val uri = line.trim()
            if (uri.isEmpty() || uri.startsWith("#") || uri.startsWith("//")) continue
            val node = parseUri(uri) ?: continue
            nodes.add(node.copy(id = "${index}_${node.host}:${node.port}"))
            index++
        }
        return nodes
    }

    private fun parseUri(uri: String): ProxyNode? = when {
        uri.startsWith("ss://") -> parseShadowsocks(uri)
        uri.startsWith("ssr://") -> parseSsr(uri)
        uri.startsWith("vmess://") -> parseVmess(uri)
        uri.startsWith("vless://") -> parseAuthority(uri, Protocol.VLESS)
        uri.startsWith("trojan://") -> parseAuthority(uri, Protocol.TROJAN)
        uri.startsWith("hysteria2://") || uri.startsWith("hy2://") -> parseAuthority(uri, Protocol.HYSTERIA2)
        uri.startsWith("tuic://") -> parseAuthority(uri, Protocol.TUIC)
        else -> null
    }

    // ---- Shadowsocks (ss://): SIP002 form and legacy base64 form ----
    private fun parseShadowsocks(uri: String): ProxyNode? {
        var main = uri.removePrefix("ss://")
        val name = extractFragment(main)
        main = stripFragment(main).substringBefore('?')   // drop plugin query
        val at = main.lastIndexOf('@')
        val hostPort = if (at >= 0) {
            main.substring(at + 1).substringBefore('/')     // SIP002: creds@host:port[/][?plugin]
        } else {
            val decoded = Base64Util.decodeToString(main)   // legacy: b64(method:pass@host:port)
            val a2 = decoded.lastIndexOf('@')
            if (a2 < 0) return null
            decoded.substring(a2 + 1)
        }
        val (h, p) = splitHostPort(hostPort) ?: return null
        return ProxyNode("", Protocol.SS, name, h, p, uri)
    }

    // ---- ShadowsocksR (ssr://base64url(host:port:proto:method:obfs:base64pass/?params)) ----
    private fun parseSsr(uri: String): ProxyNode? {
        val decoded = Base64Util.decodeToString(uri.removePrefix("ssr://"))
        val cut = decoded.indexOf("/?")
        val mainPart = if (cut >= 0) decoded.substring(0, cut) else decoded
        val params = if (cut >= 0) decoded.substring(cut + 2) else ""
        val parts = mainPart.split(":")
        if (parts.size < 2) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        val remarks = params.split("&")
            .firstOrNull { it.startsWith("remarks=") }
            ?.substringAfter('=')
            ?.let { Base64Util.decodeToString(it) }
            ?: ""
        if (host.isBlank() || port !in 1..65535) return null
        return ProxyNode("", Protocol.SSR, remarks, host, port, uri)
    }

    // ---- VMess (vmess://base64(json)) ----
    private fun parseVmess(uri: String): ProxyNode? {
        val decoded = Base64Util.decodeToString(uri.removePrefix("vmess://")).trim()
        if (!decoded.startsWith("{")) return null
        return try {
            val json = JSONObject(decoded)
            val host = json.optString("add")
            if (host.isBlank()) return null
            val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 0)
            if (port !in 1..65535) return null
            ProxyNode("", Protocol.VMESS, json.optString("ps"), host, port, uri)
        } catch (e: Exception) {
            null
        }
    }

    // ---- VLESS / Trojan / Hysteria2 / TUIC : scheme://cred@host:port?query#name ----
    private fun parseAuthority(uri: String, protocol: Protocol): ProxyNode? {
        var rest = uri.substringAfter("://")
        val name = extractFragment(rest)
        rest = stripFragment(rest).substringBefore('?').substringBefore('/')
        val at = rest.lastIndexOf('@')
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest
        val (h, p) = splitHostPort(hostPort) ?: return null
        return ProxyNode("", protocol, name, h, p, uri)
    }

    // ---- SIP008 JSON subscription ({ "servers": [ { server, server_port, remarks } ] }) ----
    private fun parseSip008(text: String): List<ProxyNode>? {
        return try {
            val servers = JSONObject(text).optJSONArray("servers") ?: return null
            val out = ArrayList<ProxyNode>()
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val host = s.optString("server")
                if (host.isBlank()) continue
                val port = s.optString("server_port").toIntOrNull() ?: s.optInt("server_port", 0)
                if (port !in 1..65535) continue
                val name = s.optString("remarks").ifBlank { s.optString("tag") }
                out.add(ProxyNode("${i}_$host:$port", Protocol.SS, name, host, port, s.toString()))
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    // ---- helpers ----
    private fun extractFragment(s: String): String {
        val h = s.indexOf('#')
        return if (h >= 0) urlDecode(s.substring(h + 1)) else ""
    }

    private fun stripFragment(s: String): String {
        val h = s.indexOf('#')
        return if (h >= 0) s.substring(0, h) else s
    }

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

    /** Percent-decode, preserving a literal '+' (subscription names are not form-encoded). */
    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }
}
