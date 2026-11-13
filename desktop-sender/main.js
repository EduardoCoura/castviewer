/**
 * CastViewer - Desktop Sender
 * Main Process (Electron)
 * Studio-Grade Low-Latency Desktop Sender for Fire TV
 */

const { app, BrowserWindow, ipcMain, desktopCapturer, session, Tray, Menu, nativeImage, shell, Notification, powerSaveBlocker } = require('electron');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { pathToFileURL } = require('url');
const { exec } = require('child_process');
const { Bonjour } = require('bonjour-service');
const { LoopbackCapture } = require('loopback-capture');
const { detectAndRecommendProfile, buildChromiumSwitches } = require('./hardware-profile');
const { remuxReplayBufferToStandardMp4 } = require('./mp4-defragment');

// Configure Product Identity
app.setName('CastViewer');
app.setAppUserModelId('com.castviewer.app');

// Persist Low-Spec, Low-RAM, Compatibility & Auto-Detected Hardware Profile in userData
const DEFAULT_PERF_PREFS = {
  lowSpecMode: false,
  lowRamMode: false,
  compatMode: false,
  userCustomized: false,
  autoConfigured: false,
  hybridGpuDetected: false,
  recommendedPreset: '1080p60',
  selectedPreset: null,
  autoReason: '',
  detectedHardware: null
};

function isValidPreset(val) {
  return val === '1080p60' || val === '720p60' || val === '1080p30' || val === '720p30';
}

function getPerfPrefsPath() {
  return path.join(app.getPath('userData'), 'performance-prefs.json');
}

function normalizePerfPrefs(raw) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const preset = isValidPreset(src.recommendedPreset) ? src.recommendedPreset : '1080p60';
  const selectedPreset = isValidPreset(src.selectedPreset) ? src.selectedPreset : null;
  return {
    lowSpecMode: Boolean(src.lowSpecMode),
    lowRamMode: Boolean(src.lowRamMode),
    compatMode: Boolean(src.compatMode),
    userCustomized: Boolean(src.userCustomized),
    autoConfigured: Boolean(src.autoConfigured),
    hybridGpuDetected: Boolean(src.hybridGpuDetected),
    recommendedPreset: preset,
    selectedPreset,
    autoReason: typeof src.autoReason === 'string' ? src.autoReason : '',
    detectedHardware: src.detectedHardware && typeof src.detectedHardware === 'object' ? src.detectedHardware : null
  };
}

function loadPerformancePrefs() {
  try {
    const raw = fs.readFileSync(getPerfPrefsPath(), 'utf8');
    return normalizePerfPrefs(JSON.parse(raw));
  } catch (_) {
    return { ...DEFAULT_PERF_PREFS };
  }
}

function savePerformancePrefs(prefs) {
  const clean = normalizePerfPrefs(prefs);
  try {
    fs.mkdirSync(app.getPath('userData'), { recursive: true });
    fs.writeFileSync(getPerfPrefsPath(), JSON.stringify(clean), 'utf8');
  } catch (err) {
    console.warn('CastViewer: Falha ao salvar preferências de desempenho:', err.message);
  }
  return clean;
}

let perfPrefs = loadPerformancePrefs();

const STARTUP_SWITCH_SIGNATURE = JSON.stringify({
  lowRamMode: perfPrefs.lowRamMode,
  lowSpecMode: perfPrefs.lowSpecMode,
  compatMode: perfPrefs.compatMode,
  hybridGpuDetected: perfPrefs.hybridGpuDetected
});

function computeNeedsRestart(prefs) {
  const currentSig = JSON.stringify({
    lowRamMode: Boolean(prefs.lowRamMode),
    lowSpecMode: Boolean(prefs.lowSpecMode),
    compatMode: Boolean(prefs.compatMode),
    hybridGpuDetected: Boolean(prefs.hybridGpuDetected)
  });
  return currentSig !== STARTUP_SWITCH_SIGNATURE;
}

// Single Instance Lock
const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

// Optimize Windows RAM Usage & Tune GPU Video Encoding / WGC Safety Per Detected Hardware
const chromiumSwitchSpec = buildChromiumSwitches(perfPrefs);
for (const [flag, value] of chromiumSwitchSpec.switches) {
  if (value !== undefined) {
    app.commandLine.appendSwitch(flag, value);
  } else {
    app.commandLine.appendSwitch(flag);
  }
}

let gpuInfoUpdated = false;
let hardwareDetectionPromise = null;

app.on('gpu-info-update', () => {
  gpuInfoUpdated = true;
});

async function waitForGpuInfoSnapshot(timeoutMs = 1200) {
  if (!gpuInfoUpdated) {
    await new Promise((resolve) => {
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        app.removeListener('gpu-info-update', finish);
        resolve();
      };
      const timer = setTimeout(finish, timeoutMs);
      app.once('gpu-info-update', finish);
    });
  }

  let gpuInfo = null;
  try {
    gpuInfo = await app.getGPUInfo('complete');
  } catch (_) {
    try {
      gpuInfo = await app.getGPUInfo('basic');
    } catch (_2) {
      gpuInfo = null;
    }
  }

  let gpuFeatureStatus = null;
  try {
    gpuFeatureStatus = app.getGPUFeatureStatus();
  } catch (_) {
    gpuFeatureStatus = null;
  }

  return { gpuInfo, gpuFeatureStatus };
}

async function detectHardwareProfile({ forceReapply = false } = {}) {
  const { gpuInfo, gpuFeatureStatus } = await waitForGpuInfoSnapshot(forceReapply ? 250 : 1200);
  const analysis = detectAndRecommendProfile({
    cpus: os.cpus(),
    totalMemBytes: os.totalmem(),
    gpuInfo,
    gpuFeatureStatus
  });

  const shouldApplyRecommended = forceReapply || (!perfPrefs.userCustomized && !perfPrefs.autoConfigured);

  const nextPrefs = {
    ...perfPrefs,
    detectedHardware: analysis.detectedHardware,
    hybridGpuDetected: analysis.recommendation.hybridGpuDetected,
    recommendedPreset: analysis.recommendation.preset,
    autoReason: analysis.recommendation.reason
  };

  if (shouldApplyRecommended) {
    nextPrefs.lowSpecMode = analysis.recommendation.lowSpecMode;
    nextPrefs.lowRamMode = analysis.recommendation.lowRamMode;
    nextPrefs.compatMode = analysis.recommendation.compatMode;
    nextPrefs.selectedPreset = analysis.recommendation.preset;
    nextPrefs.autoConfigured = true;
    if (forceReapply) {
      nextPrefs.userCustomized = false;
    }
  }

  perfPrefs = savePerformancePrefs(nextPrefs);
  return perfPrefs;
}

let mainWindow = null;
let tray = null;
let bonjour = null;
let bonjourBrowser = null;
const discoveredDevices = new Map();

// Active source selection for setDisplayMediaRequestHandler
let activeSourceId = null;

// Native loopback capture instance & notebook speaker mute tracking
let loopbackCaptureInstance = null;
let isSpeakerMutedByUs = false;
let pendingBuffers = [];
let pendingBytes = 0;

// Minimize to tray on close preference
let minimizeToTrayOnClose = true;

// Current streaming state
let streamingState = {
  isStreaming: false,
  deviceName: ''
};

function getSpeakerMuteScriptPath() {
  const fs = require('fs');
  const unpackedPath = path.join(__dirname.replace('app.asar', 'app.asar.unpacked'), 'scripts', 'speaker-mute.ps1');
  if (fs.existsSync(unpackedPath)) return unpackedPath;
  return path.join(__dirname, 'scripts', 'speaker-mute.ps1');
}

function getSpeakerMute() {
  return new Promise((resolve) => {
    if (process.platform !== 'win32') return resolve(false);
    const scriptPath = getSpeakerMuteScriptPath();
    const psCommand = `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${scriptPath}" -Action get`;
    exec(psCommand, { timeout: 3000 }, (err, stdout) => {
      if (err) return resolve(false);
      try {
        const res = JSON.parse(stdout.trim());
        resolve(Boolean(res.muted));
      } catch (_) {
        resolve(false);
      }
    });
  });
}

function setSpeakerMute(muted) {
  return new Promise((resolve) => {
    if (process.platform !== 'win32') return resolve(false);
    const scriptPath = getSpeakerMuteScriptPath();
    const action = muted ? 'mute' : 'unmute';
    const psCommand = `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${scriptPath}" -Action ${action}`;
    exec(psCommand, { timeout: 4000 }, (err, stdout) => {
      if (err) {
        console.warn('CastViewer: Falha ao alterar mudo dos alto-falantes:', err.message);
        return resolve(false);
      }
      try {
        const res = JSON.parse(stdout.trim());
        if (res.success) {
          isSpeakerMutedByUs = Boolean(muted);
        }
        resolve(Boolean(res.success));
      } catch (_) {
        resolve(false);
      }
    });
  });
}

function restoreSpeakerMuteSync() {
  if (process.platform === 'win32' && isSpeakerMutedByUs) {
    try {
      const { execSync } = require('child_process');
      const scriptPath = getSpeakerMuteScriptPath();
      const psCommand = `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${scriptPath}" -Action unmute`;
      execSync(psCommand, { timeout: 3000 });
      isSpeakerMutedByUs = false;
      console.log('CastViewer: Alto-falantes do notebook restaurados sincronicamente.');
    } catch (e) {
      console.warn('CastViewer: Erro ao restaurar alto-falantes no encerramento:', e.message);
    }
  }
}

function triggerMediaPlayPause() {
  return new Promise((resolve) => {
    if (process.platform !== 'win32') return resolve(false);
    const psCmd = `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Add-Type -MemberDefinition '[DllImport(\\"user32.dll\\")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);' -Name Win32Key -Namespace CastViewer; [CastViewer.Win32Key]::keybd_event(0xB3, 0, 0, 0); [CastViewer.Win32Key]::keybd_event(0xB3, 0, 2, 0);"`;
    exec(psCmd, { timeout: 2500 }, (err) => {
      if (err) {
        console.warn('CastViewer: Falha ao acionar tecla Play/Pause:', err.message);
        return resolve(false);
      }
      resolve(true);
    });
  });
}

// Create main application window
function createWindow() {
  Menu.setApplicationMenu(null);
  const iconPath = path.join(__dirname, 'assets', process.platform === 'win32' ? 'castviewer_v2.ico' : 'icon.png');

  mainWindow = new BrowserWindow({
    width: 720,
    height: 540,
    minWidth: 640,
    minHeight: 500,
    frame: false,
    show: false,
    title: 'CastViewer',
    backgroundColor: '#0B0C10',
    icon: iconPath,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      devTools: !app.isPackaged,
      spellcheck: false,
      v8CacheOptions: perfPrefs.lowRamMode ? 'none' : 'code',
      backgroundThrottling: false // Keep WebRTC active when window is minimized or behind
    }
  });

  mainWindow.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file://')) e.preventDefault();
  });
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // When user closes window, if streaming & setting enabled, minimize to tray
  mainWindow.on('close', (event) => {
    if (!app.isQuitting && streamingState.isStreaming && minimizeToTrayOnClose) {
      event.preventDefault();
      mainWindow.hide();
      if (tray && tray.displayBalloon) {
        tray.displayBalloon({
          title: 'CastViewer em execução',
          content: 'A transmissão continua ativa na bandeja do sistema.'
        });
      }
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// System Tray setup
function createTray() {
  const iconPath = path.join(__dirname, 'assets', process.platform === 'win32' ? 'castviewer_v2.ico' : 'icon.png');
  const icon = nativeImage.createFromPath(iconPath);
  tray = new Tray(icon);
  tray.setToolTip('CastViewer - Espelhamento local ultra-rápido para Fire TV');

  updateTrayMenu();

  tray.on('click', () => {
    if (mainWindow) {
      if (mainWindow.isVisible()) {
        mainWindow.focus();
      } else {
        mainWindow.show();
      }
    }
  });

  tray.on('double-click', () => {
    if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

function updateTrayMenu() {
  if (!tray) return;

  const contextMenu = Menu.buildFromTemplate([
    {
      label: streamingState.isStreaming
        ? `🟢 Transmitindo: ${streamingState.deviceName || 'Fire TV'}`
        : '⚪ CastViewer - Pronto',
      enabled: false
    },
    { type: 'separator' },
    {
      label: 'Abrir CastViewer',
      click: () => {
        if (mainWindow) {
          mainWindow.show();
          mainWindow.focus();
        }
      }
    },
    ...(streamingState.isStreaming
      ? [
          {
            label: 'Salvar Clip 30s',
            click: () => {
              if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('tray-save-clip');
              }
            }
          },
          {
            label: 'Tirar Print (Screenshot)',
            click: () => {
              if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('tray-save-screenshot');
              }
            }
          },
          {
            label: 'Parar Transmissão',
            click: () => {
              if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('tray-stop-cast');
              }
            }
          }
        ]
      : []),
    { type: 'separator' },
    {
      label: 'Sair',
      click: () => {
        app.isQuitting = true;
        app.quit();
      }
    }
  ]);

  tray.setContextMenu(contextMenu);
}

// Setup DisplayMedia Request Handler for custom source
function setupDisplayMediaHandler() {
  session.defaultSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(true));
  session.defaultSession.setPermissionCheckHandler(() => true);

  session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
    try {
      const sources = await desktopCapturer.getSources({
        types: ['screen', 'window']
      });

      let chosen = null;
      if (activeSourceId) {
        chosen = sources.find((s) => s.id === activeSourceId);
      }

      // Fallback: if activeSourceId is not set or not found in sources, gracefully fall back to sources[0]
      if (!chosen && sources.length > 0) {
        chosen = sources.find((s) => s.id.startsWith('screen:')) || sources[0];
      }

      if (chosen) {
        callback({
          video: chosen,
          audio: undefined // Audio captured natively via loopback-capture + AudioWorklet!
        });
      } else {
        callback({});
      }
    } catch (err) {
      console.error('CastViewer: Erro em setDisplayMediaRequestHandler:', err);
      callback({});
    }
  });
}

// Setup mDNS Discovery via bonjour-service (listening for _firecast._tcp)
function setupBonjour() {
  try {
    bonjour = new Bonjour();
    bonjourBrowser = bonjour.find({ type: 'firecast' });

    bonjourBrowser.on('up', (service) => {
      // Find suitable IPv4 address
      const ipv4 =
        (service.addresses && service.addresses.find((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a))) ||
        (service.addresses && service.addresses[0]) ||
        service.host;

      const port = service.port || 8765;
      const friendlyName = (service.txt && service.txt.name) || service.name || 'Fire Stick';
      const version = (service.txt && service.txt.version) || '1';

      const device = {
        id: `${ipv4}:${port}`,
        name: friendlyName,
        host: service.host || ipv4,
        ip: ipv4,
        port: port,
        version: version,
        txt: service.txt || {},
        lastSeen: Date.now()
      };

      discoveredDevices.set(device.id, device);

      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('devices-updated', Array.from(discoveredDevices.values()));
      }
    });

    bonjourBrowser.on('down', (service) => {
      for (const [id, dev] of discoveredDevices.entries()) {
        if (dev.name === service.name || dev.host === service.host) {
          discoveredDevices.delete(id);
        }
      }

      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('devices-updated', Array.from(discoveredDevices.values()));
      }
    });
  } catch (err) {
    console.error('CastViewer: Falha ao iniciar descoberta mDNS Bonjour:', err);
  }
}

// Check if FxSound DSP is running to prevent echo caused by capturing both primary & FxSound processed streams
function getFxSoundPid() {
  return new Promise((resolve) => {
    if (process.platform !== 'win32') return resolve(null);
    exec('tasklist /FI "IMAGENAME eq FxSound.exe" /FO CSV /NH', { timeout: 2000 }, (err, stdout) => {
      if (err || !stdout) return resolve(null);
      const line = stdout.trim().split(/\r?\n/)[0] || '';
      const match = line.match(/"FxSound\.exe","(\d+)"/i) || line.match(/FxSound\.exe[,\s]+(\d+)/i);
      if (match && match[1]) {
        const pid = parseInt(match[1], 10);
        if (!isNaN(pid) && pid > 0) return resolve(pid);
      }
      resolve(null);
    });
  });
}

function getAppMemorySummary() {
  const metrics = app.getAppMetrics();
  let workingSetKb = 0;
  let privateKb = 0;
  for (const proc of metrics) {
    if (proc && proc.memory) {
      workingSetKb += proc.memory.workingSetSize || 0;
      privateKb += proc.memory.privateBytes || 0;
    }
  }
  return {
    workingSetMb: Math.round(workingSetKb / 1024),
    privateMb: Math.round(privateKb / 1024),
    processCount: metrics.length
  };
}

function getCaptureDirs() {
  let videosRoot;
  let picturesRoot;
  try {
    videosRoot = app.getPath('videos');
  } catch (_) {
    videosRoot = path.join(os.homedir(), 'Videos');
  }
  try {
    picturesRoot = app.getPath('pictures');
  } catch (_) {
    picturesRoot = path.join(os.homedir(), 'Pictures');
  }
  return {
    clipsDir: path.join(videosRoot, 'CastViewer'),
    screenshotsDir: path.join(picturesRoot, 'CastViewer')
  };
}

function formatCaptureTimestamp(d = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_` +
    `${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}`
  );
}

function isPathInsideDirs(candidatePath, dirs) {
  if (typeof candidatePath !== 'string' || !candidatePath.trim()) return false;
  const resolvedCandidate = path.resolve(candidatePath);
  return dirs.some((dir) => {
    const resolvedDir = path.resolve(dir);
    const rel = path.relative(resolvedDir, resolvedCandidate);
    return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
  });
}

// IPC Handlers
function setupIpc() {
  // Capture Sources (Screens & Windows)
  ipcMain.handle('get-sources', async (_event, options) => {
    try {
      const useLowRam = Boolean((options && options.lowRam) || perfPrefs.lowRamMode);
      const sources = await desktopCapturer.getSources({
        types: ['screen', 'window'],
        thumbnailSize: useLowRam ? { width: 160, height: 90 } : { width: 240, height: 135 },
        fetchWindowIcons: true
      });

      const jpegQuality = useLowRam ? 55 : 75;
      const mapped = sources
        .filter((s) => s && (s.id.startsWith('screen:') || (s.name && s.name.trim().length > 0)))
        .map((s) => ({
          id: s.id,
          name: (s.name && s.name.trim()) || 'Tela',
          thumbnail: s.thumbnail && !s.thumbnail.isEmpty()
            ? 'data:image/jpeg;base64,' + s.thumbnail.toJPEG(jpegQuality).toString('base64')
            : '',
          appIcon: s.appIcon && !s.appIcon.isEmpty() ? s.appIcon.toDataURL() : null,
          display_id: s.display_id,
          isScreen: s.id.startsWith('screen:')
        }));

      // Sort screens first, then open windows preserving native z-order
      return mapped.sort((a, b) => Number(b.isScreen) - Number(a.isScreen));
    } catch (err) {
      console.error('CastViewer: Falha ao obter fontes desktopCapturer:', err);
      return [];
    }
  });

  // Select source before calling getDisplayMedia
  ipcMain.handle('select-source', (_event, payload) => {
    const sourceId = payload && typeof payload.sourceId === 'string' ? payload.sourceId : null;
    if (!sourceId || sourceId.length > 128) {
      return false;
    }
    activeSourceId = sourceId;
    return true;
  });

  // Get current discovered devices list
  ipcMain.handle('get-devices', () => {
    return Array.from(discoveredDevices.values());
  });

  // Manually refresh mDNS devices
  ipcMain.handle('refresh-devices', () => {
    if (bonjourBrowser) {
      try {
        bonjourBrowser.update();
      } catch (err) {
        console.warn('CastViewer: Erro ao atualizar browser Bonjour:', err);
      }
    }
    return Array.from(discoveredDevices.values());
  });

  let streamingPowerBlockerId = null;

  function applyProcessStreamingPriority(isHigh) {
    const targetPrio = isHigh
      ? os.constants.priority.PRIORITY_ABOVE_NORMAL
      : os.constants.priority.PRIORITY_NORMAL;
    const pids = new Set([process.pid]);
    try {
      if (typeof app.getAppMetrics === 'function') {
        for (const metric of app.getAppMetrics()) {
          if (metric && metric.pid) {
            pids.add(metric.pid);
          }
        }
      }
    } catch (_) {}

    for (const pid of pids) {
      try {
        os.setPriority(pid, targetPrio);
      } catch (_) {}
    }
  }

  // Update streaming state (from renderer)
  ipcMain.handle('set-streaming-state', async (_event, payload) => {
    const isStreaming = Boolean(payload && payload.isStreaming);
    const rawName = payload && typeof payload.deviceName === 'string' ? payload.deviceName : '';
    streamingState.isStreaming = isStreaming;
    streamingState.deviceName = rawName.slice(0, 64);
    updateTrayMenu();

    applyProcessStreamingPriority(streamingState.isStreaming);

    try {
      if (streamingState.isStreaming) {
        if (streamingPowerBlockerId === null || !powerSaveBlocker.isStarted(streamingPowerBlockerId)) {
          streamingPowerBlockerId = powerSaveBlocker.start('prevent-app-suspension');
        }
      } else if (streamingPowerBlockerId !== null) {
        if (powerSaveBlocker.isStarted(streamingPowerBlockerId)) {
          powerSaveBlocker.stop(streamingPowerBlockerId);
        }
        streamingPowerBlockerId = null;
      }
    } catch (_) {}

    if (!streamingState.isStreaming) {
      if (loopbackCaptureInstance) {
        try { loopbackCaptureInstance.stop(); } catch (_) {}
        loopbackCaptureInstance = null;
      }
      if (isSpeakerMutedByUs) {
        await setSpeakerMute(false);
      }
    }

    if (perfPrefs.lowRamMode) {
      session.defaultSession.clearCache().catch(() => {});
    }
    return true;
  });

  // Native Loopback Audio Capture (48kHz 16-bit PCM before speaker mute)
  // If FxSound is running, exclude its PID to avoid capturing duplicate delayed DSP audio
  ipcMain.handle('start-process-audio', async () => {
    try {
      if (loopbackCaptureInstance) {
        try { loopbackCaptureInstance.stop(); } catch (_) {}
        loopbackCaptureInstance = null;
      }
      const fxSoundPid = await getFxSoundPid();
      const excludePid = fxSoundPid || process.pid;

      pendingBuffers = [];
      pendingBytes = 0;

      loopbackCaptureInstance = new LoopbackCapture();
      loopbackCaptureInstance.startSystemAudio((chunk) => {
        if (!mainWindow || mainWindow.isDestroyed()) return;
        pendingBuffers.push(chunk);
        pendingBytes += chunk.byteLength || chunk.length || 0;
        // Batch ~10ms (1920 bytes at 48kHz 16-bit stereo) to cut capture->IPC buffering delay in half
        if (pendingBytes >= 1920) {
          const merged = pendingBuffers.length === 1 ? pendingBuffers[0] : Buffer.concat(pendingBuffers, pendingBytes);
          pendingBuffers = [];
          pendingBytes = 0;
          mainWindow.webContents.send('audio-pcm-chunk', merged);
        }
      }, excludePid);
      console.log(`CastViewer: LoopbackCapture iniciado (excluindo PID: ${excludePid}${fxSoundPid ? ' [FxSound.exe detectado - evitando eco]' : ' [process.pid]'})`);
      return true;
    } catch (err) {
      console.error('CastViewer: Falha ao iniciar LoopbackCapture:', err);
      return false;
    }
  });

  ipcMain.handle('stop-process-audio', async () => {
    try {
      pendingBuffers = [];
      pendingBytes = 0;
      if (loopbackCaptureInstance) {
        loopbackCaptureInstance.stop();
        loopbackCaptureInstance = null;
        console.log('CastViewer: LoopbackCapture finalizado.');
      }
      if (isSpeakerMutedByUs) {
        await setSpeakerMute(false);
      }
      return true;
    } catch (err) {
      console.warn('CastViewer: Erro ao parar LoopbackCapture:', err);
      return false;
    }
  });

  // Speaker Mute Control via IAudioEndpointVolume Win32 COM
  ipcMain.handle('set-speaker-mute', async (_event, muted) => {
    return await setSpeakerMute(Boolean(muted));
  });

  ipcMain.handle('get-speaker-mute', async () => {
    return await getSpeakerMute();
  });

  // Trigger Windows Media Play/Pause (VK_MEDIA_PLAY_PAUSE 0xB3) for Fire TV Remote (BETA)
  ipcMain.handle('trigger-media-play-pause', async () => {
    return await triggerMediaPlayPause();
  });

  // Open external URL safely in default system browser
  ipcMain.handle('open-external-url', async (_event, url) => {
    if (typeof url !== 'string' || url.length > 2048) return false;
    try {
      const parsed = new URL(url);
      if (parsed.protocol === 'https:' || parsed.protocol === 'http:') {
        await shell.openExternal(parsed.toString());
        return true;
      }
    } catch (_) {}
    return false;
  });

  // Preferences: Minimize to tray on close
  ipcMain.handle('set-minimize-to-tray-pref', (_event, enabled) => {
    minimizeToTrayOnClose = Boolean(enabled);
    return true;
  });

  ipcMain.handle('get-minimize-to-tray-pref', () => {
    return minimizeToTrayOnClose;
  });

  // Preferences & Tools: Weak PC, Compatibility, Low RAM & Auto-Detection
  ipcMain.handle('get-performance-prefs', async () => {
    if (hardwareDetectionPromise) {
      try {
        await hardwareDetectionPromise;
      } catch (_) {}
    }
    return {
      ...perfPrefs,
      needsRestart: computeNeedsRestart(perfPrefs)
    };
  });

  ipcMain.handle('set-performance-prefs', async (_event, payload) => {
    const patch = payload && typeof payload === 'object' ? payload : {};
    const merged = { ...perfPrefs };
    if ('lowSpecMode' in patch) merged.lowSpecMode = Boolean(patch.lowSpecMode);
    if ('lowRamMode' in patch) merged.lowRamMode = Boolean(patch.lowRamMode);
    if ('compatMode' in patch) merged.compatMode = Boolean(patch.compatMode);
    if ('userCustomized' in patch) merged.userCustomized = Boolean(patch.userCustomized);
    if ('selectedPreset' in patch) merged.selectedPreset = isValidPreset(patch.selectedPreset) ? patch.selectedPreset : null;
    perfPrefs = savePerformancePrefs(merged);
    if (perfPrefs.lowRamMode) {
      await session.defaultSession.clearCache().catch(() => {});
    }
    return {
      ...perfPrefs,
      needsRestart: computeNeedsRestart(perfPrefs)
    };
  });

  ipcMain.handle('reapply-auto-performance', async () => {
    const updated = await detectHardwareProfile({ forceReapply: true });
    hardwareDetectionPromise = Promise.resolve(updated);
    if (updated.lowRamMode) {
      await session.defaultSession.clearCache().catch(() => {});
    }
    return {
      ...updated,
      needsRestart: computeNeedsRestart(updated)
    };
  });

  ipcMain.handle('relaunch-app', () => {
    app.relaunch();
    app.exit(0);
  });

  ipcMain.handle('get-memory-usage', () => {
    return getAppMemorySummary();
  });

  ipcMain.handle('trim-memory', async () => {
    if (!streamingState.isStreaming) {
      pendingBuffers = [];
      pendingBytes = 0;
    }
    await session.defaultSession.clearCache().catch(() => {});
    return getAppMemorySummary();
  });

  // Window Controls
  ipcMain.handle('window-minimize', () => {
    if (mainWindow) mainWindow.minimize();
  });

  ipcMain.handle('window-hide-to-tray', () => {
    if (mainWindow) mainWindow.hide();
  });

  ipcMain.handle('window-close', () => {
    if (mainWindow) {
      if (streamingState.isStreaming && minimizeToTrayOnClose) {
        mainWindow.hide();
      } else {
        mainWindow.close();
      }
    }
  });

  // Hostname
  ipcMain.handle('get-hostname', () => {
    return os.hostname() || 'Desktop Windows';
  });

  // Desktop Captures: Save Screenshot PNG (Pictures/CastViewer)
  ipcMain.handle('save-screenshot', async (_event, payload) => {
    try {
      const dataUrl = payload && typeof payload.dataUrl === 'string' ? payload.dataUrl : '';
      const match = dataUrl.match(/^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$/);
      if (!match || dataUrl.length > 32 * 1024 * 1024) {
        return { ok: false, error: 'Imagem inválida.' };
      }
      const ext = match[1] === 'jpeg' ? 'jpg' : match[1];
      const buffer = Buffer.from(match[2], 'base64');
      if (!buffer || buffer.length === 0) {
        return { ok: false, error: 'Imagem vazia.' };
      }

      const { screenshotsDir } = getCaptureDirs();
      await fs.promises.mkdir(screenshotsDir, { recursive: true });
      const fileName = `CastViewer_Print_${formatCaptureTimestamp()}.${ext}`;
      const filePath = path.join(screenshotsDir, fileName);
      await fs.promises.writeFile(filePath, buffer);
      return { ok: true, path: filePath, fileName, sizeBytes: buffer.length };
    } catch (err) {
      console.error('CastViewer: Erro ao salvar screenshot:', err);
      return { ok: false, error: err && err.message ? err.message : 'Erro ao gravar arquivo.' };
    }
  });

  // Desktop Captures: Save Replay Clip MP4/WebM (Videos/CastViewer)
  ipcMain.handle('save-replay-clip', async (_event, payload) => {
    try {
      const rawBuf = payload && payload.buffer;
      const mimeType = String((payload && payload.mimeType) || 'video/mp4').toLowerCase();
      const durationSec = Number((payload && payload.durationSec) || 0);
      if (!rawBuf) {
        return { ok: false, error: 'Buffer de vídeo ausente.' };
      }
      const buffer = Buffer.isBuffer(rawBuf) ? rawBuf : Buffer.from(rawBuf);
      if (!buffer || buffer.length === 0 || buffer.length > 180 * 1024 * 1024) {
        return { ok: false, error: 'Clipe vazio ou excede o limite.' };
      }

      const ext = mimeType.includes('mp4') ? 'mp4' : 'webm';
      const outputBuffer = ext === 'mp4' ? remuxReplayBufferToStandardMp4(buffer, mimeType) : buffer;
      const { clipsDir } = getCaptureDirs();
      await fs.promises.mkdir(clipsDir, { recursive: true });
      const fileName = `CastViewer_Clip_${formatCaptureTimestamp()}.${ext}`;
      const filePath = path.join(clipsDir, fileName);
      await fs.promises.writeFile(filePath, outputBuffer);
      return { ok: true, path: filePath, fileName, sizeBytes: outputBuffer.length, durationSec };
    } catch (err) {
      console.error('CastViewer: Erro ao salvar clip:', err);
      return { ok: false, error: err && err.message ? err.message : 'Erro ao salvar vídeo.' };
    }
  });

  // Desktop Captures: List Clips & Screenshots
  ipcMain.handle('list-captures', async () => {
    const { clipsDir, screenshotsDir } = getCaptureDirs();
    const scanDir = async (dirPath, kind, allowedExts) => {
      try {
        const entries = await fs.promises.readdir(dirPath, { withFileTypes: true });
        const statsList = await Promise.all(
          entries
            .filter((entry) => entry.isFile())
            .map(async (entry) => {
              const ext = path.extname(entry.name).toLowerCase().replace('.', '');
              if (!allowedExts.includes(ext)) return null;
              const fullPath = path.join(dirPath, entry.name);
              try {
                const st = await fs.promises.stat(fullPath);
                if (!st.size) return null;
                return {
                  id: fullPath,
                  kind,
                  fileName: entry.name,
                  filePath: fullPath,
                  fileUrl: pathToFileURL(fullPath).href,
                  sizeBytes: st.size,
                  createdAtMs: st.mtimeMs || st.birthtimeMs || 0,
                  ext
                };
              } catch (_) {
                return null;
              }
            })
        );
        return statsList.filter(Boolean);
      } catch (_) {
        return [];
      }
    };

    const [clips, screenshots] = await Promise.all([
      scanDir(clipsDir, 'clip', ['mp4', 'webm', 'mkv']),
      scanDir(screenshotsDir, 'screenshot', ['png', 'jpg', 'jpeg', 'webp'])
    ]);

    const items = [...clips, ...screenshots]
      .sort((a, b) => b.createdAtMs - a.createdAtMs)
      .slice(0, 60);

    return { items, clipsDir, screenshotsDir };
  });

  // Open Captures Folder in Explorer
  ipcMain.handle('open-capture-folder', async (_event, kind) => {
    const { clipsDir, screenshotsDir } = getCaptureDirs();
    const targetDir = kind === 'screenshots' ? screenshotsDir : clipsDir;
    await fs.promises.mkdir(targetDir, { recursive: true });
    const errMsg = await shell.openPath(targetDir);
    return { ok: !errMsg, path: targetDir, error: errMsg || '' };
  });

  // Reveal Specific Capture in Explorer
  ipcMain.handle('reveal-capture-file', (_event, filePath) => {
    const { clipsDir, screenshotsDir } = getCaptureDirs();
    if (!isPathInsideDirs(filePath, [clipsDir, screenshotsDir])) {
      return false;
    }
    shell.showItemInFolder(path.resolve(filePath));
    return true;
  });

  // Move Capture to Recycle Bin
  ipcMain.handle('delete-capture-file', async (_event, filePath) => {
    const { clipsDir, screenshotsDir } = getCaptureDirs();
    if (!isPathInsideDirs(filePath, [clipsDir, screenshotsDir])) {
      return false;
    }
    await shell.trashItem(path.resolve(filePath));
    return true;
  });

  // Incoming Takeover Approval Notification (Windows Notification + Taskbar Flash)
  let activeTakeoverNotification = null;

  ipcMain.handle('show-takeover-notification', (_event, payload) => {
    try {
      if (activeTakeoverNotification) {
        try { activeTakeoverNotification.close(); } catch (_) {}
        activeTakeoverNotification = null;
      }

      const requester = payload && typeof payload.requesterName === 'string' && payload.requesterName.trim()
        ? payload.requesterName.trim().slice(0, 64)
        : 'Outro dispositivo';
      const timeoutSec = Math.max(5, Math.min(60, Number((payload && payload.timeoutSec) || 15)));

      if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isFocused()) {
        mainWindow.flashFrame(true);
      }

      if (Notification && Notification.isSupported()) {
        const iconPath = path.join(__dirname, 'assets', 'icon.png');
        const notif = new Notification({
          title: `${requester} quer assumir a TV`,
          body: `Clique aqui para Aceitar ou Recusar (troca automática em ${timeoutSec}s).`,
          icon: iconPath,
          urgency: 'critical'
        });
        notif.on('click', () => {
          if (mainWindow && !mainWindow.isDestroyed()) {
            if (!mainWindow.isVisible()) mainWindow.show();
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.focus();
            mainWindow.flashFrame(false);
          }
        });
        notif.on('close', () => {
          if (activeTakeoverNotification === notif) {
            activeTakeoverNotification = null;
          }
        });
        notif.show();
        activeTakeoverNotification = notif;
        return true;
      }
    } catch (err) {
      console.warn('CastViewer: Falha ao exibir notificação de takeover:', err && err.message);
    }
    return false;
  });

  ipcMain.handle('clear-takeover-notification', () => {
    try {
      if (activeTakeoverNotification) {
        activeTakeoverNotification.close();
        activeTakeoverNotification = null;
      }
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.flashFrame(false);
      }
    } catch (_) {}
    return true;
  });
}

// App Lifecycle
app.whenReady().then(() => {
  setupDisplayMediaHandler();
  setupIpc();
  setupBonjour();
  createWindow();
  createTray();
  hardwareDetectionPromise = detectHardwareProfile({ forceReapply: false }).catch((err) => {
    console.warn('CastViewer: Falha ao detectar hardware:', err && err.message);
    return perfPrefs;
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    } else if (mainWindow) {
      mainWindow.show();
    }
  });
});

app.on('before-quit', () => {
  app.isQuitting = true;
  if (loopbackCaptureInstance) {
    try { loopbackCaptureInstance.stop(); } catch (_) {}
    loopbackCaptureInstance = null;
  }
  restoreSpeakerMuteSync();
  if (bonjourBrowser) {
    try {
      bonjourBrowser.stop();
    } catch (_) {}
  }
  if (bonjour) {
    try {
      bonjour.destroy();
    } catch (_) {}
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
