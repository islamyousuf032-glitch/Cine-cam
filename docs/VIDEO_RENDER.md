# MP4 রেন্ডার — saved RAW burst থেকে H.264

## উদ্দেশ্য

অ্যাপে তোলা ৮-frame RAW burst এবং DNG preview-গুলো phone-এর অন্য প্লেয়ারে/শেয়ারে চালানোর উপযোগী একক MP4 বানানো। এটি সম্পূর্ণ RAW grade নয়: preview colour pipeline-ই ব্যবহার হয়, শব্দ নেই, এবং long video নয়।

## কীভাবে

- Manage clips → **Clips** section-এর `.ccraw` কার্ডে **MP4**, অথবা **ছবি** section-এ capture খুলে **MP4 তৈরি করুন (8)**।
- প্রথমে প্রতিটি frame native preview থেকে (আগের মতো DNG→verified CCRAW fallback) ৯৬০px-পর্যন্ত আঁকা হয়; তারপর প্রয়োজন হলে exact video size-এ scale করে ARGB→YUV 4:2:0 conversion হয়।
- MediaCodec H.264 encoder + MediaMuxer। Color format ফোনের encoder capability থেকে NV12/I420 বেছে নেওয়া হয়; কোনোটিই না থাকলে পরিষ্কার error, crash নয়।
- PTS আসল sensor timestamp থেকে (প্রথম frame=0)। report-এ timestamp না থাকলে তবেই ৩ fps অনুমান/ধারাবাহিকতা; একই PTS যাতে না যায় সেজন্য সর্বনিম্ন 33.333ms ব্যবধান।
- ফাইল: API29+ → `Download/CineCam/CineCam_<capture>_render_<epoch>.mp4` (MediaStore, IS_PENDING→publish, আগের flat naming নীতি); API26–28 → app-specific Movies/CineCam। capture/DNG/RAW/archive-এ কোনো write/delete নেই।
- ব্যর্থ হলে অর্ধেক MP4/MediaStore row মুছে ফেলা হয়; সফল হলেই শেয়ার/খোলার বাটন।

## সীমা (সৎভাবে)

- ভিডিওটি ৮-frame burst-এর real-time render (যেমন ৩ fps); এতে sustained ৩০fps, audio sync বা দীর্ঘ recording প্রমাণ হয় না।
- রঙ preview D50 pipeline-এর; highlight recovery/lens correction/noise reduction নেই। ভিন্ন প্লেয়ারে রঙ সামান্য ভিন্ন দেখাতে পারে।
- Encoder availability, exact colour reproduction, playback compatibility—ফোনে যাচাই বাকি। এমুলেটর/লোকাল Android SDK নেই, তাই শুধু pure-Kotlin conversion/timing unit test আছে।
- ফাইল মাপ preview resolution (≤১২৮০px) ও bitrate-নির্ভর; কোনো নির্দিষ্ট আকারের প্রতিশ্রুতি নেই।

## Automated

`YuvConverterTest`: limited-range BT.601 white/black, red chroma direction, NV12 vs I420 layout, invalid dimensions/pixel count। `VideoRenderPlanTest`: 4080×3060→1280×960 even scaling, odd-size truncation, real timestamp spacing, duplicate separation, fps bounds। এগুলো JVM-এ চলে, Android encoder নয়।

## v96 — ভুল clip-এর ভিডিও (সমাধান) ও section ঠিক করা

**উপসর্গ:** নতুন `.ccraw` থেকে MP4 বানালে আগের capture-এর ভিডিও আসত; Report/Archive section-এ ভুল ফাইল দেখাত।

**কারণ ১ (রেন্ডার):** `PreviewLoader` cache key ছিল `<dng>|<frame>@<dim>`। CCRAW frame target-এ DNG URI null, তাই প্রতিটি clip-এর 0..7 frame একই key পেত → অন্য clip-এর cached ছবি ফেরত আসত। ঠিক করা হয়েছে `PreviewCacheKey` দিয়ে, যেখানে DNG URI, **CCRAW URI**, frame index, CFA, matrix mode, white level ও আকার থাকে।

**কারণ ২ (section):** list report-এ থাকা mapping-এর উপর নির্ভর করত। report/metadata না মিললে বা পুরোনো হলে archive section-এ `.ccraw` ঢুকে যেত। এখন প্রতিটি section সরাসরি folder-এর ফাইল নাম দিয়ে তৈরি হয়; Archive-এ কেবল `.ccraw.bp16`, Clips-এ কেবল `.ccraw`।

**বাড়তি:** report না থাকলেও CCRAW header থেকে width/height/frame সংখ্যা পড়া যায়; Clips-এ রেন্ডার করা MP4-এর তালিকা (কোন capture-এর ভিডিও) দেখা যায়।

## v98 — render settings (resolution / quality / pacing)

Before encoding, the **MP4** button now opens **ভিডিও সেটিংস**:

| ধাপ | অপশন | মানে |
|---|---|---|
| রেজোলিউশন | 540p / 720p / 1080p | video-র লম্বা দিক যথাক্রমে 960 / 1280 / 1920 px; source ছোট হলে **upscale হয় না** |
| কোয়ালিটি | ছোট / স্বাভাবিক / উচ্চ | encoder bitrate = width×height×2 / ×4 / ×8, clamp **2–20 Mbps** |
| frame-এর সময় | আসল / সমান ১০ fps | আসল = sensor timestamps অনুযায়ী burst-এর নিজের গতি; সমান = প্রতিটি frame সমান 100ms ব্যবধানে (ছোট, স্থির clip) |

- নির্বাচন SharedPreferences-এ থাকে; পরের রেন্ডারে একই অপশন আগে থেকে বাছা থাকে।
- Frame decode এখন নির্বাচিত `maxDimension`-এ হয় (আগে সবসময় 1280), তাই 540p রেন্ডার দ্রুত ও হালকা, 1080p-এ বেশি বিশদ।
- PTS নিয়ম অপরিবর্তিত: প্রথম frame 0, ন্যূনতম 33.333 ms ব্যবধান, duplicate PTS নয়; সমান-১০fps মোডেও এই minima প্রযোজ্য।
- bitrate clamp বদলেছে: আগে সর্বোচ্চ 12 Mbps, এখন quality-নির্ভর সর্বোচ্চ 20 Mbps; ন্যূনতম 2 Mbps আগের মতোই।
- `RenderOptionsTest` ও বর্ধিত `VideoRenderPlanTest` presets, scaling, bitrate, pacing যাচাই করে (JVM; Android encoder এখনো ফোনে যাচাই বাকি)।

## v103 — frame window for long clips

A long session can hold hundreds of frames; rendering every frame takes minutes. When a clip has more
than 24 frames the render dialog adds **সব / প্রথম / শেষ** chips:

- window = `0..24` (প্রথম), `size-24..24` (শেষ), অথবা সব; ছোট clip-এ সবসময় পুরোটা।
- নির্বাচিত window-এর প্রতিটি frame-এর **আসল sensor timestamp** অটুট থাকে (render offset অনুযায়ী timestamp slice), তাই clip-এর মাঝের/শেষের অংশ render করলেও গতি বদলায় না।
- `RenderRangeTest` window সীমা, ছোট clip, custom chunk ও সেভ করা নাম যাচাই করে।
