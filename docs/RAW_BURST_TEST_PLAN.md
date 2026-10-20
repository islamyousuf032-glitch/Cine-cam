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
