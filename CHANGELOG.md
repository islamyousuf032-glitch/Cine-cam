# Changelog — CineCam RAW Camera

সব পরিবর্তন বাংলায় নথিভুক্ত করা হবে।

## [Unreleased — Phase 3] — 2026-10-19
- Galaxy F23-এর screenshot/report-এ Camera 0 preview ও RAW/manual support পাওয়া গেছে; rotation/resume/stress পরীক্ষা এখনো আলাদা।
- Camera2 RAW_SENSOR ImageReader সহ still session, preview-only fallback এবং একবারে একটি capture।
- SENSOR_TIMESTAMP দিয়ে Image ও TotalCaptureResult মেলানো ছাড়া DNG লেখা হবে না; timeout ও failure cleanup।
- Android DngCreator দিয়ে calibration/capture metadata-সহ DNG; পূর্বের native raw-dump writer ব্যবহার করা হয়নি।
- Android 10+ MediaStore pending entry → write → publish; ব্যর্থ হলে entry delete। Android 8/9-এ app-specific Pictures fallback।
- ISO/shutter manual toggle, logarithmic shutter slider, supported focus distance clamp ও autofocus toggle। AWB এখনো Auto; Kelvin calibration/peaking পরের patch।
- Disk write আলাদা single worker-এ; সর্বোচ্চ একটি still in flight; reader close লেখার পরে serialized।
- Exposure slider ও EXIF rotation rules-এর unit tests।
- RAW video এখনো চালু নয়; physical-device DNG decode পরীক্ষা বাকি।

## [Unreleased — Phase 2] — 2026-10-19
- বাস্তব Camera2 TextureView preview যুক্ত; sensor/display rotation ও aspect-fit transform।
- Camera handler thread-এ open/session/close; generation token দিয়ে stale callbacks বাতিল; surface detach-এর পরে texture release।
- Lifecycle pause-এ camera বন্ধ ও resume-এ reopen; permission retry/Settings path।
- Preview-তে শুধু CAMERA permission; অপ্রয়োজনীয় audio/media/storage permissions সরানো।
- আগের idle preview-তে 60-frame RAW pool allocation ও অসম্পূর্ণ recording controls সরানো; capture ভবিষ্যৎ ধাপে আসবে।
- কপি করা যায় এমন capability report: প্রতি-camera RAW formats, sizes, minFrameDuration, ISO/exposure ও AE ranges।
- Hardware enum numerical ranking ভুল ঠিক; LEGACY/EXTERNAL-কে FULL ধরে নেওয়া বন্ধ।
- Camera rules unit tests এবং Galaxy F23 physical-device test plan যোগ।
- এখনো physical-device preview/rotation/performance পরীক্ষা হয়নি।

## [Unreleased] — 2026-10-19
### ধাপ ১ পুনঃপরীক্ষা ও ভিত্তি মেরামত
- ব্যর্থ build-এর পর Release তৈরি বন্ধ; tests → build → release dependency chain।
- SDK/NDK install ব্যর্থতা আর লুকানো হবে না; pinned SDK/NDK ব্যবহার।
- diagnostic branch push/force-push ও automatic issue spam সরানো হয়েছে।
- debug-signed APK ও unsigned release APK আলাদাভাবে চিহ্নিত; Release-এ শুধু installable debug APK এবং SHA256।
- C++ MemoryPool: invalid dimensions/overflow reject, allocation failure-এ cleanup, double release প্রতিরোধ।
- RawFrame move assignment যোগ এবং moved-from slot reset; আগের queue assignment-এর compile blocker ঠিক।
- Linux host native tests: alignment, exhaustion, invalid input, duplicate release, multithreaded reuse, frame moves; ASan/UBSan সহ।
- README ও architecture-এ বাস্তব অবস্থা লেখা হয়েছে: lock-free, end-to-end zero-copy, ZSTD এবং device-verified recording দাবি প্রত্যাহার।

- প্রথম remote run-এ native tests সফল; setup-android ধাপ ব্যর্থ হওয়ায় ubuntu-24.04 runner-এর preinstalled SDK ব্যবহার করা হলো।

### যাচাই
- স্থানীয় `./scripts/test-native.sh`: সফল।
- স্থানীয় Android build: চালানো যায়নি (JDK/SDK অনুপস্থিত)।
- নতুন workflow remote run `39268140818`: সফল; `v0.1.58-alpha-edf19e7` prerelease ও APK প্রকাশিত।
- Physical-device tests: বাকি।

> নিচের পুরোনো v0.1.0-alpha বিবরণ ঐতিহাসিক দাবি; যাচাইকৃত feature list নয়। বর্তমান সীমাবদ্ধতার জন্য README ও docs/ARCHITECTURE.md দেখুন।

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
