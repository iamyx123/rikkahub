package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.QuoteUp
import me.rerere.rikkahub.R

/**
 * 引用 Sheet：展示 AI 回答全文（只读、可选择），用户选中一段后点「引用选中」即把这段送入输入框。
 * 未选择任何内容时，默认引用整条消息文本。
 *
 * 用只读 [BasicTextField] 而非 SelectionContainer，是因为后者无法把用户选区取出来做自定义动作；
 * TextFieldState 则可直接读取 selection 与 text。
 */
@Composable
fun ChatMessageQuoteSheet(
    message: UIMessage,
    onQuote: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val fullText = remember(message) {
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }
    }
    val textState = rememberTextFieldState(fullText)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismissRequest) {
                    Icon(HugeIcons.Cancel01, null)
                }

                Text(
                    text = "引用",
                    style = MaterialTheme.typography.headlineSmall,
                )

                TextButton(
                    onClick = {
                        val sel = textState.selection
                        val text = textState.text.toString()
                        val selected = if (sel.collapsed) {
                            text
                        } else {
                            text.substring(sel.min.coerceIn(0, text.length), sel.max.coerceIn(0, text.length))
                        }
                        if (selected.isNotBlank()) {
                            onQuote(selected)
                        }
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.QuoteUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("引用选中")
                }
            }

            Text(
                text = "选中要引用的内容后点「引用选中」；不选则引用整条。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            if (fullText.isBlank()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_text_content_to_copy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                BasicTextField(
                    state = textState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    readOnly = true,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    scrollState = rememberScrollState(),
                )
            }
        }
    }
}
