# F23 v69 user reports — 2026-10-20

Screenshot rounded 150/200MB; pasted report exact data below। Original `.ccraw` independent CRC/visual verification হয়নি।

| Metric | Burst | Paced |
|---|---:|---:|
| Images / results / matched | 8 / 8 / 8 | 8 / 8 / 8 |
| Accepted / written | 6 / 6 | 8 / 8 |
| Unmatched / pending results / evicted results | 0 / 0 / 0 | 0 / 0 / 0 |
| Native drops (all poolExhausted) | 2 | 0 |
| Pool peak / available after stop | 3 / 3 | 2 / 3 |
| Pre-touch ms | 41.503698 | 40.088125 |
| Row copy total / max ms | 52.774896 / 15.523489 | 80.592395 / 12.357031 |
| Payload CRC ms | 38.708645 | 59.460678 |
| write() total ms | 3778.236195 | 3499.710466 |
| Successful append total / max ms | 3816.860832 / 1748.677135 | 3559.113436 / 1747.756041 |
| fsync ms | 2411.248176 | 1125.089010 |
| finalize ms | 2411.606926 | 2619.587343 |
| stop/drain ms | 5901.539633 | 5458.319998 |
| wall until drain ms | 6816.529945 | 7628.163279 |
| Final bytes | 149818272 | 199757664 |

Both `arm64-crc32` / self-test passed, nativeStopOk true, writerStatus0, pool returned3/3, ring0, partial0। Received Image/result timestamp arrays exactly equal, acquisition deferrals0। This run does not exercise acquisition pausing; no claim that every future mismatch is fixed।

Copy time lower than v68, but conditions/exposure differ: v69 exposure33333332ns, ISO~329 vs v68 exposure16666666ns। No controlled causal speedup claim। Mean CRC ~6.45/7.43ms/frame, copy ~8.80/10.07ms/frame; remaining time dominated by measured write/finalization waiting, not proof of pure physical disk speed or its sole cause。

Paced finalize minus fsync ~1.494s was not separately measured (footer/close/rename/scheduling); do not label all of it fsync。 Next patch adds separate close/rename/write maxima and optional file reservation before capture, with exact EOF truncation before fsync. Same pool/format/data fidelity; no continuous/FPS/audio/compression guarantee。
