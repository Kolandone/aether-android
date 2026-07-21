package com.aether.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
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

class MainActivity : AppCompatActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var aetherManager: AetherManager

    private lateinit var btnCircle: FrameLayout
    private lateinit var btnIcon: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnMasque: TextView
    private lateinit var btnWireguard: TextView
    private lateinit var btnGool: TextView
    private lateinit var tvVersion: TextView
    private lateinit var mainRoot: android.widget.LinearLayout

    private var connected = false
    private var connecting = false
    private var selectedProtocol = AetherConfig.Protocol.MASQUE
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configManager = ConfigManager(this)
        aetherManager = AetherManager(this)

        initViews()
        loadState()
        setupCallbacks()
    }

    override fun onResume() {
        super.onResume()
        // Restore connecting state if process is running but not yet connected
        if (!connected && aetherManager.isRunning()) {
            connecting = true
        }
        updateUI()
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        aetherManager.destroy()
        super.onDestroy()
    }

    private fun initViews() {
        btnCircle = findViewById(R.id.btnCircle)
        btnIcon = findViewById(R.id.btnIcon)
        tvStatus = findViewById(R.id.tvStatus)
        btnMasque = findViewById(R.id.btnMasque)
        btnWireguard = findViewById(R.id.btnWireguard)
        btnGool = findViewById(R.id.btnGool)
        tvVersion = findViewById(R.id.tvVersion)
        mainRoot = findViewById(R.id.mainRoot)

        tvVersion.text = "v1.3 • core v1.3.0"

        btnCircle.setOnClickListener { toggleConnection() }

        btnMasque.setOnClickListener { selectProtocol(AetherConfig.Protocol.MASQUE) }
        btnWireguard.setOnClickListener { selectProtocol(AetherConfig.Protocol.WIREGUARD) }
        btnGool.setOnClickListener { selectProtocol(AetherConfig.Protocol.GOOL) }

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
        updateProtocolChips()
    }

    private fun setupCallbacks() {
        aetherManager.setLogCallback { line ->
            LogsActivity.appendLog(line)
        }
        aetherManager.setStatusCallback { running ->
            runOnUiThread {
                connected = running
                if (running) connecting = false
                updateUI()
            }
        }

        KeepAliveService.onStopRequested = {
            runOnUiThread {
                aetherManager.stop()
                connected = false
                updateUI()
            }
        }
    }

    private fun selectProtocol(protocol: AetherConfig.Protocol) {
        selectedProtocol = protocol
        updateProtocolChips()
        // Bounce animation on protocol chips
        val targetBtn = when (protocol) {
            AetherConfig.Protocol.MASQUE -> btnMasque
            AetherConfig.Protocol.WIREGUARD -> btnWireguard
            AetherConfig.Protocol.GOOL -> btnGool
        }
        targetBtn.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).withEndAction {
            targetBtn.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
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

    private fun toggleConnection() {
        // Button press animation
        btnCircle.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction {
            btnCircle.animate().scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }.start()

        if (connected) {
            stopPulseAnimation()
            aetherManager.stop()
            KeepAliveService.stop(this)
            connected = false
            connecting = false
            updateUI()
        } else {
            // Start connecting animation
            connecting = true
            startConnectingAnimation()

            val existing = configManager.loadConfig()
            val updated = existing.copy(protocol = selectedProtocol)
            configManager.saveConfig(updated)

            lifecycleScope.launch {
                val started = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    aetherManager.start(updated)
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (started) {
                        // Process started, but SOCKS not ready yet
                        // statusCallback(true) will fire when SOCKS5 is ready
                        KeepAliveService.start(this@MainActivity)
                        startPulseAnimation()
                    } else {
                        stopPulseAnimation()
                        tvStatus.text = "Failed"
                        tvStatus.setTextColor(getColor(R.color.red))
                    }
                }
            }
        }
    }

    private fun startConnectingAnimation() {
        tvStatus.text = "Connecting..."
        tvStatus.setTextColor(getColor(R.color.accent))
        btnCircle.setBackgroundResource(R.drawable.circle_connecting)
        // Pulsing animation
        startPulseAnimation()
        // Background fade
        mainRoot.animate().alpha(0.95f).setDuration(300).start()
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
        // Glow effect on circle
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
        mainRoot.animate().alpha(1f).setDuration(300).start()
    }

    private fun updateUI() {
        if (connecting && !connected) {
            tvStatus.text = "Connecting..."
            tvStatus.setTextColor(getColor(R.color.accent))
            btnCircle.setBackgroundResource(R.drawable.circle_connecting)
            startPulseAnimation()
        } else if (connected) {
            connecting = false
            stopPulseAnimation()
            tvStatus.text = "Connected"
            tvStatus.setTextColor(getColor(R.color.green))
            btnCircle.setBackgroundResource(R.drawable.circle_connected)
            btnIcon.animate().rotation(360f).setDuration(500)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
            // Green glow
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
