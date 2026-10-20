# Galaxy F23 — v0.1.67 user tests (2026-10-20)

ব্যবহারকারীর pasted JSON reports ও screenshot থেকে নিচের ফল নথিভুক্ত। Original `.ccraw` file পাওয়া যায়নি; independent CRC/visual pixel validation হয়নি। Screenshot-এ rounded 200 MB ও 150 MB sizes, report-এ exact byte counts আছে।

| Measurement | Paced A | Paced B | Burst |
|---|---:|---:|---:|
| Images/results/matched | 8/8/8 | 8/8/8 | 8/8/8 |
| Accepted/written | 8/8 | 8/8 | 6/6 |
| Native drops | 0 | 0 | 2 |
| poolExhausted | 0 | 0 | 2 |
| Other rejection reasons | 0 | 0 | 0 |
| Pool peak / total | 1/3 | 1/3 | 3/3 |
| Sensor span (ms) | 1965.915208 | 1932.594583 | 299.885364 |
| Row copy total (ms) | 171.145417 | 104.804218 | 160.503958 |
| Payload CRC total (ms) | 555.727500 | 351.010209 | 300.681926 |
| write() total (ms) | 289.289061 | 166.242395 | 326.471563 |
| Successful append total (ms) | 844.985467 | 517.198281 | 627.087085 |
| fsync (ms) | 215.574219 | 2548.921717 | 273.181406 |
| Wall until drain (ms) | 2603.906770 | 4935.908540 | 1234.347708 |
| Final file bytes | 199757664 | 199757664 | 149818272 |

সব report: schema `cinecam-raw-test-2`, app `v0.1.67-alpha-24a8b7e-debug`, CRC `slicing-by-8`, submitted=8, failed=0, nativeStopOk=true, nativeError empty, final poolAvailable=3, ringSize=0, writerStatus=0, partialFileBytes=0। Paced capacity wait=0—native capacity-র অপেক্ষায় থামেনি; single still-request/camera delivery cadence-ই ধীর ছিল। এর সব delay একক camera stage-এ নিশ্চিতভাবে attribute করা যায় না।

## কী বোঝা যায়

- এই Burst run-এ দুটো rejection-এর সরাসরি কারণ poolExhausted, metadata/layout mismatch নয়। Writer/copy/camera scheduling মিলে pool capacity ছুঁয়েছে; storage একমাত্র bottleneck বলা যাবে না।
- Burst successful append mean ~104.5ms/frame, CRC ~50.1ms/written frame, write calls ~54.4ms/written frame। Row copy mean ~26.8ms/accepted frame; first three copies বেশি ব্যয়বহুল। Time intervals overlap across threads, তাই totals যোগ করে whole-pipeline elapsed time নয়।
- Paced sensor interval average ~276–281ms (7 intervals); ~3.6 delivered frames/second in these short samples। এটি sustained FPS benchmark নয়।
- 8-frame size = 64 + 8×(96+4080×3060×2)+32 = 199757664। 6-frame size =149818272। Size difference frame count-এর কারণে; compression নেই।
- fsync 0.216–2.549s variable; flush বাদ দিয়ে দ্রুত finalization দেখানো যাবে না। New patch final stop/drain time আলাদাও দেখাবে।
- আগের v66 Burst 4/8 বনাম v67 6/8 উন্নত result হলেও scene/ISO/caching/CPU scheduling এক ছিল না; controlled speedup/causation proof নয়।

## পরের patch/test

Runtime HWCAP-gated ARM64 IEEE CRC32 instructions; known-vector/alignment/tail self-test এবং portable software fallback। Whole app-এ +crc ISA requirement বসানো নয়। একই 3-slot pool, raw byte format, payload size, capture mode ও fsync policy থাকবে। CI: x86_64/ARM64 sanitizers + hardware/forced-fallback/self-test-failure paths + Python reader। নতুন ফোনের report-এ backend/self-test/CRC time এবং Burst rejects তুলনা করতে হবে; 30fps/8-frame guarantee নয়।
