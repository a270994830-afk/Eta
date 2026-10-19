package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/** 输入区待发送的引用消息；只随当前草稿存在，不写入会话归档。 */
@Immutable
data class QuotedMessageUi(
    val messageId: String,
    val fromUser: Boolean,
    val text: String,
)

/** 引用条与模型提示共用同一段摘要，避免两处截断规则漂移。 */
internal const val QUOTE_EXCERPT_MAX_CHARS = 600

internal fun quoteExcerpt(text: String, maxChars: Int = QUOTE_EXCERPT_MAX_CHARS): String {
    val normalized = text
        .replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars).trimEnd() + "…"
}

/**
 * 引用条只显示纯文本：被引用的往往是 Markdown 回答，原样呈现会把
 * `[标题](链接)` 这类标记直接暴露给用户。
 */
private val QuoteImageMarker = Regex("""!\[[^\]]*\]\([^)\s]*\)""")
private val QuoteLinkMarker = Regex("""\[([^\]]+)\]\([^)\s]*\)""")
private val QuoteFenceMarker = Regex("(?m)^```[^\n]*$")
private val QuoteCodeSpan = Regex("`([^`\n]+)`")
private val QuoteHeadingMarker = Regex("(?m)^#{1,6}\\s+")
private val QuoteEmphasisMarker = Regex("""\*\*([^*\n]+)\*\*|__([^_\n]+)__|~~([^~\n]+)~~""")

internal fun plainQuoteText(text: String): String {
    if (text.isBlank()) return ""
    var out = QuoteImageMarker.replace(text, "")
    out = QuoteLinkMarker.replace(out) { it.groupValues[1] }
    out = QuoteFenceMarker.replace(out, "")
    out = QuoteCodeSpan.replace(out) { it.groupValues[1] }
    out = QuoteHeadingMarker.replace(out, "")
    out = QuoteEmphasisMarker.replace(out) { match ->
        match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
    }
    return out.trim()
}

/** 历史气泡里的引用展示：把开头的 Markdown 引用块拆出来单独排版。 */
@Immutable
data class DisplayQuote(
    val label: String?,
    val body: String,
    val rest: String,
)

internal fun splitLeadingQuote(
    text: String,
    userLabel: String,
    assistantLabel: String,
): DisplayQuote? {
    val lines = text.replace("\r\n", "\n").lines()
    if (lines.firstOrNull()?.startsWith(">") != true) return null
    val quoted = mutableListOf<String>()
    var index = 0
    while (index < lines.size && lines[index].startsWith(">")) {
        quoted += lines[index].removePrefix(">").removePrefix(" ")
        index++
    }
    while (index < lines.size && lines[index].isBlank()) index++
    val first = quoted.firstOrNull()?.trim().orEmpty()
    val label = first.takeIf { it == userLabel || it == assistantLabel }
    val body = (if (label != null) quoted.drop(1) else quoted)
        .joinToString("\n")
        .trim()
    if (body.isBlank()) return null
    return DisplayQuote(
        label = label,
        body = body,
        rest = lines.drop(index).joinToString("\n").trimEnd(),
    )
}

/**
 * 引用以 Markdown 引用块拼在用户问题前：模型能按普通文本读到原文，
 * 历史里也保留引用关系，不需要额外的协议字段。
 */
internal fun formatQuotedPrompt(
    quote: QuotedMessageUi,
    prompt: String,
    fromUserLabel: String,
    fromAssistantLabel: String,
): String {
    val excerpt = quoteExcerpt(quote.text)
    if (excerpt.isBlank()) return prompt
    val author = if (quote.fromUser) fromUserLabel else fromAssistantLabel
    val quoted = excerpt.lines().joinToString("\n") { "> $it" }
    val header = "> " + author
    return if (prompt.isBlank()) {
        "$header\n$quoted"
    } else {
        "$header\n$quoted\n\n$prompt"
    }
}
