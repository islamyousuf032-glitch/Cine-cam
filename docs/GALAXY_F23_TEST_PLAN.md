# Galaxy F23 — ধাপ ২ physical-device checklist

ব্যবহারকারীর দেওয়া profile: Galaxy F23, RAM 6 GB, Android 14, Snapdragon 750G, Camera2 LEVEL_3। অ্যাপের report দিয়ে camera ID-ভিত্তিক capabilities নিশ্চিত করতে হবে; এই তথ্য থেকে RAW FPS বা bit depth অনুমান নয়।

## এই build-এর সীমা

শুধু Camera2 preview ও capability checker। RAW memory pool/recording/DNG এবং microphone চালু হয় না। Preview সর্বোচ্চ 1920×1080 bounded size পছন্দ করে; উপযুক্ত bounded size না থাকলে advertised সবচেয়ে ছোট size নেয়। Android camera preview path ব্যবহার করা হয়েছে; custom C++ debayer/Vulkan renderer নয়।

## পরীক্ষা

1. Fresh install → অনুমতি ছাড়া camera খুলবে না। Deny করলে crash নয়।
2. “ক্যামেরা অনুমতি দিন” → শুধু camera permission। Permanent deny হলে Settings থেকে grant করে ফিরে আসুন।
3. Live preview আসছে কি না দেখুন। Camera ID ও hardware level লিখে রাখুন।
4. Portrait/landscape/উল্টো orientation-এ বিষয়বস্তু সঠিক, বৃত্ত গোল এবং image mirrored কি না যাচাই করুন।
5. Home চাপুন → অন্য camera app খুলুন → CineCam-এ ফিরে আসুন। Preview পুনরায় চালু হওয়া উচিত।
6. Lock/unlock, দ্রুত ১০ বার background/foreground, split-screen/rotation পরীক্ষা করুন।
7. Camera privacy toggle off/on করে retry; error message আসা উচিত, crash নয়।
8. “Capability report” → “কপি করুন”; report chat-এ পাঠান। Camera ID, RAW sizes, RAW10/12, minFrameDuration, ISO/exposure limits দরকার।
9. ৫ মিনিট preview-তে গরম হওয়া/অস্বাভাবিক RAM/black preview আছে কি না লিখুন।

## ফল পাঠানোর ফরম্যাট

- APK version:
- Preview: কাজ করে / black / error (সম্পূর্ণ message):
- Portrait/landscape:
- Background/resume:
- Capability report:

minFrameDuration থেকে theoretical FPS দেখানো হয়, sustained RAW recording guarantee নয়। এই ধাপে কোনো recording benchmark নেই।
