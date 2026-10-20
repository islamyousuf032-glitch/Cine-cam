# F23 v68 — user report (2026-10-20)

Original RAW binary পাওয়া যায়নি; screenshot এবং pasted JSON থেকে ফল। Paced report-এর `fsyncNs` key-এ chat formatting newline আছে; 3288658958ns value-টি ব্যবহারকারীর pasted value, original report file parse করা হয়নি।

| Metric | Burst | Paced |
|---|---:|---:|
| Submitted / failures | 8 / 0 | 8 / 0 |
| Images / results | 8 / 8 | 8 / 8 |
| Matched | 6 | 8 |
| Accepted / written | 3 / 3 | 8 / 8 |
| Native poolExhausted | 3 | 0 |
| Unmatched images / pending results | 2 / 2 | 0 / 0 |
| Peak native slots | 3 | 1 |
| CRC total ms | 21.337707 | 54.771407 |
| write() total ms | 313.845622 | 612.742139 |
| Row-copy total / max ms | 216.547864 / 99.563750 | 129.534166 / 28.601927 |
| fsync ms | 624.139740 | 3288.658958 |
| Stop/drain ms | 670.660937 | 3424.381457 |
| Exact final bytes | 74909184 | 199757664 |

Both: `arm64-crc32`, hardware self-test passed, nativeStopOk true, no native error, pool3/3 returned, ring0, writerStatus0, partial0। CRC mean ~7.11/~6.85ms per written frame vs v67 software ~44–69ms/frame; different runs/conditions, not controlled end-to-end speedup proof। Burst successful append mean ~111.7ms; copy/write dominate remaining measured time. Native timing intervals overlap; totals are not additive pipeline wall time。

Burst 74.91MB matches 3 frames exactly (64 + 3×(96+4080×3060×2)+32)。 3 native rejects explain only 3 of 5 unwritten frames; remaining2 never timestamp-matched। Old controller deliberately closed oldest Image once >2 were held. This is a plausible loss path, but exact mismatch timestamps/eviction events were not logged, so sole cause not proven। Do not label this run an overall improvement over v67's 6/8 Burst。

## Patch decision

- Keep <=2 acquired Images with ImageReader maxImages3. Pause acquisition before taking a third Image; resume unread buffers when metadata releases space. Never guess nearest timestamp; do not retain extra RAW copies or increase slots.
- Extract pure bounded timestamp join + tests (delayed/reordered/results-first/mismatch/duplicate/bounds). Add received/pending/evicted timestamp arrays and acquisition deferral count. Driver backpressure/deadlines can still limit capture; physical testing required.
- Pre-touch existing three native buffers once before capture, measured as poolPreTouchNs. Not mlock, no extra buffer, no zero-copy claim. Commits existing allocation pages earlier; may reduce first-copy page faults but device latency benefit not yet proven and OS may reclaim pages.
- Keep hardware CRC, same CCRAW001 format, 8-frame/192MiB capture limits, 12s capture deadline, original fsync policy. No compression/continuous recording/audio/30fps claim.
