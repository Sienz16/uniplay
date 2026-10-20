# Uniplay Developer Guide & Implementation Blueprint

This guide is designed for developers (and AI coding assistants) cloning this repository on a new machine. It outlines the project's exact state, external reference codebases, code mapping, and the chronological roadmap to bring Uniplay from architectural prototype to production-ready head unit receiver.

---

## 🚀 1. Quick Setup on a New Machine

### Prerequisites
- **Git** with SSH key added to GitHub.
- **JDK 17+** (or JDK 21 from Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`).
- **Android SDK** with Platform `android-34` and Build Tools `34.0.0`. Set `ANDROID_HOME` or define `sdk.dir` in `local.properties`.

### Clone the Repo & Fetch Upstream References

The core Uniplay repo is intentionally lean and does not commit third-party vendor code. However, the implementation logic relies heavily on the reverse-engineered reference implementations. Run these commands:

```bash
# Clone Uniplay
git clone git@github.com:Sienz16/uniplay.git
cd uniplay

# Clone the reference implementations locally for code inspection (ignored by .gitignore)
git clone https://github.com/shihabal3amri/DiAuto.git _ref_diauto
git clone https://github.com/shihabal3amri/DiPlay.git _ref_diplay
```

---

## 🗺️ 2. Reference Codebase Mapping Matrix

When implementing the unfinished layers in Uniplay, use the reference files below as your blueprint:

| Uniplay Target File | Reference File (in `_ref_*`) | Implementation Responsibility |
| :--- | :--- | :--- |
| **`core-common/.../UsbTransportChannel.kt`** *(To be created)* | `_ref_diauto/.../connection/UsbAccessoryConnection.kt`<br>`_ref_diauto/.../connection/UsbAccessoryMode.kt` | • Android `UsbManager` bulk transfer endpoints<br>• Sending AOA 2.0 control requests (get version, send vendor strings, start accessory)<br>• Converting endpoints into blocking `InputStream`/`OutputStream` |
| **`app/.../ProjectionActivity.kt`** | `_ref_diauto/.../app/UsbAttachedActivity.kt` | • Handling `ACTION_USB_DEVICE_ATTACHED` and `ACTION_USB_ACCESSORY_ATTACHED`<br>• Requesting USB runtime permissions (`UsbManager.requestPermission`)<br>• Instantiating `UsbTransportChannel` and handing it to `DeviceRouter` |
| **`protocol-androidauto/.../AndroidAutoEngine.kt`** | `_ref_diauto/.../aasdk/` | • AAP (Android Auto Protocol) frame parsing<br>• Channel 0 (Control/Handshake) SSL session setup<br>• Extracting SPS (`0x67`) & PPS (`0x68`) NALs from video channel before streaming |
| **`protocol-carplay/.../CarPlayEngine.kt`** | `_ref_diplay/.../` | • AirPlay RTSP handshake (`SETUP`, `RECORD`, `ANNOUNCE`)<br>• iAP2 / NCM Ethernet framing<br>• Decrypting video packets (ChaCha20-Poly1305 / AES-CTR) into raw H.264 NAL units |
| **`core-common/.../ZeroCopyVideoDecoder.kt`** | Existing Uniplay Code | • Hardware `MediaCodec` direct rendering to `SurfaceView`. Already built; only needs SPS/PPS passed via `onVideoConfig()` |
| **`core-common/.../LeanAudioTrack.kt`** | Existing Uniplay Code | • Low-latency `AudioTrack` ringbuffer under 2MB. Already built. |

---

## 📋 3. Step-by-Step Production Roadmap

Any developer or agent picking up this project should execute these tasks in the following exact order:

### Phase 1: USB Transport & Device Attachment (Priority #1)
- [ ] Create `UsbTransportChannel.kt` in `core-common` implementing `TransportChannel`.
- [ ] Support both USB Host mode (bulk endpoints) and Android Open Accessory (AOA) mode.
- [ ] In `ProjectionActivity.kt`, override `onNewIntent(intent: Intent?)` and inspect `intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)` and `UsbManager.EXTRA_ACCESSORY`.
- [ ] Wire up `UsbManager.requestPermission()` with a `BroadcastReceiver` for USB permission intent actions.
- [ ] Pass the open `UsbTransportChannel` to `deviceRouter.onUsbDeviceAttached(device, transport, this)`.

### Phase 2: Video SPS/PPS Extraction (Fixes the Black Screen)
- [ ] In `ZeroCopyVideoDecoder`, add a fallback NAL parser that inspects raw H.264 byte streams for start codes (`00 00 00 01 67` for SPS, `00 00 00 01 68` for PPS).
- [ ] When SPS/PPS are encountered in `AndroidAutoEngine` or `CarPlayEngine`, invoke `sink.onVideoConfig(sps, pps, width, height)` before calling `sink.onVideoNal()`.
- [ ] Verify that `MediaCodec` initializes and receives decoded frames on the `SurfaceView`.

### Phase 3: Android Auto Protocol Handshake (AOA & AASDK)
- [ ] Port the AOA switching sequence from `_ref_diauto` into `DeviceRouter`:
  - When VID is `0x18D1` and PID is NOT `0x2D00`/`0x2D01` (normal phone mode), send USB control transfer `51` to query AOA version, send vendor strings (transfer `52`), and send command `53` to force phone into Accessory mode.
  - Wait for USB reconnect with PID `0x2D00` / `0x2D01`.
- [ ] Implement the AAP Control Channel (Channel 0) handshake: Version negotiation, Service Discovery, and Auth/Ping messages.

### Phase 4: CarPlay AirPlay / iAP2 Seam
- [ ] Port the AirPlay pairing and RTSP session initiation from `_ref_diplay`.
- [ ] Extract decrypted H.264 video NAL units and route them to `sink.onVideoNal()`.
- [ ] Route decoded 44.1kHz / 48kHz audio PCM streams to `sink.onAudioData()`.

### Phase 5: Touch & Key Event Transmission
- [ ] In `AndroidAutoEngine.kt`, formulate AAP Touch Input Protobuf packets containing touch event action, normalized `(x, y)` screen coordinates, and write them to `currentTransport.outputStream`.
- [ ] In `CarPlayEngine.kt`, formulate iAP2 HID touch report packets and dispatch to output stream.
- [ ] Implement steering wheel key events (Play/Pause, Next Track, Prev Track, Voice Assistant).

### Phase 6: Hardening & DoS Prevention
- [ ] Add strict maximum packet size validation in `AndroidAutoEngine` and `CarPlayEngine` (`0 < length <= 4MB`) to guard against unhandled OOM / negative array allocation crashes.
- [ ] Make `MediaCodec` feeding non-blocking (or decouple lock from UI thread callbacks) to prevent ANR dialogs.

---

## 🧪 4. Testing & Verification Commands

```bash
# Check compilation and linting
./gradlew check

# Build debug APK for bench testing on head unit
./gradlew assembleDebug

# Deploy to connected head unit
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Monitor runtime logs and memory guard compaction in real-time
adb logcat -s ProjectionActivity DeviceRouter ZeroCopyDecoder UniplayMemoryGuard
```
