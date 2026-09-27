package com.nodeping.app.net

import android.content.Context
import android.util.Log
import com.nodeping.app.model.ProxyNode
import com.nodeping.app.model.SpeedResult
import com.nodeping.app.model.SpeedStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One progressive update emitted by [SpeedEngine.testAll]. */
data class SpeedUpdate(val nodeId: String, val result: SpeedResult)

/**
 * Real download-throughput tester. Unlike the TCP-connect ping, this routes traffic
 * *through* each node's proxy tunnel (via the embedded Xray core) and measures how fast
 * a test file downloads.
 *
 * Nodes are tested strictly serially: concurrent downloads would share the link and make
 * every per-node number too low. For each supported node we (re)start the core with that
 * node as the sole outbound exposing a local HTTP inbound, download through it, then stop.
 * Protocols the core cannot run (ssr/hysteria2/tuic) are reported as [SpeedStatus.UNSUPPORTED].
 */
class SpeedEngine(
    private val context: Context,
    private val probeUrls: List<String> = DEFAULT_PROBE_URLS,
    private val parallel: Int = 6,
    private val warmupMs: Long = 1_000,
    private val measureWindowMs: Long = 5_000,
    private val maxBytes: Long = 60L * 1024 * 1024,
    private val perNodeTimeoutMs: Long = 15_000,
) {

    fun testAll(nodes: List<ProxyNode>): Flow<SpeedUpdate> = channelFlow {
        XrayCore.init(context)
        for (node in nodes) {
            ensureActive()
            if (!node.protocol.isProxySupported) {
                send(SpeedUpdate(node.id, SpeedResult(SpeedStatus.UNSUPPORTED, error = "不支持")))
                continue
            }
            val config = XrayConfigBuilder.build(node, XrayCore.SOCKS_PORT, XrayCore.HTTP_PORT)
            if (config == null) {
                send(SpeedUpdate(node.id, SpeedResult(SpeedStatus.ERROR, error = "配置解析失败")))
                continue
            }
            val result = try {
                withContext(Dispatchers.IO) { measureOne(config) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpeedResult(SpeedStatus.ERROR, error = e.message)
            } finally {
                XrayCore.stop()
            }
            send(SpeedUpdate(node.id, result))
        }
    }

    // <APPEND>
    private suspend fun measureOne(config: String): SpeedResult {
        try {
            XrayCore.start(config)
        } catch (e: Exception) {
            val why = XrayCore.lastError ?: e.message
            return SpeedResult(SpeedStatus.ERROR, error = if (why.isNullOrBlank()) "内核启动失败" else "内核: $why")
        }
        delay(400) // let the local inbound bind before we connect through it
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", XrayCore.HTTP_PORT)))
            .connectionPool(ConnectionPool(parallel * 2, 60, TimeUnit.SECONDS))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(perNodeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return withTimeoutOrNull(perNodeTimeoutMs) {
            measureThroughProxy(client)
        } ?: SpeedResult(SpeedStatus.TIMEOUT, error = "超时")
    }

    /** Try each probe URL in order; measure the first that yields data, else report the last failure. */
    private suspend fun measureThroughProxy(client: OkHttpClient): SpeedResult {
        var last = SpeedResult(SpeedStatus.ERROR, error = "无可用测速源")
        for (url in probeUrls) {
            val r = try {
                downloadConcurrent(client, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpeedResult(SpeedStatus.ERROR, error = e.message ?: e.javaClass.simpleName)
            }
            Log.i(TAG, "probe $url -> ${r.status} ${r.mbps?.let { "%.2f Mbps".format(it) } ?: r.error}")
            if (r.status == SpeedStatus.OK) return r
            last = r
        }
        return last
    }

    /** Run [parallel] concurrent download streams through the proxy and aggregate their throughput. */
    private suspend fun downloadConcurrent(client: OkHttpClient, url: String): SpeedResult = coroutineScope {
        val meter = Meter(warmupMs * 1_000_000L, measureWindowMs * 1_000_000L, maxBytes)
        val errors = ConcurrentLinkedQueue<String>()
        val calls = ConcurrentLinkedQueue<Call>()
        val jobs = List(parallel) {
            launch(Dispatchers.IO) {
                try {
                    val err = runInterruptible { downloadStream(client, url, meter, calls) }
                    if (err != null) errors.add(err)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors.add(e.message ?: e.javaClass.simpleName)
                }
            }
        }
        // 窗口一关就立刻收尾：cancel 各自的 OkHttp Call 会关掉底层 socket，让仍阻塞在 read()
        // 的停滞流立即抛错退出——Thread.interrupt（runInterruptible）对阻塞式 socket read 无效，
        // 故必须 cancel Call 才不会干等到 readTimeout。
        val watchdog = launch {
            while (jobs.any { it.isActive }) {
                if (meter.isDone) {
                    calls.forEach { it.cancel() }
                    jobs.forEach { it.cancel() }
                    break
                }
                delay(50)
            }
        }
        jobs.joinAll()
        watchdog.cancel()
        val r = meter.result()
        if (r.status == SpeedStatus.OK) r
        else SpeedResult(SpeedStatus.ERROR, error = errors.firstOrNull() ?: r.error ?: "无数据")
    }

    /** One connection feeding [meter]; returns an error string if it never delivered data. */
    private fun downloadStream(client: OkHttpClient, url: String, meter: Meter, calls: ConcurrentLinkedQueue<Call>): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .build()
        val call = client.newCall(request)
        calls.add(call) // 交给 watchdog 在窗口关闭时 cancel，立即中断停滞的 read()
        call.execute().use { resp ->
            if (!resp.isSuccessful) return "HTTP ${resp.code}"
            val body = resp.body ?: return "空响应"
            val src = body.byteStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (!meter.record(n)) break
            }
            return null
        }
    }

    /**
     * Thread-safe aggregator shared by the concurrent download streams. The clock starts at the
     * first byte of any stream; a short warm-up is discarded (TCP slow-start) before the
     * measurement window opens, and only bytes read within that window count toward the rate.
     */
    private class Meter(private val warmupNs: Long, private val windowNs: Long, private val maxBytes: Long) {
        private val total = AtomicLong(0)
        private val firstByteNs = AtomicLong(0)
        private val measureStartNs = AtomicLong(0)
        private val bytesAtWindowStart = AtomicLong(0)
        private val lastReadNs = AtomicLong(0)
        @Volatile private var done = false

        val isDone: Boolean get() = done

        /** Record [n] freshly-read bytes; returns false once the window is full (stop reading). */
        fun record(n: Int): Boolean {
            if (done) return false
            val now = System.nanoTime()
            firstByteNs.compareAndSet(0L, now)
            val newTotal = total.addAndGet(n.toLong())
            lastReadNs.set(now)
            if (measureStartNs.get() == 0L && now - firstByteNs.get() >= warmupNs) {
                if (measureStartNs.compareAndSet(0L, now)) bytesAtWindowStart.set(newTotal)
            }
            val ms = measureStartNs.get()
            if (ms != 0L && now - ms >= windowNs) { done = true; return false }
            // 极端快链路若在 warmup 内就读满 maxBytes，会走 result() 整段平均分支（含慢启动、
            // 略偏低）；手机代理隧道达不到该速率，故不额外处理。
            if (newTotal >= maxBytes) { done = true; return false }
            return true
        }

        fun result(): SpeedResult {
            val ms = measureStartNs.get()
            val start = if (ms != 0L) ms else firstByteNs.get()
            if (start == 0L) return SpeedResult(SpeedStatus.ERROR, error = "无数据")
            val bytes = if (ms != 0L) total.get() - bytesAtWindowStart.get() else total.get()
            val seconds = (lastReadNs.get() - start) / 1e9
            if (bytes <= 0L || seconds <= 0.0) return SpeedResult(SpeedStatus.ERROR, error = "数据不足")
            return SpeedResult(SpeedStatus.OK, mbps = bytes * 8.0 / seconds / 1_000_000.0)
        }
    }

    companion object {
        private const val TAG = "SpeedEngine"

        /** Chrome-like UA — some probe hosts (and Cloudflare's WAF) 403 non-browser agents. */
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * Ordered fallback probes: the first that returns data is measured. Commercial 机场
         * routinely throttle/block name-brand speed-test CDNs (speed.cloudflare.com 403s from
         * datacenter exit IPs; hetzner/ovh get RST or throttled to ~0.2 Mbps even from a same-country
         * exit — proving host-based throttling, not distance). So we try a mainstream software-download
         * CDN first (GitHub release asset — airports almost never throttle it), then the classic
         * speed-test hosts as fallback so at least *some* number comes back.
         */
        val DEFAULT_PROBE_URLS = listOf(
            "https://github.com/git-for-windows/git/releases/download/v2.43.0.windows.1/Git-2.43.0-64-bit.exe",
            "https://speed.cloudflare.com/__down?bytes=104857600",
            "https://speed.hetzner.de/100MB.bin",
            "http://cachefly.cachefly.net/100mb.test",
        )
    }

}
