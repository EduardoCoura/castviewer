/**
 * CastViewer - AudioWorkletProcessor for PCM Loopback Audio
 * Glitch-free stereo 48kHz ring buffer with pre-buffering (priming),
 * smooth crossfade drift control, and click-free underflow decay.
 */

class PcmLoopbackProcessor extends AudioWorkletProcessor {
  constructor() {
    super();

    // 48kHz sample rate -> 48 stereo frames per ms
    this.sampleRate = 48000;

    // Default to ultra-low-latency buffering tuned for 10ms WASAPI IPC chunks
    this.applyLatencyConfig('ultra');

    // Ring buffer size: 48000 frames (1 second stereo)
    this.bufferCapacity = 48000;
    this.leftBuffer = new Float32Array(this.bufferCapacity);
    this.rightBuffer = new Float32Array(this.bufferCapacity);
    this.writeIndex = 0;
    this.readIndex = 0;
    this.bufferedFrames = 0;
    this.isPrimed = false;
    this.lastLeft = 0.0;
    this.lastRight = 0.0;

    this.port.onmessage = (event) => {
      const msg = event.data;
      if (msg && msg.type === 'config') {
        this.applyLatencyConfig(msg.latencyMode);
        return;
      }
      this.handleChunk(msg);
    };
  }

  applyLatencyConfig(latencyMode) {
    if (latencyMode === 'balanced') {
      this.primeThresholdFrames = 1920; // 40ms
      this.targetBufferedFrames = 2400; // 50ms
      this.maxBufferedFrames = 4800;    // 100ms ceiling before gentle drift trim
    } else {
      this.primeThresholdFrames = 960;  // 20ms
      this.targetBufferedFrames = 1200; // 25ms target after drift trim
      this.maxBufferedFrames = 2880;    // 60ms ceiling before gentle drift trim
    }
  }

  handleChunk(data) {
    if (!data) return;

    let int16Data;
    if (data instanceof Int16Array) {
      int16Data = data;
    } else if (data instanceof ArrayBuffer) {
      int16Data = new Int16Array(data);
    } else if (data.buffer instanceof ArrayBuffer) {
      int16Data = new Int16Array(
        data.buffer,
        data.byteOffset || 0,
        Math.floor((data.byteLength || data.length * 2) / 2)
      );
    } else {
      return;
    }

    const frameCount = Math.floor(int16Data.length / 2);
    if (frameCount === 0) return;

    // If buffer exceeds 200ms ceiling, trim down to 80ms target with 64-sample crossfade
    if (this.bufferedFrames + frameCount > this.maxBufferedFrames) {
      const excess = (this.bufferedFrames + frameCount) - this.targetBufferedFrames;
      if (excess > 0 && this.bufferedFrames > excess + 64) {
        const oldRead = this.readIndex;
        const newRead = (this.readIndex + excess) % this.bufferCapacity;
        // Smooth 64-sample crossfade across the splice to prevent any click
        for (let k = 0; k < 64; k++) {
          const alpha = k / 64.0;
          const idxOld = (oldRead + k) % this.bufferCapacity;
          const idxNew = (newRead + k) % this.bufferCapacity;
          this.leftBuffer[idxNew] =
            this.leftBuffer[idxOld] * (1.0 - alpha) + this.leftBuffer[idxNew] * alpha;
          this.rightBuffer[idxNew] =
            this.rightBuffer[idxOld] * (1.0 - alpha) + this.rightBuffer[idxNew] * alpha;
        }
        this.readIndex = newRead;
        this.bufferedFrames -= excess;
      }
    }

    // Write new stereo frames into ring buffer
    for (let i = 0; i < frameCount; i++) {
      this.leftBuffer[this.writeIndex] = int16Data[i * 2] / 32768.0;
      this.rightBuffer[this.writeIndex] = int16Data[i * 2 + 1] / 32768.0;
      this.writeIndex = (this.writeIndex + 1) % this.bufferCapacity;
    }

    this.bufferedFrames += frameCount;
    if (!this.isPrimed && this.bufferedFrames >= this.primeThresholdFrames) {
      this.isPrimed = true;
    }
  }

  process(inputs, outputs) {
    const output = outputs[0];
    if (!output || output.length === 0) return true;

    const left = output[0];
    const right = output.length > 1 ? output[1] : output[0];
    const renderFrames = left.length; // 128 frames (2.67ms)

    if (!this.isPrimed) {
      for (let i = 0; i < renderFrames; i++) {
        this.lastLeft *= 0.95;
        this.lastRight *= 0.95;
        left[i] = this.lastLeft;
        right[i] = this.lastRight;
      }
      return true;
    }

    if (this.bufferedFrames >= renderFrames) {
      for (let i = 0; i < renderFrames; i++) {
        const l = this.leftBuffer[this.readIndex];
        const r = this.rightBuffer[this.readIndex];
        left[i] = l;
        right[i] = r;
        this.lastLeft = l;
        this.lastRight = r;
        this.readIndex = (this.readIndex + 1) % this.bufferCapacity;
      }
      this.bufferedFrames -= renderFrames;
    } else {
      // Rare underflow: drain remaining frames then smoothly decay to zero (no pop!)
      const available = this.bufferedFrames;
      for (let i = 0; i < available; i++) {
        const l = this.leftBuffer[this.readIndex];
        const r = this.rightBuffer[this.readIndex];
        left[i] = l;
        right[i] = r;
        this.lastLeft = l;
        this.lastRight = r;
        this.readIndex = (this.readIndex + 1) % this.bufferCapacity;
      }
      for (let i = available; i < renderFrames; i++) {
        this.lastLeft *= 0.95;
        this.lastRight *= 0.95;
        left[i] = this.lastLeft;
        right[i] = this.lastRight;
      }
      this.bufferedFrames = 0;
      this.isPrimed = false; // Re-prime to 60ms before resuming
    }

    return true;
  }
}

registerProcessor('pcm-loopback-processor', PcmLoopbackProcessor);
