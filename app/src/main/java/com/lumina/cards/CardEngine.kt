package com.lumina.cards

/**
 * Skill Card v1（规格 §3.4）：可导入导出的 JSON 处理配方。
 * 步骤类型四类（v1）：
 *  - ANNOTATE     : LLM 看图标注（描述/标签）
 *  - CAPTION      : 社媒文案生成
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

    /** 内置示例卡（规格 §3.4；2026-11-09 按用户点名追加三张风格卡，生成式步均标注 M4 占位。） */
    fun builtIns(): List<SkillCard> = listOf(
        parse(
            """{
                "name": "徒步日记 · 文案",
                "version": 1,
                "steps": [
                    {"type": "annotate", "prompt": "用中文为这张徒步照片写下 2-3 句中文现场观察：光线、地形、装备状态。"},
                    {"type": "caption", "prompt": "基于观察写成一条instagram/小红书体文案：一句话钩子 + 一句场景 + 结尾话题标签。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "锐利风光 · 标注",
                "version": 1,
                "steps": [
                    {"type": "annotate", "prompt": "按【主体/构图/光比/建议镜头】四项中文短行标注。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "极简海报 · 前夜",
                "version": 1,
                "steps": [
                    {"type": "filter_suggest", "prompt": "在候选滤镜清单里给这张照片一个建议并说明理由（一句话）。"},
                    {"type": "generative", "prompt": "极简 zine 海报风：粗颗粒、低饱和、留白构图（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "gc-minimal-zine-poster",
                "version": 3,
                "steps": [
                    {"type": "annotate", "prompt": "用中文为这张照片写 2-3 句现场观察：光线与雾气层次、地形、装备状态；最后给一个主标题选题方向。"},
                    {"type": "caption", "prompt": "zine 海报文案：一句主标题钩子（≤14字，排版用大字）+ 一行小注（地名+日期，≤18字）。"},
                    {"type": "filter_suggest", "prompt": "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"},
                    {"type": "generative", "prompt": "极简 zine 海报 v0.3：粗颗粒、双压色（去中间调）、大字压角、右下刻意留白；排版 = 主标题占上 2/3，小注贴底；日期角标右下（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "pixel-style-poster-skill",
                "version": 1,
                "steps": [
                    {"type": "annotate", "prompt": "按【主体/构图/色板倾向（适合 16-bit 色阶化的区域）】三段中文短行标注。"},
                    {"type": "caption", "prompt": "像素游戏开场白：一句 12-20 字的 SFC 游戏 loading 提示风格文案 + 两个话题标签。"},
                    {"type": "filter_suggest", "prompt": "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"},
                    {"type": "generative", "prompt": "像素风海报：16-bit 色板色阶化、单色块面 doodle、轻微 RGB 分离高光、扫描线点缀（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
        parse(
            """{
                "name": "scenes-gathered-zine",
                "version": 3,
                "steps": [
                    {"type": "annotate", "prompt": "场景聚束标注：指出这张照片属于当日行程的哪一段（出发/路线/登顶/下撤/营地），并用中文 2 句点出该场景的山野或地表特征。"},
                    {"type": "caption", "prompt": "场景聚束图注：场景名（≤6字）+ 一句衔接语（用于多章 zine 串联，≤16字）。"},
                    {"type": "filter_suggest", "prompt": "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"},
                    {"type": "generative", "prompt": "场景聚束 zine 分镜：网点/扎染纹理、场景名标注 + 拼贴序号（第 N 幕）；成片为多张拼贴的 zine 页（M4 执行占位）。"}
                ]
            }""",
        ) { CardEngine.newId() },
    )

    fun newId(): String = java.util.UUID.randomUUID().toString()
}
