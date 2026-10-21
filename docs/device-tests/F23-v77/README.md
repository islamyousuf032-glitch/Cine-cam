# F23 v77 — প্রথম actual archive device ফল

ব্যবহারকারীর চারটি original sidecar সংরক্ষিত। দুই screenshot-এ archive option ON, Paced/Internal/verification/sequence ON, reserve OFF; flat Download/CineCam-এ প্রায়200MB RAW +110MB archive +8DNG thumbnail দেখা যায়। Screenshot binaries repository-তে কপি করা হয়নি।

স্বয়ংক্রিয় cross-check: আটটি manifest entry-এর exported filename/timestamp/PTS/sourceFrameNumber report-এর সঙ্গে মেলে। Metadata4080×3060 ও archive requested; exportFiles14টি।

- 8matched/accepted/written,0drop; saved RAW readback8 ও DNGsequence8 সফল।
- Archive CCBP0001 `ok=true`, `byteExactSourceComparison=true`;199,757,664→109,739,528bytes: **45.0637% ছোট**, প্রায়1.8203:1। এটি whole saved container-এর ratio, first-frame probe নয়।
- Encode/write aggregate8.451179841s; full-file verify0.625611562s। v77-এ read/pack/CRC/write/fsync আলাদা মাপা হয়নি;8.45s-কে codec-only সময় বলা যাবে না।
- Capture window4.231529946s, sensor span3.931830365s; capacity wait2.219273905s (capture-এর ভিতরে), native poolpeak3/3। No drop মানে fixed FPS নয়; pacing অপেক্ষা করেছে।
- RAW write syscalls5.514365777s, max write1.779112082s, fsync1.956319635s; capture→drain8.191378382s।
- DNG generation7.347079007s; export data509,497,469bytes/38.470096079s। Extra archive/data-copy cost আছে, কিন্তু এই single run দিয়ে latency বৃদ্ধির root cause, FUSE/thermal/background pressure বা archive causality প্রমাণ হয়নি।
- Free-at-prepare4,544,684,032bytes; reserve OFF। Original RAW/DNG retained, net storage saving নয়।

এই ফল app-এর pre-export byte-compare report; original RAW/archive/DNG binaries upload হয়নি। Developer independent Python restore এই device binary-তে চালায়নি; post-MediaStore-export readback-ও এখনও নেই। DNG thumbnails দেখা যায়, তবে screenshot দিয়ে পূর্ণ-resolution pixel/calibration certify নয়।

পরের সীমিত পদক্ষেপ: native archive encode-phase পৃথক stage wall-time/syscall counters এবং readable storage/pipeline সময়। একই format/algorithm, fsync, full comparison ও original retention বজায় থাকবে; compressed recording চালু নয়।
