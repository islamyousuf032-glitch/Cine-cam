# F23 v81 — native export device ফল — 2026-10-22

চার original sidecar parse/cross-check সফল: automatic8 requests,8matched/written,0failed/drop,readback8,DNG8,archive byteExact সফল। Manifest/export names/timestamps/sourceFrameNumber/PTS মেলে;14flatfiles।

- Source199,757,664→114,400,776bytes,42.7302% ছোট;3049blocks/3050archive writes। Archive4241.03ms,verify607.33ms।
- Native export12data files,মোট514,158,710bytes;সব detail ok=true,kernelBytes=bytes,bufferedBytes=0। সব file actual export mapping-এ আছে। Device kernel path validated; fallback আগে automated test-এ, এই run-এ ব্যবহৃত হয়নি।
- Export aggregate38.44373s;per-file copy sum17.01217s,fsync sum20.41330s;বাকি প্রায়1.01826s insert/query/open/close/loop overhead। Reports/publish aggregate-এ নেই।
- Archive public copy7.37441s+sync2.97732s;RAW public copy8.05009s+sync6.47944s। DNG copy relatively short, destination flush অনেক ক্ষেত্রে1–2s।
- Original RAW write4.76562s/fsync0.65295s;DNG generation7.86082s। No fixedFPS/continuous recording claim।

v79 full8frame export23.69003s ছিল; v81 এই run38.44373s—**মোট export speedup প্রমাণ হয়নি**। v81 নতুন explicit per-file fsync যোগ করেছে;এবার সেই flush প্রায়20.41s মাপা গেছে। Different scenes/storage/background states হওয়ায় regression-এর সবটাই এক পরিবর্তনের causal effect বলা যাবে না;kernel path কাজ করাই faster export-এর প্রমাণ নয়।

Payload/RAW/archive/DNG binaries পাওয়া যায়নি; pre-export archive byte comparison app report, public destination size/fsync success; independent exported-byte readback নয়। Source retained until publication,defaultOFF archive,flat names preserved। এই build-এর correctness/provider/backend milestone complete; এখনই একই test আবার চাওয়ার প্রয়োজন নেই। Next candidate duplicate staging→export I/O review, explicit durability বা originals গোপনে বাদ নয়। এই evidence update code/APK বদলায়নি।
