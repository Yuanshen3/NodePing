package com.nodeping.app.net

import android.content.Context
import android.util.Base64
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.security.SecureRandom

/**
 * Thin wrapper over the embedded Xray core (libv2ray / AndroidLibXrayLite).
 *
 * The core has a built-in TUN stack (gVisor netstack + xray-core proxy/tun), so [start]
 * takes an optional TUN fd: Stage A (local proxy / speed test) passes -1 to run only the
 * loopback SOCKS + HTTP inbounds; Stage B ([com.nodeping.app.net.NodeVpnService]) passes a
 * real VpnService fd to route the whole device through the node. At most one core instance
 * runs at a time; [start] always stops any previous instance first.
 */
object XrayCore {

    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809

    private var initialized = false
    private var controller: CoreController? = null

    /** Last core-start failure, for surfacing a real reason to the UI. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var runningNodeId: String? = null
        private set

    val isRunning: Boolean
        get() = try { controller?.isRunning == true } catch (e: Throwable) { false }

    /** Idempotent one-time core-env init. Safe to call before every start. */
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences("nodeping", Context.MODE_PRIVATE)
        // Xray reads `xray.xudp.basekey` from the env and base64url-decodes it, expecting
        // exactly 32 bytes — v2rayNG feeds it a URL-safe/no-padding base64 of 32 bytes.
        // A raw hex string decodes to the wrong length and makes core.New() fail with
        // "failed to reload environment settings", so generate a proper key once and keep it.
        var key = prefs.getString(KEY_XUDP, null)
        if (key.isNullOrBlank()) {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            key = Base64.encodeToString(raw, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
            prefs.edit().putString(KEY_XUDP, key).apply()
        }
        // Xray resolves geoip.dat / geosite.dat from the env asset path. The aar bundles
        // them under assets/, but the core reads them from the filesystem — copy them out
        // once (same as v2rayNG does on first run) so any geo-referencing config can start.
        val assetDir = context.filesDir.absolutePath
        copyAssetIfMissing(context, "geoip.dat", File(assetDir, "geoip.dat"))
        copyAssetIfMissing(context, "geosite.dat", File(assetDir, "geosite.dat"))
        Libv2ray.initCoreEnv(assetDir, key)
        initialized = true
    }

    /**
     * Start the core with [configJson]. Throws if the core fails to start.
     *
     * [tunFd] is the file descriptor to run the built-in TUN (gVisor netstack + xray-core
     * proxy/tun) on: pass a real VpnService fd for global (Stage B) VPN routing, or -1 to
     * skip TUN and only expose the loopback SOCKS/HTTP inbounds (Stage A local proxy /
     * speed test). The core dups the fd internally, so the caller keeps ownership.
     */
    @Synchronized
    fun start(configJson: String, nodeId: String? = null, tunFd: Int = -1) {
        stop()
        lastError = null
        try {
            val ctrl = Libv2ray.newCoreController(NoopCallback)
            ctrl.startLoop(configJson, tunFd)
            controller = ctrl
            runningNodeId = nodeId
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            controller = null
            runningNodeId = null
            throw e
        }
    }

    @Synchronized
    fun stop() {
        try {
            controller?.stopLoop()
        } catch (e: Exception) {
            // ignore — best-effort shutdown
        }
        controller = null
        runningNodeId = null
    }

    fun version(): String = try { Libv2ray.checkVersionX() } catch (e: Throwable) { "" }

    private fun copyAssetIfMissing(context: Context, assetName: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        try {
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            // Asset not present or copy failed — non-fatal; configs without geo refs still run.
        }
    }

    // v2 pref key: an earlier build stored a raw hex UUID here under "xudp_key" which Xray
    // could not decode; bump the key name so those bad cached values are ignored.
    private const val KEY_XUDP = "xudp_basekey_b64"

    private object NoopCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
