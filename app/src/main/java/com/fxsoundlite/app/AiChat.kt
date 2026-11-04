package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Multi-turn chat with the AI service the user already set up in Settings > AI (Gemini, OpenAI or Anthropic,
 * their own API key, optional load spreading over all configured services, see [AiLoad]).
 * [AiRecommender] only does one-shot prompts; this sends the whole conversation so the AI remembers it.
 *
 * Blocking: call [reply] from a background thread. Throws [AiRecommender.AiException] on any failure.
 */
object AiChat {
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    /** How many of the latest messages are sent along with each question. */
    const val MAX_TURNS = 20
    const val MAX_INPUT = 2000

    /** [role] is "user" or "assistant". */
    class Turn(val role: String, val text: String)

    private val SYSTEM = """
        You are the chat assistant inside StarrySound, a music player app. Be friendly, helpful and brief.
        Answer in the language the user writes in. You can talk about music, suggest songs, artists and playlists,
        and help with questions about the app. You cannot play songs or change settings yourself, so do not claim to.
    """.trimIndent()

    fun hasKey(c: Context): Boolean = AiRecommender.hasKey(c)

    fun reply(c: Context, turns: List<Turn>): String {
        val app = c.applicationContext
        // Every service wants the conversation to start with the user's message.
        val recent = turns.takeLast(MAX_TURNS).dropWhile { it.role != "user" }
        if (recent.isEmpty()) throw AiRecommender.AiException(R.string.ai_err_generic)
        val chosen = MusicPrefs.aiProvider(app)
        if (!MusicPrefs.aiBalance(app)) {
            if (MusicPrefs.aiKey(app, chosen).isBlank()) throw AiRecommender.AiException(R.string.ai_err_no_key)
            return call(app, chosen, recent)
        }
        val order = AiLoad.order(app, chosen)
        if (order.isEmpty()) throw AiRecommender.AiException(R.string.ai_err_no_key)
        var last: AiRecommender.AiException? = null
        for (p in order) {
            AiLoad.started(p)
            try {
                val text = call(app, p, recent)
                AiLoad.succeeded(p)
                return text
            } catch (e: AiRecommender.AiException) {
                AiLoad.failed(p, e.resId)
                last = e
                if (!AiLoad.worthRetry(e.resId)) throw e
            }
        }
        throw last ?: AiRecommender.AiException(R.string.ai_err_generic)
    }

    private fun call(c: Context, provider: AiProvider, turns: List<Turn>): String {
        val key = MusicPrefs.aiKey(c, provider)
        if (key.isBlank()) throw AiRecommender.AiException(R.string.ai_err_no_key)
        val model = MusicPrefs.aiModel(c, provider)
        return when (provider) {
            AiProvider.GEMINI -> gemini(key, model, turns)
            AiProvider.OPENAI -> openAi(key, model, turns)
            AiProvider.ANTHROPIC -> anthropic(key, model, turns)
        }
    }

    private fun bad(): AiRecommender.AiException = AiRecommender.AiException(R.string.ai_err_bad_answer)

    private fun gemini(key: String, model: String, turns: List<Turn>): String {
        val contents = JSONArray()
        turns.forEach {
            contents.put(
                JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 4096))
            .toString()
        val modelId = model.trim().removePrefix("models/")
        val r = AiRecommender.post(GEMINI_URL.format(modelId), mapOf("x-goog-api-key" to key), body)
        val root = try { JSONObject(r.body) } catch (e: Exception) { throw bad() }
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiRecommender.AiException(
                R.string.ai_err_http, "Gemini: " + root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().ifEmpty { "no answer" }
            )
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val text = if (parts == null) "" else (0 until parts.length()).mapNotNull { i ->
            parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought", false) }?.optString("text")
        }.joinToString("")
        if (text.isBlank()) throw AiRecommender.AiException(R.string.ai_err_http, "Gemini: " + cand.optString("finishReason", "empty answer"))
        return text.trim()
    }

    private fun openAi(key: String, model: String, turns: List<Turn>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
        turns.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("max_completion_tokens", 4096)
            .toString()
        val r = AiRecommender.post(OPENAI_URL, mapOf("Authorization" to "Bearer $key"), body)
        val root = try { JSONObject(r.body) } catch (e: Exception) { throw bad() }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiRecommender.AiException(R.string.ai_err_http, "OpenAI: no answer")
        val msg = choice.optJSONObject("message")
        val text = if (msg == null || msg.isNull("content")) "" else msg.optString("content")
        if (text.isBlank()) {
            val why = msg?.optString("refusal").orEmpty().ifEmpty { choice.optString("finish_reason", "empty answer") }
            throw AiRecommender.AiException(R.string.ai_err_http, "OpenAI: $why")
        }
        return text.trim()
    }

    private fun anthropic(key: String, model: String, turns: List<Turn>): String {
        val msgs = JSONArray()
        turns.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", SYSTEM)
            .put("messages", msgs)
            .toString()
        val r = AiRecommender.post(ANTHROPIC_URL, mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION), body)
        val root = try { JSONObject(r.body) } catch (e: Exception) { throw bad() }
        val parts = root.optJSONArray("content")
        val text = if (parts == null) "" else (0 until parts.length()).mapNotNull { i ->
            parts.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
        }.joinToString("")
        if (text.isBlank()) throw AiRecommender.AiException(R.string.ai_err_http, "Anthropic: " + root.optString("stop_reason", "empty answer"))
        return text.trim()
    }
}
