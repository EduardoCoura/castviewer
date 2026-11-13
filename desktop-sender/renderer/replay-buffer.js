/**
 * CastViewer - Replay Clip Ring Recorder & Capture Helpers
 * Pure helper utilities + single-encoder staggered ring with frozen archive for zero-overlap 15-30s clips.
 */

(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  if (root && typeof root === 'object') {
    root.CastViewerCapture = api;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  const SUPPORTED_MIME_CANDIDATES = [
    'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
    'video/mp4;codecs=avc1,opus',
    'video/mp4',
    'video/webm;codecs=vp8,opus',
    'video/webm;codecs=vp9,opus',
    'video/webm'
  ];

  function pickPreferredRecorderMimeType(isTypeSupportedFn) {
    if (typeof isTypeSupportedFn !== 'function') {
      return '';
    }
    for (const candidate of SUPPORTED_MIME_CANDIDATES) {
      try {
        if (isTypeSupportedFn(candidate)) {
          return candidate;
        }
      } catch (_) {}
    }
    return '';
  }

  function extensionForMimeType(mimeType) {
    const lower = String(mimeType || '').toLowerCase();
    return lower.includes('mp4') ? 'mp4' : 'webm';
  }

  function formatCaptureTimestamp(dateInput = new Date()) {
    const d = dateInput instanceof Date ? dateInput : new Date(dateInput);
    const pad = (n) => String(n).padStart(2, '0');
    return (
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_` +
      `${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}`
    );
  }

  function formatFileSize(bytes) {
    const val = Number(bytes) || 0;
    if (val < 1024) return `${val} B`;
    if (val < 1024 * 1024) return `${(val / 1024).toFixed(0)} KB`;
    return `${(val / (1024 * 1024)).toFixed(1)} MB`;
  }

  /**
   * Maintains a single active MediaRecorder (at most one active encoder at any time)
   * rotated every halfWindowMs into an in-memory completedArchive Blob so 15-30s clips
   * remain ready without running two concurrent hardware/software video encoders.
   */
  class StaggeredReplayRecorder {
    constructor({
      stream,
      mimeType,
      videoBitsPerSecond = 2600000,
      audioBitsPerSecond = 128000,
      windowMs = 30000,
      createRecorder = null,
      onStateChange = null
    }) {
      this.stream = stream;
      this.windowMs = Math.max(6000, windowMs);
      this.halfWindowMs = Math.round(this.windowMs / 2);
      this.videoBitsPerSecond = videoBitsPerSecond;
      this.audioBitsPerSecond = audioBitsPerSecond;
      this.mimeType =
        mimeType !== undefined
          ? mimeType
          : pickPreferredRecorderMimeType(
              typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported
                ? (m) => MediaRecorder.isTypeSupported(m)
                : null
            );
      this.createRecorder = createRecorder;
      this.onStateChange = typeof onStateChange === 'function' ? onStateChange : () => {};

      this.slots = [];
      this.completedArchive = null;
      this._pendingRotationPromise = null;
      this.rotationTimer = null;
      this.running = false;
      this.saving = false;
    }

    _instantiateRecorder(stream, opts) {
      if (typeof this.createRecorder === 'function') {
        return this.createRecorder(stream, opts);
      }
      return new MediaRecorder(stream, opts);
    }

    _hasAudioTrack() {
      try {
        return Boolean(this.stream && typeof this.stream.getAudioTracks === 'function' && this.stream.getAudioTracks().length > 0);
      } catch (_) {
        return false;
      }
    }

    _startSlot() {
      if (!this.running || !this.stream) return null;
      const opts = {};
      if (this.mimeType) opts.mimeType = this.mimeType;
      if (this.videoBitsPerSecond > 0) opts.videoBitsPerSecond = this.videoBitsPerSecond;
      if (this._hasAudioTrack() && this.audioBitsPerSecond > 0) {
        opts.audioBitsPerSecond = this.audioBitsPerSecond;
      }

      const recorder = this._instantiateRecorder(this.stream, opts);
      const slot = {
        id: `${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        recorder,
        mimeType: this.mimeType || recorder.mimeType || 'video/webm',
        chunks: [],
        startedAt: Date.now(),
        stopped: false,
        discarded: false
      };

      recorder.ondataavailable = (ev) => {
        if (!slot.discarded && ev && ev.data && ev.data.size > 0) {
          slot.chunks.push(ev.data);
        }
      };

      recorder.onerror = () => {
        slot.stopped = true;
      };

      recorder.start(4000);
      this.slots.push(slot);
      return slot;
    }

    _discardSlot(slot) {
      if (!slot) return;
      slot.discarded = true;
      slot.chunks = [];
      if (!slot.stopped && slot.recorder && slot.recorder.state !== 'inactive') {
        try {
          slot.recorder.stop();
        } catch (_) {}
      }
      slot.stopped = true;
      this.slots = this.slots.filter((s) => s !== slot);
    }

    _stopSlotToClip(slot) {
      if (!slot) return Promise.resolve(null);
      const durationSec = Math.max(1, Math.round((Date.now() - slot.startedAt) / 1000));
      const mimeType = slot.mimeType || this.mimeType || 'video/webm';

      return new Promise((resolve) => {
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          slot.stopped = true;
          if (slot.discarded) {
            slot.chunks = [];
            resolve(null);
            return;
          }
          const blob = new Blob(slot.chunks, { type: mimeType });
          slot.chunks = [];
          if (!blob || blob.size === 0) {
            resolve(null);
            return;
          }
          resolve({
            blob,
            mimeType,
            ext: extensionForMimeType(mimeType),
            durationSec
          });
        };
        const timer = setTimeout(finish, 2500);

        if (!slot.recorder || slot.recorder.state === 'inactive') {
          finish();
          return;
        }

        if (slot.recorder.addEventListener) {
          slot.recorder.addEventListener('stop', finish, { once: true });
        } else {
          const prevOnStop = slot.recorder.onstop;
          slot.recorder.onstop = (...args) => {
            if (typeof prevOnStop === 'function') {
              try { prevOnStop.apply(slot.recorder, args); } catch (_) {}
            }
            finish();
          };
        }

        try {
          slot.recorder.stop();
        } catch (_) {
          finish();
        }
      });
    }

    _rotateTick() {
      if (!this.running || this.saving) return;

      // Clean out any naturally stopped slots
      this.slots = this.slots.filter((s) => !s.discarded && s.recorder && s.recorder.state !== 'inactive');

      if (this.slots.length === 0) {
        this._startSlot();
        return;
      }

      this.slots.sort((a, b) => a.startedAt - b.startedAt);
      const outgoing = this.slots.shift();
      while (this.slots.length > 0) {
        this._discardSlot(this.slots.shift());
      }

      // Stop outgoing first so two MediaRecorder encoders are NEVER active concurrently
      this._pendingRotationPromise = this._stopSlotToClip(outgoing)
        .then((clip) => {
          if (this.running && clip) {
            this.completedArchive = {
              ...clip,
              completedAt: Date.now()
            };
          }
        })
        .catch(() => {})
        .finally(() => {
          this._pendingRotationPromise = null;
        });

      if (this.running) {
        this._startSlot();
      }
    }

    start() {
      if (this.running || !this.stream) return false;
      this.running = true;
      this.completedArchive = null;
      this._startSlot();
      this.rotationTimer = setInterval(() => {
        this._rotateTick();
      }, this.halfWindowMs);
      this.onStateChange({ active: true, saving: this.saving });
      return true;
    }

    stop() {
      this.running = false;
      if (this.rotationTimer) {
        clearInterval(this.rotationTimer);
        this.rotationTimer = null;
      }
      const existing = [...this.slots];
      for (const slot of existing) {
        this._discardSlot(slot);
      }
      this.slots = [];
      this.completedArchive = null;
      this._pendingRotationPromise = null;
      this.onStateChange({ active: false, saving: false });
    }

    async saveLatestClip() {
      if (!this.running || (this.slots.length === 0 && !this.completedArchive)) {
        throw new Error('Buffer de Replay não está ativo.');
      }
      if (this.saving) {
        throw new Error('Um clipe já está sendo finalizado.');
      }

      this.saving = true;
      this.onStateChange({ active: true, saving: true });

      try {
        if (this._pendingRotationPromise) {
          await this._pendingRotationPromise.catch(() => {});
        }

        const activeSlot =
          this.slots.find((s) => !s.discarded && s.recorder && s.recorder.state !== 'inactive') ||
          this.slots[0] ||
          null;
        const activeElapsedMs = activeSlot ? Date.now() - activeSlot.startedAt : 0;
        const minFreshMs = Math.max(8000, Math.round(this.halfWindowMs * 0.75));
        const hasFreshArchive =
          this.completedArchive &&
          this.completedArchive.blob &&
          this.completedArchive.blob.size > 0 &&
          Date.now() - (this.completedArchive.completedAt || 0) <= this.windowMs * 1.25;

        // If the current active slot just started after rotation, serve the completed frozen archive cleanly
        if (activeElapsedMs < minFreshMs && hasFreshArchive) {
          return {
            blob: this.completedArchive.blob,
            mimeType: this.completedArchive.mimeType,
            ext: this.completedArchive.ext,
            durationSec: this.completedArchive.durationSec
          };
        }

        if (!activeSlot) {
          if (hasFreshArchive) {
            return {
              blob: this.completedArchive.blob,
              mimeType: this.completedArchive.mimeType,
              ext: this.completedArchive.ext,
              durationSec: this.completedArchive.durationSec
            };
          }
          throw new Error('Nenhum segmento de vídeo gravado ainda.');
        }

        this.slots = this.slots.filter((s) => s !== activeSlot);
        const clip = await this._stopSlotToClip(activeSlot);

        if (this.running && this.slots.length === 0) {
          this._startSlot();
        }

        if (!clip || !clip.blob || clip.blob.size === 0) {
          if (hasFreshArchive) {
            return {
              blob: this.completedArchive.blob,
              mimeType: this.completedArchive.mimeType,
              ext: this.completedArchive.ext,
              durationSec: this.completedArchive.durationSec
            };
          }
          throw new Error('Nenhum quadro foi capturado ainda. Aguarde alguns segundos.');
        }

        return clip;
      } finally {
        this.saving = false;
        this.onStateChange({ active: this.running, saving: false });
      }
    }
  }

  return {
    SUPPORTED_MIME_CANDIDATES,
    pickPreferredRecorderMimeType,
    extensionForMimeType,
    formatCaptureTimestamp,
    formatFileSize,
    StaggeredReplayRecorder
  };
});
