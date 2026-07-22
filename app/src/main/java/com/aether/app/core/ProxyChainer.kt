package com.aether.app.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Proxy Chainer — chains multiple SOCKS5/HTTP proxies in sequence.
 *
 * Example chain:  App → Local SOCKS5 → Proxy1 → Proxy2 → Proxy3 → Internet
 *
 * Each hop in the chain connects to the next proxy, and the last proxy
 * connects to the actual destination.
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

    /**
     * Start the chained proxy listener.
     *
     * @param chain The ordered list of proxies to chain through
     * @param listenPort Port to listen on locally (default: 1820)
     */
    fun start(chain: List<ProxyHop>, listenPort: Int = DEFAULT_LISTEN_PORT): Boolean {
        if (chain.isEmpty()) {
            Log.w(TAG, "Empty proxy chain, nothing to do")
            return false
        }

        stop()

        return try {
            serverSocket = ServerSocket(listenPort, 128)
            isRunning = true

            listenerThread = Thread({
                Log.i(TAG, "Proxy chainer listening on 127.0.0.1:$listenPort")
                Log.i(TAG, "Chain: ${chain.joinToString(" → ") { "${it.host}:${it.port}" }}")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Thread(ChainHandler(clientSocket, chain)).start()
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }, "ProxyChain-Listener").apply {
                isDaemon = true
                start()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy chainer", e)
            false
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenerThread?.interrupt()
        listenerThread = null
        Log.i(TAG, "Proxy chainer stopped")
    }

    /**
     * Handle a single client connection through the proxy chain.
     */
    private inner class ChainHandler(
        private val clientSocket: Socket,
        private val chain: List<ProxyHop>
    ) : Runnable {

        override fun run() {
            try {
                clientSocket.soTimeout = SOCKET_TIMEOUT
                val clientInput = BufferedReader(InputStreamReader(clientSocket.inputStream))
                val clientOutput = OutputStreamWriter(clientSocket.outputStream)

                // Read the initial SOCKS5 handshake from the client
                val greeting = readSocks5Greeting(clientInput) ?: return
                val socksVersion = greeting[0].toInt() and 0xFF
                val nMethods = greeting[1].toInt() and 0xFF

                if (socksVersion != 5) {
                    Log.w(TAG, "Unsupported SOCKS version: $socksVersion")
                    return
                }

                // Reply: no auth required
                clientOutput.write(byteArrayOf(0x05, 0x00))
                clientOutput.flush()

                // Read connect request
                val connectRequest = readSocks5ConnectRequest(clientInput) ?: return
                val destHost = extractDestinationHost(connectRequest)
                val destPort = extractDestinationPort(connectRequest)

                if (destHost.isEmpty() || destPort <= 0) {
                    Log.w(TAG, "Invalid destination: $destHost:$destPort")
                    sendSocks5Error(clientOutput, 0x04) // Host unreachable
                    return
                }

                Log.d(TAG, "Client wants to connect to $destHost:$destPort")

                // Connect through the chain
                var currentSocket: Socket? = null

                for ((index, hop) in chain.withIndex()) {
                    val nextSocket = Socket()
                    nextSocket.soTimeout = SOCKET_TIMEOUT
                    nextSocket.tcpNoDelay = true

                    try {
                        nextSocket.connect(InetSocketAddress(hop.host, hop.port), SOCKET_TIMEOUT)

                        // Perform SOCKS5 handshake with this hop
                        if (hop.type == ProxyType.SOCKS5) {
                            if (!socks5Handshake(nextSocket, hop.username, hop.password)) {
                                Log.e(TAG, "SOCKS5 handshake failed at hop ${index + 1}")
                                sendSocks5Error(clientOutput, 0x01) // General failure
                                nextSocket.close()
                                return
                            }

                            // If this is the last hop, connect to the actual destination
                            if (index == chain.lastIndex) {
                                if (!socks5Connect(nextSocket, destHost, destPort)) {
                                    Log.e(TAG, "SOCKS5 connect failed at final hop")
                                    sendSocks5Error(clientOutput, 0x05) // Connection refused
                                    nextSocket.close()
                                    return
                                }
                            }
                        } else if (hop.type == ProxyType.HTTP) {
                            if (!httpConnect(nextSocket, destHost, destPort)) {
                                Log.e(TAG, "HTTP CONNECT failed at hop ${index + 1}")
                                sendSocks5Error(clientOutput, 0x01)
                                nextSocket.close()
                                return
                            }
                        }

                        // Close previous hop if exists
                        currentSocket?.close()
                        currentSocket = nextSocket

                    } catch (e: Exception) {
                        Log.e(TAG, "Hop ${index + 1} failed: ${e.message}")
                        nextSocket.close()
                        sendSocks5Error(clientOutput, 0x04) // Host unreachable
                        return
                    }
                }

                // Send success response to client
                sendSocks5Success(clientOutput)

                // Bridge traffic
                if (currentSocket != null) {
                    bridge(clientSocket, currentSocket)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Chain handler error: ${e.message}")
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }

        private fun readSocks5Greeting(reader: BufferedReader): ByteArray? {
            val version = reader.read()
            if (version != 5) return null
            val nMethods = reader.read()
            if (nMethods < 0) return null
            val methods = ByteArray(nMethods)
            reader.read(methods)
            return byteArrayOf(version.toByte(), nMethods.toByte()) + methods
        }

        private fun readSocks5ConnectRequest(reader: BufferedReader): ByteArray? {
            val buf = ByteArray(256)
            var totalRead = 0
            // Read at least 4 bytes (VER, CMD, RSV, ATYP)
            while (totalRead < 4) {
                val n = reader.read(buf, totalRead, buf.size - totalRead)
                if (n < 0) return null
                totalRead += n
            }
            // Read address based on ATYP
            val atyp = buf[3].toInt() and 0xFF
            val addrLen = when (atyp) {
                1 -> 4 // IPv4
                3 -> buf[4].toInt() and 0xFF + 1 // Domain (length-prefixed)
                4 -> 16 // IPv6
                else -> return null
            }
            while (totalRead < 4 + addrLen + 2) { // +2 for PORT
                val n = reader.read(buf, totalRead, buf.size - totalRead)
                if (n < 0) return null
                totalRead += n
            }
            return buf.copyOf(totalRead)
        }

        private fun extractDestinationHost(request: ByteArray): String {
            val atyp = request[3].toInt() and 0xFF
            return when (atyp) {
                1 -> "${request[4].toInt() and 0xFF}.${request[5].toInt() and 0xFF}.${request[6].toInt() and 0xFF}.${request[7].toInt() and 0xFF}"
                3 -> String(request, 5, request[4].toInt() and 0xFF)
                4 -> {
                    val sb = StringBuilder()
                    for (i in 0 until 16) {
                        if (i > 0) sb.append(":")
                        sb.append(String.format("%02x%02x", request[4 + i].toInt() and 0xFF, request[5 + i].toInt() and 0xFF))
                    }
                    sb.toString()
                }
                else -> ""
            }
        }

        private fun extractDestinationPort(request: ByteArray): Int {
            val atyp = request[3].toInt() and 0xFF
            val offset = when (atyp) {
                1 -> 8
                3 -> 5 + (request[4].toInt() and 0xFF)
                4 -> 20
                else -> return 0
            }
            return ((request[offset].toInt() and 0xFF) shl 8) or (request[offset + 1].toInt() and 0xFF)
        }

        private fun socks5Handshake(socket: Socket, username: String?, password: String?): Boolean {
            val output = OutputStreamWriter(socket.outputStream)
            val input = BufferedReader(InputStreamReader(socket.inputStream))

            // Send greeting with auth methods
            if (username != null && password != null) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) // No auth + Username/Password
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00)) // No auth only
            }
            output.flush()

            // Read server choice
            val response = ByteArray(2)
            input.read(response)
            if (response[0] != 0x05.toByte()) return false

            // Handle auth if required
            if (response[1] == 0x02.toByte() && username != null && password != null) {
                val authResponse = ByteArray(2)
                // Version + username length + username + password length + password
                val authPacket = byteArrayOf(0x01) +
                    byteArrayOf(username.length.toByte()) + username.toByteArray() +
                    byteArrayOf(password.length.toByte()) + password.toByteArray()
                output.write(authPacket)
                output.flush()
                input.read(authResponse)
                if (authResponse[1] != 0x00.toByte()) return false
            } else if (response[1] != 0x00.toByte()) {
                return false
            }

            return true
        }

        private fun socks5Connect(socket: Socket, host: String, port: Int): Boolean {
            val output = OutputStreamWriter(socket.outputStream)
            val input = BufferedReader(InputStreamReader(socket.inputStream))

            // Build CONNECT request
            val hostBytes = host.toByteArray()
            val connectRequest = byteArrayOf(
                0x05, 0x01, 0x00, 0x03, // VER, CMD(Connect), RSV, ATYP(Domain)
                hostBytes.length.toByte()
            ) + hostBytes +
                byteArrayOf(
                    ((port shr 8) and 0xFF).toByte(),
                    (port and 0xFF).toByte()
                )

            output.write(connectRequest)
            output.flush()

            // Read response
            val response = ByteArray(10)
            input.read(response)

            return response[1] == 0x00.toByte() // Success
        }

        private fun httpConnect(socket: Socket, host: String, port: Int): Boolean {
            val output = OutputStreamWriter(socket.outputStream)
            val input = BufferedReader(InputStreamReader(socket.inputStream))

            output.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
            output.flush()

            val response = input.readLine() ?: return false
            return response.contains("200")
        }

        private fun sendSocks5Success(output: OutputStreamWriter) {
            output.write(byteArrayOf(
                0x05, 0x00, 0x00, 0x01, // VER, REP(Success), RSV, ATYP(IPv4)
                0, 0, 0, 0, 0, 0       // Bound address + port
            ))
            output.flush()
        }

        private fun sendSocks5Error(output: OutputStreamWriter, errorCode: Int) {
            output.write(byteArrayOf(
                0x05, errorCode.toByte(), 0x00, 0x01,
                0, 0, 0, 0, 0, 0
            ))
            output.flush()
        }

        private fun bridge(client: Socket, remote: Socket) {
            try {
                val clientIn = client.inputStream
                val clientOut = client.outputStream
                val remoteIn = remote.inputStream
                val remoteOut = remote.outputStream

                val t1 = Thread({
                    try {
                        val buf = ByteArray(8192)
                        var n: Int
                        while (clientIn.read(buf).also { n = it } != -1) {
                            remoteOut.write(buf, 0, n)
                            remoteOut.flush()
                        }
                    } catch (_: Exception) {}
                    try { remote.shutdownOutput() } catch (_: Exception) {}
                }, "Chain-Up")

                val t2 = Thread({
                    try {
                        val buf = ByteArray(8192)
                        var n: Int
                        while (remoteIn.read(buf).also { n = it } != -1) {
                            clientOut.write(buf, 0, n)
                            clientOut.flush()
                        }
                    } catch (_: Exception) {}
                    try { client.shutdownOutput() } catch (_: Exception) {}
                }, "Chain-Down")

                t1.start()
                t2.start()
                t1.join()
                t2.join()
            } catch (e: Exception) {
                Log.e(TAG, "Bridge error: ${e.message}")
            }
        }
    }
}
