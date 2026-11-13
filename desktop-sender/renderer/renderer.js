/**
 * CastViewer — Desktop Sender
 * Renderer Process: 2-Tab Studio UI, WebRTC Ultra-Low Latency, Audio Loopback & Web Audio API VU Meter
 */

// Application State
let discoveredDevices = [];
let manualDevices = [];
let selectedDeviceId = null;

let captureSources = [];
let activeSourceTab = 'screens'; // 'screens' | 'windows'
let selectedSourceId = null;

let isTransmitting = false;
let isConnecting = false;
let isStreamPaused = false;
let audioCaptureEnabled = true;

// BETA: Image Mode ('detail' = Nitidez / Texto / Apps, 'motion' = Movimento / Vídeos / Jogos)
let liveImageMode = localStorage.getItem('castviewer_image_mode') || 'motion';
let liveLatencyMode = localStorage.getItem('castviewer_latency_mode') || 'ultra';
let autoReconnectPref = localStorage.getItem('castviewer_auto_reconnect') !== 'false';
let remotePauseMediaKeyPref = localStorage.getItem('castviewer_remote_pause') !== 'false';
let replayBufferEnabled = localStorage.getItem('castviewer_replay_buffer') === 'true';
function normalizeClockCorner(value) {
  const norm = String(value || '').trim().toLowerCase();
  return norm === 'tl' || norm === 'tr' || norm === 'bl' || norm === 'br' ? norm : 'br';
}
let streamClockEnabled = localStorage.getItem('castviewer_stream_clock') === 'true';
let streamClockCorner = normalizeClockCorner(localStorage.getItem('castviewer_stream_clock_corner'));
let replayRecorder = null;
let replayClonedVideoTrack = null;
let replayClonedAudioTrack = null;
let isSavingReplayClip = false;
let isSavingScreenshot = false;
let capturesCache = [];
let captureFilterMode = 'all';
let activeDialogCapturePath = null;

// Weak PC, Compatibility, Low RAM & Auto-Detected Hardware Preferences
let lowSpecMode = localStorage.getItem('castviewer_low_spec_mode') === 'true';
let lowRamMode = localStorage.getItem('castviewer_low_ram_mode') === 'true';
let compatMode = localStorage.getItem('castviewer_compat_mode') === 'true';
let perfUserCustomized = false;
let perfNeedsRestart = false;
let detectedHardwareProfile = null;
let autoDetectedReason = '';
let autoRecommendedPreset = '1080p60';
let isVuBoxSkipped = false;
let memoryPollInterval = null;
let zeroFramesStreak = 0;
let zeroFramesWarnedThisSession = false;

let ws = null;
let peerConnection = null;
let localStream = null;
let statsInterval = null;
let pendingRemoteCandidates = [];

let prevBytesSent = 0;
let prevStatsTimestamp = 0;
let prevPacketsSent = 0;
let prevPacketsLost = 0;
let pcHostname = 'Desktop';
let systemCapabilities = {
  platform: 'win32',
  isWin: true,
  isMac: false,
  isLinux: false,
  isWayland: false,
  hasNativeLoopback: true,
  screenPermission: 'granted'
};
let usingDisplayMediaAudioLoopback = false;

// Native Audio (loopback-capture + pcm-worklet) & Notebook Speaker Mute State
let audioCtx = null;
let pcmWorkletNode = null;
let mediaStreamDest = null;
let pcmChunkUnsub = null;
let liveAudioTrack = null;
let isProcessAudioRunning = false;
let isNotebookMuted = false;

// Web Audio API VU Meter State
let analyserNode = null;
let vuAnimationFrame = null;
let testToneOscillator = null;
let isTestingSound = false;

// Quality Presets
const PRESETS = {
  '720p60': {
    name: 'Ultra Baixo Delay',
    width: 1280,
    height: 720,
    fps: 60,
    bitrate: 10_000_000,
    label: '720p • 60 FPS'
  },
  '1080p60': {
    name: 'Full HD Fluido',
    width: 1920,
    height: 1080,
    fps: 60,
    bitrate: 16_000_000,
    label: '1080p • 60 FPS'
  },
  '1080p30': {
    name: 'Cinema',
    width: 1920,
    height: 1080,
    fps: 30,
    bitrate: 8_000_000,
    label: '1080p • 30 FPS'
  },
  '720p30': {
    name: 'PC Fraco (Ultra Leve)',
    width: 1280,
    height: 720,
    fps: 30,
    bitrate: 5_000_000,
    label: '720p • 30 FPS'
  }
};

// Safe bridge API getter (supports window.castviewer and window.firecast alias)
function getApi() {
  return window.castviewer || window.firecast || {};
}

// DOM Elements Cache
const tabBtnStream = document.getElementById('tab-btn-stream');
const tabBtnCaptures = document.getElementById('tab-btn-captures');
const capturesTabCount = document.getElementById('captures-tab-count');
const tabBtnSettings = document.getElementById('tab-btn-settings');
const panelStream = document.getElementById('panel-stream');
const panelCaptures = document.getElementById('panel-captures');
const panelSettings = document.getElementById('panel-settings');

const statusPill = document.getElementById('status-pill');
const statusPillText = document.getElementById('status-pill-text');

const btnMin = document.getElementById('btn-min');
const btnClose = document.getElementById('btn-close');
const btnTrayMin = document.getElementById('btn-tray-min');

const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toast-message');

const tvCountBadge = document.getElementById('tv-count-badge');
const tvCountText = document.getElementById('tv-count-text');
const btnRefreshDevices = document.getElementById('btn-refresh-devices');
const refreshTvIcon = document.getElementById('refresh-tv-icon');
const btnToggleManual = document.getElementById('btn-toggle-manual');
const manualIpBar = document.getElementById('manual-ip-bar');
const inputManualIp = document.getElementById('input-manual-ip');
const inputManualPort = document.getElementById('input-manual-port');
const btnAddManual = document.getElementById('btn-add-manual');
const devicesRow = document.getElementById('devices-row');

// BETA: Last TV quick reconnect banner elements
const lastTvBanner = document.getElementById('last-tv-banner');
const lastTvName = document.getElementById('last-tv-name');
const btnQuickReconnect = document.getElementById('btn-quick-reconnect');

// Busy Session Takeover Banner & Incoming Takeover Approval Prompt elements
const busyTakeoverBanner = document.getElementById('busy-takeover-banner');
const busyTakeoverText = document.getElementById('busy-takeover-text');
const btnForceTakeover = document.getElementById('btn-force-takeover');
const btnDismissTakeover = document.getElementById('btn-dismiss-takeover');
let pendingBusyDevice = null;
let acceptedOutgoingTakeoverTarget = null;

const takeoverRequestBanner = document.getElementById('takeover-request-banner');
const takeoverRequestTimer = document.getElementById('takeover-request-timer');
const takeoverRequestTitle = document.getElementById('takeover-request-title');
const takeoverRequestDesc = document.getElementById('takeover-request-desc');
const btnAcceptTakeover = document.getElementById('btn-accept-takeover');
const btnDenyTakeover = document.getElementById('btn-deny-takeover');
let pendingIncomingTakeover = null;
let takeoverCountdownTimer = null;

const tabSourcesScreens = document.getElementById('tab-sources-screens');
const tabSourcesWindows = document.getElementById('tab-sources-windows');
const btnRefreshSources = document.getElementById('btn-refresh-sources');
const sourcesGrid = document.getElementById('sources-grid');

const telemetryBar = document.getElementById('telemetry-bar');
const telemetryRtt = document.getElementById('telemetry-rtt');
const telemetryJitter = document.getElementById('telemetry-jitter');
const telemetryLoss = document.getElementById('telemetry-loss');
const telemetryFps = document.getElementById('telemetry-fps');
const telemetryBitrate = document.getElementById('telemetry-bitrate');
const telemetryResolution = document.getElementById('telemetry-resolution');

const btnQuickAudio = document.getElementById('btn-quick-audio');
const btnDockMuteSpeaker = document.getElementById('btn-dock-mute-speaker');
const btnDockPauseStream = document.getElementById('btn-dock-pause-stream');
const btnDockScreenshot = document.getElementById('btn-dock-screenshot');
const btnDockClip30s = document.getElementById('btn-dock-clip-30s');
const btnDockClipText = document.getElementById('btn-dock-clip-text');
const streamDockToolbar = document.getElementById('stream-dock-toolbar');
const dockStatusPill = document.getElementById('dock-status-pill');
const dockRttFpsText = document.getElementById('dock-rtt-fps-text');
const btnAction = document.getElementById('btn-cast-action') || document.getElementById('btn-action');
const btnActionText = document.getElementById('btn-action-text');

// Capturas Gallery Elements
const capturesGrid = document.getElementById('captures-grid');
const capturesReplayBadge = document.getElementById('captures-replay-badge');
const capturesToolbar = document.querySelector('.captures-toolbar');
const btnFilterAll = document.getElementById('btn-filter-all');
const btnFilterClips = document.getElementById('btn-filter-clips');
const btnFilterPrints = document.getElementById('btn-filter-prints');
const btnOpenClipsFolder = document.getElementById('btn-open-clips-folder');
const btnOpenPrintsFolder = document.getElementById('btn-open-prints-folder');
const btnRefreshCaptures = document.getElementById('btn-refresh-captures');
const capturePreviewDialog = document.getElementById('capture-preview-dialog');
const capturePreviewTitle = document.getElementById('capture-preview-title');
const capturePreviewStage = document.getElementById('capture-preview-stage');
const capturePreviewMeta = document.getElementById('capture-preview-meta');
const btnDialogReveal = document.getElementById('btn-dialog-reveal');

// Settings Elements
const toggleAudioSettings = document.getElementById('toggle-audio-settings');
const toggleMuteSpeakerSettings = document.getElementById('toggle-mute-speaker-settings');
const vuStatusBar = document.getElementById('vu-status-text');
const vuDbText = document.getElementById('vu-db-text');
const vuBarLeft = document.getElementById('vu-bar-left');
const vuBarRight = document.getElementById('vu-bar-right');
const btnVuTest = document.getElementById('btn-vu-test');
const btnVuTestText = document.getElementById('btn-vu-test-text');

// BETA: Image Mode, Latency Mode & Advanced Telemetry elements
const btnModeDetail = document.getElementById('btn-mode-detail');
const btnModeMotion = document.getElementById('btn-mode-motion');
const btnLatencyUltra = document.getElementById('btn-latency-ultra');
const btnLatencyBalanced = document.getElementById('btn-latency-balanced');
const advTelemetryIdle = document.getElementById('adv-telemetry-idle');
const advTelemetryGrid = document.getElementById('adv-telemetry-grid');
const advRtt = document.getElementById('adv-rtt');
const advJitter = document.getElementById('adv-jitter');
const advLoss = document.getElementById('adv-loss');
const advLossPackets = document.getElementById('adv-loss-packets');
const advFps = document.getElementById('adv-fps');
const advTargetFps = document.getElementById('adv-target-fps');
const advBitrate = document.getElementById('adv-bitrate');
const advResolution = document.getElementById('adv-resolution');
const advCodec = document.getElementById('adv-codec');
const advCodecSub = document.getElementById('adv-codec-sub');
const advMode = document.getElementById('adv-mode');
const advHint = document.getElementById('adv-hint');

const pausedStreamOverlay = document.getElementById('paused-stream-overlay');
const pausedOverlayKicker = document.getElementById('paused-overlay-kicker');
const pausedOverlayTitle = document.getElementById('paused-overlay-title');
const pausedOverlaySub = document.getElementById('paused-overlay-sub');
const btnPausedResume = document.getElementById('btn-paused-resume');
const btnPausedResumeText = document.getElementById('btn-paused-resume-text');
const btnPausedStop = document.getElementById('btn-paused-stop');
const srLiveRegion = document.getElementById('sr-live-region');

const toggleAutoReconnect = document.getElementById('toggle-auto-reconnect');
const toggleRemotePause = document.getElementById('toggle-remote-pause');
const toggleMinimizeTray = document.getElementById('toggle-minimize-tray');
const toggleReplayBuffer = document.getElementById('toggle-replay-buffer');
const toggleStreamClock = document.getElementById('toggle-stream-clock');
const streamClockCornerGroup = document.getElementById('stream-clock-corner-group');
const streamClockCornerRadios = document.querySelectorAll('input[name="stream-clock-corner"]');
const systemHostname = document.getElementById('system-hostname');
const vuMeterBox = document.querySelector('.vu-meter-box');

// Weak PC, Compatibility & Low RAM UI Elements
const toggleLowSpecMode = document.getElementById('toggle-low-spec-mode');
const toggleLowRamMode = document.getElementById('toggle-low-ram-mode');
const toggleCompatMode = document.getElementById('toggle-compat-mode');
const perfHwSummary = document.getElementById('perf-hw-summary');
const perfHwReason = document.getElementById('perf-hw-reason');
const perfAutoBadge = document.getElementById('perf-auto-badge');
const btnAutoDetectPerf = document.getElementById('btn-auto-detect-perf');
const btnRestartPerf = document.getElementById('btn-restart-perf');
const perfRamReadout = document.getElementById('perf-ram-readout');
const perfModeSummary = document.getElementById('perf-mode-summary');
const btnTrimMemory = document.getElementById('btn-trim-memory');

// Initialization
document.addEventListener('DOMContentLoaded', async () => {
  applyLowSpecVisualClass();
  playDesktopCinematicIntro();
  setupNavigationTabs();
  setupWindowControls();
  setupDiscoveryUI();
  setupSourcesUI();
  setupPresetsUI();
  setupAudioUI();
  setupActionUI();
  setupBetaFeaturesUI();
  setupPerformanceUI();
  setupPreferencesUI();
  setupCapturesUI();
  setupTakeoverApprovalUI();

  // Render immediate initial UI state before async IPC completes so panels are never blank
  renderDevicesList();
  updateLastTvUI();
  updateCaptureToolbarState();
  renderInitialSourcesLoadingState();

  const api = getApi();

  // Listen for Bonjour mDNS updates immediately
  if (api.onDevicesUpdated) {
    api.onDevicesUpdated((devices) => {
      discoveredDevices = devices || [];
      renderDevicesList();
      updateLastTvUI();
    });
  }

  // Listen for tray triggers (Stop, Clip 30s, Screenshot)
  if (api.onTrayStopCast) {
    api.onTrayStopCast(() => {
      if (isTransmitting || isConnecting) {
        stopTransmission('Transmissão encerrada pela bandeja do sistema.');
      }
    });
  }
  if (api.onTraySaveClip) {
    api.onTraySaveClip(() => {
      saveReplayClip30s();
    });
  }
  if (api.onTraySaveScreenshot) {
    api.onTraySaveScreenshot(() => {
      captureLiveScreenshot();
    });
  }

  // Run device discovery, source enumeration, capture gallery, and pref sync in parallel
  const perfSyncTask = (async () => {
    try {
      if (api.getPerformancePrefs) {
        const hasLegacyLocalPerf =
          localStorage.getItem('castviewer_low_spec_mode') !== null ||
          localStorage.getItem('castviewer_low_ram_mode') !== null;
        const hasExistingPreset = localStorage.getItem('castviewer_video_preset') !== null;
        const userExplicitPreset = localStorage.getItem('castviewer_preset_user_set') === 'true';
        const mainPrefs = await api.getPerformancePrefs();
        const shouldPreserveLegacy =
          Boolean(mainPrefs && !mainPrefs.autoConfigured) &&
          !userExplicitPreset &&
          (hasLegacyLocalPerf || hasExistingPreset);
        if (mainPrefs) {
          detectedHardwareProfile = mainPrefs.detectedHardware || null;
          autoRecommendedPreset = mainPrefs.recommendedPreset || '1080p60';
          autoDetectedReason = mainPrefs.autoReason || '';
          perfUserCustomized = Boolean(mainPrefs.userCustomized);
          perfNeedsRestart = Boolean(mainPrefs.needsRestart);

          if (mainPrefs.userCustomized || !shouldPreserveLegacy) {
            lowSpecMode = Boolean(mainPrefs.lowSpecMode);
            lowRamMode = Boolean(mainPrefs.lowRamMode);
            compatMode = Boolean(mainPrefs.compatMode);
            localStorage.setItem('castviewer_low_spec_mode', String(lowSpecMode));
            localStorage.setItem('castviewer_low_ram_mode', String(lowRamMode));
            localStorage.setItem('castviewer_compat_mode', String(compatMode));
          } else if (api.setPerformancePrefs) {
            const synced = await api.setPerformancePrefs({
              lowSpecMode,
              lowRamMode,
              compatMode,
              userCustomized: true
            });
            if (synced) {
              perfUserCustomized = Boolean(synced.userCustomized);
              perfNeedsRestart = Boolean(synced.needsRestart);
            }
          }

          if (mainPrefs.userCustomized && mainPrefs.selectedPreset && PRESETS[mainPrefs.selectedPreset]) {
            applySelectedPresetDom(mainPrefs.selectedPreset);
            localStorage.setItem('castviewer_video_preset', mainPrefs.selectedPreset);
            localStorage.setItem('castviewer_preset_user_set', 'true');
          } else if (!hasExistingPreset && !userExplicitPreset && !mainPrefs.userCustomized && PRESETS[autoRecommendedPreset]) {
            applySelectedPresetDom(autoRecommendedPreset);
            localStorage.setItem('castviewer_video_preset', autoRecommendedPreset);

            if (!localStorage.getItem('castviewer_autodetect_noticed')) {
              localStorage.setItem('castviewer_autodetect_noticed', 'true');
              const presetName = PRESETS[autoRecommendedPreset].name;
              const gpuLabel = (detectedHardwareProfile && detectedHardwareProfile.gpuSummary) || 'PC';
              showToast(`PC identificado (${gpuLabel}): perfil inicial ${presetName}.`, 'info');
            }
          }

          applyLowSpecVisualClass();
          syncVuMeterLifecycle();
          startSourcesRefreshLoop();
          syncPerformanceSwitchesUI();
        }
      }
    } catch (_) {}
  })();

  const capsTask = (async () => {
    try {
      if (api.getSystemCapabilities) {
        const caps = await api.getSystemCapabilities();
        if (caps && typeof caps === 'object') {
          systemCapabilities = { ...systemCapabilities, ...caps };
        }
      }
    } catch (_) {}
  })();

  const hostnameTask = (async () => {
    try {
      if (api.getHostname) {
        pcHostname = await api.getHostname();
        if (systemHostname) {
          systemHostname.textContent = pcHostname;
        }
      }
    } catch (_) {}
  })();

  const trayPrefTask = (async () => {
    try {
      if (api.getMinimizeToTrayPref && toggleMinimizeTray) {
        const pref = await api.getMinimizeToTrayPref();
        toggleMinimizeTray.checked = pref !== false;
      }
    } catch (_) {}
  })();

  const muteStateTask = (async () => {
    try {
      if (api.getSpeakerMute) {
        isNotebookMuted = await api.getSpeakerMute();
        updateNotebookSpeakerMuteUI();
      }
    } catch (_) {}
  })();

  await capsTask;
  const initialSourcesTask = (async () => {
    await refreshSources();
    if (captureSources.length === 0 && !systemCapabilities.isWayland) {
      setTimeout(() => {
        if (!isTransmitting && !isConnecting && captureSources.length === 0) {
          refreshSources();
        }
      }, 900);
    }
  })();

  if (systemCapabilities.isMac && systemCapabilities.screenPermission && systemCapabilities.screenPermission !== 'granted') {
    showToast('No macOS, autorize Gravação de Tela em Ajustes do Sistema > Privacidade e Segurança.', 'info');
  }

  startSourcesRefreshLoop();
  await Promise.allSettled([
    refreshDevices(),
    initialSourcesTask,
    refreshCapturesList(),
    perfSyncTask,
    hostnameTask,
    trayPrefTask,
    muteStateTask
  ]);
});

// Screen Reader Live Announcement Helper (ariaNotify with ARIA live-region fallback)
function announceStatus(message) {
  if (!message) return;
  try {
    if (typeof navigator.ariaNotify === 'function') {
      navigator.ariaNotify(message);
    }
  } catch (_) {}
  if (srLiveRegion) {
    srLiveRegion.textContent = '';
    requestAnimationFrame(() => {
      srLiveRegion.textContent = message;
    });
  }
}

// Toast Helper
let toastTimer = null;
function showToast(message, type = 'info') {
  if (toastTimer) clearTimeout(toastTimer);
  if (!toast || !toastMessage) return;
  toastMessage.textContent = message;
  toast.className = `toast ${type}`;
  announceStatus(message);
  toastTimer = setTimeout(() => {
    toast.className = 'toast hidden';
  }, 4000);
}

// 2-Tab Navigation & RAM Optimization (pause source refresh on hidden or Ajustes tab)
let sourcesRefreshInterval = null;
let currentNavTab = 'stream';

function shouldRefreshSources() {
  if (systemCapabilities && systemCapabilities.isWayland) {
    return false;
  }
  return !document.hidden && currentNavTab === 'stream';
}

function startSourcesRefreshLoop() {
  if (sourcesRefreshInterval) clearInterval(sourcesRefreshInterval);
  const pollMs = lowRamMode ? 30000 : 10000;
  sourcesRefreshInterval = setInterval(async () => {
    if (shouldRefreshSources() && !isTransmitting && !isConnecting) {
      await refreshSources();
    }
  }, pollMs);
}

// Listen for tab/window visibility change to pause background captures, VU RAF & polling
document.addEventListener('visibilitychange', () => {
  if (shouldRefreshSources() && !isTransmitting && !isConnecting) {
    refreshSources();
  }
  syncVuMeterLifecycle();
  syncMemoryPollLifecycle();
});

function setupNavigationTabs() {
  const tabPairs = [
    { key: 'stream', btn: tabBtnStream },
    { key: 'captures', btn: tabBtnCaptures },
    { key: 'settings', btn: tabBtnSettings }
  ].filter((t) => Boolean(t.btn));

  tabPairs.forEach((entry, idx) => {
    entry.btn.addEventListener('click', () => switchTab(entry.key));
    entry.btn.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft' && e.key !== 'Home' && e.key !== 'End') return;
      e.preventDefault();
      let nextIdx = idx;
      if (e.key === 'ArrowRight') nextIdx = (idx + 1) % tabPairs.length;
      else if (e.key === 'ArrowLeft') nextIdx = (idx - 1 + tabPairs.length) % tabPairs.length;
      else if (e.key === 'Home') nextIdx = 0;
      else if (e.key === 'End') nextIdx = tabPairs.length - 1;
      const target = tabPairs[nextIdx];
      target.btn.focus();
      switchTab(target.key);
    });
  });
}

function switchTab(tab) {
  if (currentNavTab === tab) return;
  currentNavTab = tab;

  const tabsMap = {
    stream: { btn: tabBtnStream, panel: panelStream },
    captures: { btn: tabBtnCaptures, panel: panelCaptures },
    settings: { btn: tabBtnSettings, panel: panelSettings }
  };

  const applyTabDOM = () => {
    Object.entries(tabsMap).forEach(([key, { btn, panel }]) => {
      const selected = key === tab;
      if (btn) {
        btn.classList.toggle('active', selected);
        btn.setAttribute('aria-selected', String(selected));
      }
      if (panel) {
        panel.hidden = !selected;
        panel.classList.toggle('hidden', !selected);
        panel.classList.toggle('active', selected);
      }
    });

    if (tab === 'stream' && shouldRefreshSources() && !isTransmitting && !isConnecting) {
      refreshSources();
    } else if (tab === 'captures') {
      refreshCapturesList();
    } else if (tab === 'settings') {
      updateMemoryUsageDisplay();
    }
    syncVuMeterLifecycle();
    syncMemoryPollLifecycle();
  };

  const targetPanel = (tabsMap[tab] && tabsMap[tab].panel) || panelStream;
  if (!document.startViewTransition || prefersReducedMotion()) {
    applyTabDOM();
    targetPanel?.focus({ preventScroll: true });
    return;
  }

  const transition = document.startViewTransition(() => {
    applyTabDOM();
  });
  transition.finished.finally(() => {
    targetPanel?.focus({ preventScroll: true });
  });
}

// Window Controls
function setupWindowControls() {
  const api = getApi();
  if (btnMin && api.minimizeWindow) {
    btnMin.addEventListener('click', () => api.minimizeWindow());
  }
  if (btnTrayMin && api.hideToTray) {
    btnTrayMin.addEventListener('click', () => api.hideToTray());
  }
  if (btnClose && api.closeWindow) {
    btnClose.addEventListener('click', () => api.closeWindow());
  }
}

// Update Titlebar Status Pill
function updateStatusPill(state, text) {
  if (!statusPill || !statusPillText) return;
  statusPill.className = `status-pill ${state}`;
  statusPillText.textContent = text;
}

// Device Discovery UI
function setupDiscoveryUI() {
  if (btnRefreshDevices) {
    btnRefreshDevices.addEventListener('click', async () => {
      btnRefreshDevices.classList.add('spinning');
      await refreshDevices();
      setTimeout(() => btnRefreshDevices.classList.remove('spinning'), 800);
    });
  }

  if (btnToggleManual) {
    btnToggleManual.addEventListener('click', () => {
      const isHidden = manualIpBar.classList.toggle('hidden');
      btnToggleManual.classList.toggle('active', !isHidden);
      btnToggleManual.setAttribute('aria-expanded', String(!isHidden));
      if (!isHidden && inputManualIp) {
        inputManualIp.focus();
      }
    });
  }

  if (btnAddManual) {
    btnAddManual.addEventListener('click', () => {
      const ip = (inputManualIp.value || '').trim();
      const portRaw = inputManualPort.value ? parseInt(inputManualPort.value, 10) : 8765;
      const port = Number.isInteger(portRaw) && portRaw >= 1 && portRaw <= 65535 ? portRaw : null;

      const isPrivateLanIpv4 = (addr) => {
        if (!addr || !/^(\d{1,3}\.){3}\d{1,3}$/.test(addr)) return false;
        const octets = addr.split('.').map(Number);
        if (octets.some((o) => Number.isNaN(o) || o < 0 || o > 255)) return false;
        const [a, b] = octets;
        return (
          a === 10 ||
          (a === 192 && b === 168) ||
          (a === 172 && b >= 16 && b <= 31) ||
          (a === 169 && b === 254) ||
          a === 127
        );
      };

      if (!isPrivateLanIpv4(ip) || !port) {
        showToast('Informe um IP local IPv4 válido (ex: 192.168.1.100) e porta 1-65535.', 'error');
        return;
      }

      const deviceId = `${ip}:${port}`;
      const existing = manualDevices.find((d) => d.id === deviceId);
      if (!existing) {
        manualDevices.push({
          id: deviceId,
          name: `Fire TV (${ip})`,
          host: ip,
          ip: ip,
          port: port,
          version: '1',
          manual: true
        });
      }

      selectedDeviceId = deviceId;
      manualIpBar.classList.add('hidden');
      btnToggleManual.classList.remove('active');
      btnToggleManual.setAttribute('aria-expanded', 'false');
      renderDevicesList();
      updateActionButtonState();
      showToast(`Fire TV ${ip}:${port} selecionado!`, 'success');
    });
  }

  // Quick reconnect button click
  if (btnQuickReconnect) {
    btnQuickReconnect.addEventListener('click', async () => {
      const last = getLastTv();
      if (!last || !last.ip) return;

      const deviceId = last.id || `${last.ip}:${last.port || 8765}`;
      const all = [...manualDevices, ...discoveredDevices];
      let targetDev = all.find((d) => d.id === deviceId || d.ip === last.ip);

      if (!targetDev) {
        targetDev = {
          id: deviceId,
          name: last.name || `Fire TV (${last.ip})`,
          host: last.ip,
          ip: last.ip,
          port: last.port || 8765,
          version: '1',
          manual: true
        };
        manualDevices.push(targetDev);
      }

      selectedDeviceId = targetDev.id;
      renderDevicesList();
      updateActionButtonState();

      if (!isTransmitting && !isConnecting && selectedSourceId) {
        await startTransmission();
      } else {
        showToast(`${targetDev.name} selecionado. Clique em Iniciar Transmissão!`, 'info');
      }
    });
  }

  // Busy session takeover banner actions
  if (btnForceTakeover) {
    btnForceTakeover.addEventListener('click', async () => {
      if (isTransmitting || isConnecting) return;
      const targetDev = pendingBusyDevice || getSelectedDevice();
      if (targetDev) {
        selectedDeviceId = targetDev.id;
        renderDevicesList();
        updateActionButtonState();
      }
      hideBusyTakeoverBanner();
      await startTransmission(true);
    });
  }
  if (btnDismissTakeover) {
    btnDismissTakeover.addEventListener('click', () => {
      hideBusyTakeoverBanner();
    });
  }
}

function showBusyTakeoverBanner(device, activeSender, detailMessage) {
  const payload = window.CastTakeoverState
    ? window.CastTakeoverState.buildTakeoverBannerPayload(
        device || getSelectedDevice(),
        activeSender,
        detailMessage
      )
    : {
        device: device || getSelectedDevice(),
        activeSender: activeSender || 'outro dispositivo',
        message:
          detailMessage ||
          `TV ocupada por outro dispositivo${activeSender ? ` (${activeSender})` : ''}. Deseja assumir o controle?`
      };
  pendingBusyDevice = payload.device;
  if (busyTakeoverText) {
    busyTakeoverText.textContent = payload.message;
  }
  if (busyTakeoverBanner) {
    busyTakeoverBanner.classList.remove('hidden');
  }
  updateActionButtonState();
  announceStatus(`${payload.message} Ações disponíveis`);
}

function hideBusyTakeoverBanner() {
  pendingBusyDevice = null;
  if (busyTakeoverBanner) {
    busyTakeoverBanner.classList.add('hidden');
  }
  updateActionButtonState();
}

function handleSignalingBusyOrPreempted(endAction) {
  if (!endAction || !endAction.banner) return;
  acceptedOutgoingTakeoverTarget = null;
  hideTakeoverRequestBanner();
  stopTransmission(endAction.toastMessage, Boolean(endAction.isError), true);
  if (endAction.banner.device) {
    showBusyTakeoverBanner(
      endAction.banner.device,
      endAction.banner.activeSender,
      endAction.banner.message
    );
  }
}

function setupTakeoverApprovalUI() {
  if (btnAcceptTakeover) {
    btnAcceptTakeover.addEventListener('click', () => {
      respondToTakeoverRequest(true);
    });
  }
  if (btnDenyTakeover) {
    btnDenyTakeover.addEventListener('click', () => {
      respondToTakeoverRequest(false);
    });
  }
}

function showTakeoverRequestBanner({ requestId, requesterName, timeoutSec, message }) {
  if (!requestId) return;
  hideTakeoverRequestBanner();

  const sec = Math.max(5, Math.min(60, Number(timeoutSec) || 15));
  const cleanRequester = requesterName || 'Outro dispositivo';
  const deadlineMs = Date.now() + sec * 1000;
  pendingIncomingTakeover = { requestId, requesterName: cleanRequester, deadlineMs };

  if (takeoverRequestTitle) {
    takeoverRequestTitle.textContent = `${cleanRequester} quer assumir a TV`;
  }
  if (takeoverRequestDesc) {
    takeoverRequestDesc.textContent =
      message || 'Aceite para encerrar sua sessão atual ou recuse para continuar na TV (troca automática sem resposta).';
  }
  if (takeoverRequestBanner) {
    takeoverRequestBanner.classList.remove('hidden');
  }

  const updateTick = () => {
    const remainingSec = Math.max(0, Math.ceil((deadlineMs - Date.now()) / 1000));
    if (takeoverRequestTimer) {
      takeoverRequestTimer.textContent = `${remainingSec}s`;
    }
    if (remainingSec <= 0 && takeoverCountdownTimer) {
      clearInterval(takeoverCountdownTimer);
      takeoverCountdownTimer = null;
    }
  };
  updateTick();
  takeoverCountdownTimer = setInterval(updateTick, 500);

  const api = getApi();
  if (api.showTakeoverNotification) {
    api.showTakeoverNotification({
      requestId,
      requesterName: cleanRequester,
      timeoutSec: sec
    }).catch(() => {});
  }
  announceStatus(`${cleanRequester} quer assumir a TV. Aceite ou recuse em até ${sec} segundos.`);
}

function hideTakeoverRequestBanner(expectedRequestId = null) {
  if (expectedRequestId && pendingIncomingTakeover && pendingIncomingTakeover.requestId !== expectedRequestId) {
    return;
  }
  pendingIncomingTakeover = null;
  if (takeoverCountdownTimer) {
    clearInterval(takeoverCountdownTimer);
    takeoverCountdownTimer = null;
  }
  if (takeoverRequestBanner) {
    takeoverRequestBanner.classList.add('hidden');
  }
  const api = getApi();
  if (api.clearTakeoverNotification) {
    api.clearTakeoverNotification().catch(() => {});
  }
}

function respondToTakeoverRequest(accepted) {
  const req = pendingIncomingTakeover;
  if (!req) return;
  hideTakeoverRequestBanner();

  if (accepted) {
    acceptedOutgoingTakeoverTarget = {
      device: getSelectedDevice(),
      by: req.requesterName || 'Outro dispositivo'
    };
  } else {
    acceptedOutgoingTakeoverTarget = null;
  }

  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify({
        type: 'takeover-response',
        requestId: req.requestId,
        accepted: Boolean(accepted)
      }));
    } catch (_) {}
  }

  if (accepted) {
    showToast(`Transferindo TV para ${req.requesterName}...`, 'info');
  } else {
    showToast(`Solicitação de ${req.requesterName} recusada. Transmissão mantida.`, 'info');
  }
}

async function refreshDevices() {
  const api = getApi();
  try {
    if (api.refreshDevices) {
      const devices = await api.refreshDevices();
      discoveredDevices = devices || [];
      renderDevicesList();
      updateLastTvUI();
    }
  } catch (err) {
    console.error('CastViewer: Erro ao buscar dispositivos:', err);
  }
}

function renderDevicesList() {
  const allDevices = [...manualDevices, ...discoveredDevices];

  // Remove duplicates by id
  const uniqueMap = new Map();
  allDevices.forEach((d) => uniqueMap.set(d.id, d));
  const deviceList = Array.from(uniqueMap.values());

  // Update badge count
  if (deviceList.length > 0) {
    if (tvCountText) tvCountText.textContent = `${deviceList.length} encontrado(s)`;
    if (tvCountBadge) tvCountBadge.className = 'badge-tag found';
  } else {
    if (tvCountText) tvCountText.textContent = 'Buscando na rede...';
    if (tvCountBadge) tvCountBadge.className = 'badge-tag searching';
  }

  // Auto-reconnect: if auto-select is enabled and no TV selected yet, check last TV
  if (!selectedDeviceId && deviceList.length > 0) {
    const last = getLastTv();
    if (autoReconnectPref && last) {
      const match = deviceList.find((d) => d.id === last.id || d.ip === last.ip);
      if (match) {
        selectedDeviceId = match.id;
      } else {
        selectedDeviceId = deviceList[0].id;
      }
    } else {
      selectedDeviceId = deviceList[0].id;
    }
  }

  if (!devicesRow) return;
  devicesRow.innerHTML = '';

  if (deviceList.length === 0) {
    const emptyDiv = document.createElement('div');
    emptyDiv.className = 'devices-empty-pill';
    emptyDiv.innerHTML = `
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <circle cx="12" cy="12" r="10" stroke-opacity="0.25"/>
        <path d="M12 2a10 10 0 0 1 10 10"/>
      </svg>
      <span>Procurando receptor na TV... Abra o app na TV ou clique em "+ IP".</span>
    `;
    devicesRow.appendChild(emptyDiv);
    updateActionButtonState();
    return;
  }

  deviceList.forEach((device) => {
    const isSelected = device.id === selectedDeviceId;
    const card = document.createElement('div');
    card.className = `device-card-pill ${isSelected ? 'selected' : ''}`;
    card.setAttribute('role', 'radio');
    card.setAttribute('aria-checked', String(isSelected));
    card.setAttribute('tabindex', '0');

    card.innerHTML = `
      <svg class="device-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <rect x="2" y="7" width="20" height="15" rx="2" ry="2"/>
        <polyline points="17 2 12 7 7 2"/>
      </svg>
      <div class="device-text">
        <span class="device-name">${escapeHtml(device.name)}</span>
        <span class="device-ip">${device.ip}:${device.port}</span>
      </div>
      <div class="device-check" aria-hidden="true"></div>
    `;

    const selectHandler = () => {
      if (isTransmitting || isConnecting) return;
      selectedDeviceId = device.id;
      renderDevicesList();
      updateActionButtonState();
    };

    card.addEventListener('click', selectHandler);
    card.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        selectHandler();
      }
    });

    devicesRow.appendChild(card);
  });

  updateActionButtonState();
}

// Source Selection UI
function setupSourcesUI() {
  const switchSourceSegment = (nextTab) => {
    if (activeSourceTab === nextTab) return;
    const applySegmentDOM = () => {
      activeSourceTab = nextTab;
      if (nextTab === 'screens') {
        tabSourcesScreens.classList.add('active');
        tabSourcesScreens.setAttribute('aria-selected', 'true');
        tabSourcesWindows.classList.remove('active');
        tabSourcesWindows.setAttribute('aria-selected', 'false');
      } else {
        tabSourcesWindows.classList.add('active');
        tabSourcesWindows.setAttribute('aria-selected', 'true');
        tabSourcesScreens.classList.remove('active');
        tabSourcesScreens.setAttribute('aria-selected', 'false');
      }
      renderSourcesList();
    };

    if (!document.startViewTransition) {
      applySegmentDOM();
      sourcesGrid?.focus({ preventScroll: true });
      return;
    }
    const transition = document.startViewTransition(() => {
      applySegmentDOM();
    });
    transition.finished.finally(() => {
      sourcesGrid?.focus({ preventScroll: true });
    });
  };

  if (tabSourcesScreens) {
    tabSourcesScreens.addEventListener('click', () => switchSourceSegment('screens'));
  }

  if (tabSourcesWindows) {
    tabSourcesWindows.addEventListener('click', () => switchSourceSegment('windows'));
  }

  if (btnRefreshSources) {
    btnRefreshSources.addEventListener('click', async () => {
      btnRefreshSources.classList.add('spinning');
      await refreshSources();
      setTimeout(() => btnRefreshSources.classList.remove('spinning'), 800);
    });
  }
}

async function refreshSources() {
  const api = getApi();
  try {
    if (api.getSources) {
      captureSources = await api.getSources({ lowRam: lowRamMode });
      renderSourcesList();
    }
  } catch (err) {
    console.error('CastViewer: Erro ao carregar fontes:', err);
  }
}

// Drop cached JPEG thumbnails and window icons while live streaming in Low-RAM mode
function purgeIdleSourceThumbnails() {
  if (!lowRamMode || !Array.isArray(captureSources) || captureSources.length === 0) return;
  captureSources = captureSources.map((src) => ({
    ...src,
    thumbnail: '',
    appIcon: null
  }));
  renderSourcesList();
}

function renderInitialSourcesLoadingState() {
  if (!sourcesGrid || captureSources.length > 0) return;
  sourcesGrid.innerHTML = '';
  const loadingDiv = document.createElement('div');
  loadingDiv.className = 'devices-empty-pill';
  loadingDiv.style.gridColumn = '1 / -1';
  loadingDiv.style.justifyContent = 'center';
  loadingDiv.style.padding = '36px 0';
  loadingDiv.innerHTML = `
    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
      <circle cx="12" cy="12" r="10" stroke-opacity="0.25"/>
      <path d="M12 2a10 10 0 0 1 10 10"/>
    </svg>
    <span>Detectando telas e janelas abertas...</span>
  `;
  sourcesGrid.appendChild(loadingDiv);
}

function renderSourcesList(pulseSelectedId = null) {
  const screensCount = captureSources.filter((s) => s.isScreen).length;
  const windowsCount = captureSources.filter((s) => !s.isScreen).length;
  if (tabSourcesScreens) {
    tabSourcesScreens.textContent = screensCount > 0 ? `Telas (${screensCount})` : 'Telas';
  }
  if (tabSourcesWindows) {
    tabSourcesWindows.textContent = windowsCount > 0 ? `Janelas (${windowsCount})` : 'Janelas';
  }

  const filtered = captureSources.filter((s) => {
    return activeSourceTab === 'screens' ? s.isScreen : !s.isScreen;
  });

  // Auto-select first screen if nothing selected
  if (!selectedSourceId && captureSources.length > 0) {
    const firstScreen = captureSources.find((s) => s.isScreen) || captureSources[0];
    selectedSourceId = firstScreen.id;
  }

  if (!sourcesGrid) return;
  sourcesGrid.innerHTML = '';

  if (filtered.length === 0) {
    const empty = document.createElement('div');
    empty.style.gridColumn = '1 / -1';
    empty.style.textAlign = 'center';
    empty.style.padding = '32px 0';
    empty.style.color = '#71717A';
    empty.style.fontSize = '12px';
    empty.textContent = activeSourceTab === 'screens' ? 'Nenhuma tela detectada.' : 'Nenhuma janela aberta detectada.';
    sourcesGrid.appendChild(empty);
    return;
  }

  filtered.forEach((source) => {
    const isSelected = source.id === selectedSourceId;
    const isPulsing = isSelected && pulseSelectedId === source.id;
    const card = document.createElement('div');
    card.className = `source-card ${isSelected ? 'selected' : ''} ${isPulsing ? 'morph-pulse' : ''}`.trim();
    card.setAttribute('role', 'radio');
    card.setAttribute('aria-checked', String(isSelected));
    card.setAttribute('tabindex', '0');

    const placeholderLabel = lowRamMode ? 'Economia RAM' : 'Prévia indisponível';
    const thumbHtml = source.thumbnail
      ? `<img class="source-thumb" src="${source.thumbnail}" alt="Prévia de ${escapeHtml(source.name)}" loading="lazy" />`
      : `<div class="source-thumb-placeholder" aria-label="${placeholderLabel}">
           ${
             source.appIcon
               ? `<img src="${source.appIcon}" alt="" width="26" height="26" style="object-fit:contain;" />`
               : `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                    <rect x="2" y="3" width="20" height="14" rx="2"/>
                    <line x1="8" y1="21" x2="16" y2="21"/>
                    <line x1="12" y1="17" x2="12" y2="21"/>
                  </svg>`
           }
           <span>${placeholderLabel}</span>
         </div>`;

    card.innerHTML = `
      <div class="source-thumb-wrap">
        ${thumbHtml}
        <div class="source-selected-badge" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="3">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </div>
      </div>
      <div class="source-footer">
        ${
          source.appIcon
            ? `<img class="source-app-icon" src="${source.appIcon}" alt="" />`
            : `<svg class="source-app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`
        }
        <span class="source-name" title="${escapeHtml(source.name)}">${escapeHtml(source.name)}</span>
      </div>
    `;

    const selectHandler = async () => {
      if (isConnecting) return;

      if (source.id === 'wayland-portal:reselect') {
        const api = getApi();
        if (isTransmitting) {
          await swapSourceLive(source);
          return;
        }
        if (api.selectSource) {
          await api.selectSource('wayland-portal:reselect', false);
        }
        selectedSourceId = 'wayland-portal:0';
        await refreshSources();
        playSourceSwitchAnimation('Portal Wayland', false, false);
        showToast('Nova escolha de tela Wayland será solicitada ao iniciar.', 'info');
        return;
      }

      if (selectedSourceId === source.id) return;

      const isWindowSource = !source.isScreen || String(source.id).startsWith('window:');

      // LIVE SOURCE RECONFIGURATION: Seamlessly swap tracks without disconnecting
      if (isTransmitting) {
        await swapSourceLive(source);
        return;
      }

      selectedSourceId = source.id;
      renderSourcesList(source.id);
      updateActionButtonState();
      playSourceSwitchAnimation(source.name, isWindowSource, false);
    };

    card.addEventListener('click', selectHandler);
    card.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        selectHandler();
      }
    });

    sourcesGrid.appendChild(card);
  });

  updateActionButtonState();
}

// LIVE Track Swapping: replaces video track in active RTP sender without disconnecting
async function swapSourceLive(newSource) {
  try {
    const isPortalReselect = newSource.id === 'wayland-portal:reselect';
    const effectiveSourceId = isPortalReselect ? 'wayland-portal:0' : newSource.id;
    const isWindowSource = !newSource.isScreen || String(effectiveSourceId).startsWith('window:');
    selectedSourceId = effectiveSourceId;
    renderSourcesList(effectiveSourceId);
    playSourceSwitchAnimation(newSource.name, isWindowSource, true);

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: 'source-switch',
          sourceName: newSource.name,
          sourceKind: effectiveSourceId.startsWith('window:') ? 'window' : 'screen'
        })
      );
    }

    const api = getApi();
    await api.selectSource(newSource.id, false);

    const preset = getSelectedPreset();
    const newStream = await acquireDesktopVideoStream(effectiveSourceId, preset.width, preset.height, preset.fps, false, false);

    const newVideoTrack = newStream.getVideoTracks()[0];
    if (!newVideoTrack) throw new Error('Não foi possível capturar o vídeo da nova fonte.');

    if (systemCapabilities && systemCapabilities.isWayland && api.markWaylandSessionGranted) {
      api.markWaylandSessionGranted({ label: newVideoTrack.label || newSource.name }).catch(() => {});
    }

    await applyVideoTrackConstraints(newVideoTrack, preset.width, preset.height, preset.fps);

    // Configure contentHint: 'motion' for 60 FPS fluidity, '' for Ultra Sharpness (never 'detail' which caps at 30 FPS)
    if ('contentHint' in newVideoTrack) {
      newVideoTrack.contentHint = liveImageMode === 'motion' ? 'motion' : '';
    }
    newVideoTrack.enabled = !isStreamPaused;
    newVideoTrack.onended = () => {
      stopTransmission('Compartilhamento de tela encerrado.');
    };

    if (peerConnection) {
      const senders = peerConnection.getSenders();
      const videoSender = senders.find((s) => s.track && s.track.kind === 'video');
      if (videoSender) {
        await videoSender.replaceTrack(newVideoTrack);
        await applyRtpSenderParameters(peerConnection, preset.fps, preset.bitrate);
      }
    }

    // Stop old video track cleanly
    const oldVideoTrack = localStream ? localStream.getVideoTracks()[0] : null;
    if (oldVideoTrack) {
      oldVideoTrack.onended = null;
      oldVideoTrack.stop();
      if (localStream) {
        localStream.removeTrack(oldVideoTrack);
      }
    }

    if (localStream) {
      localStream.addTrack(newVideoTrack);
    }

    restartReplayRecorderIfActive();
    showToast(`Transmitindo agora: ${newSource.name}!`, 'success');
  } catch (err) {
    console.error('CastViewer: Falha ao alternar fonte ao vivo:', err);
    showToast(`Falha ao alternar fonte: ${err.message}`, 'error');
  }
}

// Presets UI Handling & Live Reconfiguration
function applySelectedPresetDom(presetKey) {
  const presetOptions = document.querySelectorAll('.preset-option');
  presetOptions.forEach((opt) => {
    const radio = opt.querySelector('input[type="radio"]');
    const match = Boolean(radio && radio.value === presetKey);
    opt.classList.toggle('selected', match);
    if (radio) radio.checked = match;
  });
  if (advTargetFps && PRESETS[presetKey]) {
    advTargetFps.textContent = `Alvo: ${PRESETS[presetKey].fps} FPS`;
  }
}

async function selectPresetByKey(presetKey, { silentToast = false, showOverlay = true } = {}) {
  if (!presetKey || !PRESETS[presetKey] || isConnecting) return;
  applySelectedPresetDom(presetKey);
  localStorage.setItem('castviewer_video_preset', presetKey);
  const preset = PRESETS[presetKey];

  if (isTransmitting) {
    await reconfigurePresetLive(preset, presetKey, { silentToast, showOverlay });
  } else {
    if (showOverlay) playVideoProfileAnimation(preset);
    if (!silentToast) {
      showToast(`Qualidade definida para ${preset.name} (${preset.label}).`, 'info');
    }
  }
}

function setupPresetsUI() {
  const presetOptions = document.querySelectorAll('.preset-option');
  const savedPresetKey = localStorage.getItem('castviewer_video_preset');
  if (savedPresetKey && PRESETS[savedPresetKey]) {
    applySelectedPresetDom(savedPresetKey);
  }

  presetOptions.forEach((option) => {
    option.addEventListener('click', async () => {
      if (isConnecting) return;
      const radio = option.querySelector('input[type="radio"]');
      const val = radio ? radio.value : null;
      if (val && PRESETS[val]) {
        localStorage.setItem('castviewer_preset_user_set', 'true');
        perfUserCustomized = true;
        const api = getApi();
        if (api.setPerformancePrefs) {
          api.setPerformancePrefs({ selectedPreset: val, userCustomized: true }).catch(() => {});
        }
        renderDetectedHardwareUI();
      }
      await selectPresetByKey(val);
    });
  });
}

async function reconfigurePresetLive(preset, presetKey = null, { silentToast = false, showOverlay = true } = {}) {
  try {
    if (localStream) {
      const vTrack = localStream.getVideoTracks()[0];
      if (vTrack) {
        await applyVideoTrackConstraints(vTrack, preset.width, preset.height, preset.fps);
      }
    }

    if (peerConnection) {
      await applyRtpSenderParameters(peerConnection, preset.fps, preset.bitrate);
    }

    if (advTargetFps) {
      advTargetFps.textContent = `Alvo: ${preset.fps} FPS`;
    }

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: 'video-profile',
          preset: presetKey || 'custom',
          label: preset.label,
          name: preset.name
        })
      );
    }

    if (showOverlay) playVideoProfileAnimation(preset);
    if (!silentToast) {
      showToast(`Qualidade alterada para ${preset.name} (${preset.label}) ao vivo!`, 'success');
    }
  } catch (err) {
    console.warn('CastViewer: Erro ao reconfigurar preset ao vivo:', err);
  }
}

function getSelectedPresetKey() {
  const checked = document.querySelector('input[name="video-preset"]:checked');
  return checked && PRESETS[checked.value] ? checked.value : '1080p60';
}

function getSelectedPreset() {
  return PRESETS[getSelectedPresetKey()] || PRESETS['1080p60'];
}

// Notebook Speaker Mute Controls (pure Win32 Core Audio API)
async function toggleNotebookSpeakerMute() {
  const api = getApi();
  isNotebookMuted = !isNotebookMuted;
  updateNotebookSpeakerMuteUI();

  if (api.setSpeakerMute) {
    try {
      await api.setSpeakerMute(isNotebookMuted);
      showToast(
        isNotebookMuted
          ? 'Alto-falantes do notebook silenciados (Áudio transmitindo somente na TV).'
          : 'Alto-falantes do notebook reativados.',
        'info'
      );
    } catch (err) {
      console.warn('CastViewer: Falha ao alterar mudo dos alto-falantes:', err);
    }
  }
}

function updateNotebookSpeakerMuteUI() {
  const btnDockMute = document.getElementById('btn-dock-mute-speaker');
  const toggleMuteSettings = document.getElementById('toggle-mute-speaker-settings');

  if (btnDockMute) {
    btnDockMute.classList.toggle('active', isNotebookMuted);
    btnDockMute.setAttribute('aria-pressed', String(Boolean(isNotebookMuted)));
    btnDockMute.title = isNotebookMuted
      ? 'Alto-falante do notebook silenciado (Clique para reativar som no notebook)'
      : 'Silenciar alto-falante do notebook (Áudio somente na TV)';
  }
  if (toggleMuteSettings) {
    toggleMuteSettings.checked = isNotebookMuted;
  }
}

// Audio UI Setup
function setupAudioUI() {
  // Quick Audio Toggle on Bottom Dock
  if (btnQuickAudio) {
    btnQuickAudio.addEventListener('click', () => {
      setAudioEnabled(!audioCaptureEnabled);
    });
  }

  // Toggle "Enviar áudio para TV" in Settings
  if (toggleAudioSettings) {
    toggleAudioSettings.addEventListener('change', (e) => {
      setAudioEnabled(e.target.checked);
    });
  }

  // Quick "Silenciar Notebook" button on Bottom Dock
  const btnDockMute = document.getElementById('btn-dock-mute-speaker');
  if (btnDockMute) {
    btnDockMute.addEventListener('click', () => {
      toggleNotebookSpeakerMute();
    });
  }

  // Toggle "Silenciar alto-falante do notebook" in Settings
  const toggleMuteSettings = document.getElementById('toggle-mute-speaker-settings');
  if (toggleMuteSettings) {
    toggleMuteSettings.addEventListener('change', () => {
      toggleNotebookSpeakerMute();
    });
  }

  // Sound test button for visual VU verification
  if (btnVuTest) {
    btnVuTest.addEventListener('click', () => {
      toggleSoundTest();
    });
  }
}

function setAudioEnabled(enabled) {
  audioCaptureEnabled = Boolean(enabled);
  if (toggleAudioSettings) {
    toggleAudioSettings.checked = audioCaptureEnabled;
  }
  if (btnQuickAudio) {
    btnQuickAudio.classList.toggle('active', audioCaptureEnabled);
    btnQuickAudio.classList.toggle('muted', !audioCaptureEnabled);
    btnQuickAudio.setAttribute('aria-pressed', String(audioCaptureEnabled));
    const iconOn = btnQuickAudio.querySelector('.icon-audio-on');
    const iconOff = btnQuickAudio.querySelector('.icon-audio-off');
    if (iconOn && iconOff) {
      iconOn.classList.toggle('hidden', !audioCaptureEnabled);
      iconOff.classList.toggle('hidden', audioCaptureEnabled);
    }
    btnQuickAudio.title = audioCaptureEnabled
      ? 'Áudio para TV: Ativado (Clique para mutar)'
      : 'Áudio para TV: Mudo (Clique para ativar)';
  }

  // LIVE AUDIO TOGGLE on active stream
  if (liveAudioTrack) {
    liveAudioTrack.enabled = audioCaptureEnabled;
  }
  if (replayClonedAudioTrack) {
    replayClonedAudioTrack.enabled = audioCaptureEnabled;
  }

  if (isTransmitting) {
    syncLiveAudioPipelineState(audioCaptureEnabled);
    showToast(audioCaptureEnabled ? 'Áudio da transmissão ativado.' : 'Áudio da transmissão mutado.', 'info');
  }
}

async function syncLiveAudioPipelineState(enabled) {
  const api = getApi();
  if (enabled) {
    if (audioCtx && audioCtx.state === 'suspended') {
      try {
        await audioCtx.resume();
      } catch (_) {}
    }
    if (!usingDisplayMediaAudioLoopback && api.startProcessAudio && !isProcessAudioRunning) {
      try {
        const started = await api.startProcessAudio();
        isProcessAudioRunning = Boolean(started);
      } catch (err) {
        console.warn('CastViewer: Falha ao ativar captura de áudio em execução:', err);
      }
    }
    if ((pcmWorkletNode || usingDisplayMediaAudioLoopback) && analyserNode) {
      startVuMeterAnimation();
    }
  } else {
    if (!usingDisplayMediaAudioLoopback && api.stopProcessAudio && isProcessAudioRunning) {
      isProcessAudioRunning = false;
      api.stopProcessAudio().catch((e) => console.warn(e));
    }
    stopVuMeter();
  }
}

// macOS / ScreenCaptureKit system audio track wiring (when native loopback-capture addon isn't bundled)
async function setupDisplayMediaLoopbackAudio(stream) {
  const audioTracks = stream && stream.getAudioTracks ? stream.getAudioTracks() : [];
  if (audioTracks.length === 0) return null;

  const track = audioTracks[0];
  usingDisplayMediaAudioLoopback = true;
  liveAudioTrack = track;
  liveAudioTrack.enabled = audioCaptureEnabled;

  try {
    if (!audioCtx || audioCtx.state === 'closed') {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)({
        sampleRate: 48000,
        latencyHint: 'interactive'
      });
      analyserNode = null;
    }
    if (audioCtx.state === 'suspended') {
      await audioCtx.resume();
    }
    if (!analyserNode) {
      analyserNode = audioCtx.createAnalyser();
      analyserNode.fftSize = 256;
      analyserNode.smoothingTimeConstant = 0.7;
    }
    const sourceNode = audioCtx.createMediaStreamSource(new MediaStream([track]));
    sourceNode.connect(analyserNode);
    isProcessAudioRunning = true;
    startVuMeterAnimation();
  } catch (err) {
    console.warn('CastViewer: Aviso ao iniciar VU Meter do loopback macOS:', err && err.message);
  }

  return liveAudioTrack;
}

// Native Loopback Audio using loopback-capture & AudioWorklet
async function setupNativeLoopbackAudio() {
  const api = getApi();
  try {
    if (!audioCtx || audioCtx.state === 'closed') {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)({
        sampleRate: 48000,
        latencyHint: 'interactive'
      });
      analyserNode = null;
    }
    if (audioCtx.state === 'suspended') {
      await audioCtx.resume();
    }

    try {
      await audioCtx.audioWorklet.addModule('pcm-worklet.js');
    } catch (_) {}

    pcmWorkletNode = new AudioWorkletNode(audioCtx, 'pcm-loopback-processor', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [2]
    });
    pcmWorkletNode.port.postMessage({ type: 'config', latencyMode: liveLatencyMode });

    mediaStreamDest = audioCtx.createMediaStreamDestination();
    pcmWorkletNode.connect(mediaStreamDest);

    // Wire AnalyserNode for the real-time VU meter
    if (!analyserNode) {
      analyserNode = audioCtx.createAnalyser();
      analyserNode.fftSize = 256;
      analyserNode.smoothingTimeConstant = 0.7;
    }
    pcmWorkletNode.connect(analyserNode);

    // Subscribe to incoming PCM chunks once; toggling mute gates capture dynamically
    if (pcmChunkUnsub) {
      pcmChunkUnsub();
      pcmChunkUnsub = null;
    }
    if (api.onAudioPcmChunk) {
      pcmChunkUnsub = api.onAudioPcmChunk((chunk) => {
        if (pcmWorkletNode && audioCaptureEnabled) {
          pcmWorkletNode.port.postMessage(chunk);
        }
      });
    }

    if (audioCaptureEnabled && api.startProcessAudio) {
      await api.startProcessAudio();
      isProcessAudioRunning = true;
      startVuMeterAnimation();
    } else {
      isProcessAudioRunning = false;
      stopVuMeter();
    }

    const audioTracks = mediaStreamDest.stream.getAudioTracks();
    if (audioTracks.length > 0) {
      liveAudioTrack = audioTracks[0];
      liveAudioTrack.enabled = audioCaptureEnabled;
      return liveAudioTrack;
    }
  } catch (err) {
    console.error('CastViewer: Falha ao configurar áudio nativo:', err);
  }
  return null;
}

function cleanupNativeLoopbackAudio() {
  const api = getApi();
  isProcessAudioRunning = false;
  usingDisplayMediaAudioLoopback = false;
  if (pcmChunkUnsub) {
    pcmChunkUnsub();
    pcmChunkUnsub = null;
  }
  if (api.stopProcessAudio) {
    api.stopProcessAudio().catch((e) => console.warn(e));
  }
  if (pcmWorkletNode) {
    try {
      pcmWorkletNode.disconnect();
    } catch (_) {}
    pcmWorkletNode = null;
  }
  liveAudioTrack = null;
  stopVuMeter();

  if (isNotebookMuted) {
    if (api.setSpeakerMute) {
      api.setSpeakerMute(false).catch((e) => console.warn(e));
    }
    isNotebookMuted = false;
    updateNotebookSpeakerMuteUI();
  }
}

function shouldRunVuMeter() {
  return Boolean(
    analyserNode &&
      (isTransmitting || isConnecting) &&
      !lowSpecMode &&
      !document.hidden &&
      currentNavTab === 'settings' &&
      !isVuBoxSkipped
  );
}

function syncVuMeterLifecycle() {
  if (isTestingSound) return;
  if (shouldRunVuMeter()) {
    if (!vuAnimationFrame) startVuMeterAnimation();
  } else {
    stopVuMeter(true);
  }
}

// Live VU Meter via Web Audio API (AnalyserNode) - Pauses RAF completely when hidden or in Weak-PC mode
function startVuMeterAnimation() {
  stopVuMeter(true);

  if (!shouldRunVuMeter()) {
    if (vuStatusBar) {
      vuStatusBar.textContent = lowSpecMode && (isTransmitting || isConnecting)
        ? 'Áudio ao vivo (VU pausado no Modo PC Fraco)'
        : analyserNode
          ? 'Áudio ao vivo (pausado fora da aba Ajustes)'
          : 'Aguardando transmissão...';
    }
    return;
  }

  if (vuStatusBar) vuStatusBar.textContent = 'Capturando áudio ao vivo';

  const bufferLength = analyserNode.frequencyBinCount;
  const dataArray = new Uint8Array(bufferLength);
  let lastVuUpdate = 0;

  const updateVu = (now) => {
    if (!shouldRunVuMeter()) {
      stopVuMeter(true);
      return;
    }

    if (now - lastVuUpdate >= 100) {
      lastVuUpdate = now;
      analyserNode.getByteFrequencyData(dataArray);

      let sum = 0;
      for (let i = 0; i < bufferLength; i++) {
        sum += dataArray[i] * dataArray[i];
      }
      const rms = Math.sqrt(sum / bufferLength);
      const normalized = Math.min(100, Math.round((rms / 255) * 160));

      const leftLevel = normalized;
      const rightLevel = Math.max(0, Math.min(100, Math.round(normalized * 0.95 + (Math.random() * 4 - 2))));

      if (vuBarLeft) vuBarLeft.style.width = `${leftLevel}%`;
      if (vuBarRight) vuBarRight.style.width = `${rightLevel}%`;

      if (vuDbText) {
        if (normalized === 0) {
          vuDbText.textContent = '-∞ dB';
          vuDbText.style.color = '#71717A';
        } else {
          const db = Math.round(20 * Math.log10(normalized / 100));
          vuDbText.textContent = `${db} dB`;
          vuDbText.style.color = db > -3 ? '#EF4444' : db > -10 ? '#FF6B1A' : '#22C55E';
        }
      }
    }

    vuAnimationFrame = requestAnimationFrame(updateVu);
  };

  vuAnimationFrame = requestAnimationFrame(updateVu);
}

function stopVuMeter(keepLiveStatus = false) {
  if (vuAnimationFrame) {
    cancelAnimationFrame(vuAnimationFrame);
    vuAnimationFrame = null;
  }
  if (vuBarLeft) vuBarLeft.style.width = '0%';
  if (vuBarRight) vuBarRight.style.width = '0%';
  if (vuDbText) {
    vuDbText.textContent = '-∞ dB';
    vuDbText.style.color = '#71717A';
  }
  if (vuStatusBar) {
    if (keepLiveStatus && analyserNode && (isTransmitting || isConnecting)) {
      vuStatusBar.textContent = lowSpecMode
        ? 'Áudio ao vivo (VU pausado no Modo PC Fraco)'
        : 'Capturando áudio ao vivo';
    } else {
      vuStatusBar.textContent = 'Aguardando transmissão...';
    }
  }
}

// Local sound test simulation (3-second visual test of VU meter)
function toggleSoundTest() {
  if (isTestingSound) return;
  isTestingSound = true;

  try {
    if (!audioCtx || audioCtx.state === 'closed') {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
    }
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }

    const testAnalyser = audioCtx.createAnalyser();
    testAnalyser.fftSize = 256;

    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'triangle';
    osc.frequency.setValueAtTime(440, audioCtx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(880, audioCtx.currentTime + 1.5);
    osc.frequency.exponentialRampToValueAtTime(330, audioCtx.currentTime + 3);

    gain.gain.setValueAtTime(0.7, audioCtx.currentTime);

    osc.connect(gain);
    gain.connect(testAnalyser);

    osc.start();

    if (btnVuTestText) btnVuTestText.textContent = 'Testando...';
    if (vuStatusBar) vuStatusBar.textContent = 'Sinal de teste Web Audio';

    const bufferLength = testAnalyser.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);

    const testLoop = () => {
      if (!isTestingSound) return;
      testAnalyser.getByteFrequencyData(dataArray);

      let sum = 0;
      for (let i = 0; i < bufferLength; i++) {
        sum += dataArray[i] * dataArray[i];
      }
      const rms = Math.sqrt(sum / bufferLength);
      const pct = Math.min(100, Math.round((rms / 255) * 180));

      if (vuBarLeft) vuBarLeft.style.width = `${pct}%`;
      if (vuBarRight) vuBarRight.style.width = `${Math.round(pct * 0.9)}%`;
      if (vuDbText) {
        const db = Math.round(20 * Math.log10(Math.max(1, pct) / 100));
        vuDbText.textContent = `${db} dB`;
        vuDbText.style.color = '#22C55E';
      }

      requestAnimationFrame(testLoop);
    };

    testLoop();

    setTimeout(() => {
      try {
        osc.stop();
        osc.disconnect();
      } catch (_) {}
      isTestingSound = false;
      if (btnVuTestText) btnVuTestText.textContent = 'Testar Som Local';
      if (!isTransmitting) {
        stopVuMeter();
      } else {
        syncVuMeterLifecycle();
      }
    }, 3000);
  } catch (err) {
    console.warn('Erro ao executar teste de som:', err);
    isTestingSound = false;
    if (btnVuTestText) btnVuTestText.textContent = 'Testar Som Local';
  }
}

// BETA Features Setup
function setupBetaFeaturesUI() {
  // 1. Image Mode Switcher (Nitidez vs Movimento)
  setImageMode(liveImageMode, false);

  if (btnModeDetail) {
    btnModeDetail.addEventListener('click', () => {
      setImageMode('detail', true);
    });
  }

  if (btnModeMotion) {
    btnModeMotion.addEventListener('click', () => {
      setImageMode('motion', true);
    });
  }

  // 1B. Latency Control Switcher (Ultra Baixa vs Equilibrada Wi-Fi)
  setLatencyMode(liveLatencyMode, false);

  if (btnLatencyUltra) {
    btnLatencyUltra.addEventListener('click', () => {
      setLatencyMode('ultra', true);
    });
  }

  if (btnLatencyBalanced) {
    btnLatencyBalanced.addEventListener('click', () => {
      setLatencyMode('balanced', true);
    });
  }

  // 2. Auto-Reconnect Toggle
  if (toggleAutoReconnect) {
    toggleAutoReconnect.checked = autoReconnectPref;
    toggleAutoReconnect.setAttribute('aria-checked', String(autoReconnectPref));
    toggleAutoReconnect.addEventListener('change', (e) => {
      autoReconnectPref = e.target.checked;
      toggleAutoReconnect.setAttribute('aria-checked', String(autoReconnectPref));
      localStorage.setItem('castviewer_auto_reconnect', String(autoReconnectPref));
      showToast(
        autoReconnectPref
          ? 'Auto-reconectar última TV ativado.'
          : 'Auto-reconectar última TV desativado.',
        'info'
      );
    });
  }

  // 3. Fire TV Remote Play/Pause Toggle (BETA)
  if (toggleRemotePause) {
    toggleRemotePause.checked = remotePauseMediaKeyPref;
    toggleRemotePause.setAttribute('aria-checked', String(remotePauseMediaKeyPref));
    toggleRemotePause.addEventListener('change', (e) => {
      remotePauseMediaKeyPref = e.target.checked;
      toggleRemotePause.setAttribute('aria-checked', String(remotePauseMediaKeyPref));
      localStorage.setItem('castviewer_remote_pause', String(remotePauseMediaKeyPref));
      showToast(
        remotePauseMediaKeyPref
          ? 'Controle Play/Pause via Fire TV ativado.'
          : 'Controle Play/Pause via Fire TV desativado.',
        'info'
      );
    });
  }

  // 4. Quick Pause / Resume Button on Bottom Dock & Persistent Pause Overlay Controls
  if (btnDockPauseStream) {
    btnDockPauseStream.addEventListener('click', async () => {
      if (!isTransmitting) return;
      await applyStreamPauseState(!isStreamPaused, false);
    });
  }

  if (btnPausedResume) {
    btnPausedResume.addEventListener('click', async () => {
      if (!isTransmitting || !isStreamPaused) return;
      await applyStreamPauseState(false, false);
    });
  }

  if (btnPausedStop) {
    btnPausedStop.addEventListener('click', () => {
      if (!isTransmitting && !isConnecting) return;
      stopTransmission('Transmissão encerrada pelo usuário.');
    });
  }
}

async function applyStreamPauseState(paused, fromRemote = false) {
  if (!isTransmitting) return;
  isStreamPaused = Boolean(paused);

  // Pause/resume outgoing audio track and notify Fire Stick (which freezes surface rendering on paused=true).
  // Note: Do NOT toggle videoTrack.enabled on Windows DXGI/WGC capture — doing so wedges Chromium's
  // MediaFoundation AMDh264Encoder watchdog (~20s freeze) and forces CPU OpenH264 fallback on resume.
  if (liveAudioTrack) {
    liveAudioTrack.enabled = !isStreamPaused && audioCaptureEnabled;
  }

  const api = getApi();
  if (fromRemote) {
    // Also trigger Windows VK_MEDIA_PLAY_PAUSE (0xB3) so YouTube/Netflix/VLC on PC pauses/resumes!
    if (remotePauseMediaKeyPref && api.triggerMediaPlayPause) {
      api.triggerMediaPlayPause().catch(() => {});
    }
  } else if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(
      JSON.stringify({
        type: 'stream-pause',
        paused: isStreamPaused
      })
    );
  }

  updateStreamPauseUI();
  const s = getAnimStrings();
  playPauseResumeAnimation(isStreamPaused, fromRemote ? 'remote' : 'local');
  showToast(
    fromRemote
      ? isStreamPaused
        ? s.toastRemotePaused
        : s.toastRemoteResumed
      : isStreamPaused
        ? s.toastLocalPaused
        : s.toastLocalResumed,
    'info'
  );
}

function setPausedOverlayVisible(visible) {
  if (!pausedStreamOverlay) return;
  const s = getAnimStrings();
  if (pausedOverlayKicker) pausedOverlayKicker.textContent = s.pauseHeader;
  if (pausedOverlayTitle) pausedOverlayTitle.textContent = s.pauseTitle;
  if (pausedOverlaySub) pausedOverlaySub.textContent = s.pauseSub;
  if (btnPausedResumeText) btnPausedResumeText.textContent = s.pauseBtnResume || 'Retomar';

  const supportsPopover =
    'popover' in HTMLElement.prototype &&
    typeof pausedStreamOverlay.showPopover === 'function' &&
    typeof pausedStreamOverlay.hidePopover === 'function';

  if (visible) {
    try {
      if (supportsPopover) {
        if (!pausedStreamOverlay.matches(':popover-open')) {
          pausedStreamOverlay.showPopover();
        }
      } else {
        pausedStreamOverlay.classList.add('is-open');
      }
    } catch (_) {
      pausedStreamOverlay.classList.add('is-open');
    }
  } else {
    try {
      if (supportsPopover && pausedStreamOverlay.matches(':popover-open')) {
        pausedStreamOverlay.hidePopover();
      }
    } catch (_) {}
    pausedStreamOverlay.classList.remove('is-open');
  }
}

function updateStreamPauseUI() {
  setPausedOverlayVisible(Boolean(isTransmitting && isStreamPaused));

  if (!btnDockPauseStream) return;
  btnDockPauseStream.classList.toggle('hidden', !isTransmitting);
  btnDockPauseStream.classList.toggle('active', isStreamPaused);
  btnDockPauseStream.setAttribute('aria-pressed', String(Boolean(isStreamPaused)));

  const iconPause = btnDockPauseStream.querySelector('.icon-stream-pause');
  const iconPlay = btnDockPauseStream.querySelector('.icon-stream-play');
  if (iconPause && iconPlay) {
    iconPause.classList.toggle('hidden', isStreamPaused);
    iconPlay.classList.toggle('hidden', !isStreamPaused);
  }

  const label = isStreamPaused
    ? 'Retomar transmissão (Em pausa)'
    : 'Pausar transmissão (Sincronizado com o controle do Fire TV)';
  btnDockPauseStream.title = label;
  btnDockPauseStream.setAttribute('aria-label', isStreamPaused ? 'Retomar transmissão' : 'Pausar transmissão');

  if (isTransmitting) {
    if (isStreamPaused) {
      updateStatusPill('connecting', 'Em Pausa');
      if (dockRttFpsText) dockRttFpsText.textContent = 'EM PAUSA';
    } else {
      updateStatusPill('streaming', 'Ao vivo');
    }
  }
}

function setLatencyMode(mode, fromUserClick = false) {
  liveLatencyMode = mode === 'balanced' ? 'balanced' : 'ultra';
  localStorage.setItem('castviewer_latency_mode', liveLatencyMode);

  if (btnLatencyUltra) {
    btnLatencyUltra.classList.toggle('active', liveLatencyMode === 'ultra');
    btnLatencyUltra.setAttribute('aria-checked', String(liveLatencyMode === 'ultra'));
  }
  if (btnLatencyBalanced) {
    btnLatencyBalanced.classList.toggle('active', liveLatencyMode === 'balanced');
    btnLatencyBalanced.setAttribute('aria-checked', String(liveLatencyMode === 'balanced'));
  }

  const preset = getSelectedPreset();
  if (pcmWorkletNode) {
    pcmWorkletNode.port.postMessage({ type: 'config', latencyMode: liveLatencyMode });
  }
  if (peerConnection && isTransmitting) {
    applyRtpSenderParameters(peerConnection, preset.fps, preset.bitrate);
  }

  if (fromUserClick) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: 'latency-mode',
          mode: liveLatencyMode
        })
      );
    }
    playLatencyModeAnimation(liveLatencyMode);
    showToast(
      liveLatencyMode === 'ultra'
        ? 'Latência Ultra Baixa (0 ms) ativada no notebook e no Fire TV!'
        : 'Latência Equilibrada Wi-Fi (~60 ms) ativada no notebook e no Fire TV!',
      'info'
    );
  }
}

function setImageMode(mode, fromUserClick = false) {
  liveImageMode = mode === 'motion' ? 'motion' : 'detail';
  localStorage.setItem('castviewer_image_mode', liveImageMode);
  localStorage.setItem('castviewer_image_mode_v2', liveImageMode);

  if (btnModeDetail) {
    btnModeDetail.classList.toggle('active', liveImageMode === 'detail');
    btnModeDetail.setAttribute('aria-checked', String(liveImageMode === 'detail'));
  }
  if (btnModeMotion) {
    btnModeMotion.classList.toggle('active', liveImageMode === 'motion');
    btnModeMotion.setAttribute('aria-checked', String(liveImageMode === 'motion'));
  }

  const preset = getSelectedPreset();

  // Dynamic live reconfiguration during streaming:
  // NEVER set contentHint = 'detail' because Chromium's kScreensharing mode caps encoding at 30 FPS!
  // Instead: 'motion' -> contentHint = 'motion'; 'detail' -> contentHint = '' with +35% bitrate & maintain-resolution
  if (localStream) {
    const vTrack = localStream.getVideoTracks()[0];
    if (vTrack) {
      if ('contentHint' in vTrack) {
        vTrack.contentHint = liveImageMode === 'motion' ? 'motion' : '';
      }
      applyVideoTrackConstraints(vTrack, preset.width, preset.height, preset.fps).catch(() => {});
    }
  }
  if (peerConnection && isTransmitting) {
    applyRtpSenderParameters(peerConnection, preset.fps, preset.bitrate);
  }

  if (advMode) advMode.textContent = liveImageMode === 'detail' ? 'Nitidez' : 'Movimento';
  if (advHint) {
    const preset = getSelectedPreset();
    const prefLabel =
      preset && preset.fps >= 60
        ? 'maintain-framerate (+35% Mbps)'
        : 'maintain-resolution (+35% Mbps)';
    advHint.textContent =
      liveImageMode === 'detail' ? prefLabel : 'maintain-framerate (60 FPS)';
  }

  if (fromUserClick) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: 'image-mode',
          mode: liveImageMode
        })
      );
    }
    playImageModeAnimation(liveImageMode);
    showToast(
      liveImageMode === 'detail'
        ? 'Modo Nitidez (Texto/Apps) ativado: Ultra resolução +35% Bitrate a 60 FPS!'
        : 'Modo Movimento (Vídeos/Jogos) ativado: Fluidez 60 FPS prioritária!',
      'info'
    );
  }
}

// Last TV Persistence
function saveLastTv(device) {
  if (!device || !device.ip) return;
  const data = {
    id: device.id,
    ip: device.ip,
    port: device.port || 8765,
    name: device.name || `Fire TV (${device.ip})`,
    timestamp: Date.now()
  };
  localStorage.setItem('castviewer_last_tv', JSON.stringify(data));
  updateLastTvUI();
}

function getLastTv() {
  try {
    const raw = localStorage.getItem('castviewer_last_tv');
    return raw ? JSON.parse(raw) : null;
  } catch (_) {
    return null;
  }
}

function updateLastTvUI() {
  const last = getLastTv();
  if (last && last.ip && lastTvBanner && lastTvName) {
    lastTvName.textContent = `${last.name || 'Fire TV'} (${last.ip})`;
    lastTvBanner.classList.remove('hidden');
  } else if (lastTvBanner) {
    lastTvBanner.classList.add('hidden');
  }
}

// Weak PC & Low RAM Performance Controls
function applyLowSpecVisualClass() {
  if (document.body) {
    document.body.classList.toggle('low-spec-mode', lowSpecMode);
  }
  if (lowSpecMode && warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
    const overlay = document.getElementById('hyper-cast-warp-overlay');
    if (overlay) {
      overlay.classList.add('hidden');
      overlay.style.opacity = '';
    }
  }
  if (lowSpecMode || lowRamMode) {
    closeUiSfxAudioContext();
  }
  updatePerfModeSummary();
}

function syncPerformanceSwitchesUI() {
  if (toggleLowSpecMode) {
    toggleLowSpecMode.checked = lowSpecMode;
    toggleLowSpecMode.setAttribute('aria-checked', String(lowSpecMode));
  }
  if (toggleLowRamMode) {
    toggleLowRamMode.checked = lowRamMode;
    toggleLowRamMode.setAttribute('aria-checked', String(lowRamMode));
  }
  if (toggleCompatMode) {
    toggleCompatMode.checked = compatMode;
    toggleCompatMode.setAttribute('aria-checked', String(compatMode));
  }
  if (btnRestartPerf) {
    btnRestartPerf.classList.toggle('hidden', !perfNeedsRestart);
  }
  renderDetectedHardwareUI();
  updatePerfModeSummary();
}

function renderDetectedHardwareUI() {
  if (!perfHwSummary && !perfHwReason) return;
  if (!detectedHardwareProfile) {
    if (perfHwSummary) perfHwSummary.textContent = 'Hardware: identificando processador, GPU e RAM...';
    if (perfHwReason) perfHwReason.textContent = 'Ajuste inicial automático disponível para NVIDIA, Intel e AMD';
    return;
  }

  const hw = detectedHardwareProfile;
  const hybridTag = hw.isHybridGpu ? ' (Híbrido)' : '';
  const ramStr = typeof hw.ramGb === 'number' ? `${hw.ramGb} GB RAM` : '';
  const cpuShort = hw.cpuModel ? `${hw.cpuModel} (${hw.threads}t)` : `${hw.threads} threads`;
  if (perfHwSummary) {
    perfHwSummary.textContent = `${hw.gpuSummary}${hybridTag} • ${ramStr} • ${cpuShort}`;
  }

  if (perfAutoBadge) {
    perfAutoBadge.textContent = perfUserCustomized ? 'MANUAL' : 'AUTO';
  }

  if (perfHwReason) {
    const recLabel = PRESETS[autoRecommendedPreset] ? PRESETS[autoRecommendedPreset].name : autoRecommendedPreset;
    if (perfUserCustomized) {
      perfHwReason.textContent = `Personalizado por você • Recomendado para este PC: ${recLabel}`;
    } else if (autoDetectedReason) {
      perfHwReason.textContent = autoDetectedReason;
    } else {
      perfHwReason.textContent = `Configuração automática ativa: ${recLabel}`;
    }
  }
}

function updatePerfModeSummary() {
  if (!perfModeSummary) return;
  const flags = [];
  if (lowSpecMode) flags.push('PC Fraco');
  if (lowRamMode) flags.push('Pouca RAM');
  if (compatMode) flags.push('Compatibilidade GPU');

  if (perfNeedsRestart) {
    perfModeSummary.textContent = `${flags.join(' + ') || 'Modo Padrão'} selecionado • Reinicie o app para aplicar na GPU/RAM`;
  } else if (flags.length > 0) {
    perfModeSummary.textContent = `Ativos: ${flags.join(' + ')} (otimizado para estabilidade)`;
  } else if (detectedHardwareProfile && detectedHardwareProfile.isHybridGpu) {
    perfModeSummary.textContent = 'Notebook híbrido protegido (60+ FPS sem textura WGC cruzada)';
  } else {
    perfModeSummary.textContent = 'Modo padrão (60+ FPS completo com efeitos glass)';
  }
}

async function updateMemoryUsageDisplay() {
  const api = getApi();
  if (!perfRamReadout || !api.getMemoryUsage || document.hidden || currentNavTab !== 'settings') return;
  try {
    const info = await api.getMemoryUsage();
    if (info && (typeof info.privateMb === 'number' || typeof info.workingSetMb === 'number')) {
      const displayMb = info.privateMb > 0 ? info.privateMb : info.workingSetMb;
      const heapTag = lowRamMode ? ' • Alvo 128 MB heap' : '';
      perfRamReadout.textContent = `Memória CastViewer: ~${displayMb} MB (${info.processCount} proc.)${heapTag}`;
    }
  } catch (_) {}
}

function syncMemoryPollLifecycle() {
  if (memoryPollInterval) {
    clearInterval(memoryPollInterval);
    memoryPollInterval = null;
  }
  if (currentNavTab === 'settings' && !document.hidden) {
    updateMemoryUsageDisplay();
    memoryPollInterval = setInterval(updateMemoryUsageDisplay, 3000);
  }
}

function setupPerformanceUI() {
  syncPerformanceSwitchesUI();

  // Pause VU RAF cleanly when Chromium skips rendering off-screen VU box
  if (vuMeterBox) {
    vuMeterBox.addEventListener('contentvisibilityautostatechange', (event) => {
      isVuBoxSkipped = Boolean(event.skipped);
      syncVuMeterLifecycle();
    });
  }

  const api = getApi();

  if (toggleLowSpecMode) {
    toggleLowSpecMode.addEventListener('change', async (e) => {
      lowSpecMode = e.target.checked;
      perfUserCustomized = true;
      localStorage.setItem('castviewer_low_spec_mode', String(lowSpecMode));
      toggleLowSpecMode.setAttribute('aria-checked', String(lowSpecMode));
      applyLowSpecVisualClass();
      syncVuMeterLifecycle();
      restartStatsPollingIfActive();

      if (api.setPerformancePrefs) {
        const res = await api.setPerformancePrefs({ lowSpecMode, lowRamMode, compatMode, userCustomized: true }).catch(() => null);
        if (res) perfNeedsRestart = Boolean(res.needsRestart);
      }
      syncPerformanceSwitchesUI();

      const currentPresetKey = getSelectedPresetKey();
      if (lowSpecMode && (currentPresetKey === '1080p60' || currentPresetKey === '1080p30')) {
        await selectPresetByKey('720p30', { silentToast: true, showOverlay: false });
        if (api.setPerformancePrefs) {
          await api.setPerformancePrefs({ selectedPreset: '720p30', userCustomized: true }).catch(() => {});
        }
        showToast('Modo PC Fraco ativado! Perfil alterado para 720p30 e efeitos GPU/CPU reduzidos.', 'success');
      } else {
        if (isTransmitting && localStream) {
          const preset = getSelectedPreset();
          const vTrack = localStream.getVideoTracks()[0];
          if (vTrack) {
            await applyVideoTrackConstraints(vTrack, preset.width, preset.height, preset.fps);
          }
        }
        showToast(
          lowSpecMode
            ? 'Modo PC Fraco (CPU/GPU) ativado: sem blur, sem Canvas pesado e captura enxuta.'
            : 'Modo PC Fraco desativado: efeitos visuais e captura 60+ FPS restaurados.',
          'info'
        );
      }
      updateMemoryUsageDisplay();
    });
  }

  if (toggleLowRamMode) {
    toggleLowRamMode.addEventListener('change', async (e) => {
      lowRamMode = e.target.checked;
      perfUserCustomized = true;
      localStorage.setItem('castviewer_low_ram_mode', String(lowRamMode));
      toggleLowRamMode.setAttribute('aria-checked', String(lowRamMode));
      startSourcesRefreshLoop();

      if (api.setPerformancePrefs) {
        const res = await api.setPerformancePrefs({ lowSpecMode, lowRamMode, compatMode, userCustomized: true }).catch(() => null);
        if (res) perfNeedsRestart = Boolean(res.needsRestart);
      }
      syncPerformanceSwitchesUI();

      if (lowRamMode) {
        closeUiSfxAudioContext();
        if (isTransmitting) {
          purgeIdleSourceThumbnails();
        } else {
          await refreshSources();
        }
        if (api.trimMemory) await api.trimMemory().catch(() => {});
        showToast('Modo Pouca RAM ativado! Cache limpo e heap de 128 MB aplicado ao reiniciar.', 'success');
      } else {
        await refreshSources();
        showToast('Modo Pouca RAM desativado: miniaturas padrão restauradas.', 'info');
      }
      updateMemoryUsageDisplay();
    });
  }

  if (toggleCompatMode) {
    toggleCompatMode.addEventListener('change', async (e) => {
      compatMode = e.target.checked;
      perfUserCustomized = true;
      localStorage.setItem('castviewer_compat_mode', String(compatMode));
      toggleCompatMode.setAttribute('aria-checked', String(compatMode));

      if (api.setPerformancePrefs) {
        const res = await api.setPerformancePrefs({ lowSpecMode, lowRamMode, compatMode, userCustomized: true }).catch(() => null);
        if (res) perfNeedsRestart = Boolean(res.needsRestart);
      }
      syncPerformanceSwitchesUI();

      showToast(
        compatMode
          ? 'Modo Compatibilidade GPU selecionado! Clique em Reiniciar App para aplicar captura clássica.'
          : 'Modo Compatibilidade desativado. Clique em Reiniciar App para restaurar aceleração WGC/GPU.',
        compatMode ? 'success' : 'info'
      );
    });
  }

  if (btnAutoDetectPerf) {
    btnAutoDetectPerf.addEventListener('click', async () => {
      if (!api.reapplyAutoPerformance) return;
      btnAutoDetectPerf.disabled = true;
      try {
        const updated = await api.reapplyAutoPerformance();
        if (updated) {
          detectedHardwareProfile = updated.detectedHardware || detectedHardwareProfile;
          autoRecommendedPreset = updated.recommendedPreset || '1080p60';
          autoDetectedReason = updated.autoReason || '';
          lowSpecMode = Boolean(updated.lowSpecMode);
          lowRamMode = Boolean(updated.lowRamMode);
          compatMode = Boolean(updated.compatMode);
          perfUserCustomized = false;
          perfNeedsRestart = Boolean(updated.needsRestart);

          localStorage.setItem('castviewer_low_spec_mode', String(lowSpecMode));
          localStorage.setItem('castviewer_low_ram_mode', String(lowRamMode));
          localStorage.setItem('castviewer_compat_mode', String(compatMode));
          localStorage.removeItem('castviewer_preset_user_set');

          applyLowSpecVisualClass();
          syncVuMeterLifecycle();
          restartStatsPollingIfActive();
          startSourcesRefreshLoop();

          await selectPresetByKey(autoRecommendedPreset, { silentToast: true, showOverlay: false });
          syncPerformanceSwitchesUI();
          await updateMemoryUsageDisplay();

          showToast(
            updated.autoReason || `Hardware analisado: configurado para ${PRESETS[autoRecommendedPreset].name}.`,
            'success'
          );
        }
      } catch (_) {
        showToast('Não foi possível reanalisar o hardware agora.', 'error');
      } finally {
        btnAutoDetectPerf.disabled = false;
      }
    });
  }

  if (btnRestartPerf && api.relaunchApp) {
    btnRestartPerf.addEventListener('click', async () => {
      if (isTransmitting) {
        stopTransmission();
      }
      api.relaunchApp();
    });
  }

  if (btnTrimMemory) {
    btnTrimMemory.addEventListener('click', async () => {
      if (lowRamMode && isTransmitting) {
        purgeIdleSourceThumbnails();
      }
      if (api.trimMemory) {
        await api.trimMemory().catch(() => {});
      }
      await updateMemoryUsageDisplay();
      showToast('Cache de imagens e memória do CastViewer limpos!', 'success');
    });
  }
}

// Preferences UI
function setupPreferencesUI() {
  const api = getApi();
  if (toggleMinimizeTray && api.setMinimizeToTrayPref) {
    toggleMinimizeTray.addEventListener('change', (e) => {
      toggleMinimizeTray.setAttribute('aria-checked', String(e.target.checked));
      api.setMinimizeToTrayPref(e.target.checked);
    });
  }

  if (toggleReplayBuffer) {
    toggleReplayBuffer.checked = replayBufferEnabled;
    toggleReplayBuffer.setAttribute('aria-checked', String(replayBufferEnabled));
    toggleReplayBuffer.addEventListener('change', (e) => {
      replayBufferEnabled = Boolean(e.target.checked);
      toggleReplayBuffer.setAttribute('aria-checked', String(replayBufferEnabled));
      localStorage.setItem('castviewer_replay_buffer', String(replayBufferEnabled));
      if (isTransmitting) {
        if (replayBufferEnabled) {
          startReplayRecorder();
        } else {
          stopReplayRecorder();
        }
      }
      updateCaptureToolbarState();
      showToast(
        replayBufferEnabled
          ? 'Buffer Replay 30s ativado.'
          : 'Buffer Replay 30s desativado.',
        'info'
      );
    });
  }

  const syncClockCornerControls = () => {
    if (streamClockCornerGroup) {
      streamClockCornerGroup.disabled = !streamClockEnabled;
    }
    if (streamClockCornerRadios && streamClockCornerRadios.length > 0) {
      streamClockCornerRadios.forEach((radio) => {
        radio.checked = radio.value === streamClockCorner;
      });
    }
  };

  if (toggleStreamClock) {
    toggleStreamClock.checked = streamClockEnabled;
    toggleStreamClock.setAttribute('aria-checked', String(streamClockEnabled));
    syncClockCornerControls();
    toggleStreamClock.addEventListener('change', (e) => {
      streamClockEnabled = Boolean(e.target.checked);
      toggleStreamClock.setAttribute('aria-checked', String(streamClockEnabled));
      localStorage.setItem('castviewer_stream_clock', String(streamClockEnabled));
      syncClockCornerControls();
      sendClockConfigToReceiver();
      showToast(
        streamClockEnabled
          ? 'Relógio na TV durante a transmissão ativado.'
          : 'Relógio na TV durante a transmissão desativado.',
        'info'
      );
    });
  } else {
    syncClockCornerControls();
  }

  if (streamClockCornerRadios && streamClockCornerRadios.length > 0) {
    streamClockCornerRadios.forEach((radio) => {
      radio.addEventListener('change', (e) => {
        if (!e.target.checked) return;
        streamClockCorner = normalizeClockCorner(e.target.value);
        localStorage.setItem('castviewer_stream_clock_corner', streamClockCorner);
        sendClockConfigToReceiver();
      });
    });
  }
}

function sendClockConfigToReceiver() {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  try {
    ws.send(JSON.stringify({
      type: 'clock-config',
      enabled: Boolean(streamClockEnabled),
      corner: normalizeClockCorner(streamClockCorner)
    }));
  } catch (_) {}
}

// Action Button State & Handling
function setupActionUI() {
  if (btnAction) {
    btnAction.addEventListener('click', async () => {
      if (isTransmitting || isConnecting) {
        stopTransmission();
      } else {
        const directTakeover = window.CastTakeoverState
          ? window.CastTakeoverState.shouldOfferDirectTakeoverOnAction(pendingBusyDevice, getSelectedDevice())
          : Boolean(pendingBusyDevice && getSelectedDevice() && pendingBusyDevice.id === getSelectedDevice().id);
        if (directTakeover) {
          hideBusyTakeoverBanner();
        }
        await startTransmission(directTakeover);
      }
    });
  }
}

function getSelectedDevice() {
  const all = [...manualDevices, ...discoveredDevices];
  return all.find((d) => d.id === selectedDeviceId) || null;
}

function getSelectedSource() {
  return captureSources.find((s) => s.id === selectedSourceId) || null;
}

function updateActionButtonState() {
  if (!btnAction || !btnActionText) return;

  const device = getSelectedDevice();
  const source = getSelectedSource();

  const iconCast = btnAction.querySelector('.icon-cta-cast');
  const iconStop = btnAction.querySelector('.icon-cta-stop');
  const iconSpinner = btnAction.querySelector('.icon-cta-spinner');

  if (isConnecting) {
    btnAction.disabled = false;
    btnAction.className = 'btn-main-cta connecting';
    btnActionText.textContent = 'Conectando...';
    btnAction.title = 'Conectando ao Fire TV...';
    if (iconCast) iconCast.classList.add('hidden');
    if (iconStop) iconStop.classList.add('hidden');
    if (iconSpinner) iconSpinner.classList.remove('hidden');
    updateStatusPill('connecting', 'Conectando...');
    updateCaptureToolbarState();
    return;
  }

  if (isTransmitting) {
    btnAction.disabled = false;
    btnAction.className = 'btn-main-cta streaming';
    btnActionText.textContent = 'Encerrar';
    btnAction.title = 'Encerrar transmissão';
    if (iconCast) iconCast.classList.add('hidden');
    if (iconStop) iconStop.classList.remove('hidden');
    if (iconSpinner) iconSpinner.classList.add('hidden');
    updateCaptureToolbarState();
    return;
  }

  if (iconSpinner) iconSpinner.classList.add('hidden');
  if (iconStop) iconStop.classList.add('hidden');
  if (iconCast) iconCast.classList.remove('hidden');
  updateStatusPill('idle', 'Pronto');

  const directTakeoverReady = window.CastTakeoverState
    ? window.CastTakeoverState.shouldOfferDirectTakeoverOnAction(pendingBusyDevice, device)
    : Boolean(pendingBusyDevice && device && pendingBusyDevice.id === device.id);

  btnActionText.textContent = directTakeoverReady ? 'Assumir TV' : 'Transmitir';

  if (!device) {
    btnAction.disabled = true;
    btnAction.className = 'btn-main-cta idle';
    btnAction.title = 'Selecione um Fire TV';
    updateCaptureToolbarState();
    return;
  }

  if (!source) {
    btnAction.disabled = true;
    btnAction.className = 'btn-main-cta idle';
    btnAction.title = 'Selecione uma Tela ou Janela';
    updateCaptureToolbarState();
    return;
  }

  btnAction.disabled = false;
  btnAction.className = 'btn-main-cta idle';
  btnAction.title = directTakeoverReady
    ? `Assumir controle em ${device.name}`
    : `Transmitir para ${device.name}`;
  updateCaptureToolbarState();
}

// Ultra-Low Latency SDP Munging: Aligns H.264 Zero-B-Frame Baseline, Unlocks 1080p60 Macroblocks & High Instant Bitrate
function optimizeSdpForUltraLowLatency(sdp, targetBitrateBps, targetFps = 60) {
  const lines = sdp.split('\r\n');
  const modified = [];
  let inVideo = false;

  // Step 1: Detect all H264 payload types and prioritize Hardware Zero-B-Frame Baseline (4200 -> 42e0 -> 4d00 -> 6400)
  // On AMD/Intel/NVIDIA Windows systems, MediaFoundation HW VEA exposes profile-level-id=42001f while CPU OpenH264 uses 42e01f.
  // Scoring 4200 ahead of 42e0 locks GPU hardware encoding (AMDh264Encoder) and matches Fire Stick DirectSurface H.264!
  const h264Pts = [];
  for (let i = 0; i < lines.length; i++) {
    const rtpMatch = lines[i].match(/^a=rtpmap:(\d+)\s+H264\/90000/i);
    if (rtpMatch) {
      h264Pts.push(rtpMatch[1]);
    }
  }

  let bestH264Pt = null;
  let bestScore = 99;
  for (let i = 0; i < lines.length; i++) {
    const fmtpMatch = lines[i].match(/^a=fmtp:(\d+)\s+(.*)$/i);
    if (!fmtpMatch) continue;
    const pt = fmtpMatch[1];
    if (!h264Pts.includes(pt)) continue;
    const params = fmtpMatch[2].toLowerCase();
    const isPkt1 = params.includes('packetization-mode=1');
    let score = 10;
    if (params.includes('profile-level-id=4200')) score = isPkt1 ? 0 : 1;
    else if (params.includes('profile-level-id=42e0')) score = isPkt1 ? 2 : 3;
    else if (params.includes('profile-level-id=4d00')) score = isPkt1 ? 4 : 5;
    else if (params.includes('profile-level-id=6400')) score = isPkt1 ? 6 : 7;
    if (score < bestScore) {
      bestScore = score;
      bestH264Pt = pt;
    }
  }

  if (!bestH264Pt && h264Pts.length > 0) {
    bestH264Pt = h264Pts[0];
  }

  console.log(`CastViewer: Codec H.264 alinhado 60 FPS: PT=${bestH264Pt}`);

  const baseBitrate = targetBitrateBps || 16_000_000;
  const effectiveBitrateBps = liveImageMode === 'detail' ? Math.round(baseBitrate * 1.35) : baseBitrate;
  const targetKbps = Math.max(4000, Math.round(effectiveBitrateBps / 1000));
  const minKbps =
    liveLatencyMode === 'ultra' ? Math.min(8000, Math.round(targetKbps * 0.75)) : Math.min(4500, targetKbps);
  const startKbps = Math.min(14000, targetKbps);
  const maxKbps = Math.max(targetKbps, 22000);

  let inAudio = false;
  let videoBandwidthInjected = false;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (line.startsWith('m=video ')) {
      inVideo = true;
      inAudio = false;
      videoBandwidthInjected = false;

      if (bestH264Pt) {
        const parts = line.split(' ');
        const prefix = parts.slice(0, 3);
        const pts = parts.slice(3);
        const reordered = [bestH264Pt, ...pts.filter((p) => p !== bestH264Pt)];
        modified.push([...prefix, ...reordered].join(' '));
      } else {
        modified.push(line);
      }
      continue;
    } else if (line.startsWith('m=audio ')) {
      inVideo = false;
      inAudio = true;
    } else if (line.startsWith('m=')) {
      inVideo = false;
      inAudio = false;
    }

    if (inVideo) {
      if (line.startsWith('b=AS:') || line.startsWith('b=TIAS:') || line.startsWith('a=framerate:')) {
        continue;
      }

      if (line.startsWith('c=') && !videoBandwidthInjected) {
        modified.push(line);
        modified.push(`b=AS:${maxKbps}`);
        modified.push(`b=TIAS:${maxKbps * 1000}`);
        modified.push('a=framerate:' + (targetFps || 60));
        videoBandwidthInjected = true;
        continue;
      }

      // Preserve Chromium's exact profile-level-id token so MediaFoundation HW VEA matches cleanly,
      // while adding max-fs=8704;max-mbps=522240 (1080p60 macroblock budget) & high instant bitrate hints!
      if (line.startsWith('a=fmtp:')) {
        const match = line.match(/^a=fmtp:(\d+)\s+(.*)$/);
        if (match) {
          const pt = match[1];
          let params = match[2];
          if (!params.includes('apt=')) {
            if (h264Pts.includes(pt)) {
              if (!params.includes('level-asymmetry-allowed=')) params += ';level-asymmetry-allowed=1';
              if (!params.includes('max-fs=')) params += ';max-fs=8704';
              if (!params.includes('max-mbps=')) params += ';max-mbps=522240';
            }
            params = params
              .replace(/;?\b(x-google-min-bitrate|x-google-start-bitrate|x-google-max-bitrate)=\d+/gi, '')
              .replace(/^;+|;+$/g, '');
            const bitrateTail = `x-google-min-bitrate=${minKbps};x-google-start-bitrate=${startKbps};x-google-max-bitrate=${maxKbps}`;
            params = params ? `${params};${bitrateTail}` : bitrateTail;
            modified.push(`a=fmtp:${pt} ${params}`);
            continue;
          }
        }
      }
    }

    if (inAudio && line.startsWith('a=fmtp:')) {
      const match = line.match(/^a=fmtp:(\d+)\s+(.*)$/);
      if (match) {
        const pt = match[1];
        let params = match[2];
        if (!params.includes('stereo=')) params += ';stereo=1;sprop-stereo=1';
        if (!params.includes('maxaveragebitrate=')) params += ';maxaveragebitrate=128000';
        if (!params.includes('useinbandfec=')) params += ';useinbandfec=1';
        if (!params.includes('usedtx=')) params += ';usedtx=0';
        modified.push(`a=fmtp:${pt} ${params}`);
        continue;
      }
    }

    modified.push(line);
  }

  return modified.join('\r\n');
}

// Prefer H.264 Constrained Baseline First (Aligned 1:1 with Fire Stick WebRtcReceiver)
function preferH264Codec(pc) {
  if (typeof RTCRtpSender.getCapabilities !== 'function') return;
  const capabilities = RTCRtpSender.getCapabilities('video');
  if (!capabilities || !capabilities.codecs) return;

  const h264List = capabilities.codecs.filter((c) => c.mimeType.toLowerCase() === 'video/h264');
  const otherList = capabilities.codecs.filter((c) => c.mimeType.toLowerCase() !== 'video/h264');

  h264List.sort((a, b) => {
    const aLine = (a.sdpFmtpLine || '').toLowerCase();
    const bLine = (b.sdpFmtpLine || '').toLowerCase();
    const score = (line) => {
      const pkt1 = line.includes('packetization-mode=1');
      if (line.includes('4200')) return pkt1 ? 0 : 1;
      if (line.includes('42e0')) return pkt1 ? 2 : 3;
      if (line.includes('4d00')) return pkt1 ? 4 : 5;
      if (line.includes('6400')) return pkt1 ? 6 : 7;
      return 8;
    };
    return score(aLine) - score(bLine);
  });

  if (h264List.length > 0) {
    const preferredOrder = [...h264List, ...otherList];
    const transceivers = pc.getTransceivers ? pc.getTransceivers() : [];
    for (const t of transceivers) {
      if (
        t.sender &&
        t.sender.track &&
        t.sender.track.kind === 'video' &&
        typeof t.setCodecPreferences === 'function'
      ) {
        try {
          t.setCodecPreferences(preferredOrder);
          console.log('CastViewer: Preferência de codec H.264 Constrained Baseline configurada');
        } catch (e) {
          console.warn('CastViewer: Aviso ao definir setCodecPreferences:', e);
        }
      }
    }
  }
}

// Chromium drops ~1/15 frames at 60Hz due to DWM vsync Nyquist phase beating when maxFrameRate is capped at 60.
// Allowing headroom up to 120 FPS on >= 60 FPS presets eliminates beat-drop loss while minFrameRate=60 locks the timer.
// Weak-PC mode skips the extra 120Hz capture headroom to cut DWM/GPU duplication overhead.
function getCaptureMaxFps(targetFps) {
  if (lowSpecMode) return targetFps;
  return targetFps >= 60 ? 120 : targetFps;
}

async function applyVideoTrackConstraints(videoTrack, width, height, targetFps) {
  if (!videoTrack || typeof videoTrack.applyConstraints !== 'function') return;
  const maxFps = getCaptureMaxFps(targetFps);
  const current = typeof videoTrack.getSettings === 'function' ? videoTrack.getSettings() : {};
  // Preserve 120Hz capture headroom: calling applyConstraints({ ideal: 60 }) forces Chromium's track
  // back down to 60Hz and re-introduces DWM vsync Nyquist beat drops (~56.5 FPS).
  if (
    targetFps >= 60 &&
    !lowSpecMode &&
    (current.frameRate || 0) >= maxFps &&
    (current.width || 0) <= width &&
    (current.height || 0) <= height
  ) {
    return;
  }
  const idealFps = targetFps >= 60 ? maxFps : targetFps;
  try {
    await videoTrack.applyConstraints({
      width: { ideal: width, max: width },
      height: { ideal: height, max: height },
      frameRate: { min: targetFps, ideal: idealFps, max: maxFps }
    });
  } catch (_) {
    await videoTrack
      .applyConstraints({
        width: { ideal: width, max: width },
        height: { ideal: height, max: height },
        frameRate: { ideal: idealFps, max: maxFps }
      })
      .catch(() => {});
  }
}

async function acquireDesktopVideoStream(
  sourceId,
  width,
  height,
  targetFps,
  fallbackUnconstrained = false,
  requestDisplayAudio = false
) {
  const maxFps = getCaptureMaxFps(targetFps);
  const idealFps = targetFps >= 60 ? maxFps : targetFps;
  const audioConstraint = requestDisplayAudio
    ? {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false
      }
    : false;
  const displayConstraints = {
    video: {
      width: { ideal: width, max: width },
      height: { ideal: height, max: height },
      frameRate: { ideal: idealFps, max: maxFps }
    },
    audio: audioConstraint
  };

  if (sourceId === 'wayland-portal:0' || sourceId === 'wayland-portal:reselect') {
    return await navigator.mediaDevices.getDisplayMedia(displayConstraints);
  }

  // On macOS without native loopback-capture addon, prefer getDisplayMedia so
  // Electron's setDisplayMediaRequestHandler provides ScreenCaptureKit / CoreAudio system loopback.
  if (requestDisplayAudio) {
    try {
      return await navigator.mediaDevices.getDisplayMedia(displayConstraints);
    } catch (macAudioErr) {
      console.warn('CastViewer: Captura com áudio do sistema macOS indisponível, fallback para vídeo:', macAudioErr);
      try {
        return await navigator.mediaDevices.getDisplayMedia({
          video: displayConstraints.video,
          audio: false
        });
      } catch (_fallbackErr) {}
    }
  }

  try {
    // Attempt 1: Direct Electron desktop capture via getUserMedia with minFrameRate = targetFps
    return await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        mandatory: {
          chromeMediaSource: 'desktop',
          chromeMediaSourceId: sourceId,
          maxWidth: width,
          maxHeight: height,
          minFrameRate: targetFps,
          maxFrameRate: maxFps
        }
      }
    });
  } catch (attempt1Err) {
    console.warn('CastViewer: getUserMedia desktop capture falhou, tentando getDisplayMedia:', attempt1Err);
    if (!fallbackUnconstrained) {
      return await navigator.mediaDevices.getDisplayMedia(displayConstraints);
    }
    try {
      // Attempt 2: W3C getDisplayMedia with ideal & max constraints
      return await navigator.mediaDevices.getDisplayMedia(displayConstraints);
    } catch (attempt2Err) {
      console.warn('CastViewer: getDisplayMedia (Attempt 2) falhou, tentando Attempt 3:', attempt2Err);
      try {
        // Attempt 3: Ultimate fallback with unconstrained getDisplayMedia + applyConstraints
        return await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
      } catch (mediaErr) {
        console.error('CastViewer: Erro em todas as tentativas de captura:', mediaErr);
        throw new Error('Permissão negada ou cancelada na captura.');
      }
    }
  }
}

// RTCRtpSender Parameters: Enforces single-layer L1T1, uncapped 60+ FPS headroom, & Playout Delay target
async function applyRtpSenderParameters(pc, targetFps, targetBitrate) {
  const senders = pc.getSenders ? pc.getSenders() : [];
  const isDetailMode = liveImageMode === 'detail';
  const effectiveBitrate = isDetailMode ? Math.round(targetBitrate * 1.35) : targetBitrate;
  const degradationPref = isDetailMode && targetFps < 60 ? 'maintain-resolution' : 'maintain-framerate';

  for (const sender of senders) {
    if (sender.track && sender.track.kind === 'video') {
      try {
        const params = sender.getParameters() || {};
        params.degradationPreference = degradationPref;
        if (!params.encodings || params.encodings.length === 0) {
          params.encodings = [{}];
        }
        if (targetFps >= 60) {
          delete params.encodings[0].maxFramerate;
        } else {
          params.encodings[0].maxFramerate = targetFps;
        }
        params.encodings[0].maxBitrate = effectiveBitrate;
        params.encodings[0].priority = 'high';
        params.encodings[0].networkPriority = 'high';
        params.encodings[0].scaleResolutionDownBy = 1.0;

        // Force L1T1 single temporal layer to eliminate WebRTC screenshare multi-layer FPS splitting
        const prevScalability = params.encodings[0].scalabilityMode;
        params.encodings[0].scalabilityMode = 'L1T1';
        try {
          await sender.setParameters(params);
        } catch (_scalErr) {
          if (prevScalability !== undefined) {
            params.encodings[0].scalabilityMode = prevScalability;
          } else {
            delete params.encodings[0].scalabilityMode;
          }
          await sender.setParameters(params);
        }

        console.log(
          `CastViewer: Encodings L1T1: FPS=${targetFps >= 60 ? `${targetFps}+ (uncapped)` : targetFps}, Bitrate=${effectiveBitrate}, pref=${degradationPref}, latency=${liveLatencyMode}`
        );
      } catch (paramErr) {
        console.warn('CastViewer: Aviso ao aplicar setParameters:', paramErr);
      }
    } else if (sender.track && sender.track.kind === 'audio') {
      try {
        const audioParams = sender.getParameters() || {};
        if (!audioParams.encodings || audioParams.encodings.length === 0) {
          audioParams.encodings = [{}];
        }
        audioParams.encodings[0].priority = 'high';
        audioParams.encodings[0].networkPriority = 'high';
        await sender.setParameters(audioParams);
      } catch (_) {}
    }
  }

  const transceivers = pc.getTransceivers ? pc.getTransceivers() : [];
  for (const t of transceivers) {
    if (t.receiver && 'playoutDelayHint' in t.receiver) {
      try {
        t.receiver.playoutDelayHint = liveLatencyMode === 'ultra' ? 0 : 0.06;
      } catch (_) {}
    }
  }
}

// Start Transmission
async function startTransmission(forceTakeover = false) {
  const isTakeover = forceTakeover === true;
  acceptedOutgoingTakeoverTarget = null;
  if (!isTakeover) {
    hideBusyTakeoverBanner();
  }
  const api = getApi();
  const device = getSelectedDevice();
  const source = getSelectedSource();

  if (!device || !source) {
    showToast('Selecione o Fire TV e a tela de transmissão.', 'error');
    return;
  }

  const preset = getSelectedPreset();
  const targetWidth = preset.width;
  const targetHeight = preset.height;
  const targetFps = preset.fps;
  const targetBitrate = preset.bitrate;

  isConnecting = true;
  updateActionButtonState();
  playCastWarpAnimation(device.name, preset.label);
  showToast(
    isTakeover ? `Assumindo ${device.name}...` : `Iniciando transmissão para ${device.name}...`,
    'info'
  );

  try {
    // Step 1: Pre-select source in main process (enables macOS ScreenCaptureKit audio loopback when needed)
    const wantDisplayAudioLoopback = Boolean(systemCapabilities.isMac && !systemCapabilities.hasNativeLoopback);
    await api.selectSource(source.id, wantDisplayAudioLoopback);

    // Step 2: Acquire Video Stream forcing DXGI / PipeWire / ScreenCaptureKit capture
    localStream = await acquireDesktopVideoStream(
      source.id,
      targetWidth,
      targetHeight,
      targetFps,
      true,
      wantDisplayAudioLoopback
    );

    // Immediately enforce resolution and frameRate constraints atomically on the acquired videoTrack
    const videoTrack = localStream.getVideoTracks()[0];
    if (videoTrack) {
      if (systemCapabilities.isWayland && api.markWaylandSessionGranted) {
        api.markWaylandSessionGranted({ label: videoTrack.label || source.name }).catch(() => {});
      }

      await applyVideoTrackConstraints(videoTrack, targetWidth, targetHeight, targetFps);

      // NEVER use 'detail' (caps Chromium WebRTC at 30 FPS); use 'motion' for Fluidez 60 FPS and '' for Ultra Nitidez
      if ('contentHint' in videoTrack) {
        videoTrack.contentHint = liveImageMode === 'motion' ? 'motion' : '';
      }
      videoTrack.onended = () => {
        stopTransmission('Compartilhamento de tela encerrado.');
      };
    }

    // Step 3: Wire macOS ScreenCaptureKit audio track if returned, otherwise start native loopback-capture + AudioWorklet
    if (wantDisplayAudioLoopback && localStream.getAudioTracks().length > 0) {
      await setupDisplayMediaLoopbackAudio(localStream);
    } else {
      const aTrack = await setupNativeLoopbackAudio();
      if (aTrack && localStream) {
        localStream.addTrack(aTrack);
      }
    }

    // Apply notebook mute state if user chose to mute notebook speakers
    if (isNotebookMuted && api.setSpeakerMute) {
      await api.setSpeakerMute(true);
    }

    // Step 3: Open WebSocket Signaling connection
    const wsUrl = `ws://${device.ip}:${device.port}`;
    console.log(`CastViewer: Conectando em ${wsUrl}`);

    await new Promise((resolve, reject) => {
      let settled = false;
      let statusGraceTimer = null;
      let timeout = null;

      const scheduleTimeout = (ms, msg) => {
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(() => {
          finishReject(new Error(msg));
        }, ms);
      };

      const finishResolve = () => {
        if (settled) return;
        settled = true;
        if (timeout) clearTimeout(timeout);
        if (statusGraceTimer) clearTimeout(statusGraceTimer);
        resolve();
      };

      const finishReject = (errorObj) => {
        if (settled) return;
        settled = true;
        if (timeout) clearTimeout(timeout);
        if (statusGraceTimer) clearTimeout(statusGraceTimer);
        reject(errorObj);
      };

      scheduleTimeout(8000, `Tempo esgotado ao conectar no Fire Stick (${device.ip}:${device.port}).`);

      ws = new WebSocket(wsUrl);

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'status') {
            if (msg.state === 'busy') {
              if (isTakeover) {
                if (statusGraceTimer) clearTimeout(statusGraceTimer);
                scheduleTimeout(
                  24000,
                  'Tempo esgotado ao aguardar autorização de transferência na TV.'
                );
                ws.send(JSON.stringify({
                  type: 'takeover',
                  senderName: pcHostname || 'CastViewer PC'
                }));
                return;
              }
              const activeWho = msg.activeSender ? ` (${msg.activeSender})` : '';
              const busyErr = new Error(
                msg.message || `TV já conectada em outro dispositivo${activeWho}.`
              );
              busyErr.isBusySession = true;
              busyErr.activeSender = msg.activeSender || 'outro dispositivo';
              busyErr.busyDevice = device;
              finishReject(busyErr);
              return;
            }
            if (msg.state === 'takeover-pending') {
              if (statusGraceTimer) clearTimeout(statusGraceTimer);
              const waitSec = Math.max(10, Number(msg.timeoutSec) || 15);
              const activeWho = msg.activeSender || 'dispositivo atual';
              scheduleTimeout(
                (waitSec + 6) * 1000,
                `Tempo esgotado aguardando confirmação em ${activeWho}.`
              );
              updateStatusPill('connecting', `Aguardando ${activeWho} (${waitSec}s)...`);
              showToast(
                msg.message || `Aguardando ${activeWho} aceitar (troca automática em ${waitSec}s)...`,
                'info'
              );
              return;
            }
            if (msg.state === 'takeover-denied') {
              if (statusGraceTimer) clearTimeout(statusGraceTimer);
              const activeWho = msg.activeSender || 'dispositivo atual';
              const deniedErr = new Error(
                msg.message || `${activeWho} recusou transferir a TV.`
              );
              deniedErr.isBusySession = true;
              deniedErr.activeSender = activeWho;
              deniedErr.busyDevice = device;
              finishReject(deniedErr);
              return;
            }
            if (msg.state === 'ready') {
              finishResolve();
            }
          }
        } catch (_) {}
      };

      ws.onopen = () => {
        // Wait for immediate onOpen status ("ready" or "busy"); give extra headroom during takeover handshake.
        statusGraceTimer = setTimeout(() => {
          finishResolve();
        }, isTakeover ? 1200 : 320);
      };

      ws.onerror = (_err) => {
        finishReject(new Error(`Falha ao conectar em ${wsUrl}. Verifique se o app está aberto na TV.`));
      };

      ws.onclose = () => {
        finishReject(new Error('Conexão com a TV encerrada antes de iniciar.'));
      };
    });

    // Step 4: WebRTC PeerConnection (Direct LAN P2P)
    peerConnection = new RTCPeerConnection({
      iceServers: []
    });

    pendingRemoteCandidates = [];

    // Send local ICE candidates to TV
    peerConnection.onicecandidate = (event) => {
      if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
        ws.send(
          JSON.stringify({
            type: 'ice-candidate',
            candidate: {
              candidate: event.candidate.candidate,
              sdpMid: event.candidate.sdpMid,
              sdpMLineIndex: event.candidate.sdpMLineIndex
            }
          })
        );
      }
    };

    // Monitor Connection State
    peerConnection.onconnectionstatechange = () => {
      console.log('CastViewer: WebRTC Connection State:', peerConnection.connectionState);
      if (peerConnection.connectionState === 'connected') {
        onTransmissionConnected(device, preset);
      } else if (
        peerConnection.connectionState === 'failed' ||
        peerConnection.connectionState === 'disconnected'
      ) {
        if (isTransmitting) {
          stopTransmission('Conexão P2P com a TV foi interrompida.');
        }
      }
    };

    // Add media tracks
    localStream.getTracks().forEach((track) => {
      peerConnection.addTrack(track, localStream);
    });

    // Codec & RTP Parameters
    preferH264Codec(peerConnection);
    await applyRtpSenderParameters(peerConnection, targetFps, targetBitrate);

    // Create SDP Offer
    const rawOffer = await peerConnection.createOffer();
    const optimizedSdp = optimizeSdpForUltraLowLatency(rawOffer.sdp, targetBitrate, targetFps);
    const finalOffer = new RTCSessionDescription({
      type: rawOffer.type,
      sdp: optimizedSdp
    });

    await peerConnection.setLocalDescription(finalOffer);
    await applyRtpSenderParameters(peerConnection, targetFps, targetBitrate);

    // Send Offer according to PROTOCOL.md
    const offerPayload = {
      type: 'offer',
      sdp: finalOffer.sdp,
      senderName: pcHostname,
      imageMode: liveImageMode,
      latencyMode: liveLatencyMode,
      video: {
        width: targetWidth,
        height: targetHeight,
        fps: targetFps
      },
      audio: audioCaptureEnabled,
      ...(isTakeover ? { forceTakeover: true } : {})
    };

    ws.send(JSON.stringify(offerPayload));
    sendClockConfigToReceiver();

    // Handle WebSocket signaling messages
    ws.onmessage = async (event) => {
      try {
        const msg = JSON.parse(event.data);

        if (msg.type === 'answer') {
          console.log('CastViewer: Recebido SDP Answer da TV');
          await peerConnection.setRemoteDescription(
            new RTCSessionDescription({
              type: 'answer',
              sdp: msg.sdp
            })
          );
          await applyRtpSenderParameters(peerConnection, targetFps, targetBitrate);

          while (pendingRemoteCandidates.length > 0) {
            const candidate = pendingRemoteCandidates.shift();
            try {
              await peerConnection.addIceCandidate(candidate);
            } catch (candErr) {
              console.warn('Erro ao aplicar candidato ICE:', candErr);
            }
          }
        } else if (msg.type === 'ice-candidate') {
          if (msg.candidate) {
            const cand = new RTCIceCandidate(msg.candidate);
            if (peerConnection && peerConnection.remoteDescription) {
              await peerConnection.addIceCandidate(cand);
            } else {
              pendingRemoteCandidates.push(cand);
            }
          }
        } else if (
          msg.type === 'remote-pause' ||
          (msg.type === 'remote-control' &&
            (msg.action === 'toggle-pause' || msg.action === 'pause' || msg.action === 'play'))
        ) {
          if (remotePauseMediaKeyPref) {
            const nextPaused =
              typeof msg.paused === 'boolean'
                ? msg.paused
                : msg.action === 'pause'
                  ? true
                  : msg.action === 'play'
                    ? false
                    : !isStreamPaused;
            await applyStreamPauseState(nextPaused, true);
          }
        } else if (msg.type === 'takeover-request') {
          showTakeoverRequestBanner({
            requestId: msg.requestId,
            requesterName: msg.requesterName,
            timeoutSec: msg.timeoutSec,
            message: msg.message
          });
        } else if (msg.type === 'takeover-cancelled') {
          hideTakeoverRequestBanner(msg.requestId || null);
        } else if (msg.type === 'preempted') {
          const endAction = window.CastTakeoverState
            ? window.CastTakeoverState.resolveSignalingEndAction(msg, device, acceptedOutgoingTakeoverTarget)
            : {
                kind: 'preempted',
                isError: false,
                toastMessage: msg.message || 'Outro dispositivo assumiu a TV.',
                banner: {
                  device,
                  activeSender: msg.by || 'outro dispositivo',
                  message: msg.message || 'Outro dispositivo assumiu a TV. Clique em "Assumir TV" para retomar.'
                }
              };
          handleSignalingBusyOrPreempted(endAction);
        } else if (msg.type === 'status') {
          if (msg.state === 'busy') {
            const endAction = window.CastTakeoverState
              ? window.CastTakeoverState.resolveSignalingEndAction(msg, device, null)
              : {
                  kind: 'busy',
                  isError: true,
                  toastMessage: msg.message || 'TV já conectada em outro dispositivo.',
                  banner: {
                    device,
                    activeSender: msg.activeSender || 'outro dispositivo',
                    message: msg.message || 'TV ocupada por outro dispositivo.'
                  }
                };
            handleSignalingBusyOrPreempted(endAction);
          } else if (msg.state === 'takeover-pending') {
            const waitSec = Math.max(5, Number(msg.timeoutSec) || 15);
            const activeWho = msg.activeSender || 'dispositivo atual';
            updateStatusPill('connecting', `Aguardando ${activeWho} (${waitSec}s)...`);
            showToast(
              msg.message || `Aguardando ${activeWho} aceitar (troca automática em ${waitSec}s)...`,
              'info'
            );
          } else if (msg.state === 'takeover-denied') {
            const endAction = window.CastTakeoverState
              ? window.CastTakeoverState.resolveSignalingEndAction(msg, device, null)
              : {
                  kind: 'denied',
                  isError: true,
                  toastMessage: msg.message || 'Solicitação de troca recusada.',
                  banner: {
                    device,
                    activeSender: msg.activeSender || 'dispositivo atual',
                    message: msg.message || 'Solicitação de troca recusada.'
                  }
                };
            handleSignalingBusyOrPreempted(endAction);
          } else if (msg.state === 'preempted') {
            const endAction = window.CastTakeoverState
              ? window.CastTakeoverState.resolveSignalingEndAction(msg, device, acceptedOutgoingTakeoverTarget)
              : {
                  kind: 'preempted',
                  isError: false,
                  toastMessage: msg.message || 'Outro dispositivo assumiu a TV.',
                  banner: {
                    device,
                    activeSender: msg.activeSender || 'outro dispositivo',
                    message: msg.message || 'Outro dispositivo assumiu a TV.'
                  }
                };
            handleSignalingBusyOrPreempted(endAction);
          }
        } else if (msg.type === 'bye') {
          acceptedOutgoingTakeoverTarget = null;
          stopTransmission('A TV encerrou a transmissão.', false, true);
        }
      } catch (parseErr) {
        console.error('CastViewer: Erro na mensagem WebSocket:', parseErr);
      }
    };

    ws.onclose = () => {
      if (acceptedOutgoingTakeoverTarget) {
        const endAction = window.CastTakeoverState
          ? window.CastTakeoverState.resolveSignalingEndAction(null, device, acceptedOutgoingTakeoverTarget)
          : {
              kind: 'preempted',
              isError: false,
              toastMessage: 'Outro dispositivo assumiu a TV.',
              banner: {
                device: acceptedOutgoingTakeoverTarget.device || device,
                activeSender: acceptedOutgoingTakeoverTarget.by || 'outro dispositivo',
                message: 'Outro dispositivo assumiu a TV. Clique em "Assumir TV" para retomar.'
              }
            };
        handleSignalingBusyOrPreempted(endAction);
        return;
      }
      if (isTransmitting || isConnecting) {
        stopTransmission('Conexão de sinalização com a TV fechada.', isConnecting, true);
      }
    };
  } catch (err) {
    console.error('CastViewer: Falha na transmissão:', err);
    if (err && err.isBusySession) {
      stopTransmission();
      showBusyTakeoverBanner(err.busyDevice || device, err.activeSender, err.message);
      showToast(err.message, 'error');
    } else {
      showToast(err.message || 'Falha ao iniciar transmissão.', 'error');
      stopTransmission();
    }
  }
}

// Connected State Handler
function onTransmissionConnected(device, preset) {
  const api = getApi();
  isConnecting = false;
  isTransmitting = true;
  isStreamPaused = false;
  updateActionButtonState();
  updateStreamPauseUI();
  startReplayRecorder();
  updateCaptureToolbarState();
  sendClockConfigToReceiver();

  const activePreset = preset || getSelectedPreset();
  if (peerConnection && activePreset) {
    applyRtpSenderParameters(peerConnection, activePreset.fps, activePreset.bitrate).catch(() => {});
  }

  // Save last connected TV for auto-reconnect (BETA)
  saveLastTv(device);

  if (api.setStreamingState) {
    api.setStreamingState(true, device.name);
  }

  // In Low-RAM mode, free off-screen source thumbnails & Chromium image cache while streaming
  if (lowRamMode) {
    purgeIdleSourceThumbnails();
    if (api.trimMemory) {
      api.trimMemory().catch(() => {});
    }
  }
  syncVuMeterLifecycle();

  // Update Compact Bottom Dock Status Pill to streaming state
  if (dockRttFpsText) {
    dockRttFpsText.textContent = '-- ms · 60 fps';
  }

  updateStatusPill('streaming', 'Ao vivo');
  showToast(`Transmitindo para ${device.name}!`, 'success');

  prevBytesSent = 0;
  prevStatsTimestamp = 0;
  prevPacketsSent = 0;
  prevPacketsLost = 0;
  startStatsPolling();
}

// Stop Transmission
function stopTransmission(reasonMessage = null, isError = false, skipBye = false) {
  const api = getApi();
  const wasActiveOrConnecting = isTransmitting || isConnecting;
  acceptedOutgoingTakeoverTarget = null;
  isConnecting = false;
  isTransmitting = false;
  isStreamPaused = false;
  hideTakeoverRequestBanner();
  stopReplayRecorder();
  updateStreamPauseUI();
  updateCaptureToolbarState();

  if (wasActiveOrConnecting) {
    playDisconnectAnimation(reasonMessage);
  }

  if (statsInterval) {
    clearInterval(statsInterval);
    statsInterval = null;
  }

  cleanupNativeLoopbackAudio();

  if (!skipBye && ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify({ type: 'bye' }));
    } catch (_) {}
  }

  if (ws) {
    ws.onclose = null;
    ws.onerror = null;
    ws.close();
    ws = null;
  }

  if (peerConnection) {
    peerConnection.close();
    peerConnection = null;
  }

  if (localStream) {
    localStream.getTracks().forEach((track) => track.stop());
    localStream = null;
  }

  pendingRemoteCandidates = [];

  if (api.setStreamingState) {
    api.setStreamingState(false, '');
  }

  // Reset Compact Bottom Dock Status Pill to idle state
  if (dockRttFpsText) {
    dockRttFpsText.textContent = 'Pronto';
  }

  // Reset Advanced Telemetry UI
  if (advTelemetryIdle) advTelemetryIdle.classList.remove('hidden');
  if (advTelemetryGrid) advTelemetryGrid.classList.add('hidden');

  updateActionButtonState();

  if (lowRamMode) {
    if (api.trimMemory) {
      api.trimMemory().catch(() => {});
    }
    if (shouldRefreshSources()) {
      refreshSources().catch(() => {});
    }
  }

  if (reasonMessage) {
    showToast(reasonMessage, isError ? 'error' : 'info');
  }
}

function restartStatsPollingIfActive() {
  if (isTransmitting && peerConnection) {
    startStatsPolling();
  }
}

// Real-time Telemetry Polling (includes advanced diagnostics: Packet Loss, Jitter, RTT, FPS, Bitrate)
function startStatsPolling() {
  if (statsInterval) clearInterval(statsInterval);
  zeroFramesStreak = 0;
  zeroFramesWarnedThisSession = false;

  if (advTelemetryIdle) advTelemetryIdle.classList.add('hidden');
  if (advTelemetryGrid) advTelemetryGrid.classList.remove('hidden');

  const intervalMs = lowSpecMode ? 2500 : 1000;
  statsInterval = setInterval(async () => {
    if (document.hidden || !peerConnection || peerConnection.connectionState !== 'connected') return;

    try {
      const stats = await peerConnection.getStats();
      let rttMs = null;
      let jitterMs = null;
      let packetLossPct = null;
      let packetsLostCount = 0;
      let bitrateMbps = null;
      let currentFps = null;
      let frameW = null;
      let frameH = null;
      let encoderImpl = null;
      let limitReason = null;
      let sawVideoOutbound = false;
      let framesEncoded = 0;

      stats.forEach((report) => {
        if (report.type === 'outbound-rtp' && report.kind === 'video') {
          sawVideoOutbound = true;
          framesEncoded = report.framesEncoded || 0;
          const now = report.timestamp;
          const bytes = report.bytesSent || 0;

          if (prevStatsTimestamp > 0 && now > prevStatsTimestamp) {
            const deltaBytes = bytes - prevBytesSent;
            const deltaSec = (now - prevStatsTimestamp) / 1000;
            const bps = (deltaBytes * 8) / deltaSec;
            bitrateMbps = (bps / (1024 * 1024)).toFixed(1);
          }

          prevBytesSent = bytes;
          prevStatsTimestamp = now;

          if (report.framesPerSecond !== undefined) {
            currentFps = Math.round(report.framesPerSecond);
          }
          if (report.frameWidth) frameW = report.frameWidth;
          if (report.frameHeight) frameH = report.frameHeight;
          if (report.encoderImplementation) encoderImpl = report.encoderImplementation;
          if (report.qualityLimitationReason) limitReason = report.qualityLimitationReason;
        }

        if (report.type === 'remote-inbound-rtp' && report.kind === 'video') {
          if (report.jitter !== undefined) {
            jitterMs = Math.round(report.jitter * 1000);
          }
          if (report.packetsLost !== undefined) {
            packetsLostCount = report.packetsLost;
          }
          if (report.fractionLost !== undefined) {
            packetLossPct = (report.fractionLost * 100).toFixed(1);
          }
          if (report.roundTripTime !== undefined) {
            rttMs = Math.round(report.roundTripTime * 1000);
          }
        }

        if (
          report.type === 'candidate-pair' &&
          report.state === 'succeeded' &&
          (report.selected || report.nominated)
        ) {
          if (report.currentRoundTripTime !== undefined && rttMs === null) {
            rttMs = Math.round(report.currentRoundTripTime * 1000);
          }
        }
      });

      if (packetLossPct === null) packetLossPct = '0.0';
      if (jitterMs === null) jitterMs = 0;

      // Guard against black screen / stalled video encoder on incompatible drivers
      if (sawVideoOutbound && !isStreamPaused) {
        if (framesEncoded === 0) {
          zeroFramesStreak += 1;
          const stallThreshold = lowSpecMode ? 3 : 5;
          if (!zeroFramesWarnedThisSession && !compatMode && zeroFramesStreak >= stallThreshold) {
            zeroFramesWarnedThisSession = true;
            showToast(
              'Nenhum quadro codificado pela GPU. Se a TV ficar preta (NVIDIA/Intel), ative o Modo Compatibilidade em Ajustes.',
              'error'
            );
          }
        } else {
          zeroFramesStreak = 0;
        }
      } else {
        zeroFramesStreak = 0;
      }

      // Update Top Status Pill: "Ao vivo • 12ms • 60 FPS" (or keep "Em Pausa" while paused)
      if (isStreamPaused) {
        updateStatusPill('connecting', 'Em Pausa');
        if (dockRttFpsText) {
          dockRttFpsText.textContent = 'EM PAUSA';
        }
      } else {
        const statusParts = ['Ao vivo'];
        if (rttMs !== null) statusParts.push(`${rttMs}ms`);
        if (currentFps !== null) statusParts.push(`${currentFps} FPS`);
        updateStatusPill('streaming', statusParts.join(' • '));

        // Update Compact Dock RTT · FPS Status Pill
        if (dockRttFpsText) {
          const rttStr = rttMs !== null ? `${rttMs} ms` : '4 ms';
          const fpsStr = currentFps !== null ? `${currentFps} fps` : '60 fps';
          dockRttFpsText.textContent = `${rttStr} · ${fpsStr}`;
        }
      }

      // Update Bottom Dock Telemetry Bar
      if (rttMs !== null && telemetryRtt) {
        telemetryRtt.textContent = `${rttMs} ms`;
      }
      if (telemetryJitter) {
        telemetryJitter.textContent = `${jitterMs} ms`;
      }
      if (telemetryLoss) {
        telemetryLoss.textContent = `${packetLossPct}%`;
      }
      if (currentFps !== null && telemetryFps) {
        telemetryFps.textContent = `${currentFps} fps`;
      }
      if (bitrateMbps !== null && telemetryBitrate) {
        telemetryBitrate.textContent = `${bitrateMbps} Mbps`;
      }
      if (frameW && frameH && telemetryResolution) {
        telemetryResolution.textContent = `H.264 • ${frameW}x${frameH}`;
      }

      // Update Settings Advanced Telemetry Grid (BETA)
      if (advRtt && rttMs !== null) advRtt.textContent = `${rttMs} ms`;
      if (advJitter) advJitter.textContent = `${jitterMs} ms`;
      if (advLoss) advLoss.textContent = `${packetLossPct}%`;
      if (advLossPackets) advLossPackets.textContent = `${packetsLostCount} perdidos`;
      if (advFps && currentFps !== null) advFps.textContent = `${currentFps} FPS`;
      if (advBitrate && bitrateMbps !== null) advBitrate.textContent = `${bitrateMbps} Mbps`;
      if (advResolution && frameW && frameH) advResolution.textContent = `${frameW}x${frameH}`;
      if (advCodec && encoderImpl) {
        const isHw = /MediaFoundation|NVENC|AMF|QuickSync|VAAPI|V4L2|ExternalEncoder/i.test(encoderImpl);
        advCodec.textContent = isHw ? 'H.264 (HW)' : 'H.264';
      }
      if (advCodecSub) {
        const limitLabel =
          limitReason && limitReason !== 'none' ? `Limite: ${limitReason}` : 'Zero-Delay • L1T1';
        advCodecSub.textContent = limitLabel;
      }
      if (advMode) advMode.textContent = liveImageMode === 'detail' ? 'Nitidez' : 'Movimento';
      if (advHint) {
        const presetForHint = getSelectedPreset();
        const detailPrefLabel =
          presetForHint && presetForHint.fps >= 60
            ? 'maintain-framerate (+35% Mbps)'
            : 'maintain-resolution (+35% Mbps)';
        advHint.textContent =
          liveImageMode === 'detail' ? detailPrefLabel : 'maintain-framerate (60 FPS)';
      }

      const activePreset = getSelectedPreset();
      if (advTargetFps && activePreset) {
        advTargetFps.textContent = `Alvo: ${activePreset.fps} FPS`;
      }
    } catch (err) {
      console.warn('CastViewer: Erro na telemetria WebRTC:', err);
    }
  }, intervalMs);
}

// Utility: HTML Escaping
function escapeHtml(text) {
  if (!text) return '';
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

// ============================================================================
// Localization Helper & Minimalist Kinetic Precision Animation Engine
// ============================================================================
const brandLogoImg = new Image();
brandLogoImg.src = '../assets/icon.png';

function getAnimStrings() {
  const lang = (navigator.language || 'pt-BR').toLowerCase();
  if (lang.startsWith('es')) {
    return {
      introSubtitle: 'TRANSMISIÓN EN TIEMPO REAL',
      connectHeader: 'CONEXIÓN DIRECTA • 60 FPS',
      connectTitle: (dev) => `Transmitiendo a ${dev}`,
      connectSubtitle: (preset) => `${preset} • Tiempo Real`,
      defaultDevice: 'Fire TV',
      switchWindowHeader: 'CAMBIANDO VENTANA',
      switchScreenHeader: 'CAMBIANDO PANTALLA',
      switchWindowSub: 'Enfoque exclusivo en la aplicación',
      switchScreenSub: 'Duplicación de pantalla completa',
      disconnectHeader: 'SESIÓN FINALIZADA',
      disconnectTitle: 'Transmisión Finalizada',
      disconnectSub: 'Listo para transmitir de nuevo',
      pauseHeader: 'CONTROL FIRE TV • PAUSA',
      pauseTitle: 'Transmisión en Pausa',
      pauseSub: 'Cuadro congelado • Audio silenciado',
      resumeHeader: 'CONTROL FIRE TV • EN VIVO',
      resumeTitle: 'Transmisión Reanudada',
      resumeSub: 'Sincronizado en Tiempo Real • 60 FPS',
      toastRemotePaused: 'Transmisión pausada por el control de Fire TV',
      toastRemoteResumed: 'Transmisión reanudada por el control de Fire TV',
      toastLocalPaused: 'Transmisión en pausa.',
      toastLocalResumed: '¡Transmisión reanudada en vivo!',
      pauseBtnResume: 'Reanudar',
      imageModeHeader: 'MODO DE IMAGEN',
      imageModeDetailTitle: 'Ultra Nitidez',
      imageModeDetailSub: 'Resolución 1:1 • +35% Bitrate',
      imageModeMotionTitle: 'Fluidez Máxima',
      imageModeMotionSub: '60 FPS prioritario • Menor latencia',
      videoHeader: 'CALIDAD DE VIDEO',
      videoSub: 'Perfil aplicado en Tiempo Real',
      latencyHeader: 'CONTROL DE LATENCIA',
      latencyUltraTitle: 'Ultra Baja • 0 ms',
      latencyUltraSub: 'Respuesta inmediata en Tiempo Real',
      latencyBalancedTitle: 'Equilibrada • Wi-Fi',
      latencyBalancedSub: 'Buffer adaptativo (~60 ms) estable'
    };
  }
  if (lang.startsWith('en')) {
    return {
      introSubtitle: 'REAL-TIME STREAMING',
      connectHeader: 'DIRECT CONNECTION • 60 FPS',
      connectTitle: (dev) => `Streaming to ${dev}`,
      connectSubtitle: (preset) => `${preset} • Real-Time`,
      defaultDevice: 'Fire TV',
      switchWindowHeader: 'SWITCHING WINDOW',
      switchScreenHeader: 'SWITCHING SCREEN',
      switchWindowSub: 'Exclusive application focus',
      switchScreenSub: 'Full screen mirroring',
      disconnectHeader: 'SESSION ENDED',
      disconnectTitle: 'Stream Ended',
      disconnectSub: 'Ready to stream again',
      pauseHeader: 'FIRE TV REMOTE • PAUSED',
      pauseTitle: 'Stream Paused',
      pauseSub: 'Frame frozen • Audio muted',
      resumeHeader: 'FIRE TV REMOTE • LIVE',
      resumeTitle: 'Stream Resumed',
      resumeSub: 'Real-Time Synced • 60 FPS',
      toastRemotePaused: 'Stream paused by Fire TV remote',
      toastRemoteResumed: 'Stream resumed by Fire TV remote',
      toastLocalPaused: 'Stream paused.',
      toastLocalResumed: 'Stream resumed live!',
      pauseBtnResume: 'Resume',
      imageModeHeader: 'IMAGE MODE',
      imageModeDetailTitle: 'Ultra Sharpness',
      imageModeDetailSub: '1:1 Native Pixels • +35% Bitrate',
      imageModeMotionTitle: 'Maximum Fluidity',
      imageModeMotionSub: '60 FPS Priority • Ultra-Low Latency',
      videoHeader: 'VIDEO QUALITY',
      videoSub: 'Applied in Real-Time',
      latencyHeader: 'LATENCY CONTROL',
      latencyUltraTitle: 'Ultra Low • 0 ms',
      latencyUltraSub: 'Instant Zero-Buffer Response',
      latencyBalancedTitle: 'Balanced • Wi-Fi',
      latencyBalancedSub: 'Adaptive Micro-Buffer (~60 ms)'
    };
  }
  // Default: pt-BR / pt (ZERO English buzzwords!)
  return {
    introSubtitle: 'TRANSMISSÃO EM TEMPO REAL',
    connectHeader: 'CONEXÃO DIRETA • 60 FPS',
    connectTitle: (dev) => `Transmitindo para ${dev}`,
    connectSubtitle: (preset) => `${preset} • Tempo Real`,
    defaultDevice: 'Fire TV',
    switchWindowHeader: 'ALTERNANDO JANELA',
    switchScreenHeader: 'ALTERNANDO TELA',
    switchWindowSub: 'Foco exclusivo no aplicativo',
    switchScreenSub: 'Espelhamento de tela inteira',
    disconnectHeader: 'SESSÃO FINALIZADA',
    disconnectTitle: 'Transmissão Encerrada',
    disconnectSub: 'Pronto para transmitir novamente',
    pauseHeader: 'CONTROLE FIRE TV • PAUSA',
    pauseTitle: 'Transmissão em Pausa',
    pauseSub: 'Quadro congelado • Áudio silenciado',
    resumeHeader: 'CONTROLE FIRE TV • AO VIVO',
    resumeTitle: 'Transmissão Retomada',
    resumeSub: 'Sincronizado em Tempo Real • 60 FPS',
    toastRemotePaused: 'Transmissão pausada pelo controle do Fire TV',
    toastRemoteResumed: 'Transmissão retomada pelo controle do Fire TV',
    toastLocalPaused: 'Transmissão pausada.',
    toastLocalResumed: 'Transmissão retomada ao vivo!',
    pauseBtnResume: 'Retomar',
    imageModeHeader: 'MODO DE IMAGEM',
    imageModeDetailTitle: 'Ultra Nitidez',
    imageModeDetailSub: 'Resolução nativa 1:1 • +35% Bitrate',
    imageModeMotionTitle: 'Fluidez Máxima',
    imageModeMotionSub: '60 FPS prioritário • Menor latência',
    videoHeader: 'PERFIL DE VÍDEO',
    videoSub: 'Atualizado ao vivo no PC e na TV',
    latencyHeader: 'CONTROLE DE LATÊNCIA',
    latencyUltraTitle: 'Ultra Baixa • 0 ms',
    latencyUltraSub: 'Resposta imediata em Tempo Real',
    latencyBalancedTitle: 'Equilibrada • Wi-Fi',
    latencyBalancedSub: 'Micro-buffer adaptativo (~60 ms)'
  };
}

function prefersReducedMotion() {
  return Boolean(
    lowSpecMode || (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  );
}

function springEaseOut(x) {
  const clamped = Math.min(1, Math.max(0, x));
  return 1 - Math.pow(1 - clamped, 4);
}

function drawRoundedRectPath(ctx, x, y, w, h, r) {
  const rad = Math.min(r, w * 0.5, h * 0.5);
  ctx.beginPath();
  ctx.moveTo(x + rad, y);
  ctx.lineTo(x + w - rad, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + rad);
  ctx.lineTo(x + w, y + h - rad);
  ctx.quadraticCurveTo(x + w, y + h, x + w - rad, y + h);
  ctx.lineTo(x + rad, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - rad);
  ctx.lineTo(x, y + rad);
  ctx.quadraticCurveTo(x, y, x + rad, y);
  ctx.closePath();
}

// Moment 1: Minimalist Kinetic Precision App Startup Intro
function playDesktopCinematicIntro() {
  const canvas = document.getElementById('cinematic-intro-canvas');
  if (!canvas) return;

  if (prefersReducedMotion()) {
    canvas.remove();
    return;
  }

  const ctx = canvas.getContext('2d');
  if (!ctx) {
    canvas.remove();
    return;
  }

  const s = getAnimStrings();
  const DURATION_MS = 2050;
  const startTime = performance.now();

  function renderFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    const cx = w * 0.5;
    const cy = h * 0.43;

    const outroP = Math.min(1, Math.max(0, (t - 0.74) / 0.26));
    const outroEase = springEaseOut(outroP);
    const masterAlpha = 1 - outroEase;
    const cameraScale = 1 + 0.06 * outroEase;

    canvas.style.background = 'transparent';
    ctx.fillStyle = `rgba(6, 7, 10, ${masterAlpha.toFixed(3)})`;
    ctx.fillRect(0, 0, w, h);

    ctx.translate(cx, cy);
    ctx.scale(cameraScale, cameraScale);
    ctx.translate(-cx, -cy);

    // 1. Subtle Volumetric Radial Aura (rgba(255, 107, 26, 0.16))
    const auraIn = springEaseOut(t / 0.35);
    const auraAlpha = 0.16 * auraIn * masterAlpha;
    if (auraAlpha > 0.004) {
      const auraR = Math.min(w, h) * 0.42;
      const grad = ctx.createRadialGradient(cx, cy, 4, cx, cy, auraR);
      grad.addColorStop(0, `rgba(255, 107, 26, ${auraAlpha.toFixed(3)})`);
      grad.addColorStop(0.55, `rgba(255, 107, 26, ${(auraAlpha * 0.35).toFixed(3)})`);
      grad.addColorStop(1, 'rgba(6, 7, 10, 0)');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(cx, cy, auraR, 0, Math.PI * 2);
      ctx.fill();
    }

    // 2. Minimalist Razor-Thin (1.5px) Horizon Laser Line with Photon Tips
    if (t < 0.48) {
      const lp = t / 0.48;
      const spanEase = lp < 0.5 ? springEaseOut(lp / 0.5) : 1 - springEaseOut((lp - 0.5) / 0.5) * 0.35;
      const halfSpan = Math.max(8, w * 0.36 * spanEase);
      const lineAlpha = (1 - Math.pow(lp, 1.5)) * masterAlpha;

      const lGrad = ctx.createLinearGradient(cx - halfSpan, cy, cx + halfSpan, cy);
      lGrad.addColorStop(0, 'rgba(255, 107, 26, 0)');
      lGrad.addColorStop(0.2, `rgba(255, 107, 26, ${(lineAlpha * 0.85).toFixed(3)})`);
      lGrad.addColorStop(0.5, `rgba(250, 250, 250, ${lineAlpha.toFixed(3)})`);
      lGrad.addColorStop(0.8, `rgba(255, 107, 26, ${(lineAlpha * 0.85).toFixed(3)})`);
      lGrad.addColorStop(1, 'rgba(255, 107, 26, 0)');

      ctx.fillStyle = lGrad;
      ctx.fillRect(cx - halfSpan, cy - 0.75, halfSpan * 2, 1.5);

      // Leading photon dots at horizon tips
      ctx.fillStyle = `rgba(255, 138, 61, ${(lineAlpha * 0.9).toFixed(3)})`;
      ctx.beginPath();
      ctx.arc(cx - halfSpan * 0.78, cy, 2, 0, Math.PI * 2);
      ctx.arc(cx + halfSpan * 0.78, cy, 2, 0, Math.PI * 2);
      ctx.fill();
    }

    // 3. Razor-Thin 1.5px Geometric Orbital Arc + Glowing Leading Photon Dot
    const arcP = springEaseOut((t - 0.06) / 0.62);
    if (arcP > 0) {
      const orbitR = 54;
      const startAng = -Math.PI * 0.5;
      const endAng = startAng + Math.PI * 2 * arcP;

      // Subtle reference track
      ctx.strokeStyle = `rgba(255, 255, 255, ${(0.08 * masterAlpha).toFixed(3)})`;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(cx, cy, orbitR, 0, Math.PI * 2);
      ctx.stroke();

      // Active Fire-Orange orbital arc
      ctx.save();
      ctx.strokeStyle = `rgba(255, 107, 26, ${(0.92 * masterAlpha).toFixed(3)})`;
      ctx.shadowColor = 'rgba(255, 107, 26, 0.75)';
      ctx.shadowBlur = 10;
      ctx.lineWidth = 1.5;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.arc(cx, cy, orbitR, startAng, endAng);
      ctx.stroke();

      // Glowing leading photon dot
      const dotX = cx + Math.cos(endAng) * orbitR;
      const dotY = cy + Math.sin(endAng) * orbitR;
      ctx.fillStyle = `rgba(250, 250, 250, ${masterAlpha.toFixed(3)})`;
      ctx.shadowColor = '#FF6B1A';
      ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.arc(dotX, dotY, 2.8, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

    // 4. Official CastViewer Squircle Logo
    const logoP = springEaseOut((t - 0.1) / 0.45);
    if (logoP > 0) {
      const logoSize = 64;
      const scale = 0.86 + 0.14 * logoP;
      ctx.save();
      ctx.translate(cx, cy);
      ctx.scale(scale, scale);
      ctx.globalAlpha = logoP * masterAlpha;

      drawRoundedRectPath(ctx, -logoSize * 0.5, -logoSize * 0.5, logoSize, logoSize, 15);
      ctx.save();
      ctx.clip();
      if (brandLogoImg.complete && brandLogoImg.naturalWidth > 0) {
        ctx.drawImage(brandLogoImg, -logoSize * 0.5, -logoSize * 0.5, logoSize, logoSize);
      } else {
        ctx.fillStyle = '#11131A';
        ctx.fillRect(-logoSize * 0.5, -logoSize * 0.5, logoSize, logoSize);
        ctx.strokeStyle = '#FAFAFA';
        ctx.lineWidth = 2.2;
        drawRoundedRectPath(ctx, -18, -13, 36, 24, 4);
        ctx.stroke();
        ctx.fillStyle = '#FF6B1A';
        ctx.beginPath();
        ctx.moveTo(-6, -6);
        ctx.lineTo(9, -1);
        ctx.lineTo(-6, 4);
        ctx.closePath();
        ctx.fill();
      }
      ctx.restore();

      // Crisp specular squircle border
      ctx.strokeStyle = `rgba(255, 255, 255, ${(0.18 * masterAlpha).toFixed(3)})`;
      ctx.lineWidth = 1;
      drawRoundedRectPath(ctx, -logoSize * 0.5, -logoSize * 0.5, logoSize, logoSize, 15);
      ctx.stroke();
      ctx.restore();
    }

    // 5. Crisp Barlow Bold Italic Wordmark + Localized Subtitle
    const textP = springEaseOut((t - 0.24) / 0.42);
    if (textP > 0) {
      const ta = textP * masterAlpha;
      const yOffset = (1 - textP) * 12;
      const titleY = cy + 92 + yOffset;

      ctx.save();
      ctx.textAlign = 'center';
      ctx.font = 'italic 700 32px "Barlow", sans-serif';
      ctx.fillStyle = `rgba(250, 250, 250, ${ta.toFixed(3)})`;
      ctx.fillText('CastViewer', cx, titleY);

      // Razor-thin 1.5px accent rule
      const ruleHalfW = 48 * textP;
      const ruleY = titleY + 11;
      const rGrad = ctx.createLinearGradient(cx - ruleHalfW, ruleY, cx + ruleHalfW, ruleY);
      rGrad.addColorStop(0, 'rgba(255, 107, 26, 0)');
      rGrad.addColorStop(0.5, `rgba(255, 107, 26, ${ta.toFixed(3)})`);
      rGrad.addColorStop(1, 'rgba(255, 107, 26, 0)');
      ctx.fillStyle = rGrad;
      ctx.fillRect(cx - ruleHalfW, ruleY - 0.75, ruleHalfW * 2, 1.5);

      // Localized subtitle ("TRANSMISSÃO EM TEMPO REAL" in PT)
      ctx.font = 'italic 700 11px "Barlow", sans-serif';
      ctx.fillStyle = `rgba(161, 161, 170, ${(ta * 0.92).toFixed(3)})`;
      if ('letterSpacing' in ctx) {
        ctx.letterSpacing = '3px';
      }
      ctx.fillText(s.introSubtitle, cx, ruleY + 19);
      ctx.restore();
    }

    ctx.restore();

    if (t < 1) {
      requestAnimationFrame(renderFrame);
    } else {
      canvas.remove();
    }
  }

  requestAnimationFrame(renderFrame);
}

// ============================================================================
// Minimalist WebAudio Micro-Synths (Connect, Switch Source, Disconnect, Pause)
// ============================================================================
let warpAnimRafId = null;
let uiSfxCtx = null;

// Reuse a single AudioContext for short UI chimes so WASAPI doesn't churn alongside LoopbackCapture
function closeUiSfxAudioContext() {
  if (!uiSfxCtx) return;
  try {
    if (uiSfxCtx.state !== 'closed') {
      uiSfxCtx.close().catch(() => {});
    }
  } catch (_) {}
  uiSfxCtx = null;
}

function getUiSfxAudioContext() {
  if (lowSpecMode || lowRamMode) return null;
  try {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return null;
    if (!uiSfxCtx || uiSfxCtx.state === 'closed') {
      uiSfxCtx = new AudioCtx();
    }
    if (uiSfxCtx.state === 'suspended') {
      uiSfxCtx.resume().catch(() => {});
    }
    return uiSfxCtx;
  } catch (_) {
    return null;
  }
}

function playCastWarpAudio() {
  try {
    const ctx = getUiSfxAudioContext();
    if (!ctx) return;
    const now = ctx.currentTime;

    const masterGain = ctx.createGain();
    masterGain.gain.setValueAtTime(0.001, now);
    masterGain.gain.exponentialRampToValueAtTime(0.09, now + 0.08);
    masterGain.gain.setValueAtTime(0.09, now + 0.72);
    masterGain.gain.exponentialRampToValueAtTime(0.0001, now + 1.25);
    masterGain.connect(ctx.destination);

    // Subtle warm harmonic glide (146.83Hz D3 -> 293.66Hz D4)
    const glideOsc = ctx.createOscillator();
    const glideGain = ctx.createGain();
    glideOsc.type = 'sine';
    glideOsc.frequency.setValueAtTime(146.83, now);
    glideOsc.frequency.exponentialRampToValueAtTime(293.66, now + 0.72);
    glideGain.gain.setValueAtTime(0.35, now);
    glideGain.gain.exponentialRampToValueAtTime(0.001, now + 0.78);
    glideOsc.connect(glideGain);
    glideGain.connect(masterGain);
    glideOsc.start(now);
    glideOsc.stop(now + 0.8);

    // Crisp Apple-TV/Taycan harmonic fifth lock chime (D5 587.33Hz + A5 880Hz)
    const notes = [587.33, 880.0];
    notes.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const nGain = ctx.createGain();
      const t0 = now + 0.42 + idx * 0.055;
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, t0);
      nGain.gain.setValueAtTime(0.001, t0);
      nGain.gain.exponentialRampToValueAtTime(0.28 / (idx + 1), t0 + 0.04);
      nGain.gain.exponentialRampToValueAtTime(0.0001, now + 1.22);
      osc.connect(nGain);
      nGain.connect(masterGain);
      osc.start(t0);
      osc.stop(now + 1.24);
    });

    setTimeout(() => {
      try {
        masterGain.disconnect();
      } catch (_) {}
    }, 1350);
  } catch (_) {}
}

function playMinimalSwitchAudio(isWindow) {
  try {
    const ctx = getUiSfxAudioContext();
    if (!ctx) return;
    const now = ctx.currentTime;

    const masterGain = ctx.createGain();
    masterGain.gain.setValueAtTime(0.001, now);
    masterGain.gain.exponentialRampToValueAtTime(0.075, now + 0.02);
    masterGain.gain.exponentialRampToValueAtTime(0.0001, now + 0.36);
    masterGain.connect(ctx.destination);

    // Subtle tactile harmonic click + airy fifth interval
    const f1 = isWindow ? 523.25 : 440.0;
    const f2 = isWindow ? 783.99 : 659.25;

    const osc1 = ctx.createOscillator();
    osc1.type = 'sine';
    osc1.frequency.setValueAtTime(f1, now);
    osc1.frequency.exponentialRampToValueAtTime(f2, now + 0.14);
    osc1.connect(masterGain);
    osc1.start(now);
    osc1.stop(now + 0.35);

    setTimeout(() => {
      try {
        masterGain.disconnect();
      } catch (_) {}
    }, 450);
  } catch (_) {}
}

function playMinimalDisconnectAudio() {
  try {
    const ctx = getUiSfxAudioContext();
    if (!ctx) return;
    const now = ctx.currentTime;

    const masterGain = ctx.createGain();
    masterGain.gain.setValueAtTime(0.001, now);
    masterGain.gain.exponentialRampToValueAtTime(0.08, now + 0.03);
    masterGain.gain.exponentialRampToValueAtTime(0.0001, now + 0.68);
    masterGain.connect(ctx.destination);

    // Warm descending two-note harmonic chime (A4 440Hz -> D4 293.66Hz)
    const notes = [440.0, 293.66];
    notes.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const nGain = ctx.createGain();
      const t0 = now + idx * 0.11;
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, t0);
      nGain.gain.setValueAtTime(0.001, t0);
      nGain.gain.exponentialRampToValueAtTime(0.3, t0 + 0.025);
      nGain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.52);
      osc.connect(nGain);
      nGain.connect(masterGain);
      osc.start(t0);
      osc.stop(t0 + 0.54);
    });

    setTimeout(() => {
      try {
        masterGain.disconnect();
      } catch (_) {}
    }, 800);
  } catch (_) {}
}

function playMinimalPauseAudio(isPaused) {
  try {
    const ctx = getUiSfxAudioContext();
    if (!ctx) return;
    const now = ctx.currentTime;

    const masterGain = ctx.createGain();
    masterGain.gain.setValueAtTime(0.001, now);
    masterGain.gain.exponentialRampToValueAtTime(0.07, now + 0.02);
    masterGain.gain.exponentialRampToValueAtTime(0.0001, now + 0.34);
    masterGain.connect(ctx.destination);

    const osc = ctx.createOscillator();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(isPaused ? 587.33 : 440.0, now);
    osc.frequency.exponentialRampToValueAtTime(isPaused ? 392.0 : 659.25, now + 0.16);
    osc.connect(masterGain);
    osc.start(now);
    osc.stop(now + 0.32);

    setTimeout(() => {
      try {
        masterGain.disconnect();
      } catch (_) {}
    }, 420);
  } catch (_) {}
}

// ============================================================================
// Moment 2: Start Casting ("playCastWarpAnimation") — Minimalist Orbital Lock
// ============================================================================
function playCastWarpAnimation(deviceName, presetLabel, mode = 'connect') {
  if (mode === 'disconnect') {
    playDisconnectAnimation(deviceName);
    return;
  }

  const overlay = document.getElementById('hyper-cast-warp-overlay');
  const canvas = document.getElementById('hyper-cast-warp-canvas');
  const headerEl = document.getElementById('hyper-cast-header');
  const titleEl = document.getElementById('hyper-cast-title');
  const subtitleEl = document.getElementById('hyper-cast-subtitle');
  const chargePctEl = document.getElementById('hyper-cast-charge-pct');
  const emblemEl = document.getElementById('hyper-cast-emblem');

  if (!overlay || !canvas || prefersReducedMotion()) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  if (warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
  }

  playCastWarpAudio();

  const s = getAnimStrings();
  const cleanDevice = deviceName || s.defaultDevice;
  const cleanPreset = presetLabel || '1080p • 60 FPS';

  overlay.classList.remove('hidden', 'mode-switch', 'mode-disconnect');
  if (headerEl) headerEl.textContent = s.connectHeader;
  if (titleEl) titleEl.textContent = s.connectTitle(cleanDevice);
  if (subtitleEl) subtitleEl.textContent = s.connectSubtitle(cleanPreset);
  if (chargePctEl) {
    chargePctEl.classList.remove('hidden');
    chargePctEl.textContent = '0%';
  }
  if (emblemEl) {
    emblemEl.style.opacity = '1';
    emblemEl.style.transform = 'scale(1)';
  }

  overlay.style.opacity = '1';
  overlay.style.clipPath = 'none';

  const DURATION_MS = 1250;
  const LOCK_MS = 780;
  const startTime = performance.now();

  function renderConnectFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    const cx = w * 0.5;
    const cy = h * 0.41;

    const chargeProgress = Math.min(1, elapsed / LOCK_MS);
    const chargeEase = springEaseOut(chargeProgress);
    if (chargePctEl) {
      chargePctEl.textContent = `${Math.round(chargeEase * 100)}%`;
    }

    if (emblemEl) {
      const lockPulse = elapsed >= LOCK_MS ? 0.05 * Math.sin(((elapsed - LOCK_MS) / (DURATION_MS - LOCK_MS)) * Math.PI) : 0;
      const scale = 0.94 + 0.06 * chargeEase + lockPulse;
      emblemEl.style.transform = `scale(${scale.toFixed(3)})`;
    }

    // Silk-smooth circular iris aperture after LOCK_MS
    let masterAlpha = 1;
    if (elapsed > LOCK_MS) {
      const openP = Math.min(1, (elapsed - LOCK_MS) / (DURATION_MS - LOCK_MS));
      const openEase = springEaseOut(openP);
      masterAlpha = Math.max(0, 1 - openEase);
      overlay.style.opacity = masterAlpha.toFixed(3);
      const irisRadius = Math.round((1 - openEase) * 115);
      overlay.style.clipPath = `circle(${Math.max(0, irisRadius)}% at 50% 41%)`;
    } else {
      const enterP = springEaseOut(elapsed / 140);
      overlay.style.opacity = enterP.toFixed(3);
    }

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    // 1. Subtle Volumetric Radial Aura (rgba(255, 107, 26, 0.16))
    const auraR = 165 + 35 * chargeEase;
    const radialGrad = ctx.createRadialGradient(cx, cy, 6, cx, cy, auraR);
    radialGrad.addColorStop(0, `rgba(255, 107, 26, ${(0.18 * masterAlpha).toFixed(3)})`);
    radialGrad.addColorStop(0.6, `rgba(255, 107, 26, ${(0.06 * masterAlpha).toFixed(3)})`);
    radialGrad.addColorStop(1, 'rgba(6, 7, 10, 0)');
    ctx.fillStyle = radialGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, auraR, 0, Math.PI * 2);
    ctx.fill();

    // 2. Minimalist 1.5px Geometric Orbital Rings
    const orbitR = 54;
    ctx.strokeStyle = `rgba(255, 255, 255, ${(0.08 * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, 0, Math.PI * 2);
    ctx.stroke();

    // Outer subtle precision reference arc
    ctx.save();
    ctx.translate(cx, cy);
    ctx.rotate(-elapsed * 0.0014);
    ctx.strokeStyle = `rgba(255, 255, 255, ${(0.12 * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(0, 0, orbitR + 9, -Math.PI * 0.35, Math.PI * 0.35);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, 0, orbitR + 9, Math.PI * 0.65, Math.PI * 1.35);
    ctx.stroke();
    ctx.restore();

    // Main 0deg -> 360deg orbital charge arc + glowing leading photon dot
    const startAng = -Math.PI * 0.5;
    const endAng = startAng + Math.PI * 2 * chargeEase;
    const isLocked = elapsed >= LOCK_MS;
    const activeColor = isLocked ? '#22C55E' : '#FF6B1A';

    ctx.save();
    ctx.strokeStyle = activeColor;
    ctx.globalAlpha = masterAlpha;
    ctx.shadowColor = activeColor;
    ctx.shadowBlur = 12;
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, startAng, endAng);
    ctx.stroke();

    // Glowing leading photon dot
    const dotX = cx + Math.cos(endAng) * orbitR;
    const dotY = cy + Math.sin(endAng) * orbitR;
    ctx.fillStyle = '#FAFAFA';
    ctx.shadowColor = activeColor;
    ctx.shadowBlur = 14;
    ctx.beginPath();
    ctx.arc(dotX, dotY, 3, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    // 3. Crisp Micro-Pulse Ring on Lock-In
    if (isLocked) {
      const pulseP = Math.min(1, (elapsed - LOCK_MS) / (DURATION_MS - LOCK_MS));
      const pulseEase = springEaseOut(pulseP);
      const pulseR = orbitR + pulseEase * 72;
      const pulseAlpha = Math.max(0, (1 - pulseEase) * 0.65 * masterAlpha);

      ctx.strokeStyle = `rgba(34, 197, 94, ${pulseAlpha.toFixed(3)})`;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(cx, cy, pulseR, 0, Math.PI * 2);
      ctx.stroke();
    }

    ctx.restore();

    if (t < 1) {
      warpAnimRafId = requestAnimationFrame(renderConnectFrame);
    } else {
      warpAnimRafId = null;
      overlay.classList.add('hidden');
      overlay.style.opacity = '';
      overlay.style.clipPath = '';
    }
  }

  warpAnimRafId = requestAnimationFrame(renderConnectFrame);
}

// ============================================================================
// Moment 3: Switch Screen or Window ("playSourceSwitchAnimation") — ~0.65s
// ============================================================================
function playSourceSwitchAnimation(sourceName, isWindow = false, _isLiveSwap = false) {
  const overlay = document.getElementById('hyper-cast-warp-overlay');
  const canvas = document.getElementById('hyper-cast-warp-canvas');
  const headerEl = document.getElementById('hyper-cast-header');
  const titleEl = document.getElementById('hyper-cast-title');
  const subtitleEl = document.getElementById('hyper-cast-subtitle');
  const chargePctEl = document.getElementById('hyper-cast-charge-pct');
  const emblemEl = document.getElementById('hyper-cast-emblem');

  if (!overlay || !canvas || prefersReducedMotion()) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  if (warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
  }

  playMinimalSwitchAudio(Boolean(isWindow));

  const s = getAnimStrings();
  overlay.classList.remove('hidden', 'mode-disconnect');
  overlay.classList.add('mode-switch');

  if (headerEl) {
    headerEl.textContent = isWindow ? s.switchWindowHeader : s.switchScreenHeader;
  }
  if (titleEl) {
    titleEl.textContent = sourceName || (isWindow ? 'Janela' : 'Tela');
  }
  if (subtitleEl) {
    subtitleEl.textContent = isWindow ? s.switchWindowSub : s.switchScreenSub;
  }
  if (chargePctEl) {
    chargePctEl.classList.add('hidden');
  }
  // Hide img emblem during switch so canvas-drawn morphing Monitor/Window geometric icon shines at center
  if (emblemEl) {
    emblemEl.style.opacity = '0';
  }

  overlay.style.opacity = '1';
  overlay.style.clipPath = 'none';

  const DURATION_MS = 650;
  const startTime = performance.now();

  function renderSwitchFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    const cx = w * 0.5;
    const cy = h * 0.41;

    const enterP = springEaseOut(t / 0.22);
    const exitP = t > 0.68 ? springEaseOut((t - 0.68) / 0.32) : 0;
    const masterAlpha = Math.max(0, enterP * (1 - exitP));
    overlay.style.opacity = masterAlpha.toFixed(3);

    // Morph progress: 0 = Monitor frame (Tela), 1 = Floating Window frame (Janela)
    const morphEase = springEaseOut(Math.min(1, t / 0.62));
    const winFactor = isWindow ? morphEase : 1 - morphEase;

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    // 1. Subtle Volumetric Radial Aura (rgba(255, 107, 26, 0.16))
    const auraR = 155;
    const radialGrad = ctx.createRadialGradient(cx, cy, 4, cx, cy, auraR);
    radialGrad.addColorStop(0, `rgba(255, 107, 26, ${(0.16 * masterAlpha).toFixed(3)})`);
    radialGrad.addColorStop(1, 'rgba(6, 7, 10, 0)');
    ctx.fillStyle = radialGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, auraR, 0, Math.PI * 2);
    ctx.fill();

    // 2. Razor-Thin 1.5px Horizontal Fire-Orange Sweep Line Across Stage
    const sweepEase = springEaseOut(t / 0.75);
    const halfSpan = w * (0.12 + 0.34 * sweepEase);
    const sweepAlpha = (1 - Math.pow(t, 1.8)) * masterAlpha;
    const sGrad = ctx.createLinearGradient(cx - halfSpan, cy, cx + halfSpan, cy);
    sGrad.addColorStop(0, 'rgba(255, 107, 26, 0)');
    sGrad.addColorStop(0.25, `rgba(255, 107, 26, ${(sweepAlpha * 0.75).toFixed(3)})`);
    sGrad.addColorStop(0.5, `rgba(250, 250, 250, ${(sweepAlpha * 0.9).toFixed(3)})`);
    sGrad.addColorStop(0.75, `rgba(255, 107, 26, ${(sweepAlpha * 0.75).toFixed(3)})`);
    sGrad.addColorStop(1, 'rgba(255, 107, 26, 0)');
    ctx.fillStyle = sGrad;
    ctx.fillRect(cx - halfSpan, cy - 0.75, halfSpan * 2, 1.5);

    // 3. Razor-Thin 1.5px Orbital Arc + Leading Photon Dot
    const orbitR = 48;
    ctx.strokeStyle = `rgba(255, 255, 255, ${(0.09 * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, 0, Math.PI * 2);
    ctx.stroke();

    const arcProgress = springEaseOut(t / 0.72);
    const startAng = -Math.PI * 0.5;
    const endAng = startAng + Math.PI * 2 * arcProgress;

    ctx.save();
    ctx.strokeStyle = `rgba(255, 107, 26, ${masterAlpha.toFixed(3)})`;
    ctx.shadowColor = '#FF6B1A';
    ctx.shadowBlur = 10;
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, startAng, endAng);
    ctx.stroke();

    const dotX = cx + Math.cos(endAng) * orbitR;
    const dotY = cy + Math.sin(endAng) * orbitR;
    ctx.fillStyle = '#FAFAFA';
    ctx.beginPath();
    ctx.arc(dotX, dotY, 2.6, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    // 4. Clean Geometric Morphing Icon: Monitor Frame (Tela) <-> Floating Window Frame (Janela)
    ctx.save();
    ctx.translate(cx, cy);
    const iconScale = 0.92 + 0.08 * springEaseOut(t / 0.45);
    ctx.scale(iconScale, iconScale);
    ctx.globalAlpha = masterAlpha;

    const frameW = 42 - 6 * winFactor;
    const frameH = 26 + 2 * winFactor;
    const frameY = -frameH * 0.5 - 2 * (1 - winFactor);

    // Subtle secondary layered window card behind when morphing to Window
    if (winFactor > 0.05) {
      ctx.strokeStyle = `rgba(255, 107, 26, ${(0.45 * winFactor).toFixed(3)})`;
      ctx.lineWidth = 1.4;
      drawRoundedRectPath(
        ctx,
        -frameW * 0.5 + 4 * winFactor,
        frameY - 4 * winFactor,
        frameW,
        frameH,
        5
      );
      ctx.stroke();
    }

    // Main Monitor / Window Frame
    ctx.fillStyle = 'rgba(12, 14, 20, 0.92)';
    drawRoundedRectPath(ctx, -frameW * 0.5, frameY, frameW, frameH, 5);
    ctx.fill();

    ctx.strokeStyle = '#FAFAFA';
    ctx.lineWidth = 1.8;
    ctx.stroke();

    // Window Top Titlebar + 2 Micro Dots (fades in for Janela)
    if (winFactor > 0.05) {
      ctx.strokeStyle = `rgba(255, 107, 26, ${winFactor.toFixed(3)})`;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(-frameW * 0.5 + 1, frameY + 7);
      ctx.lineTo(frameW * 0.5 - 1, frameY + 7);
      ctx.stroke();

      ctx.fillStyle = `rgba(255, 107, 26, ${winFactor.toFixed(3)})`;
      ctx.beginPath();
      ctx.arc(-frameW * 0.5 + 5.5, frameY + 3.5, 1.3, 0, Math.PI * 2);
      ctx.arc(-frameW * 0.5 + 10, frameY + 3.5, 1.3, 0, Math.PI * 2);
      ctx.fill();
    }

    // Monitor Stand Neck & Base (fades in for Tela)
    const screenFactor = 1 - winFactor;
    if (screenFactor > 0.05) {
      ctx.strokeStyle = `rgba(250, 250, 250, ${screenFactor.toFixed(3)})`;
      ctx.lineWidth = 1.8;
      ctx.lineCap = 'round';
      const bottomY = frameY + frameH;
      ctx.beginPath();
      ctx.moveTo(0, bottomY);
      ctx.lineTo(0, bottomY + 5 * screenFactor);
      ctx.moveTo(-8 * screenFactor, bottomY + 5 * screenFactor);
      ctx.lineTo(8 * screenFactor, bottomY + 5 * screenFactor);
      ctx.stroke();

      // Inner Fire-Orange Fullscreen Corner Brackets
      ctx.strokeStyle = `rgba(255, 107, 26, ${(0.9 * screenFactor).toFixed(3)})`;
      ctx.lineWidth = 1.5;
      const bx = frameW * 0.5 - 5;
      const byTop = frameY + 5;
      const byBot = frameY + frameH - 5;
      ctx.beginPath();
      ctx.moveTo(-bx, byTop + 4);
      ctx.lineTo(-bx, byTop);
      ctx.lineTo(-bx + 4, byTop);
      ctx.moveTo(bx - 4, byBot);
      ctx.lineTo(bx, byBot);
      ctx.lineTo(bx, byBot - 4);
      ctx.stroke();
    }

    ctx.restore();
    ctx.restore();

    if (t < 1) {
      warpAnimRafId = requestAnimationFrame(renderSwitchFrame);
    } else {
      warpAnimRafId = null;
      overlay.classList.add('hidden');
      overlay.classList.remove('mode-switch');
      overlay.style.opacity = '';
      if (emblemEl) emblemEl.style.opacity = '1';
      if (chargePctEl) chargePctEl.classList.remove('hidden');
    }
  }

  warpAnimRafId = requestAnimationFrame(renderSwitchFrame);
}

// ============================================================================
// Moment 4: Disconnect / Stop Casting ("playDisconnectAnimation") — ~0.85s
// ============================================================================
function playDisconnectAnimation(reason = null) {
  const overlay = document.getElementById('hyper-cast-warp-overlay');
  const canvas = document.getElementById('hyper-cast-warp-canvas');
  const headerEl = document.getElementById('hyper-cast-header');
  const titleEl = document.getElementById('hyper-cast-title');
  const subtitleEl = document.getElementById('hyper-cast-subtitle');
  const chargePctEl = document.getElementById('hyper-cast-charge-pct');
  const emblemEl = document.getElementById('hyper-cast-emblem');

  if (!overlay || !canvas || prefersReducedMotion()) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  if (warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
  }

  playMinimalDisconnectAudio();

  const s = getAnimStrings();
  overlay.classList.remove('hidden', 'mode-switch');
  overlay.classList.add('mode-disconnect');

  if (headerEl) headerEl.textContent = s.disconnectHeader;
  if (titleEl) titleEl.textContent = s.disconnectTitle;
  if (subtitleEl) subtitleEl.textContent = reason || s.disconnectSub;
  if (chargePctEl) chargePctEl.classList.add('hidden');
  if (emblemEl) {
    emblemEl.style.opacity = '1';
    emblemEl.style.transform = 'scale(1)';
  }

  overlay.style.opacity = '1';
  overlay.style.clipPath = 'none';

  const DURATION_MS = 850;
  const startTime = performance.now();

  function renderDisconnectFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    const cx = w * 0.5;
    const cy = h * 0.41;

    const enterP = springEaseOut(t / 0.18);
    const exitP = t > 0.66 ? springEaseOut((t - 0.66) / 0.34) : 0;
    const masterAlpha = Math.max(0, enterP * (1 - exitP));
    overlay.style.opacity = masterAlpha.toFixed(3);

    const decelEase = springEaseOut(t);
    if (emblemEl) {
      const scale = 1.04 - 0.08 * decelEase;
      emblemEl.style.transform = `scale(${scale.toFixed(3)})`;
    }

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    // 1. Decelerating Warm Ember Radial Aura
    const auraR = 175 * (1 - 0.25 * decelEase);
    const radialGrad = ctx.createRadialGradient(cx, cy, 4, cx, cy, auraR);
    radialGrad.addColorStop(0, `rgba(255, 107, 26, ${(0.14 * (1 - decelEase * 0.6) * masterAlpha).toFixed(3)})`);
    radialGrad.addColorStop(1, 'rgba(6, 7, 10, 0)');
    ctx.fillStyle = radialGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, auraR, 0, Math.PI * 2);
    ctx.fill();

    // 2. Contracting Reverse-Iris Ring + Decelerating Orbital Arc (#FF6B1A -> #71717A)
    const orbitR = 54;
    const contractR = orbitR + (1 - decelEase) * 42;
    ctx.strokeStyle = `rgba(161, 161, 170, ${(0.18 * (1 - decelEase) * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.arc(cx, cy, contractR, 0, Math.PI * 2);
    ctx.stroke();

    // Decelerating orbital arc contracting from 360deg -> 0deg
    const remain = Math.max(0.02, 1 - decelEase);
    const startAng = -Math.PI * 0.5;
    const endAng = startAng + Math.PI * 2 * remain;

    const rCol = Math.round(255 - (255 - 113) * decelEase);
    const gCol = Math.round(107 - (107 - 113) * decelEase);
    const bCol = Math.round(26 + (122 - 26) * decelEase);
    const arcColor = `rgba(${rCol}, ${gCol}, ${bCol}, ${masterAlpha.toFixed(3)})`;

    ctx.save();
    ctx.strokeStyle = arcColor;
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, startAng, endAng);
    ctx.stroke();

    const dotX = cx + Math.cos(endAng) * orbitR;
    const dotY = cy + Math.sin(endAng) * orbitR;
    ctx.fillStyle = arcColor;
    ctx.beginPath();
    ctx.arc(dotX, dotY, 2.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    ctx.restore();

    if (t < 1) {
      warpAnimRafId = requestAnimationFrame(renderDisconnectFrame);
    } else {
      warpAnimRafId = null;
      overlay.classList.add('hidden');
      overlay.classList.remove('mode-disconnect');
      overlay.style.opacity = '';
      if (chargePctEl) chargePctEl.classList.remove('hidden');
    }
  }

  warpAnimRafId = requestAnimationFrame(renderDisconnectFrame);
}

// ============================================================================
// BETA Moment: Fire TV Remote Play / Pause Kinetic Transition — ~0.62s
// ============================================================================
function playPauseResumeAnimation(isPaused, _sourceTrigger = 'remote') {
  const overlay = document.getElementById('hyper-cast-warp-overlay');
  const canvas = document.getElementById('hyper-cast-warp-canvas');
  const headerEl = document.getElementById('hyper-cast-header');
  const titleEl = document.getElementById('hyper-cast-title');
  const subtitleEl = document.getElementById('hyper-cast-subtitle');
  const chargePctEl = document.getElementById('hyper-cast-charge-pct');
  const emblemEl = document.getElementById('hyper-cast-emblem');

  if (!overlay || !canvas || prefersReducedMotion()) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  if (warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
  }

  playMinimalPauseAudio(Boolean(isPaused));

  const s = getAnimStrings();
  overlay.classList.remove('hidden', 'mode-disconnect');
  overlay.classList.toggle('mode-switch', Boolean(isPaused));

  if (headerEl) headerEl.textContent = isPaused ? s.pauseHeader : s.resumeHeader;
  if (titleEl) titleEl.textContent = isPaused ? s.pauseTitle : s.resumeTitle;
  if (subtitleEl) subtitleEl.textContent = isPaused ? s.pauseSub : s.resumeSub;
  if (chargePctEl) chargePctEl.classList.add('hidden');
  if (emblemEl) emblemEl.style.opacity = '0';

  overlay.style.opacity = '1';
  overlay.style.clipPath = 'none';

  const DURATION_MS = 620;
  const startTime = performance.now();

  function renderPauseFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    const cx = w * 0.5;
    const cy = h * 0.41;

    const enterP = springEaseOut(t / 0.22);
    const exitP = t > 0.68 ? springEaseOut((t - 0.68) / 0.32) : 0;
    const masterAlpha = Math.max(0, enterP * (1 - exitP));
    overlay.style.opacity = masterAlpha.toFixed(3);

    const arcEase = springEaseOut(t / 0.65);
    const orbitR = 48;
    const accentColor = isPaused ? '#FF6B1A' : '#22C55E';

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    ctx.strokeStyle = `rgba(255, 255, 255, ${(0.09 * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, 0, Math.PI * 2);
    ctx.stroke();

    const startAng = -Math.PI * 0.5;
    const endAng = startAng + Math.PI * 2 * arcEase;

    ctx.save();
    ctx.strokeStyle = accentColor;
    ctx.globalAlpha = masterAlpha;
    ctx.shadowColor = accentColor;
    ctx.shadowBlur = 10;
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, startAng, endAng);
    ctx.stroke();

    const dotX = cx + Math.cos(endAng) * orbitR;
    const dotY = cy + Math.sin(endAng) * orbitR;
    ctx.fillStyle = '#FAFAFA';
    ctx.beginPath();
    ctx.arc(dotX, dotY, 2.6, 0, Math.PI * 2);
    ctx.fill();

    // Crisp Geometric Pause (||) or Play (▶) Glyph at Center
    ctx.translate(cx, cy);
    const scale = 0.88 + 0.12 * arcEase;
    ctx.scale(scale, scale);
    ctx.fillStyle = '#FAFAFA';
    if (isPaused) {
      drawRoundedRectPath(ctx, -10, -12, 6, 24, 2);
      ctx.fill();
      drawRoundedRectPath(ctx, 4, -12, 6, 24, 2);
      ctx.fill();
    } else {
      ctx.beginPath();
      ctx.moveTo(-7, -13);
      ctx.lineTo(13, 0);
      ctx.lineTo(-7, 13);
      ctx.closePath();
      ctx.fill();
    }
    ctx.restore();
    ctx.restore();

    if (t < 1) {
      warpAnimRafId = requestAnimationFrame(renderPauseFrame);
    } else {
      warpAnimRafId = null;
      overlay.classList.add('hidden');
      overlay.classList.remove('mode-switch');
      overlay.style.opacity = '';
      if (emblemEl) emblemEl.style.opacity = '1';
      if (chargePctEl) chargePctEl.classList.remove('hidden');
    }
  }

  warpAnimRafId = requestAnimationFrame(renderPauseFrame);
}

// ============================================================================
// Image Mode Kinetic Confirmation ("Ultra Nitidez" <-> "Fluidez 60 FPS")
// ============================================================================
function playImageModeAnimation(mode) {
  const overlay = document.getElementById('hyper-cast-warp-overlay');
  const canvas = document.getElementById('hyper-cast-warp-canvas');
  const headerEl = document.getElementById('hyper-cast-header');
  const titleEl = document.getElementById('hyper-cast-title');
  const subtitleEl = document.getElementById('hyper-cast-subtitle');
  const chargePctEl = document.getElementById('hyper-cast-charge-pct');
  const emblemEl = document.getElementById('hyper-cast-emblem');

  if (!overlay || !canvas || prefersReducedMotion()) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  if (warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
  }

  const isDetail = mode === 'detail';
  playMinimalSwitchAudio(isDetail);

  const s = getAnimStrings();
  overlay.classList.remove('hidden', 'mode-disconnect');
  overlay.classList.add('mode-switch');

  if (headerEl) headerEl.textContent = s.imageModeHeader || 'MODO DE IMAGEM';
  if (titleEl) titleEl.textContent = isDetail ? s.imageModeDetailTitle : s.imageModeMotionTitle;
  if (subtitleEl) subtitleEl.textContent = isDetail ? s.imageModeDetailSub : s.imageModeMotionSub;
  if (chargePctEl) chargePctEl.classList.add('hidden');
  if (emblemEl) emblemEl.style.opacity = '0';

  overlay.style.opacity = '1';
  overlay.style.clipPath = 'none';

  const DURATION_MS = 600;
  const startTime = performance.now();

  function renderModeFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    const cx = w * 0.5;
    const cy = h * 0.41;

    const enterP = springEaseOut(t / 0.22);
    const exitP = t > 0.66 ? springEaseOut((t - 0.66) / 0.34) : 0;
    const masterAlpha = Math.max(0, enterP * (1 - exitP));
    overlay.style.opacity = masterAlpha.toFixed(3);

    const arcEase = springEaseOut(t / 0.65);
    const orbitR = 48;
    const accentColor = isDetail ? '#FF6B1A' : '#22C55E';

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    ctx.strokeStyle = `rgba(255, 255, 255, ${(0.09 * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, 0, Math.PI * 2);
    ctx.stroke();

    const startAng = -Math.PI * 0.5;
    const endAng = startAng + Math.PI * 2 * arcEase;

    ctx.save();
    ctx.strokeStyle = accentColor;
    ctx.globalAlpha = masterAlpha;
    ctx.shadowColor = accentColor;
    ctx.shadowBlur = 10;
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, startAng, endAng);
    ctx.stroke();

    const dotX = cx + Math.cos(endAng) * orbitR;
    const dotY = cy + Math.sin(endAng) * orbitR;
    ctx.fillStyle = '#FAFAFA';
    ctx.beginPath();
    ctx.arc(dotX, dotY, 2.6, 0, Math.PI * 2);
    ctx.fill();

    // Minimalist Geometric Mode Glyph: Precision Diamond Reticle (Detail) or Kinetic Dual Wave (Motion)
    ctx.translate(cx, cy);
    const scale = 0.88 + 0.12 * arcEase;
    ctx.scale(scale, scale);
    ctx.strokeStyle = '#FAFAFA';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    if (isDetail) {
      ctx.beginPath();
      ctx.moveTo(0, -15);
      ctx.lineTo(15, 0);
      ctx.lineTo(0, 15);
      ctx.lineTo(-15, 0);
      ctx.closePath();
      ctx.stroke();

      ctx.fillStyle = '#FF6B1A';
      ctx.beginPath();
      ctx.arc(0, 0, 3.5, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.beginPath();
      ctx.moveTo(-16, 4);
      ctx.quadraticCurveTo(-8, -12, 0, 0);
      ctx.quadraticCurveTo(8, 12, 16, -4);
      ctx.stroke();

      ctx.strokeStyle = '#22C55E';
      ctx.beginPath();
      ctx.moveTo(-12, 10);
      ctx.quadraticCurveTo(-4, -2, 4, 6);
      ctx.quadraticCurveTo(10, 12, 16, 4);
      ctx.stroke();
    }

    ctx.restore();
    ctx.restore();

    if (t < 1) {
      warpAnimRafId = requestAnimationFrame(renderModeFrame);
    } else {
      warpAnimRafId = null;
      overlay.classList.add('hidden');
      overlay.classList.remove('mode-switch');
      overlay.style.opacity = '';
      if (emblemEl) emblemEl.style.opacity = '1';
      if (chargePctEl) chargePctEl.classList.remove('hidden');
    }
  }

  warpAnimRafId = requestAnimationFrame(renderModeFrame);
}

// ============================================================================
// Video Preset & Latency Control Kinetic Confirmation (Shared Helper)
// ============================================================================
function playKineticSettingBadgeAnimation({ header, title, subtitle, accentColor, isBoltIcon }) {
  const overlay = document.getElementById('hyper-cast-warp-overlay');
  const canvas = document.getElementById('hyper-cast-warp-canvas');
  const headerEl = document.getElementById('hyper-cast-header');
  const titleEl = document.getElementById('hyper-cast-title');
  const subtitleEl = document.getElementById('hyper-cast-subtitle');
  const chargePctEl = document.getElementById('hyper-cast-charge-pct');
  const emblemEl = document.getElementById('hyper-cast-emblem');

  if (!overlay || !canvas || prefersReducedMotion()) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  if (warpAnimRafId) {
    cancelAnimationFrame(warpAnimRafId);
    warpAnimRafId = null;
  }

  playMinimalSwitchAudio(isBoltIcon);

  overlay.classList.remove('hidden', 'mode-disconnect');
  overlay.classList.add('mode-switch');

  if (headerEl) headerEl.textContent = header;
  if (titleEl) titleEl.textContent = title;
  if (subtitleEl) subtitleEl.textContent = subtitle;
  if (chargePctEl) chargePctEl.classList.add('hidden');
  if (emblemEl) emblemEl.style.opacity = '0';

  overlay.style.opacity = '1';
  overlay.style.clipPath = 'none';

  const DURATION_MS = 620;
  const startTime = performance.now();

  function renderFrame(now) {
    const elapsed = now - startTime;
    const t = Math.min(1, Math.max(0, elapsed / DURATION_MS));

    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }

    const cx = w * 0.5;
    const cy = h * 0.41;

    const enterP = springEaseOut(t / 0.22);
    const exitP = t > 0.66 ? springEaseOut((t - 0.66) / 0.34) : 0;
    const masterAlpha = Math.max(0, enterP * (1 - exitP));
    overlay.style.opacity = masterAlpha.toFixed(3);

    const arcEase = springEaseOut(t / 0.65);
    const orbitR = 48;

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    ctx.strokeStyle = `rgba(255, 255, 255, ${(0.09 * masterAlpha).toFixed(3)})`;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, 0, Math.PI * 2);
    ctx.stroke();

    const startAng = -Math.PI * 0.5;
    const endAng = startAng + Math.PI * 2 * arcEase;

    ctx.save();
    ctx.strokeStyle = accentColor;
    ctx.globalAlpha = masterAlpha;
    ctx.shadowColor = accentColor;
    ctx.shadowBlur = 10;
    ctx.lineWidth = 1.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(cx, cy, orbitR, startAng, endAng);
    ctx.stroke();

    const dotX = cx + Math.cos(endAng) * orbitR;
    const dotY = cy + Math.sin(endAng) * orbitR;
    ctx.fillStyle = '#FAFAFA';
    ctx.beginPath();
    ctx.arc(dotX, dotY, 2.6, 0, Math.PI * 2);
    ctx.fill();

    ctx.translate(cx, cy);
    const scale = 0.88 + 0.12 * arcEase;
    ctx.scale(scale, scale);
    ctx.strokeStyle = '#FAFAFA';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    if (isBoltIcon) {
      // Minimalist Kinetic Bolt (Latency / Speed)
      ctx.beginPath();
      ctx.moveTo(2, -16);
      ctx.lineTo(-10, 2);
      ctx.lineTo(-1, 2);
      ctx.lineTo(-3, 16);
      ctx.lineTo(10, -2);
      ctx.lineTo(1, -2);
      ctx.closePath();
      ctx.fillStyle = accentColor;
      ctx.fill();
      ctx.stroke();
    } else {
      // Minimalist 16:9 Cinema Frame + 60FPS Corner Brackets (Video Profile)
      drawRoundedRectPath(ctx, -18, -11, 36, 22, 4);
      ctx.stroke();
      ctx.fillStyle = accentColor;
      ctx.beginPath();
      ctx.arc(0, 0, 3.5, 0, Math.PI * 2);
      ctx.fill();
    }

    ctx.restore();
    ctx.restore();

    if (t < 1) {
      warpAnimRafId = requestAnimationFrame(renderFrame);
    } else {
      warpAnimRafId = null;
      overlay.classList.add('hidden');
      overlay.classList.remove('mode-switch');
      overlay.style.opacity = '';
      if (emblemEl) emblemEl.style.opacity = '1';
      if (chargePctEl) chargePctEl.classList.remove('hidden');
    }
  }

  warpAnimRafId = requestAnimationFrame(renderFrame);
}

function playVideoProfileAnimation(preset) {
  const s = getAnimStrings();
  const is60 = (preset?.fps || 60) >= 60;
  playKineticSettingBadgeAnimation({
    header: s.videoHeader || 'PERFIL DE VÍDEO',
    title: preset?.name || 'Full HD Fluido',
    subtitle: `${preset?.label || '1080p • 60 FPS'} • ${s.videoSub || 'Tempo Real'}`,
    accentColor: is60 ? '#FF6B1A' : '#38BDF8',
    isBoltIcon: false
  });
}

function playLatencyModeAnimation(mode) {
  const s = getAnimStrings();
  const isUltra = mode !== 'balanced';
  playKineticSettingBadgeAnimation({
    header: s.latencyHeader || 'CONTROLE DE LATÊNCIA',
    title: isUltra ? s.latencyUltraTitle : s.latencyBalancedTitle,
    subtitle: isUltra ? s.latencyUltraSub : s.latencyBalancedSub,
    accentColor: isUltra ? '#22C55E' : '#FF6B1A',
    isBoltIcon: true
  });
}

// ============================================================================
// Captures: 1-Click Screenshot, Replay Clip 30s, Quick Gallery & Dialog Preview
// ============================================================================

function installToolbarRovingFallback(toolbarEl) {
  if (!toolbarEl || 'focusGroup' in HTMLElement.prototype) return;

  const getItems = () =>
    Array.from(toolbarEl.querySelectorAll('button')).filter(
      (el) => !el.disabled && !el.hidden && !el.classList.contains('hidden') && el.offsetParent !== null
    );

  const items = getItems();
  items.forEach((el, idx) => {
    el.tabIndex = idx === 0 ? 0 : -1;
  });

  toolbarEl.addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft' && e.key !== 'Home' && e.key !== 'End') return;
    const focusables = getItems();
    if (focusables.length === 0) return;

    const currentIdx = focusables.indexOf(document.activeElement);
    if (currentIdx === -1) return;

    e.preventDefault();
    let nextIdx = currentIdx;
    if (e.key === 'ArrowRight') nextIdx = (currentIdx + 1) % focusables.length;
    else if (e.key === 'ArrowLeft') nextIdx = (currentIdx - 1 + focusables.length) % focusables.length;
    else if (e.key === 'Home') nextIdx = 0;
    else if (e.key === 'End') nextIdx = focusables.length - 1;

    focusables.forEach((el, i) => {
      el.tabIndex = i === nextIdx ? 0 : -1;
    });
    focusables[nextIdx].focus();
  });
}

function updateCaptureToolbarState() {
  const canScreenshot = Boolean(isTransmitting && !isSavingScreenshot);
  const hasActiveRecorder = Boolean(replayRecorder && replayRecorder.running);
  const canClip = Boolean(isTransmitting && replayBufferEnabled && hasActiveRecorder && !isSavingReplayClip);

  if (btnDockScreenshot) {
    btnDockScreenshot.setAttribute('aria-disabled', String(!canScreenshot));
    btnDockScreenshot.title = isSavingScreenshot
      ? 'Capturando Print...'
      : isTransmitting
        ? 'Tirar Print 1-clique do espelhamento (Imagens\\CastViewer)'
        : 'Tirar Print 1-clique (Inicie o espelhamento para capturar)';
  }

  if (btnDockClip30s) {
    btnDockClip30s.setAttribute('aria-disabled', String(!canClip));
    btnDockClip30s.classList.toggle('is-buffering', Boolean(isTransmitting && replayBufferEnabled && hasActiveRecorder));
    btnDockClip30s.classList.toggle('is-saving', Boolean(isSavingReplayClip));

    if (btnDockClipText) {
      btnDockClipText.textContent = isSavingReplayClip ? 'Salvando...' : 'Clip 30s';
    }

    if (isSavingReplayClip) {
      btnDockClip30s.title = 'Exportando Clip dos últimos 30s...';
    } else if (!isTransmitting) {
      btnDockClip30s.title = 'Salvar Clip 30s (Inicie o espelhamento para gravar em segundo plano)';
    } else if (!replayBufferEnabled) {
      btnDockClip30s.title = 'Buffer Replay 30s desativado em Ajustes';
    } else if (!hasActiveRecorder) {
      btnDockClip30s.title = 'Inicializando buffer Replay 30s...';
    } else {
      btnDockClip30s.title = 'Salvar últimos 15–30s do espelhamento em Vídeos\\CastViewer';
    }
  }

  if (capturesReplayBadge) {
    if (isTransmitting && hasActiveRecorder) {
      capturesReplayBadge.textContent = 'Gravando buffer 30s';
      capturesReplayBadge.className = 'badge-tag is-live';
    } else if (!replayBufferEnabled) {
      capturesReplayBadge.textContent = 'Replay desativado';
      capturesReplayBadge.className = 'badge-tag is-muted';
    } else {
      capturesReplayBadge.textContent = 'Replay 30s pronto';
      capturesReplayBadge.className = 'badge-tag';
    }
  }
}

function buildCaptureMediaStream(options = {}) {
  if (!localStream) return null;
  const videoTracks = localStream.getVideoTracks().filter((t) => t.readyState === 'live');
  if (videoTracks.length === 0) return null;

  let videoTrackForStream = videoTracks[0];
  if (options.isolatedReplayTrack && typeof videoTracks[0].clone === 'function') {
    try {
      const cloned = videoTracks[0].clone();
      if (typeof cloned.applyConstraints === 'function') {
        cloned
          .applyConstraints({
            width: { max: 1280 },
            height: { max: 720 },
            frameRate: { ideal: 30, max: 30 }
          })
          .catch(() => {});
      }
      replayClonedVideoTrack = cloned;
      videoTrackForStream = cloned;
    } catch (_) {
      replayClonedVideoTrack = null;
    }
  }

  const stream = new MediaStream([videoTrackForStream]);
  let sourceAudioTrack = null;
  if (liveAudioTrack && liveAudioTrack.readyState === 'live') {
    sourceAudioTrack = liveAudioTrack;
  } else {
    const fallbackAudio = localStream.getAudioTracks().filter((t) => t.readyState === 'live');
    if (fallbackAudio.length > 0) {
      sourceAudioTrack = fallbackAudio[0];
    }
  }

  if (sourceAudioTrack) {
    let audioTrackForStream = sourceAudioTrack;
    if (options.isolatedReplayTrack && typeof sourceAudioTrack.clone === 'function') {
      try {
        const clonedAudio = sourceAudioTrack.clone();
        clonedAudio.enabled = audioCaptureEnabled;
        replayClonedAudioTrack = clonedAudio;
        audioTrackForStream = clonedAudio;
      } catch (_) {
        replayClonedAudioTrack = null;
      }
    }
    stream.addTrack(audioTrackForStream);
  }
  return stream;
}

function startReplayRecorder() {
  stopReplayRecorder();
  if (!isTransmitting || !replayBufferEnabled) {
    updateCaptureToolbarState();
    return;
  }

  const captureLib = window.CastViewerCapture;
  if (!captureLib || !captureLib.StaggeredReplayRecorder || typeof MediaRecorder === 'undefined') {
    updateCaptureToolbarState();
    return;
  }

  const recStream = buildCaptureMediaStream({ isolatedReplayTrack: true });
  if (!recStream) {
    updateCaptureToolbarState();
    return;
  }

  const hasAudioTrack = recStream.getAudioTracks().length > 0;
  const isTypeSupportedFn =
    typeof MediaRecorder.isTypeSupported === 'function'
      ? (mime) => MediaRecorder.isTypeSupported(mime)
      : () => false;

  let mimeType = '';
  if (hasAudioTrack && captureLib.pickPreferredRecorderMimeType) {
    mimeType = captureLib.pickPreferredRecorderMimeType(isTypeSupportedFn);
  } else {
    const videoOnlyCandidates = [
      'video/mp4;codecs=avc1.42E01E',
      'video/mp4;codecs=avc1',
      'video/mp4',
      'video/webm;codecs=vp9',
      'video/webm;codecs=h264',
      'video/webm;codecs=vp8',
      'video/webm'
    ];
    mimeType = videoOnlyCandidates.find((c) => isTypeSupportedFn(c)) || '';
  }

  try {
    replayRecorder = new captureLib.StaggeredReplayRecorder({
      stream: recStream,
      mimeType,
      videoBitsPerSecond: lowSpecMode ? 2_000_000 : 2_600_000,
      audioBitsPerSecond: 128_000,
      windowMs: 30_000,
      onStateChange: () => {
        updateCaptureToolbarState();
      }
    });
    replayRecorder.start();
  } catch (err) {
    console.warn('CastViewer: Não foi possível iniciar buffer Replay 30s:', err);
    replayRecorder = null;
    if (replayClonedVideoTrack) {
      try { replayClonedVideoTrack.stop(); } catch (_) {}
      replayClonedVideoTrack = null;
    }
    if (replayClonedAudioTrack) {
      try { replayClonedAudioTrack.stop(); } catch (_) {}
      replayClonedAudioTrack = null;
    }
  }

  updateCaptureToolbarState();
}

function stopReplayRecorder() {
  if (replayRecorder) {
    try {
      replayRecorder.stop();
    } catch (_) {}
    replayRecorder = null;
  }
  if (replayClonedVideoTrack) {
    try {
      replayClonedVideoTrack.stop();
    } catch (_) {}
    replayClonedVideoTrack = null;
  }
  if (replayClonedAudioTrack) {
    try {
      replayClonedAudioTrack.stop();
    } catch (_) {}
    replayClonedAudioTrack = null;
  }
  updateCaptureToolbarState();
}

function restartReplayRecorderIfActive() {
  if (isTransmitting && replayBufferEnabled) {
    startReplayRecorder();
  }
}

async function grabLiveVideoDataUrl(videoTrack) {
  if (typeof ImageCapture === 'function') {
    try {
      const imageCapture = new ImageCapture(videoTrack);
      const bitmap = await imageCapture.grabFrame();
      try {
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width || 1280;
        canvas.height = bitmap.height || 720;
        const ctx = canvas.getContext('2d');
        if (ctx) {
          ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
          return canvas.toDataURL('image/png');
        }
      } finally {
        if (typeof bitmap.close === 'function') bitmap.close();
      }
    } catch (_) {
      // Fallback to offscreen <video> element below
    }
  }

  const offscreenVideo = document.createElement('video');
  offscreenVideo.muted = true;
  offscreenVideo.playsInline = true;
  offscreenVideo.srcObject = new MediaStream([videoTrack]);

  try {
    await new Promise((resolve, reject) => {
      const timeoutId = setTimeout(() => reject(new Error('Timeout ao ler quadro do vídeo.')), 2500);
      const finish = () => {
        clearTimeout(timeoutId);
        resolve();
      };
      if (offscreenVideo.readyState >= 2 && offscreenVideo.videoWidth > 0) {
        finish();
        return;
      }
      offscreenVideo.onloadeddata = finish;
      offscreenVideo.onerror = () => {
        clearTimeout(timeoutId);
        reject(new Error('Falha ao decodificar quadro do vídeo.'));
      };
    });

    await offscreenVideo.play().catch(() => {});
    const width = offscreenVideo.videoWidth || 1280;
    const height = offscreenVideo.videoHeight || 720;
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Contexto 2D indisponível para gerar o Print.');
    ctx.drawImage(offscreenVideo, 0, 0, width, height);
    return canvas.toDataURL('image/png');
  } finally {
    offscreenVideo.pause();
    offscreenVideo.srcObject = null;
  }
}

async function captureLiveScreenshot() {
  if (isSavingScreenshot) return;
  if (!isTransmitting || !localStream) {
    showToast('Inicie o espelhamento para tirar um Print 1-clique.', 'info');
    return;
  }

  const videoTrack = localStream.getVideoTracks().find((t) => t.readyState === 'live');
  if (!videoTrack) {
    showToast('Nenhuma trilha de vídeo ativa para capturar.', 'error');
    return;
  }

  const api = getApi();
  if (!api.saveScreenshot) {
    showToast('Captura de Print indisponível nesta versão.', 'error');
    return;
  }

  isSavingScreenshot = true;
  updateCaptureToolbarState();

  try {
    const dataUrl = await grabLiveVideoDataUrl(videoTrack);
    const result = await api.saveScreenshot({ dataUrl });
    if (!result || !result.ok) {
      throw new Error((result && result.error) || 'Erro ao salvar Print.');
    }
    showToast(`Print salvo: ${result.fileName}`, 'success');
    await refreshCapturesList();
  } catch (err) {
    console.error('CastViewer: Falha ao capturar Print:', err);
    showToast(`Falha ao salvar Print: ${err.message}`, 'error');
  } finally {
    isSavingScreenshot = false;
    updateCaptureToolbarState();
  }
}

async function saveReplayClip30s() {
  if (isSavingReplayClip) return;
  if (!isTransmitting) {
    showToast('Inicie o espelhamento para salvar um Clip de 30s.', 'info');
    return;
  }
  if (!replayBufferEnabled) {
    showToast('Ative "Buffer Replay 30s" na aba Ajustes.', 'info');
    return;
  }
  if (!replayRecorder || !replayRecorder.running) {
    showToast('Aguardando buffer Replay 30s acumular vídeo...', 'info');
    return;
  }

  const api = getApi();
  if (!api.saveReplayClip) {
    showToast('Salvamento de Clip indisponível nesta versão.', 'error');
    return;
  }

  isSavingReplayClip = true;
  updateCaptureToolbarState();

  try {
    const clip = await replayRecorder.saveLatestClip();
    if (!clip || !clip.blob || clip.blob.size === 0) {
      throw new Error('Buffer vazio no momento.');
    }
    const arrayBuffer = await clip.blob.arrayBuffer();
    const bytes = new Uint8Array(arrayBuffer);
    const result = await api.saveReplayClip({
      buffer: bytes,
      mimeType: clip.mimeType,
      durationSec: clip.durationSec
    });
    if (!result || !result.ok) {
      throw new Error((result && result.error) || 'Falha ao gravar arquivo de clip.');
    }
    const secLabel = result.durationSec ? ` (${result.durationSec}s)` : '';
    showToast(`Clip salvo${secLabel}: ${result.fileName}`, 'success');
    await refreshCapturesList();
  } catch (err) {
    console.error('CastViewer: Falha ao salvar Clip 30s:', err);
    showToast(`Não foi possível salvar o Clip: ${err.message}`, 'error');
  } finally {
    isSavingReplayClip = false;
    updateCaptureToolbarState();
  }
}

async function refreshCapturesList() {
  const api = getApi();
  if (!api.listCaptures) return;
  try {
    const res = await api.listCaptures();
    capturesCache = Array.isArray(res && res.items) ? res.items : [];
    renderCapturesGrid();
  } catch (err) {
    console.warn('CastViewer: Falha ao listar capturas:', err);
  }
}

function updateCaptureFilterButtonsUI() {
  const pairs = [
    { key: 'all', btn: btnFilterAll },
    { key: 'clip', btn: btnFilterClips },
    { key: 'screenshot', btn: btnFilterPrints }
  ];
  pairs.forEach(({ key, btn }) => {
    if (!btn) return;
    const active = captureFilterMode === key;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-pressed', String(active));
  });
}

function renderCapturesGrid() {
  if (capturesTabCount) {
    capturesTabCount.textContent = String(capturesCache.length);
  }

  if (!capturesGrid) return;

  const filtered =
    captureFilterMode === 'all'
      ? capturesCache
      : capturesCache.filter((item) => item.kind === captureFilterMode);

  if (filtered.length === 0) {
    const emptyMsg =
      capturesCache.length === 0
        ? 'Nenhum Clip 30s ou Print salvo ainda. Durante uma transmissão, clique em Print ou Clip 30s na barra inferior.'
        : 'Nenhuma captura corresponde ao filtro selecionado.';
    capturesGrid.innerHTML = `<div class="empty-captures-placeholder">${escapeHtml(emptyMsg)}</div>`;
    return;
  }

  const captureLib = window.CastViewerCapture || {};
  const fmtSize = captureLib.formatFileSize || ((b) => `${Math.round((b || 0) / 1024)} KB`);

  capturesGrid.innerHTML = '';
  const api = getApi();

  filtered.forEach((item) => {
    const card = document.createElement('article');
    card.className = 'capture-card';
    card.setAttribute('role', 'listitem');

    const isClip = item.kind === 'clip';
    const dateStr = item.createdAtMs
      ? new Date(item.createdAtMs).toLocaleString('pt-BR', {
          day: '2-digit',
          month: '2-digit',
          hour: '2-digit',
          minute: '2-digit'
        })
      : '';

    const mediaHtml = isClip
      ? `<video src="${escapeHtml(item.fileUrl)}" muted playsinline preload="metadata" aria-label="${escapeHtml(item.fileName)}"></video>
         <span class="capture-kind-badge is-clip">${escapeHtml((item.ext || 'MP4').toUpperCase())} • CLIP</span>`
      : `<img src="${escapeHtml(item.fileUrl)}" alt="${escapeHtml(item.fileName)}" loading="lazy" />
         <span class="capture-kind-badge is-print">PNG • PRINT</span>`;

    card.innerHTML = `
      <button type="button" class="capture-thumb-btn" title="Abrir ${escapeHtml(item.fileName)}">
        ${mediaHtml}
      </button>
      <div class="capture-card-body">
        <div class="capture-card-name" title="${escapeHtml(item.fileName)}">${escapeHtml(item.fileName)}</div>
        <div class="capture-card-meta">
          <span>${escapeHtml(fmtSize(item.sizeBytes))}</span>
          <span>${escapeHtml(dateStr)}</span>
        </div>
        <div class="capture-card-actions">
          <button type="button" class="btn-subtle-action btn-cap-preview">Abrir</button>
          <button type="button" class="btn-subtle-action btn-cap-reveal">Pasta</button>
          <button type="button" class="btn-subtle-action btn-cap-delete" aria-label="Excluir ${escapeHtml(item.fileName)}" title="Mover para a Lixeira">Excluir</button>
        </div>
      </div>
    `;

    const openAction = () => openCapturePreview(item);
    card.querySelector('.capture-thumb-btn')?.addEventListener('click', openAction);
    card.querySelector('.btn-cap-preview')?.addEventListener('click', openAction);

    card.querySelector('.btn-cap-reveal')?.addEventListener('click', () => {
      if (api.revealCaptureFile) {
        api.revealCaptureFile(item.filePath);
      }
    });

    card.querySelector('.btn-cap-delete')?.addEventListener('click', async () => {
      if (!api.deleteCaptureFile) return;
      const ok = await api.deleteCaptureFile(item.filePath);
      if (ok) {
        showToast('Captura movida para a Lixeira.', 'info');
        await refreshCapturesList();
      } else {
        showToast('Não foi possível excluir o arquivo.', 'error');
      }
    });

    capturesGrid.appendChild(card);
  });
}

function openCapturePreview(item) {
  if (!capturePreviewDialog || !capturePreviewStage) return;

  activeDialogCapturePath = item.filePath;
  if (capturePreviewTitle) {
    capturePreviewTitle.textContent = item.fileName || 'Visualizar Captura';
  }

  const captureLib = window.CastViewerCapture || {};
  const fmtSize = captureLib.formatFileSize || ((b) => `${Math.round((b || 0) / 1024)} KB`);
  const dateStr = item.createdAtMs ? new Date(item.createdAtMs).toLocaleString('pt-BR') : '';
  if (capturePreviewMeta) {
    capturePreviewMeta.textContent = `${fmtSize(item.sizeBytes)} • ${dateStr}`;
  }

  capturePreviewStage.innerHTML = '';
  if (item.kind === 'clip') {
    const videoEl = document.createElement('video');
    videoEl.src = item.fileUrl;
    videoEl.controls = true;
    videoEl.autoplay = true;
    videoEl.playsInline = true;
    capturePreviewStage.appendChild(videoEl);
  } else {
    const imgEl = document.createElement('img');
    imgEl.src = item.fileUrl;
    imgEl.alt = item.fileName || 'Captura do CastViewer';
    capturePreviewStage.appendChild(imgEl);
  }

  if (typeof capturePreviewDialog.showModal === 'function') {
    capturePreviewDialog.showModal();
  } else {
    capturePreviewDialog.setAttribute('open', '');
  }
}

function setupCapturesUI() {
  const api = getApi();

  installToolbarRovingFallback(streamDockToolbar);
  installToolbarRovingFallback(capturesToolbar);

  if (btnDockScreenshot) {
    btnDockScreenshot.addEventListener('click', () => {
      if (btnDockScreenshot.getAttribute('aria-disabled') === 'true') {
        showToast('Inicie o espelhamento para tirar um Print 1-clique.', 'info');
        return;
      }
      captureLiveScreenshot();
    });
  }

  if (btnDockClip30s) {
    btnDockClip30s.addEventListener('click', () => {
      if (btnDockClip30s.getAttribute('aria-disabled') === 'true') {
        if (!isTransmitting) {
          showToast('Inicie o espelhamento para usar o Clip 30s.', 'info');
        } else if (!replayBufferEnabled) {
          showToast('Ative o Buffer Replay 30s na aba Ajustes.', 'info');
        }
        return;
      }
      saveReplayClip30s();
    });
  }

  const bindFilterBtn = (btn, mode) => {
    if (!btn) return;
    btn.addEventListener('click', () => {
      captureFilterMode = mode;
      updateCaptureFilterButtonsUI();
      renderCapturesGrid();
    });
  };
  bindFilterBtn(btnFilterAll, 'all');
  bindFilterBtn(btnFilterClips, 'clip');
  bindFilterBtn(btnFilterPrints, 'screenshot');

  if (btnOpenClipsFolder && api.openCaptureFolder) {
    btnOpenClipsFolder.addEventListener('click', () => api.openCaptureFolder('clips'));
  }
  if (btnOpenPrintsFolder && api.openCaptureFolder) {
    btnOpenPrintsFolder.addEventListener('click', () => api.openCaptureFolder('screenshots'));
  }
  if (btnRefreshCaptures) {
    btnRefreshCaptures.addEventListener('click', async () => {
      btnRefreshCaptures.classList.add('spinning');
      await refreshCapturesList();
      setTimeout(() => btnRefreshCaptures.classList.remove('spinning'), 600);
    });
  }

  if (btnDialogReveal && api.revealCaptureFile) {
    btnDialogReveal.addEventListener('click', () => {
      if (activeDialogCapturePath) {
        api.revealCaptureFile(activeDialogCapturePath);
      }
    });
  }

  if (capturePreviewDialog) {
    capturePreviewDialog.addEventListener('close', () => {
      if (capturePreviewStage) {
        const video = capturePreviewStage.querySelector('video');
        if (video) {
          video.pause();
          video.removeAttribute('src');
          video.load();
        }
        capturePreviewStage.innerHTML = '';
      }
      activeDialogCapturePath = null;
    });
  }

  updateCaptureFilterButtonsUI();
  updateCaptureToolbarState();
}
