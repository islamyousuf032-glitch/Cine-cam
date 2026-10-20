# CineCam — Android RAW Camera (পরীক্ষামূলক)

Android-এর জন্য Camera2 + Kotlin UI + C++17/NDK-ভিত্তিক RAW camera তৈরির প্রকল্প। MotionCam-এর বিকল্প তৈরির লক্ষ্য আছে; বর্তমানে MotionCam-এর সমমানের কার্যকারিতা বা `.mcraw` compatibility দাবি করা হচ্ছে না।

## বর্তমান অবস্থা

আগের ব্রাঞ্চ থেকে Kotlin DSL, Compose UI, Camera2 controller, JNI, C++ buffer/writer/renderer-এর প্রাথমিক কোড এসেছে। **ফাইল থাকা মানেই ফিচার সম্পূর্ণ বা ফোনে পরীক্ষিত নয়।** পূর্ববর্তী `main` commit-এর সর্বশেষ GitHub Actions run ব্যর্থ ছিল। নতুন patch `edf19e7`-এর remote run `39268140818` সফল: native tests, Android build এবং prerelease publishing সম্পন্ন। ফোনে কার্যকারিতা পরীক্ষা বাকি।

এই প্যাচে:
- CI-তে C++ sanitizer tests, debug/unsigned release build এবং diagnostic artifacts।
- সব build job সফল হওয়ার পরেই পরীক্ষামূলক GitHub Release।
- CI থেকে অন্য ব্রাঞ্চে log push/force-push ও স্বয়ংক্রিয় issue তৈরি বাদ।
- C++ memory pool validation, allocation failure ও duplicate release সুরক্ষা।
- `RawFrame` move assignment যোগ; queue-তে move assignment ব্যবহার হলেও এটি আগে অনুপস্থিত ছিল।

## ধাপ ২ — পরীক্ষার APK প্রস্তুত

Commit `bbc340b`, CI run `39270646957` সফল। Camera2 live preview integration, permission/settings UI, pause/resume handling এবং কপি করা যায় এমন capability report যুক্ত হয়েছে। Galaxy F23-তে install/preview/rotation validation এখনো বাকি।

- Release: [v0.1.59-alpha-bbc340b](https://github.com/islamyousuf032-glitch/Cine-cam/releases/tag/v0.1.59-alpha-bbc340b)
- এই ধাপে RAW engine allocation/recording চালু হয় না; আগের non-functional controls সরানো হয়েছে।
- Manual controls ও DNG পরবর্তী ধাপ; custom C++ preview renderer এখনো integrated নয়।
- ফোনে পরীক্ষার নির্দেশনা: `docs/GALAXY_F23_TEST_PLAN.md`।

## ধাপ ৩ — DNG still ও manual controls পরীক্ষার build

[v0.1.62-alpha-d62f4f8](https://github.com/islamyousuf032-glitch/Cine-cam/releases/tag/v0.1.62-alpha-d62f4f8): ISO/shutter/focus controls, matching sensor timestamps-সহ Android DngCreator export এবং Android 14-এ `DCIM/CineCam` MediaStore save। CI run `39274480939` সফল। ফোনে DNG decode যাচাই বাকি; WB Auto, Kelvin/focus peaking ও RAW video এখনো বাকি। পরীক্ষা: `docs/DNG_STILL_TEST_PLAN.md`।

## ধাপ ৪ — C++ pipeline foundation

Queue/pool reclamation, bounded 256 MiB allocation (default 8 buffers), engine lifecycle/ingest validation, writer stop/error handling এবং C++ audio/video timestamp mapping framework যুক্ত হয়েছে। `./scripts/test-native.sh` এখন real queue/engine/writer ও clock framework-কে host-এ ASan/UBSan দিয়ে পরীক্ষা করে।

এটি এখনো **RAW video/audio recording চালু করে না**। SyncTimeline standalone framework; actual audio capture, video container validation, JNI integration এবং physical-device sync/throughput tests বাকি। Preview/DNG capture আগের কার্যকর platform path-এই আছে। বিস্তারিত: `docs/PHASE4_PIPELINE.md`।

## সীমিত RAW test burst

নতুন opt-in **RAW test • সর্বোচ্চ 8 frames** Camera2 থেকে আসল RAW plane ও matching metadata C++ writer-এ পাঠায়। এটি একটি সীমিত diagnostic burst, **চলমান RAW video/MP4 recording নয়**। প্রায় 200 MB ফাইল ও export copy-এর জন্য 650 MiB free storage প্রয়োজন। ফোনে কার্যকারিতা পরীক্ষা বাকি।

Android 14-এ output: `Download/CineCam/<session>/capture.ccraw`, `metadata.json`, `report.json`। `.ccraw` একটি নথিভুক্ত experimental format, MotionCam `.mcraw` নয়। যাচাই:

```bash
python3 scripts/inspect_ccraw.py capture.ccraw
```

Format spec ও test instructions: `docs/CCRAW_FORMAT.md`, `docs/RAW_BURST_TEST_PLAN.md`। Audio, compression, normal video playback ও DNG sequence conversion এখনো নেই।

## RAW test: Paced / Burst তুলনা

সর্বশেষ diagnostic patch-এ warning dialog-এর **Paced mode** switch দিয়ে mode নির্বাচন করা যায়। Paced mode একটি matching Image/result সম্পূর্ণ হওয়ার পর এবং native capacity ফাঁকা থাকলে পরের request দেয়; Burst আগের মতো দ্রুত batch দেয়। দুটিতেই native pool 3 slots, সর্বোচ্চ 8 frames ও একই byte limits। Paced mode-এর সম্পূর্ণ delivery-কে fixed/sustained FPS বলা যাবে না।

নতুন report-এ native drop reasons ও CRC/write/fsync/copy timing আছে। CRC32 slicing-by-8-এ optimized; `.ccraw` layout ও পুরোনো reader compatibility অপরিবর্তিত। Optional host-only benchmark: `./scripts/benchmark-crc.sh`। নতুন F23 performance এখনো যাচাই বাকি; পরীক্ষা: `docs/RAW_BURST_TEST_PLAN.md`।

## ডিরেক্টরি

```text
.github/workflows/build-and-release.yml  # পরীক্ষা → build → prerelease
app/build.gradle.kts                    # Android + Compose + NDK configuration
app/src/main/
  AndroidManifest.xml
  cpp/
    CMakeLists.txt
    include/                            # core types, pool, queue, writer, renderer
    src/                                # C++ implementations এবং JNI
  java/com/cinecam/
    app/                                # Application, Activity
    camera/                             # Camera2 এবং capability checker
    engine/                             # Kotlin/JNI bridge
    ui/                                 # Compose screens ও controls
    util/                               # permission utilities
  res/
tests/native/                           # host C++ regression tests
scripts/test-native.sh                  # ASan + UBSan test runner
docs/ARCHITECTURE.md                     # সীমাবদ্ধতা ও architecture
docs/PROJECT_STATUS.md                   # পরবর্তী কাজ ও যাচাইয়ের অবস্থা
CHANGELOG.md
```

## স্থানীয় পরীক্ষা

C++ core regression (Android SDK প্রয়োজন নেই):

```bash
./scripts/test-native.sh
```

প্রয়োজন: Linux, Bash, g++, pthread, AddressSanitizer ও UndefinedBehaviorSanitizer। এটি memory pool ও frame move semantics পরীক্ষা করে; পুরো camera pipeline নয়।

APK build:

```bash
# JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

এই Arena পরিবেশে JDK/Android SDK নেই এবং Android dependency hosts-এ outbound access সীমিত; এখানে APK build সম্পন্ন হয়েছে বলে দাবি করা হচ্ছে না।

## CI/CD ও APK

`main`, `arena/**` push, `main` pull request ও manual dispatch-এ পরীক্ষা/build চলবে। `v*` tag push-ও সমর্থিত। Pull request থেকে Release হবে না। অন্য trigger-এ সব পরীক্ষা/build সফল হলে commit-নির্দিষ্ট alpha prerelease হবে। স্বয়ংক্রিয় branch merge/close নেই।

- ইনস্টলযোগ্য: `CineCam-debug.apk` (debug signed)।
- `app-release-unsigned.apk`: Actions artifact; signing ছাড়া ইনস্টলযোগ্য নয়।
- SHA256 checksum Release-এ থাকবে।
- Debug key runner-ভেদে বদলাতে পারে; আগের debug APK-এর ওপর install না হলে uninstall প্রয়োজন হতে পারে। App-private data আগে সংরক্ষণ করুন। স্থায়ী signing key ও production release আলাদা কাজ।
- Build log ও reports Actions artifacts-এ থাকবে, repository branch-এ নয়।

[GitHub Releases](https://github.com/islamyousuf032-glitch/Cine-cam/releases)

## কাজের ক্রম

1. **বেস ও CI স্থিতিশীল করা** — remote build validation সফল; physical-device validation বাকি।
2. Permissions, lifecycle-safe Camera2 preview, capability report; ফোনে পরীক্ষা।
3. ডিভাইসের সীমা অনুযায়ী manual controls ও metadata-সহ DNG still capture।
4. C++ buffer ownership/backpressure, timestamp ও audio sync framework।
5. পরীক্ষিত RAW video container, storage throughput, recovery এবং compression।
6. স্থায়ী signing, APK install ও physical-device acceptance tests।

RAW_SENSOR, RAW10/RAW12 stream format, sensor bit depth এবং নির্দিষ্ট resolution-এ 24/30/60fps ভিন্ন বিষয়। ক্যামেরার advertised capabilities ও বাস্তব পরীক্ষা ছাড়া কোনোটি নিশ্চিত নয়।

### Runtime hardware CRC follow-up

নতুন patch supported ARM64-এ HWCAP+self-test gated IEEE CRC32 acceleration দেয়; unsupported CPU-তে slicing-by-8 fallback। Actual backend report ও metadata-তে দেখা যায়। v67 paired phone result: Paced 8/8, Burst 6/8 এবং 2 poolExhausted—`docs/device-tests/F23-v67-summary.md`। নতুন hardware-backend device performance এখনও যাচাই বাকি; checksum/fsync বন্ধ বা pool বাড়ানো হয়নি।

### Internal staging comparison

API29+ RAW test-এ Internal staging switch দিয়ে private filesDir বনাম আগের app-specific external spool তুলনা করা যায়; output এখনও Download/CineCam-এ export হয়। Path test-এর জন্য reservation default OFF। App-private temporary capture নিজে user-visible video নয়; export failure হলে app data clear/uninstall করবেন না (retained private files হারাবে)। Details: `docs/RAW_BURST_TEST_PLAN.md`।

### Saved RAW readback + first-frame DNG

RAW test dialog-এর নতুন sample switch saved `.ccraw` Java CRC32 দিয়ে streaming verify করে এবং প্রথম **saved** payload থেকে `first_frame.dng` বানায় (matching Camera2 metadata + platform DngCreator)। এটি নতুন live still নয়; output একই Download test folder-এ। Extra readback/DNG/export সময় লাগে; performance-only test-এ OFF করা যায়। Calibration/visual correctness ফোনের DNG viewer দিয়ে যাচাই বাকি; playable RAW video নয়।
