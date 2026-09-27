package com.nodeping.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nodeping.app.data.SubscriptionRepository
import com.nodeping.app.model.NodeRow
import com.nodeping.app.model.PingResult
import com.nodeping.app.model.PingStatus
import com.nodeping.app.model.ProxyNode
import com.nodeping.app.model.SpeedResult
import com.nodeping.app.model.SpeedStatus
import com.nodeping.app.model.Subscription
import com.nodeping.app.net.NodeVpnService
import com.nodeping.app.net.PingEngine
import com.nodeping.app.net.SpeedEngine
import com.nodeping.app.net.XrayConfigBuilder
import com.nodeping.app.net.XrayCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A request handed to the Activity to bring up the VPN-consent dialog then start the service. */
data class VpnLaunchRequest(
    val configJson: String,
    val nodeId: String,
    val nodeName: String,
)

data class UiState(
    val input: String = "",
    val rows: List<NodeRow> = emptyList(),
    val loading: Boolean = false,
    val testing: Boolean = false,
    val testedCount: Int = 0,
    val speedTesting: Boolean = false,
    val speedTestedCount: Int = 0,
    val proxyNodeId: String? = null,
    val proxyStarting: Boolean = false,
    val vpnNodeId: String? = null,
    val vpnNodeName: String? = null,
    val vpnStarting: Boolean = false,
    val subscriptions: List<Subscription> = emptyList(),
    val selectedSubscriptionId: String? = null,
    val message: String? = null,
) {
    /** Whether the global VPN is starting or running (blocks core-sharing local actions). */
    val vpnActive: Boolean get() = vpnStarting || vpnNodeId != null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SubscriptionRepository(app)
    private val engine = PingEngine()
    private val speedEngine = SpeedEngine(app)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Emitted when the UI wants to start the VPN; the Activity handles the consent dialog. */
    private val _vpnLaunch = MutableSharedFlow<VpnLaunchRequest>(extraBufferCapacity = 1)
    val vpnLaunch = _vpnLaunch.asSharedFlow()

    private var testJob: Job? = null
    private var speedJob: Job? = null
    private var proxyJob: Job? = null
    private var loadJob: Job? = null

    init {
        val subs = repo.listSubscriptions()
        val selId = repo.selectedId?.takeIf { id -> subs.any { it.id == id } } ?: subs.firstOrNull()?.id
        val sel = subs.firstOrNull { it.id == selId }
        _state.update {
            it.copy(
                subscriptions = subs,
                selectedSubscriptionId = selId,
                input = sel?.input ?: repo.lastInput,
                // 启动即用上次缓存的节点，不联网——需要最新节点时由用户点「更新订阅」
                rows = if (selId != null) repo.cachedNodes(selId).map { NodeRow(it) } else emptyList(),
            )
        }
        // 跟随 VpnService 的真实运行状态，反映到 UI（连接中/已连接/断开/失败）
        viewModelScope.launch {
            NodeVpnService.state.collect { vs ->
                _state.update {
                    it.copy(
                        vpnStarting = vs.starting,
                        vpnNodeId = if (vs.running || vs.starting) vs.nodeId else null,
                        vpnNodeName = if (vs.running || vs.starting) vs.nodeName else null,
                        message = vs.error?.let { e -> "VPN：$e" } ?: it.message,
                    )
                }
            }
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** The node currently proxied, if any. */
    val proxyNode get() = _state.value.rows.firstOrNull { it.node.id == _state.value.proxyNodeId }?.node

    /** Aliveness / best-latency summary derived from the current rows. */
    val aliveCount get() = _state.value.rows.count { it.ping.status == PingStatus.OK }

    fun importSubscription() {
        val input = _state.value.input
        if (input.isBlank()) {
            _state.update { it.copy(message = "请输入订阅链接或节点内容") }
            return
        }
        fetchAndApply(input, saveAsNew = true)
    }

    /** 联网重新拉取当前选中的订阅并刷新其缓存（导入之外唯一会主动联网的入口）。 */
    fun updateSubscription() {
        val id = _state.value.selectedSubscriptionId
        val sub = id?.let { repo.getSubscription(it) }
        if (sub == null) {
            // 尚无选中订阅：把输入框内容当作新订阅导入
            importSubscription()
            return
        }
        fetchAndApply(sub.input, saveAsNew = false, selectId = sub.id)
    }

    /** Switch to a saved subscription using its cached nodes — no network fetch. */
    fun selectSubscription(sub: Subscription) {
        if (_state.value.loading) return
        if (sub.id == _state.value.selectedSubscriptionId && _state.value.rows.isNotEmpty()) return
        repo.selectedId = sub.id
        applyCachedNodes(sub.id, sub.input)
    }

    /**
     * 取消针对旧节点的测试、停掉 Stage A 本地代理（但不动全局 VPN 的内核），
     * 然后把 [subId] 的缓存节点应用到列表。纯本地、不联网。
     */
    private fun applyCachedNodes(subId: String, subInput: String) {
        testJob?.cancel()
        speedJob?.cancel()
        proxyJob?.cancel()
        loadJob?.cancel()
        if (!NodeVpnService.state.value.running && !NodeVpnService.state.value.starting) {
            viewModelScope.launch { withContext(Dispatchers.IO) { XrayCore.stop() } }
        }
        val cached = repo.cachedNodes(subId)
        _state.update {
            it.copy(
                selectedSubscriptionId = subId,
                input = subInput,
                rows = cached.map { NodeRow(it) },
                loading = false,
                testedCount = 0,
                speedTestedCount = 0,
                proxyNodeId = null,
                proxyStarting = false,
                message = if (cached.isEmpty()) "该订阅暂无缓存节点，点「更新订阅」联网获取" else null,
            )
        }
    }

    /** 重命名已保存订阅——纯本地缓存操作，不联网、不影响当前节点列表与运行中的代理/VPN。 */
    fun renameSubscription(id: String, newName: String) {
        if (newName.isBlank()) return
        repo.renameSubscription(id, newName)
        _state.update { it.copy(subscriptions = repo.listSubscriptions()) }
    }

    fun deleteSubscription(id: String) {
        repo.deleteSubscription(id)
        val remaining = repo.listSubscriptions()
        if (_state.value.selectedSubscriptionId == id) {
            val next = remaining.firstOrNull()
            if (next != null) {
                repo.selectedId = next.id
                _state.update { it.copy(subscriptions = remaining) }
                applyCachedNodes(next.id, next.input)
            } else {
                loadJob?.cancel()
                testJob?.cancel()
                speedJob?.cancel()
                proxyJob?.cancel()
                viewModelScope.launch { withContext(Dispatchers.IO) { XrayCore.stop() } }
                _state.update {
                    it.copy(
                        subscriptions = remaining,
                        selectedSubscriptionId = null,
                        rows = emptyList(),
                        testedCount = 0,
                        speedTestedCount = 0,
                        proxyNodeId = null,
                        proxyStarting = false,
                    )
                }
            }
        } else {
            _state.update { it.copy(subscriptions = remaining) }
        }
    }

    /**
     * 联网抓取+解析 [rawInput] 为节点，并把结果缓存起来供之后免联网切换。
     * [saveAsNew] 把它 upsert 进已存列表；[selectId] 则刷新一条已存订阅的节点缓存与计数。
     * 这是唯一会主动联网的路径（导入 / 更新订阅）。
     */
    private fun fetchAndApply(rawInput: String, saveAsNew: Boolean, selectId: String? = null) {
        val input = rawInput.trim()
        testJob?.cancel()
        speedJob?.cancel()
        proxyJob?.cancel()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(input = input, loading = true, message = null) }
            // 重新导入/更新时清掉旧的本地代理内核；但全局 VPN 由前台服务持有，不在此处停。
            if (!NodeVpnService.state.value.running && !NodeVpnService.state.value.starting) {
                withContext(Dispatchers.IO) { XrayCore.stop() }
            }
            try {
                val nodes = repo.load(input)
                repo.lastInput = input
                val sub = when {
                    selectId != null -> repo.getSubscription(selectId)?.also { repo.updateNodeCount(it.id, nodes.size) }
                    saveAsNew -> repo.upsertSubscription(input, nodes.size)
                    else -> null
                }
                if (sub != null) {
                    repo.selectedId = sub.id
                    if (nodes.isNotEmpty()) repo.cacheNodes(sub.id, nodes) // 空结果不覆盖已有缓存
                }
                _state.update {
                    it.copy(
                        loading = false,
                        rows = nodes.map { n -> NodeRow(n) },
                        testedCount = 0,
                        speedTestedCount = 0,
                        proxyNodeId = null,
                        proxyStarting = false,
                        subscriptions = repo.listSubscriptions(),
                        selectedSubscriptionId = sub?.id ?: it.selectedSubscriptionId,
                        message = if (nodes.isEmpty()) "未解析到任何节点，请检查订阅内容" else "已更新 ${nodes.size} 个节点",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = e.message ?: "更新失败") }
            }
        }
    }

    fun testAll() {
        val nodes = _state.value.rows.map { it.node }
        if (nodes.isEmpty()) {
            _state.update { it.copy(message = "请先导入订阅") }
            return
        }
        testJob?.cancel()
        _state.update { st ->
            st.copy(
                testing = true,
                testedCount = 0,
                rows = st.rows.map { it.copy(ping = PingResult(PingStatus.TESTING)) },
            )
        }
        testJob = viewModelScope.launch {
            try {
                engine.testAll(nodes).collect { update ->
                    _state.update { st ->
                        st.copy(
                            rows = st.rows.map { row ->
                                if (row.node.id == update.nodeId) row.copy(ping = update.result) else row
                            },
                            testedCount = st.testedCount + 1,
                        )
                    }
                }
                _state.update { it.copy(testing = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(testing = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, message = e.message) }
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        _state.update { it.copy(testing = false) }
    }

    fun sortByLatency() = _state.update { st ->
        st.copy(rows = st.rows.sortedWith(LatencyComparator))
    }

    // ---- 测网速：逐个走各自代理隧道下载，串行测量 ----
    fun testSpeedAll() {
        if (_state.value.vpnActive) {
            _state.update { it.copy(message = "全局 VPN 运行中，请先停止再测网速") }
            return
        }
        val rows = _state.value.rows
        if (rows.isEmpty()) {
            _state.update { it.copy(message = "请先导入订阅") }
            return
        }
        speedJob?.cancel()
        proxyJob?.cancel()
        // 初始化每行：不支持→UNSUPPORTED；延迟已测且不可达→直接跳过（省去逐个干等超时的漫长过程）；其余→待测
        val initialized = rows.map { row ->
            val speed = when {
                !row.node.protocol.isProxySupported -> SpeedResult(SpeedStatus.UNSUPPORTED)
                row.ping.status in DEAD_PING -> SpeedResult(SpeedStatus.ERROR, error = "延迟不通")
                else -> SpeedResult(SpeedStatus.TESTING)
            }
            row.copy(speed = speed)
        }
        val toTest = initialized.filter { it.speed.status == SpeedStatus.TESTING }.map { it.node }
        // 不送测的节点（不支持 + 已跳过）先计进度，使「测速 x/总数」能走满
        val preCounted = initialized.size - toTest.size
        _state.update { st ->
            st.copy(
                speedTesting = true,
                speedTestedCount = preCounted,
                proxyNodeId = null, // 测速独占内核/端口，先停掉手动代理
                proxyStarting = false,
                rows = initialized,
            )
        }
        if (toTest.isEmpty()) {
            _state.update {
                it.copy(speedTesting = false, message = "没有可测速的节点；先「测延迟」筛出可用节点会更快")
            }
            return
        }
        speedJob = viewModelScope.launch {
            try {
                speedEngine.testAll(toTest).collect { update ->
                    _state.update { st ->
                        st.copy(
                            rows = st.rows.map { row ->
                                if (row.node.id == update.nodeId) row.copy(speed = update.result) else row
                            },
                            speedTestedCount = st.speedTestedCount + 1,
                        )
                    }
                }
                _state.update { it.copy(speedTesting = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(speedTesting = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(speedTesting = false, message = e.message) }
            }
        }
    }

    fun cancelSpeed() {
        speedJob?.cancel()
        viewModelScope.launch { withContext(Dispatchers.IO) { XrayCore.stop() } }
        _state.update { it.copy(speedTesting = false) }
    }

    // ---- 选一个节点启动本地代理（Stage A：loopback SOCKS/HTTP） ----
    fun startProxy(node: ProxyNode) {
        if (_state.value.vpnActive) {
            _state.update { it.copy(message = "全局 VPN 运行中，请先停止再启动本地代理") }
            return
        }
        if (_state.value.speedTesting) {
            _state.update { it.copy(message = "正在测网速，请先停止再启动代理") }
            return
        }
        if (!node.protocol.isProxySupported) {
            _state.update { it.copy(message = "${node.protocol.label} 暂不支持启动代理") }
            return
        }
        proxyJob?.cancel()
        proxyJob = viewModelScope.launch {
            _state.update { it.copy(proxyStarting = true, message = null) }
            val config = XrayConfigBuilder.build(node, XrayCore.SOCKS_PORT, XrayCore.HTTP_PORT)
            if (config == null) {
                _state.update { it.copy(proxyStarting = false, proxyNodeId = null, message = "配置解析失败") }
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    XrayCore.init(getApplication())
                    XrayCore.start(config, node.id)
                }
                _state.update {
                    it.copy(
                        proxyStarting = false,
                        proxyNodeId = node.id,
                        message = "代理已启动 · SOCKS 127.0.0.1:${XrayCore.SOCKS_PORT} · HTTP 127.0.0.1:${XrayCore.HTTP_PORT}",
                    )
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO) { XrayCore.stop() }
                _state.update { it.copy(proxyStarting = false, proxyNodeId = null) }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { XrayCore.stop() }
                _state.update { it.copy(proxyStarting = false, proxyNodeId = null, message = "代理启动失败：${e.message}") }
            }
        }
    }

    fun stopProxy() {
        proxyJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { XrayCore.stop() }
            _state.update { it.copy(proxyStarting = false, proxyNodeId = null, message = "代理已停止") }
        }
    }

    // ---- Stage B：全局 VPN（VpnService + 内核内建 TUN，接管全设备流量） ----
    /**
     * Ask the UI (Activity) to bring up VPN consent and start [NodeVpnService] for [node].
     * The core is shared, so this first tears down any local proxy / speed test.
     */
    fun startVpn(node: ProxyNode) {
        if (!node.protocol.isProxySupported) {
            _state.update { it.copy(message = "${node.protocol.label} 暂不支持全局 VPN") }
            return
        }
        if (_state.value.speedTesting) {
            _state.update { it.copy(message = "正在测网速，请先停止再启动 VPN") }
            return
        }
        val config = XrayConfigBuilder.build(node, XrayCore.SOCKS_PORT, XrayCore.HTTP_PORT, withTun = true)
        if (config == null) {
            _state.update { it.copy(message = "配置解析失败，无法启动 VPN") }
            return
        }
        // 本地代理与 VPN 共用同一内核实例，先停掉本地代理
        proxyJob?.cancel()
        viewModelScope.launch { withContext(Dispatchers.IO) { XrayCore.stop() } }
        _state.update {
            it.copy(
                proxyNodeId = null,
                proxyStarting = false,
                vpnStarting = true,
                vpnNodeId = node.id,
                vpnNodeName = node.name.ifBlank { node.host },
                message = null,
            )
        }
        _vpnLaunch.tryEmit(VpnLaunchRequest(config, node.id, node.name.ifBlank { node.host }))
    }

    fun stopVpn() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, NodeVpnService::class.java).setAction(NodeVpnService.ACTION_STOP),
        )
    }

    /** User declined the system VPN-consent dialog: roll back the optimistic starting state. */
    fun onVpnConsentDenied() {
        _state.update {
            it.copy(vpnStarting = false, vpnNodeId = null, vpnNodeName = null, message = "已取消 VPN 授权")
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 若全局 VPN 正在前台服务中运行，内核归它持有，别在这里停掉它；
        // 只清理 Stage A 的本地代理内核。
        if (!NodeVpnService.state.value.running && !NodeVpnService.state.value.starting) {
            XrayCore.stop()
        }
    }

    private companion object {
        /** 延迟已测且判定不可达的状态——测网速时直接跳过，不必逐个干等超时。 */
        val DEAD_PING = setOf(PingStatus.TIMEOUT, PingStatus.DNS_FAIL, PingStatus.REFUSED, PingStatus.ERROR)

        val LatencyComparator = compareBy<NodeRow>(
            { if (it.ping.status == PingStatus.OK) 0 else 1 },
            { it.ping.latencyMs ?: Int.MAX_VALUE },
            { it.node.name },
        )
    }
}
