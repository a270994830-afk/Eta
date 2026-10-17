package io.github.mangi.eta.data.repository

import java.util.Locale

/**
 * MEMORY.md 的轻量检索：把「整文件子串命中」升级为「按小节打分排序」。
 *
 * 纯函数、不碰 Android API，方便用普通 JUnit 覆盖（本仓库 Robolectric 在容器内不可用）。
 *
 * 打分规则（分数越高越先返回）：
 * - 命中词越多分越高；词越长权重越大（`1 + (len-1)*0.15`，上限 6）；
 * - 标题行命中额外 +3（标题是这一节的主题）；
 * - 全部检索词都命中 ×1.5；query 原样出现在某一行 ×2。
 */
internal object AgentMemorySearch {
    const val DEFAULT_CONTEXT_LINES = 1
    const val DEFAULT_SEMANTIC_LIMIT = 3
    const val DEFAULT_SEMANTIC_MIN_SIMILARITY = 0.45
    const val DEFAULT_SECTION_PREVIEW_LINES = 8
    private const val MAX_TERMS = 8
    private val SEPARATORS = charArrayOf(
        ' ', '\t', '\n', ',', '，', '、', '。', '；', ';', ':', '：',
        '(', ')', '（', '）', '[', ']', '【', '】', '/', '\\', '|', '"', '\'', '=', '-', '_', '+',
    )

    data class Section(val start: Int, val end: Int)

    /** 语义召回的小节及其相似度。 */
    data class SemanticHit(val section: Section, val similarity: Double)

    /** 余弦相似度；维度不一致或零向量返回 0（调用方据此忽略）。 */
    fun cosine(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    /** 按相似度取前 limit 个小节（低于阈值的丢弃）。 */
    fun semanticSections(
        candidates: List<Pair<Section, FloatArray>>,
        queryVector: FloatArray,
        limit: Int = DEFAULT_SEMANTIC_LIMIT,
        minSimilarity: Double = DEFAULT_SEMANTIC_MIN_SIMILARITY,
    ): List<SemanticHit> =
        candidates
            .map { (section, vector) -> SemanticHit(section, cosine(queryVector, vector)) }
            .filter { it.similarity >= minSimilarity }
            .sortedByDescending { it.similarity }
            .take(limit.coerceAtLeast(0))

    /** 小节预览行：标题起、跳过空行与注释，取最多 maxLines 行。 */
    fun sectionPreview(section: Section, lines: List<String>, maxLines: Int): List<Int> {
        if (section.start > section.end || maxLines <= 0) return emptyList()
        val result = ArrayList<Int>()
        for (index in section.start..section.end) {
            if (result.size >= maxLines) break
            val line = lines[index].trim()
            if (line.isEmpty() || line.startsWith("<!--")) continue
            result += index
        }
        return result
    }

    /**
     * 混合排序：关键词命中行 → 关键词命中小节（按得分）→ 语义召回小节（相似度高者先，只补没被关键词覆盖的）。
     * 纯函数，传入的语义小节由调用方（向量索引）提供，便于单测。
     */
    fun hybridIndices(
        lines: List<String>,
        query: String,
        semantic: List<SemanticHit> = emptyList(),
        contextLines: Int = DEFAULT_CONTEXT_LINES,
        previewLines: Int = DEFAULT_SECTION_PREVIEW_LINES,
    ): List<Int> {
        val sequence = LinkedHashSet<Int>(orderedIndices(lines, query, contextLines))
        for (hit in semantic) {
            val section = hit.section
            if (section.start > section.end) continue
            if ((section.start..section.end).any { it in sequence }) continue
            sectionPreview(section, lines, previewLines).forEach { sequence += it }
        }
        return sequence.toList()
    }

    /** 按空白与常见标点切词；丢弃单字符 ASCII（无检索价值），保留中文单字。 */
    fun terms(query: String): List<String> =
        query.lowercase(Locale.ROOT)
            .split(*SEPARATORS)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.length >= 2 || it.any { ch -> ch.code > 127 } }
            .distinct()
            .take(MAX_TERMS)
            .toList()

    fun matchedIndices(lines: List<String>, query: String): List<Int> {
        val terms = terms(query)
        if (terms.isEmpty() || lines.isEmpty()) return emptyList()
        return lines.indices.filter { index ->
            terms.any { term -> lines[index].contains(term, ignoreCase = true) }
        }
    }

    /** 按 Markdown 标题行切小节；标题行归入其后的小节。 */
    fun sections(lines: List<String>): List<Section> {
        if (lines.isEmpty()) return emptyList()
        val result = ArrayList<Section>()
        var start = 0
        for (index in lines.indices) {
            if (!lines[index].trimStart().startsWith("#")) continue
            if (index > start) result += Section(start, index - 1)
            start = index
        }
        if (start <= lines.lastIndex) result += Section(start, lines.lastIndex)
        return result
    }

    fun score(section: Section, lines: List<String>, terms: List<String>, rawQuery: String): Double {
        if (terms.isEmpty() || section.start > section.end) return 0.0
        var score = 0.0
        var hitTerms = 0
        var phraseHit = false
        for (term in terms) {
            var hits = 0
            var headingHit = 0
            for (index in section.start..section.end) {
                val line = lines[index]
                if (index == section.start && line.trimStart().startsWith("#") &&
                    line.contains(term, ignoreCase = true)
                ) {
                    headingHit = 1
                }
                var from = 0
                while (true) {
                    val at = line.indexOf(term, from, ignoreCase = true)
                    if (at < 0) break
                    hits++
                    from = at + term.length
                }
                if (!phraseHit && rawQuery.isNotBlank() && line.contains(rawQuery, ignoreCase = true)) {
                    phraseHit = true
                }
            }
            if (hits == 0 && headingHit == 0) continue
            hitTerms++
            val weight = 1.0 + (term.length - 1).coerceIn(0, 6) * 0.15
            score += hits * weight + headingHit * 3.0
        }
        if (hitTerms == 0) return 0.0
        if (hitTerms == terms.size) score *= 1.5
        if (phraseHit) score *= 2.0
        return score
    }

    /**
     * 待渲染行索引：高分小节优先，每个命中行带上 [contextLines] 行上下文，去重且保持小节内行序。
     * 调用方按返回顺序渲染即可（跨小节时会自然出现行号跳跃）。
     */
    fun orderedIndices(lines: List<String>, query: String, contextLines: Int = DEFAULT_CONTEXT_LINES): List<Int> {
        val terms = terms(query)
        val matched = matchedIndices(lines, query)
        if (terms.isEmpty() || matched.isEmpty()) return emptyList()
        val ordered = sections(lines)
            .map { section -> section to score(section, lines, terms, query) }
            .filter { (_, value) -> value > 0.0 }
            .sortedByDescending { (_, value) -> value }
        val sequence = LinkedHashSet<Int>()
        for ((section, _) in ordered) {
            for (index in matched) {
                if (index < section.start || index > section.end) continue
                for (candidate in (index - contextLines)..(index + contextLines)) {
                    if (candidate in lines.indices) sequence += candidate
                }
            }
        }
        return sequence.toList()
    }
}
