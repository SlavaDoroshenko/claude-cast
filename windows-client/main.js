'use strict';

const {
  app,
  BrowserWindow,
  ipcMain,
  desktopCapturer,
  session,
} = require('electron');
const path  = require('path');
const os    = require('os');
const SignalingServer = require('./server/signaling');
const loudness = require('loudness');

// ─── Globals ─────────────────────────────────────────────────────────────────

let mainWindow     = null;
let signalingServer = null;

// One stable PIN for the lifetime of the process
const SESSION_PIN = String(Math.floor(1000 + Math.random() * 9000));

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getLocalIP() {
  const ifaces = os.networkInterfaces();
  const candidates = [];

  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        candidates.push({ ip: iface.address, name });
      }
    }
  }

  // Предпочитаем 192.168.x.x — типичная домашняя Wi-Fi сеть
  const wifi = candidates.find(c => c.ip.startsWith('192.168.'));
  if (wifi) return wifi.ip;

  // Затем 10.0.x.x / 10.1.x.x — некоторые роутеры
  const lan10 = candidates.find(c => /^10\.[01]\./.test(c.ip));
  if (lan10) return lan10.ip;

  // Затем 172.16-31.x.x
  const lan172 = candidates.find(c => /^172\.(1[6-9]|2\d|3[01])\./.test(c.ip));
  if (lan172) return lan172.ip;

  // Fallback — любой не-internal
  return candidates[0]?.ip ?? '127.0.0.1';
}

// ─── Window ──────────────────────────────────────────────────────────────────

function createWindow() {
  mainWindow = new BrowserWindow({
    width:     960,
    height:    680,
    minWidth:  800,
    minHeight: 580,
    title:     'ScreenMirror',
    backgroundColor: '#0f0f1a',
    show: false,
    webPreferences: {
      preload:          path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration:  false,
      webSecurity:      true,
    },
  });

  // Allow screen-capture permission requests from renderer
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === 'media');
  });

  // Electron 25+: handle getDisplayMedia automatically for getUserMedia fallback
  // The renderer uses getUserMedia with chromeMediaSource, so this is a safety net.
  try {
    mainWindow.webContents.session.setDisplayMediaRequestHandler(
      (_request, callback) => {
        desktopCapturer
          .getSources({ types: ['screen'] })
          .then(sources => callback({ video: sources[0], audio: false }))
          .catch(() => callback({}));
      }
    );
  } catch {
    // API may not be available in all Electron 29 builds
  }

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.setMenuBarVisibility(false);

  mainWindow.on('closed', () => { mainWindow = null; });
}

// ─── Lifecycle ───────────────────────────────────────────────────────────────

app.whenReady().then(() => {
  signalingServer = new SignalingServer();
  signalingServer.start(8765, 8766);

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (signalingServer) signalingServer.stop();
  if (process.platform !== 'darwin') app.quit();
});

// ─── IPC Handlers ────────────────────────────────────────────────────────────

ipcMain.handle('get-sources', async () => {
  try {
    const sources = await desktopCapturer.getSources({
      types:         ['screen'],
      thumbnailSize: { width: 320, height: 180 },
      fetchWindowIcons: false,
    });
    return sources.map(s => ({
      id:        s.id,
      name:      s.name,
      thumbnail: s.thumbnail.toDataURL(),
    }));
  } catch (err) {
    console.error('[IPC] get-sources error:', err.message);
    return [];
  }
});

ipcMain.handle('get-local-ip', ()      => getLocalIP());
ipcMain.handle('get-pin',      ()      => SESSION_PIN);
ipcMain.handle('get-hostname', ()      => os.hostname());
ipcMain.handle('get-volume',   ()      => loudness.getVolume());
ipcMain.handle('set-volume',   (_, v)  => loudness.setVolume(v));
