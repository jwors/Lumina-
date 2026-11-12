package com.lumina.probe

/**
 * 生成式编译的输入拼装（纯函数，JVM 可测）：
 * 基础规则 + 前序标注/文案/滤镜 + 本次执行序号（供 SKILL 的“第 N 幕”使用）。
 */
object GenerativePrompt {

    fun assemble(base: String, prior: String, index: Int, total: Int): String = buildString {
        append(base)
        if (prior.isNotBlank()) {
            append("\n\n【前序结果（仅语义参考，不要照抄）】\n")
            append(prior)
        }
        append("\n\n【序号】本张是本次执行的第 ")
        append(index + 1)
        append(" 张，共 ")
        append(total)
        append(" 张；若 SKILL 需要拼贴序号，用“第 ")
        append(index + 1)
        append(" 幕”。")
    }
}
