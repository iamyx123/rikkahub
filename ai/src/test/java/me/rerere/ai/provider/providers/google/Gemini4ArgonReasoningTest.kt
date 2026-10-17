package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemini4ArgonReasoningTest {
    private val modelIds = listOf("gemini-4-argon", "Gemini-4-Argon", "google/gemini-4-argon")
    private val messages = listOf(UIMessage.user("hello"))
    private val client = OkHttpClient()

    private fun params(modelId: String, level: ReasoningLevel) = TextGenerationParams(
        model = Model(
            modelId = modelId,
            abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId) ?: emptyList(),
        ),
        reasoningLevel = level,
    )

    @Test
    fun `Argon aliases have reasoning and vision capabilities`() {
        modelIds.forEach { modelId ->
            assertTrue(modelId, ModelRegistry.GEMINI_4.match(modelId))
            assertTrue(modelId, ModelRegistry.GEMINI_SERIES.match(modelId))
            assertTrue(modelId, params(modelId, ReasoningLevel.HIGH).model.abilities.contains(ModelAbility.REASONING))
            assertTrue(modelId, ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId).contains(Modality.IMAGE))
        }
    }

    @Test
    fun `generateContent sends levels rather than token budgets for every Argon setting`() {
        val provider = GoogleProvider(client)
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        val expected = mapOf(
            ReasoningLevel.OFF to "minimal", ReasoningLevel.AUTO to null,
            ReasoningLevel.LOW to "low", ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high", ReasoningLevel.XHIGH to "high", ReasoningLevel.MAX to "high",
        )
        modelIds.forEach { modelId ->
            expected.forEach { (level, thinkingLevel) ->
                val body = method.invoke(provider, messages, params(modelId, level)) as JsonObject
                val config = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
                assertEquals("$modelId $level", thinkingLevel, config["thinkingLevel"]?.jsonPrimitive?.content)
                assertFalse("$modelId $level", config.containsKey("thinkingBudget"))
                assertTrue(config["includeThoughts"]!!.jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun `Interactions API sends Argon thinking levels`() {
        val api = InteractionsAPI(client)
        modelIds.forEach { modelId ->
            ReasoningLevel.entries.forEach { level ->
                val body = api.buildRequestBody(messages, params(modelId, level), stream = true)
                val config = body["generation_config"]!!.jsonObject
                val expected = when (level) {
                    ReasoningLevel.OFF -> "minimal"
                    ReasoningLevel.AUTO -> null
                    ReasoningLevel.LOW -> "low"
                    ReasoningLevel.MEDIUM -> "medium"
                    else -> "high"
                }
                assertEquals("$modelId $level", expected, config["thinking_level"]?.jsonPrimitive?.content)
                assertFalse(config.containsKey("thinkingBudget"))
            }
        }
    }

    @Test
    fun `OpenAI compatible relay receives Argon reasoning effort`() {
        val api = ChatCompletionsAPI(client, KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val provider = ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1")
        modelIds.forEach { modelId ->
            listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.AUTO).forEach { level ->
                val body = method.invoke(api, messages, params(modelId, level), provider, true) as JsonObject
                assertEquals("$modelId $level", level.effort.takeUnless { level == ReasoningLevel.AUTO },
                    body["reasoning_effort"]?.jsonPrimitive?.content)
            }
        }
    }
}
