package com.nodeping.app.net

import com.nodeping.app.model.PingResult
import com.nodeping.app.model.PingStatus
import com.nodeping.app.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** One progressive update emitted by [PingEngine.testAll]. */
data class PingUpdate(val nodeId: String, val result: PingResult)

/**
 * Concurrent TCP-connect latency tester.
 *
 * ICMP ping needs root and does not reflect the proxy port, so we measure the
 * time to establish a TCP connection to `host:port`. DNS is resolved separately
 * so name-lookup time is not charged to the reported latency. Concurrency is
 * bounded by a [Semaphore] so testing hundreds of nodes cannot exhaust file
 * descriptors. Blocking socket work runs inside [runInterruptible] so cancelling
 * the flow (e.g. starting a new scan) actually aborts in-flight connects.
 */
class PingEngine(
    private val timeoutMs: Int = 3000,
    private val concurrency: Int = 32,
    private val attempts: Int = 1,
) {

    /** Ping every node concurrently, emitting each result as soon as it lands. */
    fun testAll(nodes: List<ProxyNode>): Flow<PingUpdate> = channelFlow {
        val gate = Semaphore(concurrency)
        for (node in nodes) {
            launch(Dispatchers.IO) {
                gate.withPermit {
                    send(PingUpdate(node.id, ping(node)))
                }
            }
        }
    }

    /** Ping a single node, taking the best latency across [attempts]. */
    suspend fun ping(node: ProxyNode): PingResult {
        if (node.port !in 1..65535 || node.host.isBlank()) {
            return PingResult(PingStatus.ERROR, error = "地址无效")
        }
        val addr: InetAddress = try {
            runInterruptible(Dispatchers.IO) { InetAddress.getByName(node.host) }
        } catch (e: UnknownHostException) {
            return PingResult(PingStatus.DNS_FAIL, error = "DNS 解析失败")
        } catch (e: IOException) {
            return PingResult(PingStatus.ERROR, error = e.message)
        }

        var best: Int? = null
        var lastFailure = PingResult(PingStatus.ERROR)
        repeat(attempts.coerceAtLeast(1)) {
            val r = connectOnce(addr, node.port)
            if (r.status == PingStatus.OK && r.latencyMs != null) {
                best = best?.let { minOf(it, r.latencyMs) } ?: r.latencyMs
            } else {
                lastFailure = r
            }
        }
        return best?.let { PingResult(PingStatus.OK, it) } ?: lastFailure
    }

    private suspend fun connectOnce(addr: InetAddress, port: Int): PingResult {
        return try {
            runInterruptible(Dispatchers.IO) {
                val socket = Socket()
                try {
                    val start = System.nanoTime()
                    socket.connect(InetSocketAddress(addr, port), timeoutMs)
                    val ms = ((System.nanoTime() - start) / 1_000_000L).toInt()
                    PingResult(PingStatus.OK, ms.coerceAtLeast(0))
                } finally {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            PingResult(PingStatus.TIMEOUT, error = "超时")
        } catch (e: ConnectException) {
            PingResult(PingStatus.REFUSED, error = "连接被拒绝")
        } catch (e: IOException) {
            PingResult(PingStatus.ERROR, error = e.message ?: "网络错误")
        }
    }
}
