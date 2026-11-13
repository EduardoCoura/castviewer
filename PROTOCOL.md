# FireCast Local — Protocolo de Descoberta e Sinalização (v1)

## 1. Descoberta na Rede Local (mDNS / DNS-SD)
- **Service Type:** `_firecast._tcp.` (Android NSD: `_firecast._tcp.`)
- **Porta padrão:** `8765` (WebSocket de sinalização)
- **TXT Records:**
  - `name`: Nome amigável do Fire Stick (ex.: `"Fire Stick Sala"`)
  - `version`: `"1"`

## 2. Sinalização WebSocket (`ws://<FIRESTICK_IP>:8765`)
Mensagens em JSON UTF-8:

### Cliente (Desktop/Mobile) -> Fire Stick (Receptor)
1. **Offer SDP**
```json
{
  "type": "offer",
  "sdp": "<SDP string>",
  "senderName": "Notebook Windows",
  "video": { "width": 1920, "height": 1080, "fps": 60 },
  "audio": true
}
```
2. **ICE Candidate**
```json
{
  "type": "ice-candidate",
  "candidate": {
    "candidate": "<candidate string>",
    "sdpMid": "0",
    "sdpMLineIndex": 0
  }
}
```
3. **Stop / Disconnect**
```json
{
  "type": "bye"
}
```

### Fire Stick (Receptor) -> Cliente
1. **Answer SDP**
```json
{
  "type": "answer",
  "sdp": "<SDP string>"
}
```
2. **ICE Candidate**
```json
{
  "type": "ice-candidate",
  "candidate": {
    "candidate": "<candidate string>",
    "sdpMid": "0",
    "sdpMLineIndex": 0
  }
}
```
3. **Status / Busy / Error**
```json
{
  "type": "status",
  "state": "ready" | "streaming" | "busy",
  "message": "..."
}
```

## 3. WebRTC (LAN Only)
- `iceServers`: `[]` (sem STUN/TURN externo, 100% rede local).
- Codecs preferenciais: H.264 (hardware decode no Fire Stick) com fallback VP8; áudio Opus.

## 4. Apple AirPlay 2 / RAOP (`firestick-app` v2.5+)
- **mDNS / DNS-SD (Android NSD):** `_airplay._tcp.` e `_raop._tcp.` (`<HWID>@<Nome>`), publicados na porta RTSP padrão `7000` (ou porta dinâmica retornada pelo socket nativo).
- **Motor Nativo (`libcastviewer_airplay.so`):** implementação local via JNI integrando OpenSSL (FairPlay / SRP / Ed25519 / X25519), `libplist` e `llhttp`, com identidade hardware de 6 bytes persistida em armazenamento privado da aplicação.
- **Mídia na TV:** vídeo H.264 e H.265/HEVC (Annex-B com VPS/SPS/PPS) decodificado diretamente por hardware via `MediaCodec` em `SurfaceView` centralizado (com preservação de proporção retrato/paisagem), e áudio PCM / ALAC / AAC-LC / AAC-ELD reproduzido via `AudioTrack` (`USAGE_MEDIA`).
- **Convivência WebRTC ↔ AirPlay:** sessões exclusivas na TV — uma nova conexão AirPlay encerra a sessão WebRTC anterior (e vice-versa) sem necessidade de reiniciar os anúncios mDNS.
- **Escopo conhecido:** streams protegidos por DRM FairPlay da Apple em apps fechados (ex.: Apple TV+, Netflix) permanecem bloqueados pelo sistema operacional do dispositivo iOS.
