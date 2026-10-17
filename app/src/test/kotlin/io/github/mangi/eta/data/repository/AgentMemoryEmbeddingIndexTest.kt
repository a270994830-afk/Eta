package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 向量索引的缓存/预算/失效逻辑测试（纯 JUnit，用假 embedder 替代网络）。 */
class AgentMemoryEmbeddingIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newCacheFile(): File = File(folder.newFolder(), "embeddings.json")

    @Test
    fun reusesCachedVectorsAcrossInstances() {
        val file = newCacheFile()
        var calls = 0
        val embedder: (List<String>) -> List<FloatArray>? = { texts ->
            calls++
            texts.map { text -> floatArrayOf(text.length.toFloat(), 1f) }
        }
        val first = AgentMemoryEmbeddingIndex(file, embedder)
        val initial = first.vectors("m1", mapOf("a" to "hello", "b" to "world!"), budget = 8)
        assertEquals(2, initial.size)
        assertEquals(1, calls)

        val second = AgentMemoryEmbeddingIndex(file, embedder)
        val again = second.vectors("m1", mapOf("c" to "hello"), budget = 8)
        assertEquals(1, calls)
        assertTrue(initial["a"]!!.contentEquals(again["c"]!!))
    }

    @Test
    fun budgetOnlyEmbedsMissingEntriesPerCall() {
        val file = newCacheFile()
        val batchSizes = ArrayList<Int>()
        val embedder: (List<String>) -> List<FloatArray>? = { texts ->
            batchSizes += texts.size
            texts.map { floatArrayOf(it.length.toFloat()) }
        }
        val index = AgentMemoryEmbeddingIndex(file, embedder)
        val texts = mapOf("a" to "1", "b" to "22", "c" to "333", "d" to "4444", "e" to "55555")

        val first = index.vectors("m1", texts, budget = 2)
        assertEquals(2, first.size)
        assertEquals(listOf(2), batchSizes)

        val second = index.vectors("m1", texts, budget = 2)
        assertEquals(4, second.size)
        assertEquals(listOf(2, 2), batchSizes)

        val third = index.vectors("m1", texts, budget = 2)
        assertEquals(5, third.size)
        assertEquals(listOf(2, 2, 1), batchSizes)
    }

    @Test
    fun changingModelKeyInvalidatesCache() {
        val file = newCacheFile()
        var calls = 0
        val embedder: (List<String>) -> List<FloatArray>? = { texts ->
            calls++
            texts.map { floatArrayOf(1f) }
        }
        val index = AgentMemoryEmbeddingIndex(file, embedder)
        index.vectors("model-a", mapOf("a" to "text"), budget = 4)
        index.vectors("model-b", mapOf("a" to "text"), budget = 4)
        assertEquals(2, calls)
    }

    @Test
    fun embedderFailureKeepsCachedEntriesAndDoesNotThrow() {
        val file = newCacheFile()
        var fail = false
        val embedder: (List<String>) -> List<FloatArray>? = { texts ->
            if (fail) null else texts.map { floatArrayOf(2f, 2f) }
        }
        val index = AgentMemoryEmbeddingIndex(file, embedder)
        index.vectors("m1", mapOf("a" to "keep"), budget = 4)

        fail = true
        val result = index.vectors("m1", mapOf("a" to "keep", "b" to "missing"), budget = 4)
        assertEquals(1, result.size)
        assertTrue(result["a"]!!.contentEquals(floatArrayOf(2f, 2f)))
        assertNull(result["b"])
    }

    @Test
    fun zeroBudgetReturnsCachedOnly() {
        val file = newCacheFile()
        var calls = 0
        val embedder: (List<String>) -> List<FloatArray>? = { texts ->
            calls++
            texts.map { floatArrayOf(1f) }
        }
        val index = AgentMemoryEmbeddingIndex(file, embedder)
        val result = index.vectors("m1", mapOf("a" to "text"), budget = 0)
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }
}
