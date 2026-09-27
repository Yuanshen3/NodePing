package com.nodeping.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeping.app.model.NodeRow
import com.nodeping.app.model.PingResult
import com.nodeping.app.model.PingStatus
import com.nodeping.app.model.SpeedResult
import com.nodeping.app.model.SpeedStatus
import com.nodeping.app.ui.theme.LatencyBad
import com.nodeping.app.ui.theme.LatencyGood
import com.nodeping.app.ui.theme.LatencyOk

@Composable
fun NodeRowItem(
    row: NodeRow,
    isProxy: Boolean = false,
    isVpn: Boolean = false,
    proxyBusy: Boolean = false,
    vpnActive: Boolean = false,
    vpnBusy: Boolean = false,
    onStartProxy: () -> Unit = {},
    onStopProxy: () -> Unit = {},
    onStartVpn: () -> Unit = {},
    onStopVpn: () -> Unit = {},
) {
    val highlighted = isProxy || isVpn
    val border = if (highlighted) {
        androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else null
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = border,
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.node.name.ifBlank { row.node.host },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolChip(row.node.protocol.label)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${row.node.host}:${row.node.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isVpn) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "全局 VPN 运行中 · 全设备流量",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (isProxy) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "本地代理运行中 · 127.0.0.1:10808/10809",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                LatencyBadge(row.ping)
                SpeedBadge(row.speed)
            }
            Spacer(Modifier.width(4.dp))
            NodeActions(
                isProxy = isProxy,
                isVpn = isVpn,
                supported = row.node.protocol.isProxySupported,
                proxyBusy = proxyBusy,
                vpnActive = vpnActive,
                vpnBusy = vpnBusy,
                onStartProxy = onStartProxy,
                onStopProxy = onStopProxy,
                onStartVpn = onStartVpn,
                onStopVpn = onStopVpn,
            )
        }
    }
}

@Composable
private fun NodeActions(
    isProxy: Boolean,
    isVpn: Boolean,
    supported: Boolean,
    proxyBusy: Boolean,
    vpnActive: Boolean,
    vpnBusy: Boolean,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
) {
    when {
        isVpn -> OutlinedButton(onClick = onStopVpn) { Text("停止 VPN") }
        isProxy -> OutlinedButton(onClick = onStopProxy) { Text("停止代理") }
        !supported -> TextButton(onClick = {}, enabled = false) { Text("不支持") }
        else -> {
            var menuOpen by remember { mutableStateOf(false) }
            // 有 VPN 在跑（别的节点）或正在启动时，本行的启动入口禁用，避免抢占同一内核。
            val enabled = !proxyBusy && !vpnBusy && !vpnActive
            Box {
                TextButton(onClick = { menuOpen = true }, enabled = enabled) { Text("启动") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("全局 VPN（接管全设备）") },
                        onClick = { menuOpen = false; onStartVpn() },
                    )
                    DropdownMenuItem(
                        text = { Text("本地代理（127.0.0.1）") },
                        onClick = { menuOpen = false; onStartProxy() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolChip(label: String) {
    Box(
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LatencyBadge(ping: PingResult) {
    when (ping.status) {
        PingStatus.TESTING -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        PingStatus.OK -> {
            val ms = ping.latencyMs ?: 0
            val color = when {
                ms < 150 -> LatencyGood
                ms < 350 -> LatencyOk
                else -> LatencyBad
            }
            BadgeText("$ms ms", color)
        }
        PingStatus.TIMEOUT -> BadgeText("超时", LatencyBad)
        PingStatus.DNS_FAIL -> BadgeText("DNS", LatencyBad)
        PingStatus.REFUSED -> BadgeText("拒绝", LatencyBad)
        PingStatus.ERROR -> BadgeText("错误", LatencyBad)
        PingStatus.UNTESTED -> BadgeText("—", MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun BadgeText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SpeedBadge(speed: SpeedResult) {
    when (speed.status) {
        SpeedStatus.UNTESTED -> {}
        SpeedStatus.TESTING -> {
            Spacer(Modifier.height(2.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        }
        SpeedStatus.OK -> {
            val mbps = speed.mbps ?: 0.0
            val color = when {
                mbps >= 20.0 -> LatencyGood
                mbps >= 5.0 -> LatencyOk
                else -> LatencyBad
            }
            SmallBadge("%.1f Mbps".format(mbps), color)
        }
        SpeedStatus.UNSUPPORTED -> SmallBadge("不支持", MaterialTheme.colorScheme.outline)
        SpeedStatus.TIMEOUT -> SmallBadge("超时", LatencyBad)
        SpeedStatus.ERROR -> SmallBadge(speed.error ?: "错误", LatencyBad)
    }
}

@Composable
private fun SmallBadge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
    )
}
