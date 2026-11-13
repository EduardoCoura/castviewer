'use strict';

/**
 * Pure-JS ISO BMFF (fMP4 -> Progressive faststart MP4) remuxer.
 *
 * Chromium's MediaRecorder outputs fragmented MP4 (ftyp + moov/mvex + moof/mdat...),
 * which omits total duration and can have slight inter-fragment tfdt rounding gaps
 * that cause Windows MediaFoundation (Windows Media Player, Photos, Movies & TV,
 * desktop social apps) to fail or report truncated audio/video streams.
 *
 * Repacking those already-encoded H.264/AAC samples into standard progressive MP4
 * (ftyp + moov + contiguous mdat with stts/stss/stsc/stsz/stco) takes a few
 * milliseconds with zero external binaries or re-encoding.
 */

function makeBox(type, ...parts) {
  const payload = parts.length === 1 ? parts[0] : Buffer.concat(parts);
  const hdr = Buffer.allocUnsafe(8);
  hdr.writeUInt32BE(8 + payload.length, 0);
  hdr.write(type, 4, 4, 'ascii');
  return Buffer.concat([hdr, payload]);
}

function makeFullBox(type, version, flags, ...parts) {
  const vf = Buffer.allocUnsafe(4);
  vf[0] = version & 0xff;
  vf[1] = (flags >> 16) & 0xff;
  vf[2] = (flags >> 8) & 0xff;
  vf[3] = flags & 0xff;
  return makeBox(type, vf, ...parts);
}

function iterBoxes(buf, start, end) {
  const out = [];
  let off = start;
  while (off + 8 <= end) {
    let size = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    let hdr = 8;
    if (size === 1) {
      if (off + 16 > end) break;
      size = Number(buf.readBigUInt64BE(off + 8));
      hdr = 16;
    } else if (size === 0) {
      size = end - off;
    }
    if (size < hdr || off + size > end) break;
    out.push({ type, start: off, hdr, size, end: off + size });
    off += size;
  }
  return out;
}

function isFragmentedMp4(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 32) return false;
  return iterBoxes(buf, 0, buf.length).some((b) => b.type === 'moof');
}

function writeBoxDuration(rawBox, versionByteOffset, durationTicks, v0Offset, v1Offset) {
  const out = Buffer.from(rawBox);
  const ver = out[versionByteOffset];
  if (ver === 1) {
    out.writeBigUInt64BE(BigInt(Math.max(0, Math.round(durationTicks))), v1Offset);
  } else {
    out.writeUInt32BE(Math.max(0, Math.round(durationTicks)) >>> 0, v0Offset);
  }
  return out;
}

function parseTimescale(boxBuf) {
  const ver = boxBuf[8];
  return ver === 1 ? boxBuf.readUInt32BE(8 + 20) : boxBuf.readUInt32BE(8 + 12);
}

function parseTkhdTrackId(boxBuf) {
  const ver = boxBuf[8];
  return ver === 1 ? boxBuf.readUInt32BE(8 + 20) : boxBuf.readUInt32BE(8 + 12);
}

function buildStts(samples) {
  const entries = [];
  for (const s of samples) {
    const dur = Math.max(0, s.duration) >>> 0;
    if (entries.length > 0 && entries[entries.length - 1].dur === dur) {
      entries[entries.length - 1].count++;
    } else {
      entries.push({ count: 1, dur });
    }
  }
  const payload = Buffer.allocUnsafe(4 + entries.length * 8);
  payload.writeUInt32BE(entries.length, 0);
  for (let i = 0; i < entries.length; i++) {
    payload.writeUInt32BE(entries[i].count, 4 + i * 8);
    payload.writeUInt32BE(entries[i].dur, 8 + i * 8);
  }
  return makeFullBox('stts', 0, 0, payload);
}

function buildCttsIfNeeded(samples) {
  if (!samples.some((s) => s.cto !== 0)) return null;
  const hasNeg = samples.some((s) => s.cto < 0);
  const entries = [];
  for (const s of samples) {
    if (entries.length > 0 && entries[entries.length - 1].cto === s.cto) {
      entries[entries.length - 1].count++;
    } else {
      entries.push({ count: 1, cto: s.cto });
    }
  }
  const payload = Buffer.allocUnsafe(4 + entries.length * 8);
  payload.writeUInt32BE(entries.length, 0);
  for (let i = 0; i < entries.length; i++) {
    payload.writeUInt32BE(entries[i].count, 4 + i * 8);
    if (hasNeg) payload.writeInt32BE(entries[i].cto, 8 + i * 8);
    else payload.writeUInt32BE(entries[i].cto >>> 0, 8 + i * 8);
  }
  return makeFullBox('ctts', hasNeg ? 1 : 0, 0, payload);
}

function buildStssIfNeeded(samples, handlerType) {
  if (handlerType !== 'vide') return null;
  const syncNums = [];
  for (let i = 0; i < samples.length; i++) {
    if (samples[i].isSync) syncNums.push(i + 1);
  }
  if (syncNums.length === 0 || syncNums.length === samples.length) return null;
  const payload = Buffer.allocUnsafe(4 + syncNums.length * 4);
  payload.writeUInt32BE(syncNums.length, 0);
  for (let i = 0; i < syncNums.length; i++) {
    payload.writeUInt32BE(syncNums[i], 4 + i * 4);
  }
  return makeFullBox('stss', 0, 0, payload);
}

function buildStsc(chunks) {
  const entries = [];
  for (let i = 0; i < chunks.length; i++) {
    const c = chunks[i];
    const prev = entries[entries.length - 1];
    if (!prev || prev.spc !== c.sampleCount || prev.sdi !== c.sampleDescIdx) {
      entries.push({ firstChunk: i + 1, spc: c.sampleCount, sdi: c.sampleDescIdx });
    }
  }
  const payload = Buffer.allocUnsafe(4 + entries.length * 12);
  payload.writeUInt32BE(entries.length, 0);
  for (let i = 0; i < entries.length; i++) {
    payload.writeUInt32BE(entries[i].firstChunk, 4 + i * 12);
    payload.writeUInt32BE(entries[i].spc, 8 + i * 12);
    payload.writeUInt32BE(entries[i].sdi, 12 + i * 12);
  }
  return makeFullBox('stsc', 0, 0, payload);
}

function buildStsz(samples) {
  const payload = Buffer.allocUnsafe(8 + samples.length * 4);
  payload.writeUInt32BE(0, 0);
  payload.writeUInt32BE(samples.length, 4);
  for (let i = 0; i < samples.length; i++) {
    payload.writeUInt32BE(samples[i].size, 8 + i * 4);
  }
  return makeFullBox('stsz', 0, 0, payload);
}

function buildStco(chunks, mdatDataOffset) {
  const payload = Buffer.allocUnsafe(4 + chunks.length * 4);
  payload.writeUInt32BE(chunks.length, 0);
  for (let i = 0; i < chunks.length; i++) {
    payload.writeUInt32BE((mdatDataOffset + chunks[i].relOffset) >>> 0, 4 + i * 4);
  }
  return makeFullBox('stco', 0, 0, payload);
}

function defragmentMp4Buffer(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 32) return buf;
  const top = iterBoxes(buf, 0, buf.length);
  const moofEntries = [];
  for (let i = 0; i < top.length; i++) {
    if (top[i].type === 'moof') {
      const pairedMdat = top[i + 1] && top[i + 1].type === 'mdat' ? top[i + 1] : null;
      moofEntries.push({ moof: top[i], pairedMdat });
    }
  }
  if (moofEntries.length === 0) return buf;

  const moovBox = top.find((b) => b.type === 'moov');
  if (!moovBox) return buf;

  const outFtyp = makeBox('ftyp', Buffer.from('isom\x00\x00\x02\x00isomiso2avc1mp41', 'latin1'));

  const moovChildren = iterBoxes(buf, moovBox.start + moovBox.hdr, moovBox.end);
  const mvhdRawBox = moovChildren.find((b) => b.type === 'mvhd');
  if (!mvhdRawBox) return buf;
  const mvhdBuf = buf.subarray(mvhdRawBox.start, mvhdRawBox.end);
  const mvhdTimescale = parseTimescale(mvhdBuf) || 1000;

  const trexMap = new Map();
  const mvexBox = moovChildren.find((b) => b.type === 'mvex');
  if (mvexBox) {
    for (const c of iterBoxes(buf, mvexBox.start + mvexBox.hdr, mvexBox.end)) {
      if (c.type === 'trex' && c.size >= 32) {
        const p = c.start + c.hdr;
        trexMap.set(buf.readUInt32BE(p + 4), {
          sampleDescIdx: buf.readUInt32BE(p + 8) || 1,
          defaultDur: buf.readUInt32BE(p + 12),
          defaultSize: buf.readUInt32BE(p + 16),
          defaultFlags: buf.readUInt32BE(p + 20)
        });
      }
    }
  }

  const tracks = new Map();
  const otherMoovBoxes = [];
  for (const b of moovChildren) {
    if (b.type === 'mvhd' || b.type === 'mvex') continue;
    if (b.type !== 'trak') {
      otherMoovBoxes.push(Buffer.from(buf.subarray(b.start, b.end)));
      continue;
    }
    const trakChildren = iterBoxes(buf, b.start + b.hdr, b.end);
    const tkhdB = trakChildren.find((c) => c.type === 'tkhd');
    const mdiaB = trakChildren.find((c) => c.type === 'mdia');
    if (!tkhdB || !mdiaB) continue;
    const tkhdRaw = Buffer.from(buf.subarray(tkhdB.start, tkhdB.end));
    const trackId = parseTkhdTrackId(tkhdRaw);
    const mdiaChildren = iterBoxes(buf, mdiaB.start + mdiaB.hdr, mdiaB.end);
    const mdhdB = mdiaChildren.find((c) => c.type === 'mdhd');
    const hdlrB = mdiaChildren.find((c) => c.type === 'hdlr');
    const minfB = mdiaChildren.find((c) => c.type === 'minf');
    if (!mdhdB || !hdlrB || !minfB) continue;
    const mdhdRaw = Buffer.from(buf.subarray(mdhdB.start, mdhdB.end));
    const mdhdTimescale = parseTimescale(mdhdRaw) || 1000;
    const hdlrRaw = Buffer.from(buf.subarray(hdlrB.start, hdlrB.end));
    const handlerType = buf.toString('ascii', hdlrB.start + hdlrB.hdr + 8, hdlrB.start + hdlrB.hdr + 12);

    const minfChildren = iterBoxes(buf, minfB.start + minfB.hdr, minfB.end);
    const stblB = minfChildren.find((c) => c.type === 'stbl');
    if (!stblB) continue;
    const stblChildren = iterBoxes(buf, stblB.start + stblB.hdr, stblB.end);
    const stsdB = stblChildren.find((c) => c.type === 'stsd');
    if (!stsdB) continue;
    const stsdRaw = Buffer.from(buf.subarray(stsdB.start, stsdB.end));

    const otherTrak = trakChildren
      .filter((c) => c.type !== 'tkhd' && c.type !== 'mdia')
      .map((c) => Buffer.from(buf.subarray(c.start, c.end)));
    const otherMdia = mdiaChildren
      .filter((c) => c.type !== 'mdhd' && c.type !== 'hdlr' && c.type !== 'minf')
      .map((c) => Buffer.from(buf.subarray(c.start, c.end)));
    const otherMinf = minfChildren
      .filter((c) => c.type !== 'stbl')
      .map((c) => Buffer.from(buf.subarray(c.start, c.end)));

    tracks.set(trackId, {
      trackId,
      tkhdRaw,
      mdhdRaw,
      mdhdTimescale,
      hdlrRaw,
      handlerType,
      stsdRaw,
      otherTrak,
      otherMdia,
      otherMinf,
      samples: [],
      chunks: [],
      baseTfdt: null,
      cumDecodeTime: 0
    });
  }

  const mdatSlices = [];
  let mdatRelOffset = 0;

  for (const { moof: moofB, pairedMdat } of moofEntries) {
    const moofStart = moofB.start;
    let implicitTrafCursor = pairedMdat ? pairedMdat.start + pairedMdat.hdr : moofStart;

    for (const child of iterBoxes(buf, moofB.start + moofB.hdr, moofB.end)) {
      if (child.type !== 'traf') continue;
      const trafChildren = iterBoxes(buf, child.start + child.hdr, child.end);
      const tfhdB = trafChildren.find((c) => c.type === 'tfhd');
      if (!tfhdB) continue;

      let p = tfhdB.start + tfhdB.hdr;
      const tfhdFlags = buf.readUInt32BE(p) & 0xffffff;
      const trackId = buf.readUInt32BE(p + 4);
      p += 8;
      const trex = trexMap.get(trackId) || {
        sampleDescIdx: 1,
        defaultDur: 0,
        defaultSize: 0,
        defaultFlags: 0
      };

      let hasBaseDataOffset = false;
      let baseDataOffset = moofStart;
      if (tfhdFlags & 0x000001) {
        hasBaseDataOffset = true;
        baseDataOffset = Number(buf.readBigUInt64BE(p));
        p += 8;
      }
      const sampleDescIdx = (tfhdFlags & 0x000002) ? (p += 4, buf.readUInt32BE(p - 4)) : trex.sampleDescIdx;
      const defaultDur = (tfhdFlags & 0x000008) ? (p += 4, buf.readUInt32BE(p - 4)) : trex.defaultDur;
      const defaultSize = (tfhdFlags & 0x000010) ? (p += 4, buf.readUInt32BE(p - 4)) : trex.defaultSize;
      const defaultFlags = (tfhdFlags & 0x000020) ? (p += 4, buf.readUInt32BE(p - 4)) : trex.defaultFlags;
      const defaultBaseIsMoof = (tfhdFlags & 0x020000) !== 0;

      const track = tracks.get(trackId);
      if (!track) continue;

      const tfdtB = trafChildren.find((c) => c.type === 'tfdt');
      let tfdtTime = null;
      if (tfdtB) {
        const tp = tfdtB.start + tfdtB.hdr;
        const ver = buf[tp];
        tfdtTime = ver === 1 ? Number(buf.readBigUInt64BE(tp + 4)) : buf.readUInt32BE(tp + 4);
      }

      if (tfdtTime !== null) {
        if (track.baseTfdt === null) {
          track.baseTfdt = tfdtTime;
        }
        const relTfdt = tfdtTime - track.baseTfdt;
        if (track.samples.length > 0 && relTfdt > track.cumDecodeTime) {
          const gap = relTfdt - track.cumDecodeTime;
          if (gap > 0 && gap < track.mdhdTimescale * 2) {
            track.samples[track.samples.length - 1].duration += gap;
            track.cumDecodeTime += gap;
          }
        }
      }

      let cursorPos = hasBaseDataOffset
        ? baseDataOffset
        : defaultBaseIsMoof
          ? moofStart
          : implicitTrafCursor;

      for (const trunB of trafChildren.filter((c) => c.type === 'trun')) {
        const trunVer = buf[trunB.start + trunB.hdr];
        let q = trunB.start + trunB.hdr;
        const tFlags = buf.readUInt32BE(q) & 0xffffff;
        const sampleCount = buf.readUInt32BE(q + 4);
        q += 8;

        if (tFlags & 0x000001) {
          const dataOff = buf.readInt32BE(q);
          q += 4;
          cursorPos = (hasBaseDataOffset ? baseDataOffset : moofStart) + dataOff;
        }
        let firstSampleFlags = defaultFlags;
        if (tFlags & 0x000004) {
          firstSampleFlags = buf.readUInt32BE(q);
          q += 4;
        }
        if (sampleCount === 0) continue;

        const chunkRelStart = mdatRelOffset;
        let chunkSampleCount = 0;

        for (let i = 0; i < sampleCount; i++) {
          const dur = (tFlags & 0x000100) ? (q += 4, buf.readUInt32BE(q - 4)) : defaultDur;
          const sz = (tFlags & 0x000200) ? (q += 4, buf.readUInt32BE(q - 4)) : defaultSize;
          const sFlags = (tFlags & 0x000400)
            ? (q += 4, buf.readUInt32BE(q - 4))
            : (i === 0 && (tFlags & 0x000004) ? firstSampleFlags : defaultFlags);
          const cto = (tFlags & 0x000800)
            ? (q += 4, trunVer === 1 ? buf.readInt32BE(q - 4) : buf.readUInt32BE(q - 4))
            : 0;

          if (cursorPos < 0 || cursorPos + sz > buf.length) {
            throw new Error(`Sample out of range at ${cursorPos}+${sz}`);
          }
          mdatSlices.push(buf.subarray(cursorPos, cursorPos + sz));
          mdatRelOffset += sz;
          cursorPos += sz;

          const nonSync = (sFlags & 0x00010000) !== 0;
          const dep = (sFlags >> 24) & 0x03;
          const isSync = !nonSync && dep !== 1;
          track.samples.push({ duration: dur, size: sz, cto, isSync });
          track.cumDecodeTime += dur;
          chunkSampleCount++;
        }

        if (chunkSampleCount > 0) {
          track.chunks.push({
            relOffset: chunkRelStart,
            sampleCount: chunkSampleCount,
            sampleDescIdx: sampleDescIdx || 1
          });
        }
        implicitTrafCursor = cursorPos;
      }
    }
  }

  let totalSamples = 0;
  for (const t of tracks.values()) totalSamples += t.samples.length;
  if (totalSamples === 0) return buf;

  const buildMoov = (mdatDataOffset) => {
    let maxMvhdDur = 0;
    const trakBoxes = [];
    for (const t of tracks.values()) {
      if (t.samples.length === 0) continue;
      const trackDurMdhd = t.cumDecodeTime;
      const trackDurMvhd = Math.round((trackDurMdhd * mvhdTimescale) / t.mdhdTimescale);
      if (trackDurMvhd > maxMvhdDur) maxMvhdDur = trackDurMvhd;

      const tkhd = writeBoxDuration(t.tkhdRaw, 8, trackDurMvhd, 28, 36);
      const mdhd = writeBoxDuration(t.mdhdRaw, 8, trackDurMdhd, 24, 32);
      const stblParts = [t.stsdRaw, buildStts(t.samples)];
      const ctts = buildCttsIfNeeded(t.samples);
      if (ctts) stblParts.push(ctts);
      const stss = buildStssIfNeeded(t.samples, t.handlerType);
      if (stss) stblParts.push(stss);
      stblParts.push(
        buildStsc(t.chunks),
        buildStsz(t.samples),
        buildStco(t.chunks, mdatDataOffset)
      );
      const stbl = makeBox('stbl', ...stblParts);
      const minf = makeBox('minf', ...t.otherMinf, stbl);
      const mdia = makeBox('mdia', mdhd, t.hdlrRaw, ...t.otherMdia, minf);
      const trak = makeBox('trak', tkhd, ...t.otherTrak, mdia);
      trakBoxes.push(trak);
    }
    const mvhd = writeBoxDuration(mvhdBuf, 8, maxMvhdDur, 24, 32);
    return makeBox('moov', mvhd, ...trakBoxes, ...otherMoovBoxes);
  };

  const dummyMoov = buildMoov(0);
  const mdatDataStart = outFtyp.length + dummyMoov.length + 8;
  const finalMoov = buildMoov(mdatDataStart);
  const mdatHdr = Buffer.allocUnsafe(8);
  mdatHdr.writeUInt32BE(8 + mdatRelOffset, 0);
  mdatHdr.write('mdat', 4, 4, 'ascii');
  return Buffer.concat([outFtyp, finalMoov, mdatHdr, ...mdatSlices]);
}

function remuxReplayBufferToStandardMp4(buf, mimeType) {
  if (!Buffer.isBuffer(buf) || buf.length < 32) return buf;
  if (typeof mimeType === 'string' && !mimeType.includes('mp4')) return buf;
  if (!isFragmentedMp4(buf)) return buf;
  try {
    return defragmentMp4Buffer(buf);
  } catch (_) {
    return buf;
  }
}

module.exports = {
  defragmentMp4Buffer,
  isFragmentedMp4,
  remuxReplayBufferToStandardMp4
};
