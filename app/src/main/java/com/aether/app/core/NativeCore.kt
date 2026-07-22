package com.aether.app.core

import org.json.JSONObject

/**
 * JNI bridge to the Aether Rust core (libaether.so).
 *
 * This provides direct access to the Rust FFI functions, which is more efficient
 * than running the binary as a separate process via ProcessBuilder.
 *
 * NOTE: For JNI to work, libaether.so must be compiled as a cdylib (shared library)
 * with the FFI functions exported. If the binary is a standalone executable,
 * use AetherManager (ProcessBuilder) instead.
 */
object NativeCore {
    init {
        try {
            System.loadLibrary("aether")
            System.loadLibrary("aether_jni")
        } catch (e: UnsatisfiedLinkError) {
            // JNI libraries not available — fall back to ProcessBuilder mode
            android.util.Log.w("NativeCore", "JNI libs not loaded, falling back to process mode: ${e.message}")
        }
    }

    data class TunnelAddresses(val ipv4: String, val ipv6: String)

    /**
     * Check if JNI native libraries are available.
     */
    fun isAvailable(): Boolean {
        return try {
            nativeIsRunning() // Any JNI call will fail if libs aren't loaded
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Prepare identity (provision WARP account, get tunnel addresses).
     * Must be called before start() to set up the VPN interface.
     */
    fun prepare(config: String): TunnelAddresses {
        check(nativePrepare(config) == 0) { nativeLastError() }
        val result = JSONObject(nativeLastResult())
        return TunnelAddresses(
            result.getString("ipv4"),
            result.getString("ipv6"),
        )
    }

    /**
     * Start Aether in VPN mode (with TUN fd).
     * Blocks until the tunnel is stopped.
     */
    fun start(config: String, tunFd: Int): Int = nativeStart(config, tunFd)

    /**
     * Start Aether in SOCKS5 proxy mode (no TUN).
     * Blocks until the tunnel is stopped.
     */
    fun startProxy(config: String): Int = nativeStartProxy(config)

    /**
     * Request shutdown of the active tunnel.
     */
    fun stop(): Int = nativeStop()

    fun isRunning(): Boolean = nativeIsRunning()
    fun isReady(): Boolean = nativeIsReady()
    fun lastError(): String = nativeLastError()
    fun lastLog(): String = nativeLastLog()

    /**
     * Attach a VpnService instance for socket protection callbacks.
     * This allows the Rust core to call VpnService.protect() on sockets
     * so that tunnel traffic doesn't loop back through the VPN.
     */
    fun attach(service: com.aether.app.service.AetherVpnService) {
        nativeAttach(service)
    }

    /**
     * Detach the VpnService instance.
     */
    fun detach() {
        nativeDetach()
    }

    // JNI native declarations
    @JvmStatic private external fun nativePrepare(config: String): Int
    @JvmStatic private external fun nativeLastResult(): String
    @JvmStatic private external fun nativeStart(config: String, tunFd: Int): Int
    @JvmStatic private external fun nativeStartProxy(config: String): Int
    @JvmStatic private external fun nativeStop(): Int
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeIsReady(): Boolean
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeLastLog(): String
    @JvmStatic private external fun nativeAttach(service: com.aether.app.service.AetherVpnService)
    @JvmStatic private external fun nativeDetach()
}
