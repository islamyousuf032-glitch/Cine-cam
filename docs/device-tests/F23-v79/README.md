# F23 v79 — user-stop চার-frame পরীক্ষা — 2026-10-22

চার original sidecar parse ও filename/timestamp/source-frame/PTS mapping cross-check সফল। Screenshot-এ flat Download/CineCam-এ প্রায়99.88MB RAW,57.19MB archive,4DNG ও companion files দেখা যায়; screenshot binary repository-তে কপি নয়।

- Report reason=`user stop`; target8,submitted5,matched/accepted/written4; native drop0,failedRequests0। এটি পূর্ণ8-frame run নয়; submitted পঞ্চম request-এর completed frame রিপোর্টে নেই। Stop-time in-flight cancellation সম্ভব, কিন্তু এই report থেকে নির্দিষ্ট callback outcome প্রমাণ নয়। “চারটি frame হারিয়েছে” বলা ভুল।
- RAW readback4,DNGsequence4,archive `ok=true`/`byteExactSourceComparison=true`;10flat export names ঠিক। Source99,878,880→57,194,312bytes:42.7363% ছোট। Original payload/archive binaries developer পায়নি; pre-export app verification, independent device restore/post-export readback নয়।
- Coalesced mode1525blocks/1526write syscalls: প্রত্যাশিতB+1 মেলে। আগের two-write scheme-এ একই block count হলে3051calls হতো (তাত্ত্বিক তুলনা)।
- Archive aggregate690.01ms:read45.30/pack222.60/CRC11.73/write92.11/fsync315.89/close0.02ms;full verify316.21ms।
- RAW write112.64ms/fsync2539.45ms;capture→drain3727.05ms,capture window1110.56ms। DNG2456.32ms;export257,074,617bytes/8374.81ms।

আগের8-frame run-এর সঙ্গে এই4-frame run সরাসরি speedup তুলনা নয়: volume/scene/storage state আলাদা, RAW fsync এখনও2.54s। Next validation একই v79 APK-তে আগেভাগে stop না দিয়ে automatic8-request completion; same settings/archiveON/>=1.5GiB free; four small sidecars। No new APK/code change প্রয়োজন এই validation-এর জন্য।
