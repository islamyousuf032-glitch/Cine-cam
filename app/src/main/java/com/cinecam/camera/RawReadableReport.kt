package com.cinecam.camera

import org.json.JSONObject
import java.util.Locale

internal object RawReadableReport {
    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0)
    fun text(report: JSONObject): String = buildString {
        appendLine("CineCam — সহজ পরীক্ষার রিপোর্ট")
        appendLine("Capture: ${report.optString("exportSessionId", "unknown")}")
        appendLine("Mode: ${report.optString("captureMode")}")
        report.optJSONObject("recordingPlan")?.let { plan ->
            appendLine("Session: ${plan.optInt("seconds")} s • ${plan.optInt("fps")} fps target • " +
                "সর্বোচ্চ ${plan.optInt("maxFrames")} frame (${plan.optInt("slotFrames")} slot)" +
                if (plan.optBoolean("storageLimited")) " • জায়গা অনুযায়ী সীমিত" else "")
        }
        if (report.has("achievedFps")) appendLine("আসল rate: ${report.optDouble("achievedFps")} fps (sensor timestamp থেকে; fixed FPS নয়)")
        if (report.has("skippedSlots")) appendLine("ব্যবহার হয়নি এমন slot: ${report.optInt("skippedSlots")} (storage/sensor ধীর হলে)")
        appendLine("মেলা frame: ${report.optInt("matchedFrames")} / ${report.optInt("requestedFrames")}")
        appendLine("লেখা frame: ${report.optJSONObject("native")?.optLong("framesWritten") ?: 0}")
        appendLine("Native drop: ${report.optJSONObject("native")?.optLong("framesDropped") ?: 0}")
        appendLine("Saved RAW যাচাই: ${if (!report.optBoolean("readbackRequested")) "করা হয়নি" else if (report.optBoolean("readbackOk")) "সফল" else "ব্যর্থ/অসম্পূর্ণ"}")
        val directNote = when (report.optString("directDisabledReason")) {
            "archive" -> " (archive চালু তাই)"; "api" -> " (Android 10-এর নিচে)"
            "off" -> " (আপনি বন্ধ রেখেছেন)"; else -> ""
        }
        appendLine("সংরক্ষণ: " + when (report.optString("storageMode")) {
            "direct-download" -> "সরাসরি Download/CineCam-এ লেখা — RAW/DNG-র দ্বিতীয় copy নেই"
            "staging-export" -> "app staging → Download/CineCam copy$directNote"
            else -> report.optString("storageMode", "অজানা")
        })
        appendLine("DNG mode: ${report.optString("dngExportMode")}")
        if (report.optInt("dngSequenceSourceParts", 0) == 1 && report.optInt("ccrawParts", 1) > 1)
            appendLine("DNG sequence শুধু প্রথম CCRAW অংশ থেকে তৈরি — বাকি অংশের frame-এ DNG নেই (report-এ সংখ্যা আছে)।")
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
        if (report.optInt("ccrawParts", 1) > 1)
            appendLine("CCRAW অংশ: ${report.optInt("ccrawParts")}টি (প্রতিটি সম্পূর্ণ, আলাদা যাচাই করা; " +
                "প্রথম অংশের নাম capture.ccraw) • প্রতি অংশের frame: ${report.optJSONArray("ccrawPartFrames")}")
        if (report.has("directInPlace")) appendLine("সরাসরি লেখা ফাইল: ${report.optJSONArray("directInPlace")?.length() ?: 0}টি; আলাদা copy হয়েছে ${report.optJSONArray("exportCopiedFiles")?.length() ?: 0}টি (report/metadata)")
        if (report.has("directPublishFailures") && (report.optJSONArray("directPublishFailures")?.length() ?: 0) > 0)
            appendLine("Publish ব্যর্থ: ${report.optJSONArray("directPublishFailures")}")
        if (report.has("exportConcurrency")) appendLine("Export concurrency: ${report.optInt("exportConcurrency")} (স্বাধীন ফাইল একসঙ্গে লেখা/fsync; ফাইল বা ক্রম বদলায় না)")
        if (report.has("dngSequenceWorkers")) appendLine("DNG sequence workers: ${report.optInt("dngSequenceWorkers")} (ফাইলের নাম ও ক্রম অপরিবর্তিত)")
        if (report.has("exportCopyBackend")) appendLine("Export পথ: ${report.optString("exportCopyBackend")}; destination fsync চালু, post-export byte readback নয়।")
        report.optJSONArray("exportCopyDetails")?.let { details ->
            for (index in 0 until details.length()) {
                val detail = details.getJSONObject(index)
                appendLine("Export ${detail.optString("file")}: copy ${ms(detail.optLong("copyNs"))} ms; fsync ${ms(detail.optLong("fsyncNs"))} ms; kernel ${detail.optLong("kernelBytes")} / buffered ${detail.optLong("bufferedBytes")} bytes; ${if (detail.optBoolean("ok")) "সফল" else "ব্যর্থ"}")
            }
        }
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
