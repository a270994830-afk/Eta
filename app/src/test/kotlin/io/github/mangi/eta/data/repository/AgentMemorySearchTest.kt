package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆检索的纯逻辑测试（不依赖 Robolectric / Android）。
 *
 * 覆盖：小节切分、标题命中加权、多词/短语加成、行序与上下文、以及无命中的处理。
 */
class AgentMemorySearchTest {

    private val doc = listOf(
        "# 核心记忆",
        "- 用户喜欢简洁回答",
        "## 闲聊",
        "- 昨天又聊到部署这件事",
        "## 部署",
        "- 部署脚本在 eta-src",
    )

    @Test
    fun sectionsSplitOnHeadingsAndKeepHeadingWithFollowingLines() {
        val sections = AgentMemorySearch.sections(doc)
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 5), sections.map { it.start to it.end })
    }

    @Test
    fun headingHitSectionIsRankedFirstEvenWhenItComesLaterInFile() {
        val order = AgentMemorySearch.orderedIndices(doc, "部署")
        // 高分小节（标题命中，在文件后面）先渲染；标题行自身也含检索词，其上下文把上一行带进来
        assertEquals(listOf(3, 4, 5, 2), order)
        val matched = AgentMemorySearch.matchedIndices(doc, "部署")
        assertEquals(listOf(3, 4, 5), matched)
    }

    @Test
    fun unrelatedSectionIsNotIncluded() {
        val order = AgentMemorySearch.orderedIndices(doc, "部署")
        assertFalse(order.contains(0))
        assertFalse(order.contains(1))
    }

    @Test
    fun allTermsAcrossLinesBeatSplitTermsDueToPhraseBonus() {
        val lines = listOf(
            "# 记忆",
            "## B",
            "- 部署",
            "- 脚本",
            "## A",
            "- 部署 脚本",
        )
        val order = AgentMemorySearch.orderedIndices(lines, "部署 脚本")
        // A（含完整短语，在文件后面）先渲染；B 里两个词分开，分数低
        assertEquals(listOf(4, 5, 1, 2, 3), order)
        assertEquals(3, AgentMemorySearch.matchedIndices(lines, "部署 脚本").size)
    }

    @Test
    fun termsDropSingleAsciiButKeepSingleCjkChar() {
        assertTrue(AgentMemorySearch.terms("x").isEmpty())
        assertEquals(listOf("记"), AgentMemorySearch.terms("记"))
        assertEquals(listOf("eta", "agent"), AgentMemorySearch.terms("Eta, agent"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(AgentMemorySearch.matchedIndices(doc, "不存在的词").isEmpty())
        assertTrue(AgentMemorySearch.orderedIndices(doc, "不存在的词").isEmpty())
    }

    @Test
    fun cosineHandlesMismatchAndZeroVectors() {
        assertEquals(0.0, AgentMemorySearch.cosine(null, floatArrayOf(1f)), 1e-9)
        assertEquals(0.0, AgentMemorySearch.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f)), 1e-9)
        assertEquals(0.0, AgentMemorySearch.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 1e-9)
        assertEquals(1.0, AgentMemorySearch.cosine(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f)), 1e-9)
        assertEquals(-1.0, AgentMemorySearch.cosine(floatArrayOf(1f, 0f), floatArrayOf(-3f, 0f)), 1e-9)
    }

    @Test
    fun semanticSectionsFilterByThresholdAndKeepTopOrder() {
        val sections = AgentMemorySearch.sections(doc)
        val candidates = listOf(
            sections[0] to floatArrayOf(1f, 0f),
            sections[1] to floatArrayOf(0.95f, 0.31f),
            sections[2] to floatArrayOf(0f, 1f),
        )
        val hits = AgentMemorySearch.semanticSections(candidates, floatArrayOf(1f, 0f), limit = 2, minSimilarity = 0.9)
        assertEquals(listOf(sections[0], sections[1]), hits.map { it.section })
        assertTrue(hits[0].similarity > hits[1].similarity)
    }

    @Test
    fun hybridIndicesAppendSemanticOnlySectionAsPreview() {
        val lines = listOf(
            "# 爱好",
            "- 用户喜欢在周末做饭",
            "",
            "# 出行",
            "- 常用地铁通勤",
        )
        val sections = AgentMemorySearch.sections(lines)
        // 查询与任何行都不字面匹配，但语义命中第一个小节
        val semantic = listOf(AgentMemorySearch.SemanticHit(sections[0], 0.7))
        val order = AgentMemorySearch.hybridIndices(lines, "烹饪", semantic)
        assertTrue(AgentMemorySearch.matchedIndices(lines, "烹饪").isEmpty())
        assertTrue(order.containsAll(listOf(0, 1)))
        assertTrue(order.none { it >= 3 })
    }

    @Test
    fun hybridIndicesSkipSemanticSectionAlreadyCoveredByKeywordHits() {
        val lines = listOf(
            "# 部署",
            "- 部署脚本在 eta-src",
            "# 无关",
            "- 其他内容",
        )
        val sections = AgentMemorySearch.sections(lines)
        val keywordOnly = AgentMemorySearch.hybridIndices(lines, "部署")
        val withSemantic = AgentMemorySearch.hybridIndices(
            lines,
            "部署",
            listOf(AgentMemorySearch.SemanticHit(sections[0], 0.9)),
        )
        // 语义命中的小节已被关键词覆盖时，不应额外追加任何行
        assertEquals(keywordOnly, withSemantic)
        assertTrue(withSemantic.containsAll(listOf(0, 1)))
    }
}
