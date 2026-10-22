package me.rerere.rikkahub.ui.components.intelligentui

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.intelligentui.InteractiveUiRuntime
import me.rerere.rikkahub.data.intelligentui.interactiveUiKey
import me.rerere.rikkahub.data.intelligentui.interactiveUiPendingUpdates
import me.rerere.rikkahub.data.intelligentui.InteractiveUiPersistenceException
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.StaticCaretTextField
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MathBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject
import java.time.LocalDate
import kotlin.uuid.Uuid

private data class UiMessageContext(
    val message: UIMessage,
    val enabled: Boolean,
    val save: suspend (String, JsonObject) -> Unit,
    val draft: (String) -> Unit,
    val conversationId: Uuid? = null,
)
private val LocalInteractiveMessage = staticCompositionLocalOf<UiMessageContext?> { null }

@Composable
fun ProvideInteractiveUiExport(message: UIMessage, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInteractiveMessage provides UiMessageContext(message, false, { _, _ -> }, {}), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideInteractiveUi(conversationId: Uuid?, message: UIMessage, enabled: Boolean, draft: (String) -> Unit, content: @Composable () -> Unit) {
    val service: ChatService = koinInject()
    val value = remember(conversationId, message, enabled, draft) {
        UiMessageContext(message, enabled && message.role == MessageRole.ASSISTANT,
            save = { source, state -> conversationId?.let { check(service.saveInteractiveUiState(it, message.id, source, state)) { "原卡片已改变，状态未保存" } } },
            draft = draft, conversationId = conversationId)
    }
    CompositionLocalProvider(
        LocalInteractiveMessage provides value,
        LocalRippleConfiguration provides if (LocalSettings.current.displaySetting.disableCursorBlink) null else LocalRippleConfiguration.current,
        content = content,
    )
}

internal fun JsonObject.text(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
internal fun JsonObject.number(key: String, fallback: Double = 0.0) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(-1e12,1e12) ?: fallback
internal fun JsonObject.list(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
private fun change(target: String, value: JsonElement) = buildJsonObject { put("type","change"); put("target",target); put("value",value) }

@Composable
fun InteractiveUiBlock(source: String, complete: Boolean) {
    val host = LocalInteractiveMessage.current
    if (host == null || host.message.role != MessageRole.ASSISTANT) {
        HighlightCodeBlock(source,"openui-lang",Modifier.fillMaxWidth(),completeCodeBlock=complete)
        return
    }
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val key = remember(source) { interactiveUiKey(source) }
    val saved = host.message.interactiveUiStates[key] ?: JsonObject(emptyMap())
    var state by remember(key) { mutableStateOf(saved) }
    var result by remember(key) { mutableStateOf<JsonObject?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var showSource by rememberSaveable(key) { mutableStateOf(false) }
    val mounted = remember(key) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val evaluationMutex = remember(key) { Mutex() }
    val currentHost by rememberUpdatedState(host)
    suspend fun evaluate(event: JsonObject? = null) = evaluationMutex.withLock {
        busy = true
        var persistenceStarted = false
        try {
            if (event?.text("type") == "persist") {
                if (state.isNotEmpty()) { persistenceStarted = true; currentHost.save(source,state) }
                return@withLock
            }
            val next = InteractiveUiRuntime.render(context,source,state,event)
            next.text("error").takeIf { it.isNotBlank() }?.let { throw IllegalArgumentException(it) }
            result = next
            state = next["state"]?.jsonObject ?: state
            error = null
            val transient = (event?.get("transient") as? JsonPrimitive)?.booleanOrNull == true
            if (!transient && state.isNotEmpty() && state != currentHost.message.interactiveUiStates[key]) {
                persistenceStarted = true
                currentHost.save(source,state)
            }
            next.list("effects").takeIf { mounted.get() }?.forEach { item ->
                val effect = item.jsonObject
                when (effect.text("type")) {
                    "draft" -> currentHost.draft(effect.text("text"))
                    "url" -> if (effect.text("text").startsWith("https://") || effect.text("text").startsWith("http://")) uriHandler.openUri(effect.text("text"))
                    "copy" -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("交互卡片",effect.text("text")))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = failure.message?.take(200) ?: "卡片暂时无法显示，请查看源码"
            if (persistenceStarted) throw InteractiveUiPersistenceException(failure)
            if (event != null) throw failure
        }
        finally { busy = false }
    }
    fun enqueue(event: JsonObject? = null) {
        val id = currentHost.conversationId
        if (id != null) interactiveUiPendingUpdates.enqueue(id, "${currentHost.message.id}/$key", event) { evaluate(it) }
        else scope.launch { evaluate(event) }
    }
    LaunchedEffect(key, complete, host.enabled) {
        if (complete && host.enabled && result == null) enqueue()
    }
    // A state written by another client is restored without executing any action.
    LaunchedEffect(saved) { if (!busy && saved != state) { state=saved; if (complete && host.enabled) enqueue() } }
    DisposableEffect(key) {
        onDispose {
            mounted.set(false)
            enqueue(buildJsonObject { put("type", "persist") })
        }
    }
    fun dispatch(event: JsonObject) {
        if (!host.enabled) return
        enqueue(event)
    }
    Surface(shape=MaterialTheme.shapes.largeIncreased,color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("交互卡片",style=MaterialTheme.typography.titleSmallEmphasized,modifier=Modifier.weight(1f))
                if (busy && !LocalSettings.current.displaySetting.disableCursorBlink) Text("更新中",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={showSource=!showSource}) { Text(if(showSource) "显示卡片" else "源码") }
                TextButton(enabled=!busy&&host.enabled,onClick={dispatch(buildJsonObject{put("type","reset")})}) { Text("重置") }
            }
            if (!complete || !host.enabled && result==null) Text("正在生成交互内容…",style=MaterialTheme.typography.bodySmall)
            error?.let { message ->
                Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                TextButton(enabled=!busy,onClick={enqueue()}) { Text("重新显示") }
            }
            if(showSource || error!=null && result==null) HighlightCodeBlock(source,"openui-lang",Modifier.fillMaxWidth(),completeCodeBlock=complete)
            else result?.get("root")?.let { NativeUiNode(it,host.enabled,::dispatch) }
            result?.list("errors")?.takeIf { it.isNotEmpty() }?.let {
                Text("部分内容未能显示，可查看源码",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Export remains synchronous: include saved values and the complete source for reproducibility. */
@Composable
fun InteractiveUiExport(source: String) {
    val message = LocalInteractiveMessage.current?.message
    val key = interactiveUiKey(source)
    val snapshot = LocalInteractiveUiSnapshots.current["${message?.id}/$key"]
    if(snapshot?.get("root") != null) { NativeUiSnapshot(snapshot["root"]); return }
    val values = message?.interactiveUiStates?.get(key)
    Column {
        Text("交互卡片 · 静态记录", style = MaterialTheme.typography.titleSmall)
        values?.forEach { (name, value) -> Text("$name = $value", style = MaterialTheme.typography.bodySmall) }
        HighlightCodeBlock(source, "openui-lang", Modifier.fillMaxWidth(), completeCodeBlock = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeUiNode(node: JsonElement?, enabled: Boolean, dispatch: (JsonObject)->Unit) {
    val obj=node as? JsonObject ?: run { if(node!=null && node!=JsonNull) Text((node as? JsonPrimitive)?.contentOrNull?:node.toString()); return }
    val type=obj.text("type"); val p=obj["props"] as? JsonObject ?: return
    val eink = LocalSettings.current.displaySetting.disableCursorBlink
    val children=p.list("children")
    val renderChildren: @Composable ()->Unit = { children.forEach { key((it as? JsonObject)?.text("id") ?: it.hashCode()) { NativeUiNode(it,enabled,dispatch) } } }
    val gap=p.number("gap",8.0).coerceIn(0.0,32.0).dp
    when(type) {
        "Col","Form","Box" -> Column(Modifier.fillMaxWidth().padding(if(type=="Box") p.number("padding",8.0).coerceIn(0.0,24.0).dp else 0.dp),verticalArrangement=Arrangement.spacedBy(gap)){renderChildren()}
        "Row" -> FlowRow(horizontalArrangement=Arrangement.spacedBy(gap),verticalArrangement=Arrangement.spacedBy(gap)){renderChildren()}
        "Grid" -> Column(verticalArrangement=Arrangement.spacedBy(gap)) {
            children.chunked(p.number("columns",2.0).toInt().coerceIn(1,3)).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                    row.forEach { child -> Box(Modifier.weight(1f)) { NativeUiNode(child,enabled,dispatch) } }
                }
            }
        }
        "Card" -> Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,border=if(eink)BorderStroke(1.dp,MaterialTheme.colorScheme.outline)else null){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){if(p.text("title").isNotBlank())Text(p.text("title"),style=MaterialTheme.typography.titleSmallEmphasized);renderChildren()}}
        "Carousel" -> NativeUiCarousel(obj.text("id"), p, enabled, dispatch)
        "Popover" -> NativeUiPopover(obj.text("id"), p, enabled, dispatch)
        "Link" -> NativeUiLink(p)
        "Icon" -> NativeUiIcon(p)
        "ListItem" -> Column { if(p.text("label").isNotBlank()) Text(p.text("label"),style=MaterialTheme.typography.labelSmall); NativeUiSlot(p["content"], enabled, dispatch) }
        "Title" -> Text(p.text("text"),style=MaterialTheme.typography.titleMedium)
        "Caption","Label" -> Text(p.text("text"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        "Badge" -> Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer,border=if(eink)BorderStroke(1.dp,MaterialTheme.colorScheme.outline)else null){Text(p.text("text"),Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium)}
        "Text" -> if(p["text"] is JsonArray) FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { NativeUiSlot(p["text"],enabled,dispatch) } else CompositionLocalProvider(LocalInteractiveMessage provides null) { MarkdownBlock(p.text("text")) }
        "Quote" -> Text(p.text("text"),style=MaterialTheme.typography.bodyMedium)
        "Markdown" -> CompositionLocalProvider(LocalInteractiveMessage provides null){MarkdownBlock(p.text("text"))}
        "Code" -> HighlightCodeBlock(p.text("text"),"plaintext",Modifier.fillMaxWidth(),completeCodeBlock=true)
        "Math" -> MathBlock(latex=p.text("text"),modifier=Modifier.fillMaxWidth(),fontSize=LocalTextStyle.current.fontSize)
        "Divider" -> HorizontalDivider()
        "Spacer" -> Spacer(Modifier.height(p.number("size",8.0).coerceIn(0.0,48.0).dp))
        "List" -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)){p.list("items").forEachIndexed{index,item->Row{Text(if(p.text("variant")=="numbers") "${index+1}.  " else if(p.text("variant")=="timeline") "│  " else "•  ");NativeUiNode(item,enabled,dispatch)}}}
        "IconButton" -> {
            val active = enabled && p.text("action").isNotBlank()
            val click = { dispatch(buildJsonObject { put("type", "action"); put("target", p.text("action")) }) }
            val icon: @Composable () -> Unit = { NativeUiIcon(buildJsonObject { put("name", p.text("icon")); put("label", p.text("label")) }) }
            if (eink) OutlinedButton(
                enabled = active, onClick = click,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, disabledContentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                icon()
                Spacer(Modifier.width(6.dp))
                Text(p.text("label").ifBlank { "操作" } + if (active) "" else "（不可用）")
            } else FilledTonalIconButton(enabled = active, onClick = click) { icon() }
        }
        "Pressable" -> {
            val active = enabled && p.text("action").isNotBlank()
            val selected = (p["selected"] as? JsonPrimitive)?.booleanOrNull
            OutlinedCard(
                enabled = active,
                onClick = { dispatch(buildJsonObject { put("type", "action"); put("target", p.text("action")) }) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(if (selected == true) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (selected != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = null)
                        Text(if (selected) "已选择" else "未选择", style = MaterialTheme.typography.labelMedium)
                    }
                    if (p.text("label").isNotBlank()) Text(p.text("label"), style = MaterialTheme.typography.labelLarge)
                    if (!active) Text("只读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    children.forEach { NativeUiNode(it, false, {}) }
                }
            }
        }
        "Button" -> {
            val click={dispatch(buildJsonObject{put("type","action");put("target",p.text("action"))})}
            val active=enabled && p.text("action").isNotBlank() && (p["disabled"] as? JsonPrimitive)?.booleanOrNull!=true
            val label = p.text("label") + if (active) "" else "（不可用）"
            if (eink) OutlinedButton(
                onClick = click, enabled = active,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, disabledContentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text(label) }
            else when(p.text("variant")){"text","ghost"->TextButton(onClick=click,enabled=active){Text(label)};"tonal","soft"->FilledTonalButton(onClick=click,enabled=active){Text(label)};"outline"->OutlinedButton(onClick=click,enabled=active){Text(label)};else->Button(onClick=click,enabled=active){Text(label)}}
        }
        "Input","TextArea","NumberInput" -> NativeInput(obj.text("id"),p,type,enabled,dispatch)
        "Slider" -> NativeUiSlider(obj.text("id"), p, enabled, dispatch)
        "Checkbox", "Switch" -> NativeUiBoolean(type, p, enabled, dispatch)
        "Select", "RadioGroup", "SegmentedControl" -> NativeUiChoice(type, p, enabled, dispatch)
        "DatePicker" -> {
            val context=LocalContext.current
            val date=runCatching{LocalDate.parse(p.text("value"))}.getOrDefault(LocalDate.now())
            OutlinedButton(enabled=enabled,onClick={DatePickerDialog(context,{_,year,month,day->dispatch(change(p.text("valueBinding"),JsonPrimitive(LocalDate.of(year,month+1,day).toString())))},date.year,date.monthValue-1,date.dayOfMonth).show()}){Text("${p.text("label")}  ${p.text("value")}")}
        }
        "Timer" -> NativeUiTimer(obj.text("id"),p,enabled,dispatch)
        "Celebration" -> NativeUiCelebration(p)
        "Progress" -> Column{Text("${p.text("label")} ${p.number("value")}",style=MaterialTheme.typography.labelMedium);LinearProgressIndicator(progress={ (p.number("value")/p.number("max",100.0).coerceAtLeast(0.001)).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())}
        "Flashcard","Accordion" -> {
            var expanded by rememberSaveable(obj.text("id")){mutableStateOf(false)}
            OutlinedCard(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(type=="Flashcard") NativeUiSlot(p[if(expanded)"back" else "front"],enabled,dispatch) else Text(p.text("title"))
                    if(type=="Accordion"&&expanded)renderChildren()
                    Text(if(expanded)"点击收起" else if(type=="Flashcard")"点击查看答案" else "点击展开",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        "Table" -> Column(Modifier.horizontalScroll(rememberScrollState())){
            Row{p.list("columns").forEach{Text((it as? JsonPrimitive)?.contentOrNull.orEmpty(),Modifier.width(140.dp).padding(8.dp),style=MaterialTheme.typography.labelLarge)}}
            HorizontalDivider()
            p.list("rows").take(100).forEach{row->Row{(row as? JsonArray)?.forEach{cell->Box(Modifier.width(140.dp).padding(8.dp)){NativeUiNode(cell,enabled,dispatch)}}}}
        }
        "Chart" -> NativeUiChart(p)
        "Svg" -> NativeUiVector(p)
        "Map" -> NativeUiMap(p)
        "MapMarker" -> NativeUiMap(buildJsonObject{put("markers",JsonArray(listOf(p)))})
        "Image" -> NativeUiImage(p)
        "SearchImage" -> NativeUiSearchImage(p)
        else -> Text("不支持的组件：$type",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NativeInput(id:String,p:JsonObject,type:String,enabled:Boolean,dispatch:(JsonObject)->Unit) {
    val value=p.text("value")
    var text by remember(id){mutableStateOf(value)}
    var focused by remember(id){mutableStateOf(false)}
    LaunchedEffect(value, focused) { if (!focused) text = value }
    val numeric=type=="NumberInput" || p.text("inputType")=="number"
    val update:(String)->Unit={next->if(enabled) { text=next;val parsed=if(numeric)next.toDoubleOrNull()?.takeIf{it.isFinite()}?.let(::JsonPrimitive) else JsonPrimitive(next);if(parsed!=null)dispatch(change(p.text("valueBinding"),parsed)) }}
    val keyboard=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text)
    if(LocalSettings.current.displaySetting.disableCursorBlink) {
        val state=rememberTextFieldState(text)
        LaunchedEffect(text){if(state.text.toString()!=text)state.setTextAndPlaceCursorAtEnd(text)}
        val onUpdate by rememberUpdatedState(update)
        LaunchedEffect(state){snapshotFlow{state.text.toString()}.distinctUntilChanged().drop(1).collect{if(it != text) onUpdate(it)}}
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(p.text("label"),style=MaterialTheme.typography.labelLarge)
            Surface(shape=MaterialTheme.shapes.small,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline)){
                StaticCaretTextField(state,MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),MaterialTheme.colorScheme.primary,
                    modifier=Modifier.fillMaxWidth().padding(12.dp).onFocusChanged { focused=it.isFocused },keyboardOptions=keyboard,enabled=enabled){field->field()}
            }
        }
    } else OutlinedTextField(value=text,onValueChange=update,enabled=enabled,label={Text(p.text("label"))},placeholder={Text(p.text("placeholder"))},keyboardOptions=keyboard,singleLine=type!="TextArea",maxLines=if(type=="TextArea")6 else 1,modifier=Modifier.fillMaxWidth().onFocusChanged { focused=it.isFocused })
}
