package com.fxsoundlite.app

import androidx.annotation.StringRes

/**
 * AI services the user can bring their own API key for (Settings > AI). Each has its own saved key,
 * so switching back and forth does not lose anything. [keyUrl] is the page where the user creates an API key. [defaultModel] is used when the user leaves the
 * model field empty; model names get retired now and then, which is why the field can be changed.
 */
enum class AiProvider(val id: String, val label: String, val defaultModel: String, @StringRes val keyHintRes: Int, val keyUrl: String) {
    GEMINI("gemini", "Gemini", "gemini-flash-latest", R.string.ai_key_hint, "https://aistudio.google.com/apikey"),
    OPENAI("openai", "OpenAI", "gpt-5.4-mini", R.string.ai_key_hint_openai, "https://platform.openai.com/api-keys"),
    ANTHROPIC("anthropic", "Anthropic", "claude-haiku-5-5", R.string.ai_key_hint_anthropic, "https://console.anthropic.com/settings/keys");

    companion object {
        fun fromId(id: String?): AiProvider = values().firstOrNull { it.id == id } ?: GEMINI
    }
}
