const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { detectAndRecommendProfile, buildChromiumSwitches } = require('../hardware-profile');
const {
  WAYLAND_PERSIST_MODE_PERSISTENT,
  WAYLAND_PORTAL_SOURCE_ID,
  WAYLAND_RESELECT_SOURCE_ID,
  isValidRestoreToken,
  loadWaylandSessionState,
  saveWaylandSessionState,
  clearWaylandSessionState,
  buildWaylandCapturerOptions,
  extractWaylandRestoreMetadata,
  shouldAutoPollDesktopSources,
  buildWaylandPortalCards,
  toBaseCapturerOptions
} = require('../wayland-session');

const GIB = 1024 ** 3;

test('Wayland session state persists valid RestoreToken to disk and rejects malformed tokens', () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cv-wayland-'));
  const sessionFile = path.join(tmpDir, 'wayland-session.json');

  assert.equal(isValidRestoreToken(''), false);
  assert.equal(isValidRestoreToken('ab'), false);
  assert.equal(isValidRestoreToken('bad token with spaces'), false);
  assert.equal(isValidRestoreToken('pw-restore-9d2a1b4c-8f01'), true);

  const saved = saveWaylandSessionState(sessionFile, {
    restoreToken: '  pw-restore-9d2a1b4c-8f01  ',
    persistMode: WAYLAND_PERSIST_MODE_PERSISTENT,
    sourceId: 'screen:1:0',
    lastSourceLabel: 'Monitor HDMI-1',
    grantedAt: 1731520000000
  });
  assert.equal(saved.restoreToken, 'pw-restore-9d2a1b4c-8f01');
  assert.equal(saved.persistMode, 2);

  const loaded = loadWaylandSessionState(sessionFile);
  assert.equal(loaded.restoreToken, 'pw-restore-9d2a1b4c-8f01');
  assert.equal(loaded.lastSourceLabel, 'Monitor HDMI-1');

  const cleared = clearWaylandSessionState(sessionFile);
  assert.equal(cleared.restoreToken, null);
  assert.equal(fs.existsSync(sessionFile), false);

  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('buildWaylandCapturerOptions injects RestoreToken & persistMode=2 only on Wayland', () => {
  const winOpts = buildWaylandCapturerOptions({
    lowRam: false,
    isWayland: false,
    restoreToken: 'pw-restore-1234'
  });
  assert.equal('restoreToken' in winOpts, false);
  assert.equal('persistent' in winOpts, false);

  const waylandOpts = buildWaylandCapturerOptions({
    lowRam: true,
    isWayland: true,
    restoreToken: 'pw-restore-1234',
    persistent: true
  });
  assert.deepEqual(waylandOpts.thumbnailSize, { width: 160, height: 90 });
  assert.equal(waylandOpts.persistent, true);
  assert.equal(waylandOpts.persistMode, 2);
  assert.equal(waylandOpts.restoreToken, 'pw-restore-1234');

  const baseOnly = toBaseCapturerOptions(waylandOpts);
  assert.equal('restoreToken' in baseOnly, false);
  assert.deepEqual(baseOnly.types, ['screen', 'window']);
});

test('extractWaylandRestoreMetadata rotates and keeps newest portal token across source switches', () => {
  const initial = {
    restoreToken: 'pw-token-old-1111',
    persistMode: 2,
    sourceId: 'screen:0:0',
    lastSourceLabel: 'Tela 1',
    grantedAt: 1000
  };
  const updated = extractWaylandRestoreMetadata(
    [
      { id: 'screen:1:0', name: 'Monitor Secundário', restoreToken: 'pw-token-rotated-2222' }
    ],
    initial,
    'screen:1:0'
  );
  assert.equal(updated.changed, true);
  assert.equal(updated.state.restoreToken, 'pw-token-rotated-2222');
  assert.equal(updated.state.lastSourceLabel, 'Monitor Secundário');
  assert.equal(updated.state.sourceId, 'screen:1:0');
});

test('Wayland auto-polling is disabled and portal cards provide persisted + reselect actions', () => {
  assert.equal(shouldAutoPollDesktopSources({ isWayland: true, waylandPortalRequired: false }), false);
  assert.equal(shouldAutoPollDesktopSources({ isWayland: false, waylandPortalRequired: true }), false);
  assert.equal(shouldAutoPollDesktopSources({ isWayland: false, waylandPortalRequired: false }), true);

  const freshCards = buildWaylandPortalCards({ hasRestoreToken: false, hasActiveSession: false });
  assert.equal(freshCards.length, 1);
  assert.equal(freshCards[0].id, WAYLAND_PORTAL_SOURCE_ID);

  const persistedCards = buildWaylandPortalCards({
    hasRestoreToken: true,
    hasActiveSession: true,
    lastSourceLabel: 'Monitor Principal'
  });
  assert.equal(persistedCards.length, 2);
  assert.match(persistedCards[0].name, /Sessão Wayland Persistida/);
  assert.match(persistedCards[0].name, /Monitor Principal/);
  assert.equal(persistedCards[1].id, WAYLAND_RESELECT_SOURCE_ID);
});

test('macOS Apple Silicon profile & Darwin switches enable ScreenCaptureKit loopback without Win32/ANGLE flags', () => {
  const hw = detectAndRecommendProfile({
    cpus: Array.from({ length: 8 }, () => ({ model: 'Apple M2 Pro', speed: 3200 })),
    totalMemBytes: 16 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [{ vendorId: 0x106b, active: true, deviceString: 'Apple M2 Pro' }]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(hw.detectedHardware.primaryVendor, 'apple');
  assert.equal(hw.detectedHardware.hasApple, true);
  assert.equal(hw.detectedHardware.isSoftwareOnlyGpu, false);
  assert.equal(hw.recommendation.preset, '1080p60');

  const macSwitches = buildChromiumSwitches({
    platform: 'darwin',
    compatMode: false,
    lowSpecMode: false,
    lowRamMode: false
  });
  assert.equal(macSwitches.switches.find(([k]) => k === 'use-angle'), undefined);
  assert.equal(macSwitches.switches.find(([k]) => k === 'ozone-platform-hint'), undefined);
  assert.ok(!macSwitches.enableFeatures.includes('MediaFoundationVideoEncodeAccelerator'));
  assert.ok(!macSwitches.enableFeatures.includes('WebRtcAllowWgcUsingTexture'));
  assert.ok(macSwitches.enableFeatures.includes('MacLoopbackAudioForScreenShare'));
  assert.ok(macSwitches.enableFeatures.includes('MacCatapSystemAudioLoopbackCapture'));
  assert.ok(macSwitches.enableFeatures.includes('MacSckSystemAudioLoopbackOverride'));
  assert.ok(!macSwitches.disableFeatures.includes('WebRtcScreenshareSwEncoding'));

  const macCompat = buildChromiumSwitches({
    platform: 'darwin',
    compatMode: true
  });
  assert.ok(macCompat.enableFeatures.includes('MacSckSystemAudioLoopbackOverride'));
  assert.ok(!macCompat.disableFeatures.includes('WebRtcAllowWgcDesktopCapture'));
});
