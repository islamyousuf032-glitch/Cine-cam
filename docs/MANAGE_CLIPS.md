# Manage clips — প্রথম ব্যবহারযোগ্য ধাপ

## ব্যবহার

ক্যামেরার **Manage clips • saved captures** বোতাম খুলুন (capture/finalizing-এর সময় disabled)। **CineCam folder বাছুন** → Android folder picker-এ সরাসরি `Download/CineCam` নির্বাচন করে access দিন। Download root বা প্রতি capture-এর নতুন folder বানাতে হবে না। প্রথমবার folder বাছা লাগবে; persisted read access পরেরবার মনে থাকবে। App reinstall/access revoke হলে আবার বাছতে হবে। API26–28-এ আগের fixed app-external Movies/CineCam folder picker থেকে accessible হলে বাছুন; সব provider/app-specific folder Android-এ accessible হবে এমন নিশ্চয়তা নয়।

- MP4: Clips কার্ড বা ছবি-details থেকে silent H.264 render; docs/VIDEO_RENDER.md।
- Sections: **ছবি** (প্রতিটি DNG আলাদা; thumbnail/viewer/আগের-পরের), **Clips** (শুধু `.ccraw`, frame preview/share), **Lossless archive** (শুধু `.ccraw.bp16`, আকার/ratio/share), **Report** (report/metadata/sequence sidecar, যাচাইয়ের ফল)।
- Capture list: thumbnail (প্রথম DNG-এর native preview), timestamp/unique ID, written/target frames, drop, RAW/archive size, companion files found/expected।
- Details/viewer: বড় cover preview; প্রতিটি DNG **অ্যাপে দেখুন** (full-screen, আগের/পরের frame); RAW/archive এখনো photo হিসেবে খোলা হয় না। Preview render details docs/DNG_PREVIEW.md।
- Details: reason (user stop বনাম automatic), saved report-এর readback/DNG/archive ফল, actual available file sizes, missing/ambiguous file markers, পূর্ণ readable report/copy।
- প্রতিটি mapped available file **অন্য অ্যাপে খুলুন** বা **শেয়ার** করা যায়; compatible external viewer প্রয়োজন। URI read grant দিয়ে original file পাঠানো হয়, পুনরায় export/copy নয়। RAW/archive সাধারণ video নয়।
- Refresh folder পুনরায় পড়ে; folder বদলানো যায়। Back details→list→camera। SAF cancel করলে আগের selection অক্ষত। Camera permission না থাকলেও browser বোতাম পাওয়া যাবে।

এই build-এ in-app player/thumbnail renderer/scrubber/MP4/audio/continuous RAW recording নেই। Fake play/export-format buttons নেই। Existing report খুলতে নতুন capture/test দরকার নেই।

## Safety / scope

Read-only browser: file create/move/rename/delete বা original RAW/DNG/EXIF/capture/export pipeline পরিবর্তন নেই। নতুন user-visible folder নেই। Broad storage permission নেই; শুধু user-selected tree-এর read grant persist হয়। Report prefixed filename→exportSessionId→schema→flat layout→সব mapping-এর exact safe basename মেলে কি না যাচাই হয়। অন্য capture/path traversal/content URI report-এ থাকলে reject; open/share URI কেবল picker-এর document listing থেকে আসে। Missing files disabled, duplicate names ambiguous বলে বাদ।

Saved report সফল বললে সেটিই দেখানো হয়; manager payload CRC/byte comparison আবার চালায় না। File size/existence মেলে মানে exported bytes স্বাধীনভাবে যাচাই হয়েছে নয়। Malformed report warning হয়, অন্য valid clips দেখানো চলতে পারে। Private retained failed-export staging এই public-folder browser recover করে না।

## Bounded loading

এক level-এর সর্বোচ্চ5000entries, সর্বশেষ100report, প্রতি report64KiB UTF-8 bytes, mapping≤64files। Limit হলে incomplete notice; nested folders recursively scan নয়। Report/directory I/O background coroutine, cancellation checks, no full RAW/DNG buffers/native pool allocated by manager। Folder content actual capture হিসেবে নির্বিচারে বিশ্বাস নয়। Chronological display report filename epoch থেকে; device clock ভুল হলে date ভুল হতে পারে। Capture/frame maxima-কে video FPS হিসেবে দেখানো নয়।

## Automated / device validation

Pure Kotlin catalog tests: real v81 sidecar fixture, optional verification/archive, user-stop4/8, exact references, cross-capture/traversal rejection, malformed JSON/schema/layout, missing self mapping, UTF-8 byte/mapping limits, negative counters, candidate filter। Existing native sanitizer/reference/export-copy suites অপরিবর্তিত। Android CI unit/lint/debug/release-এ নতুন UI/parser compilation যাচাই; SAF/provider/navigation/share/persisted grant ফোনে যাচাই প্রয়োজন।

এক phone session-এ existing v81 capture দিয়ে list→details→DNG external-open→report copy/share→back→reopen→refresh পরীক্ষা করা যাবে। নতুন8-frame capture বা একই diagnostics পুনরাবৃত্তি দরকার নেই।
