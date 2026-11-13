# CastViewer — Receptor Nativo Fire OS / Android TV

Receptor nativo em Kotlin de alta performance para Amazon Fire TV Stick, Fire TV Cube e Smart TVs Android.

Projetado especificamente para rodar sem dependências do Google Play Services (100% autônomo), utilizando decodificação de vídeo acelerada por hardware via MediaCodec e renderização direta com `SurfaceViewRenderer`.

---

## 📺 Recursos Principais

1. **Descoberta Automática na Rede (mDNS / DNS-SD)**:
   - Anuncia o serviço `_firecast._tcp.` na porta `8765` via `NsdManager` do Android.
   - Fornece atributos TXT com o nome amigável da TV (`name`) e versão do protocolo (`version="1"`).
   - Gerencia automaticamente `WifiManager.MulticastLock` para garantir recepção confiável de pacotes mDNS no Fire OS.

2. **Servidor WebSocket Integrado (Sinalização LAN)**:
   - WebSocket embutido na porta `8765` via `Java-WebSocket:1.5.7`.
   - Implementa a troca de SDP (`offer` / `answer`), candidatos ICE e mensagens de controle (`bye`, `status`).
   - Gerenciamento de sessão única: responde com status `busy` se um segundo cliente tentar transmitir enquanto o receptor já estiver ocupado.

3. **WebRTC Nativo de Baixa Latência (LAN Only) + Correção HDMI Áudio TV**:
   - Biblioteca `io.getstream:stream-webrtc-android:1.2.2`.
   - Decodificador de hardware `DefaultVideoDecoderFactory(eglBase.eglBaseContext)` (H.264 / VP8).
   - Sem dependência de servidores STUN/TURN externos (`iceServers = emptyList()`), funcionando 100% offline em redes locais isoladas.
   - **Correção HDMI Audio Fire TV**:
     - Configuração de `JavaAudioDeviceModule` com `AudioAttributes` (`USAGE_MEDIA`, `CONTENT_TYPE_MOVIE`), `setUseLowLatency(true)` e `setUseStereoOutput(true)`.
     - Forçamento explícito de `AudioManager.MODE_NORMAL` e `isSpeakerphoneOn = false` na thread principal ao conectar e acoplar a faixa de áudio, evitando que o WebRTC use `MODE_IN_COMMUNICATION` (que silencia ou desvia a saída de áudio HDMI no Fire OS).
     - Roteamento de controle de volume para `AudioManager.STREAM_MUSIC`.

4. **Interface Studio Otimizada para TV (10-Foot UI & Design System CastViewer)**:
   - Tela de espera com paleta Dark Studio (`#09090B` canvas, `#111115` cards, bordas hairline, acento `#6366F1` Electric Indigo e status `#10B981` Emerald).
   - Nome do dispositivo, IP local (`192.168.x.x:8765`) e QR Code nítido gerado via ZXing.
   - Transição suave para tela cheia imersiva (`SurfaceViewRenderer`) ao iniciar a transmissão.
   - Badge flutuante (HUD) discreto no topo: `CastViewer • <Remetente> • Voltar para sair` com auto-fade após 3.5 segundos.
   - **Controle Remoto (D-Pad)**:
     - Botão **VOLTAR (BACK)** durante a transmissão encerra a sessão ativa e retorna imediatamente para a tela de espera com o QR code (não fecha o app).
     - Botão **VOLTAR (BACK)** na tela de espera sai do aplicativo.
     - Botão **OK / SELECT / MENU** exibe temporariamente o HUD com detalhes da transmissão ativa.

---

## 📁 Estrutura do Projeto

```
firestick-app/
├── app/
│   ├── build.gradle.kts                # Configuração do módulo Android (compileSdk 34, minSdk 25)
│   ├── proguard-rules.pro              # Regras de preservação para WebRTC e WebSocket
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml     # Permissões de rede, Leanback e LEANBACK_LAUNCHER
│           ├── java/com/firecast/local/
│           │   ├── MainActivity.kt     # UI TV, gerenciamento de tela cheia, áudio HDMI e controle remoto
│           │   ├── SignalingServer.kt  # Servidor WebSocket RFC 6455 porta 8765
│           │   ├── WebRtcReceiver.kt   # Pipeline WebRTC, EGL hardware decode e áudio USAGE_MEDIA
│           │   ├── NsdAdvertiser.kt    # mDNS DNS-SD _firecast._tcp. e MulticastLock
│           │   └── NetworkUtils.kt     # Resolução de IPv4 (Wi-Fi/Eth), modelo Fire TV e QR Code
│           └── res/
│               ├── drawable/           # Backgrounds Studio, badges, ícones TV/Cast e banner Leanback
│               ├── layout/
│               │   └── activity_main.xml # Layout D-Pad com SurfaceViewRenderer + HUD + Tela de espera
│               └── values/
│                   ├── colors.xml      # Paleta Dark Studio CastViewer (Obsidian, Electric Indigo, Emerald)
│                   ├── strings.xml     # Textos CastViewer em Português (pt-BR)
│                   └── themes.xml      # Tema NoActionBar fullscreen com keepScreenOn
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew.bat
```

---

## 🛠️ Como Compilar e Instalar no Fire Stick

### Pré-requisitos:
- JDK 17+
- Android SDK (com plataformas API 34 e Build Tools)
- Fire Stick com **Opções de Desenvolvedor** e **Depuração ADB** ativadas:
  - No Fire TV: *Configurações > Meu Fire TV > Informações > Clicar 7x no nome do dispositivo*.
  - Em *Opções de desenvolvedor*: Ativar *Depuração ADB* e *Aplicativos de fontes desconhecidas*.

### Compilação:
```bash
./gradlew assembleDebug
```
O APK será gerado em: `app/build/outputs/apk/debug/app-debug.apk`

### Instalação via Wi-Fi no Fire Stick:
```bash
# Conectar via ADB ao IP do Fire Stick (ex: 192.168.1.150)
adb connect 192.168.1.150:5555

# Instalar o aplicativo
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Iniciar o aplicativo
adb shell am start -n com.firecast.local/.MainActivity
```
