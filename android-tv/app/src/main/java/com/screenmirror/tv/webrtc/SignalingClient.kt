package com.screenmirror.tv.webrtc

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Callbacks delivered on the OkHttp dispatcher thread. */
interface SignalingListener {
    fun onConnected()
    fun onOffer(sdp: String)
    fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    fun onPeerDisconnected()
    fun onError(message: String)
}

/**
 * WebSocket client that connects to the Windows signaling server and
 * exchanges WebRTC signaling messages for the "receiver" role.
 *
 * Protocol (all messages are JSON):
 *   → { type:"join",  pin:"1234", role:"receiver", name:"Android TV" }
 *   ← { type:"offer", sdp:"..." }
 *   → { type:"answer", sdp:"..." }
 *   ↔ { type:"ice-candidate", candidate, sdpMid, sdpMLineIndex }
 */
class SignalingClient(
    private val serverIp:   String,
    private val serverPort: Int = 8765,
    private val pin:        String,
    private val listener:   SignalingListener,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS)  // keep-alive
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val url     = "ws://$serverIp:$serverPort"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                // Join the room as receiver
                ws.send(
                    JSONObject().apply {
                        put("type", "join")
                        put("pin",  pin)
                        put("role", "receiver")
                        put("name", "Android TV")
                    }.toString()
                )
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.getString("type")) {
                        "offer" -> {
                            listener.onOffer(msg.getString("sdp"))
                        }
                        "ice-candidate" -> {
                            listener.onIceCandidate(
                                candidate     = msg.getString("candidate"),
                                sdpMid        = msg.optString("sdpMid"),
                                sdpMLineIndex = msg.optInt("sdpMLineIndex", 0),
                            )
                        }
                        "peer-disconnected" -> {
                            listener.onPeerDisconnected()
                        }
                        // "joined" — server ACK, we don't need to act on it
                    }
                } catch (e: Exception) {
                    listener.onError("Parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "WebSocket failure")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }
        })
    }

    fun sendAnswer(sdp: String) {
        webSocket?.send(
            JSONObject().apply {
                put("type", "answer")
                put("sdp",  sdp)
            }.toString()
        )
    }

    fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        webSocket?.send(
            JSONObject().apply {
                put("type",          "ice-candidate")
                put("candidate",     candidate)
                put("sdpMid",        sdpMid ?: "")
                put("sdpMLineIndex", sdpMLineIndex)
            }.toString()
        )
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}
