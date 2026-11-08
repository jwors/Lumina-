package com.lumina.pass

import com.lumina.cards.SkillCard
import com.lumina.cards.StepKind
import com.lumina.llm.LlmClient
import com.lumina.llm.LlmConfigEntity
import com.lumina.llm.LlmOutcome

/**
 * Processing Pass 执行器（M2 工单 04）：
 * 一张 SkillCard × 一批 Master = 串行步骤调用（每步骤一次 LLM 请求）。
 * 依赖全部由构造注入（client/配置/落库/图片源/时间）——JVM 可测（缝隙方法论第四次复刻）。
 *
 * 语义（Q6 拍板）：
 * - 串行执行；单请求失败自动重试 1 次（默认 2s 退避；测试用 0）
 * - GENERATIVE 步骤 = M4 占位（写占位结果，不真正调用 LLM）
 * - 无 JPEG 源的对象记失败（" 待 JPEG"）
 */
class PassExecutor(
    private val clientProvider: () -> LlmClient?,
    private val cfgProvider: () -> LlmConfigEntity?,
    private val sink: PassSink,
    private val retryDelayMs: Long = 2000,
    private val now: () -> Long = System::currentTimeMillis,
    /** 取消缝：返回 true 时跳过剩余条目（在途请求照常跑完一格）。默认永不取消。 */
    private val abortCheck: () -> Boolean = { false },
) {

    /** 内置中文默认 prompt（卡内未带 prompt 的步骤落在这些默认上） */
    object Prompts {
        val annotate = "用中文为这张徒步照片写 2-3 句现场观察：光线、地形、装备状态。"
        val caption = "写一条社交媒体文案：一句钩子 + 一句场景描述 + 两个话题标签。"
        val filter = "从候选滤镜清单【锐利风光 / 胶片暖调 / 黑白胶片 / 极简 zine / 波塔柔彩 / 浓彩风光 / 经典褪色 / 银盐硬黑 / 柯达金黄 / 青橙电影 / 拍立得 / 秋雨暮色 / 冷调山雾】中给这张照片一个建议并一句话说明理由。"
    }

    /** 执行一张卡 × 一批 Master；callback 汇报。 */
    fun run(items: List<String>, card: SkillCard, callback: PassCallback) {
        val cfg = cfgProvider()
        if (cfg == null) {
            callback.onStart(items.size)
            callback.onAllDone(0, items.size)
            return
        }
        callback.onStart(items.size)
        var ok = 0
        var fail = 0
        for (name in items) {
            if (abortCheck()) break
            var anySuccess = false
            var lastNote = ""
            for (step in card.steps) {
                if (step.kind == StepKind.GENERATIVE) {
                    // v1 占位：不调 LLM，写占位结果（M4 接管）
                    sink.saveResult(name, card.cardId, "generative",
                        "（生成式步骤：待 M4 执行）${step.prompt ?: ""}", cfg.model, now())
                    continue
                }
                val jpeg = sink.jpegFor(name)
                if (jpeg == null) {
                    lastNote = "无 JPEG 源（仅 NEF，跳过视觉步骤）"
                    continue
                }
                val prompt = step.prompt ?: when (step.kind) {
                    StepKind.ANNOTATE -> Prompts.annotate
                    StepKind.CAPTION -> Prompts.caption
                    StepKind.FILTER_SUGGEST -> Prompts.filter
                    StepKind.GENERATIVE -> ""
                }
                val outcome = callWithRetry(cfg, prompt, jpeg)
                when (outcome) {
                    is com.lumina.llm.LlmOutcome.Text -> {
                        sink.saveResult(name, card.cardId, step.kind.name.lowercase(),
                            outcome.text, cfg.model, now())
                        anySuccess = true
                    }
                    else -> {
                        lastNote = when (outcome) {
                            is com.lumina.llm.LlmOutcome.RateLimited -> "限流（重试后仍 ${outcome.retryAfterSec ?: "?"}s）"
                            is com.lumina.llm.LlmOutcome.Http -> "HTTP ${outcome.code}"
                            is com.lumina.llm.LlmOutcome.NetworkTimeout -> "网络不可达"
                            else -> "未知结局"
                        }
                    }
                }
            }
            if (card.steps.none { it.kind != StepKind.GENERATIVE } ) {
                // 全占位卡也算成功完成（无 LLM 调用）
                anySuccess = true
            }
            if (anySuccess) {
                sink.removeQueued(name)
                ok++
                callback.onItemDone(name, true, lastNote)
            } else {
                fail++
                callback.onItemDone(name, false, lastNote)
            }
        }
        callback.onAllDone(ok, fail)
    }

    /** 单请求 + 限流/断网自动重试 1 次。 */
    private fun callWithRetry(
        cfg: LlmConfigEntity,
        prompt: String,
        jpeg: ByteArray,
    ): com.lumina.llm.LlmOutcome {
        val client = clientProvider() ?: return com.lumina.llm.LlmOutcome.NetworkTimeout("no client")
        var attempt = 0
        var last: com.lumina.llm.LlmOutcome = com.lumina.llm.LlmOutcome.NetworkTimeout("no attempt")
        while (attempt < 2) {
            val outcome = client.complete(cfg, prompt, jpeg)
            if (outcome is com.lumina.llm.LlmOutcome.Text) {
                last = outcome
                sink.saveLedger(
                    ledgerName(), cfg.model, prompt.length, jpeg != null,
                    "OK", outcome.promptTokens, outcome.completionTokens,
                )
                return last
            }
            last = outcome
            when (outcome) {
                is com.lumina.llm.LlmOutcome.RateLimited -> {
                    sink.saveLedger(ledgerName(), cfg.model, prompt.length, jpeg != null,
                        "RateLimited", null, null)
                }
                is com.lumina.llm.LlmOutcome.Http -> {
                    // 4xx 无意义重试（限流除外）；直接出
                    sink.saveLedger(ledgerName(), cfg.model, prompt.length, jpeg != null,
                        "Http_${outcome.code}", null, null)
                    if (outcome.code in 400..499) return outcome
                }
                is com.lumina.llm.LlmOutcome.NetworkTimeout -> {
                    sink.saveLedger(ledgerName(), cfg.model, prompt.length, jpeg != null,
                        "NetworkTimeout", null, null)
                }
                else -> {}
            }
            attempt++
            if (attempt < 2 && retryDelayMs > 0) Thread.sleep(retryDelayMs)
        }
        return last
    }

    private fun ledgerName(): String = "pass"
}
