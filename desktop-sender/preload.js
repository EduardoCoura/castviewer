/**
 * CastViewer - Desktop Sender
 * Preload Script (Context Isolation Bridge)
 */

const { contextBridge, ipcRenderer, webFrame } = require('electron');

const api = {
  // Screen and window capture sources
  getSources: (options) => ipcRenderer.invoke('get-sources', options),
  selectSource: (sourceId, audio) => ipcRenderer.invoke('select-source', { sourceId, audio }),

  // Fire Stick network discovery (mDNS _firecast._tcp)
  getDevices: () => ipcRenderer.invoke('get-devices'),
  refreshDevices: () => ipcRenderer.invoke('refresh-devices'),
  onDevicesUpdated: (callback) => {
    const handler = (_event, devices) => callback(devices);
    ipcRenderer.on('devices-updated', handler);
    return () => ipcRenderer.removeListener('devices-updated', handler);
  },

  // Streaming state and tray
  setStreamingState: (isStreaming, deviceName) =>
    ipcRenderer.invoke('set-streaming-state', { isStreaming, deviceName }),
  onTrayStopCast: (callback) => {
    const handler = () => callback();
    ipcRenderer.on('tray-stop-cast', handler);
    return () => ipcRenderer.removeListener('tray-stop-cast', handler);
  },
  onTraySaveClip: (callback) => {
    const handler = () => callback();
    ipcRenderer.on('tray-save-clip', handler);
    return () => ipcRenderer.removeListener('tray-save-clip', handler);
  },
  onTraySaveScreenshot: (callback) => {
    const handler = () => callback();
    ipcRenderer.on('tray-save-screenshot', handler);
    return () => ipcRenderer.removeListener('tray-save-screenshot', handler);
  },

  // Desktop Captures (Clip 30s, Screenshot & In-App Gallery)
  saveScreenshot: (payload) => ipcRenderer.invoke('save-screenshot', payload),
  saveReplayClip: (payload) => ipcRenderer.invoke('save-replay-clip', payload),
  listCaptures: () => ipcRenderer.invoke('list-captures'),
  openCaptureFolder: (kind) => ipcRenderer.invoke('open-capture-folder', kind),
  revealCaptureFile: (filePath) => ipcRenderer.invoke('reveal-capture-file', filePath),
  deleteCaptureFile: (filePath) => ipcRenderer.invoke('delete-capture-file', filePath),
  showTakeoverNotification: (payload) => ipcRenderer.invoke('show-takeover-notification', payload),
  clearTakeoverNotification: () => ipcRenderer.invoke('clear-takeover-notification'),

  // Native Loopback Audio & Speaker Mute (No VB-CABLE needed)
  startProcessAudio: () => ipcRenderer.invoke('start-process-audio'),
  stopProcessAudio: () => ipcRenderer.invoke('stop-process-audio'),
  setSpeakerMute: (muted) => ipcRenderer.invoke('set-speaker-mute', muted),
  getSpeakerMute: () => ipcRenderer.invoke('get-speaker-mute'),
  triggerMediaPlayPause: () => ipcRenderer.invoke('trigger-media-play-pause'),
  onAudioPcmChunk: (callback) => {
    const handler = (_event, chunk) => callback(chunk);
    ipcRenderer.on('audio-pcm-chunk', handler);
    return () => ipcRenderer.removeListener('audio-pcm-chunk', handler);
  },

  // Preferences & URLs
  openExternalUrl: (url) => ipcRenderer.invoke('open-external-url', url),
  setMinimizeToTrayPref: (enabled) => ipcRenderer.invoke('set-minimize-to-tray-pref', enabled),
  getMinimizeToTrayPref: () => ipcRenderer.invoke('get-minimize-to-tray-pref'),

  // Weak PC, Compatibility, Low RAM & Hardware Auto-Detection
  getPerformancePrefs: () => ipcRenderer.invoke('get-performance-prefs'),
  setPerformancePrefs: (prefs) => ipcRenderer.invoke('set-performance-prefs', prefs),
  reapplyAutoPerformance: () => ipcRenderer.invoke('reapply-auto-performance'),
  relaunchApp: () => ipcRenderer.invoke('relaunch-app'),
  getMemoryUsage: () => ipcRenderer.invoke('get-memory-usage'),
  trimMemory: () => {
    try {
      webFrame.clearCache();
    } catch (_) {}
    return ipcRenderer.invoke('trim-memory');
  },

  // Window control buttons
  minimizeWindow: () => ipcRenderer.invoke('window-minimize'),
  hideToTray: () => ipcRenderer.invoke('window-hide-to-tray'),
  closeWindow: () => ipcRenderer.invoke('window-close'),

  // System info
  getHostname: () => ipcRenderer.invoke('get-hostname'),
  getVersion: () => '1.0.0'
};

contextBridge.exposeInMainWorld('castviewer', api);
contextBridge.exposeInMainWorld('firecast', api);
