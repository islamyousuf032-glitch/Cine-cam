# CineCam — Professional RAW Camera (MotionCam Alternative)

> **প্ল্যাটফর্ম:** Android (Kotlin + NDK/C++17) | **Preview:** Jetpack Compose + OpenGL ES 3.1 | **Engine:** Camera2 RAW_SENSOR

CineCam একটি প্রফেশনাল CinemaDNG / MCRAW ভিত্তিক RAW ক্যামেরা অ্যাপ — MotionCam-এর আর্কিটেকচার থেকে অনুপ্রাণিত, কিন্তু আধুনিক Kotlin DSL, NDK Ring Buffer এবং Zero-copy পাইপলাইন দিয়ে তৈরি।

---

## ✨ বর্তমান অবস্থা — ধাপ ১ সম্পন্ন (v0.1.0-alpha)

### ✅ এই রিলিজে যা আছে
- **C++ Core Engine** — `MemoryPool`, `RingBuffer` (SPSC lock-free), `RawWriter`, `CineRawEngine` JNI ব্রিজ
- **OpenGL ES 3.1 Preview Renderer** — Debayer + Tonemap shader (Vulkan রেডি)
- **Kotlin Camera Layer** — `CameraController`, `CameraCapabilities` (RAW_SENSOR / RAW10/RAW12 যাচাই)
- **Jetpack Compose UI** — Manual Controls (ISO, Shutter, Focus, WB), FPS Selector, Recording Button
- **GitHub Actions CI/CD** — প্রতিটি push-এ অটোমেটিক APK build + GitHub Release

---

## 🏗️ প্রজেক্ট স্ট্রাকচার

```
Cine-cam/
├── .github/workflows/build-and-release.yml   # CI/CD - অটো APK + Release
├── app/
│   ├── build.gradle.kts                      # Kotlin DSL + NDK + Compose
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── cpp/                              # ★ C++ বেশি (NATIVE CORE)
│   │   │   ├── CMakeLists.txt
│   │   │   ├── include/
│   │   │   │   ├── cine_types.h              # FrameMetadata, RawFrame, Config
│   │   │   │   ├── memory_pool.h             # Zero-copy buffer pool
│   │   │   │   ├── ring_buffer.h             # SPSC lock-free queue
│   │   │   │   ├── raw_writer.h              # High-speed disk writer
│   │   │   │   ├── cinemadng_writer.h        # CinemaDNG sequence
│   │   │   │   ├── cine_raw_engine.h         # Central engine
│   │   │   │   └── gl_preview_renderer.h     # OpenGL preview
│   │   │   └── src/
│   │   │       ├── jni_bridge.cpp            # Kotlin ↔ C++ JNI
│   │   │       ├── cine_raw_engine.cpp
│   │   │       ├── memory_pool.cpp
│   │   │       ├── ring_buffer.cpp
│   │   │       ├── raw_writer.cpp
│   │   │       ├── cinemadng_writer.cpp
│   │   │       └── gl_preview_renderer.cpp
│   │   ├── java/com/cinecam/
│   │   │   ├── app/MainActivity.kt + CineCamApp.kt
│   │   │   ├── camera/CameraController.kt
│   │   │   ├── camera/CameraCapabilities.kt  # RAW checker
│   │   │   ├── engine/NativeRawEngine.kt     # JNI wrapper
│   │   │   ├── ui/screens/CameraScreen.kt
│   │   │   ├── ui/components/ManualControls.kt
│   │   │   └── ui/theme/Theme.kt
│   │   └── res/
│   └── proguard-rules.pro
├── gradle/wrapper/
├── docs/ARCHITECTURE.md
└── README.md
```

---

## 🔧 লোকাল বিল্ড

```bash
# Requirements: JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 GitHub Actions - অটোমেটিক রিলিজ

প্রতিটি `main` বা `arena/*` ব্রাঞ্চে push করলে:

1. JDK 17 + Android SDK + NDK 26.1 সেটআপ
2. `./gradlew assembleDebug` + `assembleRelease`
3. `app-debug.apk` artifact upload (30 দিন)
4. GitHub Release তৈরি — Tag: `v0.1.<run_number>-<short_sha>`
5. Release Notes বাংলায় + APK Assets সংযুক্ত

**Releases পেজ থেকে সরাসরি APK ডাউনলোড করুন:** `https://github.com/islamyousuf032-glitch/Cine-cam/releases`

---

## 🎯 রোডম্যাপ (ধাপে ধাপে)

| ধাপ | ফিচার | অবস্থা |
|-----|--------|--------|
| **ধাপ ১** | প্রজেক্ট ইনিশিয়ালাইজেশন, NDK বেস, CI/CD | ✅ **সম্পন্ন** |
| ধাপ ২ | Permissions + Camera2 Live Preview + Capability Checker | 🔜 পরবর্তী |
| ধাপ ৩ | Manual Controls (ISO/Shutter/Focus/WB) + Still RAW DNG | ⏳ |
| ধাপ ৪ | NDK Ring Buffer + Audio-Video Sync Framework | ⏳ |
| ধাপ ৫ | High-speed RAW Video Recording + Storage | ⏳ |
| ধাপ ৬ | প্রথম Release APK যাচাই | ⏳ |

---

## 📸 টেক স্ট্যাক

- **Language:** Kotlin 1.9.22 + C++17 (NDK)
- **UI:** Jetpack Compose (Material 3) + OpenGL ES 3.1 (→ Vulkan)
- **Camera:** Camera2 API + NDK `AImageReader` (RAW_SENSOR 10/12/14-bit DNG)
- **Build:** Gradle 8.7 + AGP 8.5.2 + Kotlin DSL
- **Storage:** CinemaDNG sequence / Custom `.mcraw` (ZSTD lossless)
- **CI:** GitHub Actions (JDK 17, NDK 26.1)

---

## 📄 লাইসেন্স

MIT — CineCam Team

---

## 🙏 কৃতজ্ঞতা

MotionCam-এর ওপেন-সোর্স আর্কিটেকচার থেকে অনুপ্রাণিত — আরও আধুনিক NDK পাইপলাইন ও Compose UI দিয়ে পুনর্নির্মিত।
