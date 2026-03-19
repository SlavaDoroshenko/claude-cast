'use strict';

const WebSocket = require('ws');
const dgram = require('dgram');
const os = require('os');

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getLocalIP() {
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

// ─── SignalingServer ──────────────────────────────────────────────────────────

class SignalingServer {
  constructor() {
    /** @type {WebSocket.Server|null} */
    this.wss = null;
    /** @type {import('dgram').Socket|null} */
    this.udp = null;
    /**
     * rooms: Map<pin, { sender: WebSocket|null, receiver: WebSocket|null }>
     */
    this.rooms = new Map();
  }

  /**
   * Start both WebSocket signaling and UDP discovery.
   * @param {number} wsPort   WebSocket port (default 8765)
   * @param {number} udpPort  UDP discovery port (default 8766)
   */
  start(wsPort = 8765, udpPort = 8766) {
    this._startWebSocket(wsPort);
    this._startDiscovery(udpPort);
    return this;
  }

  stop() {
    if (this.wss) {
      this.wss.close();
      this.wss = null;
    }
    if (this.udp) {
      try { this.udp.close(); } catch (_) {}
      this.udp = null;
    }
  }

  // ── WebSocket ────────────────────────────────────────────────────────────

  _startWebSocket(port) {
    this.wss = new WebSocket.Server({ port });

    this.wss.on('listening', () => {
      console.log(`[Signaling] WebSocket server listening on port ${port}`);
    });

    this.wss.on('error', err => {
      console.error('[Signaling] WebSocket server error:', err.message);
    });

    this.wss.on('connection', ws => {
      /** @type {string|null} */ ws._pin = null;
      /** @type {'sender'|'receiver'|null} */ ws._role = null;
      /** @type {string} */ ws._name = 'Unknown';

      ws.on('message', raw => {
        let msg;
        try {
          msg = JSON.parse(raw.toString());
        } catch {
          return; // ignore malformed
        }
        this._handleMessage(ws, msg);
      });

      ws.on('close', () => this._handleClose(ws));
      ws.on('error', err => console.error('[Signaling] Client error:', err.message));
    });
  }

  _handleMessage(ws, msg) {
    switch (msg.type) {
      case 'join':        return this._handleJoin(ws, msg);
      case 'offer':       return this._relay(ws, msg, 'receiver');
      case 'answer':      return this._relay(ws, msg, 'sender');
      case 'ice-candidate': return this._relayIce(ws, msg);
      default:
        console.warn('[Signaling] Unknown message type:', msg.type);
    }
  }

  _handleJoin(ws, { pin, role, name }) {
    if (!pin || !role) return;

    ws._pin  = pin;
    ws._role = role;
    ws._name = name || (role === 'sender' ? 'PC' : 'Android TV');

    // Create room if needed
    if (!this.rooms.has(pin)) {
      this.rooms.set(pin, { sender: null, receiver: null });
    }
    const room = this.rooms.get(pin);

    // Close any stale connection for this role
    const prev = room[role];
    if (prev && prev !== ws && prev.readyState === WebSocket.OPEN) {
      prev.close();
    }
    room[role] = ws;

    this._send(ws, { type: 'joined', role, pin });

    // Cross-notify parties
    if (role === 'receiver' && room.sender?.readyState === WebSocket.OPEN) {
      this._send(room.sender, { type: 'receiver-joined', receiverName: ws._name });
    } else if (role === 'sender' && room.receiver?.readyState === WebSocket.OPEN) {
      // Receiver was already waiting — tell the new sender immediately
      this._send(ws, { type: 'receiver-joined', receiverName: room.receiver._name });
    }

    console.log(`[Signaling] [${pin}] ${role} joined (${ws._name})`);
  }

  _relay(ws, msg, targetRole) {
    const room = this.rooms.get(ws._pin);
    if (!room) return;
    const target = room[targetRole];
    if (target?.readyState === WebSocket.OPEN) {
      this._send(target, msg);
      console.log(`[Signaling] [${ws._pin}] ${msg.type} → ${targetRole}`);
    }
  }

  _relayIce(ws, msg) {
    const room = this.rooms.get(ws._pin);
    if (!room) return;
    const targetRole = ws._role === 'sender' ? 'receiver' : 'sender';
    const target = room[targetRole];
    if (target?.readyState === WebSocket.OPEN) {
      this._send(target, msg);
    }
  }

  _handleClose(ws) {
    if (!ws._pin) return;
    const room = this.rooms.get(ws._pin);
    if (!room) return;

    // Clear this client's slot
    if (room[ws._role] === ws) {
      room[ws._role] = null;
    }

    // Notify the other party
    const otherRole = ws._role === 'sender' ? 'receiver' : 'sender';
    const other = room[otherRole];
    if (other?.readyState === WebSocket.OPEN) {
      this._send(other, { type: 'peer-disconnected', role: ws._role });
    }

    // Garbage-collect empty rooms
    if (!room.sender && !room.receiver) {
      this.rooms.delete(ws._pin);
      console.log(`[Signaling] [${ws._pin}] Room removed`);
    }

    console.log(`[Signaling] [${ws._pin}] ${ws._role} disconnected`);
  }

  _send(ws, msg) {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  }

  // ── UDP Discovery ────────────────────────────────────────────────────────

  _startDiscovery(port) {
    this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });

    this.udp.on('error', err => {
      console.error('[Discovery] UDP error:', err.message);
    });

    this.udp.on('message', (buf, rinfo) => {
      if (buf.toString().trim() !== 'SCREEN_MIRROR_DISCOVER') return;

      const response = JSON.stringify({
        name:    os.hostname(),
        ip:      getLocalIP(),
        port:    8765,
        version: '1.0',
      });

      this.udp.send(response, rinfo.port, rinfo.address, err => {
        if (err) console.error('[Discovery] Send error:', err.message);
        else console.log(`[Discovery] Responded to ${rinfo.address}`);
      });
    });

    this.udp.bind(port, () => {
      this.udp.setBroadcast(true);
      console.log(`[Discovery] UDP listener on port ${port}`);
    });
  }
}

module.exports = SignalingServer;
