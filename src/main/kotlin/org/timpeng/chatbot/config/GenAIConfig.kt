package org.timpeng.chatbot.config

import com.google.genai.Client
import com.google.genai.Models
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import org.timpeng.chatbot.rag.embedding.GeminiEmbeddingProvider

/**
 * The system-wide Gemini `Client`/`Models` — used as [org.timpeng.chatbot.llm.GeminiClientFactory]'s
 * fallback for chat when a caller has no BYOK key, and as the sole backend for
 * [GeminiEmbeddingProvider] (RAG has no per-user BYOK path at all — see docs/decision/010).
 *
 * `app.llm.gemini.system-api-key` (env `GOOGLE_API_KEY`) is optional (docs/decision/011).
 * Returning `null` from a `@Bean` method registers no bean at all rather than failing startup —
 * Kotlin's nullable parameter types tell Spring the resulting downstream dependencies are optional
 * too, so:
 *   - with no key: no `Client`/`Models` bean exists. `GeminiClientFactory` requires every caller
 *     to have their own key (`MissingApiKeyException` otherwise), and [geminiEmbeddingProvider]
 *     below returns `null` — RAG is off entirely.
 *   - with a key: both work exactly as before this system-key-optional behavior was added.
 * Deliberately builds the `Client` explicitly from the resolved property rather than relying on
 * `Client()`'s own implicit `GOOGLE_API_KEY` env lookup, so presence/absence goes through Spring's
 * config resolution (testable, overridable) instead of a raw process-environment read.
 */
@Configuration
class GenAIConfig {

    @Bean
    fun googleGenAiClient(
        @Value("\${app.llm.gemini.system-api-key:}") apiKey: String
    ): Client? = apiKey.takeIf { it.isNotBlank() }?.let { Client.builder().apiKey(it).build() }

    @Bean
    fun googleGenAiModels(client: Client?): Models? = client?.models

    // GeminiEmbeddingProvider is deliberately NOT @Service/component-scanned — see its own kdoc
    // for why a class-level @ConditionalOnBean(Models::class) doesn't reliably gate a
    // component-scanned class (bean-registration-order dependent). Wiring it here, as a @Bean
    // factory method taking the same nullable `Models?` this class already produces, is the
    // reliable version of the same "no Models -> no bean" propagation.
    @Bean
    @ConditionalOnProperty(name = ["app.embedding.provider"], havingValue = "gemini")
    fun geminiEmbeddingProvider(models: Models?, meterRegistry: MeterRegistry): EmbeddingProvider? =
        models?.let { GeminiEmbeddingProvider(it, meterRegistry) }
}
