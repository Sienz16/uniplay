# Uniplay: Universal Dual Receiver (CarPlay & Android Auto)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-21%20(Lollipop)-green.svg)](https://developer.android.com)
[![Android Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.13-blueviolet.svg)](https://gradle.org)
[![Platform](https://img.shields.io/badge/Platform-Automotive%20Head%20Units%20(1GB%20RAM)-orange.svg)]()

**Uniplay** is an ultra-lean, modular, unified Apple CarPlay and Google Android Auto software receiver engine engineered specifically for entry-level automotive Android head units. It is designed from the ground up to operate reliably on constrained hardware environments (**1GB RAM / 16GB Storage**, UIS8581A, Allwinner, MTK, BYD DiLink, and generic aftermarket head units) without triggering the Android Low Memory Killer (LMK).

---

## ⚡ Architectural Highlights

1. **Zero-Copy Video Pipeline**:
   - Video frames (H.264 NAL units) bypass JVM memory allocations and intermediate `Bitmap` / `byte[]` arrays entirely.
   - Feeds directly into hardware `MediaCodec` and outputs natively onto an `ANativeWindow` (`SurfaceView`), keeping video memory consumption **under 10MB RAM**.

2. **Lean Audio Ringbuffer**:
   - Ultra-low latency `AudioTrack` direct streaming ringbuffer.
   - Bounded buffer allocations with total audio memory overhead **under 2MB**.
   - Multi-channel stream separation (Media Audio vs. Navigation / Voice Assistant).

3. **MemoryGuard (LMK Defense Engine)**:
   - Proactive daemon monitoring system cgroups and available RAM thresholds.
   - Intercepts memory pressure events before the Linux kernel Low Memory Killer (LMK) sends `SIGKILL` to the projection process.

4. **Unified Hardware Dispatcher (`DeviceRouter`)**:
   - Automatic USB attachment detection and VID/PID routing:
     - **VID `0x05AC`**: Apple iPhone -> CarPlay protocol engine.
     - **VID `0x18D1`**: Google AOA -> Android Auto protocol engine.
   - Enforces a strict single-session rule to prevent concurrent CPU/GPU resource contention.

5. **Pure Native UI (No Framework Bloat)**:
   - Zero Jetpack Compose overhead, minimal AndroidX dependencies.
   - Pure XML layout with a single fullscreen hardware `SurfaceView`.

---

## 📂 Project Architecture

```
uniplay/
├── app/                          # Main Android application module
│   ├── src/main/java/com/uniplay/app/
│   │   ├── ProjectionActivity.kt # True fullscreen immersive projection activity
│   │   └── DeviceRouter.kt       # USB event dispatcher & single-session manager
│   ├── src/main/res/xml/
│   │   └── device_filter.xml     # USB accessory and device VID filter rules
│   └── src/main/AndroidManifest.xml
│
├── core-common/                  # Core media & system resilience module
│   └── src/main/java/com/uniplay/core/
│       ├── ZeroCopyVideoDecoder.kt # Hardware MediaCodec H.264 decoder to Surface
│       ├── LeanAudioTrack.kt       # Low-latency AudioTrack streaming wrapper
│       ├── MemoryGuard.kt          # Active RAM monitoring and compaction utility
│       ├── MediaSink.kt            # Zero-copy callback seam for decoders
│       ├── ProjectionEngine.kt     # Common abstraction for projection protocols
│       └── ProjectionType.kt       # Projection enums and audio channel definitions
│
├── protocol-androidauto/         # Android Auto protocol implementation
│   └── src/main/java/com/uniplay/androidauto/
│       └── AndroidAutoEngine.kt   # AAP framing, AOA 2.0 transport & Protobuf dispatch
│
├── protocol-carplay/             # Apple CarPlay protocol implementation
│   └── src/main/java/com/uniplay/carplay/
│       └── CarPlayEngine.kt       # AirPlay / iAP2 framing & decrypted stream sink
│
└── gradle/                       # Build configuration & Gradle version catalogs
```

---

## 📊 Current Development Status

> [!NOTE]
> Uniplay has completed its initial architectural scaffolding, core media decoder pipeline, and build system configuration. Both Debug and Release builds (with R8 code shrinking and optimization) compile successfully.

> [!TIP]
> **Continuing Development on Another Device?**
> Check out the [Developer Guide & Implementation Blueprint](DEVELOPMENT.md) for upstream reference clone commands, architectural file mapping, and the step-by-step production roadmap.

| Component | Status | Description |
| :--- | :---: | :--- |
| **Gradle Build Pipeline** | ✅ Ready | Builds cleanly with Gradle 8.13 and Android SDK 34. |
| **R8 / ProGuard Optimization** | ✅ Ready | Full code shrinking and resource optimization verified. |
| **ZeroCopyVideoDecoder** | ✅ Ready | `MediaCodec` decoder bound to native `SurfaceView`. |
| **LeanAudioTrack** | ✅ Ready | Low-overhead streaming audio pipeline for Media & Nav. |
| **MemoryGuard** | ✅ Ready | Real-time RAM compaction and LMK mitigation daemon. |
| **USB Device Routing Filter** | ✅ Ready | XML hardware filters configured for Apple & Google VIDs. |
| **USB Transport Layer** | 🔄 In Progress | Bridging Android `UsbManager` bulk endpoints to `TransportChannel`. |
| **Google AOA 2.0 Handshake** | 🔄 In Progress | Porting accessory mode switching and AASDK SSL channel. |
| **Apple AirPlay / iAP2 Seam** | 🔄 In Progress | Porting RTSP session negotiation and decryption seam. |
| **Touch / Key Backpropagation**| 🔄 In Progress | Connecting touch events from `SurfaceView` back to phone. |

---

## 🔌 Hardware Identification & Compatibility

| Standard | Vendor ID (VID) | Product ID (PID) | Connection Protocol |
| :--- | :--- | :--- | :--- |
| **Apple CarPlay** | `0x05AC` (1452) | Various (iPhone) | USB NCM / Bulk iAP2 / AirPlay |
| **Android Auto (AOA)** | `0x18D1` (6353) | `0x2D00` / `0x2D01` | Google Android Open Accessory 2.0 |

---

## 🛠️ Building & Installing

### Prerequisites
- **JDK**: Java Development Kit 17+ (or Android Studio bundled JBR 21)
- **Android SDK**: Build-Tools `34.0.0`, Compile SDK `34`, Min SDK `21`
- **Gradle**: 8.13 (included via `./gradlew`)

### Build Commands

```bash
# 1. Clone the repository
git clone git@github.com:Sienz16/uniplay.git
cd uniplay

# 2. Build Debug APK
./gradlew assembleDebug

# 3. Build Optimized Production Release APK (with R8 minification)
./gradlew assembleRelease
```

Compiled APK locations:
* **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
* **Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Installing to Android Head Unit via ADB

```bash
# Connect to head unit via Wi-Fi or USB debugging
adb connect <head-unit-ip>:5555

# Sideload the APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🤝 Third-Party Credits & Acknowledgments

Uniplay stands on the shoulders of giants in the open-source automotive reverse-engineering and software receiver community. We gratefully acknowledge and credit the following foundational projects and researchers:

1. **[DiAuto](https://github.com/shihabal3amri/DiAuto)** by **Shihab Al-Amri (`shihabal3amri`)**
   - Pioneering Android Auto implementation for automotive Android head units (BYD DiLink).
   - Provided foundational architecture for USB accessory handling, Wi-Fi Direct p2p discovery, and frame pacing.

2. **[DiPlay](https://github.com/shihabal3amri/DiPlay)** by **Shihab Al-Amri (`shihabal3amri`)**
   - Independent Apple CarPlay software receiver implementation for automotive displays.
   - Provided critical insights into AirPlay streaming, audio ringbuffer tuning, and head unit compatibility.

3. **[OpenHeadUnit](https://github.com/andrerinas)** by **Andre Rinas**
   - Open-source Android Auto head unit client implementation.
   - Core research on Android Open Accessory (AOA) protocol handshakes and channel multiplexing.

4. **Headunit for Android Auto** by **Michael A. Reid (`mikereidis`)**
   - Earliest open-source research and implementation of software-based Android Auto receivers (licensed under GNU AGPLv3).

5. **AASDK Community**
   - The open-source Android Auto Software Development Kit community for defining AAP framing and protobuf channel specifications.

---

## ⚖️ License & Legal Disclaimers

### License
This project is licensed under the **GNU General Public License v3.0 (GPLv3)** in compliance with upstream copyleft obligations from derived open-source research. See the [LICENSE](LICENSE) file for the complete license text.

```
Uniplay: Universal Dual Receiver (CarPlay & Android Auto)
Copyright (C) 2026 Uniplay Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```

### Trademark Disclaimers
* **Apple CarPlay®**, **AirPlay®**, **iAP2®**, **MFi®**, and **iPhone®** are registered trademarks of **Apple Inc.**, registered in the U.S. and other countries and regions.
* **Android™**, **Android Auto™**, **Google Play™**, and **Google™** are trademarks of **Google LLC**.
* Any product names, logos, brands, and other trademarks referenced within this project are the property of their respective trademark holders. These trademark holders are not affiliated with Uniplay, our products, or our repository. They do not sponsor, endorse, or certify this open-source research.

### Automotive Safety & Driver Distraction Disclaimer
> [!CAUTION]
> **DO NOT USE OR OPERATE THIS SOFTWARE WHILE OPERATING A MOTOR VEHICLE.**
> Distracted driving can result in serious injury or death. This software is provided strictly for experimental, developmental, and laboratory research purposes. The authors and contributors assume no liability for any direct, indirect, incidental, or consequential damages resulting from the use or misuse of this software in a moving vehicle. Always keep your eyes on the road and obey all local traffic and safety laws.

---

## 🔗 Repository Information

* **Primary Repository**: [github.com/Sienz16/uniplay](https://github.com/Sienz16/uniplay)
* **SSH Remote**: `git@github.com:Sienz16/uniplay.git`
* **HTTPS Remote**: `https://github.com/Sienz16/uniplay.git`
