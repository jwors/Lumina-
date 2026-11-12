package com.lumina.probe

/**
 * 生成式编译的输入拼装（纯函数，JVM 可测）：
 * 基础规则 + 前序标注/文案/滤镜 + 本次执行序号（供 SKILL 的“第 N 幕”使用）。
 */
object GenerativePrompt {

    /**
     * 统一画布口径：生图端点固定 1056x1760（3:5），
     * 模型偶尔会写 3:4/4:3，这里归一，避免 prompt 与实际出图比例打架。
     */
    fun normalizeAspect(prompt: String): String =
        prompt.replace("3:4", "3:5").replace("4:3", "3:5")

    /**
     * 清掉模型偶尔回吐的章节标签/Markdown 加粗：
     * 这些字会 enter 生图 prompt 并被模型当正文画到纸上（实测出现过标题字压在图顶）。
     */
    fun sanitize(prompt: String): String {
        val labels = listOf(
            "canvas and attention geometry", "scene fidelity", "illustration field",
            "chromatic structure", "reproduction", "paper and constraints",
            "画布与注意几何", "场景忠实", "插画场", "色彩结构", "复印质感",
        )
        return prompt.replace("**", "")
            .lines()
            .filterNot { line ->
                val t = line.trim().trim(':', '：', '.', '。', '-', '—', ' ')
                t.isEmpty() ||
                    labels.any { t.equals(it, ignoreCase = true) } ||
                    Regex("^\\d\\s*/\\s*4\\b").containsMatchIn(t)
            }
            .joinToString("\n")
            .trim()
    }

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
