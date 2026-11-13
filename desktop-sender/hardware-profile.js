/**
 * CastViewer - Hardware Auto-Detection & Multi-GPU Compatibility Profile
 * Identifies CPU, RAM and GPU (NVIDIA, Intel, AMD, Hybrid Optimus, Software/Legacy)
 * and builds safe Chromium WebRTC/WGC/MediaFoundation switches + default preset.
 */

const VENDOR_IDS = {
  AMD: 0x1002,
  AMD_ALT: 0x1022,
  NVIDIA: 0x10de,
  INTEL: 0x8086,
  MICROSOFT: 0x1414
};

const VIRTUAL_VENDOR_IDS = new Set([
  0x15ad, // VMware
  0x1af4, // Red Hat VirtIO / QEMU
  0x80ee, // VirtualBox
  0x1234  // Bochs / QEMU standard VGA
]);

const WEAK_CPU_MODEL_RE =
  /\b(Celeron|Pentium|Atom|Sempron|Core\(TM\)\s*2|Core 2|E1-|E2-|A4-|A6-|A9-)\b/i;
const STRICT_BUDGET_CPU_RE = /\b(Celeron|Atom|Sempron|E1-|E2-)\b/i;
const VIRTUAL_GPU_NAME_RE =
  /\b(Microsoft Basic|VMware|VirtualBox|VirtIO|QEMU|Parallels|SwiftShader|llvmpipe|Remote Display)\b/i;
const LEGACY_WEAK_GPU_RE =
  /\b(GeForce\s*(210|310|410|510|605|610|710|GT\s*2|GT\s*4|GT\s*5|GT\s*610|GT\s*710|GT\s*1030)|Radeon\s*(HD\s*[23456]\d{3}|R[234]\b)|Intel\(R\)\s*HD\s*Graphics\s*(2000|2500|3000|4000)?$)/i;

function parseVendorId(rawVendorId) {
  if (typeof rawVendorId === 'number' && Number.isFinite(rawVendorId)) {
    return rawVendorId;
  }
  if (typeof rawVendorId === 'string') {
    const trimmed = rawVendorId.trim();
    if (!trimmed) return 0;
    const parsed = trimmed.startsWith('0x') ? Number.parseInt(trimmed, 16) : Number(trimmed);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function classifyGpuDeviceVendor(device) {
  const vid = parseVendorId(device && device.vendorId);
  const name = String((device && (device.deviceString || device.driverVendor)) || '');

  if (vid === VENDOR_IDS.NVIDIA || /nvidia/i.test(name)) return 'nvidia';
  if (vid === VENDOR_IDS.AMD || vid === VENDOR_IDS.AMD_ALT || /\b(amd|ati|radeon)\b/i.test(name)) {
    return 'amd';
  }
  if (vid === VENDOR_IDS.INTEL || /\bintel\b/i.test(name)) return 'intel';
  if (vid === VENDOR_IDS.MICROSOFT || /microsoft basic/i.test(name)) return 'microsoft';
  if (VIRTUAL_VENDOR_IDS.has(vid) || VIRTUAL_GPU_NAME_RE.test(name)) return 'virtual';
  return 'unknown';
}

function cleanCpuModelName(rawModel) {
  return String(rawModel || 'Processador Desktop')
    .replace(/\(R\)|\(TM\)|CPU|@/gi, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Classifies host hardware and produces initial recommended CastViewer settings.
 * Pure function so it can run identically in main process or headless tests.
 */
function detectAndRecommendProfile({ cpus = [], totalMemBytes = 0, gpuInfo = null, gpuFeatureStatus = null } = {}) {
  const cpuList = Array.isArray(cpus) ? cpus : [];
  const threads = cpuList.length || 4;
  const rawCpuModel = (cpuList[0] && cpuList[0].model) || '';
  const cpuModel = cleanCpuModelName(rawCpuModel);
  const ramGb = totalMemBytes > 0 ? Math.round((totalMemBytes / 1024 ** 3) * 10) / 10 : 8;

  const rawDevices = Array.isArray(gpuInfo && gpuInfo.gpuDevice) ? gpuInfo.gpuDevice : [];
  const normalizedGpus = rawDevices.map((dev) => {
    const vendor = classifyGpuDeviceVendor(dev);
    const name = String((dev && dev.deviceString) || (dev && dev.driverVendor) || vendor.toUpperCase()).trim();
    return {
      vendor,
      name,
      active: Boolean(dev && dev.active),
      driverVersion: dev && dev.driverVersion ? String(dev.driverVersion) : ''
    };
  });

  const hardwareGpus = normalizedGpus.filter(
    (g) => g.vendor !== 'microsoft' && g.vendor !== 'virtual' && g.vendor !== 'unknown'
  );
  const realOrListedGpus = hardwareGpus.length > 0 ? hardwareGpus : normalizedGpus;

  const hasNvidia = hardwareGpus.some((g) => g.vendor === 'nvidia');
  const hasIntel = hardwareGpus.some((g) => g.vendor === 'intel');
  const hasAmd = hardwareGpus.some((g) => g.vendor === 'amd');

  const aux = (gpuInfo && gpuInfo.auxAttributes) || {};
  const isOptimus = Boolean(aux.optimus);
  const isAmdSwitchable = Boolean(aux.amdSwitchable);
  const hasDistinctMultiVendor =
    Number(hasNvidia) + Number(hasIntel) + Number(hasAmd) >= 2;
  const isHybridGpu = isOptimus || isAmdSwitchable || hasDistinctMultiVendor;

  const isSoftwareOnlyGpu =
    hardwareGpus.length === 0 &&
    (normalizedGpus.length === 0 ||
      normalizedGpus.every(
        (g) => g.vendor === 'microsoft' || g.vendor === 'virtual' || VIRTUAL_GPU_NAME_RE.test(g.name)
      ));

  const primaryGpu =
    realOrListedGpus.find((g) => g.active) ||
    realOrListedGpus.find((g) => g.vendor === 'nvidia') ||
    realOrListedGpus[0] || { vendor: 'unknown', name: 'GPU padrão', active: false };

  const videoEncodeStatus = String((gpuFeatureStatus && gpuFeatureStatus.video_encode) || '');
  const hasHwEncode = videoEncodeStatus
    ? !/\b(disabled|unavailable)\b/i.test(videoEncodeStatus)
    : !isSoftwareOnlyGpu;

  const glRenderer = String(aux.glRenderer || '');
  const gpuSummaryNames = hardwareGpus.length > 0
    ? hardwareGpus.map((g) => g.name)
    : [primaryGpu.name || (glRenderer ? glRenderer.slice(0, 48) : 'Adaptador de vídeo básico')];
  const gpuSummary = Array.from(new Set(gpuSummaryNames)).join(' + ');

  const lowRamMode = ramGb > 0 && ramGb <= 6.2;

  const isBudgetOrWeakCpu =
    threads <= 2 ||
    STRICT_BUDGET_CPU_RE.test(rawCpuModel) ||
    (threads <= 4 && WEAK_CPU_MODEL_RE.test(rawCpuModel));

  const isLegacyWeakGpu =
    isSoftwareOnlyGpu ||
    !hasHwEncode ||
    LEGACY_WEAK_GPU_RE.test(gpuSummary);

  const isLimitedIntegrated =
    (!hasNvidia && threads <= 4) ||
    (!hasNvidia && hasIntel && threads < 8 && !/\b(Iris|Arc)\b/i.test(gpuSummary));

  let tier = 'high';
  let lowSpecMode = false;
  let compatMode = false;
  let recommendedPreset = '1080p60';
  let reason = 'Hardware moderno detectado: pronto para 1080p 60 FPS fluido.';

  if (isBudgetOrWeakCpu || isLegacyWeakGpu) {
    tier = 'weak';
    lowSpecMode = true;
    compatMode = isSoftwareOnlyGpu;
    recommendedPreset = '720p30';
    if (isSoftwareOnlyGpu) {
      reason = 'Driver de vídeo básico/virtual detectado: Modo PC Fraco + Compatibilidade (720p 30 FPS).';
    } else if (!hasHwEncode) {
      reason = 'Codificação por hardware indisponível: Modo PC Fraco (720p 30 FPS).';
    } else {
      reason = 'Processador ou GPU básica detectada: Modo PC Fraco em 720p 30 FPS para evitar travamentos.';
    }
  } else if (lowRamMode || isLimitedIntegrated) {
    tier = 'balanced';
    lowSpecMode = false;
    compatMode = false;
    recommendedPreset = '720p60';
    reason = lowRamMode
      ? 'Memória RAM limitada (<= 6 GB): Modo Pouca RAM + 720p 60 FPS recomendado.'
      : 'GPU integrada intermediária: 720p 60 FPS equilibrado para manter 60 FPS constantes.';
  }

  return {
    detectedHardware: {
      cpuModel,
      threads,
      ramGb,
      gpuSummary,
      primaryVendor: primaryGpu.vendor,
      hasNvidia,
      hasIntel,
      hasAmd,
      isHybridGpu,
      isSoftwareOnlyGpu,
      hasHwEncode,
      videoEncodeStatus: videoEncodeStatus || 'unknown',
      tier
    },
    recommendation: {
      preset: recommendedPreset,
      lowSpecMode,
      lowRamMode,
      compatMode,
      hybridGpuDetected: isHybridGpu,
      reason
    }
  };
}

/**
 * Produces Chromium command-line switches tuned for the current prefs + GPU vendor.
 * Returns { switches: Array<[name, value?]>, enableFeatures: string[], disableFeatures: string[] }.
 */
function buildChromiumSwitches(prefs = {}) {
  const lowSpecMode = Boolean(prefs.lowSpecMode);
  const lowRamMode = Boolean(prefs.lowRamMode);
  const compatMode = Boolean(prefs.compatMode);
  const hybridGpuDetected = Boolean(prefs.hybridGpuDetected);
  const platform = prefs.platform || 'win32';
  const isLinux = platform === 'linux';
  const isWayland = isLinux && Boolean(prefs.wayland);

  const switches = [
    ['renderer-process-limit', '1'],
    ['disable-renderer-backgrounding'],
    ['disable-background-timer-throttling'],
    ['disable-backgrounding-occluded-windows'],
    ['webrtc-max-cpu-consumption-percentage', '100']
  ];

  if (lowRamMode) {
    switches.push(['js-flags', '--max-old-space-size=512']);
    switches.push(['disk-cache-size', '10485760']);
    switches.push(['media-cache-size', '10485760']);
  }

  const baseDisableFeatures = [
    'MediaRouter',
    'OptimizationHints',
    'Translate',
    'AutofillServerCommunication',
    'CalculateNativeWinOcclusion'
  ];

  if (compatMode) {
    // Safe fallback for hybrid laptop DXGI issues, old drivers, or virtual adapters:
    // disables WGC shared-texture + WGC capture forcing on Windows and keeps WebRTC software H.264 unlocked.
    const disableFeatures = isLinux
      ? [...baseDisableFeatures]
      : [
          ...baseDisableFeatures,
          'WebRtcWgcRequireBorder',
          'WebRtcAllowWgcUsingTexture',
          'WebRtcAllowWgcDesktopCapture'
        ];
    const enableFeatures = isWayland ? ['WebRTCPipeWireCapturer'] : [];
    if (isWayland) {
      switches.push(['ozone-platform-hint', 'auto']);
      switches.push(['enable-features', enableFeatures.join(',')]);
    }
    switches.push(['disable-features', disableFeatures.join(',')]);
    return {
      switches,
      enableFeatures,
      disableFeatures
    };
  }

  switches.push(['ignore-gpu-blocklist']);
  if (isLinux) {
    if (isWayland) {
      switches.push(['ozone-platform-hint', 'auto']);
    }
  } else {
    switches.push(['use-angle', 'd3d11']);
  }
  if (!lowSpecMode && !lowRamMode) {
    switches.push(['enable-gpu-rasterization']);
  }
  switches.push(['enable-zero-copy']);
  switches.push(['enable-accelerated-video-decode']);
  switches.push(['enable-accelerated-video-encode']);

  let enableFeatures;
  let disableFeatures;
  if (isLinux) {
    enableFeatures = ['WebRTCPipeWireCapturer'];
    if (!lowSpecMode) {
      enableFeatures.push('VaapiVideoEncoder');
    }
    // Keep OpenH264 software H.264 fallback unlocked on Linux distros without VA-API drivers
    disableFeatures = [...baseDisableFeatures];
  } else {
    // Omit MediaFoundationUseSWBRCForH264Desktop / ForceAMDGPU: triggers Chromium 0xC000001D
    // renderer crashes when MediaRecorder + WebRTC desktop capture run concurrently on AMD GPUs.
    enableFeatures = ['MediaFoundationVideoEncodeAccelerator'];
    if (!hybridGpuDetected) {
      enableFeatures.push('WebRtcAllowWgcUsingTexture');
    }
    disableFeatures = [
      ...baseDisableFeatures,
      'WebRtcScreenshareSwEncoding',
      'WebRtcWgcRequireBorder'
    ];
  }

  switches.push(['enable-features', enableFeatures.join(',')]);
  switches.push(['disable-features', disableFeatures.join(',')]);
  switches.push([
    'force-fieldtrials',
    'WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:0/WebRTC-Pacer-DrainQueue/Enabled/WebRTC-Pacer-IgnoreTransportOverhead/Enabled/WebRTC-FlexFEC-03/Disabled/'
  ]);

  return {
    switches,
    enableFeatures,
    disableFeatures
  };
}

module.exports = {
  VENDOR_IDS,
  detectAndRecommendProfile,
  buildChromiumSwitches
};
