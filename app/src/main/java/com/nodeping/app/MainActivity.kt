package com.nodeping.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nodeping.app.net.NodeVpnService
import com.nodeping.app.ui.MainViewModel
import com.nodeping.app.ui.NodePingScreen
import com.nodeping.app.ui.VpnLaunchRequest
import com.nodeping.app.ui.theme.NodePingTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** The launch request held while the system VPN-consent dialog is up. */
    private var pendingVpn: VpnLaunchRequest? = null

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val req = pendingVpn
        pendingVpn = null
        if (result.resultCode == RESULT_OK && req != null) {
            startVpnService(req)
        } else {
            viewModel.onVpnConsentDenied()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored — VPN still runs without a visible notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // The VpnService.prepare() consent dialog needs an Activity, so the ViewModel asks
        // us (via vpnLaunch) rather than starting the service itself.
        lifecycleScope.launch {
            viewModel.vpnLaunch.collect { req ->
                val consent = VpnService.prepare(this@MainActivity)
                if (consent == null) {
                    startVpnService(req)
                } else {
                    pendingVpn = req
                    vpnConsentLauncher.launch(consent)
                }
            }
        }

        setContent {
            NodePingTheme {
                NodePingScreen(viewModel)
            }
        }
    }

    private fun startVpnService(req: VpnLaunchRequest) {
        val intent = Intent(this, NodeVpnService::class.java).apply {
            action = NodeVpnService.ACTION_START
            putExtra(NodeVpnService.EXTRA_CONFIG, req.configJson)
            putExtra(NodeVpnService.EXTRA_NODE_ID, req.nodeId)
            putExtra(NodeVpnService.EXTRA_NODE_NAME, req.nodeName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
