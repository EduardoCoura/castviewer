/**
 * CastViewer - Wayland PipeWire ScreenCast Session Persistence
 * Persists XDG Desktop Portal RestoreTokens (persist_mode = 2) and prevents
 * redundant portal picker reconfirmations across source/preset switches on Wayland.
 */

const fs = require('fs');
const path = require('path');

const WAYLAND_PERSIST_MODE_TRANSIENT = 0;
const WAYLAND_PERSIST_MODE_APPLICATION = 1;
const WAYLAND_PERSIST_MODE_PERSISTENT = 2;

const WAYLAND_PORTAL_SOURCE_ID = 'wayland-portal:0';
const WAYLAND_RESELECT_SOURCE_ID = 'wayland-portal:reselect';

const DEFAULT_WAYLAND_SESSION_STATE = {
  restoreToken: null,
  persistMode: WAYLAND_PERSIST_MODE_PERSISTENT,
  sourceId: null,
  lastSourceLabel: null,
  grantedAt: null,
  updatedAt: null
};

function isValidRestoreToken(token) {
  if (typeof token !== 'string') return false;
  const trimmed = token.trim();
  return trimmed.length >= 4 && trimmed.length <= 512 && /^[\x21-\x7E]+$/.test(trimmed);
}

function normalizeWaylandSessionState(raw) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const restoreToken = isValidRestoreToken(src.restoreToken) ? src.restoreToken.trim() : null;
  const rawMode = Number(src.persistMode);
  const persistMode =
    rawMode === WAYLAND_PERSIST_MODE_TRANSIENT ||
    rawMode === WAYLAND_PERSIST_MODE_APPLICATION ||
    rawMode === WAYLAND_PERSIST_MODE_PERSISTENT
      ? rawMode
      : WAYLAND_PERSIST_MODE_PERSISTENT;

  const sourceId =
    typeof src.sourceId === 'string' && src.sourceId.trim().length > 0 && src.sourceId.trim().length <= 128
      ? src.sourceId.trim()
      : null;

  const lastSourceLabel =
    typeof src.lastSourceLabel === 'string' && src.lastSourceLabel.trim().length > 0
      ? src.lastSourceLabel.trim().slice(0, 120)
      : null;

  const grantedAt = typeof src.grantedAt === 'number' && Number.isFinite(src.grantedAt) ? src.grantedAt : null;
  const updatedAt = typeof src.updatedAt === 'number' && Number.isFinite(src.updatedAt) ? src.updatedAt : null;

  return {
    restoreToken,
    persistMode,
    sourceId,
    lastSourceLabel,
    grantedAt,
    updatedAt
  };
}

function loadWaylandSessionState(filePath) {
  try {
    if (filePath && fs.existsSync(filePath)) {
      const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
      return normalizeWaylandSessionState(parsed);
    }
  } catch (err) {
    console.warn('CastViewer: Falha ao ler sessão Wayland:', err && err.message);
  }
  return { ...DEFAULT_WAYLAND_SESSION_STATE };
}

function saveWaylandSessionState(filePath, state) {
  const normalized = normalizeWaylandSessionState({
    ...state,
    updatedAt: Date.now()
  });
  try {
    if (filePath) {
      fs.mkdirSync(path.dirname(filePath), { recursive: true });
      fs.writeFileSync(filePath, JSON.stringify(normalized, null, 2), 'utf8');
    }
  } catch (err) {
    console.warn('CastViewer: Falha ao salvar sessão Wayland:', err && err.message);
  }
  return normalized;
}

function clearWaylandSessionState(filePath) {
  const empty = { ...DEFAULT_WAYLAND_SESSION_STATE };
  try {
    if (filePath && fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
  } catch (err) {
    console.warn('CastViewer: Falha ao limpar sessão Wayland:', err && err.message);
  }
  return empty;
}

/**
 * Builds options for Electron desktopCapturer.getSources(...) on Wayland vs X11/Windows/macOS.
 * Passes persistent=true and stored restoreToken when running under Wayland PipeWire.
 */
function buildWaylandCapturerOptions({
  lowRam = false,
  isWayland = false,
  restoreToken = null,
  persistent = true
} = {}) {
  const base = {
    types: ['screen', 'window'],
    thumbnailSize: lowRam ? { width: 160, height: 90 } : { width: 240, height: 135 },
    fetchWindowIcons: true
  };

  if (!isWayland) {
    return base;
  }

  const opts = {
    ...base,
    persistent: Boolean(persistent),
    persistMode: persistent ? WAYLAND_PERSIST_MODE_PERSISTENT : WAYLAND_PERSIST_MODE_TRANSIENT
  };

  if (isValidRestoreToken(restoreToken)) {
    opts.restoreToken = restoreToken.trim();
  }

  return opts;
}

/**
 * Extracts and merges any rotated PipeWire RestoreToken / metadata returned by Electron.
 */
function extractWaylandRestoreMetadata(sources = [], currentState = DEFAULT_WAYLAND_SESSION_STATE, activeSourceId = null) {
  const list = Array.isArray(sources) ? sources : [];
  const normalized = normalizeWaylandSessionState(currentState);
  let nextToken = normalized.restoreToken;
  let matchedSource = null;

  for (const src of list) {
    if (!src || typeof src !== 'object') continue;
    const candidateToken = src.restoreToken || src.restore_token || null;
    if (isValidRestoreToken(candidateToken)) {
      nextToken = candidateToken.trim();
      if (!matchedSource || src.id === activeSourceId) {
        matchedSource = src;
      }
    }
  }

  if (!matchedSource && activeSourceId && activeSourceId !== WAYLAND_PORTAL_SOURCE_ID) {
    matchedSource = list.find((s) => s && s.id === activeSourceId) || null;
  }
  if (!matchedSource && list.length > 0) {
    matchedSource = list.find((s) => s && String(s.id).startsWith('screen:')) || list[0];
  }

  const nextSourceLabel =
    matchedSource && typeof matchedSource.name === 'string' && matchedSource.name.trim()
      ? matchedSource.name.trim().slice(0, 120)
      : normalized.lastSourceLabel;

  const nextSourceId =
    matchedSource && typeof matchedSource.id === 'string' && matchedSource.id.trim()
      ? matchedSource.id.trim().slice(0, 128)
      : normalized.sourceId;

  const changed =
    nextToken !== normalized.restoreToken ||
    nextSourceId !== normalized.sourceId ||
    nextSourceLabel !== normalized.lastSourceLabel;

  return {
    changed,
    state: normalizeWaylandSessionState({
      ...normalized,
      restoreToken: nextToken,
      sourceId: nextSourceId,
      lastSourceLabel: nextSourceLabel,
      grantedAt: matchedSource || isValidRestoreToken(nextToken) ? normalized.grantedAt || Date.now() : normalized.grantedAt
    })
  };
}

/**
 * Wayland portals should NEVER be auto-polled in timers / visibility listeners because
 * unsolicited desktopCapturer probes trigger compositor permission pickers.
 */
function shouldAutoPollDesktopSources({ isWayland = false, waylandPortalRequired = false } = {}) {
  if (isWayland || waylandPortalRequired) {
    return false;
  }
  return true;
}

/**
 * Returns synthetic PipeWire portal cards when Wayland compositors isolate enumeration.
 * Includes a session-restoring primary entry plus an explicit re-pick trigger.
 */
function buildWaylandPortalCards({ hasRestoreToken = false, hasActiveSession = false, lastSourceLabel = null } = {}) {
  const restored = Boolean(hasRestoreToken || hasActiveSession);
  const labelSuffix = lastSourceLabel && restored ? ` — ${lastSourceLabel}` : '';
  const cards = [
    {
      id: WAYLAND_PORTAL_SOURCE_ID,
      name: restored
        ? `Sessão Wayland Persistida (PipeWire)${labelSuffix}`
        : 'Tela ou Janela (Seletor PipeWire / Wayland)',
      thumbnail: '',
      appIcon: null,
      display_id: '0',
      isScreen: true,
      isPortalPicker: true,
      hasRestoreToken: Boolean(hasRestoreToken),
      isSessionPersisted: restored
    }
  ];

  if (restored) {
    cards.push({
      id: WAYLAND_RESELECT_SOURCE_ID,
      name: 'Trocar Tela/Janela (Abrir Portal Wayland)',
      thumbnail: '',
      appIcon: null,
      display_id: 'reselect',
      isScreen: true,
      isPortalPicker: true,
      isPortalReset: true
    });
  }

  return cards;
}

/**
 * Strips custom Wayland hints before falling back to strict Chromium signature if needed.
 */
function toBaseCapturerOptions(opts = {}) {
  return {
    types: Array.isArray(opts.types) ? opts.types : ['screen', 'window'],
    thumbnailSize: opts.thumbnailSize || { width: 240, height: 135 },
    fetchWindowIcons: Boolean(opts.fetchWindowIcons)
  };
}

module.exports = {
  WAYLAND_PERSIST_MODE_TRANSIENT,
  WAYLAND_PERSIST_MODE_APPLICATION,
  WAYLAND_PERSIST_MODE_PERSISTENT,
  WAYLAND_PORTAL_SOURCE_ID,
  WAYLAND_RESELECT_SOURCE_ID,
  DEFAULT_WAYLAND_SESSION_STATE,
  isValidRestoreToken,
  normalizeWaylandSessionState,
  loadWaylandSessionState,
  saveWaylandSessionState,
  clearWaylandSessionState,
  buildWaylandCapturerOptions,
  extractWaylandRestoreMetadata,
  shouldAutoPollDesktopSources,
  buildWaylandPortalCards,
  toBaseCapturerOptions
};
