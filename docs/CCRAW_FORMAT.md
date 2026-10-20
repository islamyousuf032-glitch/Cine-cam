# CCRAW001 — experimental RAW burst container

এই container CineCam-এর diagnostic RAW burst-এর জন্য; MotionCam MCRAW, CinemaDNG বা MP4 নয়। এখনও production/interchange specification নয়। Sensor calibration companion `metadata.json` এবং matched per-frame values `report.json`-এ থাকবে। স্বাধীন reader `scripts/inspect_ccraw.py` stdlib-only, streaming memory bounded।

সব integer little-endian, float IEEE754 binary32। Supported Android ABIs RAW_SENSOR bytes little-endian unsigned 16-bit sample হিসেবে copy করে। StorageBits=16 সেন্সরের actual precision নয়; whiteLevel/calibration আলাদা metadata-তে। Row padding native pipeline strip করে; pixels পরিবর্তন/encode/compress করা হয় না।

## File header — 64 bytes

| Offset | Type/length | Meaning |
|---|---|---|
| 0 | 8 bytes | ASCII `CCRAW001` |
| 8 | u32 | header bytes=64 |
| 12,16 | u32 | width,height |
| 20 | u32 | storageBits=16 |
| 24 | u32 | fixed FPS=0 (not promised) |
| 28 | u32 | clock=1 (Android BOOTTIME) |
| 32 | u64 | recording epoch nanoseconds |
| 40 | 20 zero bytes | reserved |
| 60 | u32 | CRC32 of bytes 0..59 |

## Frame record — 96-byte header + width×height×2 payload

| Offset | Type | Meaning |
|---|---|---|
| 0 | 4 ASCII | `FRM1` |
| 4 | u32 | header bytes=96 |
| 8 | u64 | sensor timestamp, BOOTTIME ns |
| 16 | u64 | actual exposure ns from matching TotalCaptureResult |
| 24 | u64 | RAW payload bytes |
| 32,36,40 | u32 | width,height,packed row stride (=width×2) |
| 44,48,52 | u32 | actual ISO, source Camera2 frameNumber, storageBits=16 |
| 56 | float | focus distance diopters (0 if absent) |
| 60,64,68 | float | sensor neutral color point R/G/B (0 if absent) |
| 72 | u32 | CRC32 of RAW payload |
| 76 | u32 | CRC32 of frame header bytes 0..75 |
| 80 | 16 zero bytes | reserved |
| 96 | payload | row-major unpacked Bayer samples |

Only strictly increasing sensor timestamps >= epoch accepted। PTS = sensor timestamp − epoch; skipped/rejected frames leave their time gap intact। No synthetic frameNumber/fps-based PTS। Sensor timestamp BOOTTIME report ছাড়া Android burst adapter starts না।

## Completion footer — 32 bytes

| Offset | Type | Meaning |
|---|---|---|
| 0 | 4 ASCII | `END1` |
| 4 | u32 | footer bytes=32 |
| 8 | u64 | successfully written frame count |
| 16 | u64 | total RAW payload bytes |
| 24 | u32 | CRC32 of footer bytes 0..23 |
| 28 | 4 zero bytes | reserved |

CRC32 is the standard reflected IEEE polynomial 0xedb88320; compatible with Python zlib.crc32। It detects accidental corruption, not malicious tampering/authenticity।

## Limits/failure contract

- Native file writer: max 8 frames / 256 MiB total payload; Android adapter further limits requested payload to 192 MiB। Max dimensions 16384×16384 and per-frame payload <=256 MiB; engine pool allocation budget also applies।
- Android test: 3 native buffers plus ImageReader maxImages=3; hold <=2 unmatched Images and <=12 results। Full pool or invalid metadata is rejected/counts as dropped, not unbounded memory growth।
- C++ exclusively creates `capture.ccraw.partial`; finish writes footer, fsyncs, closes, then renames inside unique session directory। Existing final file is refused; no append/reuse of prior captures। On error/process kill partial remains; successful complete footer is not invented।
- Reader rejects truncated records, invalid CRCs, missing footer, invalid dimensions/timestamps/counts or trailing data। Recovery of intact frames from partial files is future work, not automatically supported。
- Integrity verification alone does not prove sensor/color calibration correctness。 No DNG conversion or playback viewer yet। `metadata.json` saves static matrices/CFA/levels/orientation; report carries neutral point, dynamic levels and crop when present。 It is not a full DNG metadata contract (lens shading etc. still need future work)।
- This short burst cannot establish sustained 30fps, thermal stability, storage durability after power loss, audio sync or long-record reliability。
