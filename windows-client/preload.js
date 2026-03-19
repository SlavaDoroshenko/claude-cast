'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getSources:  ()  => ipcRenderer.invoke('get-sources'),
  getLocalIP:  ()  => ipcRenderer.invoke('get-local-ip'),
  getPin:      ()  => ipcRenderer.invoke('get-pin'),
  getHostname: ()  => ipcRenderer.invoke('get-hostname'),
  getVolume:   ()  => ipcRenderer.invoke('get-volume'),
  setVolume:   (v) => ipcRenderer.invoke('set-volume', v),
});
