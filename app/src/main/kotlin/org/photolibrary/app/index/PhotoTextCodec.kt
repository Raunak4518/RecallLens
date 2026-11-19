package org.photolibrary.app.index

import org.json.JSONArray
import org.json.JSONObject

internal object PhotoTextCodec {
    fun encode(text: PhotoText): String = JSONObject().apply {
        put("version", 1)
        put("complete", text.complete)
        put("lines", encodeLines(text))
    }.toString()

    private fun encodeLines(text: PhotoText): JSONArray = JSONArray().apply {
        for (line in text.lines) {
            put(JSONObject().apply {
                put("text", line.text)
                put("language", line.language)
                line.bounds?.let { put("bounds", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
            })
        }
    }

    fun decode(payload: String): PhotoText {
        if (payload.length > 262_144) throw PhotoIndexUnavailable()
        val envelope = if (payload.startsWith("[")) null else JSONObject(payload)
        if (envelope != null && envelope.getInt("version") != 1) throw PhotoIndexUnavailable()
        val lines = envelope?.getJSONArray("lines") ?: JSONArray(payload)
        if (lines.length() > 256) throw PhotoIndexUnavailable()
        return PhotoText(List(lines.length()) { index ->
            val line = lines.getJSONObject(index)
            val bounds = line.optJSONArray("bounds")?.let { TextBounds(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }
            TextLine(line.getString("text"), line.getString("language"), bounds)
        }, complete = envelope?.getBoolean("complete") ?: true)
    }
}
