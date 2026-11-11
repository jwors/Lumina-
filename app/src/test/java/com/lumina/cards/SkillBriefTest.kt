package com.lumina.cards

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillBriefTest {

    private val doc = """
        ---
        name: demo-skill
        ---
        # Demo

        ## Decision Priority
        this must not be picked

        ## Photo-Specific Prompt Compiler
        resolve canvas and geometry

        ## Minimal Abstraction Engine
        this must not be picked either

        ## Prompt Shape
        four paragraphs

        ## Hard Avoids
        avoid neon and 3d

        ## Output Format
        not picked
    """.trimIndent()

    @Test
    fun extractsOnlyCompilerSections() {
        val brief = SkillBrief.extract(doc)
        assertTrue(brief.contains("Photo-Specific Prompt Compiler"))
        assertTrue(brief.contains("Prompt Shape"))
        assertTrue(brief.contains("Hard Avoids"))
        assertFalse(brief.contains("Decision Priority"))
        assertFalse(brief.contains("Minimal Abstraction"))
        assertFalse(brief.contains("Output Format"))
    }

    @Test
    fun fallsBackToHeadWhenNoSectionMatches() {
        val brief = SkillBrief.extract("# plain\nno headings here, just text")
        assertTrue(brief.startsWith("# plain"))
    }

    @Test
    fun capsOverlongBrief() {
        val long = "## Hard Avoids\n" + "x".repeat(10_000)
        assertTrue(SkillBrief.extract(long).length <= SkillBrief.MAX_CHARS)
    }

    @Test
    fun detectsOwnAnchor() {
        assertTrue(SkillBrief.hasOwnAnchor("## Hard Avoids\navoid neon"))
        assertTrue(SkillBrief.hasOwnAnchor("keep a torn paper edge"))
        assertFalse(SkillBrief.hasOwnAnchor("a plain zine card without rules"))
    }
}
