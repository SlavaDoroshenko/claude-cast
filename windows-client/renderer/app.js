'use strict';

// ─── State ────────────────────────────────────────────────────────────────────

let localStream       = null;   // MediaStream from screen capture
let peerConnection    = null;   // RTCPeerConnection
let signalingSocket   = null;   // WebSocket to local signaling server
let currentPin        = null;   // 4-digit PIN string
let isStreaming       = false;  // screen capture active
let receiverConnected = false;  // TV is in the signaling room
let receiverName      = '';
let iceCandidateQueue = [];     // buffer candidates until remote desc is set

let statsInterval  = null;
let reconnectTimer = null;
let lastBytesSent  = 0;
let lastStatsTime  = 0;
let savedVolume    = null;  // volume level saved before muting

const ICE_CONFIG = {
  iceServers:          [],   // LAN-only — no STUN required
  iceCandidatePoolSize: 10,
};

// ─── DOM ──────────────────────────────────────────────────────────────────────

const $ = id => document.getElementById(id);

const UI = {
  localIP:       $('local-ip'),
  pinCode:       $('pin-code'),
  statusDot:     $('status-dot'),
  statusText:    $('status-text'),
  startBtn:      $('start-btn'),
  stopBtn:       $('stop-btn'),
  sourceSelect:  $('source-select'),
  qualitySelect: $('quality-select'),
  fpsSelect:     $('fps-select'),
  deviceName:    $('connected-device'),
  preview:       $('source-preview'),
  previewEmpty:  $('preview-empty'),
  statsCard:     $('stats-card'),
  statRes:       $('stat-resolution'),
  statFps:       $('stat-fps'),
  statConn:      $('stat-connection'),
  statBitrate:   $('stat-bitrate'),
  toast:         $('toast'),
};

// ─── Init ─────────────────────────────────────────────────────────────────────

async function init() {
  try {
    const [ip, pin] = await Promise.all([
      window.electronAPI.getLocalIP(),
      window.electronAPI.getPin(),
    ]);

    currentPin = pin;
    UI.localIP.textContent  = ip;
    UI.pinCode.textContent  = pin;

    await loadSources();
    connectSignaling();
    setStatus('waiting');
  } catch (err) {
    console.error('[Init]', err);
    setStatus('error', 'Startup failed');
    toast('Startup error: ' + err.message);
  }
}

// ─── Screen Sources ───────────────────────────────────────────────────────────

async function loadSources() {
  const sources = await window.electronAPI.getSources();

  UI.sourceSelect.innerHTML = sources.length
    ? ''
    : '<option value="">No screens found</option>';

  sources.forEach((src, i) => {
    const opt = document.createElement('option');
    opt.value       = src.id;
    opt.textContent = src.name;
    if (i === 0) opt.selected = true;
    UI.sourceSelect.appendChild(opt);
  });

  if (sources.length > 0) showPreview(sources[0].thumbnail);
}

function showPreview(dataUrl) {
  if (!dataUrl || dataUrl === 'data:,') return;
  UI.preview.src              = dataUrl;
  UI.preview.style.display    = 'block';
  UI.previewEmpty.style.display = 'none';
}

// Refresh preview when source changes
async function onSourceChange() {
  const sources = await window.electronAPI.getSources();
  const sel = sources.find(s => s.id === UI.sourceSelect.value);
  if (sel) showPreview(sel.thumbnail);
}

// ─── Signaling ────────────────────────────────────────────────────────────────

function connectSignaling() {
  // Clean up any existing socket
  if (signalingSocket) {
    signalingSocket.onclose = null;
    signalingSocket.close();
    signalingSocket = null;
  }

  signalingSocket = new WebSocket('ws://localhost:8765');

  signalingSocket.onopen = () => {
    console.log('[WS] Connected to signaling server');
    clearTimeout(reconnectTimer);
    sendMsg({ type: 'join', pin: currentPin, role: 'sender', name: 'ScreenMirror PC' });
  };

  signalingSocket.onmessage = async ({ data }) => {
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    await onSignalingMessage(msg);
  };

  signalingSocket.onclose = () => {
    console.log('[WS] Disconnected — retrying in 2 s');
    reconnectTimer = setTimeout(connectSignaling, 2000);
  };

  signalingSocket.onerror = () => {}; // onclose always fires after onerror
}

async function onSignalingMessage(msg) {
  switch (msg.type) {

    case 'receiver-joined':
      receiverConnected = true;
      receiverName = msg.receiverName || 'Android TV';
      UI.deviceName.textContent = receiverName;
      setStatus('connected');
      toast(`${receiverName} connected`);
      // If already streaming, kick off the WebRTC handshake immediately
      if (isStreaming) await createOffer();
      break;

    case 'answer':
      if (!peerConnection) break;
      await peerConnection.setRemoteDescription(
        new RTCSessionDescription({ type: 'answer', sdp: msg.sdp })
      );
      console.log('[WebRTC] Remote description set');
      // Flush ICE candidates that arrived before the answer
      for (const c of iceCandidateQueue) {
        await addCandidate(c);
      }
      iceCandidateQueue = [];
      break;

    case 'ice-candidate': {
      const candidate = {
        candidate:     msg.candidate,
        sdpMid:        msg.sdpMid,
        sdpMLineIndex: msg.sdpMLineIndex,
      };
      if (peerConnection?.remoteDescription) {
        await addCandidate(candidate);
      } else if (peerConnection) {
        iceCandidateQueue.push(candidate);
      }
      break;
    }

    case 'peer-disconnected':
      onReceiverDisconnect();
      break;
  }
}

async function addCandidate(raw) {
  try {
    await peerConnection.addIceCandidate(new RTCIceCandidate(raw));
  } catch (e) {
    console.warn('[ICE] addIceCandidate failed:', e.message);
  }
}

// ─── Streaming: Start / Stop ──────────────────────────────────────────────────

async function startStreaming() {
  if (isStreaming) return;

  const sourceId = UI.sourceSelect.value;
  if (!sourceId) { toast('Select a screen source first'); return; }

  try {
    setStatus('connecting', 'Starting capture…');
    // Mute laptop speakers — audio will play on TV only
    try {
      savedVolume = await window.electronAPI.getVolume();
      await window.electronAPI.setVolume(0);
    } catch { savedVolume = null; }

    localStream = await captureScreen(sourceId);
    isStreaming = true;
    UI.startBtn.classList.add('hidden');
    UI.stopBtn.classList.remove('hidden');
    setStatus('streaming', 'Waiting for TV…');
    toast('Screen capture started');

    // If TV is already waiting, begin the handshake right away
    if (receiverConnected) await createOffer();
  } catch (err) {
    console.error('[Stream]', err);
    toast('Capture failed: ' + err.message);
    setStatus(receiverConnected ? 'connected' : 'waiting');
    isStreaming = false;
  }
}

function stopStreaming() {
  if (!isStreaming) return;
  isStreaming = false;

  // Restore laptop volume
  if (savedVolume !== null) {
    window.electronAPI.setVolume(savedVolume).catch(() => {});
    savedVolume = null;
  }

  if (localStream) {
    localStream.getTracks().forEach(t => t.stop());
    localStream = null;
  }

  closePeerConnection();
  UI.stopBtn.classList.add('hidden');
  UI.startBtn.classList.remove('hidden');
  UI.statsCard.classList.add('hidden');
  clearInterval(statsInterval);

  setStatus(receiverConnected ? 'connected' : 'waiting');
  toast('Streaming stopped');
}

// ─── Screen Capture ───────────────────────────────────────────────────────────

async function captureScreen(sourceId) {
  const quality = UI.qualitySelect.value;
  const fps     = parseInt(UI.fpsSelect.value, 10);

  const maxRes = {
    '720p':  { maxWidth: 1280, maxHeight: 720  },
    '1080p': { maxWidth: 1920, maxHeight: 1080 },
    'source':{ maxWidth: 7680, maxHeight: 4320 },
  }[quality] ?? { maxWidth: 1920, maxHeight: 1080 };

  const videoConstraints = {
    mandatory: {
      chromeMediaSource:   'desktop',
      chromeMediaSourceId: sourceId,
      maxWidth:   maxRes.maxWidth,
      maxHeight:  maxRes.maxHeight,
      minFrameRate: Math.min(fps, 15),
      maxFrameRate: fps,
    },
  };

  // Try with system audio; fall back to video-only if the OS doesn't support loopback
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { mandatory: { chromeMediaSource: 'desktop' } },
      video: videoConstraints,
    });
    if (stream.getAudioTracks().length === 0) toast('No system audio detected');
    return stream;
  } catch {
    toast('System audio unavailable — video only');
    return navigator.mediaDevices.getUserMedia({ audio: false, video: videoConstraints });
  }
}

// ─── WebRTC ───────────────────────────────────────────────────────────────────

async function createOffer() {
  closePeerConnection();
  iceCandidateQueue = [];

  peerConnection = new RTCPeerConnection(ICE_CONFIG);

  peerConnection.onicecandidate = ({ candidate }) => {
    if (candidate) {
      sendMsg({
        type:          'ice-candidate',
        candidate:     candidate.candidate,
        sdpMid:        candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex,
      });
    }
  };

  peerConnection.onconnectionstatechange = () => {
    const state = peerConnection?.connectionState;
    console.log('[WebRTC] Connection state:', state);

    if (state === 'connected') {
      setStatus('streaming', `Streaming to ${receiverName}`);
      startStatsPolling();
    } else if (state === 'disconnected' || state === 'failed') {
      setStatus('connected', `${receiverName} — not streaming`);
      stopStats();
    }
  };

  // Add all video tracks
  if (localStream) {
    localStream.getTracks().forEach(track => {
      peerConnection.addTrack(track, localStream);
    });
  }

  // Prefer H264 for hardware encoding compatibility
  const offer = await peerConnection.createOffer({
    offerToReceiveAudio: false,
    offerToReceiveVideo: false,
  });

  offer.sdp = preferH264(offer.sdp);
  offer.sdp = setBitrateInSdp(offer.sdp, 20000); // 20 Mbps — sharp text over LAN

  await peerConnection.setLocalDescription(offer);
  sendMsg({ type: 'offer', sdp: offer.sdp });
  console.log('[WebRTC] Offer sent');
}

/**
 * Move H264 to the front of the video codec list in the SDP.
 * Falls back gracefully if H264 is absent.
 */
function preferH264(sdp) {
  // Find all H264 payload types in m=video block
  const h264Pt = [];
  const h264Re = /a=rtpmap:(\d+) H264\/90000/gi;
  let m;
  while ((m = h264Re.exec(sdp)) !== null) h264Pt.push(m[1]);
  if (h264Pt.length === 0) return sdp; // no H264 — use default

  return sdp.replace(/(m=video \d+ \S+ )([\d ]+)/, (_full, prefix, pts) => {
    const list     = pts.trim().split(' ');
    const reordered = [
      ...h264Pt.filter(pt => list.includes(pt)),
      ...list.filter(pt => !h264Pt.includes(pt)),
    ];
    return prefix + reordered.join(' ');
  });
}

/**
 * Inject b=AS:<kbps> bandwidth lines into each m= section.
 */
function setBitrateInSdp(sdp, kbps) {
  return sdp.replace(
    /(m=video[^\n]*\n)((?:[^\n]*\n)*?)(a=)/,
    `$1$2b=AS:${kbps}\r\n$3`
  );
}

function closePeerConnection() {
  if (!peerConnection) return;
  peerConnection.onicecandidate          = null;
  peerConnection.onconnectionstatechange = null;
  peerConnection.close();
  peerConnection = null;
}

// ─── Disconnect ───────────────────────────────────────────────────────────────

function onReceiverDisconnect() {
  receiverConnected = false;
  receiverName = '';
  UI.deviceName.textContent = 'No device connected';
  closePeerConnection();
  stopStats();
  setStatus(isStreaming ? 'streaming' : 'waiting');
  toast('TV disconnected');
}

// ─── Stats Polling ────────────────────────────────────────────────────────────

function startStatsPolling() {
  UI.statsCard.classList.remove('hidden');
  lastBytesSent = 0;
  lastStatsTime = performance.now();
  stopStats();

  statsInterval = setInterval(async () => {
    if (!peerConnection) return;
    try {
      const stats = await peerConnection.getStats();
      const now   = performance.now();

      stats.forEach(r => {
        if (r.type === 'outbound-rtp' && r.kind === 'video') {
          UI.statRes.textContent = r.frameWidth
            ? `${r.frameWidth}×${r.frameHeight}`
            : '—';
          UI.statFps.textContent = r.framesPerSecond != null
            ? `${Math.round(r.framesPerSecond)} fps`
            : '—';

          // Calculate bitrate from delta
          if (lastBytesSent > 0) {
            const dt      = (now - lastStatsTime) / 1000;
            const bitrate = ((r.bytesSent - lastBytesSent) * 8) / dt / 1000;
            UI.statBitrate.textContent = `${Math.round(bitrate)} kbps`;
          }
          lastBytesSent = r.bytesSent ?? 0;
          lastStatsTime = now;
        }

        if (r.type === 'candidate-pair' && r.state === 'succeeded') {
          UI.statConn.textContent = r.localCandidateType === 'host'
            ? 'Direct (LAN)'
            : r.localCandidateType || '—';
        }
      });
    } catch { /* stats can throw when connection closes */ }
  }, 1000);
}

function stopStats() {
  clearInterval(statsInterval);
  statsInterval = null;
  UI.statsCard.classList.add('hidden');
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function sendMsg(msg) {
  if (signalingSocket?.readyState === WebSocket.OPEN) {
    signalingSocket.send(JSON.stringify(msg));
  }
}

const STATUS = {
  waiting:    { cls: 'dot-waiting',    text: 'Waiting for TV…'  },
  connected:  { cls: 'dot-connected',  text: 'TV Connected'      },
  connecting: { cls: 'dot-connecting', text: 'Connecting…'       },
  streaming:  { cls: 'dot-streaming',  text: 'Streaming'         },
  error:      { cls: 'dot-error',      text: 'Error'             },
};

function setStatus(state, overrideText) {
  const s = STATUS[state] ?? STATUS.waiting;
  UI.statusDot.className  = `status-dot ${s.cls}`;
  UI.statusText.textContent = overrideText ?? s.text;
}

let toastTimer;
function toast(msg) {
  UI.toast.textContent = msg;
  UI.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => UI.toast.classList.remove('show'), 3000);
}

// ─── Boot ─────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  UI.startBtn.addEventListener('click',    startStreaming);
  UI.stopBtn.addEventListener('click',     stopStreaming);
  UI.sourceSelect.addEventListener('change', onSourceChange);
  init();
});
