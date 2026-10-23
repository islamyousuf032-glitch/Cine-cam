# দীর্ঘ (bounded) RAW session — v99

আগের সংস্করণে এক capture-এ সর্বোচ্চ **৮ frame** এবং ~২০০ MB লেখা হতো। এখন ব্যবহারকারী
দৈর্ঘ্য (৩/৫/১০/২০/৩০ সেকেন্ড) ও target fps (২/৩/৫) বেছে নিতে পারেন। session **সবসময় সীমিত**:
কত frame লেখা হবে তা আগেই হিসাব করা হয়, এবং প্রতিটি request একবারে একটি।

## পরিকল্পনা (pure Kotlin, `RecordingPlanner`)

```
frameBytes = width × height × 2
usable     = min(মুক্ত জায়গা − 1 GiB reserve, 8 GiB)
byStorage  = usable / (frameBytes × copyFactor)
slots      = seconds × fps                      (সর্বোচ্চ 180)
maxFrames  = min(slots, byStorage)
```

- `copyFactor` = ১.০ (শুধু CCRAW) + ১.০ যদি প্রতিটি frame-এর DNG চাওয়া হয় + ০.৬ archive-এর জন্য।
  অর্থাৎ DNG/archive চাইলে একই জায়গায় কম frame ধরবে — এটাই সঠিক ও নিরাপদ হিসাব।
- `sessionBytes` = frames × frameBytes + 64 + 32 + frames × 96; এটাই native writer-এ দেওয়া হয়।
- ১ GiB reserve কখনও ভাঙা হয় না; জায়গা কম হলে session শুরুর আগেই পরিষ্কার error দেখানো হয়।
- `storageLimited` হলে UI ও report-এ "জায়গা অনুযায়ী কমেছে" লেখা হয়।

## Pacing ও stop শর্ত

- প্রতিটি request ঠিক `start + slot × (1e9/fps)` ns-এ যায়; একবারে **একটির বেশি request নেই**
  (3-buffer pool ও আগের ৮-request নীতি অটুট)।
- দেরি হলে ওই slot বাদ পড়ে এবং `skippedSlots` হিসেবে report-এ লেখা হয়; কোনো frame জোর করে
  ঠেলে দেওয়া হয় না।
- session থামে: frame budget শেষ, duration শেষ, ব্যবহারকারীর Stop, অথবা writer **৩ সেকেন্ড**
  ধরে কোনো buffer না দিলে ("storage frame নিচ্ছে না")। প্রতিটি কারণ report-এর `reason`-এ থাকে।
- safety timeout = চাওয়া দৈর্ঘ্য + ৬ সেকেন্ড (কখনোই অসীম নয়)।

## আসল rate (সৎভাবে)

`achievedFps` = (accepted − 1) × 1e9 / (শেষ − প্রথম sensor timestamp)। এটি storage/grading
গতির উপর নির্ভর করে; **fixed FPS, sustained 30 fps, audio বা MP4 কোনো দাবি নয়**।
F23-এ আগের পরিমাপ ছিল ~৩ fps, তাই বাস্তবে ৩ fps-এর বেশি চাওয়া মানে অনেক slot বাদ পড়বে।

## Native পরিবর্তন

- `CcrawWriter::open(..., preallocate, maxFrames, maxBytes)`: আগের হার্ডকোড `count >= 8` ও
  256 MiB payload সীমার বদলে session-নির্দিষ্ট সীমা। preallocation এখন `maxFrames`-এর জন্য জায়গা
  রাখে (আগের ডিফল্ট ৮/২৫৬ MiB অপরিবর্তিত)।
- `RecordingConfig.maxFrames` / `maxBytes` JNI দিয়ে আসে; JNI-তে সর্বোচ্চ 100000 frame ও 64 GiB clamp।
- `CcrawVerifier` ২০০০ frame / 8 GiB পর্যন্ত যাচাই করে; DNG sequence ও report-এর limit বাড়ানো হয়েছে।
- `.bp16` archive limit ২৫৭ MiB → 8 GiB (ব্লক-স্ট্রিমিং, memory বাড়ে না)।
- CCRAW preview আর ১ GiB/৬৪-frame-এ আটকায় না; frame সংখ্যা ফাইল মাপ থেকে বের হয়।

## যা বদলায়নি

- ৩-slot native pool, প্রতিটি request একবারে একটি, 8-request burst mode (paced ছাড়া)।
- মূল `.ccraw`/`.dng`/`.exif`/archive অপরিবর্তিত; flat `Download/CineCam`; report বাংলা।
- fsync বা নির্দিষ্ট MB/s-এর কোনো প্রতিশ্রুতি নেই; ফোনে যাচাই ছাড়া performance দাবি নেই।

## পরীক্ষা

JVM: `RecordingPlanTest`, `PacedRequestGateTest` (দীর্ঘ budgetেও একটির বেশি in-flight নয়),
`CcrawHeaderTest`, `DngSequencePlanTest`।
Native: `tests/native/pipeline_test.cpp` — ৪০-frame long session file size, byte-budget rejection,
invalid limits।
Android encoder/camera যাচাই ফোনে করতে হবে (`docs/RAW_BURST_TEST_PLAN.md`-এর ধাপ অনুযায়ী)।
