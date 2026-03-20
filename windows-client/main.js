'use strict';

const {
  app,
  BrowserWindow,
  ipcMain,
  desktopCapturer,
  session,
} = require('electron');
const path       = require('path');
const os         = require('os');
const fs         = require('fs');
const { execFile } = require('child_process');
const SignalingServer = require('./server/signaling');

// ─── Volume control (PowerShell, no native modules) ───────────────────────────

const VOL_PS1 = path.join(os.tmpdir(), 'sm_vol.ps1');

// Write the helper script once; PowerShell @'...'@ here-string avoids escaping
fs.writeFileSync(VOL_PS1, [
  'param([string]$action, [int]$v = 0)',
  "Add-Type -TypeDefinition @'",
  'using System;using System.Runtime.InteropServices;',
  '[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]',
  'interface IAudioEndpointVolume{int a();int b();int c();int d();int SetMasterVolumeLevelScalar(float f,Guid g);int e();int GetMasterVolumeLevelScalar(out float f);int h();int i();int j();int k();int SetMute(bool b,Guid g);int GetMute(out bool b);}',
  '[Guid("D666063F-1587-4E43-81F1-B948E807363F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]',
  'interface IMMDevice{int Activate(ref Guid id,int c,int p,out IAudioEndpointVolume v);}',
  '[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]',
  'interface IMMDeviceEnumerator{int f();int GetDefaultAudioEndpoint(int d,int r,out IMMDevice e);}',
  '[ComImport,Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]class MMDevEnum{}',
  'public class WinAudio{',
  '  static IAudioEndpointVolume Ep(){var en=new MMDevEnum()as IMMDeviceEnumerator;IMMDevice d;en.GetDefaultAudioEndpoint(0,1,out d);IAudioEndpointVolume v;var id=typeof(IAudioEndpointVolume).GUID;d.Activate(ref id,23,0,out v);return v;}',
  '  public static int GetVol(){float f;Ep().GetMasterVolumeLevelScalar(out f);return(int)Math.Round(f*100);}',
  '  public static void SetVol(int p){Ep().SetMasterVolumeLevelScalar(p/100f,Guid.Empty);}',
  '}',
  "'@ -ErrorAction SilentlyContinue",
  "if ($action -eq 'get') { [WinAudio]::GetVol() } else { [WinAudio]::SetVol($v) }",
].join('\r\n'), 'utf8');

function callVolScript(action, v) {
  const args = ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-File', VOL_PS1, '-action', action];
  if (v !== undefined) args.push('-v', String(v));
  return new Promise(resolve => {
    execFile('powershell.exe', args, { windowsHide: true }, (err, stdout) => {
      resolve(stdout.trim());
    });
  });
}

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
ipcMain.handle('get-volume',   ()      => callVolScript('get').then(v => parseInt(v) || 50));
ipcMain.handle('set-volume',   (_, v)  => callVolScript('set', v));
