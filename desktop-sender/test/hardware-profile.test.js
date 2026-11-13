const test = require('node:test');
const assert = require('node:assert/strict');
const {
  detectAndRecommendProfile,
  buildChromiumSwitches
} = require('../hardware-profile');

const GIB = 1024 ** 3;

function fakeCpus(model, count) {
  return Array.from({ length: count }, () => ({ model, speed: 2400 }));
}

test('Modern AMD desktop/laptop gets 1080p60 high tier without lowSpec/lowRam', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('AMD Ryzen 7 5700U with Radeon Graphics', 16),
    totalMemBytes: 16 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x1002, active: true, deviceString: 'AMD Radeon(TM) Graphics' },
        { vendorId: 0x1414, active: false, deviceString: 'Microsoft Basic Render Driver' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.primaryVendor, 'amd');
  assert.equal(res.detectedHardware.hasAmd, true);
  assert.equal(res.detectedHardware.isHybridGpu, false);
  assert.equal(res.recommendation.preset, '1080p60');
  assert.equal(res.recommendation.lowSpecMode, false);
  assert.equal(res.recommendation.lowRamMode, false);
  assert.equal(res.recommendation.compatMode, false);

  const sw = buildChromiumSwitches({
    ...res.recommendation,
    detectedHardware: res.detectedHardware
  });
  assert.ok(sw.enableFeatures.includes('MediaFoundationVideoEncodeAccelerator'));
  assert.ok(sw.enableFeatures.includes('WebRtcAllowWgcUsingTexture'));
  assert.ok(!sw.enableFeatures.includes('MediaFoundationUseSWBRCForH264Desktop'));
  assert.ok(!sw.enableFeatures.includes('MediaFoundationSWBRCForH264ForceAMDGPU'));
  assert.ok(!sw.switches.some(([k]) => k === 'js-flags'));
});

test('NVIDIA GTX/RTX desktop gets 1080p60 and omits AMD-only force switch', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('Intel(R) Core(TM) i5-10400F CPU @ 2.90GHz', 12),
    totalMemBytes: 16 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x10de, active: true, deviceString: 'NVIDIA GeForce RTX 3060' },
        { vendorId: 0x1414, active: false, deviceString: 'Microsoft Basic Render Driver' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.primaryVendor, 'nvidia');
  assert.equal(res.detectedHardware.hasNvidia, true);
  assert.equal(res.detectedHardware.hasAmd, false);
  assert.equal(res.recommendation.preset, '1080p60');
  assert.equal(res.recommendation.lowSpecMode, false);
  assert.equal(res.recommendation.compatMode, false);

  const sw = buildChromiumSwitches({
    ...res.recommendation,
    detectedHardware: res.detectedHardware
  });
  assert.ok(!sw.enableFeatures.includes('MediaFoundationSWBRCForH264ForceAMDGPU'));
  assert.ok(sw.enableFeatures.includes('MediaFoundationVideoEncodeAccelerator'));
  assert.ok(sw.enableFeatures.includes('WebRtcAllowWgcUsingTexture'));
});

test('Hybrid Intel + NVIDIA Optimus laptop disables WGC shared-texture to prevent black screen', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz', 16),
    totalMemBytes: 16 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: true, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x8086, active: true, deviceString: 'Intel(R) UHD Graphics' },
        { vendorId: 0x10de, active: false, deviceString: 'NVIDIA GeForce RTX 3050 Laptop GPU' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.isHybridGpu, true);
  assert.equal(res.recommendation.hybridGpuDetected, true);
  assert.equal(res.recommendation.preset, '1080p60');
  assert.equal(res.recommendation.compatMode, false);

  const sw = buildChromiumSwitches({
    ...res.recommendation,
    detectedHardware: res.detectedHardware
  });
  assert.ok(!sw.enableFeatures.includes('WebRtcAllowWgcUsingTexture'));
  assert.ok(sw.enableFeatures.includes('MediaFoundationVideoEncodeAccelerator'));
});

test('Mid-range Intel i5-7200U 4-thread laptop with HD 620 gets smooth 720p60', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('Intel(R) Core(TM) i5-7200U CPU @ 2.50GHz', 4),
    totalMemBytes: 8 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x8086, active: true, deviceString: 'Intel(R) HD Graphics 620' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.primaryVendor, 'intel');
  assert.equal(res.detectedHardware.tier, 'balanced');
  assert.equal(res.recommendation.preset, '720p60');
  assert.equal(res.recommendation.lowSpecMode, false);
  assert.equal(res.recommendation.lowRamMode, false);
});

test('Weak Celeron 4GB notebook gets 720p30 + lowSpecMode + lowRamMode', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('Intel(R) Celeron(R) N4020 CPU @ 1.10GHz', 2),
    totalMemBytes: 3.85 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x8086, active: true, deviceString: 'Intel(R) UHD Graphics 600' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.tier, 'weak');
  assert.equal(res.recommendation.preset, '720p30');
  assert.equal(res.recommendation.lowSpecMode, true);
  assert.equal(res.recommendation.lowRamMode, true);
  assert.equal(res.recommendation.compatMode, false);
});

test('Microsoft Basic Display / no GPU driver enables Safe Compat Mode + 720p30', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('Intel(R) Core(TM) i3-10100 CPU @ 3.60GHz', 8),
    totalMemBytes: 8 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x1414, active: true, deviceString: 'Microsoft Basic Render Driver' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'unavailable_software' }
  });

  assert.equal(res.detectedHardware.isSoftwareOnlyGpu, true);
  assert.equal(res.recommendation.preset, '720p30');
  assert.equal(res.recommendation.lowSpecMode, true);
  assert.equal(res.recommendation.compatMode, true);

  const sw = buildChromiumSwitches({
    ...res.recommendation,
    detectedHardware: res.detectedHardware
  });
  assert.equal(sw.enableFeatures.length, 0);
  assert.ok(sw.disableFeatures.includes('WebRtcAllowWgcDesktopCapture'));
  assert.ok(!sw.disableFeatures.includes('WebRtcScreenshareSwEncoding'));
});

test('Intel Core i5 11th gen with Iris Xe and 8GB RAM gets smooth 1080p60', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('11th Gen Intel(R) Core(TM) i5-1135G7 @ 2.40GHz', 8),
    totalMemBytes: 8 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x8086, active: true, deviceString: 'Intel(R) Iris(R) Xe Graphics' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.primaryVendor, 'intel');
  assert.equal(res.detectedHardware.tier, 'high');
  assert.equal(res.recommendation.preset, '1080p60');
  assert.equal(res.recommendation.lowSpecMode, false);
});

test('NVIDIA GT 1030 (without NVENC) falls back cleanly to 720p30 + lowSpecMode', () => {
  const res = detectAndRecommendProfile({
    cpus: fakeCpus('Intel(R) Core(TM) i3-9100F CPU @ 3.60GHz', 4),
    totalMemBytes: 8 * GIB,
    gpuInfo: {
      auxAttributes: { optimus: false, amdSwitchable: false },
      gpuDevice: [
        { vendorId: 0x10de, active: true, deviceString: 'NVIDIA GeForce GT 1030' }
      ]
    },
    gpuFeatureStatus: { video_encode: 'enabled' }
  });

  assert.equal(res.detectedHardware.tier, 'weak');
  assert.equal(res.recommendation.preset, '720p30');
  assert.equal(res.recommendation.lowSpecMode, true);
});

test('buildChromiumSwitches enables low-latency WebRTC field trials safely outside compatMode', () => {
  const normal = buildChromiumSwitches({ compatMode: false, lowSpecMode: false, lowRamMode: false });
  const ftSwitch = normal.switches.find(([flag]) => flag === 'force-fieldtrials');
  assert.ok(ftSwitch);
  assert.match(ftSwitch[1], /WebRTC-ForceSendPlayoutDelay\/min_ms:0,max_ms:0\//);
  assert.match(ftSwitch[1], /WebRTC-Pacer-DrainQueue\/Enabled\//);
  assert.match(ftSwitch[1], /WebRTC-Pacer-IgnoreTransportOverhead\/Enabled\//);
  assert.ok(!normal.enableFeatures.includes('MediaFoundationUseSWBRCForH264Desktop'));
  assert.ok(!normal.enableFeatures.includes('ForceAMDGPU'));

  const compat = buildChromiumSwitches({ compatMode: true });
  assert.equal(compat.switches.find(([flag]) => flag === 'force-fieldtrials'), undefined);
});

test('Linux X11/Wayland switches omit d3d11 and Win32 MediaFoundation flags while enabling PipeWire', () => {
  const linuxX11 = buildChromiumSwitches({
    platform: 'linux',
    wayland: false,
    compatMode: false,
    lowSpecMode: false,
    lowRamMode: false
  });
  assert.equal(linuxX11.switches.find(([flag]) => flag === 'use-angle'), undefined);
  assert.equal(linuxX11.switches.find(([flag]) => flag === 'ozone-platform-hint'), undefined);
  assert.ok(!linuxX11.enableFeatures.includes('MediaFoundationVideoEncodeAccelerator'));
  assert.ok(linuxX11.enableFeatures.includes('WebRTCPipeWireCapturer'));
  assert.ok(linuxX11.enableFeatures.includes('VaapiVideoEncoder'));
  assert.ok(!linuxX11.disableFeatures.includes('WebRtcScreenshareSwEncoding'));

  const linuxWayland = buildChromiumSwitches({
    platform: 'linux',
    wayland: true,
    compatMode: false,
    lowSpecMode: false,
    lowRamMode: false
  });
  assert.deepEqual(
    linuxWayland.switches.find(([flag]) => flag === 'ozone-platform-hint'),
    ['ozone-platform-hint', 'auto']
  );

  const linuxCompat = buildChromiumSwitches({
    platform: 'linux',
    wayland: true,
    compatMode: true
  });
  assert.ok(linuxCompat.enableFeatures.includes('WebRTCPipeWireCapturer'));
  assert.equal(linuxCompat.switches.find(([flag]) => flag === 'force-fieldtrials'), undefined);
  assert.ok(!linuxCompat.disableFeatures.includes('WebRtcAllowWgcDesktopCapture'));
});



