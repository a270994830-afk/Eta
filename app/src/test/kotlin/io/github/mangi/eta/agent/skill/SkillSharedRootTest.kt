package io.github.mangi.eta.agent.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillSharedRootTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun createSkill(root: File, id: String, body: String = "shared body"): File {
        val dir = File(root, id).also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: $id
            description: probe skill
            ---
            $body
            """.trimIndent(),
        )
        return dir
    }

    private fun entry(dir: File, source: String = "shared") = SkillIndexEntry(
        id = dir.name,
        name = dir.name,
        description = "probe skill",
        rootPath = dir.absolutePath,
        skillFilePath = File(dir, "SKILL.md").absolutePath,
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
        source = source,
    )

    @Test
    fun loaderLoadsSkillFromSharedRoot() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val sharedRoot = temporaryFolder.newFolder("shared")
        val skillDir = createSkill(sharedRoot, "probe", "loaded from shared root")

        val loaded = SkillLoader(skillsRoot, sharedRoots = listOf(sharedRoot))
            .load(entry(skillDir), "test")

        assertNotNull(loaded)
        assertEquals("probe", loaded?.skillId)
        assertTrue(loaded?.bodyMarkdown.orEmpty().contains("loaded from shared root"))
    }

    @Test
    fun loaderRejectsSkillOutsideConfiguredRoots() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val sharedRoot = temporaryFolder.newFolder("shared")
        val outsideRoot = temporaryFolder.newFolder("outside")
        val skillDir = createSkill(outsideRoot, "probe")

        val loaded = SkillLoader(skillsRoot, sharedRoots = listOf(sharedRoot))
            .load(entry(skillDir), "test")

        assertNull(loaded)
    }

    @Test
    fun resourceReaderReadsSharedSkillAndRejectsOutsideRoot() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val sharedRoot = temporaryFolder.newFolder("shared")
        val skillDir = createSkill(sharedRoot, "probe")
        val reference = File(skillDir, "references/guide.md")
        reference.parentFile?.mkdirs()
        reference.writeText("shared reference")

        val reader = SkillResourceReader(skillsRoot, sharedRoots = listOf(sharedRoot))
        val read = reader.readText(entry(skillDir), "references/guide.md")
        assertTrue(read is SkillResourceReadResult.Success)
        assertEquals("shared reference", (read as SkillResourceReadResult.Success).text)

        val outsideRoot = temporaryFolder.newFolder("outside")
        val outsideSkill = createSkill(outsideRoot, "probe")
        val rejected = reader.readText(entry(outsideSkill), "SKILL.md")
        assertTrue(rejected is SkillResourceReadResult.Failure)
    }
}
