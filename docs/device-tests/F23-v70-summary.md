# F23 v70 reports — 2026-10-20

Screenshot/pasted JSON only; original RAW binary independently verified হয়নি।

| Metric | Reserve requested ON | Reserve OFF |
|---|---:|---:|
| Submitted/images/results/matched | 8/8/8/8 | 8/8/8/8 |
| Accepted/written | 5/5 | 6/6 |
| Rejects (all poolExhausted) | 3 | 2 |
| Unmatched/pending/evicted | 0/0/0 | 0/0/0 |
| Reserved bytes | 0 | 0 |
| Allocation error / ns | 95 / 10000 | 0 / 0 |
| CRC total ms | 37.547292 | 51.863958 |
| Row copy total / max ms | 41.645052 / 10.384375 | 61.992293 / 20.368021 |
| write() total / max ms | 3220.221196 / 1420.771666 | 3692.356248 / 1595.184426 |
| fsync ms | 4708.886665 | 5548.672446 |
| close / rename ms | 0.045990 / 0.673594 | 0.021354 / 0.355990 |
| stop/drain ms | 7677.364164 | 8884.635049 |
| Final bytes | 124848576 | 149818272 |

Both hardware CRC/self-test passed, nativeStopOk true, writerStatus0, no error/partial, final pool3/3/ring0। Both runs actually used normal writes: errno95 is EOPNOTSUPP; preallocation fell back as designed. Therefore 5 vs6 frames is NOT reservation success/failure performance evidence. Exposure changed30→40ms in first run and40ms in second; capture cadence/ISO differ, not controlled throughput comparison.

File sizes exactly match5/6 frames, not corruption proof. Close/rename are small in these runs; large write()/fsync waits remain. Could involve filesystem layer, dirty throttling, storage/OS scheduling etc; not a pure disk benchmark or proof of a single cause. Exposure40ms itself excludes a guaranteed30fps cadence in these settings.

Next patch: optional/default app-private internal staging on API29+, external staging retained for same-build comparison. Final MediaStore Downloads export unchanged, same native bytes/pool3/CRC/fsync. Reservation default OFF to isolate path change; report actual staging and per-volume free space plus export data-copy time. API26–28 retain old external staging/export path. No sustained FPS/audio/compression claim.
