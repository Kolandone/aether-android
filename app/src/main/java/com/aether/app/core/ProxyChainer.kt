package com.aether.app.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Proxy Chainer — chains multiple SOCKS5/HTTP proxies in sequence.
 *
 * Example chain:  App → Local SOCKS5 → Proxy1 → Proxy2 → Proxy3 → Internet
 */
class ProxyChainer(private val context: Context) {

    companion object {
        private const val TAG = "ProxyChainer"
        private const val DEFAULT_LISTEN_PORT = 1820
        private const val SOCKET_TIMEOUT = 15_000
    }

    data class ProxyHop(
        val host: String,
        val port: Int,
        val type: ProxyType = ProxyType.SOCKS5,
        val username: String? = null,
        val password: String? = null
    )

    enum class ProxyType { SOCKS5, HTTP }

    private var serverSocket: ServerSocket? = null
    private var listenerThread: Thread? = null
    private var isRunning = false

    val listenPort: Int
        get() = serverSocket?.localPort ?: DEFAULT_LISTEN_PORT

    fun start(chain: List<ProxyHop>, listenPort: Int = DEFAULT_LISTEN_PORT): Boolean {
        if (chain.isEmpty()) {
            Log.w(TAG, "Empty proxy chain")
            return false
        }
        stop()
        return try {
            serverSocket = ServerSocket(listenPort, 128)
            isRunning = true
            listenerThread = Thread({
                Log.i(TAG, "Listening on 127.0.0.1:$listenPort → ${chain.joinToString(" → ") { "${it.host}:${it.port}" }}")
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Thread(ChainHandler(clientSocket, chain)).start()
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }, "ProxyChain-Listener").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            false
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenerThread?.interrupt()
        listenerThread = null
    }

    private inner class ChainHandler(
        private val clientSocket: Socket,
        private val chain: List<ProxyHop>
    ) : Runnable {

        override fun run() {
            try {
                clientSocket.soTimeout = SOCKET_TIMEOUT
                val clientIn = clientSocket.inputStream
                val clientOut = clientSocket.outputStream

                // SOCKS5 greeting
                val greeting = ByteArray(2)
                clientIn.read(greeting)
                if (greeting[0] != 0x05.toByte()) return

                // Reply: no auth
                clientOut.write(byteArrayOf(0x05, 0x00))
                clientOut.flush()

                // Connect request
                val header = ByteArray(4)
                clientIn.read(header)
                val atyp = header[3].toInt() and 0xFF

                val destHost: String
                val destPort: Int

                when (atyp) {
                    1 -> { // IPv4
                        val addr = ByteArray(4)
                        clientIn.read(addr)
                        destHost = "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                        val portBuf = ByteArray(2)
                        clientIn.read(portBuf)
                        destPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)
                    }
                    3 -> { // Domain
                        val lenBuf = ByteArray(1)
                        clientIn.read(lenBuf)
                        val len = lenBuf[0].toInt() and 0xFF
                        val domain = ByteArray(len)
                        clientIn.read(domain)
                        destHost = String(domain)
                        val portBuf = ByteArray(2)
                        clientIn.read(portBuf)
                        destPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)
                    }
                    4 -> { // IPv6
                        val addr = ByteArray(16)
                        clientIn.read(addr)
                        destHost = addr.joinToString(":") { String.format("%02x%02x", it.toInt() and 0xFF, it.toInt() and 0xFF) }
                        val portBuf = ByteArray(2)
                        clientIn.read(portBuf)
                        destPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)
                    }
                    else -> return
                }

                Log.d(TAG, "Connect to $destHost:$destPort")

                // Connect through chain
                var currentSocket: Socket? = null
                for ((index, hop) in chain.withIndex()) {
                    val nextSocket = Socket()
                    nextSocket.soTimeout = SOCKET_TIMEOUT
                    nextSocket.tcpNoDelay = true
                    try {
                        nextSocket.connect(InetSocketAddress(hop.host, hop.port), SOCKET_TIMEOUT)
                        if (hop.type == ProxyType.SOCKS5) {
                            if (!socks5Handshake(nextSocket, hop.username, hop.password)) {
                                sendError(clientOut, 0x01)
                                nextSocket.close()
                                return
                            }
                            if (index == chain.lastIndex) {
                                if (!socks5Connect(nextSocket, destHost, destPort)) {
                                    sendError(clientOut, 0x05)
                                    nextSocket.close()
                                    return
                                }
                            }
                        }
                        currentSocket?.close()
                        currentSocket = nextSocket
                    } catch (e: Exception) {
                        Log.e(TAG, "Hop ${index + 1} failed: ${e.message}")
                        nextSocket.close()
                        sendError(clientOut, 0x04)
                        return
                    }
                }

                // Success
                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()

                if (currentSocket != null) bridge(clientSocket, currentSocket)
            } catch (e: Exception) {
                Log.e(TAG, "Handler error: ${e.message}")
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }

        private fun socks5Handshake(socket: Socket, username: String?, password: String?): Boolean {
            val out = socket.outputStream
            val inp = socket.inputStream

            if (username != null && password != null) {
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                out.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            out.flush()

            val resp = ByteArray(2)
            inp.read(resp)
            if (resp[0] != 0x05.toByte()) return false

            if (resp[1] == 0x02.toByte() && username != null && password != null) {
                val auth = byteArrayOf(0x01) +
                    byteArrayOf(username.length.toByte()) + username.toByteArray() +
                    byteArrayOf(password.length.toByte()) + password.toByteArray()
                out.write(auth)
                out.flush()
                val authResp = ByteArray(2)
                inp.read(authResp)
                return authResp[1] == 0x00.toByte()
            }
            return resp[1] == 0x00.toByte()
        }

        private fun socks5Connect(socket: Socket, host: String, port: Int): Boolean {
            val out = socket.outputStream
            val inp = socket.inputStream
            val hostBytes = host.toByteArray()
            val req = byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                hostBytes + byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
            out.write(req)
            out.flush()
            val resp = ByteArray(10)
            inp.read(resp)
            return resp[1] == 0x00.toByte()
        }

        private fun sendError(out: OutputStream, code: Int) {
            out.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
        }

        private fun bridge(client: Socket, remote: Socket) {
            try {
                val t1 = Thread({
                    try { copy(client.inputStream, remote.outputStream) } catch (_: Exception) {}
                    try { remote.shutdownOutput() } catch (_: Exception) {}
                }, "Up")
                val t2 = Thread({
                    try { copy(remote.inputStream, client.outputStream) } catch (_: Exception) {}
                    try { client.shutdownOutput() } catch (_: Exception) {}
                }, "Down")
                t1.start(); t2.start()
                t1.join(); t2.join()
            } catch (_: Exception) {}
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        }
    }
}
