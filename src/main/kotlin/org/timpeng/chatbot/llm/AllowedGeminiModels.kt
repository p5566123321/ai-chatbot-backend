package org.timpeng.chatbot.llm

/**
 * Whitelist for the per-user chat model override (`GeminiSettings.model`, docs/decision/010).
 * Enforced in `UserService.updateGeminiSettings` — kept as a plain code constant rather than
 * config, same as the rest of `GeminiSettings`' "loose bounds, Gemini API is the real source of
 * truth" philosophy; update this set directly when the account's available models change.
 *
 * Deliberately chat-only — see docs/decision/010 for why embedding model choice isn't covered.
 */
object AllowedGeminiModels {
    val IDS: Set<String> = setOf(
        "gemini-3-flash-preview", // matches the app.llm.gemini.model default
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.0-flash",
    )
}
