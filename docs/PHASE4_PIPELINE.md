# ধাপ ৪ — C++ buffer ও timestamp foundation

## কাজের সীমা

এই patch-এ pipeline-এর ভিতরের ownership/locking/error paths ঠিক ও clock mapping framework যুক্ত হয়েছে। RAW video recording, microphone capture, muxing বা compression চালু হয়নি। ফোনে আগে পরীক্ষিত preview, DNG ও manual controls-এর request/transform path অপরিবর্তিত। Capability report-এ sensor timestamp source নতুন তথ্য হিসেবে থাকবে।

## Ownership ও stop contract

- Engine/pool lifetime নিয়ন্ত্রণ mutex-এ serialize করা হয়। Capture callback try-lock ব্যর্থ হলে drop করে; stop/drain I/O শেষ হওয়া পর্যন্ত callback অপেক্ষা করে না। এই implementation lock-free নয়।
- `MemoryPool` আগে তৈরি, `RingBuffer` পরে; queue/consumer শেষ না হওয়া পর্যন্ত pool থাকতে হবে।
- Producer একটি slot acquire করে। `push` সফল হলে slot-এর ফেরত দেওয়ার দায়িত্ব queue/consumer-এর; reject হলে producer-এর। একই slot দুবার enqueue করা contract violation।
- DropOldest, clear ও queue destruction-এ queued slot pool-এ ফেরে। Pop করলে consumer write/discard শেষে slot ফেরত দেয়।
- `RawFrame` নিজে RAII lease নয়। `peek` তাই শুধু metadata/size দেয়—pixel pointer বা owned slot নয়। RawFrame দিয়ে arbitrary external ownership sharing সমর্থিত নয়।
- `pushPooledFrame` internal API; caller-কে engine-এর pool-এর একটি unique acquired slot দিতে হবে, reject হলে caller release করবে। Public acquisition/lifetime handle API এখনো নেই; JNI camera path copied ingestion-ই ব্যবহার করবে।
- Writer stop thread join ও drain-এর পরে pool ফেরত এসেছে কি না host test করে। I/O fail হলে পরবর্তী queued frame safe discard হয়; status/error প্রকাশ হয়।

## RAM ও RAW layout

Default 8 slots; engine allocation সর্বোচ্চ 256 MiB। F23 4080×3060 unpacked RAW16-এ প্রায় 200 MB pool, এর বাইরে Android ImageReader, preview, driver ও process memory লাগে। এটি মোট app RAM limit বা performance guarantee নয়।

Native ingestion এখন unpacked RAW_SENSOR-এর row stride ও last-row payload যাচাই করে। Packed RAW10/RAW12 নয়। Pool capacity ছাড়ানো padded layout silently truncate না করে reject হয়। Camera integration-এ measured rowStride অনুযায়ী allocation বা row packing যোগ করতে হবে।

## Clock framework contract

`SyncTimeline::begin(commonEpochNs, clock, offset)`:
- Common epoch হলো Android BOOTTIME/elapsedRealtimeNanos clock-এর recording start।
- Camera `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` হলে sensor timestamps BOOTTIME-compatible।
- `UNKNOWN` হলে externally measured sensor→common offset না দেওয়া পর্যন্ত video PTS ফেরত আসে না। Callback arrival time-কে exact sensor clock calibration ধরা যাবে না।
- Recording epoch-এর আগের বা duplicate/backward video timestamps reject হয়। Video PTS timestamp-ভিত্তিক, শুধু frameNumber/fps নয়; frame drop timeline compress করবে না।

Audio:
- ভবিষ্যৎ capture path-এ `AudioRecord.getTimestamp(..., TIMEBASE_BOOTTIME)` থেকে frame position/sample rate/common timestamp anchor দিতে হবে। অন্য timebase মেশানো যাবে না।
- Sample index থেকে integer nanosecond mapping; repeated rounding যোগ করে drift তৈরি করা হয় না।
- পরবর্তী measured timestamp বনাম anchor model-এর difference দিয়ে drift নির্ণয় করা যায়। এটি measurement helper—resampler বা automatic correction নয়।
- 32-bit frame-position wrapping, audio device restart, clock offset drift ও discontinuity recovery capture adapter-এ যোগ করতে হবে।

Framework এখনো camera/JNI/audio adapter বা container-এ wired নয়। বাস্তব lip-sync/recording পরীক্ষা হয়নি।

## পরীক্ষা

```bash
./scripts/test-native.sh
```

ASan + UBSan-এ original pool/frame tests এবং নতুন pipeline tests চলে:
- invalid capacity, DropOldest/DropNewest, repeated clear ও destructor reclamation;
- 5,000-frame ordered producer/consumer transfer;
- invalid allocation ও reinitialize/error-path deadlock regression;
- raw layout, idle rejection, oversize/truncated input;
- বাস্তব writer-এ synthetic frames, stop/drain ও retained written count;
- `/dev/full` symlink দিয়ে disk-write error (নিজের disk ভরানো নয়);
- unknown clock rejection, video monotonicity, 44.1/48kHz audio mapping, 2-hour position ও integer overflow।

Test timeout 30s; artifacts `build/native-tests`-এ, Git-এ নয়। Android API/driver throughput এই host tests মাপে না।

## পরবর্তী কাজ

1. Device-advertised RAW/preview stream combination এবং matching CaptureResult/Image metadata ধরে bounded camera ingestion।
2. Versioned, documented container + independent reader/round-trip tests; legacy `.mcraw` dump production format নয়। Native CinemaDNG stub ব্যবহার নিষিদ্ধ রাখা হয়েছে।
3. AudioRecord capture/clock adapter/JNI sync integration, recovery ও drift monitoring।
4. তারপর সীমিত recording UI এবং F23 storage/thermal/drop-rate test। Kelvin WB ও focus peaking-ও এখনো বাকি feature।
