# F23 v71 paired staging results — 2026-10-20

User screenshot + pasted JSON; original payload not supplied. Both files149818272 bytes (6 frames), not independent CRC/pixel validation.

| Metric | Private internal | App-external |
|---|---:|---:|
| Images/results/matched | 8/8/8 | 8/8/8 |
| Written / poolExhausted | 6 / 2 | 6 / 2 |
| Unmatched / pending / evicted | 0/0/0 | 0/0/0 |
| Acquisition deferrals | 3 | 5 |
| Native row copy total/max ms | 175.055209/76.803437 | 198.301406/64.202604 |
| CRC ms | 138.497449 | 137.448906 |
| write() ms | 1958.472136 | 1277.872707 |
| fsync ms | 3120.329738 | 2203.026666 |
| Stop/drain ms | 4448.665936 | 2954.007291 |
| Wall until drain ms | 6019.036665 | 4421.750207 |
| Export data-copy ms | 4799.629685 | 7860.049737 |

Both hardware CRC/self-test passed, reservation OFF, pool3/3 returned/ring0, no native error/partial, nativeStopOk true. Acquisition pausing actually occurred without unmatched loss in these runs; not a universal guarantee. Internal staging did not increase delivered frame count; native wait was longer internally, export copy shorter. Different sensor cadence/exposure (internal40ms; external20→30ms) and scheduling; no controlled storage winner/FUSE-only-cause claim. Both still drop2 from pool exhaustion.

Next milestone: optional/default saved-file readback using independent Java CRC32 (64KiB stream, strict headers/layout/footer/counts/exact EOF) followed by first saved payload→DNG using Android DngCreator + its exact original TotalCaptureResult/characteristics/orientation. Does not capture a new still or claim playable video. Separate readback/DNG timing, same native pool/bytes/fsync. Device DNG visual/calibration verification remains necessary.
