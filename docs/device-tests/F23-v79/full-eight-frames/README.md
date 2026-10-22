# F23 v79 — পূর্ণ আট-frame automatic completion — 2026-10-22

চার original sidecar parse/cross-check সফল। reason=paced request limit reached;submitted/matched/accepted/written8,failed0,nativeDrop0;readback8,DNG8,archive byteExactSourceComparisontrue। Manifest/export filename,timestamp,result/sourceFrameNumber ও epoch-relative PTS সব মেলে;14flat names। Screenshot-এ200MB RAW/116MB archive/8DNG দেখা যায়; screenshot binary কপি নয়।

- Source199,757,664→116,067,848bytes,41.8957% ছোট; source retained, net disk saving নয়।
-3049blocks/3050writeCalls,coalesced-record-v1: প্রত্যাশিতB+1 নিশ্চিত। v78-এ একই source size/block count-এ6099calls ছিল। Call-count reduction validated; latency causal speedup proof নয়।
- Archive total3988.74ms;read85.89/pack397.97/CRC20.77/write230.53/fsync3249.69/close0.07ms;verify632.26ms আলাদা। Flush প্রায়81.47% aggregate।
- Capture→drain5905.67ms,window3027.54ms;RAWwrite2898.25ms/fsync844.64ms/maxwrite1553.40ms। DNG5843.29ms;export515,825,782bytes/23690.03ms।

পূর্ণ8-frame path এবং coalesced calls-এর device validation সফল। Previous v78 total10.732s/write3.334s/export39.496s-এর তুলনায় এই run কম, কিন্তু scene/archive ratio/storage state/thermal/background conditions controlled নয়; শুধু optimization-এর causal gain বা sustainedFPS নয়। Original payloads upload হয়নি; এটি pre-export app byte-comparison result, developer independent binary restore/post-export readback certification নয়।

এই milestone-এ নতুন APK বা একই test আবার চাওয়ার প্রয়োজন নেই। অবশিষ্ট প্রধান measured costs archivefsync এবং প্রায়516MB public export-copy; ভবিষ্যৎ কাজ source safety/verification/flat layout বজায় রেখে সেই path review।
