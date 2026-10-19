# Galaxy F23 — ধাপ ৩ পরীক্ষা

## কী আছে

Camera 0 দিয়ে preview+RAW still session; ISO/shutter/focus controls, DngCreator export। Camera2 metadata ও Android storage API Kotlin-এ; এখনো C++ RAW processing/ভিডিও recording নয়। DngCreator ব্যবহার করার কারণ: ডিভাইসের calibration ও CaptureResult দিয়ে প্রকৃত DNG তৈরি; native stub-এর মতো raw bytes-কে .dng নাম দেওয়া নয়। RAW_SENSOR-এর container 16-bit হলেও প্রকৃত sensor bit depth অনুমান করা হচ্ছে না।

## ফোনে পরীক্ষা

1. নতুন APK খুলে preview-এর status-এ “DNG প্রস্তুত” দেখুন। Session না হলে preview-only fallback/error-এর message পাঠান।
2. Manual exposure বন্ধ রেখে “RAW DNG তুলুন” চাপুন। “DNG সংরক্ষিত” message আসা পর্যন্ত অপেক্ষা করুন।
3. Android 14-এ My Files → Internal storage → DCIM → CineCam-এ .dng খুঁজুন। Gallery-তে DNG thumbnail না আসা মানেই file invalid নয়।
4. Lightroom/অন্য স্বাধীন DNG decoder-এ খুলুন। Resolution 4080×3060, EXIF ISO/shutter, orientation ও image content যাচাই করুন। File size ও decode error থাকলে পাঠান।
5. “Manual controls” খুলে manual exposure চালু করুন। একই shutter-এ ISO 100 → 800 এবং একই ISO-তে shutter 1ms → 20ms বদলে preview ও DNG পরিবর্তন তুলনা করুন। সীমার বাইরে request পাঠানো উচিত নয়।
6. Auto focus বন্ধ করে 0 D (infinity) এবং কাছের focus তুলনা করুন। Focus distance সর্বোচ্চ device-advertised limit অনুযায়ী হবে। Focus peaking নেই।
7. Manual exposure বন্ধ করলে auto exposure ফিরে আসে কি না দেখুন। WB এখন Auto।
8. Portrait ও landscape-এ আলাদা DNG তুলুন এবং decoder-এ orientation দেখুন।
9. ১০টি still পরপর (আগের save শেষ হলে) তুলুন; busy অবস্থায় আরেকটি capture গ্রহণ করা হবে না।
10. Capture-এর সময় Home/lock করুন, ফিরে এসে preview/capture পরীক্ষা করুন। Pending save worker-এ শেষ হতে পারে; busy state clear হওয়া উচিত।
11. Camera privacy off/on ও retry পরীক্ষা করুন। Storage-full handling বাস্তব ফোনে পরীক্ষা হয়নি; আপনার গুরুত্বপূর্ণ data মুছবেন না বা storage ইচ্ছাকৃতভাবে ভরবেন না।

## রিপোর্ট

APK version, DNG success/error message, file size, decoder-এর নাম, ISO/shutter EXIF ও একটি original DNG sample দিন। RAW file-এ location/scene-sensitive content থাকতে পারে; শেয়ার করার আগে বিবেচনা করুন।
