package com.lumina.master

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M3 起点的回归基线（用户拍板"完成功能前先跑基线"的一部分）：
 * **源码策略锁**——把"升级 DB 版本必须挂正向迁移、绝不静默清库"写成可执行断言。
 * 背景（2026-11-09 实锤）：bump 6→7 时忘了 addMigrations，fallbackToDestructiveMigration
 * 把用户全库清空（LLM 配置/卡库/智能结果全没）。此类事故 JVM Room 没法直接测，
 * 就用源码扫描做护栏（读的是本文件自身，误配即红）。
 */
class RegressionGuardTest {

    /** 单测工作目录兼容：可能在 app/ 下跑也可能在仓库根跑。 */
    private fun source(rel: String): String {
        val candidates = listOf(File("src/main/java/$rel"), File("app/src/main/java/$rel"))
        val f = candidates.firstOrNull { it.exists() }
            ?: candidates.mapNotNull { c -> c.takeIf { it.parentFile?.name == "java" } }.firstOrNull()
            ?: error("source not found: $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun `db version bumps ship migrations - destructive fallback stays dead`() {
        val raw = source("com/lumina/master/InventoryDb.kt")
        val version = Regex("""version\s*=\s*(\d+)""").find(raw)?.groupValues?.get(1)?.toInt()
            ?: error("找不到 AppDatabase version")
        // 代码语义扫描前剥掉行注释，防"教训注释"本身误伤断言
        val src = raw.lineSequence()
            .map { it.substringBefore("//") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val policyFloor = 7   // v7 起"每 bump 必挂迁移"成为纪律（v1..v6 属破坏性迁移前史）
        for (i in (policyFloor + 1)..version) {
            assertTrue(
                "v$policyFloor→$i 的 Migration 缺失：请在 InventoryDb.kt 增加 MIGRATION_${policyFloor}_$i 并挂进 addMigrations",
                src.contains("MIGRATION_${policyFloor}_$i")
                    || src.contains("MIGRATION_${policyFloor - 1}_$i"),
            )
        }
        assertFalse(
            "builder 必须挂 addMigrations",
            !src.contains("addMigrations(MIGRATION_"),
        )
        assertFalse(
            "fallbackToDestructive 破坏性降级必须移除（它清过全库），代码里不得再出现",
            src.contains("fallbackToDestructiveMigration"),
        )
    }
}
