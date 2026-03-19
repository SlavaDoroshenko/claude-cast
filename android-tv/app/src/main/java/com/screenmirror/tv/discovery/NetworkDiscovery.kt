package com.screenmirror.tv.discovery

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Info returned by each responding Windows client. */
data class DiscoveredDevice(
    val name: String,
    val ip:   String,
    val port: Int,
)

/**
 * Sends a UDP broadcast every [intervalMs] ms and collects responses
 * from Windows clients.  Call [start] to begin, [stop] to clean up.
 *
 * The Windows side listens on port 8766 and replies with JSON:
 *   {"name":"PC-Name","ip":"192.168.1.5","port":8765}
 */
class NetworkDiscovery(
    private val discoveryPort: Int   = 8766,
    private val intervalMs:    Long  = 2_000L,
    private val timeoutMs:     Int   = 1_500,
    private val onDeviceFound: (DiscoveredDevice) -> Unit,
) {
    private val PROBE = "SCREEN_MIRROR_DISCOVER".toByteArray()

    private var job:    Job?             = null
    private var socket: DatagramSocket?  = null

    /** Start broadcasting in the background. */
    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket().also { it.broadcast = true }
                while (isActive) {
                    broadcast()
                    delay(intervalMs)
                }
            } catch (e: Exception) {
                if (isActive) e.printStackTrace()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }

    private fun broadcast() {
        val sock = socket ?: return
        val seen = mutableSetOf<String>()

        // Send probe to broadcast address
        val broadcastAddr = InetAddress.getByName("255.255.255.255")
        val probe = DatagramPacket(PROBE, PROBE.size, broadcastAddr, discoveryPort)
        sock.send(probe)

        // Receive all replies within timeoutMs
        sock.soTimeout = timeoutMs
        val buf = ByteArray(512)
        val reply = DatagramPacket(buf, buf.size)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                sock.receive(reply)
                val text = String(reply.data, 0, reply.length).trim()
                val json = JSONObject(text)
                val ip   = json.optString("ip", reply.address.hostAddress ?: "")
                if (ip.isNotEmpty() && seen.add(ip)) {
                    onDeviceFound(
                        DiscoveredDevice(
                            name = json.optString("name", "Unknown PC"),
                            ip   = ip,
                            port = json.optInt("port", 8765),
                        )
                    )
                }
            } catch (_: java.net.SocketTimeoutException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
