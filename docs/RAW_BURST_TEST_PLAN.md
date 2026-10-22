# Galaxy F23 — সীমিত RAW burst পরীক্ষা

## প্রস্তুতি

- নতুন build-এ আগে একটি DNG তুলে preview/manual regression দেখুন।
- অন্তত **650 MiB free storage** রাখুন। F23-এর সর্বোচ্চ 8 RAW frames প্রায় 200 MB; export চলাকালীন temporary duplicate লাগে। RAM budget পৃথক।
- এটি MP4 নয়। Gallery/player-এ `.ccraw` চলবে না। Audio, compression বা sustained recording নেই।

## পরীক্ষা

1. প্রথম test Auto exposure ও স্থির দৃশ্যে করুন। Controls/preview সঠিক দেখুন।
2. **RAW test • সর্বোচ্চ 8 frames** → warning পড়ুন → **টেস্ট শুরু করুন**।
3. Burst auto-stop ও export শেষ হওয়া পর্যন্ত অপেক্ষা করুন। Native pool full হলে 8-এর কম frame লেখা স্বাভাবিক diagnostic result; এটি গোপন করা হবে না।
4. **শেষ RAW test report** → **কপি করুন**, পুরো report chat-এ দিন। acceptedFrames, native.framesWritten, matchedButRejected, native.framesDropped, nativeStopOk, sensorSpanNs এবং wallUntilDrainNs দরকার। wall time-এ setup/drain থাকতে পারে; এটি capture FPS নয়।
5. My Files → Internal storage → Download → CineCam → session folder-এ `capture.ccraw`, `metadata.json`, `report.json` আছে কি না দেখুন। Failed/incomplete test-এ `.partial` থাকতে পারে; report/error পাঠান।
6. প্রথম test সফল হলে আরেকবার manual ISO/shutter দিয়ে test করুন; দীর্ঘ shutter দিলে test-এর frame duration বাড়বে, 30fps আশা নয়।
7. আলাদা test-এ user stop এবং Home/background → resume পরীক্ষা করুন। Burst বন্ধ হয়ে native drain/export শেষ হওয়া উচিত; ফিরে এসে DNG/preview চালু থাকতে হবে।
8. সবসময় আগে save/export শেষ হতে দিন; process kill/power-loss recovery এখনো পূর্ণ নয়। Export failure-এ app-private temporary clip রাখা হয়; uninstall/clear app data করলে সেই private data হারাবে। Published Downloads ফাইল আলাদা।

## কম্পিউটারে স্বাধীন যাচাই (ঐচ্ছিক)

```bash
python3 scripts/inspect_ccraw.py /path/to/capture.ccraw
```

Valid complete container হলে JSON summary ও frame metadata আসবে। Truncated/CRC-invalid/unfinished file হলে nonzero exit status। এটি RAW debayer/color renderer নয়। আপাতত বড় file upload না করে report পাঠালেই হবে। Scene ব্যক্তিগত হলে share করবেন না।

## নতুন Paced/Burst তুলনা (report schema 2)

আগের F23 result: 8 matched, 4 written, 4 rejected, clean stop। এর কারণ আলাদা counters ছাড়া নিশ্চিত বলা যায়নি।

1. নতুন APK-তে আগে DNG/preview regression দেখুন।
2. RAW test warning dialog-এ **Paced mode ON** রেখে test চালান। One request in flight; buffer free না হলে 10ms interval-এ capacity পরীক্ষা করে। এটি camera callback thread-এ sleep/spin নয়। 12-second limit শুধু capture window; stop/drain/export পরে আরও সময় নিতে পারে।
3. Export শেষ হলে **শেষ RAW test report** কপি করে পাঠান। report.captureMode=`paced` যাচাই করুন।
4. তারপর dialog-এ **Paced mode OFF** করে একই scene/exposure-এ Burst test; দ্বিতীয় report-ও পাঠান। Mode ছাড়া native pool size/checksum/limits এক। নতুন run-এর জন্য 650 MiB free-space check আবার হবে।
5. Paced-এ 8 frames পেলেও সেটি 30fps বা performance bottleneck solved প্রমাণ নয়; capture cadence ধীর/variable হতে পারে। Burst result তুলনায় প্রয়োজন।

### নতুন measurements

- `native.dropReasons`: poolExhausted/controlBusy/invalidLayout/timestampRejected/queueRejected/notRecording/writerUnavailable। এগুলো native copied-ingestion rejects; Java missing metadata/unsupported plane errors per-frame rejectionReason-এ।
- `poolPeakUsed`: maximum leased native slots; 3-এর বেশি হওয়া উচিত নয়।
- `rowCopyNs`, `maxRowCopyNs`: native row-packing/copy calls-এর elapsed সময়; JNI/driver/metadata wait বাদ।
- `writerTimings.crcNs`: RAW payload CRC হিসাবের সময়; small header CRC বাদ।
- `writeSyscallNs`: header/payload/footer write() elapsed calls; OS blocking/cache-এর প্রভাব আছে—pure physical disk benchmark নয়।
- `fsyncNs`: fsync elapsed time; `finalizeNs`-এ footer/fsync/close/rename আছে, তাই এগুলো যোগ করলে double-count হবে।
- `appendNs/maxAppendNs`: সফল full-frame append-এর total/max time, যার মধ্যে CRC ও frame write আছে; failed append-এর CRC/write totals আলাদা counter-এ থাকতে পারে।
- `pacedCapacityWaitNs`: capacity unavailable/busy থাকায় poll-ভিত্তিক wait; camera exposure/metadata delivery time নয়।
- Per-frame `nativeIngestNs`: JNI call+native ingestion; `pairingLatencyNs` sensor start থেকে matching moment (exposure/driver/queue অন্তর্ভুক্ত)। এগুলো standalone camera/disk FPS নয়।
- CRC backend `slicing-by-8` checksum-compatible; file size বা compression ratio বদলায় না। Actual device speed report দিয়েই বিচার করতে হবে।

এই patch audio, compression, MP4 playback বা calibrated DNG conversion চালু করে না। পুরোনো personal files স্বয়ংক্রিয়ভাবে মুছে ফেলা হবে না।

## Runtime hardware CRC patch পরীক্ষা

নতুন APK-তে আগে Burst (Paced OFF), পরে Paced ON test দিন। দুই report-এ `native.writerTimings.crcBackend` ও `hardwareCrcStatus` দেখুন। Supported+verified ARM64 path: `arm64-crc32`/`passed`; unsupported CPU: `slicing-by-8`/`unavailable`; self-test failure: `slicing-by-8-self-test-fallback`/`failed`। Backend fallback নিরাপত্তার জন্য; unsupported CPU-কে +crc চালাতে বাধ্য করা হবে না।

একই 3 native slots, 8-frame budget, no compression; 8 frames হলে আবার ~200 MB-ই হবে। `captureWindowNs` setupসহ stop requested পর্যন্ত, `stopDrainNs` native stop/drain/finalize call-এর elapsed time; `wallUntilDrainNs` export বাদ দেয়। fsync অপরিবর্তিত, তাই finalizing কয়েক সেকেন্ড থাকতে পারে। CRC দ্রুত হলেও copy/write/camera overhead-এর কারণে Burst-এ drop থাকতে পারে। Thermal/power/storage variation-এর জন্য ideally একই settings/scene-এ paired tests দরকার।

## Bounded pairing / pre-touch patch

Burst OFF/ON comparison একইভাবে করুন (Paced OFF = Burst)। New report: `pairingPolicy=bounded-acquisition-no-eviction`, `imageAcquisitionDeferrals`, received `imageTimestamps/resultTimestamps`, pending-at-stop arrays ও `evictedResultTimestamps`; exact matching এখনও mandatory। 2 held Images হলে unread ImageReader buffers acquire না করে metadata আসার পর আবার drain হয়। Driver backpressure হতে পারে; deadline অপরিবর্তিত।

`native.poolPreTouchNs` হলো আগে থেকেই বরাদ্দ 3টি native buffer-এর pages capture শুরুর আগে স্পর্শ করার সময়। `captureWindowNs`-এ এই setup আছে; rowCopyNs-এ নেই। Allocated bytes বাড়ে না, কিন্তু physical resident pages আগে materialize হয়। OS reclaim করতে পারে; warm-up মানেই first-copy latency কমবেই নয়।

লক্ষ্য: আগে 2টি unmatched pair হওয়া repeat হয় কি না, exact timestamp arrays মেলে কি না, first-copy max ও Burst written count কেমন বদলায়। fsync বাদ দেওয়া হয়নি; finalizing কয়েক সেকেন্ড থাকতে পারে। 8 frames হলেও sustained30fps নয়।

## File reservation A/B পরীক্ষা

নতুন warning dialog-এ দুটি আলাদা switch। এই তুলনায় **Paced OFF** রাখুন:
1. **আগে file space reserve ON** → Burst test → report।
2. কয়েক সেকেন্ড বিশ্রাম দিয়ে **আগে file space reserve OFF** → Burst test → দ্বিতীয় report।
ঐচ্ছিক Paced ON + reserve ON run দিয়ে full8-frame regression দেখুন। একই scene/exposure/power/thermal conditions যতটা সম্ভব রাখুন। 650MiB free-space guard অপরিবর্তিত।

`preallocationRequested` user choice। `native.writerTimings.reservedBytes>0` মানে reservation successful; `allocationError` errno-style result। Unsupported (EINVAL/ENOSYS/EOPNOTSUPP)-এ zero reservation ও regular writes; ENOSPC/other genuine error-এ start fail। `allocationNs` capture setup-এর অংশ, 12s capture deadline বা OS operation-এর total timeout নয়। Reserve setup-ও ধীর হতে পারে; pool/pre-touch/camera একই থাকে।

`maxWriteNs/writeCalls` write syscall-এর max elapsed/count (partial write/EINTR attempts included); `truncateNs/closeNs/renameNs` আলাদা finalize phase। These overlap enclosing finalize/append totals, তাই যোগ করে overall elapsed নয়। Unsupported/off reservation-এ truncate phase0; skipped rename0। Full fsync safety আছে; preallocation durability/30fps guarantee নয়।

সফল file-এর unused reserved tail সরানো হয়; final 6-frame file এখনও149818272 bytes, 8-frame199757664 bytes। Interrupted/failed preallocated `.partial` বড় logical size দেখাতে পারে—size থেকে completed frame count অনুমান করবেন না; complete footer/checksums/exact EOF দরকার।

## Internal staging A/B (reservation OFF)

এই পরীক্ষায় **Paced OFF, আগে file space reserve OFF**—দুটিই স্থির রাখুন:
1. **Internal staging ON** → Burst → report।
2. কিছুক্ষণ বিরতি → **Internal staging OFF** → Burst → দ্বিতীয় report।
ঐচ্ছিক Paced ON + Internal ON regression। Same scene/settings/power conditions রাখুন; 40ms exposure-এ30fps দাবি করা যাবে না।

`stagingLocation` actual `app-private-internal` / `app-specific-external`; `internalStagingRequested` UI choice। API26–28-এ internal disabled ও পুরোনো external fallback। No automatic performance-based path switching। `captureAvailableBytesAtPrepare` / `exportAvailableBytesAtPrepare` প্রত্যেকটিতে650MiB guard; একই volume হলে capacity যোগ করবেন না। Internal path app filesDir, not cacheDir (OS cache cleanup নয়), not a new physical disk guarantee।

`wallUntilDrainNs` native capture/drain পর্যন্ত, final copy বাদ। `exportDataCopyNs` data+metadata pending Downloads copy/insert/close পর্যন্ত; report copy/publish/cleanup বাদ। তাই native speedup হলেও final export slow থাকতে পারে। Successful export-এর পর temporary source delete; error-এ source retained, pending exports rollback। Private source file manager-এ সরাসরি দেখা যায় না; uninstall/clear-data করলে হারাবে। Export error পেলে report/error পাঠান, আগে app data clear/uninstall করবেন না; automatic retry UI এখনও নেই।

v70 reserve ON-এ errno95 হওয়ায় reservation হয়নি; 5/6 frames তুলনায় allocation benefit প্রমাণ হয়নি। New internal path-এ reservation support ভিন্ন হতে পারে, কিন্তু প্রথম তুলনায় OFF-ই রাখুন। Same pool3/8-frame budget/fsync; no compression/audio/sustained FPS claim।

## Saved file readback ও DNG sample

প্রথমে **Paced ON, reserve OFF, Saved RAW যাচাই + DNG sample ON** রাখুন; Internal staging default ON থাকতে পারে। Finalizing-এ extra time লাগবে: native flush-এর পরে full RAW readback, তারপর sample DNG, তারপর Download export। 650MiB guard অপরিবর্তিত; sample প্রায়25MB extra। 12s limit শুধু capture; verification/export OS operations total-time-bounded নয়।

Export folder-এ `capture.ccraw`, `metadata.json`, `report.json` এবং সফল হলে **first_frame.dng** থাকবে। DNG-টি ওই saved file-এর প্রথম frame; পুরোনো DCIM still-এর সঙ্গে গুলিয়ে ফেলবেন না। DNG-capable viewer-এ খুলে scene/orientation/exposure দেখুন, report পাঠান। Phone gallery DNG support/thumbnail behavior ভিন্ন হতে পারে; cannot-open screenshot/error-ও উপকারী।

Expected report: `readbackRequested=true`, `readbackOk=true`, `readbackFrames`=native.framesWritten, `readbackPayloadBytes`, `firstFrameDngOk=true`, sample timestamp=firstAcceptedSensorNs। Verification fail হলে sample তৈরি হয় না, report-এ error; original RAW/report export হয় কিন্তু UI verified-success দাবি করে না। DNG failure independently reported; RAW remains।

`readbackNs` ও `firstFrameDngNs` capture/wallUntilDrainNs-এর পরে, `exportDataCopyNs`-এ generated DNG-ও অন্তর্ভুক্ত। নতুন timings-কে আগের native writer benchmark-এর সঙ্গে যোগ করবেন না। Performance-only comparison-এ sample switch OFF রাখা যায়।

Verifier native CRC implementation ব্যবহার করে না; Java standard CRC32 দিয়ে saved bytes re-read। CRC authenticity/scene/color calibration প্রমাণ নয়; DngCreator matching capture metadata ব্যবহার করলেও actual visual decoder test দরকার। Native3-slot cap unchanged, verifier64KiB scratch; DngCreator's own memory is SDK-dependent। No continuous RAW, MP4/audio/compression/FPS claim।

## সব saved frame-এর DNG sequence পরীক্ষা

অন্তত **1GiB free**, Paced ON, Internal staging ON, reserve OFF, **Saved RAW যাচাই + DNG export ON** এবং **সব saved frame-এর DNG sequence ON** রাখুন। Extra RAW re-read + সব DNG লেখা + export-এ অনেক বেশি সময় লাগতে পারে; finalizing অবস্থায় app foreground-এ রাখুন। OS operations-এর total timeout guarantee নেই।

সফল export folder: capture.ccraw, metadata.json, report.json, **frame_000001.dng…frame_000008.dng**, **sequence.json**। এই mode-এ আলাদা first_frame.dng duplicate বানানো হয় না। 6টি saved frame হলে6টি DNG—drop হওয়া request বানিয়ে পূরণ করা নয়। প্রতি DNG নিজস্ব matching result/exposure/ISO/WB পায়।

Report চাই: readbackOk=true, dngExportMode=all-saved-frames, dngSequenceOk=true, dngSequenceFrames=native.framesWritten; dngSequenceBytes/dngSequenceNs। প্রথম ও শেষ DNG খুলে দৃশ্য/রং/orientation যাচাই করুন; report + ছোট sequence.json পাঠান। বিশাল RAW/সব DNG upload প্রয়োজন নেই।

sequence.json-এ real sensor timestamp/epoch-relative PTS আছে। Numbered DNGs NLE-তে imported হলে editor নিজের default fixed FPS বসাতে পারে—সেটি real capture cadence নয়। Formal CinemaDNG timecode/audio/frame-rate tag conformance বা NLE import certify করা হয়নি; এটি CinemaDNG-style still sequence।

শুধু আগের sample চাইলে sequence OFF: first_frame.dng +650MiB guard। Verification/export OFF-এ আগের performance-only path। Sequence failure হলে created DNG cleanup best-effort, success manifest লেখা হয় না, raw/report রক্ষা করে export; error পাঠান। Native3-slot allocation capture-এর পরে free হয়; SDK memory implementation-dependent। Private export failure-এ uninstall/clear-data নয়।

## Flat export / readable report regression

আগের successful settings-এই একটি নতুন sequence test করুন (Paced ON, Internal ON, reserve OFF, verification+all-frames ON; >=1GiB free)। নতুন user-visible capture folder হবে না; files সরাসরি **Download/CineCam**। প্রতিটি filename-এ একই unique capture prefix, যেমন `CineCam_<timestamp>_<id>_frame_000001.dng`। Old folders stay; deleting/migration automatic নয়। Still DNG path DCIM/CineCam আগেই flat, সেটি unchanged।

একটি8-frame result-এ13 files: RAW1 + DNG8 + metadata/sequence/report JSON3 + readable report.txt1। sequence.json `file` entries exported prefixed basenames-এর সঙ্গে মিলবে; relative base এখন Download/CineCam। User হাতে prefix rename করলে manifest নিজে বদলাবে না। New two captures must have different prefixes/no overwrite; আগের files খোলা যাচ্ছে কি না দেখুন।

JSON খুলতে না পারলেও `<prefix>_report.txt` পড়তে পারবেন অথবা app-এর **শেষ RAW test report**-এ সহজ সারাংশ দেখবেন। **JSON কপি** ও **সহজ রিপোর্ট কপি** আলাদা। Error হলে একই summary-তে error থাকবে; export failure-এ private source retained, app data clear/uninstall নয়।

Only new exports use flat layout; native temporary capture directories still unique/private (not user-facing output folders)। API26–28 uses one fixed app-external Movies/CineCam folder instead of MediaStore Downloads, same prefixed naming. No media file bytes/EXIF/capture scheduling changed।

## Lossless codec feasibility probe

আগের successful settings-এই test করুন (Paced ON, Internal ON, reserve OFF, verification ON; sequence চাইলে1GiB free)। Saved readback সফল হলে প্রথম saved payload C++ BP16 codec-এ encode→decode→memcmp হবে। No compressed files তৈরি হয়; `.ccraw`/DNG sizes একই থাকবে। Native read-only open; source overwritten/deleted নয়।

সহজ report-এ lossless roundtrip সফল/ব্যর্থ ও source→packed bytes পড়ুন; বিস্তারিত JSON `losslessProbe`-এ roundtripOk/inputBytes/packedBytes/blocks/readNs/packNs/unpackNs। **compressedRecordingEnabled=false, savedCompressedFile=false** intentional। সব frame/all scenes-এর ratio নয়; sensor white1023 থেকেও precision assume করা হয়নি। Full16 values roundtrip tests আছে, compressible নয় এমন input header overhead-এ বড় হতে পারে।

সময় capture window-এর পরে; probe64KiB blocks, মোট~192KiB scratch। Existing verifier CRC আলাদা, codec probe byte-exact roundtrip আলাদা। Compressed container format/decoder, production streaming codec choice ও actual sustained recording এখনও next work; এই পরীক্ষা speed/FPS guarantee নয়। Verification OFF হলে probe-ও skip। Probe failure report হলেও original RAW/DNG path অক্ষত থাকবে।

## ঐচ্ছিক actual lossless archive পরীক্ষা

আগের successful settings: Paced ON, Internal staging ON, reserve OFF, saved verification ON, সব saved-frame DNG ON। এবার **Lossless archive ON** করুন (ডিফল্ট OFF)। Staging ও export storage-এ অন্তত **1.5GiB free** রাখুন। Finalizing-এ archive encode+পুরো-file readback বাড়তি সময় নেবে; app foreground-এ রাখুন।

সফল8-frame sequence-এ আগের13টির সঙ্গে অতিরিক্ত1টি `<prefix>_capture.ccraw.bp16`—মোট14 files, একই flat Download/CineCam; নতুন subfolder নয়। `.ccraw`/সব DNG থাকবেই। Archive play/open করে video দেখা যাবে না; restore-এ original `.ccraw` ফিরে পাওয়া যায়। Original রাখায় মোট occupied space বাড়ে, archive অনুপাতে net storage সাশ্রয়ের দাবি নয়।

সহজ report-এ **Lossless archive: সফল ও byte-exact যাচাই**, original/archive bytes, encode/write ও verify সময় দেখুন। JSON `losslessArchive.ok=true`, `byteExactSourceComparison=true`, `format=CCBP0001`, `compressedRecordingEnabled=false` চাই। প্রথম-frame `losslessProbe` ফল থেকে আলাদা: archive পুরো container bytes-এর বাস্তব ফল। Failed/অসম্পূর্ণ archive public export হবে না; error report পাঠান, মূল RAW/DNG রক্ষিত থাকবে।

আগের মতো একই prefix-এর report.txt/report.json/sequence.json/metadata.json দিন; বিরাট RAW/DNG/archive upload আবশ্যক নয়। PC থাকলে independent restore নির্দেশনা docs/BP16_ARCHIVE.md অনুসরণ করে original-এর সঙ্গে SHA256/byte compare করা যায়। OFF রেখে আরেকবার test করলে অতিরিক্ত archive হবে না এবং আগের650MiB/1GiB guard থাকবে। New device archive success এখনও যাচাই হয়নি; sustained30fps/compressed hot-path প্রমাণ নয়।

## Archive latency breakdown পরীক্ষা

v77-এ actual archive সফল; এখন একই settings-এ আরেকবার test (archive ON, Paced/Internal/verification/all-DNG ON, reserve OFF,>=1.5GiB free)। একই prefix-এর চারটি ছোট sidecar পাঠান। নতুন readable report-এ archive **read/encode/CRC/write/fsync/close**, max-write, syscall ও block count পাওয়া যাবে। Capture→drain/RAW storage/Paced buffer অপেক্ষা/DNG/export data copy-ও দেখা যাবে। Stage wall time দিয়ে delay কোন অংশে ঘটছে বোঝা যাবে; এটি CPU-only benchmark বা speed improvement নয়। Format/output count/defaultOFF/source retention অপরিবর্তিত; নতুন subfolder নয়। Background load/temperature/storage state বদলাতে পারে, single-run থেকে causality সিদ্ধান্ত নয়।

## Coalesced archive writes পরীক্ষা

আগের settings অপরিবর্তিত রাখুন: Paced/Internal/verification/all-DNG/archive ON, reserve OFF,>=1.5GiB free। নতুন report-এ `blockWriteMode=coalesced-record-v1`, `ok`/`byteExactSourceComparison=true`, same14flatfiles চাই। F23-এর199,757,664byte RAW-এ3049blocks; full writes হলে3050writeCalls (আগে6099), partial/EINTR হলে বেশি হতে পারে। Pack/write/fsync/verify/export সময় আলাদা তুলনা করুন। Scene বদলালে archive ratio বদলাবে; fsync/background/thermal variability থেকে এক run-এ speedup নিশ্চিত নয়। চার ছোট sidecar পাঠান; huge binary প্রয়োজন নেই। মূল RAW/DNG/EXIF/verification/flush অক্ষত।

## Native export + একসঙ্গে timing পরীক্ষা

একটি full8-frame automatic test যথেষ্ট: আগের successful Paced/Internal/verification/allDNG/archiveON,reserveOFF,>=1.5GiB free; Stop নয়, foreground-এ finalizing/export শেষ হতে দিন। Same14flatfiles/8DNG/readback/archive byteExact চাই। In-app `Export n/total` progress দেখুন। চার sidecar পাঠান; report-এ প্রতি data file copy/fsync ও kernel/buffered bytes থাকবে। Kernel unsupported হলে buffered fallback expected, error নয়। Explicit destinationfsync এখন যোগ হয়েছে—copy দ্রুত হলেও flush-এ সময় লাগতে পারে; total সময় না দেখে speedup নয়। Export error হলে app data clear/uninstall নয়, source private-এ retained। নতুন provider FD path nonregular output reject করে, actual F23 behavior pending।
