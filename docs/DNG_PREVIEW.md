# অ্যাপের ভিতরে DNG preview — 2026-10-22

## কেন

Manage clips ফাইল দেখাতে পারলেও RAW ছবি দেখাতে পারত না; phone-এর Gallery/ফাইল manager-এ DNG খুললে অনেক ডিভাইসে preview আসে না। এখন অ্যাপ নিজেই saved DNG থেকে ছোট preview আঁকে—কোনো নতুন capture বা upload দরকার নেই।

## কী কাজ করে

- Capture card-এ বাঁ দিকে cover thumbnail; details-এ বড় cover; প্রতিটি DNG সারিতে **অ্যাপে দেখুন** এবং full-screen viewer-এ আগের/পরের frame, `DNG ছবি n/8`, loading/error state।
- Preview কেবল user-selected folder-এর readable document URI থেকে; open descriptor শুধু পড়া হয়, caller `ParcelFileDescriptor` বন্ধ করে, native descriptor ব্যবহার শেষে ফেরত দেয়।
- Original DNG/RAW/EXIF/archive/export-এ কোনো write নেই; viewer-এর preview ব্যবহার করলেও ফাইল অপরিবর্তিত। Non-DNG file আগের মতো বাইরে খোলা/শেয়ার করা যায়।

## Native render path (C++)

`dng_preview.cpp` একটি bounded, defensive TIFF/DNG reader:

- Header/IFD/SubIFD chain, typed values, বড়/ছোট endian; IFD≤512 entries, depth≤16, ফাইল≤96MiB।
- শুধু **uncompressed (Compression=1), 16-bit, 1 sample, CFA (Photometric=32803)** RAW image গ্রহণ; LinearizationTable, JPEG-CFA, 8/10/12-bit packed, SPP>1, planar!=1 প্রত্যাখ্যান করে পরিষ্কার error।
- Strip offsets/counts ফাইলের ভেতরে বাধ্যতামূলক; total RAW bytes, crop, pixels≤48MP, preview≤6MP। Missing/overlapping/truncated strip → ব্যর্থ, crash/OOM নয়।
- Regular source `mmap` read-only; provider pipe হলে bounded read fallback। Source বদলায় না।
- 2×2 CFA cell block average (প্রতি আউটপুট পিক্সেলে step×step cells), per-channel black/white, AsShotNeutral white balance, optional ColorMatrix1→XYZ→sRGB, sRGB gamma LUT। Bayer-এর প্রতিটি গ্রিন site সঠিকভাবে গোনা হয়; দ্রুত, কোনো full-frame RGBA buffer নয়।
- Output: ARGB int array + stored orientation; Kotlin EXIF orientation (3/6/8) অনুযায়ী rotate করে Bitmap বানায়; cache সর্বোচ্চ24MiB, viewer-এ1020-এর কাছাকাছি preview, thumbnail ছোট।

এটি RAW editor/color-accurate render নয়: highlight recovery, lens correction, noise reduction, full demosaic, ICC/second illuminant transformation নেই। ভুল রঙ হলে source DNG অক্ষত; বাইরের professional converter ব্যবহার করা যাবে।

## সীমা

- আমাদের নিজের DNG (uncompressed CFA 16-bit) principal target; অন্য camera/app-এর compressed/vendor DNG preview নাও হতে পারে—সেক্ষেত্রে অ্যাপ পরিষ্কারভাবে "preview তৈরি করা যায়নি" দেখাবে, ভুল ছবি দেখাবে না।
- Folder-এ থাকা DNG-এর extension/পাশের report mapping ছাড়া manager কিছু খোলে না; report mapping এখনও untrusted index, pixel verification নয়।
- `RAW/capture.ccraw` ও `archive.bp16` file viewer-এ ছবি হিসেবে খোলা হয় না; সেগুলো raw data/backup।
- Continuous video playback, timeline scrub, audio, MP4 export এখনো নেই। DNG viewer চলমান video নয়।

## Automated / pending

Host ASan/UBSan tests synthetic LE/BE uncompressed DNG, RGGB/GRBG channel mapping, exact expected sRGB values, orientation, mmap FD path, JPEG/RGB/bad-pattern/truncated/oversize reject। Existing native archive/export suites অপরিবর্তিত। Android CI unit/lint/build যাচাই করবে; actual device DNG structure/timing এবং thumbnail quality এখনো যাচাই বাকি—একই পুরোনো capture দিয়েই দেখা যাবে।
