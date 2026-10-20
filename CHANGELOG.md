# Changelog — CineCam RAW Camera

সব পরিবর্তন বাংলায় নথিভুক্ত করা হবে।

## [Unreleased — Bounded pairing / buffer warm-up] — 2026-10-20
- F23 v68 hardware CRC verified by report (~7ms/written frame); Burst worsened to 3/8 written: 3 poolExhausted rejects + 2 unmatched images/pending results। Paced still 8/8, fsync ~3.29s। Full summary `docs/device-tests/F23-v68-summary.md`।
- Image acquisition এখন <=2 held Images হলে pause/resume করে; আগে তৃতীয় Image নেওয়ার পরে oldest Image বন্ধ করা হতো। Exact timestamp join অপরিবর্তিত, no nearest-timestamp guessing, no extra RAW copy/buffer।
- Pure bounded timestamp pairing helper + tests; report-এ received/pending/evicted timestamp lists ও acquisition deferrals। নতুন policy driver backpressure দিতে পারে; 12s safety limit অপরিবর্তিত।
- Native RAW test-এর existing three buffers pre-touch before capture; `poolPreTouchNs` আলাদা রিপোর্ট। First-copy page faults কমানোর চেষ্টা, actual speedup guaranteed নয়; physical memory pages আগে materialize হয়, allocation/pool cap বাড়ে না, no mlock।
- Hardware/software CRC, CCRAW001 bytes, DNG/preview এবং fsync safety unchanged। Local native suites passed; new Android/device validation pending।

## [Unreleased — Runtime hardware CRC patch] — 2026-10-20
- F23 v67 reports: দুই Paced run-এ 8/8 written, Burst 6/8 written এবং ঠিক 2 poolExhausted rejects। 200/150 MB file sizes frame count-এর সঙ্গে মেলে; original payload এখনও independently verified নয়। বিস্তারিত `docs/device-tests/F23-v67-summary.md`।
- Supported ARM64 CPU-তে runtime HWCAP detection + startup self-test-এর পর IEEE CRC32 hardware backend। Unsupported CPU/self-test failure-এ portable slicing-by-8 fallback; ARM32/x86 baseline অপরিবর্তিত।
- ISA-specific source আলাদাভাবে +crc/no-LTO compile; global +crc নয়, noinline hardware entry; payload copy/precision/checksum/container byte format অপরিবর্তিত।
- Report/sidecar backend এখন actual selection; `hardwareCrcStatus` ও capture-window/stop-drain সময় যোগ। Pool 3, capture limits, Paced/Burst scheduler ও fsync safety অপরিবর্তিত।
- CI native tests x86_64 এবং ARM64; selected/forced-portable/ARM64 simulated self-test-failure paths এবং independent Python reader। নতুন physical-device speed/drop tests pending।

## [Unreleased — RAW pacing/diagnostics patch] — 2026-10-20
- F23 user report: 8/8 Image/result pairs matched, 4 accepted/written, 4 native rejects, clean stop/pool return। 99,878,880-byte size exactly matches four 4080×3060 RAW16 frames plus container overhead; actual payload sample স্বাধীনভাবে পরীক্ষা করা হয়নি।
- CRC32 bytewise implementation replaced by portable slicing-by-8; CCRAW001 bytes/checksum semantics unchanged। Correctness tests cover offsets, short/tail lengths, larger pseudorandom data এবং independent Python reader।
- Native report-এ পৃথক reject counters, pool peak, row-copy timing, CRC/write-syscall/fsync/append/finalize timing। Stop-এর পরেও measurements সংরক্ষণ।
- Optional/default Paced mode: one request in flight; only submit when native capacity >0। Burst mode remains for same-3-slot-pool comparison; allocation size বাড়ানো হয়নি। Paced variable FPS—30fps/sustained-recording দাবি নয়।
- Schema `cinecam-raw-test-2`: mode, submitted/failed requests, capacity wait, per-frame source strides/ingest time/rejection detail এবং app version।
- Pure Kotlin request gate unit tests, C++ counter/timing assertions এবং optional host-only CRC benchmark script।
- Preview/DNG paths ও container layout অপরিবর্তিত; phone performance/rejection cause এখনো নতুন report দিয়ে যাচাই বাকি।

## [Unreleased — Bounded RAW test burst] — 2026-10-19
- ব্যবহারকারীর Camera 0 report-এ REALTIME/BOOTTIME clock ও DNG regression success নিশ্চিত।
- নতুন opt-in RAW test: সর্বোচ্চ 8 request/192 MiB payload, 12-second safety timeout; continuous recording/30fps guarantee নয়।
- Camera2 Image/TotalCaptureResult timestamp matching, bounded pending images/results, native RAW16 row packing ও pool-full drop counters। DNG/viewfinder transform অপরিবর্তিত।
- C++ CCRAW001 container: explicit little-endian headers, প্রতি-frame capture metadata ও payload/header CRC32, footer counts, fsync/partial finalization; `.mcraw` compatibility দাবি নয়।
- BOOTTIME epoch ও monotonic timestamp validation; 3 native buffers, RAW ImageReader সর্বোচ্চ 3 images।
- Android 10+ এ MediaStore Download/CineCam-এ capture.ccraw + metadata.json + report.json export; upfront storage warning ও minimum 650 MiB free-space check।
- Calibration sidecar ও per-frame metadata summary, app-এর RAW test report copy UI। Export successful হলে temporary duplicate payload delete।
- Independent Python reader verifier এবং C++→Python round-trip, padded-row byte equality, checksum corruption/truncation/unfinished-file tests সফল।
- No microphone, compression, MP4 playback, validated DNG conversion বা sustained RAW FPS দাবি। ফোনে burst/stop/resume/export পরীক্ষা বাকি।

## [Unreleased — Phase 4 foundation] — 2026-10-19
- ব্যবহারকারী নিশ্চিত করেছেন preview orientation এখন ঠিক; DNG/manual controls আগেই ফোনে সফল।
- মূলত C++ প্যাচ: pool-aware queue drop/clear/destructor reclamation, locked overflow policy ও metadata-only peek। Rejected frame-এর ownership producer-এর কাছেই থাকে।
- Engine initialize/error-path recursive-lock deadlock ঠিক; ingest/stats/control access serialize; callback control-path join-এর জন্য অপেক্ষা না করে drop করে। Stable singleton address দিয়ে concurrent release-এর dangling pointer এড়ানো হয়েছে।
- Native ও Kotlin default pool 8; 256 MiB allocation budget; invalid dimensions/overflow reject। RAW_SENSOR stride/size validate; silent truncation বন্ধ।
- JNI array/direct-buffer bounds check এবং Kotlin direct-buffer position অনুযায়ী slice। Native copy আছে, end-to-end zero-copy দাবি নয়।
- Writer stop/drain-এ slot ফেরত; stream/finalize error propagation এবং stop-এর পর written/status counters সংরক্ষণ। অসম্পূর্ণ native CinemaDNG/ZSTD/audio অপশন reject।
- C++ SyncTimeline: explicit BOOTTIME epoch, UNKNOWN sensor-clock offset requirement, monotonic video PTS, sample-based audio PTS ও drift calculation। Camera/audio capture বা mux integration এখনো হয়নি।
- Capability report-এ SENSOR_INFO_TIMESTAMP_SOURCE যোগ।
- Real native queue/engine/writer host tests: 5000-frame concurrent transfer, pool reclamation, reinitialize/error deadlock regression, stop/drain, /dev/full I/O failure, layout bounds, 44.1/48kHz clock mapping, 2-hour audio position ও integer overflow। স্থানীয় ASan/UBSan tests সফল।
- এই patch RAW video UI, audio capture, Kelvin WB বা focus peaking চালু করে না। Physical-device recording/sync validation বাকি।

## [Unreleased — Preview orientation patch] — 2026-10-19
- ব্যবহারকারীর 24.64-second screen recording-এর বিভিন্ন সময়ের frame পরীক্ষা: UI সোজা থাকলেও scene পাশ ফিরে আছে।
- TextureView producer-এর rotation আবার প্রয়োগ করা বন্ধ; sensor orientation এখন natural content aspect ratio নির্ধারণ করে, display rotation আলাদাভাবে counter-rotate হয়।
- DisplayListener দিয়ে 180° display change ও view size/control-panel layout পরিবর্তনে transform refresh।
- Preview geometry regression tests: F23 portrait identity, reverse portrait, landscape, centering ও aspect ratio।
- RAW/DNG capture ও EXIF orientation অপরিবর্তিত। Physical-device confirmation বাকি।
- Review শেষে uploaded video ও extracted frames local workspace থেকে সরানো হয়েছে; Git history rewrite করা হয়নি।

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
