# F23 v73 device result — 2026-10-21

User supplied original report.json + sequence.json and screenshots; confirmed all8 DNGs visually correct. Both JSONs parsed locally: 8written/matched/accepted, native drops0, readbackOk=true/8frames/199756800bytes, DNG sequenceOk=true/8files/199996288bytes. All manifest timestamps equal both Image/result timestamp arrays; every PTS exactly timestamp−epoch. Original raw/DNG binaries not independently read in this workspace; no scientific calibration/NLE certification claim.

RAW199757664bytes, no partial, nativeStopOk, pool3/3 returned/ring0. Hardware CRC passed. Paced sensor span3098815521ns; not fixed/sustained FPS. Native wall-until-drain4.416s; readback1.158s, DNG sequence17.299s, export data copy9.615s (not additive pure hardware throughput measurements).

User requests removing per-capture user-visible subfolders and cannot conveniently open JSON on phone. Next patch exports flat into one Download/CineCam directory with capture-unique filename prefixes and matching manifest references; companion report.txt + in-app readable summary. Existing user folders/files remain untouched; private temporary capture isolation remains to protect native exclusive output/rollback. No capture/codec/performance change claimed.
