# Changelog — CineCam RAW Camera

সব পরিবর্তন বাংলায় নথিভুক্ত করা হবে।

## [v0.1.0-alpha] - 2026-10-19
### ধাপ ১ — প্রজেক্ট ইনিশিয়ালাইজেশন ও বেস আর্কিটেকচার
#### ✨ নতুন ফিচার
- **NDK/C++ Core Engine** — MotionCam অনুপ্রাণিত আর্কিটেকচার:
  - `MemoryPool` (memory_pool.h/cpp): 4096-aligned zero-copy বাফার পুল, 60fps হট-পাথে malloc নেই
  - `RingBuffer` (ring_buffer.h/cpp): SPSC Lock-Free Circular Queue, DropOldest/DropNewest পলিসি
  - `RawWriter` (raw_writer.h/cpp): High-speed Direct-I/O friendly writer, MCRAW + CinemaDNG সাপোর্ট, ZSTD লেভেল ৩
  - `CineRawEngine` (cine_raw_engine.h/cpp): Central JNI controller, singleton lifecycle
  - `GlPreviewRenderer` (gl_preview_renderer.h/cpp): OpenGL ES 3.1 debayer + tonemap, Vulkan-রেডি শেডার
- **Kotlin Camera Layer**:
  - `CameraCapabilities.kt`: RAW_SENSOR / RAW10 / RAW12 সাপোর্ট চেকার, hardware level যাচাই
  - `CameraController.kt`: Camera2 device/session, manual ISO/exposure/focus, RAW ImageReader
  - `NativeRawEngine.kt`: JNI bridge, ByteArray + DirectByteBuffer zero-copy path
- **Jetpack Compose UI**:
  - `CameraScreen.kt`: Capability overlay, TextureView preview placeholder, Engine stats
  - `ManualControls.kt`: ISO/Shutter/Focus/WB sliders, FPS selector (24/30/60), Record button
  - `Theme.kt`: CineCam Dark cinema palette
- **Build System**:
  - Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`), Gradle 8.7, AGP 8.5.2, NDK 26.1, C++17
  - CMake 3.22.1, ABIs: arm64-v8a, armeabi-v7a, x86_64
- **CI/CD**:
  - `.github/workflows/build-and-release.yml`: JDK 17 + NDK + CMake সেটআপ, auto APK build, artifact upload, GitHub Release with tag `v0.1.<run>-<sha>`

#### 🔧 ফিক্স
- Initial commit baseline স্থাপন

#### 📝 টেকনিক্যাল নোট
- `minSdk 26`, `targetSdk 34`, `compileSdk 34`
- Package: `com.cinecam.app` (applicationId)
- Native lib: `libcinccam_raw.so` (cinecam_raw)

---

## পরবর্তী পরিকল্পনা
- **ধাপ ২**: Live preview, permissions, device capability UI
- **ধাপ ৩**: Manual controls + Still RAW DNG capture
- **ধাপ ৪**: NDK frame buffer + Audio sync
- **ধাপ ৫**: High-speed RAW video recording
