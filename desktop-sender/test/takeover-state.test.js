const test = require('node:test');
const assert = require('node:assert/strict');
const {
  buildTakeoverBannerPayload,
  resolveSignalingEndAction,
  shouldOfferDirectTakeoverOnAction
} = require('../renderer/takeover-state.js');

const fireTv = { id: 'fire-1', name: 'Fire TV da Sala', ip: '192.168.0.2', port: 8765 };

test('resolveSignalingEndAction preserves Assumir TV banner immediately on preempted', () => {
  const action = resolveSignalingEndAction(
    {
      type: 'preempted',
      by: 'Galaxy A30s',
      message: 'Você transferiu a TV para Galaxy A30s.'
    },
    fireTv,
    null
  );

  assert.equal(action.kind, 'preempted');
  assert.equal(action.isError, false);
  assert.equal(action.banner.device.id, 'fire-1');
  assert.equal(action.banner.activeSender, 'Galaxy A30s');
  assert.match(action.banner.message, /Galaxy A30s/);
});

test('resolveSignalingEndAction handles status.state=preempted and status.state=busy uniformly', () => {
  const preemptStatus = resolveSignalingEndAction(
    { type: 'status', state: 'preempted', activeSender: 'Celular', message: '' },
    fireTv,
    null
  );
  assert.equal(preemptStatus.kind, 'preempted');
  assert.equal(preemptStatus.banner.activeSender, 'Celular');
  assert.match(preemptStatus.banner.message, /Assumir TV/);

  const busyStatus = resolveSignalingEndAction(
    { type: 'status', state: 'busy', activeSender: 'Galaxy A30s', message: '' },
    fireTv,
    null
  );
  assert.equal(busyStatus.kind, 'busy');
  assert.equal(busyStatus.isError, true);
  assert.equal(busyStatus.banner.activeSender, 'Galaxy A30s');
});

test('resolveSignalingEndAction recovers banner if WebSocket closes right after accepting takeover', () => {
  const fallback = resolveSignalingEndAction(
    null,
    fireTv,
    { device: fireTv, by: 'Galaxy A30s' }
  );
  assert.equal(fallback.kind, 'preempted');
  assert.equal(fallback.banner.activeSender, 'Galaxy A30s');
  assert.equal(fallback.banner.device.id, 'fire-1');
});

test('shouldOfferDirectTakeoverOnAction matches only selected busy TV', () => {
  assert.equal(shouldOfferDirectTakeoverOnAction(fireTv, { id: 'fire-1' }), true);
  assert.equal(shouldOfferDirectTakeoverOnAction(fireTv, { id: 'fire-2' }), false);
  assert.equal(shouldOfferDirectTakeoverOnAction(null, { id: 'fire-1' }), false);
  assert.equal(buildTakeoverBannerPayload(fireTv, '', '').activeSender, 'outro dispositivo');
});
