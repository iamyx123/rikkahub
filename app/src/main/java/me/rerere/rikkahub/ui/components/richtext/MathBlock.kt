package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import me.rerere.ui.components.LocalExportContext

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    LatexText(
        latex = proceededLatex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    val exporting = LocalExportContext.current
    val parts = remember(latex) { runCatching { parseMathParts(latex) }.getOrElse { listOf(MathPart.Tex(latex)) } }
    if (parts.any { it is MathPart.Structure }) {
        ChemMathRow(
            parts = parts,
            modifier = modifier.padding(vertical = 8.dp),
            fontSize = fontSize,
        )
    } else if (exporting) {
        // 导出/打印：屏幕上靠横向滚动查看的宽公式无法滚动，改为按可用宽度等比缩小字号以完整放下（不裁切）
        val density = LocalDensity.current
        val baseFs = fontSize.takeOrElse { LocalTextStyle.current.fontSize }
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val baseFsPx = with(density) { baseFs.toPx() }
            val naturalWidth = assumeLatexSize(proceededLatex, baseFsPx).width().toFloat()
            val fitFs = if (naturalWidth > 0f && naturalWidth > maxWidthPx) {
                baseFs * (maxWidthPx / naturalWidth)
            } else {
                baseFs
            }
            LatexText(
                latex = proceededLatex,
                color = LocalContentColor.current,
                fontSize = fitFs,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    } else {
        Box(
            modifier = modifier.padding(8.dp)
        ) {
            LatexText(
                latex = proceededLatex,
                color = LocalContentColor.current,
                fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
                modifier = Modifier
                    .align(Alignment.Center)
                    .horizontalScroll(
                        rememberScrollState()
                    ),
            )
        }
    }
}
