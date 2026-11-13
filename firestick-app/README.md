# CastViewer — Receptor Nativo Fire OS / Android TV (`v2.5`)

Receptor nativo em **Kotlin + C/C++ (NDK/JNI)** de baixa latência para Amazon Fire TV Stick, Fire TV Cube e Smart TVs Android.

Projetado para rodar sem dependências do Google Play Services (100% autônomo na rede local), recebendo tanto **WebRTC LAN P2P** quanto **Apple AirPlay 2 / RAOP** com decodificação acelerada por hardware via `MediaCodec` (`SurfaceViewRenderer`) e áudio HDMI via `AudioTrack`.

---

## 📺 Recursos Principais

1. **Descoberta Automática na Rede (mDNS / DNS-SD)**:
   - Anuncia o serviço WebRTC `_firecast._tcp.` na porta `8765` via `NsdManager` (`NsdAdvertiser.kt`) com TXT records `name` e `version="1"`.
   - Anuncia o receptor AirPlay (`_airplay._tcp.` e `_raop._tcp.`) via `AirPlayNsdAdvertiser.kt` para iPhone, iPad e Mac.
   - Gerencia `WifiManager.MulticastLock` para recepção confiável de pacotes mDNS no Fire OS.

2. **Receptor Apple AirPlay 2 / RAOP Nativo (C/C++ NDK + JNI)**:
   - Core baseado em UxPlay + decodificação ALAC em [`app/src/main/cpp/`](./app/src/main/cpp) integrado via `AirPlayNativeBridge.kt` e `AirPlayReceiverController.kt`.
   - Decodificação de vídeo H.264 / H.265 (HEVC) em `AirPlayVideoRenderer.kt` com cálculo automático retrato/paisagem (`AirPlayAspectMath.kt`).
   - Saída de áudio PCM / ALAC / AAC em `AirPlayAudioRenderer.kt` e alternância automática entre sessões WebRTC e AirPlay.

3. **Servidor WebSocket & WebRTC LAN P2P (Porta `8765`)**:
   - WebSocket embutido em `SignalingServer.kt` para troca de SDP (`offer` / `answer`), candidatos ICE, takeover coordenado (`TakeoverCoordinator.kt`) e comandos de controle.
   - Pipeline WebRTC em `WebRtcReceiver.kt` e decodificação direta em superfície (`DirectSurfaceVideoDecoder.kt`) sem STUN/TURN externo (`iceServers = emptyList()`).
   - Áudio HDMI no Fire OS configurado com `USAGE_MEDIA` / `CONTENT_TYPE_MOVIE` e `AudioManager.MODE_NORMAL` + `STREAM_MUSIC` para evitar roteamento indevido para chamada.

4. **Interface Studio Otimizada para TV (10-Foot UI & Design System CastViewer)**:
   - Paleta Studio Charcoal (`#0A0A0C`) e acento Signal Orange (`#FF5A1F`) — zero brilho roxo/ciano neon — com animação de abertura (`CinematicIntroView.kt`, `KineticMath.kt`), relógio na TV (`NetworkClockResolver.kt`) e QR Code para conexão rápida.
   - Botão **VOLTAR (BACK)** no controle durante a transmissão encerra a sessão ativa e retorna à tela de espera; **OK / SELECT / MENU** exibe o HUD de status.

---

## 📁 Estrutura do Projeto

```
firestick-app/
├── app/
│   ├── build.gradle.kts                # Módulo Android (compileSdk 34, minSdk 25, targetSdk 34, NDK 27)
│   └── src/main/
│       ├── AndroidManifest.xml         # Permissões de rede, Leanback e LEANBACK_LAUNCHER
│       ├── cpp/                        # Bridge JNI, shim DNS-SD Android, UxPlay e ALAC (CMake 3.22+)
│       ├── java/com/firecast/local/
│       │   ├── MainActivity.kt         # UI TV, HUD, controle remoto e orquestração WebRTC/AirPlay
│       │   ├── SignalingServer.kt      # Servidor WebSocket porta 8765
│       │   ├── WebRtcReceiver.kt       # Pipeline WebRTC + áudio HDMI USAGE_MEDIA
│       │   ├── DirectSurfaceVideoDecoder.kt
│       │   ├── NsdAdvertiser.kt        # mDNS _firecast._tcp. e MulticastLock
│       │   ├── TakeoverCoordinator.kt  # Coordenação de troca/assunção de sessão
│       │   ├── NetworkClockResolver.kt # Hora exibida na TV durante a transmissão
│       │   ├── CinematicIntroView.kt   # Animação de entrada e KineticMath.kt
│       │   ├── NetworkUtils.kt         # IPv4 local, modelo Fire TV e QR Code
│       │   └── airplay/                # AirPlayNativeBridge, Controller, NsdAdvertiser, Renderers e AspectMath
│       └── res/                        # Layouts TV, ícones Fire-Orange, banner Leanback e textos pt-BR
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

---

## 🛠️ Como Compilar e Instalar no Fire Stick

### Pré-requisitos:
- JDK 17+
- Android SDK (API 34), **Android NDK 27** (`27.0.12077973`) e **CMake 3.22+** (o CMake baixa automaticamente OpenSSL e libplist durante o build)
- Fire Stick com **Opções de Desenvolvedor** e **Depuração ADB** ativadas:
  - No Fire TV: *Configurações > Meu Fire TV > Informações > Clicar 7x no nome do dispositivo*.
  - Em *Opções de desenvolvedor*: Ativar *Depuração ADB* e *Aplicativos de fontes desconhecidas*.

### Compilação e Testes:
```bash
./gradlew testDebugUnitTest assembleDebug
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
