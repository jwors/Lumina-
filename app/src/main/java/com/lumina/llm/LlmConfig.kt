package com.lumina.llm

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * LLM 配置（M2 工单 01）：OpenAI 兼容三元组 + 模板来源 + 云处理总开关（规格 §5）。
 * 单例行（id = 1）：App 只有一份"当前生效配置"；模板选择 = 填表快捷方式。
 */
@Entity(tableName = "llm_configs")
data class LlmConfigEntity(
    @PrimaryKey val id: Int = 1,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 用户最后选择的模板名（仅 UI 记忆） */
    val templateName: String,
    /** 云处理总开关（false = 所有 LLM 请求挂起） */
    val cloudEnabled: Boolean = false,
)

@Dao
interface LlmConfigDao {
    @Query("SELECT * FROM llm_configs WHERE id = 1 LIMIT 1")
    fun active(): LlmConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: LlmConfigEntity)
}

/**
 * 平台模板（规格 §3.3）：选模板 = 一键填表；字段仍可手改。
 * 注意：模型名是arket预置，用户 base_url 变了就要改（模板只是起点）。
 */
object LlmTemplates {
    /** 生图端点定义（M4-01）：模板可携带；平台模板选中时一并保存进 ImageGenConfig。 */
    data class ImageGenDef(val baseUrl: String, val model: String, val size: String)

    data class Template(
        val name: String,
        val baseUrl: String,
        val model: String,
        val note: String,
        /** 有生图能力的模板才带（M4-01：智谱 GLM 同平台同 key 两用）。 */
        val image: ImageGenDef? = null,
    )

    val presets = listOf(
        Template("通用 OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "按 token 计费，视觉可用"),
        Template("DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", "vision-flash 已并入；支持 JPEG/PNG/GIF/WebP（按内容判定）"),
        Template("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash", "免费额度；生图同平台（glm-image 3:5）",
            image = ImageGenDef(
                baseUrl = "https://open.bigmodel.cn/api/paas/v4/images/generations",
                model = "glm-image",
                size = "1056x1760",
            ),
        ),
        Template("Kimi 月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k-vision-preview", "视觉预览模型"),
        Template("OpenCode Go（你的套餐）", "https://opencode.ai/inference/go/openai/v1", "glm-5.3-flash",
            "文本+多模态编译用；生图端点仍走智谱 glm-image"),
    )
}
