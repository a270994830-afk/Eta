package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.ui.markdown.decodeMarkdownText
import io.github.mangi.eta.ui.markdown.safeLinkUrl

/** 回答正文里的外链来源；按首次出现顺序去重。 */
@Immutable
data class AnswerSourceUi(
    val label: String,
    val url: String,
) {
    val host: String
        get() = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
            .removePrefix("www.")
}

internal const val ANSWER_SOURCES_MAX = 20

private val markdownLink = Regex(
    """\[([^\[\]\n]{1,160})]\(\s*<?([A-Za-z][A-Za-z0-9+.\-]*:[^\s)<>]+)>?(?:\s+"[^"\n]*")?\s*\)""",
)
private val autolink = Regex("""<([A-Za-z][A-Za-z0-9+.\-]*:[^\s<>]+)>""")

internal fun extractAnswerSources(
    markdown: String,
    max: Int = ANSWER_SOURCES_MAX,
): List<AnswerSourceUi> {
    if ('[' !in markdown && '<' !in markdown) return emptyList()
    val out = LinkedHashMap<String, AnswerSourceUi>()
    fun add(label: String, rawUrl: String) {
        val url = safeLinkUrl(rawUrl) ?: return
        if (out.containsKey(url)) return
        out[url] = AnswerSourceUi(label = cleanLabel(label), url = url)
    }
    for (match in markdownLink.findAll(markdown)) {
        // `![alt](url)` 是图片，不是来源。
        if (match.range.first > 0 && markdown[match.range.first - 1] == '!') continue
        if (out.size >= max) return out.values.toList()
        add(match.groupValues[1], match.groupValues[2])
    }
    for (match in autolink.findAll(markdown)) {
        if (out.size >= max) break
        add("", match.groupValues[1])
    }
    return out.values.toList()
}

private fun cleanLabel(raw: String): String = decodeMarkdownText(raw)
    .replace("`", "")
    .replace("**", "")
    .trim()
