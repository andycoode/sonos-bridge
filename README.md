# Sonos Bridge for Fire TV Stick

Stream audio from your Fire Stick to a Sonos speaker over WiFi.

## The Problem

The Sonos One has no physical audio inputs (no Bluetooth, no aux, no optical). It only
receives audio over WiFi. The Fire Stick only outputs audio via HDMI or Bluetooth.
There's no native way to bridge the two.

## How It Works

```
┌──────────────┐     HDMI      ┌────────────┐
│  Fire Stick  │──────────────▶│  Projector  │  (video)
│              │               └────────────┘
│  ┌────────┐  │
│  │ Sonos  │  │   HTTP/WAV    ┌────────────┐
│  │ Bridge │  │◄──────────────│  Sonos One  │  (audio)
│  │  App   │  │──────────────▶│            │
│  └────────┘  │  UPnP/SOAP   └────────────┘
└──────────────┘
```

1. **Audio Capture**: Uses Android's `AudioPlaybackCapture` API (Android 10+) to tap
   into system audio output at the mixer level.

2. **HTTP Stream Server**: Runs a lightweight NanoHTTPD server on the Fire Stick that
   serves captured audio as a live WAV stream.

3. **Sonos Control**: Sends UPnP/SOAP commands to the Sonos speaker telling it to play
   from the Fire Stick's HTTP stream URL.

4. **Auto-Discovery**: Finds Sonos speakers on your network using SSDP multicast.

## Latency Strategy

There are two sources of delay:

| Source | Latency | Controllable? |
|--------|---------|---------------|
| Audio capture buffer | ~20ms | ✅ Minimised |
| PCM encoding | 0ms (raw) | ✅ No encoding |
| Network transfer | ~5ms | ✅ LAN only |
| Sonos internal buffer | ~500-2000ms | ❌ Not controllable |

**Total expected delay: ~0.5 - 2 seconds**

### Compensation: Video Delay Slider

Since we can't reduce the Sonos buffering, the app includes a **video delay slider**
(0-3000ms). The idea is:

1. Start streaming
2. Play content with visible audio cues (e.g. someone talking)
3. Adjust the slider until lips sync with audio
4. The app remembers this setting

> **Note**: The current version compensates by letting you tune the delay value.
> A future version could overlay a delayed video feed using `MediaProjection` screen
> capture, but this is resource-intensive on a Fire Stick.

### Tips to Minimise Latency

- Use the `x-rincon-mp3radio://` protocol prefix (already implemented) — this tells
  Sonos to use its live radio mode with smaller buffers
- Keep the Fire Stick and Sonos on the same WiFi network (ideally 5GHz)
- Set the WAV data length to unknown/max — signals "live stream" to Sonos
- Use `object.item.audioItem.audioBroadcast` in the DIDL metadata — another hint
  that this is live content

## Requirements

- Fire TV Stick (2nd gen or newer) running Fire OS 7+ (Android 10+)
- Sonos speaker on the same WiFi network
- The 3rd party app you're streaming must NOT set `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)`
  (most 3rd party apps don't — DRM-protected apps like Netflix/Prime Video do)

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- Kotlin 1.9+

### Steps

```bash
# Clone/download the project
cd sonos-bridge

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

## Sideloading onto Fire Stick

### Method 1: ADB over WiFi

```bash
# 1. On Fire Stick: Settings → My Fire TV → Developer Options
#    - Enable "ADB Debugging"
#    - Enable "Apps from Unknown Sources"

# 2. Find your Fire Stick's IP:
#    Settings → My Fire TV → About → Network

# 3. Connect from your PC:
adb connect <fire-stick-ip>:5555

# 4. Install the APK:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Method 2: Using "Downloader" app

1. Install "Downloader" from Amazon App Store on Fire Stick
2. Host the APK on your local network or a file sharing service
3. Enter the URL in Downloader and install

## Usage

1. Launch **Sonos Bridge** from your Fire Stick apps
2. Press **Discover Speakers** — your Sonos One should appear
3. Select your speaker from the list
4. Press **Start Streaming** — approve the screen capture permission when prompted
5. Switch to your 3rd party app and play content
6. Audio should start playing through your Sonos within a few seconds
7. Adjust the **Video Delay** slider if audio is noticeably behind the video

## Known Limitations

- **DRM-protected apps** (Netflix, Prime Video, Disney+) block audio capture at the
  system level. This app only works with 3rd party apps that don't restrict capture.
- **Sonos buffering** adds ~0.5-2s of unavoidable delay. The video delay slider helps
  compensate but it's a manual adjustment.
- **MediaProjection permission** must be re-granted each time the app starts (Android
  security requirement — no way around this).
- **Fire OS quirks** — Amazon's Fire OS strips some Android features. If discovery
  fails, try entering the Sonos IP manually (a manual IP entry feature is planned).

## Project Structure

```
app/src/main/java/com/sonosbridge/
├── MainActivity.kt          # UI, permissions, orchestration
├── AudioCaptureService.kt   # Foreground service, MediaProjection audio capture
├── AudioStreamServer.kt     # NanoHTTPD HTTP server, serves live WAV stream
├── SonosController.kt       # UPnP/SOAP commands to control Sonos playback
├── SonosDiscovery.kt        # SSDP multicast discovery of Sonos speakers
└── NetworkUtils.kt           # Local IP address detection
```

## Future Ideas

- Manual Sonos IP entry (bypass discovery issues)
- Video delay overlay using screen capture (full lip-sync compensation)
- Persistent settings (remember speaker, delay value)
- Volume control from the app
- Support for Sonos speaker groups
- Opus/AAC encoding option for lower bandwidth
