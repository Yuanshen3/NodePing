package com.nodeping.app.data

import android.content.Context
import com.nodeping.app.model.Protocol
import com.nodeping.app.model.ProxyNode
import com.nodeping.app.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Loads proxy nodes from a subscription. The input may be:
 *  - an http(s) subscription URL (fetched, then parsed), or
 *  - raw subscription text / a single URI / a base64 blob pasted directly.
 * Also persists a list of saved subscriptions (so the user never re-pastes) plus the
 * currently-selected one, and remembers the last input for the text field.
 */
class SubscriptionRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nodeping", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var lastInput: String
        get() = prefs.getString(KEY_LAST_INPUT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_INPUT, value).apply()
        }

    /** Id of the subscription to auto-load on launch; null if none selected. */
    var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, value)
            }.apply()
        }

    /** All saved subscriptions, in insertion order. Migrates a pre-existing lastInput once. */
    fun listSubscriptions(): List<Subscription> {
        migrateIfNeeded()
        val raw = prefs.getString(KEY_SUBS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Subscription(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    input = o.getString("input"),
                    nodeCount = o.optInt("nodeCount", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun getSubscription(id: String): Subscription? = listSubscriptions().firstOrNull { it.id == id }

    /** Add a subscription for [input], or return the existing one (deduped by trimmed input). */
    fun upsertSubscription(input: String, nodeCount: Int): Subscription {
        val norm = input.trim()
        val list = listSubscriptions().toMutableList()
        val idx = list.indexOfFirst { it.input.trim() == norm }
        if (idx >= 0) {
            val updated = list[idx].copy(nodeCount = nodeCount)
            list[idx] = updated
            saveSubscriptions(list)
            return updated
        }
        val sub = Subscription(UUID.randomUUID().toString(), uniqueName(defaultName(norm), list), norm, nodeCount)
        list.add(sub)
        saveSubscriptions(list)
        return sub
    }

    /** Rename subscription [id] to [newName] (trimmed, deduped against the others). No-op if blank/missing. */
    fun renameSubscription(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        val list = listSubscriptions().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (list[idx].name == name) return
        val others = list.filterIndexed { i, _ -> i != idx }
        list[idx] = list[idx].copy(name = uniqueName(name, others))
        saveSubscriptions(list)
    }

    fun updateNodeCount(id: String, nodeCount: Int) {
        val list = listSubscriptions().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(nodeCount = nodeCount)
            saveSubscriptions(list)
        }
    }

    fun deleteSubscription(id: String) {
        val list = listSubscriptions().filterNot { it.id == id }
        saveSubscriptions(list)
        prefs.edit().remove(nodesKey(id)).apply()   // 一并清掉该订阅的节点缓存
        if (selectedId == id) selectedId = null
    }

    private fun saveSubscriptions(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("input", s.input)
                    .put("nodeCount", s.nodeCount)
            )
        }
        prefs.edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    /** On first run with a legacy single lastInput but no saved list, seed one from it. */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_SUBS)) return
        val last = prefs.getString(KEY_LAST_INPUT, "").orEmpty().trim()
        if (last.isEmpty()) {
            prefs.edit().putString(KEY_SUBS, "[]").apply()
            return
        }
        val sub = Subscription(UUID.randomUUID().toString(), defaultName(last), last, 0)
        saveSubscriptions(listOf(sub))
        if (selectedId == null) selectedId = sub.id
    }

    /** A friendly default label: the host for URLs, otherwise a generic tag. */
    private fun defaultName(input: String): String {
        val t = input.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            runCatching { java.net.URI(t).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "订阅"
        } else {
            "节点文本"
        }
    }

    private fun uniqueName(base: String, existing: List<Subscription>): String {
        if (existing.none { it.name == base }) return base
        var i = 2
        while (existing.any { it.name == "$base ($i)" }) i++
        return "$base ($i)"
    }

    /**
     * Persist the parsed [nodes] for subscription [id] so switching back to it later
     * needs no network — the core of "don't re-fetch on every switch".
     */
    fun cacheNodes(id: String, nodes: List<ProxyNode>) {
        val arr = JSONArray()
        nodes.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("protocol", n.protocol.name)
                    .put("name", n.name)
                    .put("host", n.host)
                    .put("port", n.port)
                    .put("raw", n.raw)
            )
        }
        prefs.edit().putString(nodesKey(id), arr.toString()).apply()
    }

    /** The last cached nodes for [id], or empty if it was never loaded online. */
    fun cachedNodes(id: String): List<ProxyNode> {
        val raw = prefs.getString(nodesKey(id), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ProxyNode(
                    id = o.getString("id"),
                    protocol = runCatching { Protocol.valueOf(o.getString("protocol")) }
                        .getOrDefault(Protocol.UNKNOWN),
                    name = o.optString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port"),
                    raw = o.optString("raw"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun nodesKey(id: String) = "$KEY_NODES_PREFIX$id"

    /** Fetch (if a URL) and parse the subscription into nodes. */
    suspend fun load(input: String): List<ProxyNode> {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "请输入订阅链接或节点内容" }
        val text = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            fetch(trimmed)
        } else {
            trimmed
        }
        return SubscriptionParser.parse(text)
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NodePing/1.0 (subscription)")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("订阅请求失败：HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private companion object {
        const val KEY_LAST_INPUT = "last_input"
        const val KEY_SUBS = "subscriptions"
        const val KEY_SELECTED = "selected_subscription_id"
        const val KEY_NODES_PREFIX = "nodes_"
    }
}
