package me.rerere.rikkahub.ui.components.richtext

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import me.rerere.rikkahub.data.chem.ChemStructureEngine
import me.rerere.ui.components.LocalExportContext

// 原子标签比正文略小一点，和课本里结构式的比例接近
private const val LABEL_TO_TEXT = 0.9f

// 说明文字（如「乙醇」）相对正文的大小
private const val CAPTION_SCALE = 0.85f

/** 结构式按正文字号缩放后的尺寸（像素） */
internal fun ChemStructureEngine.Structure.scaleFor(textSizePx: Float): Float = textSizePx * LABEL_TO_TEXT / fontSize

/** 没渲染好时先按这个大小占位 */
internal fun estimatedStructureSize(textSizePx: Float): Pair<Float, Float> = textSizePx * 3f to textSizePx * 2f

internal fun captionHeightPx(textSizePx: Float, caption: String?): Float =
    if (caption.isNullOrEmpty()) 0f else textSizePx * CAPTION_SCALE * 1.5f

@Composable
fun ChemStructureImage(
    part: MathPart.Structure,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val density = LocalDensity.current
    val ink = LocalContentColor.current
    val inkArgb = ink.toArgb()
    val textSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize }.takeOrElse { 16.sp }
    val textSizePx = with(density) { textSize.toPx() }
    val exporting = LocalExportContext.current
    val result by produceState(
        initialValue = if (exporting) {
            ChemStructureEngine.renderBlocking(part.smiles, part.mode, inkArgb)
        } else {
            ChemStructureEngine.cached(part.smiles, part.mode, inkArgb)
        },
        part, inkArgb,
    ) {
        value = ChemStructureEngine.render(part.smiles, part.mode, inkArgb)
    }
    ChemStructureContent(result, part, textSizePx, density, ink, modifier)
}

/** 行内公式和独立公式共用的绘制部分；[result] 为 null 表示还在渲染 */
@Composable
internal fun ChemStructureContent(
    result: ChemStructureEngine.Result?,
    part: MathPart.Structure,
    textSizePx: Float,
    density: Density,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (result) {
            is ChemStructureEngine.Result.Ready -> {
                val structure = result.structure
                val scale = structure.scaleFor(textSizePx)
                val width = with(density) { (structure.width * scale).toDp() }
                val height = with(density) { (structure.height * scale).toDp() }
                Canvas(modifier = Modifier.size(width, height)) {
                    drawContext.canvas.nativeCanvas.drawPicture(
                        structure.picture,
                        RectF(0f, 0f, size.width, size.height),
                    )
                }
            }

            // 解析失败时把原文给出来，至少能看出写的是什么
            is ChemStructureEngine.Result.Failed -> Text(
                text = part.smiles,
                fontFamily = FontFamily.Monospace,
                color = ink.copy(alpha = 0.7f),
                fontSize = with(density) { (textSizePx * 0.85f).toSp() },
            )

            null -> {
                val (w, h) = estimatedStructureSize(textSizePx)
                Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }))
            }
        }
        part.caption?.let { caption ->
            Text(
                text = caption,
                color = ink,
                fontSize = with(density) { (textSizePx * CAPTION_SCALE).toSp() },
                lineHeight = 1.3.em,
                maxLines = 1,
            )
        }
    }
}

/**
 * 含结构式的独立公式：结构式、方程式片段和普通 TeX 横向排列，放不下时换行。
 * 方程式按加号、箭头拆开，长反应式在窄屏上也能完整显示。
 */
@Composable
fun ChemMathRow(
    parts: List<MathPart>,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    spacing: Dp = 2.dp,
) {
    val textSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize }
    val color = LocalContentColor.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { part ->
            when (part) {
                is MathPart.Tex -> LatexText(latex = part.latex, fontSize = textSize, color = color)
                is MathPart.Chem -> part.pieces.forEach { piece ->
                    LatexText(latex = piece, fontSize = textSize, color = color)
                }

                is MathPart.Structure -> ChemStructureImage(part = part, fontSize = textSize)
            }
        }
    }
}

/** 找出一段 Markdown 里所有的结构式，用来提前开始渲染 */
internal fun collectStructures(content: String): List<MathPart.Structure> {
    if ("\\smiles" !in content) return emptyList()
    return SMILES_COMMAND_REGEX.findAll(content).flatMap { match ->
        parseMathParts(match.value).filterIsInstance<MathPart.Structure>()
    }.distinct().toList()
}

private val SMILES_COMMAND_REGEX = Regex("""\\smiles\s*(?:\[[^\]\n]*])?\s*\{[^{}\n]*}""")

/** 正在显示的内容里有结构式时，渲染完成会改变这个值，让公式重新排版 */
@Composable
internal fun rememberChemStructureVersion(content: String, ink: Int): Int {
    val structures = remember(content) { collectStructures(content) }
    if (structures.isEmpty()) return 0
    LaunchedEffect(structures, ink) {
        structures.forEach { ChemStructureEngine.request(it.smiles, it.mode, ink) }
    }
    val version by ChemStructureEngine.version.collectAsState()
    return version
}
