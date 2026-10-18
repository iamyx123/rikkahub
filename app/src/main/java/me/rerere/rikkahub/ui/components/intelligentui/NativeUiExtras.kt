package me.rerere.rikkahub.ui.components.intelligentui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.context.LocalSettings
import kotlin.math.ceil

internal fun uiChange(target: String, value: JsonElement) = buildJsonObject {
    put("type", "change"); put("target", target); put("value", value)
}

@Composable
internal fun NativeUiSlot(value: JsonElement?, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    if (value is JsonArray) value.forEach { NativeUiNode(it, enabled, dispatch) }
    else NativeUiNode(value, enabled, dispatch)
}

@Composable
internal fun NativeUiIcon(p: JsonObject) {
    val context = LocalContext.current.applicationContext
    val name = p.text("name").takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) }
    var data by remember(name) { mutableStateOf<JsonObject?>(null) }
    var missing by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) {
        data = withContext(Dispatchers.IO) {
            runCatching {
                requireNotNull(name)
                context.assets.open("intelligent-ui/icons/$name.json").bufferedReader().use {
                    Json.parseToJsonElement(it.readText()).jsonObject
                }
            }.getOrNull()
        }
        missing = data == null
    }
    Box(Modifier.size(p.number("size", 24.0).coerceIn(12.0, 64.0).dp).semantics { if(p.text("label").isNotBlank()) contentDescription = p.text("label") }) {
        data?.let { NativeUiVector(it, showDescription = false) }
        if (missing) Text(p.text("label", "◇"), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun NativeUiCarousel(id: String, p: JsonObject, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    val children = p.list("children")
    var selected by rememberSaveable(id) { mutableIntStateOf(0) }
    val page = selected.coerceIn(0, (children.size - 1).coerceAtLeast(0))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (children.isNotEmpty()) key(page) { NativeUiNode(children[page], enabled, dispatch) }
        if (children.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selected = page - 1 }, enabled = page > 0) { Text("上一张") }
            Text("${page + 1} / ${children.size}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { selected = page + 1 }, enabled = page + 1 < children.size) { Text("下一张") }
        }
    }
}

@Composable
internal fun NativeUiPopover(id: String, p: JsonObject, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    OutlinedCard(onClick = { open = !open }) {
        Column(Modifier.padding(12.dp)) { NativeUiSlot(p["trigger"], enabled = false, dispatch = {}) }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
        text = { Column { NativeUiSlot(p["content"], enabled, dispatch) } },
    )
}

@Composable
internal fun NativeUiLink(p: JsonObject) {
    val uri = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    val url = p.text("url")
    TextButton(enabled = url.startsWith("https://") || url.startsWith("http://"), onClick = {
        failed = runCatching { uri.openUri(url) }.isFailure
    }) { Text(p.text("text", url)) }
    if (failed) Text("无法打开链接：$url", style = MaterialTheme.typography.bodySmall)
}

/** One deadline wake-up on e-ink; a visible normal-screen timer refreshes once per second. */
@Composable
internal fun NativeUiTimer(id: String, p: JsonObject, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    val eink = LocalSettings.current.displaySetting.disableCursorBlink
    val seconds = p.number("seconds").toInt().coerceIn(0, 86400 * 7)
    var deadline by rememberSaveable(id) { mutableLongStateOf(0L) }
    var initial by rememberSaveable(id) { mutableIntStateOf(seconds) }
    var started by rememberSaveable(id) { mutableStateOf(false) }
    var shown by remember(id) { mutableIntStateOf(seconds) }
    var done by rememberSaveable(id) { mutableStateOf(false) }
    val liveDispatch by rememberUpdatedState(dispatch)
    val liveTarget by rememberUpdatedState(p.text("secondsBinding"))
    val liveEnabled by rememberUpdatedState(enabled)
    fun remaining() = ceil((deadline - SystemClock.elapsedRealtime()) / 1000.0).toInt().coerceAtLeast(0)
    LaunchedEffect(seconds) { if (deadline == 0L) shown = seconds }
    LaunchedEffect(deadline, eink, enabled) {
        if (deadline == 0L || !enabled) return@LaunchedEffect
        while (true) {
            val left = remaining()
            if (left == 0) {
                deadline = 0L; shown = 0; done = true
                if (liveEnabled) liveDispatch(uiChange(liveTarget, JsonPrimitive(0)))
                break
            }
            if (!eink) shown = left
            delay(if (eink) (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1) else 1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.text("label", "倒计时"), style = MaterialTheme.typography.labelLarge)
        Text(if (deadline != 0L && eink) "计时中" else if (done) "计时结束" else "%d:%02d".format(shown / 60, shown % 60), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && (seconds > 0 || deadline != 0L), onClick = {
                if (deadline != 0L) {
                    shown = remaining(); deadline = 0L
                    dispatch(uiChange(p.text("secondsBinding"), JsonPrimitive(shown)))
                } else {
                    if(!started) initial = seconds
                    started = true; shown = seconds; done = false
                    deadline = SystemClock.elapsedRealtime() + seconds * 1000L
                }
            }) { Text(if (deadline != 0L) "暂停" else "开始") }
            TextButton(enabled = enabled, onClick = {
                deadline = 0L; shown = initial; done = false; started = false
                dispatch(uiChange(p.text("secondsBinding"), JsonPrimitive(initial)))
            }) { Text("重置") }
        }
    }
}

@Composable
internal fun NativeUiCelebration(p: JsonObject) {
    if ((p["when"] as? JsonPrimitive)?.booleanOrNull != true) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text("✓  " + p.text("message", "完成了"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmallEmphasized)
    }
}
