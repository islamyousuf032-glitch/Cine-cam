# F23 v78 — archive latency device ফল

চার original sidecar parse ও cross-check সফল:8matched/written,0drop,readback/DNGsequence8; manifest-এর নাম/timestamp/sourceFrameNumber/epoch-relative PTS মেলে। Metadata4080×3060,archive requested; flat export14টি নাম। Screenshot-এ flat CineCam directory-তে RAW প্রায়200MB/archive120MB/8DNG/sidecars দেখা যায়; screenshot binary repository-তে কপি করা হয়নি।

- Archive source199,757,664→120,139,272bytes, **39.8575% ছোট**; `ok=true`, `byteExactSourceComparison=true`। Original RAW/DNG/archive binaries এখানে পাওয়া যায়নি; app-এর pre-export verification report, developer independent device restore/post-export readback নয়।
- Archive aggregate10.73248s; source read0.10620s, pack0.48501s, CRC0.02612s, write3.33422s, fsync6.77440s, close0.00003s। Write+fsync মিলে aggregate-এর প্রায়94.19%। CPU-only benchmark/root-cause/thermal diagnosis নয়।
- 3049blocks,3049read ও6099write syscalls; max archive write19.25ms। Full-file verification0.81591s আলাদা।
- Capture→drain9.90604s, capture window5.44098s, capacitywait2.09935s (window-এর ভিতরে), RAW write7.81612s/fsync0.62717s/maxwrite2.23036s। No drop মানে fixed FPS নয়।
- DNG9.22925s; export519,897,207bytes/39.49556s। Post-capture archive capture-এর পরে চলে; archive চালু থাকার কারণে সেই capture slow হয়েছে—এমন causal দাবি নয়।

পরের ছোট optimization: record-এর8byte prefix একই bounded buffer-এর সামনে রেখে BP16 payload সরাসরি পিছনে encode; একসঙ্গে writeAll। Extra frame buffer/copy নয়; source/format/CRC/fsync/full comparison অপরিবর্তিত। স্বাভাবিক full-write হলে3049blocks-এর6099→3050calls (~49.99% কম), short-write/EINTR হলে retry বাড়তে পারে। Huge fsync delay এটি নিশ্চয়ই কমাবে না; latency লাভ ফোনে যাচাই বাকি।
