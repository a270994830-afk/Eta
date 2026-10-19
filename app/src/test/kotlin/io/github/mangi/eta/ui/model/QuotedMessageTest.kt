package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotedMessageTest {
    @Test
    fun excerptNormalizesLineEndingsAndTrailingSpaces() {
        assertEquals("第一行\n第二行", quoteExcerpt("  第一行  \r\n第二行\t\r\n"))
    }

    @Test
    fun excerptTruncatesLongTextWithEllipsis() {
        val excerpt = quoteExcerpt("字".repeat(20), maxChars = 8)
        assertEquals("字".repeat(8) + "…", excerpt)
    }

    @Test
    fun quotedPromptPrefixesEveryLineWithAuthor() {
        val quote = QuotedMessageUi(messageId = "m1", fromUser = false, text = "原文\n第二行")
        val prompt = formatQuotedPrompt(quote, "当前问题", "用户", "助手")
        assertEquals("> 助手\n> 原文\n> 第二行\n\n当前问题", prompt)
    }

    @Test
    fun quotedPromptReturnsPromptAloneWhenQuoteBlank() {
        val quote = QuotedMessageUi(messageId = "m1", fromUser = true, text = "   ")
        assertEquals("当前问题", formatQuotedPrompt(quote, "当前问题", "用户", "助手"))
    }

    @Test
    fun quotedPromptOmitsTrailingBlankSectionWithoutPrompt() {
        val quote = QuotedMessageUi(messageId = "m1", fromUser = true, text = "原文")
        assertEquals("> 用户\n> 原文", formatQuotedPrompt(quote, "", "用户", "助手"))
    }

    @Test
    fun plainQuoteTextKeepsLinkLabelsAndDropsMarkup() {
        val markdown = "## 标题\n1. [Google](https://www.google.com)\n2. **GitHub** 与 `code`"
        assertEquals("标题\n1. Google\n2. GitHub 与 code", plainQuoteText(markdown))
    }

    @Test
    fun plainQuoteTextDropsImagesAndFences() {
        val markdown = "![图](https://example.com/a.png)\n```kotlin\nval a = 1\n```"
        assertEquals("val a = 1", plainQuoteText(markdown))
    }

    @Test
    fun splitLeadingQuoteSeparatesAuthorAndRest() {
        val display = splitLeadingQuote("> 助手\n> 原文\n> 第二行\n\n当前问题", "用户", "助手")
        assertTrue(display != null)
        assertEquals("助手", display!!.label)
        assertEquals("原文\n第二行", display.body)
        assertEquals("当前问题", display.rest)
    }

    @Test
    fun splitLeadingQuoteTreatsUnknownFirstLineAsContent() {
        val display = splitLeadingQuote("> 手写的引用\n> 第二行", "用户", "助手")
        assertTrue(display != null)
        assertEquals(null, display!!.label)
        assertEquals("手写的引用\n第二行", display.body)
        assertEquals("", display.rest)
    }

    @Test
    fun splitLeadingQuoteIgnoresOrdinaryMessage() {
        assertEquals(null, splitLeadingQuote("普通消息\n> 不是开头", "用户", "助手"))
    }
}

class AnswerSourcesTest {
    @Test
    fun extractsMarkdownLinksInOrderAndDeduplicates() {
        val content = "见 [文档](https://example.com/a) 与 [另一个](https://example.com/b)，" +
            "再引一次 [文档](https://example.com/a)。"
        val sources = extractAnswerSources(content)
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), sources.map { it.url })
        assertEquals(listOf("文档", "另一个"), sources.map { it.label })
    }

    @Test
    fun skipsImagesAndUnsafeSchemes() {
        val content = "![图](https://example.com/p.png) [坏](javascript:alert(1)) " +
            "[好](https://example.com/ok)"
        val sources = extractAnswerSources(content)
        assertEquals(listOf("https://example.com/ok"), sources.map { it.url })
    }

    @Test
    fun acceptsAutolinksAndDropsDuplicateWithMarkdownLink() {
        val content = "裸链 <https://example.com/auto> 与显式 [站](https://example.com/auto)"
        val sources = extractAnswerSources(content)
        assertEquals(1, sources.size)
        assertEquals("https://example.com/auto", sources.single().url)
    }

    @Test
    fun returnsEmptyForPlainAnswer() {
        assertTrue(extractAnswerSources("没有链接的回答。").isEmpty())
    }

    @Test
    fun hostStripsSchemeAndWww() {
        val source = AnswerSourceUi(label = "示例", url = "https://www.example.com/path?q=1")
        assertEquals("example.com", source.host)
    }
}
