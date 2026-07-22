package com.aether.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.aether.app.R
import com.aether.app.core.AetherManager
import com.aether.app.core.Tun2SocksManager
import com.aether.app.service.ConnectionLog

/**
 * Android VPN Service that captures all device traffic and routes it through
 * the Aether SOCKS5 proxy via tun2socks.
 *
 * Traffic flow:
 *   Device apps
 *     -> VPN TUN interface (captured by Android VPN)
 *     -> tun2socks (bridges TUN to SOCKS proxy)
 *     -> Aether SOCKS5 proxy (127.0.0.1:1819)
 *     -> Aether tunnel (MASQUE/WireGuard/Gool)
 *     -> Internet
 */
class AetherVpnService : VpnService() {

    companion object {
        private const val TAG = "AetherVpnService"
        private const val CHANNEL_ID = "aether_vpn"
        private const val NOTIFICATION_ID = 10
        private const val VPN_MTU = 1500
        private const val VLAN_CLIENT = "10.0.0.2"
        private const val VLAN_ROUTER = "10.0.0.3"

        const val ACTION_START = "com.aether.app.action.START_VPN"
        const val ACTION_STOP = "com.aether.app.action.STOP_VPN"
        const val ACTION_STATUS = "com.aether.app.action.VPN_STATUS"

        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"

        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_FAILED = "failed"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var tun2SocksManager: Tun2SocksManager? = null
    private var aetherManager: AetherManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var readinessCheck: java.util.concurrent.ScheduledFuture<*>? = null
    private val readinessExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    // Network callback for binding underlying network (API 28+)
    @delegate:androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @delegate:androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn(notify = false)
        super.onDestroy()
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true
        sendStatus(STATUS_CONNECTING)

        startForegroundNotification()
        acquireWakeLock()

        // Bind underlying network (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.w(TAG, "requestNetwork failed: ${e.message}")
            }
        }

        // Start Aether process if not running
        aetherManager = AetherManager(this).apply {
            setLogCallback { line ->
                ConnectionLog.record(line)
                Log.d(TAG, "aether: $line")
            }
            setStatusCallback { running ->
                if (!running && isRunning) {
                    Log.w(TAG, "Aether process died")
                    sendStatus(STATUS_FAILED, "Aether process stopped")
                }
            }
        }

        // Start Aether in background thread
        Thread {
            try {
                // Load config and start Aether
                val config = com.aether.app.core.ConfigManager(this).loadConfig()
                val started = aetherManager?.start(config) ?: false
                if (!started) {
                    sendStatus(STATUS_FAILED, "Failed to start Aether core")
                    stopVpn()
                    return@Thread
                }

                // Wait for SOCKS5 to be ready
                waitForSocks()

                // Establish VPN interface
                val builder = Builder()
                builder.setMtu(VPN_MTU)
                builder.addAddress(VLAN_CLIENT, 32)
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
                builder.setSession("Aether")

                // Exclude our own app to prevent routing loop
                builder.addDisallowedApplication(packageName)

                // Apply per-app proxy if configured
                applyPerAppProxy(builder)

                // Close old interface
                try { tunInterface?.close() } catch (_: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                tunInterface = builder.establish()
                if (tunInterface == null) {
                    sendStatus(STATUS_FAILED, "VPN permission denied")
                    stopVpn()
                    return@Thread
                }

                // Start tun2socks to bridge TUN -> SOCKS5
                val socksPort = 1819
                tun2SocksManager = Tun2SocksManager(this)
                val tun2socksStarted = tun2SocksManager!!.start(
                    tunFd = tunInterface!!,
                    socksHost = "127.0.0.1",
                    socksPort = socksPort,
                    routerAddress = VLAN_ROUTER
                )

                if (!tun2socksStarted) {
                    sendStatus(STATUS_FAILED, "Failed to start tun2socks")
                    stopVpn()
                    return@Thread
                }

                sendStatus(STATUS_CONNECTED)
                Log.i(TAG, "VPN connected successfully")

                // Monitor Aether process
                monitorProcess()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed", e)
                sendStatus(STATUS_FAILED, e.message)
                stopVpn()
            }
        }.start()
    }

    private fun stopVpn(notify: Boolean = true) {
        isRunning = false

        readinessCheck?.cancel(true)
        readinessCheck = null

        tun2SocksManager?.stop()
        tun2SocksManager = null

        aetherManager?.stop()
        aetherManager = null

        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}
        }

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (notify) sendStatus(STATUS_DISCONNECTED)
    }

    private fun waitForSocks(timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (aetherManager?.checkSocks() == true) {
                Log.i(TAG, "SOCKS5 proxy is ready")
                return
            }
            Thread.sleep(500)
        }
        throw Exception("SOCKS5 proxy not ready after ${timeoutMs}ms")
    }

    private fun monitorProcess() {
        Thread {
            while (isRunning) {
                Thread.sleep(5000)
                if (!isRunning) break
                if (aetherManager?.isRunning() != true) {
                    Log.w(TAG, "Aether process died, stopping VPN")
                    sendStatus(STATUS_FAILED, "Aether process died")
                    stopVpn()
                    break
                }
            }
        }.start()
    }

    private fun applyPerAppProxy(builder: VpnService.Builder) {
        // Add per-app proxy support via SharedPreferences if needed
        val prefs = getSharedPreferences("per_app_proxy", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
        val bypassMode = prefs.getBoolean("bypass_mode", true)
        val packages = prefs.getStringSet("packages", emptySet()) ?: emptySet()

        if (enabled && packages.isNotEmpty()) {
            packages.forEach { pkg ->
                try {
                    if (bypassMode) {
                        builder.addDisallowedApplication(pkg)
                    } else {
                        builder.addAllowedApplication(pkg)
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Package not found: $pkg")
                }
            }
        }
    }

    private fun sendStatus(status: String, detail: String? = null) {
        Log.i(TAG, "status=$status${detail?.let { " detail=$it" } ?: ""}")
        ConnectionLog.record("${status.replaceFirstChar(Char::uppercase)}${detail?.let { ": $it" } ?: ""}")
        updateNotification(
            when (status) {
                STATUS_CONNECTING -> "Connecting..."
                STATUS_CONNECTED -> "Connected"
                STATUS_FAILED -> detail ?: "Failed"
                STATUS_DISCONNECTED -> "Disconnected"
                else -> "Aether VPN"
            }
        )
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .apply { detail?.let { putExtra(EXTRA_DETAIL, it) } })
    }

    // =========================================================================
    // Foreground notification
    // =========================================================================

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Aether VPN", NotificationManager.IMPORTANCE_LOW
        ))

        val notification = buildNotification("Connecting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AetherVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_power)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    // =========================================================================
    // Wake lock
    // =========================================================================

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aether:vpn")
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
