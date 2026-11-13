(function initTakeoverStateModule(rootFactory) {
  const api = rootFactory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  }
  if (typeof window !== 'undefined') {
    window.CastTakeoverState = api;
  }
})(() => {
  function normalizeSenderName(activeSender) {
    const clean = typeof activeSender === 'string' ? activeSender.trim() : '';
    return clean || 'outro dispositivo';
  }

  function buildTakeoverBannerPayload(device, activeSender, detailMessage) {
    const owner = normalizeSenderName(activeSender);
    const devName = device && device.name ? device.name : 'Fire TV';
    const cleanDetail = typeof detailMessage === 'string' ? detailMessage.trim() : '';
    return {
      device: device || null,
      activeSender: owner,
      message:
        cleanDetail ||
        `${owner} está usando ${devName}. Clique em "Assumir TV" quando quiser voltar para este PC.`
    };
  }

  function resolveSignalingEndAction(msg, device, acceptedOutgoingTakeoverTarget) {
    if (msg && msg.type === 'preempted') {
      const owner = normalizeSenderName(msg.by || msg.activeSender);
      const rawMsg = typeof msg.message === 'string' ? msg.message.trim() : '';
      return {
        kind: 'preempted',
        isError: false,
        banner: buildTakeoverBannerPayload(
          device,
          owner,
          rawMsg || `${owner} assumiu ${device && device.name ? device.name : 'a TV'}. Clique em "Assumir TV" para retomar no PC.`
        ),
        toastMessage: rawMsg || `${owner} assumiu a TV.`
      };
    }

    if (msg && msg.type === 'status' && msg.state === 'preempted') {
      const owner = normalizeSenderName(msg.activeSender || msg.by);
      const rawMsg = typeof msg.message === 'string' ? msg.message.trim() : '';
      return {
        kind: 'preempted',
        isError: false,
        banner: buildTakeoverBannerPayload(
          device,
          owner,
          rawMsg || `${owner} assumiu ${device && device.name ? device.name : 'a TV'}. Clique em "Assumir TV" para retomar no PC.`
        ),
        toastMessage: rawMsg || `${owner} assumiu a TV.`
      };
    }

    if (msg && msg.type === 'status' && msg.state === 'busy') {
      const owner = normalizeSenderName(msg.activeSender);
      const rawMsg = typeof msg.message === 'string' ? msg.message.trim() : '';
      return {
        kind: 'busy',
        isError: true,
        banner: buildTakeoverBannerPayload(device, owner, rawMsg),
        toastMessage: rawMsg || `TV já conectada em outro dispositivo (${owner}).`
      };
    }

    if (msg && msg.type === 'status' && msg.state === 'takeover-denied') {
      const owner = normalizeSenderName(msg.activeSender);
      const rawMsg = typeof msg.message === 'string' ? msg.message.trim() : '';
      const deniedMsg = rawMsg || `${owner} recusou transferir a TV.`;
      return {
        kind: 'denied',
        isError: true,
        banner: buildTakeoverBannerPayload(device, owner, deniedMsg),
        toastMessage: deniedMsg
      };
    }

    if (!msg && acceptedOutgoingTakeoverTarget) {
      const owner = normalizeSenderName(acceptedOutgoingTakeoverTarget.by);
      const targetDev = acceptedOutgoingTakeoverTarget.device || device;
      const devName = targetDev && targetDev.name ? targetDev.name : 'a TV';
      const reason = `Você transferiu ${devName} para ${owner}. Clique em "Assumir TV" para retomar no PC.`;
      return {
        kind: 'preempted',
        isError: false,
        banner: buildTakeoverBannerPayload(targetDev, owner, reason),
        toastMessage: reason
      };
    }

    return null;
  }

  function shouldOfferDirectTakeoverOnAction(pendingBusyDevice, selectedDevice) {
    return Boolean(
      pendingBusyDevice &&
        selectedDevice &&
        pendingBusyDevice.id &&
        pendingBusyDevice.id === selectedDevice.id
    );
  }

  return {
    normalizeSenderName,
    buildTakeoverBannerPayload,
    resolveSignalingEndAction,
    shouldOfferDirectTakeoverOnAction
  };
});
