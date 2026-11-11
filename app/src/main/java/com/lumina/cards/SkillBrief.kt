package com.lumina.cards

/**
 * 从导入的 SKILL.md 里抽取「编译用规则」，避免把 38k 全文塞进一次请求。
 *
 * 设计取舍：不做数据库列——brief 是 stepsJson 的派生值，单一事实源仍留在卡里；
 * 每次编译现抽（纯字符串扫描，几毫秒），省掉一次 Room migration 和清库风险。
 */
object SkillBrief {

    /** 编译真正需要的三节（按 SKILL 的章节名匹配，大小写不敏感）。 */
    private val SECTIONS = listOf(
        "Photo-Specific Prompt Compiler",
        "Prompt Shape",
        "Hard Avoids",
    )

    /** 抽取三节正文；不足 200 字视为没命中，退回全文前 [MAX_CHARS]。 */
    fun extract(body: String, maxChars: Int = MAX_CHARS): String {
        val sb = StringBuilder()
        var capturing = false
        var matched = false
        for (line in body.lines()) {
            if (line.startsWith("## ")) {
                capturing = SECTIONS.any { line.contains(it, ignoreCase = true) }
                if (capturing) {
                    matched = true
                    sb.appendLine(line)
                }
                continue
            }
            if (capturing) sb.appendLine(line)
        }
        val picked = sb.toString().trim()
        val text = if (matched && picked.isNotEmpty()) picked else body.take(maxChars)
        return if (text.length <= maxChars) text else text.substring(0, maxChars)
    }

    /** SKILL 是否自带风格锚定（有就不追加内置 STYLE_ANCHOR，避免覆盖）。 */
    fun hasOwnAnchor(body: String): Boolean =
        body.contains("Hard Avoids", ignoreCase = true) ||
            body.contains("torn", ignoreCase = true) ||
            body.contains("撕", ignoreCase = false)

    const val MAX_CHARS = 4000
}
