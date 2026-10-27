package me.rerere.rikkahub.ui.components.richtext

import org.scilab.forge.jlatexmath.ChemFormulaBridge

/** 结构式的画法：键线式、结构简式（碳上标出 CH₃ 等）、结构式（画出所有 C–H 键） */
enum class StructureMode(val id: String) {
    SKELETAL("skeletal"),
    CONDENSED("condensed"),
    FULL("full"),
}

private val STRUCTURE_MODES = mapOf(
    "skeletal" to StructureMode.SKELETAL,
    "line" to StructureMode.SKELETAL,
    "键线式" to StructureMode.SKELETAL,
    "condensed" to StructureMode.CONDENSED,
    "简式" to StructureMode.CONDENSED,
    "结构简式" to StructureMode.CONDENSED,
    "full" to StructureMode.FULL,
    "structural" to StructureMode.FULL,
    "结构式" to StructureMode.FULL,
)

/** 公式里的一段。\ce 和 \smiles 只在最外层拆出来，嵌在其他命令参数里的 \ce 交给 [expandChemCommands] */
sealed interface MathPart {
    data class Tex(val latex: String) : MathPart

    /** 展开后的化学方程式，每段结尾是加号或箭头，段与段之间可以换行 */
    data class Chem(val pieces: List<String>) : MathPart

    data class Structure(val smiles: String, val mode: StructureMode, val caption: String?) : MathPart
}

fun hasChemCommand(formula: String): Boolean = "\\ce" in formula || "\\smiles" in formula || "\\pu" in formula

/** 公式里没有 \ce、\smiles 时返回只含一段 [MathPart.Tex] 的列表 */
fun parseMathParts(formula: String): List<MathPart> {
    if ("\\ce" !in formula && "\\smiles" !in formula) return listOf(MathPart.Tex(formula))
    val parts = mutableListOf<MathPart>()
    val tex = StringBuilder()
    fun flushTex() {
        if (tex.isNotBlank()) parts += MathPart.Tex(tex.toString())
        tex.clear()
    }
    var depth = 0
    var i = 0
    while (i < formula.length) {
        val c = formula[i]
        if (c == '\\' && depth == 0) {
            val command = when {
                formula.isCommandAt(i, "ce") -> "ce"
                formula.isCommandAt(i, "smiles") -> "smiles"
                else -> null
            }
            if (command != null) {
                val parsed = formula.readCommand(i + 1 + command.length)
                if (parsed != null) {
                    flushTex()
                    parts += if (command == "ce") {
                        MathPart.Chem(chemPieces(ceToTex(parsed.argument)))
                    } else {
                        MathPart.Structure(
                            smiles = parsed.argument.trim(),
                            mode = STRUCTURE_MODES[parsed.option?.trim()?.lowercase()] ?: StructureMode.SKELETAL,
                            caption = parsed.caption?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }
                    i = parsed.end
                    continue
                }
            }
        }
        when (c) {
            '\\' -> {
                // \{ \} 这类转义不算括号
                tex.append(c)
                if (i + 1 < formula.length) tex.append(formula[i + 1])
                i += 2
                continue
            }

            '{' -> depth++
            '}' -> depth--
        }
        tex.append(c)
        i++
    }
    flushTex()
    return parts
}

/** 把嵌在其他命令里的 \ce{...}、\pu{...} 展开成 JLatexMath 能直接画的 TeX */
fun expandChemCommands(latex: String): String {
    if ("\\ce" !in latex && "\\pu" !in latex) return latex
    val out = StringBuilder()
    var i = 0
    while (i < latex.length) {
        val command = when {
            latex[i] != '\\' -> null
            latex.isCommandAt(i, "ce") -> "ce"
            latex.isCommandAt(i, "pu") -> "pu"
            else -> null
        }
        val parsed = command?.let { latex.readCommand(i + 1 + it.length, allowCaption = false) }
        if (command != null && parsed != null) {
            val expanded = if (command == "ce") ceToTex(parsed.argument) else puToTex(parsed.argument)
            out.append('{').append(expanded).append('}')
            i = parsed.end
        } else {
            out.append(latex[i])
            if (latex[i] == '\\' && i + 1 < latex.length) {
                out.append(latex[i + 1])
                i++
            }
            i++
        }
    }
    return out.toString()
}

private val ceCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
}

// 结晶水的点写成 * 或 · 时转换器不认识，统一成 mhchem 的 .
private val HYDRATE_DOT_REGEX = Regex("""\s*[*·•∙⋅]\s*""")

/** \ce 的参数转成 TeX；转换器不认识 # 三键，换成 \equiv */
fun ceToTex(formula: String): String = synchronized(ceCache) {
    ceCache.getOrPut(formula) {
        ChemFormulaBridge.toTex(formula.trim().replace(HYDRATE_DOT_REGEX, "."))
            .replace("\\#", "\\equiv ")
    }
}

private fun puToTex(quantity: String): String =
    quantity.trim().split(Regex("""\s+""")).joinToString("\\ ") { token ->
        if (token.first().isDigit() || token.first() == '-') token else "\\mathrm{$token}"
    }

// 转换器在每个物质和运算符之间放 \ 空格；遇到这些运算符就在它后面断开
private val CHEM_OPERATOR_PREFIXES = listOf(
    "\\mathbin", "=", "\\xrightarrow", "\\xleftarrow", "\\long", "\\Long", "\\underset", "\\overset",
    "\\rightleftharpoons", "\\rightarrow", "\\leftarrow", "\\leftrightarrow",
)

/** 按最外层的 `\ ` 把展开后的方程式切成「物质 + 运算符」一段一段，供行内换行 */
fun chemPieces(tex: String): List<String> {
    val items = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var i = 0
    while (i < tex.length) {
        val c = tex[i]
        if (c == '\\' && i + 1 < tex.length) {
            if (depth == 0 && tex[i + 1] == ' ') {
                items += current.toString()
                current.clear()
            } else {
                current.append(c).append(tex[i + 1])
            }
            i += 2
            continue
        }
        if (c == '{') depth++
        if (c == '}') depth--
        current.append(c)
        i++
    }
    items += current.toString()
    val pieces = mutableListOf<String>()
    val piece = mutableListOf<String>()
    items.filter { it.isNotBlank() }.forEach { item ->
        piece += item
        if (CHEM_OPERATOR_PREFIXES.any { item.startsWith(it) }) {
            pieces += piece.joinToString("\\ ") + "\\ "
            piece.clear()
        }
    }
    if (piece.isNotEmpty()) pieces += piece.joinToString("\\ ")
    return pieces.ifEmpty { listOf(tex) }
}

private fun String.isCommandAt(index: Int, name: String): Boolean {
    if (!startsWith(name, index + 1) || this[index] != '\\') return false
    val next = getOrNull(index + 1 + name.length)
    return next == null || !next.isLetter()
}

private class ParsedCommand(val option: String?, val argument: String, val caption: String?, val end: Int)

/** 读 `[可选]{参数}{说明}`，括号不完整（比如还在流式输出）时返回 null */
private fun String.readCommand(start: Int, allowCaption: Boolean = true): ParsedCommand? {
    var i = start
    while (i < length && this[i] == ' ') i++
    var option: String? = null
    if (getOrNull(i) == '[') {
        val close = indexOf(']', i)
        if (close < 0) return null
        option = substring(i + 1, close)
        i = close + 1
        while (i < length && this[i] == ' ') i++
    }
    val argumentEnd = closingBrace(i) ?: return null
    val argument = substring(i + 1, argumentEnd)
    var end = argumentEnd + 1
    var caption: String? = null
    if (allowCaption && getOrNull(end) == '{') {
        closingBrace(end)?.let { captionEnd ->
            caption = substring(end + 1, captionEnd)
            end = captionEnd + 1
        }
    }
    return ParsedCommand(option, argument, caption, end)
}

private fun String.closingBrace(open: Int): Int? {
    if (getOrNull(open) != '{') return null
    var depth = 0
    var i = open
    while (i < length) {
        when (this[i]) {
            '\\' -> i++
            '{' -> depth++
            '}' -> if (--depth == 0) return i
        }
        i++
    }
    return null
}
