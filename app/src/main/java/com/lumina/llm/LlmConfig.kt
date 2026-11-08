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
    data class Template(val name: String, val baseUrl: String, val model: String, val note: String)

    val presets = listOf(
        Template("通用 OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "按 token 计费，视觉可用"),
        Template("DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", "vision-flash 已并入；支持 JPEG/PNG/GIF/WebP（按内容判定）"),
        Template("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash", "免费额度"),
        Template("Kimi 月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k-vision-preview", "视觉预览模型"),
    )
}
