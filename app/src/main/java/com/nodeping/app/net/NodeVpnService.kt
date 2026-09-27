package com.nodeping.app.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.nodeping.app.MainActivity
import com.nodeping.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Stage B — real global VPN. Builds a TUN interface via [VpnService.Builder] and hands its
 * fd to the embedded Xray core ([XrayCore]), whose built-in gVisor netstack + xray-core
 * proxy/tun turn every device packet into an outbound through the selected node. No external
 * tun2socks binary is needed.
 *
 * The core has no `protect(fd)` callback, so we exclude our own package from the tunnel
 * ([android.net.VpnService.Builder.addDisallowedApplication]) — that keeps the core's socket
 * to the proxy server on the real network and avoids a routing loop.
 *
 * Runs as a foreground service; [state] is observed by the UI to reflect connect state.
 */
class NodeVpnService : VpnService() {

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
        val nodeName = intent?.getStringExtra(EXTRA_NODE_NAME) ?: "节点"
        if (config.isNullOrBlank()) {
            _state.value = VpnRunState(error = "缺少配置")
            stopSelf()
            return START_NOT_STICKY
        }

        _state.value = VpnRunState(starting = true, nodeId = nodeId, nodeName = nodeName)
        startForegroundCompat(notification("正在连接 · $nodeName"))

        // establish() + startLoop() can block briefly; keep them off the main thread.
        thread(name = "nodeping-vpn-start") {
            try {
                val fd = establishTun(nodeName)
                XrayCore.init(applicationContext)
                XrayCore.start(config, nodeId, fd)
                _state.value = VpnRunState(running = true, nodeId = nodeId, nodeName = nodeName)
                notificationManager().notify(NOTIF_ID, notification("已连接 · $nodeName"))
            } catch (e: Throwable) {
                _state.value = VpnRunState(error = e.message ?: e.javaClass.simpleName)
                teardown()
            }
        }
        return START_NOT_STICKY
    }

    private fun establishTun(nodeName: String): Int {
        val builder = Builder()
            .setSession(nodeName)
            .setMtu(VPN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        // Exclude ourselves so the core's tunnel socket bypasses the VPN (no protect()).
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // Should never happen for our own package; ignore and continue.
        }
        val pfd = builder.establish()
            ?: throw IllegalStateException("establish() 返回 null（VPN 未授权或已被系统占用）")
        tunInterface = pfd
        return pfd.fd
    }

    /** Stop the core, close the TUN, drop the foreground state, and stop the service. */
    private fun teardown() {
        try {
            XrayCore.stop()
        } catch (e: Throwable) {
            // best-effort
        }
        try {
            tunInterface?.close()
        } catch (e: Throwable) {
            // best-effort
        }
        tunInterface = null
        if (_state.value.error == null) _state.value = VpnRunState()
        stopForegroundCompat()
        stopSelf()
    }

    /** System revoked the VPN (another VPN started, or user tapped "disconnect"). */
    override fun onRevoke() {
        _state.value = VpnRunState(error = "VPN 已被系统断开")
        teardown()
    }

    override fun onDestroy() {
        // Guard against a kill that skipped teardown().
        if (tunInterface != null || XrayCore.isRunning) {
            try {
                XrayCore.stop()
            } catch (e: Throwable) {
            }
            try {
                tunInterface?.close()
            } catch (e: Throwable) {
            }
            tunInterface = null
            if (_state.value.running || _state.value.starting) _state.value = VpnRunState()
        }
        super.onDestroy()
    }

    // ---- foreground / notification ----

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "VPN 状态", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            notificationManager().createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NodeVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NodePing 全局代理")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.nodeping.app.action.VPN_START"
        const val ACTION_STOP = "com.nodeping.app.action.VPN_STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_NODE_ID = "nodeId"
        const val EXTRA_NODE_NAME = "nodeName"

        private const val CHANNEL_ID = "nodeping_vpn"
        private const val NOTIF_ID = 0x4E50 // 'NP'
        private const val VPN_MTU = 1500
        private const val TUN_ADDRESS = "10.10.10.2"
        private const val TUN_PREFIX = 32

        private val _state = MutableStateFlow(VpnRunState())

        /** Observable connect state for the UI. */
        val state: StateFlow<VpnRunState> = _state.asStateFlow()
    }
}

/** Snapshot of the VPN service's lifecycle, surfaced to the UI. */
data class VpnRunState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val nodeId: String? = null,
    val nodeName: String? = null,
    val error: String? = null,
)
