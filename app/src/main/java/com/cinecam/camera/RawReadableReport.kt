package com.cinecam.camera

import org.json.JSONObject
import java.util.Locale

internal object RawReadableReport {
    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0)
    fun text(report: JSONObject): String = buildString {
        appendLine("CineCam — সহজ পরীক্ষার রিপোর্ট")
        appendLine("Capture: ${report.optString("exportSessionId", "unknown")}")
        appendLine("Mode: ${report.optString("captureMode")}")
        appendLine("মেলা frame: ${report.optInt("matchedFrames")} / ${report.optInt("requestedFrames")}")
        appendLine("লেখা frame: ${report.optJSONObject("native")?.optLong("framesWritten") ?: 0}")
        appendLine("Native drop: ${report.optJSONObject("native")?.optLong("framesDropped") ?: 0}")
        appendLine("Saved RAW যাচাই: ${if (!report.optBoolean("readbackRequested")) "করা হয়নি" else if (report.optBoolean("readbackOk")) "সফল" else "ব্যর্থ/অসম্পূর্ণ"}")
        appendLine("DNG mode: ${report.optString("dngExportMode")}")
        if (report.has("dngSequenceOk")) appendLine("DNG sequence: ${if (report.optBoolean("dngSequenceOk")) "সফল" else "ব্যর্থ"}; ${report.optInt("dngSequenceFrames")} frame")
        if (report.has("firstFrameDngOk")) appendLine("প্রথম frame DNG: ${if (report.optBoolean("firstFrameDngOk")) "সফল" else "ব্যর্থ"}")
        report.optJSONObject("losslessProbe")?.let { probe ->
            appendLine("Lossless codec byte-exact পরীক্ষা: ${if (probe.optBoolean("roundtripOk")) "সফল" else "ব্যর্থ"}")
            appendLine("প্রথম saved frame: ${probe.optLong("inputBytes")} bytes → পরীক্ষামূলক packed ${probe.optLong("packedBytes")} bytes")
            appendLine("শুধু codec probe; RAW/DNG ফাইল compressed নয়, recording performance proof নয়।")
        }
        if (report.has("losslessProbeError")) appendLine("Codec probe error: ${report.optString("losslessProbeError")}")
        if (!report.optBoolean("losslessArchiveRequested")) appendLine("Lossless archive: OFF")
        report.optJSONObject("losslessArchive")?.let { archive ->
            appendLine("Lossless archive: ${if (archive.optBoolean("ok")) "সফল ও byte-exact যাচাই" else "ব্যর্থ"}")
            appendLine("Archive bytes: ${archive.optLong("archiveBytes")}; original bytes: ${archive.optLong("sourceBytes")}")
            appendLine("Archive encode/write: ${ms(archive.optLong("encodeWriteNs"))} ms; পুরো-file verify: ${ms(archive.optLong("verifyNs"))} ms")
            if (archive.optString("blockWriteMode") == "coalesced-record-v1")
                appendLine("Archive write: record header+payload একসঙ্গে; short write/EINTR হলে retry, fsync বজায়।")
            if (archive.has("packNs")) {
                appendLine("Archive stage (ms): read ${ms(archive.optLong("sourceReadNs"))}; encode ${ms(archive.optLong("packNs"))}; CRC ${ms(archive.optLong("crcNs"))}")
                appendLine("Archive storage (ms): write ${ms(archive.optLong("writeNs"))}; fsync ${ms(archive.optLong("fsyncNs"))}; close ${ms(archive.optLong("closeNs"))}; সর্বোচ্চ এক write ${ms(archive.optLong("maxWriteNs"))}")
                appendLine("Archive blocks: ${archive.optLong("blocks")}; read/write syscall: ${archive.optLong("readCalls")}/${archive.optLong("writeCalls")}")
                appendLine("এগুলো encode-phase wall time; পুরো-file verify আলাদা, CPU-only benchmark নয়।")
            }
            if (archive.optString("error").isNotEmpty()) appendLine("Archive error: ${archive.optString("error")}")
            appendLine("মূল RAW/DNG রাখা হয়েছে; archive অতিরিক্ত file, playable video নয়।")
        }
        appendLine("সময় (ms; একে অন্যের সঙ্গে সব যোগ করা যাবে না):")
        if (report.has("wallUntilDrainNs")) appendLine("Capture শুরু→writer drain: ${ms(report.optLong("wallUntilDrainNs"))}; এর ভিতরে capture window ${ms(report.optLong("captureWindowNs"))}")
        report.optJSONObject("native")?.optJSONObject("writerTimings")?.let { timing ->
            appendLine("RAW storage: write ${ms(timing.optLong("writeSyscallNs"))}; fsync ${ms(timing.optLong("fsyncNs"))}; সর্বোচ্চ এক write ${ms(timing.optLong("maxWriteNs"))}")
        }
        if (report.has("pacedCapacityWaitNs")) appendLine("Paced buffer অপেক্ষা: ${ms(report.optLong("pacedCapacityWaitNs"))}; এটি capture window-এর অংশ, drop নয়।")
        if (report.has("dngSequenceNs")) appendLine("DNG sequence তৈরি: ${ms(report.optLong("dngSequenceNs"))}")
        if (report.has("exportDataCopyNs")) appendLine("Export data copy: ${ms(report.optLong("exportDataCopyNs"))}; ${report.optLong("exportDataBytes")} bytes (report লেখা/publish সময় অন্তর্ভুক্ত নয়)")
        appendLine("RAW file bytes: ${report.optLong("fileBytes")}")
        appendLine("গন্তব্য: ${report.optString("exportFolder")}")
        appendLine("একই Capture prefix-এর ফাইলগুলো একই পরীক্ষার অংশ। পুরোনো capture মুছে ফেলা হয়নি।")
        appendLine("এটি সীমিত RAW/DNG পরীক্ষা; fixed FPS, MP4, audio বা continuous video নয়।")
        val files = report.optJSONObject("exportFiles")
        if (files != null) {
            appendLine("ফাইলের তালিকা:")
            files.keys().asSequence().sorted().forEach { appendLine("${files.getString(it)}") }
        }
        for (key in listOf("nativeError", "readbackError", "dngSequenceError", "firstFrameDngError", "losslessArchiveError", "exportError"))
            if (report.optString(key).isNotEmpty()) appendLine("$key: ${report.optString(key)}")
    }
}
