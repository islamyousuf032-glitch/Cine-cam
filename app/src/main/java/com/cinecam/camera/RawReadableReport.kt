package com.cinecam.camera

import org.json.JSONObject

internal object RawReadableReport {
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
        appendLine("RAW file bytes: ${report.optLong("fileBytes")}")
        appendLine("গন্তব্য: ${report.optString("exportFolder")}")
        appendLine("একই Capture prefix-এর ফাইলগুলো একই পরীক্ষার অংশ। পুরোনো capture মুছে ফেলা হয়নি।")
        appendLine("এটি সীমিত RAW/DNG পরীক্ষা; fixed FPS, MP4, audio বা continuous video নয়।")
        val files = report.optJSONObject("exportFiles")
        if (files != null) {
            appendLine("ফাইলের তালিকা:")
            files.keys().asSequence().sorted().forEach { appendLine("${files.getString(it)}") }
        }
        for (key in listOf("nativeError", "readbackError", "dngSequenceError", "firstFrameDngError", "exportError"))
            if (report.optString(key).isNotEmpty()) appendLine("$key: ${report.optString(key)}")
    }
}
