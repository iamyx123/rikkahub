package me.rerere.rikkahub.data.chem

import android.content.Context
import android.graphics.Picture
import android.util.Log
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.ui.components.richtext.StructureMode
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChemStructureEngine"

/**
 * SMILES 结构式渲染：assets/chem/engine.js（OpenChemLib）在 QuickJS 里算出 SVG，
 * AndroidSVG 录成 Picture 后由 Compose 直接画，不经过 WebView。
 *
 * 同一个结构只算一次；渲染在后台排队进行，完成后 [version] 加一，正在显示的公式据此重新排版。
 */
object ChemStructureEngine {
    /** SVG 坐标下的尺寸；fontSize 是原子标签的字号，用来和正文字号对齐 */
    class Structure(val picture: Picture, val width: Float, val height: Float, val fontSize: Float)

    sealed interface Result {
        class Ready(val structure: Structure) : Result
        class Failed(val message: String) : Result
    }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeMutex = Mutex()
    private var runtime: QuickJs? = null
    private var idleClose: Job? = null

    // 引擎输出与主题无关；按墨色录好的 Picture 另存一份
    private val svgCache = LruCache<String, Pair<String, Float>>(256)
    private val results = LruCache<String, Result>(256)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result>>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun key(smiles: String, mode: StructureMode, ink: Int) = "${mode.id}|$ink|$smiles"

    fun cached(smiles: String, mode: StructureMode, ink: Int): Result? = results[key(smiles, mode, ink)]

    /** 没有缓存时在后台开始渲染，不等待结果 */
    fun request(smiles: String, mode: StructureMode, ink: Int) {
        val key = key(smiles, mode, ink)
        if (results[key] != null || pending.containsKey(key)) return
        scope.launch { render(smiles, mode, ink) }
    }

    /** 导出图片时没法等下一帧，直接同步算出来 */
    fun renderBlocking(smiles: String, mode: StructureMode, ink: Int): Result =
        cached(smiles, mode, ink) ?: runBlocking(Dispatchers.Default) { render(smiles, mode, ink) }

    suspend fun render(smiles: String, mode: StructureMode, ink: Int): Result {
        val key = key(smiles, mode, ink)
        results[key]?.let { return it }
        val deferred = CompletableDeferred<Result>()
        val existing = pending.putIfAbsent(key, deferred)
        if (existing != null) return existing.await()
        val result = try {
            val (svg, fontSize) = engineSvg(smiles, mode)
            Result.Ready(record(svg, fontSize, ink))
        } catch (error: Throwable) {
            Log.w(TAG, "render $smiles failed", error)
            Result.Failed(error.message ?: error.javaClass.simpleName)
        }
        results.put(key, result)
        pending.remove(key)
        deferred.complete(result)
        _version.update { it + 1 }
        return result
    }

    private suspend fun engineSvg(smiles: String, mode: StructureMode): Pair<String, Float> {
        val cacheKey = "${mode.id}|$smiles"
        svgCache[cacheKey]?.let { return it }
        require(smiles.isNotBlank() && smiles.length <= 2000) { "SMILES 为空或过长" }
        val output = runtimeMutex.withLock {
            idleClose?.cancel()
            try {
                val js = runtime ?: QuickJs.create(Dispatchers.Default).also { instance ->
                    runtime = instance
                    instance.memoryLimit = 128L * 1024 * 1024
                    instance.maxStackSize = 1024L * 1024
                    // 第一次要解析约 1 MB 的引擎，慢设备上给足时间
                    instance.evaluationTimeoutMillis = 15_000
                    val engine = appContext.assets.open("chem/engine.js").bufferedReader().use { it.readText() }
                    instance.evaluate<Any?>(engine)
                }
                js.evaluationTimeoutMillis = 5_000
                js.evaluate<String>(
                    "RikkaChem.render(${JsonPrimitive(smiles)}, ${JsonPrimitive(mode.id)}, true)"
                )
            } catch (error: Throwable) {
                runtime?.close()
                runtime = null
                throw error
            } finally {
                // 讲题时结构式会连续出现，空闲两分钟再释放运行时
                idleClose = scope.launch {
                    delay(120_000)
                    runtimeMutex.withLock {
                        runtime?.close()
                        runtime = null
                    }
                }
            }
        }
        val json = Json.parseToJsonElement(output).jsonObject
        json["error"]?.jsonPrimitive?.contentOrNull?.let { error("SMILES 无法解析：$it") }
        val svg = json["svg"]?.jsonPrimitive?.contentOrNull ?: error("引擎没有返回 SVG")
        val fontSize = json["fontSize"]?.jsonPrimitive?.floatOrNull ?: 14f
        return (svg to fontSize).also { svgCache.put(cacheKey, it) }
    }

    // OpenChemLib 给每个原子和键叠了一层透明的点击区域，画图用不到
    private val EVENT_LAYER_REGEX = Regex("""\s*<(?:line|circle)[^>]*class="event"[^>]*/>""")

    private fun record(svg: String, fontSize: Float, ink: Int): Structure {
        val color = "#%06X".format(ink and 0xFFFFFF)
        val document = SVG.getFromString(
            svg.replace(EVENT_LAYER_REGEX, "").replace("rgb(0,0,0)", color)
        )
        val width = document.documentWidth
        val height = document.documentHeight
        require(width > 0 && height > 0) { "结构式尺寸无效" }
        return Structure(document.renderToPicture(), width, height, fontSize)
    }
}
