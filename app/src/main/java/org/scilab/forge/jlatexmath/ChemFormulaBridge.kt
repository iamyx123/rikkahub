package org.scilab.forge.jlatexmath

/**
 * JLatexMath 里 \ce 的转换器只在包内可见。放在同一个包里直接调用，
 * 应用就能先把 \ce 展开成普通 TeX，再按加号、箭头拆成能换行的小段。
 */
object ChemFormulaBridge {
    fun toTex(formula: String): String = ChemFormulaConverter.convert(formula)
}
