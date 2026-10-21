# পরীক্ষামূলক post-capture BP16 archive — CCBP0001

এটি **ঐচ্ছিক, default OFF**। Saved CCRAW001 verifier সফল হওয়ার পরে native C++ পুরো `.ccraw`-এর bytes archive করে। Capture writer, DNG pixels/EXIF ও flat Download/CineCam layout অপরিবর্তিত। Original `.ccraw` এবং DNG রাখা হয়: archive অতিরিক্ত file; **মোট storage কমে না**। এটি recording codec, ZSTD, playable video, DNG/CinemaDNG বা MotionCam/NLE format নয়। Ratio/সময় scene ও device নির্ভর; noisy16-bit data বড়ও হতে পারে।

## Format (সব integer little-endian)

24-byte header:

| Offset | Bytes | অর্থ |
|---|---|---|
| 0 | 8 | ASCII `CCBP0001` |
| 8 | 8 | Original file byte count; nonzero/even, সর্বোচ্চ257MiB |
| 16 | 4 | Raw block size, অবশ্যই65536 |
| 20 | 4 | প্রথম20 header bytes-এর IEEE CRC32 |

এরপর source শেষ হওয়া পর্যন্ত record:

- `encodedLength:u32` (8…65544), `decodedCRC32:u32` (IEEE CRC32 of decoded bytes)।
- ঠিক `encodedLength` bytes BP16 block: `BP16` magic4, bits:u8 (0…16), reserved:u8=0, sampleCount:u16 (1…32768), packed values।
- প্রতিটি source sample original দুইটি LE byte; actual maximum bit width (block samples-এর OR) দিয়ে lossless packing। Packed bits LSB-first। শেষ byte-এর অব্যবহৃত high bits অবশ্যই0।
- Encoded block length অবশ্যই `8 + ceil(sampleCount*bits/8)`। Decoded bytes অবশ্যই `min(65536, remainingOriginalBytes)`। Zero-width block-এর payload নেই; samples সব0।
- Footer নেই; declared source bytes restore হওয়ার পরে **exact EOF** প্রয়োজন। Missing/trailing/corrupt bytes প্রত্যাখ্যান। CRC accidental corruption check, cryptographic authentication নয়।

Full `.ccraw` header/frame headers/pixels/footer—সব bytes রাখা হয়; companion metadata/sequence/DNG archive-এর ভিতরে নেই। Sidecars-ও সংরক্ষণ করুন।

## Native safety / export

- Source `O_RDONLY`; নতুন destination `O_CREAT|O_EXCL`, mode0600; existing output কখনো overwrite নয়।
- ছোট fixed-size block buffers; archive stage-এ nested encode/verify stack scratch সর্বোচ্চ প্রায়320KiB (capture pool থেকে আলাদা); full-file Java byte array নেই।
- Write শেষ হলে fsync/close, archive ও original reopen, সব block decode+CRC, **পুরো original-এর সঙ্গে memcmp**, exact lengths/EOF; তারপরেই success।
- Read/write/sync/verification failure-এ নিজের newly-created output unlink চেষ্টা; source অপরিবর্তিত। Archive failure হলেও RAW/DNG/report export চলতে পারে। শুধু `ok` এবং `byteExactSourceComparison` সফল archive-ই public export-এ নেওয়া হয়।
- Process kill হলে unfinished private file থেকে যেতে পারে; automatic recovery/retry নেই। Public archive export সফল হওয়া পর্যন্ত private। CRC/full comparison export-এর **আগে**; destination storage-এর independent post-export readback এখনও নেই।
- Archive চাওয়া হলে staging/export উভয় জায়গায় conservative1.5GiB guard; free-space race/ENOSPC সম্ভব। Finalizing সময়ে foreground-এ রাখুন; extra encode/readback/copy সময় লাগবে।

## Independent restore (PC, Python3; extra package নয়)

```sh
python3 scripts/restore_bp16.py CineCam_PREFIX_capture.ccraw.bp16 restored.ccraw
python3 scripts/inspect_ccraw.py restored.ccraw
```

প্রথম command existing output overwrite করে না; malformed/truncated/checksum failure-এ নিজের incomplete output সরিয়ে দেয়। দ্বিতীয় command restored CCRAW structure/frame checksums যাচাই করে। Original `.ccraw` থাকলে additionally SHA256 বা `cmp` দিয়ে মিলিয়ে নিন। Restore tool নিজে independent Python implementation; Android/native decoder-এর binding নয়। Original binary phone থেকে upload ছাড়া developer সরাসরি আপনার archive byte-compare করেছে—এমন দাবি নয়।

Native ASan/UBSan tests + independent Python restore tests: real tiny CCRAW fixture, multi-block tail, সব0…16-bit widths, corruption/header/truncation/trailing EOF, source mismatch, exclusive no-overwrite। Android integration CI ও নতুন phone archive report আলাদাভাবে যাচাই করতে হবে।

## Encode-phase latency counters

নতুন report `losslessArchive`-এ `sourceReadNs`, `packNs`, `crcNs`, `writeNs`, `fsyncNs`, `closeNs`, `maxWriteNs`, `readCalls`, `writeCalls`, `blocks`। `timingScope=encode-phase-wall-time-no-verify`। Read/write syscall duration wall time; interrupted/failed attempts-ও count হয়, source EOF check/open/stat/unlink এতে নেই। Header/record/payload writes included। Blocks হলো সম্পূর্ণ লেখা records। `encodeWriteNs` aggregate-এর মধ্যে এই stage-গুলি ও loop/setup overhead; aggregate-এর সঙ্গে stage যোগ করবেন না। `verifyNs` আলাদা full-file read/decode/CRC/memcmp সময়; তার ভিতরের read/CRC উপরের encode counters-এ মেশানো নয়। No CPU-only/thermal/root-cause claim। Failure-এর counters অসম্পূর্ণ কাজের মাপ; success নয়।
