package com.aether.app.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Manages the tun2socks native process that bridges the Android VPN TUN interface
 * to a local SOCKS5 proxy.
 *
 * tun2socks reads raw IP packets from the TUN fd and forwards them to the SOCKS5
 * proxy specified by --socks-server-addr.
 *
 * Usage:
 *   val mgr = Tun2SocksManager(context)
 *   mgr.start(tunFd, "127.0.0.1", 1819, "10.0.0.3")
 *   // ... later ...
 *   mgr.stop()
 */
class Tun2SocksManager(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksManager"
        private const val TUN2SOCKS_BINARY = "libtun2socks.so"
        private const val VPN_MTU = 1500
        private const val SOCK_PATH = "tun2socks.sock"
    }

    private var process: Process? = null

    /**
     * Start tun2socks process.
     *
     * @param tunFd The VPN TUN file descriptor
     * @param socksHost SOCKS5 proxy host (e.g., "127.0.0.1")
     * @param socksPort SOCKS5 proxy port (e.g., 1819)
     * @param routerAddress Gateway IP for the TUN network (e.g., "10.0.0.3")
     * @return true if started successfully
     */
    fun start(
        tunFd: ParcelFileDescriptor,
        socksHost: String = "127.0.0.1",
        socksPort: Int = 1819,
        routerAddress: String = "10.0.0.3"
    ): Boolean {
        stop()

        val binaryPath = File(context.applicationInfo.nativeLibraryDir, TUN2SOCKS_BINARY)
        if (!binaryPath.exists()) {
            Log.e(TAG, "tun2socks binary not found: ${binaryPath.absolutePath}")
            // Fallback: try to use the Rust core's built-in TUN bridge
            return startFallbackTunBridge(tunFd, socksHost, socksPort)
        }

        return try {
            val cmd = listOf(
                binaryPath.absolutePath,
                "--netif-ipaddr", routerAddress,
                "--netif-netmask", "255.255.255.252",
                "--socks-server-addr", "$socksHost:$socksPort",
                "--tunmtu", VPN_MTU.toString(),
                "--sock-path", File(context.filesDir, SOCK_PATH).absolutePath,
                "--enable-udprelay",
                "--loglevel", "notice"
            )

            Log.i(TAG, "Starting tun2socks: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            process = pb.directory(context.filesDir).start()

            // Send TUN fd to tun2socks via Unix socket
            sendFd(tunFd)

            // Monitor process
            Thread {
                try {
                    val exitCode = process?.waitFor()
                    Log.i(TAG, "tun2socks exited with code: $exitCode")
                } catch (e: Exception) {
                    Log.w(TAG, "tun2socks monitor error: ${e.message}")
                }
            }.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks", e)
            false
        }
    }

    /**
     * Fallback: when tun2socks binary is missing, return false so VPN service knows.
     * A real TUN→SOCKS bridge requires either the tun2socks binary or JNI integration.
     */
    private fun startFallbackTunBridge(
        tunFd: ParcelFileDescriptor,
        socksHost: String,
        socksPort: Int
    ): Boolean {
        Log.e(TAG, "tun2socks binary not found — VPN mode requires tun2socks")
        Log.e(TAG, "Place libtun2socks.so in jniLibs/${android.os.Build.SUPPORTED_ABIS[0]}/")
        return false
    }

    private fun sendFd(tunFd: ParcelFileDescriptor) {
        val fd = tunFd.fileDescriptor
        val path = File(context.filesDir, SOCK_PATH).absolutePath
        var tries = 0
        while (tries <= 5) {
            try {
                Thread.sleep(50L shl tries)
                android.net.LocalSocket().use { localSocket ->
                    localSocket.connect(
                        android.net.LocalSocketAddress(path, android.net.LocalSocketAddress.Namespace.FILESYSTEM)
                    )
                    localSocket.setFileDescriptorsForSend(arrayOf(fd))
                    localSocket.outputStream.write(42)
                }
                Log.i(TAG, "TUN fd sent to tun2socks")
                return
            } catch (e: Exception) {
                Log.w(TAG, "sendFd attempt $tries failed: ${e.message}")
                tries++
            }
        }
        Log.e(TAG, "Failed to send TUN fd after ${tries + 1} attempts")
    }

    fun stop() {
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
        Log.i(TAG, "tun2socks stopped")
    }

    fun isRunning(): Boolean = try {
        process?.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
