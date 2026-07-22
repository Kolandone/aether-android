package com.aether.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import com.aether.app.core.AetherManager
import com.aether.app.core.ConfigManager
import com.aether.app.model.AetherConfig
import com.aether.app.service.AetherVpnService
import com.aether.app.service.ConnectionLog

class MainActivity : AppCompatActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var aetherManager: AetherManager

    private lateinit var btnCircle: FrameLayout
    private lateinit var btnIcon: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnMasque: TextView
    private lateinit var btnWireguard: TextView
    private lateinit var btnGool: TextView
    private lateinit var btnVpnMode: TextView
    private lateinit var btnSocksMode: TextView
    private lateinit var tvVersion: TextView

    private var connected = false
    private var connecting = false
    private var selectedProtocol = AetherConfig.Protocol.MASQUE
    private var selectedMode = AetherConfig.ConnectionMode.VPN
    private var pulseAnimator: ObjectAnimator? = null

    private val VPN_REQUEST = 1001

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(AetherVpnService.EXTRA_STATUS)) {
                AetherVpnService.STATUS_CONNECTING -> {
                    connecting = true
                    updateUI()
                }
                AetherVpnService.STATUS_CONNECTED -> {
                    connected = true
                    connecting = false
                    updateUI()
                }
                AetherVpnService.STATUS_DISCONNECTED -> {
                    connected = false
                    connecting = false
                    updateUI()
                }
                AetherVpnService.STATUS_FAILED -> {
                    connected = false
                    connecting = false
                    tvStatus.text = intent.getStringExtra(AetherVpnService.EXTRA_DETAIL) ?: "Failed"
                    tvStatus.setTextColor(getColor(R.color.red))
                    updateUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configManager = ConfigManager(this)
        aetherManager = AetherManager(this)

        initViews()
        loadState()
        setupCallbacks()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AetherVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStatusReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(vpnStatusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (AetherVpnService.isRunning) {
            connected = true
            connecting = false
        }
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                startVpnService()
            } else {
                // Permission denied → fall back to SOCKS
                selectMode(AetherConfig.ConnectionMode.SOCKS)
                connectSocks()
            }
        }
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        aetherManager.destroy()
        super.onDestroy()
    }

    // =========================================================================
    // Init
    // =========================================================================

    private fun initViews() {
        btnCircle = findViewById(R.id.btnCircle)
        btnIcon = findViewById(R.id.btnIcon)
        tvStatus = findViewById(R.id.tvStatus)
        btnMasque = findViewById(R.id.btnMasque)
        btnWireguard = findViewById(R.id.btnWireguard)
        btnGool = findViewById(R.id.btnGool)
        btnVpnMode = findViewById(R.id.btnVpnMode)
        btnSocksMode = findViewById(R.id.btnSocksMode)
        tvVersion = findViewById(R.id.tvVersion)

        tvVersion.text = "v1.4 • core v1.3.0"

        btnCircle.setOnClickListener { toggleConnection() }

        // Protocol chips
        btnMasque.setOnClickListener { selectProtocol(AetherConfig.Protocol.MASQUE) }
        btnWireguard.setOnClickListener { selectProtocol(AetherConfig.Protocol.WIREGUARD) }
        btnGool.setOnClickListener { selectProtocol(AetherConfig.Protocol.GOOL) }

        // Mode chips
        btnVpnMode.setOnClickListener { selectMode(AetherConfig.ConnectionMode.VPN) }
        btnSocksMode.setOnClickListener { selectMode(AetherConfig.ConnectionMode.SOCKS) }

        // Bottom nav
        findViewById<TextView>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<TextView>(R.id.tvTelegram).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/kolandjs1")))
        }
        findViewById<TextView>(R.id.tvGithub).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Kolandone")))
        }
    }

    private fun loadState() {
        val config = configManager.loadConfig()
        selectedProtocol = config.protocol
        selectedMode = config.mode
        updateProtocolChips()
        updateModeChips()
    }

    private fun setupCallbacks() {
        aetherManager.setLogCallback { line ->
            LogsActivity.appendLog(line)
        }
        aetherManager.setStatusCallback { running ->
            runOnUiThread {
                if (selectedMode == AetherConfig.ConnectionMode.VPN) {
                    // VPN mode: status managed by AetherVpnService broadcast
                    return@runOnUiThread
                }
                // SOCKS mode: manage directly
                connected = running
                if (running) connecting = false
                updateUI()
            }
        }

        KeepAliveService.onStopRequested = {
            runOnUiThread {
                if (selectedMode == AetherConfig.ConnectionMode.VPN) {
                    AetherVpnService.stop(this)
                } else {
                    aetherManager.stop()
                }
                connected = false
                connecting = false
                updateUI()
            }
        }
    }

    // =========================================================================
    // Protocol & Mode selection
    // =========================================================================

    private fun selectProtocol(protocol: AetherConfig.Protocol) {
        selectedProtocol = protocol
        updateProtocolChips()
        val targetBtn = when (protocol) {
            AetherConfig.Protocol.MASQUE -> btnMasque
            AetherConfig.Protocol.WIREGUARD -> btnWireguard
            AetherConfig.Protocol.GOOL -> btnGool
        }
        bounce(targetBtn)
    }

    private fun selectMode(mode: AetherConfig.ConnectionMode) {
        if (connected || connecting) {
            android.widget.Toast.makeText(this, "Disconnect first to change mode", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        selectedMode = mode
        updateModeChips()
        val targetBtn = when (mode) {
            AetherConfig.ConnectionMode.VPN -> btnVpnMode
            AetherConfig.ConnectionMode.SOCKS -> btnSocksMode
        }
        bounce(targetBtn)
    }

    private fun bounce(view: android.view.View) {
        view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }

    private fun updateProtocolChips() {
        val protocols = listOf(
            btnMasque to AetherConfig.Protocol.MASQUE,
            btnWireguard to AetherConfig.Protocol.WIREGUARD,
            btnGool to AetherConfig.Protocol.GOOL
        )
        for ((btn, proto) in protocols) {
            if (proto == selectedProtocol) {
                btn.setBackgroundResource(R.drawable.chip_selected)
                btn.setTextColor(getColor(R.color.text_primary))
            } else {
                btn.setBackgroundResource(R.drawable.chip_unselected)
                btn.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun updateModeChips() {
        val modes = listOf(
            btnVpnMode to AetherConfig.ConnectionMode.VPN,
            btnSocksMode to AetherConfig.ConnectionMode.SOCKS
        )
        for ((btn, mode) in modes) {
            if (mode == selectedMode) {
                btn.setBackgroundResource(R.drawable.chip_selected)
                btn.setTextColor(getColor(R.color.text_primary))
            } else {
                btn.setBackgroundResource(R.drawable.chip_unselected)
                btn.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    // =========================================================================
    // Connection logic
    // =========================================================================

    private fun toggleConnection() {
        btnCircle.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction {
            btnCircle.animate().scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }.start()

        if (connected || connecting) {
            // ── Disconnect ──
            stopPulseAnimation()
            if (selectedMode == AetherConfig.ConnectionMode.VPN) {
                AetherVpnService.stop(this)
            } else {
                aetherManager.stop()
                KeepAliveService.stop(this)
            }
            connected = false
            connecting = false
            updateUI()
        } else {
            // ── Connect ──
            saveConfig()
            connecting = true
            startConnectingAnimation()

            if (selectedMode == AetherConfig.ConnectionMode.VPN) {
                connectVpn()
            } else {
                connectSocks()
            }
        }
    }

    /** VPN mode: ask for VPN permission, then start AetherVpnService */
    private fun connectVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST)
        } else {
            startVpnService()
        }
    }

    /** SOCKS mode: start Aether binary directly, expose local SOCKS5 proxy */
    private fun connectSocks() {
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                val config = configManager.loadConfig()
                aetherManager.start(config)
            }
            withContext(Dispatchers.Main) {
                if (started) {
                    KeepAliveService.start(this@MainActivity)
                    startPulseAnimation()
                } else {
                    stopPulseAnimation()
                    tvStatus.text = "Failed"
                    tvStatus.setTextColor(getColor(R.color.red))
                    connecting = false
                    updateUI()
                }
            }
        }
    }

    private fun startVpnService() {
        AetherVpnService.start(this)
        KeepAliveService.start(this)
        startPulseAnimation()
    }

    private fun saveConfig() {
        val existing = configManager.loadConfig()
        val updated = existing.copy(protocol = selectedProtocol, mode = selectedMode)
        configManager.saveConfig(updated)
    }

    // =========================================================================
    // Animations
    // =========================================================================

    private fun startConnectingAnimation() {
        tvStatus.text = "Connecting..."
        tvStatus.setTextColor(getColor(R.color.accent))
        btnCircle.setBackgroundResource(R.drawable.circle_connecting)
        startPulseAnimation()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(btnIcon, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        btnCircle.animate()
            .scaleX(1.05f).scaleY(1.05f)
            .setDuration(1000).setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        btnIcon.alpha = 1f
        btnCircle.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
    }

    private fun updateUI() {
        val modeTag = if (selectedMode == AetherConfig.ConnectionMode.VPN) "VPN" else "SOCKS"

        if (connecting && !connected) {
            tvStatus.text = "Connecting..."
            tvStatus.setTextColor(getColor(R.color.accent))
            btnCircle.setBackgroundResource(R.drawable.circle_connecting)
            startPulseAnimation()
        } else if (connected) {
            connecting = false
            stopPulseAnimation()
            tvStatus.text = "Connected • $modeTag"
            tvStatus.setTextColor(getColor(R.color.green))
            btnCircle.setBackgroundResource(R.drawable.circle_connected)
            btnIcon.animate().rotation(360f).setDuration(500)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
            btnCircle.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300)
                .setInterpolator(OvershootInterpolator(2f)).start()
        } else {
            stopPulseAnimation()
            tvStatus.text = "Disconnected"
            tvStatus.setTextColor(getColor(R.color.text_secondary))
            btnCircle.setBackgroundResource(R.drawable.circle_disconnected)
        }
    }
}
