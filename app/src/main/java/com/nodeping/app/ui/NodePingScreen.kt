@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.nodeping.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nodeping.app.model.PingStatus
import com.nodeping.app.model.SpeedStatus
import com.nodeping.app.model.Subscription
import kotlinx.coroutines.launch

@Composable
fun NodePingScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 长按某条已保存订阅时，记住它并弹出重命名对话框
    var renameTarget by remember { mutableStateOf<Subscription?>(null) }
    renameTarget?.let { target ->
        RenameSubscriptionDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameSubscription(target.id, newName)
                renameTarget = null
            },
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 订阅输入框默认隐藏；点「导入订阅」才展开。导入成功（订阅数增加）后自动收起。
    var showInput by remember { mutableStateOf(false) }
    var lastSubCount by remember { mutableStateOf(state.subscriptions.size) }
    LaunchedEffect(state.subscriptions.size) {
        if (state.subscriptions.size > lastSubCount) showInput = false
        lastSubCount = state.subscriptions.size
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NodePing · 节点测速") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // 订阅输入框：默认隐藏，其下内容上移占位；点「导入订阅」向上展开到原位，把下方内容往下挤。
            AnimatedVisibility(visible = showInput) {
                Column {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = state.input,
                            onValueChange = viewModel::onInputChange,
                            label = { Text("订阅链接 / 节点内容") },
                            placeholder = { Text("ss、vmess、vless、trojan、ssr") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            shape = RoundedCornerShape(12.dp),
                            minLines = 1,
                            maxLines = 4,
                            trailingIcon = {
                                if (state.input.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onInputChange("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空")
                                    }
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // 已保存订阅：有订阅时常驻显示；输入框收起时它会上移填补空位。
            if (state.subscriptions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SavedSubscriptionsRow(
                        state = state,
                        onSelect = { viewModel.selectSubscription(it) },
                        onDelete = { viewModel.deleteSubscription(it) },
                        onRename = { renameTarget = it },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    // 输入框隐藏时先展开（把下方内容往下挤）；已展开则真正执行导入。
                    onClick = { if (showInput) viewModel.importSubscription() else showInput = true },
                    enabled = !state.loading && !state.testing && !state.speedTesting,
                ) { Text(if (state.loading) "导入中…" else "导入订阅") }

                OutlinedButton(
                    onClick = { viewModel.updateSubscription() },
                    enabled = state.selectedSubscriptionId != null &&
                        !state.loading && !state.testing && !state.speedTesting,
                ) { Text("更新订阅") }

                if (state.testing) {
                    Button(onClick = { viewModel.cancelTest() }) { Text("停止测延迟") }
                } else {
                    Button(
                        onClick = { viewModel.testAll() },
                        enabled = state.rows.isNotEmpty() && !state.speedTesting,
                    ) { Text("测延迟") }
                }

                if (state.speedTesting) {
                    Button(onClick = { viewModel.cancelSpeed() }) { Text("停止测网速") }
                } else {
                    Button(
                        onClick = { viewModel.testSpeedAll() },
                        enabled = state.rows.isNotEmpty() && !state.testing && !state.vpnActive,
                    ) { Text("测网速") }
                }

                OutlinedButton(
                    onClick = { viewModel.sortByLatency() },
                    enabled = state.rows.isNotEmpty(),
                ) { Text("按延迟排序") }

                // 输入框展开时才出现的红色「关闭」：收起订阅链接输入框，下方内容上移。
                if (showInput) {
                    Button(
                        onClick = { showInput = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("关闭") }
                }
            }

            if (state.loading || state.testing || state.speedTesting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            VpnStatusBar(state, onStop = { viewModel.stopVpn() })

            ProxyStatusBar(state, onStop = { viewModel.stopProxy() })

            SummaryBar(state)

            Spacer(Modifier.height(4.dp))
            // 节点列表 + 右侧可拖动滚轮（滚动条），列表内容需滚动时才出现
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(state.rows, key = { it.node.id }) { row ->
                        NodeRowItem(
                            row = row,
                            isProxy = row.node.id == state.proxyNodeId,
                            isVpn = row.node.id == state.vpnNodeId,
                            proxyBusy = state.proxyStarting || state.speedTesting,
                            vpnActive = state.vpnActive,
                            vpnBusy = state.vpnStarting,
                            onStartProxy = { viewModel.startProxy(row.node) },
                            onStopProxy = { viewModel.stopProxy() },
                            onStartVpn = { viewModel.startVpn(row.node) },
                            onStopVpn = { viewModel.stopVpn() },
                        )
                    }
                }
                VerticalScrollbar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SavedSubscriptionsRow(
    state: UiState,
    onSelect: (Subscription) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (Subscription) -> Unit,
) {
    if (state.subscriptions.isEmpty()) return
    // 切换/改名订阅都是纯本地缓存操作，不联网，故 VPN 运行时也允许操作
    val busy = state.loading || state.testing || state.speedTesting
    Column(Modifier.padding(12.dp)) {
        Text(
            "已保存订阅 · 点按切换 · 长按改名 · × 删除",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.subscriptions.forEach { sub ->
                SubscriptionChip(
                    sub = sub,
                    selected = sub.id == state.selectedSubscriptionId,
                    enabled = !busy,
                    onSelect = { onSelect(sub) },
                    onLongPress = { onRename(sub) },
                    onDelete = { onDelete(sub.id) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionChip(
    sub: Subscription,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface
    val onContainer = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = onContainer,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onSelect,
            onLongClick = onLongPress,
        ),
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = if (sub.nodeCount > 0) "${sub.name} · ${sub.nodeCount}" else sub.name,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Clear,
                contentDescription = "删除订阅",
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) { onDelete() }
                    .padding(2.dp),
            )
        }
    }
}

@Composable
private fun RenameSubscriptionDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名订阅") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("订阅名称") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VpnStatusBar(state: UiState, onStop: () -> Unit) {
    if (!state.vpnActive) return
    val running = state.vpnNodeId != null && !state.vpnStarting
    Surface(
        color = if (running) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "全局 VPN 运行中 · ${state.vpnNodeName.orEmpty()}" else "正在启动全局 VPN…",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "全设备流量经该节点转发",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OutlinedButton(onClick = onStop) { Text("停止 VPN") }
        }
    }
}

@Composable
private fun ProxyStatusBar(state: UiState, onStop: () -> Unit) {
    val node = state.rows.firstOrNull { it.node.id == state.proxyNodeId }?.node
    when {
        state.proxyStarting -> {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    "正在启动代理…",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        node != null -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "代理运行中 · ${node.name.ifBlank { node.host }}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "SOCKS 127.0.0.1:10808 · HTTP 127.0.0.1:10809",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OutlinedButton(onClick = onStop) { Text("停止") }
                }
            }
        }
    }
}

@Composable
private fun SummaryBar(state: UiState) {
    if (state.rows.isEmpty()) return
    val alive = state.rows.count { it.ping.status == PingStatus.OK }
    val best = state.rows.mapNotNull { it.ping.latencyMs }.minOrNull()
    val fastest = state.rows
        .filter { it.speed.status == SpeedStatus.OK }
        .mapNotNull { it.speed.mbps }
        .maxOrNull()
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        Text("共 ${state.rows.size}", style = style, maxLines = 1, softWrap = false)
        Text("可用 $alive", style = style, maxLines = 1, softWrap = false)
        if (state.testing || state.testedCount > 0) {
            Text("延迟 ${state.testedCount}/${state.rows.size}", style = style, maxLines = 1, softWrap = false)
        }
        best?.let { Text("最快 $it ms", style = style, maxLines = 1, softWrap = false) }
        if (state.speedTesting || state.speedTestedCount > 0) {
            Text("测速 ${state.speedTestedCount}/${state.rows.size}", style = style, maxLines = 1, softWrap = false)
        }
        fastest?.let { Text("最高 %.1f Mbps".format(it), style = style, maxLines = 1, softWrap = false) }
    }
}

/**
 * 贴在节点列表右侧的竖向滚轮（滚动条）：反映当前滚动位置，可拖动快速滚动。
 * 仅在内容高度超过可视区域（确实需要滚动）时才出现。
 */
@Composable
private fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    width: Dp = 6.dp,
    minThumbHeight: Dp = 32.dp,
) {
    val scope = rememberCoroutineScope()
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.width(width)) {
        val info = state.layoutInfo
        val totalItems = info.totalItemsCount
        val visible = info.visibleItemsInfo
        if (totalItems == 0 || visible.isEmpty()) return@BoxWithConstraints

        val trackPx = constraints.maxHeight.toFloat()
        val avgItem = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
        val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val contentPx = avgItem * totalItems
        if (contentPx <= viewportPx) return@BoxWithConstraints // 无需滚动则不画滚轮

        val minThumbPx = with(density) { minThumbHeight.toPx() }
        val thumbPx = (viewportPx / contentPx * trackPx).coerceIn(minThumbPx, trackPx)
        val maxScrollPx = contentPx - viewportPx
        val scrolledPx = (visible.first().index * avgItem - visible.first().offset).coerceAtLeast(0f)
        val proportion = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
        val thumbOffsetPx = proportion * (trackPx - thumbPx)

        // 轨道底色
        Box(
            Modifier
                .fillMaxHeight()
                .width(width)
                .clip(RoundedCornerShape(width / 2))
                .background(trackColor),
        )
        // 滑块（可拖动快速滚动）
        Box(
            Modifier
                .offset(y = with(density) { thumbOffsetPx.toDp() })
                .width(width)
                .height(with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(width / 2))
                .background(thumbColor)
                .pointerInput(trackPx, thumbPx, maxScrollPx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val trackRange = trackPx - thumbPx
                        if (trackRange > 0f) {
                            val delta = drag.y / trackRange * maxScrollPx
                            scope.launch { state.scrollBy(delta) }
                        }
                    }
                },
        )
    }
}
