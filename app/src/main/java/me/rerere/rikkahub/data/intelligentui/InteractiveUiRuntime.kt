package me.rerere.rikkahub.data.intelligentui

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One bounded JS runtime for the entire process, no browser, network, filesystem or native bindings. */
object InteractiveUiRuntime {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runtime: QuickJs? = null
    private var idleClose: Job? = null

    @Volatile private var cachedPrompt: String? = null

    fun prompt(context: Context): String = cachedPrompt ?: context.assets
        .open("intelligent-ui/prompt.txt").bufferedReader().use { it.readText() }.also { cachedPrompt = it }

    suspend fun render(context: Context, source: String, state: JsonObject, event: JsonObject? = null): JsonObject =
        withContext(Dispatchers.Default) {
            require(source.length <= INTERACTIVE_UI_SOURCE_LIMIT)
            require(state.toString().length <= INTERACTIVE_UI_STATE_LIMIT)
            mutex.withLock {
                idleClose?.cancel()
                try {
                    val js = runtime ?: QuickJs.create(Dispatchers.Default).also { instance ->
                        runtime = instance
                        instance.memoryLimit = 32L * 1024 * 1024
                        instance.maxStackSize = 512L * 1024
                        instance.evaluationTimeoutMillis = 2_000
                        val engine = context.assets.open(
                            "intelligent-ui/engine.js"
                        ).bufferedReader().use { it.readText() }
                        instance.evaluate<Any?>(engine)
                    }
                    js.evaluationTimeoutMillis = 600
                    val request = buildJsonObject {
                        put("source", source)
                        put("state", state)
                        event?.let { put("event", it) }
                    }
                    val result = js.evaluate<String>("RikkaOpenUI.renderJson(${JsonPrimitive(request.toString())})")
                    require(result.length <= 256 * 1024) { "交互结果过大" }
                    Json.parseToJsonElement(result).jsonObject
                } catch (error: Throwable) {
                    runtime?.close()
                    runtime = null
                    throw error
                } finally {
                    idleClose = scope.launch {
                        delay(15_000)
                        mutex.withLock {
                            runtime?.close()
                            runtime = null
                        }
                    }
                }
            }
        }
}
