# CastViewer (FireCast Local)

Ecossistema completo de espelhamento de tela e áudio **100% em rede local (LAN)** via **WebRTC** com baixa latência, descoberta automática via mDNS (`_firecast._tcp.`) e sinalização WebSocket local na porta `8765` (sem nuvem nem STUN/TURN externo).

## Componentes do Monorepo

| Pasta | Plataforma | Descrição | Versão Atual |
| :--- | :--- | :--- | :--- |
| [`firestick-app/`](./firestick-app) | **Fire TV / Android TV** (`minSdk 25`) | Receptor nativo Kotlin com decodificação por hardware (`MediaCodec` / H.264 / VP8), QR Code na tela e áudio HDMI em modo mídia. | `v2.4` (`versionCode 15`) |
| [`android-sender/`](./android-sender) | **Android** (`minSdk 26`) | Emissor Jetpack Compose / Material 3 com `MediaProjection`, áudio interno, Clip Replay 30s, modo AMOLED, Tile rápido e bolha flutuante externa. | `v1.7.4` (`versionCode 13`) |
| [`desktop-sender/`](./desktop-sender) | **Windows (Electron 45)** | Emissor para PC/Notebook com captura de tela/janela, áudio loopback WASAPI (`loopback-capture`), silenciamento inteligente de alto-falantes e Clip Replay. | `v1.0.0` |

## Documentação Adicional

- [`PROTOCOL.md`](./PROTOCOL.md) — Especificação do protocolo mDNS + WebSocket LAN (`_firecast._tcp.`, porta `8765`, Offer/Answer SDP, ICE e controle).
- [`DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) — Identidade visual Dark Studio + Fire Orange e tokens compartilhados.
- [`firestick-app/README.md`](./firestick-app/README.md) — Detalhes técnicos e build do receptor para Fire TV.

## Como Compilar a Partir do Código-Fonte

### 1. Desktop Sender (Windows / Node.js)
```bash
cd desktop-sender
npm install
npm run check
npm run dist:win
```

### 2. Android Sender
```bash
cd android-sender
./gradlew testDebugUnitTest assembleDebug
```

### 3. Fire TV / Android TV Receiver
```bash
cd firestick-app
./gradlew testDebugUnitTest assembleDebug
```

> Os instaladores prontos (`.apk` para Android/Fire TV e `.exe` para Windows) são disponibilizados na aba **Releases** do repositório.
