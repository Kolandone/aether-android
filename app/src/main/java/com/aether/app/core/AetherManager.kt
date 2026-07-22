package com.aether.app.core

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.aether.app.model.AetherConfig
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

class AetherManager(private val context: Context) {
    private var process: Process? = null
    private var readerThread: Job? = null
    private var errReaderThread: Job? = null
    private var exitThread: Job? = null
    private var healthThread: Job? = null
    private var logCallback: ((String) -> Unit)? = null
    private var statusCallback: ((Boolean) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    // ponytail: keeping a single log callback + a status callback is enough for now.
    // If we add multiple consumers later, switch to SharedFlow.

    companion object {
        private const val TAG = "AetherManager"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 1819
        private const val WAKELOCK_TAG = "aether:process"
    }

    fun setLogCallback(callback: (String) -> Unit) { logCallback = callback }
    fun setStatusCallback(callback: (Boolean) -> Unit) { statusCallback = callback }
    private fun log(line: String) { Log.d(TAG, line); logCallback?.invoke(line) }

    fun start(config: AetherConfig): Boolean {
        if (isRunning()) stop()

        val binaryPath = getBinaryPath() ?: return false
        val args = config.toArgs()
        log("▶ ${listOf(binaryPath).plus(args).joinToString(" ")}")

        return try {
            val pb = ProcessBuilder(listOf(binaryPath) + args)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(false)
            process = pb.start()

            acquireWakeLock()

            process?.inputStream?.bufferedReader()?.let { reader ->
                readerThread = scope.launch {
                    try {
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            log(line)
                        }
                    } catch (e: Exception) {
                        if (isActive) log("stdout err: ${e.message}")
                    }
                }
            }

            process?.errorStream?.bufferedReader()?.let { reader ->
                errReaderThread = scope.launch {
                    try {
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            log(line)
                        }
                    } catch (e: Exception) {
                        if (isActive) log("stderr err: ${e.message}")
                    }
                }
            }

            // Monitor process exit
            exitThread = scope.launch {
                val exit = process?.waitFor() ?: return@launch
                log("■ Process exited with code: $exit")
                releaseWakeLock()
                withContext(Dispatchers.Main) { statusCallback?.invoke(false) }
            }

            // Health check — verify SOCKS is reachable
            healthThread = scope.launch {
                for (i in 1..90) {
                    delay(2000)
                    if (!isRunning()) break
                    if (checkSocks()) {
                        log("✓ SOCKS5 ready on $SOCKS_HOST:$SOCKS_PORT")
                        withContext(Dispatchers.Main) { statusCallback?.invoke(true) }
                        break
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            log("✗ ${e.message}")
            false
        }
    }

    private fun getBinaryPath(): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeBin = File(nativeDir, "libaether.so")
        if (nativeBin.exists()) {
            log("▶ Binary: ${nativeBin.absolutePath} (${nativeBin.length()} bytes)")
            // Ensure executable
            if (!nativeBin.canExecute()) nativeBin.setExecutable(true, false)
            return nativeBin.absolutePath
        }
        log("✗ Binary not found at ${nativeBin.absolutePath}")
        return null
    }

    fun stop() {
        readerThread?.cancel()
        errReaderThread?.cancel()
        exitThread?.cancel()
        healthThread?.cancel()
        process?.let { proc ->
            try {
                proc.outputStream.close()
                proc.inputStream.close()
                proc.errorStream.close()
                proc.destroy()
                if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
        process = null
        releaseWakeLock()
        log("■ Stopped")
    }

    fun isRunning(): Boolean = try {
        process?.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    fun checkSocks(): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 1000)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock?.acquire(60 * 60 * 1000L) // 1 hour, renewed by process monitor
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
