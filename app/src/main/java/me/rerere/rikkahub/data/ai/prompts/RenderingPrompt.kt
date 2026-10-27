package me.rerere.rikkahub.data.ai.prompts

/**
 * 告诉模型本应用能原生渲染哪些公式写法。放在系统提示词末尾且内容固定，不影响提示词缓存。
 */
val FORMULA_RENDERING_PROMPT = """
    ## 公式与化学的写法（本应用原生渲染，直接写在回答里即可，不需要调用工具）
    - 数学：LaTeX，行内 $...$，单独成行 $$...$$。
    - 化学式、离子、化学方程式一律写 \ce{...}，放在 $ 里，和讲解文字混排；不要用 Unicode 下标、<sub> 或纯文字拼凑：
      $\ce{H2SO4}$ $\ce{SO4^2-}$ $\ce{Fe^3+}$ $\ce{CuSO4.5H2O}$ $\ce{2H2 + O2 ->[点燃] 2H2O}$
      $\ce{N2 + 3H2 <=>[高温、高压][催化剂] 2NH3}$ $\ce{CaCO3 + 2HCl = CaCl2 + H2O + CO2 ^}$（气体 ^、沉淀 v，前面留空格）
    - 结构式写 \smiles{SMILES}，同样放在 $ 里，可以和文字、\ce、箭头写进同一个公式：
      键线式 $\smiles{CC(=O)OCC}$；结构简式 $\smiles[condensed]{CC(C)CO}$（画成主链横排、支链竖直的课本样式）；
      结构式（画出全部 C–H 键）$\smiles[full]{CCO}$；第二个花括号是名称 $\smiles{c1ccccc1}{苯}$。
      反应：$$\smiles[condensed]{C=C} + \ce{Br2 ->} \smiles[condensed]{BrCCBr}$$
      SMILES 要写对：苯环 c1ccccc1，双键 =，三键 #，带电原子 [NH4+]。不带支链的结构简式也可以直接写 $\ce{CH3CH2OH}$。
    - 这些写法在表格单元格里同样可用。不要用字符画、代码块或 ASCII 拼结构式，也不要因为难画就省略或改用简写。
""".trimIndent()
