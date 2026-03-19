'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getSources:  ()  => ipcRenderer.invoke('get-sources'),
  getLocalIP:  ()  => ipcRenderer.invoke('get-local-ip'),
  getPin:      ()  => ipcRenderer.invoke('get-pin'),
  getHostname: ()  => ipcRenderer.invoke('get-hostname'),
});
