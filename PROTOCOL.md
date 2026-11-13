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
