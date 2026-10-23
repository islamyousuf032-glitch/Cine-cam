# কাজের ধারাবাহিকতা — 2026-10-22

## বর্তমান অগ্রাধিকার

Manage clips তিন section-এ (ছবি / lossless archive / ফাইল ও রিপোর্ট); DNG preview-এর পাশাপাশি verified CCRAW frame fallback। এরপর DNG sequence review-এর playback/scrub, তারপর বাস্তব recording/render workflow। Storage micro-optimization একা পরবর্তী milestone নয়। ইতিহাস নিচে আছে; সর্বশেষ delivery/status শেষে দেখুন।

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

- Patch commit `683690b`; main upload ancestry reconciled on session branch at `f121642` so diff against main explicitly deletes the recording. Main itself remains unchanged; no GitHub PR merge/close or history rewrite.
- Release `v0.1.64-alpha-f121642` with `CineCam-debug.apk` verified through GitHub release API. Run/check API still reported queued despite release existing; do not claim independently confirmed remote test completion. Local native sanitizer tests passed; device confirmation pending.

## ধাপ ৪ foundation — buffer safety ও timestamp framework

- User confirms patched viewfinder এখন ঠিক কাজ করছে। Preview/DNG/manual path এই patch-এ বদলানো হয়নি।
- C++ ring buffer pool reclamation, engine lifecycle/locking, bounded allocation, ingestion validation ও writer stop/I/O-error paths মেরামত করা হয়েছে।
- Native RAW engine এই build-এ CameraController-এর recording path-এর সঙ্গে যুক্ত নয়। শুধুমাত্র host tests ছোট synthetic RAW frames-এ legacy experimental uncompressed dump চালায়; MotionCam compatibility দাবি নয়।
- 256 MiB pool cap/default 8; actual padded row layouts capacity ছাড়ালে reject হবে। Stride-aware allocation/copy integration পরের video-ingestion patch-এ লাগবে।
- SyncTimeline C++ framework: video timestamp source REALTIME→BOOTTIME, UNKNOWN→explicit measured offset required; audio needs AudioTimestamp TIMEBASE_BOOTTIME frame-position anchor। এখনো microphone capture/JNI sync plumbing/mux/resampling নেই।
- `./scripts/test-native.sh` স্থানীয়ভাবে ASan/UBSan সহ সফল; real queue/engine/writer tests এবং 5000-frame concurrent transfer যুক্ত।
- RAW video UI, validated container/reader, actual frame metadata matching, compression, audio integration, thermal/storage benchmark, WB Kelvin ও focus peaking বাকি।
- Commit `294f701`; Release `v0.1.65-alpha-294f701`-এ APK (14,658,716 bytes) ও checksum asset এবং target commit GitHub API দিয়ে যাচাই হয়েছে।
- Run `39284029694`-এর status API release প্রকাশের পরও queued দেখিয়েছে; remote test conclusion আলাদাভাবে নিশ্চিত নয়। Local ASan/UBSan tests সফল। ফোনে recording/sync test হয়নি।

## Bounded RAW test burst patch

User confirms Camera 0 REALTIME/BOOTTIME timestamps and existing DNG/manual path still works। New opt-in finite captureBurst (up to 8 requests / 192 MiB payload; 12-second timeout) feeds matched RAW_SENSOR planes to C++ with row-stride packing, 3-slot pool, bounded pending Image/result maps।

New C++ CCRAW001 writer and independent streaming Python reader document/validate little-endian metadata, frame/header CRC and completion footer। It is NOT MotionCam MCRAW, MP4 or a calibrated DNG video sequence। Native ingestion rejects old/duplicate timestamps before they can poison the file।

Android MediaStore Downloads export carries calibration metadata.json and diagnostic report.json। Successful export removes private duplicate; failed export retains temporary data and error path। Preview transform and still DngCreator path unchanged (ImageReader maxImages is now 3)।

Local native ASan/UBSan and independent reader tests passed। Remote run `39372351030` সফল: native tests, Android test/build এবং prerelease publishing। Release `v0.1.66-alpha-ec20db2`, commit `ec20db2`। Physical-device RAW burst/export validation pending। Next: user RAW test report, actual dropped-frame/write throughput analysis; then sustained recording/validated DNG conversion/audio/compression in separate patches।

## RAW pacing/diagnostics patch — 2026-10-20

F23 user test of v0.1.66: images/results/matched=8, accepted/written=4, rejected=4, no unmatched frames; writerStatus=0, nativeStopOk=true, poolAvailable=3, ringSize=0। File bytes=99,878,880 matches 4×(4080×3060×2 + 96)+96; screenshot shows three exported files। Payload integrity/visual decode not independently verified from original file।

Old report lacks per-stage time/reject reason, so do not state CRC or storage is proven bottleneck。 New patch replaces bytewise CRC with slicing-by-8 (same format), exposes per-reason/per-stage timing, and offers Paced/default vs Burst mode with unchanged 3 native slots。 Paced is one-request-at-a-time with capacity wait, not fixed FPS or sustained-recording success।

Local native ASan/UBSan + independent reader tests passed। Host-only O3 CRC sample (5×24,969,600 bytes): old ~304ms, new ~72ms; not phone performance or a gating threshold। Kotlin request gate tests, Android unit/lint/debug+release builds এবং publish CI run 39375263600/67 সফল। Release `v0.1.67-alpha-24a8b7e` published; নতুন physical-device tests pending। Next: compare two new F23 reports, then decide compression/format/camera pipeline changes from actual timings।

## v67 paired device test → runtime CRC patch

User supplied two Paced reports (8/8, 0 drop, pool peak1) and one Burst report (6/8, 2 poolExhausted, pool peak3); clean stop/all slots returned in all three। Detailed timings/exact sizes in `docs/device-tests/F23-v67-summary.md`। No original binary independently verified। Burst CRC mean ~50ms and append mean ~105ms per written frame; one Paced fsync ~2.55s। Do not sum overlapping phase timings or call old/new samples controlled speedup।

Next patch uses isolated ARM64 CRC instructions behind HWCAP+self-test, fallback on other CPUs/failure, actual backend reporting, unchanged pool/format/fsync। Local x86 selected+forced fallback sanitizer/reader tests passed। CI run 39378149582/68: x86_64 + ARM64 native sanitizer suites, Android unit/lint/debug/release এবং publish সব successful। APK `v0.1.68-alpha-dfa29ed` published (14,740,632 bytes); physical F23 backend/speed/drop tests pending। ARM runner logs retrieval hit network EOF; exact hardware backend log line independently read হয়নি, job success verified।

## v68 result → bounded pairing/pre-touch patch

Device reports prove hardware backend selected/self-test passed: CRC ~7ms/frame। Paced 8/8; Burst3/8 with 3 poolExhausted + 2 unmatched images/pending results (not just native drops)। Burst row-copy max99.56ms, write total313.85ms/3 frames; Paced fsync3.289s। No overall performance success or 30fps claim; original binary not independently verified। Full evidence: `docs/device-tests/F23-v68-summary.md`。

Patch replaces acquire-then-evict with acquisition backpressure at2 held Images, resumes on metadata callbacks, adds exact timestamp diagnostics/testable bounded join। Three native buffers pre-touched once before capture (measured) to move possible page faults out of callback; same allocation cap, earlier resident pages, not mlock/performance guarantee। Hardware CRC/format/fsync/limits unchanged। Local native tests passed; Android CI/device check pending。

### v69 delivery confirmed
CI `39384711171`/69 succeeded: ARM64/x86_64 sanitizers, Android unit tests (including timestamp pairing), lint/debug/release এবং publish। Release `v0.1.69-alpha-dabef97`, APK 14,740,632 bytes, verified uploaded asset। Physical-device matching/pre-touch benefit এবং sustained throughput যাচাই বাকি।

## v69 device result → file reservation patch

Evidence in `docs/device-tests/F23-v69-summary.md`: both modes matched8/8, no unmatched; Burst6written/2poolExhausted, Paced8written. Pre-touch ~40–42ms, copy max12–16ms. Writes ~3.5–3.8s, append max~1.75s; Paced finalize2.62s vs fsync1.13s needs phase breakdown. No original-file independent validation/sustained FPS proof.

Patch adds optional/default-ON bounded posix_fallocate before capture (unsupported fallback, genuine errors fail), exact-EOF truncation before existing fsync/close/rename, allocation/max-write/truncate/close/rename stats and UI A/B switch. Pool3, pairing/CRC/format/capture budgets unchanged. Local native reserve/plain/error/reader tests passed; Android CI and device benefit pending.

### v70 delivery confirmed
CI `39418510595`/70 successful: ARM64/x86_64 sanitizers, Android unit/lint/debug/release এবং publish। Verified release `v0.1.70-alpha-6210de4`, APK 14,740,636 bytes। Reservation ON/OFF F23 comparison ও original RAW independent validation pending; no sustained FPS/performance success claim।

## v70 results → internal staging patch

Detailed evidence `docs/device-tests/F23-v70-summary.md`: reserve ON actually unsupported95 and fell back; both regular paths5/6written, matched8/8, no unmatched, only poolExhausted. write total3.2–3.7s/fsync4.7–5.5s; close/rename <1ms. No claim reservation improved performance or FUSE proven cause.

Patch: API29+ private internal spool default, external staging A/B switch, unchanged final Downloads export/native pool/format/CRC/fsync; reservation defaults OFF to isolate variable. Check both source/export free space; API26–28 keep original external flow. Report actual location and export data-copy measurements; source retained on error, private recovery not exposed via file manager and uninstall/clear-data loses it (no automatic retry UI yet). Local native tests pass; new policy tests added; Android CI/device results pending.

### v71 delivery confirmed
CI `39427028753`/71 successful: ARM64/x86_64 native sanitizers, Android unit tests (storage policy included), lint/debug/release এবং publication। Verified release `v0.1.71-alpha-f19bafa`, APK14,740,636 bytes। Internal vs external staging phone comparison pending; no storage/FPS improvement claimed yet।

## v71 result → saved RAW verification/DNG milestone

Evidence `docs/device-tests/F23-v71-summary.md`: both staging paths6written/2poolExhausted, matched8/8, deferrals3/5/no unmatched. No staging win/sustained FPS proof. Original RAW still not independently checked.

New optional/default post-stop Java CRC32 streaming verifier (64KiB), exact saved-container checks, then first saved payload to Android DngCreator using retained exact first accepted result/calibration/orientation. Same native bytes/pool/fsync; separate verification/sample timing and error reporting, extra read + ~25MB DNG + export cost. SDK allocation not asserted bounded by verifier size. Device CRC success/visual DNG/calibration/endurance pending; native local suites passed, new Kotlin tests/Android CI pending.

### v72 delivery confirmed
CI `39435722437`/72 successful: ARM64/x86_64 native sanitizer suites, Android unit tests (saved verifier corruption/layout/EOF cases included), lint/debug/release এবং publish। Release `v0.1.72-alpha-4affb40`, verified uploaded APK14,757,020 bytes। Device readback CRC result, saved-first-frame DNG SDK/visual/calibration behavior pending; no sustained recording/FPS claim।

## v72 visual confirmation → bounded DNG sequence

User confirmed saved first-frame DNG visually correct; Gallery/file-manager screenshots supplied. No new report/original binary, so full-file CRC/metadata correctness not independently certified; preserve EXIF behavior. Evidence `docs/device-tests/F23-v72-visual-summary.md`.

Patch adds all-saved-frame numbered DNG export with exact per-frame Camera2 metadata retained (max8), sequence.json real timestamp/PTS map, no synthetic frames/fixed FPS. Single sample retained. Same capture/CCRAW/native pool; native pool released before saved-file processing. Sequence guard1GiB vs650MiB single/off; extra I/O/storage noted. Sequential SDK generation/rollback/progress and plan/space tests. Local native tests passed; Android CI/device multi-frame rendering pending. Formal CinemaDNG/NLE timing/endurance/audio not validated.

### v73 delivery confirmed
CI `39441155082`/73 successful: ARM64/x86_64 native sanitizer suites, Android unit tests (sequence name/PTS/gap/bounds and expanded space guards), lint/debug/release এবং publish। Verified release `v0.1.73-alpha-bb2de20`, APK14,757,020 bytes। Multi-frame DNG rendering/metadata/manifest device verification and NLE import pending; same8-frame capture limit, no sustained recording/FPS claim।

## v73 device success → flat export and readable reports

User provided report/sequence JSONs + screenshots, confirmed all8 DNGs visually correct. Parsed and cross-checked8/8 matched/written,0drop,readbackOk8,sequenceOk8, exact timestamp/PTS maps. Evidence docs/device-tests/F23-v73. No original binaries independently checked here; no NLE/sustained-FPS/calibration certification.

User requests no per-capture folders and cannot open JSON. New exports flat under Download/CineCam using unique capture-prefix basenames; manifests updated to actual DNG names; report mapping + Bengali report.txt and in-app readable summary/JSON+text copy. Existing folders/files never migrated/deleted. Private temporary directories remain for safe capture. API26–28 fixed app-external CineCam destination. Native local suites pass; naming tests added; Android/device comparison pending.

### v74 delivery confirmed
CI `39554063068`/74 successful: ARM64/x86_64 native sanitizers, Android unit tests (flat naming/safety/manifest retry cases), lint/debug/release এবং publish। Verified `v0.1.74-alpha-092dbae` APK14,773,404 bytes। Physical MediaStore flat-directory/name/manifest/readable-report behavior pending; existing user folders/files untouched।

## v74 physical success → lossless codec feasibility

User confirms flat layout/readable reports and unchanged visual DNG quality. Uploaded report/sequence JSONs checked:8written,0drop,readbackOk,DNGsequenceOk8; all manifest names match export mapping and timestamps. Evidence docs/device-tests/F23-v74. Original payload not directly inspected here.

Next patch adds portable native lossless block bitpacking (actual0–16-bit value range, never sensor-white-level truncation), read-only first verified saved-frame encode/decode/memcmp probe, bounded~192KiB scratch and timing/byte counters. Existing CCRAW001/DNG/capture unchanged; NO compressed recording/archive output enabled. All16 bits supported, noisy16bit blocks can expand. Local sanitizers/container tests pass; Android CI/device codec result pending. Production format/version/decoder/compression choice needs later review, not claimed solved.

### v75 delivery confirmed
CI `39594661556`/75 successful: ARM64/x86_64 native sanitizers (codec0–16-bit/range/malformed/roundtrip cases), Android unit/lint/debug/release এবং publish। Verified `v0.1.75-alpha-b64b484` APK14,773,404 bytes। First saved-frame BP16 device ratio/encode/decode latency pending; compressed recording/output NOT enabled, same RAW/DNG/flat layout preserved।

## v75 device ফল → ঐচ্ছিক actual archive

চারটি uploaded sidecar docs/device-tests/F23-v75-এ সংরক্ষিত;8/8written,0drop,readback/DNG সফল। সব manifest নাম/timestamp/PTS/sourceFrameNumber cross-check সফল। Native first-frame lossless probe24,969,600→13,999,256bytes (~43.94% কম), encode49.533129ms/decode54.542812ms; savedCompressedFile=false intentional v75। Original RAW/DNG binary developer পরীক্ষা করেনি; sustained FPS/whole-clip ratio নয়।

নতুন কাজ: default OFF optional post-capture `.ccraw.bp16` CCBP0001 archive, bounded native encoding, full-file decode/CRC/memcmp verification, independent Python restore, অতিরিক্ত1.5GiB guard। Original CCRAW001/DNG/EXIF/flat export/native pool অক্ষত; compressed recording নয় এবং original রাখায় মোট space সাশ্রয় নয়। শুধু সফল byte-exact archive public export হবে। Details docs/BP16_ARCHIVE.md। Local selected+forced CRC ASan/UBSan/native+Python tests সফল; নতুন Kotlin space/name tests যোগ হয়েছে। Android CI/release ও physical archive test এখনও বাকি।

### v77 delivery confirmed
CI `39605249901`/77 সম্পূর্ণ সফল: ARM64 ও x86_64 ASan/UBSan/native+independent Python restore suites, Android unit tests/lint/debug/unsigned-release build এবং prerelease publication। Source `2ae09208e50a421a2ea56fc6c6963a298849d0b1` (archive feature `0f76f25`)। `v0.1.77-alpha-2ae0920`-এর `CineCam-debug.apk` asset uploaded,14,806,172bytes; SHA256SUMS.txt প্রকাশিত। Intermediate run76 বাতিল,77-ই install/test target।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.77-alpha-2ae0920/CineCam-debug.apk

পরবর্তী ধাপ: ফোনে archive switch ON + আগের successful8-frame settings +>=1.5GiB free, report.txt/report.json/metadata.json/sequence.json সংগ্রহ। Full archive size/byteExactSourceComparison/timings/flat14-files/unchanged DNG physical verification **pending**; CI পাস মানে device archive success বা sustained recording নয়। Original `.ccraw`/DNG retained; defaultOFF; no branch merge/close।

## v77 device ফল → latency breakdown

Evidence docs/device-tests/F23-v77: চারটি original sidecar parse/cross-check,14 flat files;8written/0drop/readback8/DNG8; actual archive199,757,664→109,739,528bytes, byteExactSourceComparisontrue। Screenshots archiveON এবং RAW/archive/DNG thumbnails দেখায়। Original binary এখানে নেই, independent device restore/post-export readback certify নয়।

Archive aggregate8.451s,verify0.626s; RAW write5.514s/fsync1.956s/maxwrite1.779s; capacitywait2.219s,capture→drain8.191s;DNG7.347s,export38.470s/509.5MB। Root cause অনুমান নয়: archive encode-stage পৃথক native read/pack/CRC/write/fsync/close timings/calls/maxwrite যোগ হয়েছে। Readable report pipeline/storage সময় ও overlap দেখায়; same formats/codec/pool/fsync/defaultOFF/flat export/EXIF/original retention। Native/independent restore local suites pass; JSON-backed readable report Kotlin unit cases যোগ। Android CI/release ও নতুন device stage timings pending।

### v78 delivery confirmed
CI `39620030332`/78 সম্পূর্ণ সফল: ARM64/x86_64 ASan/UBSan native (timing/accounting included), independent Python restore, Android unit tests (নতুন JSON-backed readable/legacy/error/locale cases সহ), lint, debug/unsigned-release এবং publication। Source `063c966cb257be55004eec130681c8668873aae6`। Release `v0.1.78-alpha-063c966`, uploaded `CineCam-debug.apk`14,806,172bytes +SHA256SUMS.txt যাচাই হয়েছে।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.78-alpha-063c966/CineCam-debug.apk

পরের physical test: একই successful settings, archiveON (defaultOFF),>=1.5GiB free, চার sidecar সংগ্রহ। নতুন sourceRead/pack/CRC/write/fsync/close/calls/maxWrite পর্যবেক্ষণ **pending**। এটি instrumentation release, speedup/causal diagnosis/compressed recording claim নয়; original RAW/DNG, full byte verification, flat layout ও3-buffer capture অপরিবর্তিত। Branch merge/close হয়নি।

## v78 device ফল → bounded record write coalescing

Evidence docs/device-tests/F23-v78: চার original sidecar cross-check,8written/0drop/readback/DNG8,14flat names/PTS/source-frame mapping মেলে। Actual archive199,757,664→120,139,272bytes, byteExactSourceComparisontrue। Original binary developer পায়নি। Archive pack0.485s/write3.334s/fsync6.774s/aggregate10.732s; export39.496s। Storage wall time dominant in this run, specific underlying cause unproven।

Native encoder এখন record prefix-এর জন্য একই small packed buffer-এ8bytes reserve করে, সরাসরি পিছনে encode, prefix+payload এক writeAll request। Payload copy/large buffering নেই; verify/source/format/fsync/capture pool/EXIF/defaultOFF/flat layout unchanged। 3049blocks-এর usual6099→3050syscalls; retry semantics preserved, sync delay থাকবে। `blockWriteMode` রিপোর্টে। Local ASan/UBSan + native call-count + independent reference wire-byte/restore tests pass; Kotlin readable-mode tests updated। Android CI/release ও physical gain pending।

### v79 delivery confirmed
CI `39624318624`/79 সফল: ARM64/x86_64 ASan/UBSan native call-count/accounting, independent Python exact reference encode+restore/corruption tests; Android unit/readable-mode/lint/debug/unsigned-release এবং prerelease publication। Source `1f947a7e6df4fa643c9a39b41879ac958c16e23c`। Verified release `v0.1.79-alpha-1f947a7`, APK14,806,168bytes uploaded; SHA256SUMS.txt প্রকাশিত।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.79-alpha-1f947a7/CineCam-debug.apk

পরের physical check: একই settings archiveON/defaultOFF,>=1.5GiB free; report/metadata/sequence চার sidecar। 3049block হলে typical3050writeCalls বনাম6099, full verification/DNG/flat14files বজায় কি না এবং storage timing দেখুন। Device latency improvement **pending**, fsync delay সমাধানের দাবি নয়। Source retained/format identical, no merge/close।

## v79 প্রথম device ফল — চার frame-এ user stop

2026-10-22: docs/device-tests/F23-v79-এ চার original sidecar সংরক্ষিত/cross-check সফল। reason=user stop,target8/submitted5/written4/nativeDrop0;readback/DNG4/archive byteExact সফল,10flat names/manifest mappings ঠিক। Source99,878,880→57,194,312bytes (42.7363% ছোট);1525blocks/1526writes প্রত্যাশিতcoalescedB+1। Archive690.01ms+verify316.21ms,export8.375s/257MB; RAW fsync2.539s এখনও আছে। Four-frame result8-frame performance proof নয়; no sustained FPS/causal speedup claim। Next: একই v79 APK-তে automatic8-request completion পর্যন্ত অপেক্ষা, আগে stop নয়। এই evidence update-এ code/APK/branch merge পরিবর্তন নেই।

## v79 পূর্ণ8-frame automatic completion নিশ্চিত — 2026-10-22

Evidence docs/device-tests/F23-v79/full-eight-frames: চার sidecar cross-check;reason=paced request limit reached,8submitted/matched/written,0failed/drop;readback8/DNG8/archivebyteExacttrue;14flat names/PTS/sourceFrameNumber মিলে গেছে।199,757,664→116,067,848bytes(41.8957%ছোট);3049blocks/3050calls প্রত্যাশিতB+1। Archive3988.74ms,তার মধ্যে pack397.97/write230.53/fsync3249.69ms;verify632.26ms। DNG5843.29ms/export23690.03ms for515,825,782bytes। Source retained, no net disk/FPS/causal performance claim। Coalesced full8-frame validation milestone complete; আবার একই test বা নতুন APK চাওয়া হয়নি। পরের candidate export-copy/flush review; implementation এখনও শুরু নয়। এই turn docs/evidence only, code/merge/close unchanged।

## Native export implementation — 2026-10-22

User কাজ/test iteration দ্রুত করার অনুরোধ করেছেন। Related work এক build-এ: C++ sendfile fast path + bounded256KiB exact-offset fallback + explicit per-file destinationfsync + JSON/readable per-file timings/backend bytes/errors + in-app progress। Flat layout/IS_PENDING/collision/rollback/report-last/source-retention/RAW+DNG+archive/full verification unchanged। আগের output explicitfsync ছিল না; এতে latency বাড়তে পারে, speedup claim নয়। docs/NATIVE_EXPORT.md-এ contract/limitations। Local native sanitizers এবং injected partial/EINTR/unsupported/ENOSPC/safety/exact-byte tests pass, archive reference/restore suites pass; new Kotlin readable test। Android CI/release/physical provider timing pending। Branch same, no merge/close।

### Export CI watchdog follow-up
প্রথম native-export CI39745023805-এ x86 pass, ARM sanitizer step exit124 (30s watchdog); Android build শুরু হয়নি, broken APK publish হয়নি। Restricted log host-এর কারণে full trace পাওয়া যায়নি; annotation দিয়ে timeout নিশ্চিত। Fixture byte-iterator comparison bulk64KiB reads-এ বদল এবং expanded sanitizer suite watchdog60s করা হয়েছে (finite, assertions unchanged)। ARM root cause/phone performance সম্পর্কে অনুমান নয়; নতুন run যাচাই করতে হবে।

### v81 delivery confirmed — native export batch
CI `39745351829`/81 সম্পূর্ণ সফল: ARM64/x86_64 sanitizers/native normal+fallback+injected error/byte checks, existing archive reference/restore/reader suites; Android unit/readable export tests/lint/debug/unsigned-release এবং publication। Source `43cbe6bd78debd3dbd3d48bb44b72d397380844b` (feature9c7ebe8)। Verified `v0.1.81-alpha-43cbe6b` APK14,806,172bytes uploaded +SHA256SUMS.txt। Failed80-এ APK publish হয়নি;81 install target।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.81-alpha-43cbe6b/CineCam-debug.apk

পরের এক phone test-এ full8 automatic completion এবংচার sidecar: flat14files, RAW/DNG/archive unchanged, kernel/buffered bytes/copyNs/fsyncNs/per-file errors/progress। Explicit per-file exportfsync নতুন, তাই শুধু kernel-copy দ্রুত দেখে aggregate speedup claim নয়। Actual MediaStoreFD support/export latency/device preservation pending। Related changes grouped to reduce test cycles; no merge/close।

## v81 native export device validation — 2026-10-22

Evidence docs/device-tests/F23-v81: চার sidecar cross-check;automatic8/written8/drop0/readback+DNG8/archivebyteExacttrue/14flat names। Actualarchive199,757,664→114,400,776bytes(42.7302%ছোট)।12data files514,158,710bytes সবkernelcopy/zero buffered/oktrue;providerFD fast path confirmed। Export38.44373s,copy sum17.01217s,explicitfsync sum20.41330s। v79-এর23.69003s-এর তুলনায় এই run বেশি;speedup দাবি নয়,fsync নতুন ও conditions uncontrolled। Original payload binaries নেই,post-export byte-readback নয়। নতুন APK/একই test আবার চাওয়া হয়নি; পরের candidate duplicate I/O review, durability/source retention না কমিয়ে। Code/merge/close unchanged।

## Manage clips + details implementation — 2026-10-22

ব্যবহারকারী approved user-facing workflow milestone। Read-only SAF folder browser/activity, persisted read grant, bounded background report index, captures list/details/missing files/actual sizes/saved-report flags/reportcopy/external open-share। Same flat folder/no mutations; old captures accessible without recapture। Unsafe cross-prefix/path refs reject, duplicate document names not resolved arbitrarily;64KiB/100reports/5000entries limits। Camera/finalizing entry disabled; own activity allows camera lifecycle pause/back। docs/MANAGE_CLIPS.md describes limits and one-session device check। No fake playback/render/buttons; no new pixel processing. Existing native sanitizer/reference/restore/export suites local pass; new pure Kotlin catalog tests use real v81 fixture. Android unit/lint/build/release and SAF provider/navigation/device checks pending।

### v82 delivery confirmed — Manage clips
CI `39750130983`/82 full success: ARM64/x86 sanitizers/native/export-copy/archive reference/restore suites, Android catalog unit tests, lint, debug+unsigned release, publication। Source `b30bcada9b4bf280b76c17e0b9cc33b01e8935cd`। Verified `v0.1.82-alpha-b30bcad` APK14,855,423bytes + SHA256SUMS.txt।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.82-alpha-b30bcad/CineCam-debug.apk

পরের device check (নতুন capture লাগবে না): Download/CineCam folder একবার বাছুন → পুরোনো v81 capture তালিকায় দেখা → details→DNG external-open→report copy/share→back→reopen→refresh। SAF persisted grant/duplicate/missing/revoke behavior pending। In-app playback/render/MP4 এখনো নেই; next milestone DNG-sequence review foundation।

## In-app DNG preview — 2026-10-22

User feedback: saved DNG ফাইল list-এ ছিল কিন্তু অ্যাপে ছবি দেখা যাচ্ছিল না; external DNG viewer-ও অনেক ডিভাইসে নেই। Added bounded native TIFF/DNG preview renderer + cover thumbnail/details cover/DNG viewer (prev-next, 1/8 counter), auto-refresh library on resume. Old captures directly testable, no recapture। Uncompressed 16-bit CFA only; unsupported gets clear message। No RAW write/export change। Local ASan/UBSan synthetic LE+BE exact-pixel/CFA/FD/corrupt tests pass, archive/export/reader suites pass; Android build/device parse-quality pending। details docs/DNG_PREVIEW.md।

### v83 delivery confirmed — in-app DNG preview
CI `39773936706`/83 full success: ARM64/x86 sanitizer/native/export/archive/reference plus synthetic LE/BE DNG preview tests, Android unit/lint/debug/unsigned-release/publication। Source `70c3ed93344fa4cc2e73b80955e6ffc8c96b5ec6`। Verified `v0.1.83-alpha-70c3ed9` APK14,953,727bytes + SHA256SUMS.txt।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.83-alpha-70c3ed9/CineCam-debug.apk

পুরোনো capture দিয়েই device check: Manage clips→cover thumbnail দেখা→details→কোনো DNG-এ **অ্যাপে দেখুন**→আগের/পরের frame→back। কোনো DNG-তে thumbnail/preview না এলে সেটি real DNG-এর tag/layout ভেদ—report-এ কোন frame-এ সমস্যা লিখে জানাতে হবে; APK/source অক্ষত। New capture বা পুরোনো diagnostic পুনরাবৃত্তি দরকার নেই।

## v84 — three sections + reliable preview — 2026-10-22

Device feedback v83: সব thumbnail "Preview নেই"—real DngCreator layout strict reader-এ মেলেনি, কারণও দেখা যাচ্ছিল না। Added verified-CCRAW-frame fallback (same payload, own format), native error surfacing, orientation hint from optional metadata, and three explicit sections: ছবি (grid+viewer), Lossless archive (size/ratio/saved verification/share), ফাইল ও রিপোর্ট। Read-only behavior unchanged। New CCRAW preview sanitizer tests (index bound, header/frame CRC, truncation, invalid fd); existing DNG/export/archive suites pass locally। Android CI/device pending।

### v84 delivery confirmed — three sections + CCRAW fallback
CI `39778598396`/84 full success: ARM/x86 sanitizer (DNG+CCRAW preview, export, archive, reference/restore), Android unit/lint/debug/unsigned-release/publication। Source `1d7a4a28399cb7a2f64180a591972725b9a9026e`। Verified `v0.1.84-alpha-1d7a4a2` APK15,002,883bytes + SHA256SUMS.txt।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.84-alpha-1d7a4a2/CineCam-debug.apk

Device check (পুরোনো capture দিয়েই): ছবি section→capture→grid thumbnail; DNG reader ব্যর্থ হলেও verified `.ccraw` frame fallback কাজ করবে; failure হলে এখন কারণ লেখা থাকবে (frame-এর নামসহ জানালে DNG tag অনুযায়ী ঠিক করা যাবে)। Lossless archive section-এ size/ratio/share; ফাইল section-এ সব companion। New capture দরকার নেই।

## v85 — four sections, colour and freeze fixes — 2026-10-22

Device feedback: requested exactly four sections (ছবি / clips / lossless archive / report); previews were green instead of red; pressing "ছবিগুলো দেখুন" froze the UI. Root causes: CCRAW fallback hardcoded RGGB while metadata says cfa=1 (GRBG), so red/green samples swapped; and the photo grid decoded up to eight 25MB payloads concurrently with a full-frame allocation each. Fixes: CFA mapping + metadata/report calibration (neutral, black/white, ColorMatrix1); max two concurrent decoders, 320px lazy thumbnails, row-sampled pread instead of whole-payload load, 24MiB cache; four explicit tabs with per-file rows; tiled DNG support. Native sanitizer suites updated and passing; Android CI/device verification pending.

### v93 delivery — চারটি বিভাগ, রঙ ও ফ্রিজ সমাধান
Source `2aca9a5a9018c18bc92da74765538c2dc9f26d78`। CI `39793532969`-এর ARM/x86 sanitizer, Android unit/lint/debug/release এবং publish ধাপ সফল; verified `v0.1.93-alpha-2aca9a5` APK15,035,647bytes + SHA256SUMS.txt।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.93-alpha-2aca9a5/CineCam-debug.apk

**ব্যবহারকারীর রিপোর্ট ও কারণ**
- চারটি আলাদা section দরকার ছিল; আগের build-এ তিনটি ছিল এবং archive-এ `.ccraw`ও মিশে ছিল। এখন: ছবি (প্রতিটি DNG), Clips (শুধু `.ccraw`), Lossless archive (শুধু `.ccraw.bp16`), Report (report/metadata/sequence)।
- লাল বস্তু সবুজ দেখানোর দুটি কোড-কারণ পাওয়া গেছে: (1) CCRAW fallback-এ CFA হার্ডকোড RGGB, কিন্তু sensor metadata `cfa=1` (GRBG); (2) DNG path-এ AsShotNeutral gain উল্টো (n_r/n_g) ছিল। দুটোই ঠিক করা হয়েছে; এখন metadata/report-এর CFA, neutral, black/white ও ColorMatrix1 ব্যবহৃত হয়।
- "ছবিগুলো দেখুন" চাপে ফ্রিজ: একসাথে ৮টি ২৫MB payload decode ও পূর্ণ-frame allocation। এখন সর্বোচ্চ2 concurrent decoder, lazy per-file row, 320px thumbnail, sampled-row pread (full payload নয়), 24MiB cache।
- 7:07-এর capture-এ DNG ছিল না কারণ ওই test-এ DNG sequence বন্ধ ছিল (companion4/4); এটি bug নয়—photos section-এ শুধু DNG থাকবে, ওই capture-এর RAW frame Clips section-এ দেখা যাবে।
- tiled DNG (TileOffsets/TileByteCounts) সমর্থন যোগ হয়েছে; unsupported DNG-তে CCRAW fallback ও পরিষ্কার error।

পরের device check পুরোনো capture দিয়েই: চার section দেখুন; ছবি→কোনো DNG→রঙ লাল/সবুজ মিলছে কি না; Clips→frame preview; Report→report খোলা/শেয়ার। নতুন capture বা নতুন diagnostic দরকার নেই।

## v94 — D50 colour fix + burst playback — 2026-10-22

User confirmed four sections/preview/freeze work, but previews skew red. Root cause: DNG matrices are D50-referenced while the preview multiplied them with a D65 sRGB matrix (D50 white → R1.18/G0.98/B0.72). Fix: Bradford-adapted D50→sRGB, prefer ForwardMatrix1, else invert ColorMatrix1; same for CCRAW fallback with metadata forwardMatrix1 / Camera2 colorTransform1. Added burst playback in the viewer using real sensor timestamps (50–800 ms clamp, loop, average burst fps, next-frame prefetch, 40 MiB cache). Synthetic ForwardMatrix neutrality test added. Android CI/device pending.

### v94 delivery confirmed — D50 রঙ ও burst playback
CI `39798806890`/94 সম্পূর্ণ সফল (ARM/x86 sanitizer + DNG ForwardMatrix/neutrality test, Android unit/lint/debug/release/publication)। Source `a450b5b52cab0472f4369b43cfa4fd1a652c37d6`; APK digest v93 থেকে ভিন্ন (sha256:8d6669b0…), আকার15,035,647 bytes।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.94-alpha-a450b5b/CineCam-debug.apk

পরের device check: পুরোনো capture খুলে রঙ আগের চেয়ে কম লাল কি না এবং viewer-এ **চালান** চেপে burst playback/loop দেখা। রঙ এখনো বেশি লাল হলে কোন capture/frame ও আলো কেমন ছিল জানালে ম্যাট্রিক্স আরও টিউন করা যাবে।

## v95 — MP4 render from saved burst — 2026-10-22

User confirmed colours and burst playback now correct (3.0 fps shown from real timestamps). Next milestone implemented: silent H.264 MP4 render from the saved burst's native preview frames via MediaCodec/MediaMuxer, real sensor-timestamp PTS, NV12/I420 capability selection, MediaStore flat Download/CineCam naming (API29+) or app-specific legacy folder, pending/publication and failure cleanup, in-app progress and share. Sources untouched. JVM unit tests cover YUV conversion and scaling/timestamp rules; Android encoder/playback verification pending.

### v95 delivery — MP4 render
CI `39803625141`/95 সম্পূর্ণ সফল (ARM/x86 native sanitizer, Android unit/lint/debug/release, publication)। Source `d089067e1ca57be36d41aefb52b43a5afbc2ec52`। Release `v0.1.95-alpha-d089067`, APK 15,068,415 bytes + SHA256SUMS.txt।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.95-alpha-d089067/CineCam-debug.apk

Device check (পুরোনো capture): Clips→`MP4` বা ছবি-details→`MP4 তৈরি করুন (8)`। সফল হলে `Download/CineCam/CineCam_<capture>_render_<epoch>.mp4` এবং **ভিডিও শেয়ার**; ফোনের gallery/player-এ চালিয়ে দেখা যাবে। Encoder/playback physical verification এখনো বাকি; ব্যর্থ হলে app-এর দেখানো error text জানাতে হবে (এতে encoder capability/image layout বোঝা যাবে)।

## v96 — wrong-clip MP4 & archive listing fixes — 2026-10-23

Device feedback: new capture's MP4 showed the previously rendered clip; Lossless archive showed .ccraw rows. Root causes: (1) preview cache key omitted the CCRAW URI, so every clip's frame N shared one cache entry; (2) sections were built from saved-report mappings rather than actual files. Fixed with PreviewCacheKey (DNG/CCRAW URI+frame+calibration+size) and document-derived sections (dng / ccraw / ccraw.bp16 / sidecars), plus CCRAW header frame counts when reports are missing and a rendered-MP4 list showing each video's capture id. New JVM tests for cache-key uniqueness and header parsing. Android CI/device verification pending.

### v97 delivery — library fix build
CI `39910039682`/97 সম্পূর্ণ সফল (ARM/x86 native sanitizer, Android unit/lint/debug/release, publication)। Source `6123e1bb2b6f5064315222820159474ee24374f0`। Release `v0.1.97-alpha-6123e1b`, APK 15,084,799 bytes + SHA256SUMS.txt। প্রথম build (06a9161) Kotlin compile-এ ব্যর্থ হয়েছিল (`InputStream.readBytes(Int)` নেই); report পড়া এখন 64 KiB bounded buffer দিয়ে String-এ decode হয়।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.97-alpha-6123e1b/CineCam-debug.apk

Device check: Clips-এ 23 Oct capture → `MP4` → ভিডিওতে ওই capture-এর 8 frame দেখা উচিত (পুরোনো clip-এর নয়); এরপর 22 Oct capture-এ গিয়ে আবার render করলে সেটির নিজের frame আসবে। Archive-.bp16 ট্যাবে শুধু `.ccraw.bp16` ফাইল থাকবে, কোনো `.ccraw` নয়। App থেকে বেরিয়ে আবার ঢুকলে তালিকা নিজে থেকে refresh হবে।

## v98 — render settings — 2026-10-23

User confirmed v97 fixed the wrong-clip MP4 and the archive listing. Next milestone step: render quality/speed control. `RenderOptions` + `RenderResolution`/`RenderQuality`/`RenderPacing` presets (960/1280/1920 px long edge, 2/4/8 bpp clamped 2–20 Mbps, real sensor pacing or constant 10 fps), a settings dialog before every render, and persisted choices. Frame decoding follows the chosen size, so 540p renders faster and lighter than before. Pure-Kotlin tests cover presets, no-upscale scaling, bitrate clamps and constant pacing. Android CI/device verification pending.

### v98 delivery
CI `39912141455`/98 সম্পূর্ণ সফল (ARM/x86 native sanitizer, Android unit/lint/debug/release, publication)। Source `31e73fe`। Release `v0.1.98-alpha-31e73fe`, APK 15,084,799 bytes (digest v97 থেকে ভিন্ন: sha256:8fd23faa…), SHA256SUMS.txt সহ।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.98-alpha-31e73fe/CineCam-debug.apk

Device check: Clips → **MP4** → নতুন **ভিডিও সেটিংস** ডায়ালগে 540p/720p/1080p, ছোট/স্বাভাবিক/উচ্চ, আসল/সমান ১০ fps বেছে **তৈরি করুন**। 540p সবচেয়ে দ্রুত ও ছোট ফাইল; 1080p সবচেয়ে বিশদ। সেটিংস পরেরবারও মনে থাকবে।

## v99 — long RAW session — 2026-10-23

User confirmed v98 and asked to start the next planned milestone. The capture path is no longer an 8-frame burst: the user picks 3/5/10/20/30 s and a 2/3/5 fps pacing target. `RecordingPlanner` computes a finite frame budget from duration and free storage (1 GiB reserve, 8 GiB cap, DNG/archive copies counted), so a session can never run unbounded. Requests are scheduled at absolute slot times with exactly one in flight; missed slots are counted, and a writer that refuses buffers for 3 s stops the session with a reason. The native `CcrawWriter` now takes explicit `maxFrames`/`maxBytes` (defaults keep the old 8-frame/256 MiB container byte-identical), and the verifier, preview reader, BP16 archive and library caps were raised for long files. Reports add the plan, achieved fps, skipped slots and stop reason. Native + JVM tests added; Android device verification pending (this build is the first compiler check of the Kotlin).

### v99 delivery — long RAW session
CI `39932332069`/102 সম্পূর্ণ সফল (ARM/x86 native sanitizer, Android unit/lint/debug/release, publication)। Source `822370f`। Release `v0.1.102-alpha-822370f`, APK 15,101,183 bytes + SHA256SUMS.txt। প্রথম দুটি build শুধু unit-test প্রত্যাশার কারণে লাল হয়েছিল (নতুন long-session limit-গুলো পুরোনো পরীক্ষায় লেখা ছিল); এখন সব unit test pass।

APK: https://github.com/islamyousuf032-glitch/Cine-cam/releases/download/v0.1.102-alpha-822370f/CineCam-debug.apk

Device check: RAW session → দৈর্ঘ্য (৩–৩০s) ও target fps (২/৩/৫) বাছুন। হিসাব দেখে নিশ্চিত হয়ে **টেস্ট শুরু করুন**; শেষে report-এ `Session`, `আসল rate`, `ব্যবহৃত হয়নি এমন slot` দেখুন। ফোনে আসল rate ও storage আচরণই এখানে একমাত্র প্রমাণ — কোনো FPS দাবি করা হয়নি।

## v101 — archive restore — 2026-10-23

Device test of the long session (CineCam_1792750236346_07a3ccc3): 27 of 30 slots, 27 frames written, 0 native drops, achieved 2.69 fps, 674,181,888-byte CCRAW; writer time 751 ms with a 35.7 ms worst write, so storage was never the bottleneck — the camera simply delivered 27 frames in 10 s. Export copied 674 MB in 25.2 s (~26.7 MB/s to MediaStore) and MP4 render of that clip works on device.

Next milestone step: in-app restore of `.ccraw.bp16`. `restoreBp16Archive(fd, fd)` streams the archive, validating every block CRC, EOF and byte count, and the Kotlin layer verifies the restored CCRAW with `CcrawVerifier` before publishing it as `<clipId>_capture_restored_<ts>.ccraw` in Download/CineCam. Archive limit raised to 8 GiB for long sessions. Native restore, header parsing and naming are covered by tests; Android restore UI/MediaStore still needs a device check.
