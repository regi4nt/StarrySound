package com.fxsoundlite.app

import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder

/**
 * Romanization (offline, Android's ICU) and translation (Indonesian or English) of lyric lines.
 * Translation uses Google's web translate endpoint (no on-device ML Kit: it cost tens of MB).
 */
object LyricsText {

    /** True when some line has letters that are not Latin (Japanese, Korean, Cyrillic, Arabic, ...). */
    fun needsRoman(lines: List<String>): Boolean = lines.any { line ->
        line.codePoints().anyMatch {
            Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        }
    }

    /**
     * Latin-letter reading of each line. Kana, Hangul, Cyrillic, Arabic, Devanagari... work well;
     * Han characters come out as Mandarin pinyin, so Japanese kanji are not read the Japanese way.
     */
    fun romanize(lines: List<String>): List<String> {
        val tr = android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
        return lines.map { tr.transliterate(it) }
    }

    // ---------------------------------------------------------------- translation

    private fun endpoint(lang: String) =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$lang&dt=t"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val CHUNK_LINES = 30
    private const val CHUNK_CHARS = 1800

    /** Translates to [lang] ("id" or "en"), one output line per input line. Blocking; throws [IOException] on failure. */
    fun translate(lines: List<String>, lang: String): List<String> = viaEndpoint(lines, lang)

    // ---- Google web endpoint (unofficial, no key)

    private fun viaEndpoint(lines: List<String>, lang: String): List<String> {
        val out = MutableList(lines.size) { "" }
        var i = 0
        while (i < lines.size) {
            // Collect the next chunk of non-blank lines (blank ones stay blank).
            val idx = ArrayList<Int>()
            var chars = 0
            while (i < lines.size && idx.size < CHUNK_LINES && chars < CHUNK_CHARS) {
                if (lines[i].isNotBlank()) { idx += i; chars += lines[i].length + 1 }
                i++
            }
            if (idx.isEmpty()) continue
            val joined = idx.joinToString("\n") { lines[it].replace('\n', ' ') }
            val parts = translate(joined, lang).split("\n")
            if (parts.size == idx.size) {
                idx.forEachIndexed { k, li -> out[li] = parts[k].trim() }
            } else {
                // The service merged / split lines: translate this chunk line by line instead.
                idx.forEach { li -> out[li] = translate(lines[li], lang).replace('\n', ' ').trim() }
            }
        }
        return out
    }

    private fun translate(text: String, lang: String): String {
        val r = Http.request(
            "POST", endpoint(lang), mapOf("User-Agent" to UA),
            body = "q=" + URLEncoder.encode(text, "UTF-8"),
            contentType = "application/x-www-form-urlencoded"
        )
        if (r.code != 200) throw IOException("translate HTTP ${r.code}")
        return try {
            val segs = JSONArray(r.body).getJSONArray(0)
            val sb = StringBuilder()
            for (k in 0 until segs.length()) sb.append(segs.getJSONArray(k).optString(0, ""))
            sb.toString()
        } catch (e: Exception) {
            throw IOException("translate: unexpected reply", e)
        }
    }
}
