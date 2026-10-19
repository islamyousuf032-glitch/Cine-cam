# CineCam বেস আর্কিটেকচার

## প্রযুক্তি ও দায়িত্ব

- **C++17/NDK:** frame layout validation, memory pool, queue, processing, container, compression এবং storage writer — performance-critical কাজের প্রধান স্তর।
- **Kotlin/Android:** permissions, lifecycle, Camera2 characteristics/request/session, Compose UI এবং Android storage integration।
- **JNI:** সীমিত control API ও frame/metadata transfer। বর্তমান engine-এ `memcpy` আছে; এটি end-to-end zero-copy নয়।
- Build: AGP 8.5.2, Gradle 8.7, Kotlin 1.9.22, NDK 26.1.10909125, CMake 3.22.1; minSdk 26, compile/targetSdk 34।

## বর্তমান কোড বনাম লক্ষ্য

| অংশ | বর্তমান অবস্থা | বাকি যাচাই/কাজ |
|---|---|---|
| MemoryPool | page-aligned preallocation, mutex-protected free stack | native tests আছে; device memory budget ও lifecycle পরীক্ষা বাকি |
| RingBuffer | mutex/condition-variable queue, atomics-এ stats | lock-free নয়; dropped/cleared frame-এর pool ownership audit বাকি |
| RawFrame | non-owning pointer ও pool index, move semantics | RAII lease/ownership contract দরকার; move destination-এর পুরোনো slot caller-কে ফেরত দিতে হবে |
| Camera2/Compose | controller, capabilities ও UI কোড আছে | permission/session/surface lifecycle এবং physical-device preview পরীক্ষা বাকি |
| JNI/engine | bridge ও pool-এ pixel copy আছে | stride/bounds/lifetime ও concurrent stop/release audit বাকি |
| RAW writer/DNG | প্রাথমিক implementation আছে | calibrated metadata, decoder round-trip, disk error/recovery পরীক্ষা বাকি |
| Compression | config fields আছে | ZSTD implementation/linking নেই; compression সমর্থিত বলা যাবে না |
| GL renderer | native renderer/shader কোড আছে | live camera integration, correctness ও performance যাচাই বাকি |
| Audio | পূর্ণ sync pipeline নেই | capture, clock-domain mapping, mux/sync framework বাকি |

## লক্ষ্য pipeline

```text
Camera callback + matching CaptureResult
    → validate format / rowStride / capacity / timestamps
    → acquire bounded C++ pool lease
    → copy অথবা যাচাইকৃত NDK image-lifetime path
    → bounded queue (recording: drop newest + explicit counter)
    → writer thread
    → validated container + metadata + finalization
    → return pool lease
```

সব error/drop/stop path-এ buffer ফেরত দিতে হবে। Producer কখনো UI বা storage I/O-এর জন্য অপেক্ষা করবে না। Preview ও recording একই ownership অসতর্কভাবে ভাগ করতে পারবে না। 60fps এবং zero-copy লক্ষ্য, বর্তমান নিশ্চয়তা নয়।

## ফরম্যাটের সীমা

RAW_SENSOR সাধারণত 16-bit container-এ Bayer samples বহন করে; প্রকৃত sensor precision white level ও calibration metadata থেকে নির্ধারণ করতে হয়। RAW10/RAW12 packed stream-এর stride/packing আলাদা। DNG-তে সঠিক CFA, black/white levels, color matrices ও capture metadata লাগবে। `.mcraw` extension ব্যবহার করলেই MotionCam compatibility হয় না; custom format-এর versioned specification ও reader round-trip test দরকার।

## Build/release নিরাপত্তা

CI প্রথমে host C++ sanitizer tests চালাবে, তারপর Android tests/lint ও দুটি APK variant build করবে। Release job শুধু সব dependency job সফল হলে চলবে। Build job read-only; শুধু Release job-এ contents write permission। Log artifact ছাড়া কোনো diagnostic branch/force-push নেই। Production signing এখনো কনফিগার করা হয়নি।
