const test = require('node:test');
const assert = require('node:assert/strict');
const {
  pickPreferredRecorderMimeType,
  extensionForMimeType,
  formatCaptureTimestamp,
  formatFileSize,
  StaggeredReplayRecorder
} = require('../renderer/replay-buffer');

function makeFakeRecorderClass() {
  return class FakeMediaRecorder {
    constructor(stream, options = {}) {
      this.stream = stream;
      this.options = options;
      this.mimeType = options.mimeType || 'video/mp4';
      this.state = 'inactive';
      this.ondataavailable = null;
      this.onstop = null;
      this.onerror = null;
    }

    start() {
      this.state = 'recording';
      if (this.ondataavailable) {
        this.ondataavailable({ data: new Blob(['header+moof'], { type: this.mimeType }) });
      }
    }

    stop() {
      if (this.state === 'inactive') return;
      this.state = 'inactive';
      if (this.ondataavailable) {
        this.ondataavailable({ data: new Blob(['final-tail'], { type: this.mimeType }) });
      }
      if (this.onstop) this.onstop();
    }
  };
}

test('pickPreferredRecorderMimeType selects MP4 AVC1 ahead of WebM', () => {
  const chosen = pickPreferredRecorderMimeType(
    (mime) => mime === 'video/mp4;codecs=avc1.42E01E,mp4a.40.2' || mime === 'video/webm'
  );
  assert.equal(chosen, 'video/mp4;codecs=avc1.42E01E,mp4a.40.2');
  assert.equal(extensionForMimeType(chosen), 'mp4');
});

test('pickPreferredRecorderMimeType falls back cleanly to WebM when MP4 unavailable', () => {
  const chosen = pickPreferredRecorderMimeType((mime) => mime === 'video/webm;codecs=vp8,opus');
  assert.equal(chosen, 'video/webm;codecs=vp8,opus');
  assert.equal(extensionForMimeType(chosen), 'webm');
});

test('formatCaptureTimestamp formats deterministic filenames and human sizes', () => {
  const d = new Date(2026, 10, 13, 1, 5, 9);
  assert.equal(formatCaptureTimestamp(d), '2026-11-13_01-05-09');
  assert.equal(formatFileSize(512), '512 B');
  assert.equal(formatFileSize(2048), '2 KB');
  assert.equal(formatFileSize(5 * 1024 * 1024), '5.0 MB');
});

test('StaggeredReplayRecorder records, rotates oldest slot cleanly, and produces standalone clip Blob', async () => {
  const FakeRecorder = makeFakeRecorderClass();
  const fakeStream = { getAudioTracks: () => [{ id: 'a1' }] };

  const ring = new StaggeredReplayRecorder({
    stream: fakeStream,
    mimeType: 'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
    windowMs: 30000,
    createRecorder: (s, o) => new FakeRecorder(s, o)
  });

  assert.equal(ring.start(), true);
  assert.equal(ring.slots.length, 1);

  // Simulate half-window tick: stops outgoing slot into completedArchive and starts only 1 fresh active slot
  ring.slots[0].startedAt = Date.now() - 16000;
  ring._rotateTick();
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(ring.slots.length, 1);
  assert.ok(ring.completedArchive && ring.completedArchive.blob.size > 0);

  // Immediately extracting after rotation serves the archived >=15s clip and keeps 1 active slot running
  const clipFromArchive = await ring.saveLatestClip();
  assert.equal(clipFromArchive.ext, 'mp4');
  assert.ok(clipFromArchive.blob.size > 0);
  assert.ok(clipFromArchive.durationSec >= 15);
  assert.equal(ring.slots.length, 1);

  // Simulate active slot growing >=12s: extracting stops active slot and replaces it with 1 fresh slot
  ring.slots[0].startedAt = Date.now() - 13000;
  const clipFromActive = await ring.saveLatestClip();
  assert.equal(clipFromActive.ext, 'mp4');
  assert.ok(clipFromActive.blob.size > 0);
  assert.ok(clipFromActive.durationSec >= 12);
  assert.equal(ring.slots.length, 1);

  ring.stop();
  assert.equal(ring.slots.length, 0);
  assert.equal(ring.completedArchive, null);
});
