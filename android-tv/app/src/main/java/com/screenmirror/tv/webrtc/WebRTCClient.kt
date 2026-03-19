package com.screenmirror.tv.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

private const val TAG = "WebRTCClient"

/** Callbacks for the caller (MirrorActivity). All called on WebRTC internal threads. */
interface WebRTCListener {
    fun onIceCandidate(candidate: IceCandidate)
    fun onVideoTrackReceived(track: VideoTrack)
    fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    fun onError(message: String)
}

/**
 * Wraps the WebRTC Android SDK for the *receiver* side.
 *
 * Flow:
 *  1. [init] — initialise PeerConnectionFactory
 *  2. [createPeerConnection] — create RTCPeerConnection
 *  3. [setRemoteOffer] — apply the SDP offer from the PC
 *  4. [createAnswer] — generate + set local answer, callback provides SDP string
 *  5. [addIceCandidate] — add candidates as they arrive
 *  6. [attachRenderer] — display video on a SurfaceViewRenderer
 *  7. [dispose] — tear everything down
 */
class WebRTCClient(
    private val context:  Context,
    private val listener: WebRTCListener,
) {
    private val rootEglBase = EglBase.create()

    private var factory:        PeerConnectionFactory? = null
    private var peerConnection: PeerConnection?        = null
    private var remoteVideoTrack: VideoTrack?          = null

    // Buffer ICE candidates that arrive before remote description is set
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescSet = false

    // ── Init ─────────────────────────────────────────────────────────────────

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
            )
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()
    }

    // ── PeerConnection ────────────────────────────────────────────────────────

    fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(
            listOf() // LAN only — no STUN/TURN needed
        ).also {
            it.sdpSemantics  = PeerConnection.SdpSemantics.UNIFIED_PLAN
            it.bundlePolicy  = PeerConnection.BundlePolicy.MAXBUNDLE
            it.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(60)}")
                listener.onIceCandidate(candidate)
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Video track received")
                    remoteVideoTrack = track
                    listener.onVideoTrackReceived(track)
                }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state: $state")
                listener.onConnectionStateChanged(state)
            }

            // ── Required overrides (no-op for receiver) ──────────────────────
            override fun onSignalingChange(s: PeerConnection.SignalingState?)         = Unit
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(b: Boolean)                   = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?)   = Unit
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>?)         = Unit
            override fun onAddStream(stream: MediaStream?)                            = Unit
            override fun onRemoveStream(stream: MediaStream?)                         = Unit
            override fun onDataChannel(dc: DataChannel?)                              = Unit
            override fun onRenegotiationNeeded()                                      = Unit
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }) ?: run {
            listener.onError("Failed to create PeerConnection")
        }
    }

    // ── SDP Exchange ─────────────────────────────────────────────────────────

    fun setRemoteOffer(sdp: String, onSuccess: () -> Unit) {
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote offer set")
                remoteDescSet = true
                // Flush buffered candidates
                synchronized(pendingCandidates) {
                    pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingCandidates.clear()
                }
                onSuccess()
            }
            override fun onSetFailure(err: String?) = listener.onError("setRemoteDesc failed: $err")
            override fun onCreateSuccess(p0: SessionDescription?)  = Unit
            override fun onCreateFailure(p0: String?)              = Unit
        }, desc)
    }

    fun createAnswer(onAnswer: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                // Set local description
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer set")
                        onAnswer(sdp.description)
                    }
                    override fun onSetFailure(err: String?) = listener.onError("setLocalDesc failed: $err")
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?)             = Unit
                }, sdp)
            }
            override fun onCreateFailure(err: String?) = listener.onError("createAnswer failed: $err")
            override fun onSetSuccess()                = Unit
            override fun onSetFailure(p0: String?)     = Unit
        }, constraints)
    }

    // ── ICE Candidates ────────────────────────────────────────────────────────

    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val ic = IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate)
        if (remoteDescSet) {
            peerConnection?.addIceCandidate(ic)
        } else {
            synchronized(pendingCandidates) { pendingCandidates.add(ic) }
        }
    }

    // ── Renderer ─────────────────────────────────────────────────────────────

    /**
     * Attach the remote video track to a [SurfaceViewRenderer].
     * Must be called after [onVideoTrackReceived].
     */
    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(rootEglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setMirror(false)
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(renderer)
        renderer.release()
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    fun dispose() {
        remoteVideoTrack = null
        peerConnection?.dispose()
        peerConnection = null
        factory?.dispose()
        factory = null
        rootEglBase.release()
        pendingCandidates.clear()
        remoteDescSet = false
    }
}
