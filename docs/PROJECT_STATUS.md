# কাজের ধারাবাহিকতা — 2026-10-19

## সেশন ও workflow

- এই সেশনের ব্রাঞ্চ: `arena/30298c6b-cine-cam`। এখানেই কাজ রাখতে হবে।
- baseline: `0df9156` (আগের PR #37 merge)।
- ব্যবহারকারীর স্পষ্ট অনুমতি ছাড়া PR merge/close নয়।
- প্রথম foundation patch commit `081820d` এই ব্রাঞ্চে push হয়েছে; merge/close করা হয়নি।
- Remote run `39267934860`: native tests সফল; setup-android ধাপে ব্যর্থ। Android compilation শুরু হয়নি।
- SDK patch `edf19e7`: ubuntu-24.04 ও preinstalled SDK ব্যবহার করে remote run `39268140818` সম্পূর্ণ সফল।
- Native sanitizer tests, Android build/test/lint command এবং prerelease publishing সফল।
- Release: `v0.1.58-alpha-edf19e7`; assets: `CineCam-debug.apk` (14,593,400 bytes), `SHA256SUMS.txt`।
- ফোনে install, preview ও RAW capture পরীক্ষা এখনো হয়নি।
- সম্পূর্ণ অ্যাপ বা ধাপ ১-এর APK build যাচাই সম্পন্ন বলা যাবে না।

## পরীক্ষা

- `./scripts/test-native.sh` ASan/UBSan সহ স্থানীয়ভাবে সফল।
- এই পরিবেশে java/cmake/Android SDK নেই; g++ আছে।
- GitHub run list-এ baseline main-এর run `39255730366` failed।
- `gh run view --log-failed`-এর log download restricted host-এর কারণে সম্ভব হয়নি; failure-এর নির্দিষ্ট কারণ জানা যায়নি।

## পরবর্তী কাজ

1. CI foundation যাচাই সফল। পরের code patch-ও এই ব্রাঞ্চে push করতে হবে; merge নয়।
2. CameraController ও CameraScreen lifecycle/permissions audit। RAW feature চালুর আগে preview স্থিতিশীল করা।
3. ফোনের model, Android version, Camera2 capability report সংগ্রহ। RAW-capable rear camera ও supported stream combination নির্বাচন।
4. C++ queue drop/clear ownership, engine stop/drain/release ordering, JNI bounds এবং writer error propagation ঠিক ও regression test যোগ।
5. সঠিক metadata-সহ still DNG; independent decoder দিয়ে validation।
6. তারপর bounded RAW recording, compression, audio sync, thermal/storage stress tests।

## acceptance checklist

- [x] CI Android build/test/lint command সফল (বিদ্যমান Gradle config-এ lint abortOnError=false; এটি lint-error-free নিশ্চয়তা নয়)
- [ ] Release APK download ও ফোনে install
- [ ] Permission deny/grant, background/resume, rotation-এ preview নিরাপদ
- [ ] বাস্তব RAW formats/resolutions/FPS report
- [ ] ISO/shutter/focus সীমা সঠিকভাবে প্রয়োগ
- [ ] DNG decoder round-trip ও color metadata validation
- [ ] recording stop/drain, storage-full ও dropped-frame report
- [ ] long-record thermal/RAM/throughput পরীক্ষা
- [ ] stable signing ও update-install পরীক্ষা

## ধাপ ২ প্যাচ

- ব্যবহারকারীর test device: Galaxy F23, 6GB RAM, Android 14, Snapdragon 750G; user-reported Camera2 LEVEL_3।
- Camera2 preview, lifecycle/texture ownership, permission UI ও capability report যুক্ত।
- Idle preview-তে native RAW engine initialize হয় না; false recording UI সরানো হয়েছে।
- Unit tests: hardware capability classification ও rotation rules।
- `docs/GALAXY_F23_TEST_PLAN.md` অনুসারে ফোনে পরীক্ষার ফল দরকার।
- Remote run `39270646957` সফল: native sanitizer tests, Android unit tests/build command এবং APK prerelease publishing।
- Commit `bbc340b`, Release `v0.1.59-alpha-bbc340b`; physical-device validation বাকি।

## ধাপ ৩ — manual controls + DNG still

User-provided Galaxy F23 report:
- Camera 0: LEVEL_3, RAW_SENSOR/RAW10 true, RAW12 false, manual sensor/post true।
- RAW 4080×3060, advertised minFrameDuration 33333333 ns (~30fps theoretical)।
- ISO 20–3840, exposure 77100–647363350 ns। Sensor precision এখনো অজানা।
- User screenshot-এ Camera 0-এর live preview দেখা যায়। Rotation/resume/stress pass বলা হয়নি।

এই patch-এ platform DngCreator দিয়ে still export, timestamp pairing, manual ISO/shutter/focus, bounded worker ও MediaStore যুক্ত। Android metadata/session/storage integration Kotlin-এ; performance-critical video core C++-এই থাকবে। WB Auto, focus peaking ও video পরবর্তী কাজ। Remote run `39274480939` সফল: native tests, Android unit tests/build ও prerelease publishing। Release `v0.1.62-alpha-d62f4f8`, commit `d62f4f8`। ফোনে DNG capture/decoder validation বাকি।

অতিরিক্ত fixes: optional focus local snapshot এবং request serial দিয়ে late callback isolation। পরবর্তী কাজ: ব্যবহারকারীর DNG test result, তারপর WB/focus peaking ও C++ video pipeline ownership audit।

## Preview orientation patch

- User confirms DNG capture/save ও manual controls কাজ করে।
- User-uploaded screen recording `Screen_Recording_20261019_212605_CineCam RAW.mp4` পাওয়া গেছে main upload commit `eb8c5dd`-এ; duration 24.64s, HEVC 1080×2408।
- Multiple extracted frames-এ portrait UI-তে sideways scene দেখা গেছে। TextureView producer-এর sensor orientation পুনরায় প্রয়োগ সরানো হয়েছে; EXIF path untouched।
- Temporary video, frames এবং review tooling মুছে ফেলা হয়েছে। Git ইতিহাসে থাকা original blob বা main branch remote deletion দাবি করা হয়নি।
- Geometry unit tests যোগ; নতুন CI ও physical-device confirmation বাকি।
