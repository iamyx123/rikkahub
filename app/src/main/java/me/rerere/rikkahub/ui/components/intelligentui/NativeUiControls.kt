package me.rerere.rikkahub.ui.components.intelligentui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.intelligentui.interactiveUiChange

@Composable
internal fun NativeUiSlider(id: String, p: JsonObject, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    val min = p.number("min").toFloat()
    val max = p.number("max", 100.0).toFloat().coerceAtLeast(min + maxOf(kotlin.math.abs(min) * 0.000001f, 0.001f))
    val actual = p.number("value", min.toDouble()).toFloat().coerceIn(min, max)
    var value by remember(id) { mutableFloatStateOf(actual) }
    var dragging by remember(id) { mutableStateOf(false) }
    LaunchedEffect(actual) { if (!dragging) value = actual }
    val target = p.text("valueBinding")
    val active = enabled && target.isNotBlank()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${p.text("label")}  ${"%.2f".format(value)} ${p.text("unit")}", style = MaterialTheme.typography.labelLarge)
        if (!active) Text("只读参数", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                value = it
                dispatch(interactiveUiChange(target, JsonPrimitive(it), transient = true))
            },
            onValueChangeFinished = {
                dragging = false
                dispatch(interactiveUiChange(target, JsonPrimitive(value), commit = true))
            },
            valueRange = min..max,
            enabled = active,
            steps = if (p.number("step") > 0) ((max - min) / p.number("step")).toInt().minus(1).coerceIn(0, 1000) else 0,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeUiChoice(type: String, p: JsonObject, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    val options = p.list("options").take(40).map { option ->
        if (option is JsonObject) Triple(option.text("value"), option.text("label", option.text("value")), option.text("description"))
        else Triple((option as? JsonPrimitive)?.contentOrNull.orEmpty(), (option as? JsonPrimitive)?.contentOrNull.orEmpty(), "")
    }
    val target = p.text("valueBinding")
    val active = enabled && target.isNotBlank()
    val selected = p.text("value")
    fun select(value: String) {
        if (active) dispatch(interactiveUiChange(target, JsonPrimitive(value), commit = true))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (p.text("label").isNotBlank()) Text(p.text("label"), style = MaterialTheme.typography.labelLarge)
        if (!active) Text("只读：${options.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { "未选择" }}", style = MaterialTheme.typography.bodySmall)
        when {
            type == "Select" -> {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (active) expanded = it }) {
                    OutlinedTextField(
                        value = options.firstOrNull { it.first == selected }?.second ?: selected,
                        onValueChange = {}, readOnly = true, enabled = active,
                        placeholder = { Text(p.text("placeholder", "请选择")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = active),
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { (value, label, _) -> DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { RadioButton(selected = selected == value, onClick = null) },
                            enabled = active,
                            onClick = { expanded = false; select(value) },
                        ) }
                    }
                }
            }
            type == "SegmentedControl" && options.size in 1..6 -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label, _) ->
                    SegmentedButton(
                        selected = selected == value,
                        onClick = { select(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        enabled = active,
                        icon = { SegmentedButtonDefaults.Icon(active = selected == value) },
                    ) { Text(label) }
                }
            }
            else -> options.forEach { (value, label, description) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = active) { select(value) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == value, onClick = { select(value) }, enabled = active)
                    Column {
                        Text(label)
                        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeUiBoolean(type: String, p: JsonObject, enabled: Boolean, dispatch: (JsonObject) -> Unit) {
    val checked = (p["value"] as? JsonPrimitive)?.booleanOrNull == true
    val target = p.text("valueBinding")
    val active = enabled && target.isNotBlank()
    val update: (Boolean) -> Unit = { dispatch(interactiveUiChange(target, JsonPrimitive(it), commit = true)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (type == "Checkbox") Checkbox(checked, update, enabled = active) else Switch(checked, update, enabled = active)
        Column(Modifier.weight(1f)) {
            Text(p.text("label"))
            val status = if (type == "Checkbox") {
                if (checked) "已选中" else "未选中"
            } else if (checked) "已开启" else "已关闭"
            Text(status + if (active) "" else " · 只读", style = MaterialTheme.typography.bodySmall)
        }
    }
}
