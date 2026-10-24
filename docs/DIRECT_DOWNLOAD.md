# Direct-to-Download session writing

## Why

Until v104 every RAW session was written to app staging and then copied byte-for-byte into
`Download/CineCam`. On a real 120 s session that meant:

- **2.6 GB written for a 1.3 GB clip** (staging + export), and the export needed both at once.
- **~3.5 minutes of extra fsync-bound copying** for 52 DNG files plus the clip.
- One failed copy at the end left the user with nothing usable in Download.

Direct mode removes that copy entirely: the CCRAW and every DNG are written **once**, into their
final MediaStore row, and published when the session finishes.

## Flow

1. `RawClipStore.prepare` still creates a small staging folder for `metadata.json` and the report.
   Its free-space figure now comes from the Download volume, because that is where the bytes go.
2. `DirectClipStore` inserts a pending row `CineCam_<ts>_<id>_capture.ccraw`
   (`RELATIVE_PATH=Download/CineCam`, `IS_PENDING=1`) and returns its writable descriptor.
3. `nativeStartRawClipFd` hands that descriptor to `CcrawWriter::openFd`, which writes the header and
   every frame straight into the row. The writer borrows the descriptor: `finish()` fsyncs but never
   closes or renames, and the row stays hidden until the app publishes it.
4. After the writer drains, `CcrawVerifier.verify(descriptor)` re-reads the file with `pread` on the
   same descriptor (independent of the native writer, same CRC rules as the staging path).
5. DNG frames read the pending CCRAW through `ContentResolver.openInputStream` and write their own
   pending rows (`ClipDngSample` now takes a `ClipDngTarget` instead of a folder).
6. `publish()` clears `IS_PENDING` for every row, so the clip and its DNGs appear in the gallery even
   if a later step fails. Only `metadata.json`, `sequence.json` and the reports are copied afterwards
   by `RawClipStore.export` (a few KB, plus the archive when that is enabled).

## Guarantees

- File names are exactly `RawExportNames.name(session, original)` — identical to the export layout,
  so existing library code, sequence references and report mappings keep working.
- The CCRAW container format is byte-for-byte the same as the staging path (a native test compares
  both writers frame by frame).
- A failed session deletes its pending rows (`DirectClipStore.discard`); a half-written clip can never
  appear in Download.
- Nothing is ever overwritten: the row is new, the name is unique per session, and the writer refuses
  a non-empty or already-advanced descriptor.

## Limits (measured or explicit)

- Requires Android 10+ (MediaStore pending rows). Older devices stay on staging + export.
- **Lossless archive still uses staging**, because archive creation and full-file byte comparison need
  real paths. With archive ON the session falls back and the report says `directDisabledReason: archive`.
- The lossless codec probe is skipped in direct mode (it takes a path) and is reported as skipped.
- MediaStore writes go through FUSE. Speed on the device must be measured; if a direct session turns
  out slower than staging, turn the toggle off — the report keeps both numbers comparable.
