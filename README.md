# ClawWatch

A voice-first AI assistant for Wear OS. Connect it to an OpenClaw gateway, speak to your watch, and hear responses through either on-device Android TTS or ElevenLabs.

## Features

- **Voice-first interaction** — tap the orb, speak, and hear the response back
- **Configurable speech output** — choose local Android TTS or ElevenLabs in settings
- **LiquidLifeforce UI** — a living, morphing entity that smoothly transitions between orb (idle), waveform (listening/speaking), ring (thinking), and vortex (compacting) states
- **TTS pre-fetching** — next audio chunk fetches while the current one plays for seamless speech
- **Streaming responses** — text appears in real time with sentence-by-sentence playback
- **Swipe-up command menu** — mute, continuous mode, timer, compact session, settings
- **Timer with snarky jokes** — AI delivers a witty timer-related joke + alarm sound on completion
- **Independent volume controls** — separate assistant voice and alarm volumes in settings
- **Session compaction** — compress context via gateway `sessions.compact` RPC
- **Continuous conversation mode** — auto-listens after each response for hands-free chat
- **Auto-reconnect** — exponential backoff with 32s cap
- **Location-aware** — sends GPS coordinates with each message (cached 60s for battery)
- **Ambient mode** — burn-in-safe static outline when watch screen dims
- **Audio ducking** — ducks other audio during listening/speaking
- **AI skill awareness** — the AI knows it can browse the web, generate images/videos, send emails, etc. Media outputs are delivered to Discord DM

## Requirements

- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Wear OS device or emulator running API 30+ (Wear OS 3+)
- An OpenClaw gateway server

## Building

1. Open the project in Android Studio, or build from the command line:
   ```
   ./gradlew assembleDebug
   ```

2. Install on a connected Wear OS device:
   ```
   ./gradlew installDebug
   ```

## Releases

- Pushes to `main` and pull requests build a debug APK in GitHub Actions and upload it as a workflow artifact.
- Tags matching `v*` build a release APK and attach `app-release-unsigned.apk` to the GitHub release for that tag.
- Debug builds use the app id suffix `.debug` and version suffix `-debug` so they stay separate from release installs.
- Release tags should match `app/build.gradle.kts` `versionName` values, for example `v0.1.1`.

## Configuration

Swipe up on the main screen → tap **Settings** to configure:

- **Gateway URL** — address of your OpenClaw gateway
- **Auth Token** — gateway authentication token
- **Use local TTS** — toggle between on-device speech and ElevenLabs
- **ElevenLabs API Key** — required only when ElevenLabs mode is enabled
- **ElevenLabs Voice ID** — required only when ElevenLabs mode is enabled
- **🔊 System Volume** — device media volume
- **🗣 Assistant Volume** — speech playback volume
- **🔔 Alarm Volume** — timer alarm ringtone volume

## Project Structure

```
app/src/main/java/com/clawwatch/
├── MainActivity.kt                  # Entry point + ambient mode
├── ClawWatchApp.kt                  # Application class + DI
├── data/
│   ├── OpenClawProtocol.kt          # Wire protocol data classes
│   ├── OpenClawClient.kt            # WebSocket client + sessions.compact RPC
│   ├── SettingsStore.kt             # DataStore-backed preferences
│   └── LocationProvider.kt          # GPS with 60s cache
├── viewmodel/
│   └── MainViewModel.kt             # Central ViewModel (voice + network + timer)
├── voice/
│   ├── VoiceInput.kt                # SpeechRecognizer (warm start, reused)
│   └── VoiceOutput.kt               # Local Android TTS or ElevenLabs playback
└── ui/
    ├── MainScreen.kt                # Primary watch face composable
    ├── SettingsScreen.kt            # Connection + volume settings
    ├── navigation/NavGraph.kt       # Wear OS swipe-dismissable navigation
    └── components/
        ├── LiquidLifeforce.kt       # Unified morphing animation (orb/line/ring/vortex)
        ├── CommandMenu.kt           # Swipe-up action menu
        ├── TimerPicker.kt           # Timer duration selector
        ├── AnimatedConnectionDot.kt # Connection status indicator
        └── ThinkingIndicator.kt     # Pulsing dots animation
```

## How It Works

1. App connects to OpenClaw gateway over WebSocket
2. Challenge-response handshake authenticates the client
3. Tap the orb → Android SpeechRecognizer transcribes speech on-device
4. Transcribed text + GPS location sent to the gateway as `chat.send` RPC
5. Gateway streams back response chunks in real time
6. Response text is spoken via the configured speech provider
7. AI can trigger timers via `:::timer {}` blocks, which are stripped from display/voice and handled natively
