# কাজের ধারাবাহিকতা — 2026-10-19

## সেশন ও workflow

- এই সেশনের ব্রাঞ্চ: `arena/30298c6b-cine-cam`। এখানেই কাজ রাখতে হবে।
- baseline: `0df9156` (আগের PR #37 merge)।
- ব্যবহারকারীর স্পষ্ট অনুমতি ছাড়া PR merge/close নয়।
- প্রথম foundation patch commit `081820d` এই ব্রাঞ্চে push হয়েছে; merge/close করা হয়নি।
- Remote run `39267934860`: native tests সফল; setup-android ধাপে ব্যর্থ। Android compilation শুরু হয়নি।
- পরের patch-এ ubuntu-24.04 এবং runner-এর preinstalled SDK ব্যবহার করা হয়েছে; ফল যাচাই বাকি।
- সম্পূর্ণ অ্যাপ বা ধাপ ১-এর APK build যাচাই সম্পন্ন বলা যাবে না।

## পরীক্ষা

- `./scripts/test-native.sh` ASan/UBSan সহ স্থানীয়ভাবে সফল।
- এই পরিবেশে java/cmake/Android SDK নেই; g++ আছে।
- GitHub run list-এ baseline main-এর run `39255730366` failed।
- `gh run view --log-failed`-এর log download restricted host-এর কারণে সম্ভব হয়নি; failure-এর নির্দিষ্ট কারণ জানা যায়নি।

## পরবর্তী কাজ

1. ব্যবহারকারীর সম্মতিতে এই ব্রাঞ্চ push করে CI পরীক্ষা; build failure হলে প্রকৃত error অনুযায়ী ছোট patch। Merge নয়।
2. CameraController ও CameraScreen lifecycle/permissions audit। RAW feature চালুর আগে preview স্থিতিশীল করা।
3. ফোনের model, Android version, Camera2 capability report সংগ্রহ। RAW-capable rear camera ও supported stream combination নির্বাচন।
4. C++ queue drop/clear ownership, engine stop/drain/release ordering, JNI bounds এবং writer error propagation ঠিক ও regression test যোগ।
5. সঠিক metadata-সহ still DNG; independent decoder দিয়ে validation।
6. তারপর bounded RAW recording, compression, audio sync, thermal/storage stress tests।

## acceptance checklist

- [ ] CI Android build ও lint সফল
- [ ] Release APK download ও ফোনে install
- [ ] Permission deny/grant, background/resume, rotation-এ preview নিরাপদ
- [ ] বাস্তব RAW formats/resolutions/FPS report
- [ ] ISO/shutter/focus সীমা সঠিকভাবে প্রয়োগ
- [ ] DNG decoder round-trip ও color metadata validation
- [ ] recording stop/drain, storage-full ও dropped-frame report
- [ ] long-record thermal/RAM/throughput পরীক্ষা
- [ ] stable signing ও update-install পরীক্ষা
