# CineCam — Android RAW Camera (পরীক্ষামূলক)

Android-এর জন্য Camera2 + Kotlin UI + C++17/NDK-ভিত্তিক RAW camera তৈরির প্রকল্প। MotionCam-এর বিকল্প তৈরির লক্ষ্য আছে; বর্তমানে MotionCam-এর সমমানের কার্যকারিতা বা `.mcraw` compatibility দাবি করা হচ্ছে না।

## বর্তমান অবস্থা

আগের ব্রাঞ্চ থেকে Kotlin DSL, Compose UI, Camera2 controller, JNI, C++ buffer/writer/renderer-এর প্রাথমিক কোড এসেছে। **ফাইল থাকা মানেই ফিচার সম্পূর্ণ বা ফোনে পরীক্ষিত নয়।** পূর্ববর্তী `main` commit-এর সর্বশেষ GitHub Actions run ব্যর্থ ছিল। নতুন প্যাচের APK build এখনো যাচাই হয়নি।

এই প্যাচে:
- CI-তে C++ sanitizer tests, debug/unsigned release build এবং diagnostic artifacts।
- সব build job সফল হওয়ার পরেই পরীক্ষামূলক GitHub Release।
- CI থেকে অন্য ব্রাঞ্চে log push/force-push ও স্বয়ংক্রিয় issue তৈরি বাদ।
- C++ memory pool validation, allocation failure ও duplicate release সুরক্ষা।
- `RawFrame` move assignment যোগ; queue-তে move assignment ব্যবহার হলেও এটি আগে অনুপস্থিত ছিল।

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

1. **বেস ও CI স্থিতিশীল করা** — এই প্যাচ; remote build validation বাকি।
2. Permissions, lifecycle-safe Camera2 preview, capability report; ফোনে পরীক্ষা।
3. ডিভাইসের সীমা অনুযায়ী manual controls ও metadata-সহ DNG still capture।
4. C++ buffer ownership/backpressure, timestamp ও audio sync framework।
5. পরীক্ষিত RAW video container, storage throughput, recovery এবং compression।
6. স্থায়ী signing, APK install ও physical-device acceptance tests।

RAW_SENSOR, RAW10/RAW12 stream format, sensor bit depth এবং নির্দিষ্ট resolution-এ 24/30/60fps ভিন্ন বিষয়। ক্যামেরার advertised capabilities ও বাস্তব পরীক্ষা ছাড়া কোনোটি নিশ্চিত নয়।
