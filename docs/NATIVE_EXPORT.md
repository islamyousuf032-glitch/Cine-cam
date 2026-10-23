# Native descriptor export — 2026-10-22

উদ্দেশ্য: v79 full8-frame run-এর প্রায়516MB export-copy/23.69s path-এ Java userspace copy কমানো এবং এক পরীক্ষায় copy বনাম flush আলাদা মাপা। Speedup বা zero-copy physical storage guarantee নয়।

## পথ

- আগের flat unique-prefix MediaStore names/IS_PENDING, collision check, final report-last ordering অপরিবর্তিত। API26–28 fixed app-external destination-ও একই native copier ব্যবহার করে।
- Kotlin নতুন output descriptor খুলে C++-কে ধার দেয়; C++ source read-only open করে। Native method descriptor close করে না; Kotlin use/close ব্যর্থ হলেও transaction rollback/source retention।
- Regular source সর্বোচ্চ1GiB; output regular,empty,position0 ও source থেকে আলাদা inode হতে হবে। Invalid/readonly/nonregular destination fail-safe; সব provider support করে এমন দাবি নয়।
- Kernel sendfile সর্বোচ্চ1MiB/request; partial/EINTR retry। EINVAL/ENOSYS/EOPNOTSUPP/EXDEV-এ successful explicit source offset থেকে bounded256KiB pread/write fallback। Existing kernel bytes আবার লেখা নয়। ENOSPC/EIO/EBADF-এ fallback করে error লুকানো নয়।
- Exact source/destination byte counts ও final sizes মিলিয়ে **destination fsync**; তারপর Kotlin descriptor close। Pre-export RAW/archive verification অপরিবর্তিত। Size check/fsync মানে public destination byte readback/cryptographic verification নয়। Same-length concurrent source mutation detection নেই; staging files এই stage-এ আর লেখা হয় না।
- পুরোনো export path-এ explicit per-file fsync ছিল না। এখন আছে: durability বাড়াতে explicit flush point, তাই কিছু storage-এ সময় বাড়তেও পারে। flush বাদ দিয়ে speed claim নয়। Original source সব output publish না হওয়া পর্যন্ত রাখা হয়; failure-এ নিজের inserted rows/new files rollback চেষ্টা, private source retained।
- Native pipeline pool/codec/DNG pixels/EXIF/default archiveOFF এবং file count অপরিবর্তিত। App foreground-এ রাখতে হবে। Parallel copies না করায় storage contention বাড়ানো হয়নি।

## একসঙ্গে diagnostics / progress

`exportCopyBackend=native-sendfile-with-bounded-fallback-v1`; `exportCopyDetails` প্রতিটি non-report data file-এর exported basename/ok/bytes/kernelBytes/bufferedBytes/copyNs/fsyncNs/error। Copy time source open/stat ও Kotlin insert/query/close বাদ; fsync আলাদা। `exportDataCopyNs` aggregate data-file insert/query/copy/sync/close-সহ, reports/publish বাদ। Per-file total aggregate-এর সঙ্গে আবার যোগ নয়। Report files নিজেদের timing snapshot-এ ঢোকানো হয় না। Error হলে details partial।

In-app progress `Export n/total: name`; বাংলা readable report-এ per-file copy/flush/backend bytes। এক capture-এর চার sidecar যথেষ্ট; আলাদা প্রতিটি diagnostic-এর জন্য নতুন APK/test লাগবে না।

## Automated পরীক্ষা

ASan/UBSan native suites: normal kernel/forced-buffered exact-byte copy,1MiB-এর বেশি input/tail, injected EINTR→13byte kernel→EINVAL→exact fallback, ENOSPC without unsafe fallback, readonly/invalid/self/nonempty fd rejection, empty source, borrowed fd remains open, source retained। Existing archive reference encoder/restore ও pipeline tests বজায়। Kotlin readable report tests export details এবং no-readback disclaimer cover করে। Android MediaStore/provider behavior ও physical timing আলাদা phone test-এ যাচাই বাকি।

## Parallel export (v102)

- `copyExportFile`-এর এক-ফাইল সীমা ছিল 1 GiB; দীর্ঘ session-এ CCRAW 1 GiB ছাড়ালে export ব্যর্থ হতো। এখন সীমা **8 GiB** (session/archive-এর সঙ্গে সামঞ্জস্যপূর্ণ)।
- ডেটা ফাইলগুলো এখন **3টি worker**-এ সমান্তরালে copy+fsync হয় (`BoundedParallel`, index-order ফলাফল)। ফাইলের নাম, সংখ্যা, ক্রম বা CRC-নীতি বদলায় না; শুধু এক ফাইলের fsync-এর সময়ে অন্য ফাইল copy হয়।
- কোনো ফাইল ব্যর্থ হলে বাকি সব worker **আগে বন্ধ** করা হয়, তারপর আংশিক MediaStore row/ফাইল মুছে ফেলা হয় — cleanup কখনো চলমান লেখার সঙ্গে দৌড়ায় না।
- report-এ `exportConcurrency` লেখা থাকে; per-file `copyNs`/`fsyncNs` আগের মতোই আলাদা।
- DNG sequence-ও একই ভাবে 3 worker-এ তৈরি হয় (`dngSequenceWorkers`) এবং 1 MiB buffered stream ব্যবহার করে; `sequence.json`-এর ক্রম index অনুযায়ী, completion অনুযায়ী নয়।
- যন্ত্র-তথ্য (v0.1.105, 40 frame, 2.55 GB): DNG sequence 29.99 s, export copy 133.96 s — এর বড় অংশ 40টি DNG-র আলাদা আলাদা ~1.5 s fsync। समानांतरকরণের লক্ষ্য এই অপেক্ষা overlap করা; কোনো নির্দিষ্ট সময়ের প্রতিশ্রুতি নয়।
