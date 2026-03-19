package com.screenmirror.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.screenmirror.tv.databinding.ActivityMirrorBinding
import com.screenmirror.tv.webrtc.SignalingClient
import com.screenmirror.tv.webrtc.SignalingListener
import com.screenmirror.tv.webrtc.WebRTCClient
import com.screenmirror.tv.webrtc.WebRTCListener
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

class MirrorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IP   = "server_ip"
        const val EXTRA_PORT = "server_port"
        const val EXTRA_PIN  = "pin"
    }

    private lateinit var binding: ActivityMirrorBinding

    private var webRTC:    WebRTCClient?    = null
    private var signaling: SignalingClient? = null
    private var videoAttached = false

    private val mainHandler       = Handler(Looper.getMainLooper())
    private val hideControlsDelay = 3_000L
    private val hideControlsRunnable = Runnable { hideControls() }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMirrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while mirroring
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ip   = intent.getStringExtra(EXTRA_IP)   ?: run { finish(); return }
        val port = intent.getIntExtra(EXTRA_PORT, 8765)
        val pin  = intent.getStringExtra(EXTRA_PIN)  ?: run { finish(); return }

        binding.btnDisconnect.setOnClickListener { showDisconnectDialog() }

        setStatus("Connecting to $ip…")
        startMirroring(ip, port, pin)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideControlsRunnable)
        cleanup()
    }

    // ── WebRTC + Signaling setup ─────────────────────────────────────────────

    private fun startMirroring(ip: String, port: Int, pin: String) {
        webRTC = WebRTCClient(this, object : WebRTCListener {

            override fun onIceCandidate(candidate: IceCandidate) {
                signaling?.sendIceCandidate(
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex
                )
            }

            override fun onVideoTrackReceived(track: VideoTrack) {
                runOnUiThread {
                    if (!videoAttached) {
                        videoAttached = true
                        webRTC?.attachRenderer(binding.svRenderer)
                        binding.svRenderer.visibility = View.VISIBLE
                        binding.layoutStatus.visibility = View.GONE
                    }
                }
            }

            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            // Не показываем overlay если видео уже идёт
                            if (!videoAttached) setStatus("Connected")
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED ->
                            setStatus("Connection lost…")
                        PeerConnection.PeerConnectionState.FAILED -> {
                            setStatus("Connection failed")
                            showReconnectHint()
                        }
                        else -> Unit
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread { setStatus("Error: $message") }
            }
        })

        webRTC!!.init()
        webRTC!!.createPeerConnection()

        // ── Signaling ────────────────────────────────────────────────────────

        signaling = SignalingClient(
            serverIp   = ip,
            serverPort = port,
            pin        = pin,
            listener   = object : SignalingListener {

                override fun onConnected() {
                    runOnUiThread { setStatus("Waiting for stream…") }
                }

                override fun onOffer(sdp: String) {
                    webRTC?.setRemoteOffer(sdp) {
                        webRTC?.createAnswer { answerSdp ->
                            signaling?.sendAnswer(answerSdp)
                        }
                    }
                }

                override fun onIceCandidate(
                    candidate: String, sdpMid: String?, sdpMLineIndex: Int
                ) {
                    webRTC?.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onPeerDisconnected() {
                    runOnUiThread {
                        setStatus("PC disconnected")
                        showReconnectHint()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread { setStatus("Signaling error: $message") }
                }
            }
        )

        signaling!!.connect()
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun setStatus(text: String) {
        binding.tvStatus.text       = text
        binding.layoutStatus.visibility = View.VISIBLE
    }

    private fun showReconnectHint() {
        setStatus("Press BACK to exit or wait for reconnection")
    }

    private fun showControls() {
        binding.layoutControls.visibility = View.VISIBLE
        binding.layoutControls.alpha      = 1f
        scheduleHideControls()
    }

    private fun hideControls() {
        binding.layoutControls.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction { binding.layoutControls.visibility = View.INVISIBLE }
            .start()
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, hideControlsDelay)
    }

    // ── Disconnect dialog ────────────────────────────────────────────────────

    private fun showDisconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_disconnect_title)
            .setMessage(R.string.dialog_disconnect_msg)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    // ── Input ────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                showDisconnectDialog()
                true
            }
            // Any D-pad / media key shows the overlay controls
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                showControls()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    private fun cleanup() {
        signaling?.disconnect()
        signaling = null

        if (videoAttached) {
            webRTC?.detachRenderer(binding.svRenderer)
            videoAttached = false
        }
        webRTC?.dispose()
        webRTC = null
    }
}
