# Changelog — CineCam RAW Camera

সব পরিবর্তন বাংলায় নথিভুক্ত করা হবে।

## [Unreleased — রেন্ডার quality/speed অপশন] — 2026-10-23
- **MP4** চাপলে এখন "ভিডিও সেটিংস" ডায়ালগ: **রেজোলিউশন** (540p / 720p / 1080p — লম্বা দিক 960/1280/1920 px), **কোয়ালিটি** (ছোট / স্বাভাবিক / উচ্চ = 2/4/8 bits-per-pixel) এবং **frame-এর সময়** (আসল sensor burst timing বা সমান ১০ fps)।
- একই সেটিংস পরেরবারের জন্য মনে রাখা হয় (SharedPreferences), তাই বারবার বাছতে হয় না।
- bitrate এখন নির্বাচিত quality অনুযায়ী 2–20 Mbps-এর মধ্যে encoder-safe clamp; 1080p-তেও বড় আকারের অকারণ ফোলা ফাইল হয় না। মূল `.ccraw`/`.dng`/archive-এ কোনো পরিবর্তন নেই।
- `VideoRenderPlan`/`RenderOptions` pure-Kotlin unit tests: preset mapping, per-preset scaling (কোনো upscale নয়), bitrate clamp, সমান-ব্যবধান PTS, সীমার বাইরের মান rejection।

## [Unreleased — ভুল ক্লিপের ভিডিও ও archive section সংশোধন] — 2026-10-23
- **মূল বাগ:** preview cache key-তে `.ccraw` container URI ছিল না; CCRAW frame-এর DNG URI null হওয়ায় সব clip-এর একই frame index একই cache entry পেত। ফলে নতুন capture-এর MP4 রেন্ডার করলে **আগের clip-এর ছবি** আসত। এখন key-তে container URI, frame index, CFA, matrix mode, white level ও আকার—সব থাকে (unit test সহ)।
- Manage clips-এর প্রতিটি section এখন **বাস্তব folder ফাইল** থেকে তৈরি: ছবি = `.dng`, Clips = `.ccraw`, Archive = **শুধু** `.ccraw.bp16`, Report = sidecar। report/metadata হারালেও ফাইল তালিকা থাকে; CCRAW header (64 byte) পড়ে frame সংখ্যা বের করা হয়, তাই report ছাড়াও preview/render চলে (রঙের calibration ছাড়া)।
- Clips-এ **রেন্ডার করা MP4** তালিকা: capture id, সাইজ, চালান/শেয়ার—কোন ভিডিও কোন capture-এর তা সরাসরি দেখা যায়। নতুন রেন্ডার নতুন ফাইল, পুরোনো মুছে যায় না।
- রেন্ডার স্টেটাসে capture id যোগ; tab chip সংকুচিত ও অনুভূমিক scroll (Report আর ভেঙে যাচ্ছে না)।
- Android encoder/playback যাচাই এখনো ফোনে বাকি।

## [Unreleased — RAW burst থেকে MP4 রেন্ডার] — 2026-10-22
- Manage clips-এর Clips ও ছবি section থেকে **MP4 তৈরি**: MediaCodec H.264 + MediaMuxer, native preview frame থেকে; শব্দ নেই।
- আসল sensor timestamp→PTS (প্রথম frame=0, ন্যূনতম 33.333ms ব্যবধান, duplicate PTS নয়); report-এ timestamp না থাকলে তবেই অনুমান। burst গড় fps দেখানো হয়।
- API29+ flat naming `Download/CineCam/CineCam_<capture>_render_<epoch>.mp4` MediaStore IS_PENDING→publish; API26–28 app-specific Movies/CineCam। capture/DNG/archive/EXIF অপরিবর্তিত; ব্যর্থ হলে অর্ধেক ফাইল/row cleanup।
- Encoder capability থেকে NV12/I420 conversion; unsupported হলে পরিষ্কার error। ফাইল মাপ preview resolution, bitrate limit 2–12Mbps।
- Pure-Kotlin YUV ও timestamp/scaling unit tests; details docs/VIDEO_RENDER.md। Android encoder/playback physical verification pending।

## [Unreleased — সঠিক রঙ (D50) ও burst sequence playback] — 2026-10-22
- "ছবি লাল বেশি" সমস্যার মূল কারণ: DNG-র ColorMatrix1/ForwardMatrix1 হলো **XYZ D50**, কিন্তু preview সেগুলোকে D65 sRGB ম্যাট্রিক্স দিয়ে গুণ করছিল—তাই মাঝারি রঙ লাল/ম্যাগেন্টা হয়ে যেত। এখন Bradford-adapted D50→sRGB ম্যাট্রিক্স, এবং থাকলে **ForwardMatrix1** (white-balanced camera→XYZ D50) সরাসরি ব্যবহার হয়; না থাকলে ColorMatrix1 invert করা হয়। CCRAW fallback-ও একই পথ ও D50 ম্যাট্রিক্স ব্যবহার করে।
- CCRAW ক্যালিব্রেশনে metadata-র `forwardMatrix1` অগ্রাধিকার পায়, তারপর Camera2 `colorTransform1` (XYZ→camera, invert করে)। vivo/স্যামসাং-ভেদে DNG tag ভিন্ন হলেও fallback স্বাভাবিক রঙ দেয়।
- **Burst playback**: viewer-এ চালান/থামান; প্রতিটি frame আসল sensor timestamp অনুযায়ী (50–800ms clamp) এগোয়, শেষে loop; পাশে গড় burst fps ও সময়। পরের frame আগেই decode হয়ে cache-এ থাকে (960px, 40MiB cache)।
- ফাইল/রিপোর্ট/EXIF/export আচরণ অপরিবর্তিত; এটা ৮-frame burst review, sustained video নয়। Host sanitizer + DNG ForwardMatrix/neutrality test pass; Android CI/device pending।

## [Unreleased — চারটি বিভাগ, সঠিক রঙ, ফ্রিজসমূহ সমাধান] — 2026-10-22
- Manage clips চারটি আলাদা section: **ছবি** (প্রতিটি DNG), **Clips** (শুধু `.ccraw`), **Lossless archive** (শুধু `.ccraw.bp16`), **Report** (report/metadata/sequence)।
- লাল বস্তু সবুজ দেখানোর কারণ ধরা পড়েছে: CCRAW fallback-এ CFA origin হার্ডকোড RGGB ছিল, কিন্তু এই sensor GRBG (`cfa=1`)। এখন capture-এর metadata/report calibration (CFA, neutral, black/white, color matrix) ব্যবহার হয়।
- "ছবিগুলো দেখুন" চাপে ফ্রিজ ঠিক: unbounded একসাথে-decoder বন্ধ, সর্বোচ্চ2 concurrent, thumbnail 320px, lazy load, full 25MB payload-এর বদলে sampled row read, 24MiB cache।
- tiled (TileOffsets/TileByteCounts) DNG পড়ার সমর্থন; unsupported হলে CCRAW fallback এবং পরিষ্কার error।
- RAW/DNG/EXIF/archive/export আচরণ অপরিবর্তিত; playback/MP4 এখনো নেই। Host sanitizer tests pass; Android CI/device pending।

## [Unreleased — তিন section ও নির্ভরযোগ্য ছবি preview] — 2026-10-22
- v83 device feedback: বাস্তব DNG-এ thumbnail সব "Preview নেই" ছিল। এখন DNG reader ব্যর্থ হলে একই capture-এর verified `.ccraw` frame থেকে native preview—vendor DNG tag-এর উপর নির্ভরতা কম।
- Library তিনটি আলাদা section: **ছবি** (capture→ছবির grid→viewer), **Lossless archive** (আকার, কত% ছোট, রিপোর্টের যাচাই ফল, শেয়ার), **ফাইল ও রিপোর্ট** (সব companion, সহজ রিপোর্ট)।
- Preview failure-এর আসল কারণ UI-তে দেখানো হয়; thumbnail/details/viewer একই fallback ব্যবহার করে। Orientation hint optional metadata থেকে; malformed হলে 무시।
- Native CCRAW frame preview: header/frame-CRC/bounds যাচাই, auto black/white+WB, frame index bound; কোনো source write নেই।
- DNG/RAW/EXIF/archive/export আচরণ অপরিবর্তিত; archive playable video এখনো নয়; MP4/audio এখনো নেই।
- Host ASan/UBSan synthetic CCRAW + DNG tests pass, existing suites pass। Android CI ও device preview pending। docs/DNG_PREVIEW.md।

## [Unreleased — অ্যাপের ভিতরে DNG preview] — 2026-10-22
- Manage clips-এ cover thumbnail, details-এ বড় preview, প্রতিটি DNG-এর **অ্যাপে দেখুন** এবং full-screen আগের/পরের frame viewer; পুরোনো saved capture দিয়েই কাজ করে।
- নতুন native `dng_preview.cpp`: bounded TIFF/DNG parse, mmap/pread source, uncompressed 16-bit CFA only, strip/crop/size validation, 2×2 CFA block average, black/white, AsShotNeutral, optional ColorMatrix1, sRGB LUT। Full-frame Java buffer নেই; descriptor caller-owned/read-only।
- 24MiB preview cache; thumbnail220, details900, viewer1600 max dimension; EXIF orientation rotate। Missing/unsupported/truncated DNG-এ পরিষ্কার Bangla error, crash/vendor assumption নয়।
- RAW/archive/DNG/EXIF/export/flat folder unchanged; RAW-archive এখনো viewer photo নয়। Real video playback/MP4 এখনো নেই।
- Host ASan/UBSan synthetic LE/BE DNG, CFA mapping, expected pixel values, FD/mmap path, corrupt/unsupported rejection tests; existing native/archive/export/reader suites pass locally। Device DNG parse/quality এবং Android CI pending; details docs/DNG_PREVIEW.md।

## [Unreleased — Manage clips / capture details] — 2026-10-22
- Storage-counter-only iteration-এর বদলে প্রথম usable capture manager: camera থেকে আলাদা Manage clips screen, user-selected flat folder-এর পুরোনো saved captures, timestamp/frame/drop/RAW/archive sizes, present/missing companion files।
- Details-এ automatic/user-stop reason, saved report-এর RAW/DNG/archive ফল, actual file sizes, readable report/copy; mapped files external open/share। File delete/move/rename/new capture folder নেই; original capture/export/EXIF/native pipeline untouched।
- SAF persisted read access; broad storage permission নয়। Camera/finalizing সময় entry disabled; lifecycle camera pause/back preserves existing behavior। Folder permission/cancel/revoke/empty/invalid report errors visible; background bounded scan/refresh।
- Untrusted report prefix/schema/flat layout/all mappings validated; unsafe/cross-capture refs reject, duplicate/missing files never silently opened।5000entries/100reports/64KiB report limits, no RAW pixel decode/native buffer allocation।
- In-app video playback/render/MP4 এখনো নেই; saved-report success independent payload verification নয়। Existing clips দিয়েই এই feature পরীক্ষা করা যাবে, নতুন diagnostic capture প্রয়োজন নেই। Details docs/MANAGE_CLIPS.md।
- Catalog unit tests real v81 fixture/optional flags/user-stop/path safety/schema/UTF8 bounds/mapping/counters; local existing native suites pass। Android CI এবং physical SAF/navigation/share validation pending।

## [Unreleased — Native export + একই build-এ diagnostics] — 2026-10-22
- Full8-frame v79 device evidence সফল; পরের measured cost প্রায়516MB export/23.69s। এখন একসঙ্গে native fast path, safe fallback, per-file diagnostics ও progress; শুধু ছোট counters-এর জন্য বারবার APK/test নয়।
- Java copyTo-এর বদলে C++ sendfile→provider descriptor; unsupported হলে exact offset থেকে bounded256KiB fallback। Partial/EINTR handling, exact sizes, no full-frame Java buffer। Kernel path physical zero-copy বা guaranteed speedup নয়।
- প্রতিটি destination explicit fsync + Kotlin close-এর পরে transaction success; আগের export-এ explicit per-file fsync ছিল না, তাই latency বাড়তেও পারে। RAW/archive flush/full verification অক্ষত, source retained on failure; rollback/naming/pending/report-last/flat folder অপরিবর্তিত।
- JSON/readable report-এ per-data-file kernel/buffered bytes/copy/fsync/error, in-app file-count progress। Reports নিজেদের serialized timing snapshot-এ ঢোকে না; copy/fsync aggregate timing-এর সঙ্গে double-count নয়।
- Native ASan/UBSan normal/forced fallback, byte-exact comparison, injected short-kernel+EINTR+unsupported continuation, ENOSPC/readonly/invalid/self/nonempty fd এবং empty source tests স্থানীয়ভাবে সফল। Existing archive/reference/reader tests বজায়। CI39745351829/81-এ native ARM/x86, Android unit/lint/APK build/publication সফল; device result pending। Details docs/NATIVE_EXPORT.md।

## [Unreleased — Archive record write coalescing] — 2026-10-21
- F23 v78 চার sidecar cross-check:8written/0drop/readback/DNG সফল;199,757,664→120,139,272bytes archive, byte-exact compare সফল;14 flat names/timestamp/PTS/source-frame mapping মেলে। Evidence docs/device-tests/F23-v78; original binaries পাওয়া যায়নি।
- Archive pack0.485s,write3.334s,fsync6.774s;10.732s aggregate-এর প্রায়94.19% write+sync। RAW write7.816s/export39.496s-ও ধীর; filesystem/root cause বা sustained FPS সিদ্ধান্ত নয়।
- Native archive record prefix+BP16 payload একই bounded buffer-এ সরাসরি encode করে এক writeAll request; আলাদা8byte write বাদ, payload memcpy/full-frame buffer নেই। Format/algorithm/source retention/CRC/fsync/close/full memcmp অপরিবর্তিত।
- স্বাভাবিক full-write-এ3049records-এর6099→3050write syscalls; short-write/EINTR retry বজায়। `blockWriteMode=coalesced-record-v1` JSON/readable report-এ। এটি fsync বাদ দেওয়া বা নিশ্চিত speedup নয়; physical latency যাচাই বাকি।
- Native fixtures-এ actual write-call reduction/accounting, independent Python reference encoder দিয়ে exact archive wire bytes (সব0…16-bit/tail) এবং restore/corruption/no-overwrite tests স্থানীয়ভাবে সফল। CI39624318624/79-এ native ARM/x86, independent reference/restore, Android unit/lint/APK build ও publication সফল। নতুন device ফল বাকি।

## [Unreleased — Archive latency breakdown] — 2026-10-21
- F23 v77-এর চারটি sidecar ও দুই screenshot পর্যালোচনা:8written/0drop,RAW readback/DNG8 সফল; পুরো199,757,664bytes→109,739,528bytes archive, byteExactSourceComparison সফল। Timestamp/PTS/source-frame/flat14-file mapping মিলেছে; evidence docs/device-tests/F23-v77। Original binaries সরাসরি পরীক্ষা হয়নি।
- Archive aggregate8.451s-কে codec speed বলা যায় না; এই run-এ RAW write5.514s/fsync1.956s ও export38.470s-ও ধীর। Single test দিয়ে storage/root cause বা archive-এর কারণে capture ধীর—এমন সিদ্ধান্ত নয়।
- Native C++ archive-এ পৃথক source-read/pack/CRC/write/fsync/close wall time, max write, block ও read/write syscall counters। Verification সময় আলাদা; clock measurement সামান্য overhead যোগ করে। Format/algorithm/buffers/capture path/fsync/full byte-compare অপরিবর্তিত।
- সহজ বাংলা report-এ archive stage breakdown, RAW storage timings, paced অপেক্ষা, capture→drain, DNG ও export-copy সময়; overlap স্পষ্ট, যোগ করে ভুল FPS নয়। Locale-stable readable decimals; পুরোনো report-এ অনুপস্থিত stage invent নয়।
- Native timing/accounting ও independent restore tests স্থানীয়ভাবে সফল; নতুন readable-report/legacy/error/locale unit tests যোগ। CI39620030332/78-এ native ARM/x86, Android unit/lint/debug/release/publication সফল। নতুন device stage ফল বাকি।

## [Unreleased — ঐচ্ছিক byte-exact archive] — 2026-10-21
- F23 v75-এর চারটি sidecar parse/cross-check: 8/8 written,0drop,readback+DNG সফল; timestamp/PTS/source-frame/export mapping মিলে গেছে। প্রথম-frame probe 24,969,600→13,999,256 bytes (~43.94% কম), pack49.533ms/unpack54.543ms। এটি পুরো clip ratio বা sustained FPS নয়; original binaries পাওয়া যায়নি। Evidence docs/device-tests/F23-v75।
- Default OFF নতুন Lossless archive switch: saved RAW যাচাইয়ের পরে C++ দিয়ে পুরো `.ccraw`→`capture.ccraw.bp16` (experimental CCBP0001)। মূল RAW/DNG অক্ষত; extra file মানে মোট storage বাড়ে। Capture format/hot-path compression/EXIF/native pool অপরিবর্তিত।
- Bounded block processing, header ও decoded-block CRC, exclusive create/no overwrite, fsync/close, পুরো archive decode+original-এর সঙ্গে byte-exact comparison। সফল verified archive-ই flat public export-এ যাবে; failure রিপোর্ট হলেও মূল RAW/DNG export চলতে পারে।
- Archive চাওয়া হলে conservative1.5GiB free-space guard; বাড়তি encode/readback/copy সময় ও disk cost UI/report-এ স্পষ্ট। JSON ও সহজ বাংলা report-এ actual archive size/timings/verification/error।
- Independent Python restore tool `scripts/restore_bp16.py`, format/safety/restore নির্দেশনা `docs/BP16_ARCHIVE.md`। Native sanitizers ও independent restore-এ সব0…16-bit widths, tail/multiblock, corruption/truncation/trailing/no-overwrite পরীক্ষাগুলো স্থানীয়ভাবে সফল। Android CI run39605249901/77-এ native ARM/x86 + unit/lint/APK build/publication সফল। নতুন device archive test বাকি।

## [Unreleased — Lossless RAW16 codec feasibility] — 2026-10-21
- F23 v74 flat export validated by uploaded JSONs + user:8/8 written,0drop,readbackOk,8 DNGs correct; manifest names match prefixed exportFiles, no new capture folder。 Original RAW binaries not supplied。
- New experimental C++ BP16 block codec: actual RAW16 values determine0–16 bits per block; no white-level-based truncation, gamma, quantization or precision inference। Full16-bit inputs losslessly supported; incompressible data may expand by header overhead。
- Read-only first verified saved-frame probe: bounded64KiB raw+packed+decoded buffers (~192KiB total), byte-for-byte roundtrip, input/packed bytes and read/encode/decode times in report। No output compressed file, no capture-format change or compressed recording enabled。
- Native tests cover all bit widths, unaligned pointers, small/large/tail blocks, invalid caps/headers/tails/truncation and source-range validation; ASan/UBSan + existing container reader tests passed locally।
- Probe runs after native stop/readback, before DNG; optional via existing saved-verification switch。 Extra time/read I/O; codec results are one-frame feasibility only, not sustained throughput proof。 Existing RAW/DNG/flat filenames/EXIF/fsync/pool3 unchanged。

## [Unreleased — Flat export / readable reports] — 2026-10-21
- F23 v73 original JSONs verified:8/8 written,0drop,readbackOk8frames,DNGsequenceOk8files; timestamp/PTS maps agree। User confirms all8 DNGs visually correct। Evidence archived in docs/device-tests/F23-v73 (no original RAW binaries)।
- User-visible RAW export এখন একটিই Download/CineCam folder; প্রতি capture-এ subfolder নয়। Unique capture prefix প্রতিটি companion/DNG/report filename-এ, old files overwrite নয়। Private temporary directories remain isolated for safe native writing।
- sequence.json frame filenames updated to actual exported names; report exportFiles/exportSessionId/exportLayout mapping। Provider collision/rename detected হলে rollback/source retention, silently broken manifest নয়। API26–28 fixed app-external CineCam destination with unique basenames/no overwrite।
- Human-readable Bengali report.txt এবং in-app readable summary/copy; original JSON copy retained for diagnostics। JSON file opener install করা প্রয়োজন নেই।
- Existing exported folders/files not moved/deleted/migrated; same raw/DNG bytes, EXIF behavior, native pool/CRC/fsync/capture limits। No recording/FPS claim।
- Naming uniqueness/path safety/manifest retry-reference tests; native local suites pass। New Android/device flat export validation pending।

## [Unreleased — Saved DNG sequence] — 2026-10-20
- User confirmed v72 first saved frame DNG opens and looks correct; screenshots show rendered scene/25MB DNG। New JSON/original file not supplied, so whole-file readback result independently confirmed নয়।
- Optional/default all-saved-frame DNG sequence: frame_000001.dng… from verified payloads, each using its own retained matching Camera2 result/calibration, not duplicated first-frame metadata। Single first_frame.dng option retained।
- sequence.json records BOOTTIME/epoch/timestamp/PTS/source frame numbers and file mapping; no fixed FPS, missing-frame synthesis, audio or certified CinemaDNG/NLE timing conformance claim। This is a numbered DNG still sequence/CinemaDNG-style export, not continuous capture/MP4।
- Sequential SDK DngCreator streaming, <=8 metadata objects retained, no new full RAW byte arrays/native buffers। Native3-slot pool released before post-processing; SDK DngCreator memory remains platform-dependent।
- Sequence mode free-space guard1GiB at source/export (source RAW+DNG + export duplication); single/off650MiB। Typical8-frame result ~200MB RAW+~200MB DNG, extra read/write/export time।
- Preflight metadata mapping, sequence failure cleanup best-effort, no success manifest on failure; RAW/report still export। Progress/error/count/bytes/time reporting, timestamp naming/space-policy tests। Existing still/preview/EXIF/native format/fsync unchanged।

## [Unreleased — Saved RAW verification / DNG sample] — 2026-10-20
- v71 F23 দুই staging path-এ matched8/8, written6/8, 2 poolExhausted; acquisition deferrals3/5 হলেও কোনো unmatched pair নেই। Internal path বেশি frame দেয়নি; different conditions-এ storage winner বলা যাবে না।
- Optional/default Saved RAW যাচাই + DNG sample switch। Native stop-এর পরে saved CCRAW001 Java CRC32 দিয়ে re-read; strict geometry/header/payload/footer/count/timestamp/exact EOF validation, 64KiB verifier buffer। Native CRC code পুনর্ব্যবহার নয়।
- সফল readback-এর প্রথম saved payload থেকে `first_frame.dng`, Android DngCreator-এর exact retained first accepted capture result/characteristics/orientation দিয়ে। নতুন still capture নয়; পূর্ণ RAW byte array তৈরি করা হয় না (SDK DngCreator memory implementation-dependent)।
- readbackOk/frames/bytes/error/ns এবং firstFrameDngOk/bytes/timestamp/error/ns রিপোর্ট; verification/DNG extra I/O ও ~25MB extra output, capture hot path-এর পরে। Readback failure হলে success দাবি নয়; original files/report export হয়, DNG বাদ।
- DNG metadata/SDK failure আলাদাভাবে report হয়, RAW মুছে ফেলা হয় না। CRC accidental-corruption check, authenticity/pixel calibration বা rendered-video validation নয়।
- Kotlin verifier tests: independent constructed fixture, payload/header/footer corruption, truncation/trailing data, re-signed geometry/count mismatch। Native pool3, capture budgets, format, CRC/fsync, existing still/preview unchanged। Physical device readback/DNG tests pending।

## [Unreleased — Internal staging A/B] — 2026-10-20
- v70 F23: requested reserve ON returned EOPNOTSUPP95, reservedBytes0, safe fallback; ON/OFF দুটোই regular writes। 5/6 frame difference দিয়ে reservation speedup বলা যাবে না। Matching8/8, drops only poolExhausted; write/fsync waits বড়, close/rename ছোট।
- API29+ default app-private internal temporary capture, final export এখনও Download/CineCam। Warning dialog-এ Internal staging switch দিয়ে পুরোনো app-external path রাখা হয়েছে। API26–28 unchanged external staging/export।
- Capture ও export locations-এ পৃথক free-space check; same physical volume হলে available values যোগ করা যাবে না। Report/sidecar-এ actual staging location/free bytes; no hidden internal→external fallback।
- Reservation default OFF for path-only A/B; native pool3, bounded capture/pairing/pre-touch/CRC/format/fsync অপরিবর্তিত।
- Export data-copy ns/bytes report-এ; exported report লেখা হয় data files-এর পরে। এটি report copy/publication/cleanup-এর total export latency নয়। Failure-এ pending entries rollback এবং source/report retained; private source uninstall/clear-data-তে হারাবে।
- Storage-policy unit tests (API fallback, both volume space limits); device speedup/endurance/original RAW validation pending।

## [Unreleased — File reservation / finalization timing] — 2026-10-20
- F23 v69: দুই mode-এ 8/8 exact timestamp pairs, no unmatched; Burst 6/8 written, 2 poolExhausted, Paced 8/8। Native copy max ~15.5/12.4ms; write/finalization stalls এখনও বড়।
- Warning dialog-এ separate file-space reserve switch (default ON); capture-এর আগে `posix_fallocate` দিয়ে bounded native frame budget-এর file space reserve। OFF রাখলে আগের allocation path দিয়ে A/B করা যায়।
- Unsupported allocation APIs/filesystems-এ fallback; ENOSPC ও অন্য genuine errors-এ start fail, নীরবে উপেক্ষা নয়। Reserve startup নিজে slow হতে পারে; background storage traffic/flush/scheduling ঠিক করার guarantee নয়।
- সফল finalize-এ unused reservation `ftruncate` করে exact EOF, তারপর fsync/close/rename। Frame সংখ্যা কম হলে file ছোটই হবে। Partial preallocated file-এ zero tail থাকতে পারে; complete/valid capture বলা যাবে না।
- Allocation/error/reserved bytes, max write-call time/count, truncate/close/rename timings report-এ যোগ; fsync EINTR হলে retry, close EINTR-এ unsafe retry নয়।
- Native reserve/plain byte equality, exact-EOF, zero-frame, ENOSPC, unsupported fallback, EINTR এবং independent Python reader tests। Hardware CRC, pool3, capture limits, pairing policy, DNG/preview অপরিবর্তিত। নতুন device comparison pending।

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
