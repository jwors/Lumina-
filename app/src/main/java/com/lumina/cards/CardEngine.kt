package com.lumina.cards

/**
 * Skill Card v1（规格 §3.4）：可导入导出的 JSON 处理配方。
 * 步骤类型四类（v1；2026-11-15 起 ANNOTATE/CAPTION 停用，仅保留解析兼容，执行器跳过）：
 *  - ANNOTATE     : LLM 看图标注（描述/标签）——已停用
 *  - CAPTION      : 社媒文案生成——已停用
 *  - FILTER_SUGGEST : 让模型在候选滤镜里给建议
 *  - GENERATIVE   : 生成式风格（M4 执行；v1 队列中标记"待生成"占位）
 * schema：
 * { "name": "徒步日记", "version": 1,
 *   "steps": [ {"type":"annotate","prompt":"…" } ] }
 */
enum class StepKind {
    ANNOTATE, CAPTION, FILTER_SUGGEST, GENERATIVE;

    companion object {
        fun of(raw: String): StepKind? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

data class CardStep(val kind: StepKind, val prompt: String?)

data class SkillCard(
    val cardId: String,
    val name: String,
    val version: Int,
    val steps: List<CardStep>,
)

/** 解析失败（人话 reason + 病灶 position 信息保留给日志）。 */
class SkillCardException(val reason: String) : RuntimeException(reason)

object CardEngine {

    /**
     * 解析 JSON → SkillCard。失败抛 SkillCardException（reason 人话）。
     * id 缺失时生成 uuid（导入文件不复用 id，防撞库）。
     */
    fun parse(json: String, generateId: () -> String): SkillCard {
        val root = try {
            org.json.JSONObject(json)
        } catch (e: org.json.JSONException) {
            throw SkillCardException("不是合法 JSON：${e.message}")
        }
        val name = root.optString("name", "").trim()
        if (name.isEmpty()) throw SkillCardException("缺少 name 字段")
        val version = root.optInt("version", -1)
        if (version < 1) throw SkillCardException("version 必须 ≥ 1")
        val stepsArr = root.optJSONArray("steps") ?: throw SkillCardException("缺少 steps 数组")
        if (stepsArr.length() == 0) throw SkillCardException("steps 为空：一张卡至少一个步骤")

        val steps = mutableListOf<CardStep>()
        for (i in 0 until stepsArr.length()) {
            val stepObj = stepsArr.optJSONObject(i)
                ?: throw SkillCardException("steps[$i] 不是对象")
            val typeRaw = stepObj.optString("type", "")
            val kind = StepKind.of(typeRaw)
                ?: throw SkillCardException(
                    "steps[$i] 未知步骤类型 \"$typeRaw\"（合法：${StepKind.entries.joinToString("/")}}）",
                )
            val prompt = stepObj.optString("prompt", "").takeIf { it.isNotBlank() }
            if ((kind == StepKind.ANNOTATE || kind == StepKind.CAPTION) && prompt == null) {
                throw SkillCardException("steps[$i]（$kind）缺 prompt 字段")
            }
            steps += CardStep(kind, prompt)
        }
        return SkillCard(cardId = generateId(), name = name, version = version, steps = steps.toList())
    }

    /** 导出：卡 → 规整 JSON（幂等出书，不带 cardId——id 归属系统，配方属于文件）。 */
    fun toJson(card: SkillCard): String {
        val stepsArr = org.json.JSONArray()
        card.steps.forEach { s ->
            val o = org.json.JSONObject().put("type", s.kind.name.lowercase())
            s.prompt?.let { o.put("prompt", it) }
            stepsArr.put(o)
        }
        return org.json.JSONObject()
            .put("name", card.name)
            .put("version", card.version)
            .put("steps", stepsArr)
            .toString(2)
    }

    /**
     * 内置示例卡。
     * 2026-11-15 用户拍板：标注/文案步骤全产品停用，只保留滤镜建议 + 生成式；
     * 纯标注/文案的两张内置卡（徒步日记·文案 / 锐利风光·标注）随之下架。
     * StepKind 仍保留 ANNOTATE/CAPTION 以便解析旧导入卡（执行时跳过）。
     */
    fun builtIns(): List<SkillCard> = listOf(
        parse(
            """{
                "name": "极简海报 · 前夜",
                "version": 2,
                "steps": [
                    {"type": "filter_suggest", "prompt": "在候选滤镜清单里给这张照片一个建议并说明理由（一句话）。"},
                    {"type": "generative", "prompt": "极简 zine 海报风：粗颗粒、低饱和、留白构图（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "gc-minimal-zine-poster",
                "version": 4,
                "steps": [
                    {"type": "filter_suggest", "prompt": "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"},
                    {"type": "generative", "prompt": "极简 zine 海报 v0.4：粗颗粒、双压色（去中间调）、大字压角、右下刻意留白；排版 = 主标题占上 2/3，小注贴底；日期角标右下（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "pixel-style-poster-skill",
                "version": 2,
                "steps": [
                    {"type": "filter_suggest", "prompt": "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"},
                    {"type": "generative", "prompt": "像素风海报：16-bit 色板色阶化、单色块面 doodle、轻微 RGB 分离高光、扫描线点缀（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "scenes-gathered-zine",
                "version": 4,
                "steps": [
                    {"type": "filter_suggest", "prompt": "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"},
                    {"type": "generative", "prompt": "场景聚束 zine 分镜：网点/扎染纹理、场景名标注 + 拼贴序号（第 N 幕）；成片为多张拼贴的 zine 页（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
    )

    fun newId(): String = java.util.UUID.randomUUID().toString()

    /** 卡库与内置定义的同步计划（M2-07 + 2026-11-15 步骤停用）。 */
    data class CardSyncPlan(
        /** 已下架的内置卡行（只删 imported=false，名字已不在 builtIns）：按 cardId 退场 */
        val retireCardIds: List<String>,
        /** 需要新建的内置卡（已排除墓碑名与同名卡） */
        val seed: List<SkillCard>,
        /** 定义/版本变了的在册内置卡：保留原 cardId 更新步骤，避免队列引用断掉 */
        val refresh: List<Refresh>,
    )

    data class Refresh(val cardId: String, val card: SkillCard)

    /**
     * 同步计划（纯函数）：内置卡定义是唯一真相，用户导入卡不碰。
     * - 下架：库里 `imported=false` 且名字已不在 [builtIns] → 删行
     * - 播种：库里完全没有这个名字（含导入卡占名）→ 不补；墓碑名 → 不补
     * - 刷新：同名内置卡行步骤/版本变了 → 保留 cardId 覆盖定义
     */
    fun syncPlan(rows: List<CardEntity>, deletedBuiltInNames: Set<String>): CardSyncPlan {
        val builtIns = builtIns()
        val activeNames = builtIns.map { it.name }.toSet()
        val allNames = rows.map { it.name }.toSet()
        val builtInRowByName = rows.filter { !it.imported }.associateBy { it.name }
        val retire = rows
            .filter { !it.imported && it.name !in activeNames }
            .map { it.cardId }
        val seed = builtIns.filter { it.name !in allNames && it.name !in deletedBuiltInNames }
        val refresh = builtIns.mapNotNull { card ->
            val row = builtInRowByName[card.name] ?: return@mapNotNull null
            if (row.stepsJson == toJson(card) && row.version == card.version) {
                null
            } else {
                Refresh(row.cardId, card)
            }
        }
        return CardSyncPlan(retire, seed, refresh)
    }
}
