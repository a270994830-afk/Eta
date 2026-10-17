package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("安全内容")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# 核心记忆\n喜欢 Kotlin\n## 项目\nEta Agent\n其他")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## 项目"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Eta Agent"))
        assertTrue(search.content.contains("3: ## 项目"))
        assertTrue(search.content.contains("5: 其他"))
        assertFalse(search.hasMore)
    }

    @Test
    fun searchRanksHeadingHitSectionFirstAndSkipsUnrelatedSections() {
        val store = store()
        store.replaceAll(
            "# 核心记忆\n" +
                "- 用户喜欢简洁回答\n" +
                "## 闲聊\n" +
                "- 昨天又聊到部署这件事\n" +
                "## 部署\n" +
                "- 部署脚本在 eta-src",
        )

        val result = store.read(query = "部署", maxChars = 400)
        // 标题命中的小节（在文件更后面）整体排在前面；行号仍是原始行号
        assertTrue(result.content.contains("5: ## 部署"))
        assertTrue(result.content.contains("6: - 部署脚本在 eta-src"))
        assertTrue(result.content.indexOf("5: ## 部署") < result.content.indexOf("3: ## 闲聊"))
        assertEquals(3, result.matchedLines)
        assertFalse(result.hasMore)
        // 完全没命中的小节不出现
        assertFalse(result.content.contains("用户喜欢简洁回答"))
    }

    @Test
    fun searchMatchesAllTermsAcrossLinesAndKeepsPhraseBonus() {
        val store = store()
        store.replaceAll(
            "# 记忆\n" +
                "## A\n" +
                "- 部署脚本\n" +
                "## B\n" +
                "- 部署\n" +
                "- 脚本",
        )

        val result = store.read(query = "部署 脚本", maxChars = 400)
        assertEquals(3, result.matchedLines)
        // A 小节里有完整短语，得分高于 B 小节里拆开的两个词
        assertTrue(result.content.startsWith("3: ## A"))
    }

    @Test
    fun searchTermsDropSingleAsciiButKeepSingleCjkChar() {
        val store = store()
        store.replaceAll("# 记忆\n- 记一下这个点\n- xyz")

        val asciiOnly = store.read(query = "x", maxChars = 200)
        assertEquals(0, asciiOnly.matchedLines)

        val cjk = store.read(query = "记", maxChars = 200)
        assertEquals(2, cjk.matchedLines)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "新偏好",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n## 项目\n旧项目", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## 冲突追加"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("冲突追加"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## 新章节\n内容"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("旧项目"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
