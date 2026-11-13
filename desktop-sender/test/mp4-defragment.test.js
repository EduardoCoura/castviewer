const test = require('node:test');
const assert = require('node:assert/strict');
const {
  defragmentMp4Buffer,
  isFragmentedMp4,
  remuxReplayBufferToStandardMp4
} = require('../mp4-defragment');

function box(type, ...parts) {
  const payload = parts.length === 1 ? parts[0] : Buffer.concat(parts);
  const hdr = Buffer.alloc(8);
  hdr.writeUInt32BE(8 + payload.length, 0);
  hdr.write(type, 4, 4, 'ascii');
  return Buffer.concat([hdr, payload]);
}

function fullBox(type, ver, flags, payload) {
  const vf = Buffer.alloc(4);
  vf[0] = ver;
  vf[1] = (flags >> 16) & 0xff;
  vf[2] = (flags >> 8) & 0xff;
  vf[3] = flags & 0xff;
  return box(type, vf, payload);
}

function buildSyntheticFmp4() {
  const ftyp = box('ftyp', Buffer.from('iso5\x00\x00\x02\x00iso5iso6mp41', 'latin1'));
  const mvhdPayload = Buffer.alloc(108);
  mvhdPayload.writeUInt32BE(1000, 16); // ver 1 timescale @ offset 16 after vf
  const mvhd = fullBox('mvhd', 1, 0, mvhdPayload);

  const mkTrack = (id, timescale, handler) => {
    const tkhdP = Buffer.alloc(92);
    tkhdP.writeUInt32BE(id, 16);
    const tkhd = fullBox('tkhd', 1, 3, tkhdP);
    const mdhdP = Buffer.alloc(32);
    mdhdP.writeUInt32BE(timescale, 16);
    const mdhd = fullBox('mdhd', 1, 0, mdhdP);
    const hdlrP = Buffer.alloc(24);
    hdlrP.write(handler, 4, 4, 'ascii');
    const hdlr = fullBox('hdlr', 0, 0, hdlrP);
    const stsd = fullBox('stsd', 0, 0, Buffer.from([0, 0, 0, 1, 0, 0, 0, 8, 116, 101, 115, 116]));
    const empty = fullBox('stsz', 0, 0, Buffer.alloc(8));
    const stbl = box('stbl', stsd, empty);
    const minf = box('minf', fullBox(handler === 'soun' ? 'smhd' : 'vmhd', 0, 0, Buffer.alloc(4)), stbl);
    return box('trak', tkhd, box('mdia', mdhd, hdlr, minf));
  };

  const trexAudio = fullBox('trex', 0, 0, (() => { const b = Buffer.alloc(20); b.writeUInt32BE(1, 0); return b; })());
  const trexVideo = fullBox('trex', 0, 0, (() => { const b = Buffer.alloc(20); b.writeUInt32BE(2, 0); return b; })());
  const moov = box('moov', mvhd, mkTrack(1, 48000, 'soun'), mkTrack(2, 120000, 'vide'), box('mvex', trexAudio, trexVideo));

  const aSample = Buffer.from([0xaa, 0xbb, 0xcc, 0xdd]);
  const vKey = Buffer.from([0x11, 0x22, 0x33, 0x44, 0x55]);
  const vDelta = Buffer.from([0x66, 0x77, 0x88]);

  const buildFragment = (tfdtAudio, tfdtVideo) => {
    const mdatPayload = Buffer.concat([aSample, vKey, vDelta]);
    const mdat = box('mdat', mdatPayload);

    const buildTraf = (trackId, tfdtVal, samples, relStartInMdat) => {
      const tfhdP = Buffer.alloc(4);
      tfhdP.writeUInt32BE(trackId, 0);
      const tfhd = fullBox('tfhd', 0, 0x020000, tfhdP);
      const tfdtP = Buffer.alloc(8);
      tfdtP.writeBigUInt64BE(BigInt(tfdtVal), 0);
      const tfdt = fullBox('tfdt', 1, 0, tfdtP);
      const trunP = Buffer.alloc(8 + samples.length * 12);
      trunP.writeUInt32BE(samples.length, 0);
      trunP.writeInt32BE(relStartInMdat, 4); // patched below
      let off = 8;
      for (const s of samples) {
        trunP.writeUInt32BE(s.dur, off);
        trunP.writeUInt32BE(s.buf.length, off + 4);
        trunP.writeUInt32BE(s.flags, off + 8);
        off += 12;
      }
      const trun = fullBox('trun', 0, 0x701, trunP);
      return { traf: box('traf', tfhd, tfdt, trun), patchRel: relStartInMdat };
    };

    const mfhd = fullBox('mfhd', 0, 0, Buffer.from([0, 0, 0, 1]));
    const dummyA = buildTraf(1, tfdtAudio, [{ dur: 1024, buf: aSample, flags: 0x02000000 }], 0);
    const dummyV = buildTraf(2, tfdtVideo, [{ dur: 4000, buf: vKey, flags: 0x02000000 }, { dur: 4000, buf: vDelta, flags: 0x01010000 }], aSample.length);
    const moofLen = box('moof', mfhd, dummyA.traf, dummyV.traf).length;
    const realA = buildTraf(1, tfdtAudio, [{ dur: 1024, buf: aSample, flags: 0x02000000 }], moofLen + 8);
    const realV = buildTraf(2, tfdtVideo, [{ dur: 4000, buf: vKey, flags: 0x02000000 }, { dur: 4000, buf: vDelta, flags: 0x01010000 }], moofLen + 8 + aSample.length);
    return Buffer.concat([box('moof', mfhd, realA.traf, realV.traf), mdat]);
  };

  // Start fragments at non-zero tfdt (simulating ring rotation!) with slight tfdt gap
  const frag1 = buildFragment(48000, 120000);
  const frag2 = buildFragment(48000 + 1024 + 26, 120000 + 8000 + 100);
  return Buffer.concat([ftyp, moov, frag1, frag2]);
}

test('defragmentMp4Buffer removes moof/mvex and builds progressive stbl with accurate duration', () => {
  const fmp4 = buildSyntheticFmp4();
  assert.equal(isFragmentedMp4(fmp4), true);

  const clean = remuxReplayBufferToStandardMp4(fmp4, 'video/mp4;codecs=avc1.42E01E,mp4a.40.2');
  assert.equal(isFragmentedMp4(clean), false);
  assert.ok(!clean.includes(Buffer.from('mvex')));
  assert.ok(!clean.includes(Buffer.from('moof')));
  assert.ok(clean.includes(Buffer.from('stss')));
  assert.ok(clean.includes(Buffer.from('stco')));
  assert.ok(clean.includes(Buffer.from('stsz')));
  // mvhd is inside moov right after ftyp(24 bytes); verify non-zero total duration written
  const ftypSize = clean.readUInt32BE(0);
  const mvhdOffset = ftypSize + 8;
  const mvhdDuration = Number(clean.readBigUInt64BE(mvhdOffset + 8 + 24));
  assert.ok(mvhdDuration > 0, `expected non-zero mvhd duration, got ${mvhdDuration}`);
});

test('remuxReplayBufferToStandardMp4 passes webm buffers untouched', () => {
  const webm = Buffer.from([0x1a, 0x45, 0xdf, 0xa3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  assert.equal(remuxReplayBufferToStandardMp4(webm, 'video/webm;codecs=vp8,opus'), webm);
});
