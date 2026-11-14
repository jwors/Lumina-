package com.lumina.master

import com.lumina.work.WorkFiles

/**
 * 图库删除的纯决策（M3-06）：从"待删行 / 残留行"算出
 * ① 哪些 Master 名的名称键控数据（智能结果 / 待处理队列）可以清；
 * ② 哪些 Master 的 Work 成片（`_g`/`_w` 按 base 共享）可以删。
 *
 * 保护语义（用户拍板"级联清理"但不得误伤）：
 * - 还有同名 Master 残留 → 不清名称键控数据（Room 行无法区分同名不同日期）
 * - 还有同 base Master 残留 → 不删 Work 成片（文件名按 base 共享，NEF 同 base 也会用到）
 *
 * 纯 JVM 可测；文件/Room 动作在 ProbeController 由计划驱动。
 */
object GalleryDeletePlan {

    data class Plan(
        /** 无同名残留的 Master 名：可清 smart_results / processing_queue */
        val purgeNames: Set<String>,
        /** 无同 base 残留的 Master 名：可删其 Work 成片 */
        val workNames: List<String>,
    )

    fun plan(
        targets: List<MasterInventoryEntity>,
        remaining: List<MasterInventoryEntity>,
    ): Plan {
        val remainingNames = remaining.map { it.origFileName }.toSet()
        val remainingBases = remaining.map { WorkFiles.baseKey(it.origFileName) }.toSet()
        return Plan(
            purgeNames = targets
                .map { it.origFileName }
                .filter { it !in remainingNames }
                .toSet(),
            workNames = targets
                .filter { WorkFiles.baseKey(it.origFileName) !in remainingBases }
                .map { it.origFileName },
        )
    }
}
