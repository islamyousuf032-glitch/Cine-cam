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
