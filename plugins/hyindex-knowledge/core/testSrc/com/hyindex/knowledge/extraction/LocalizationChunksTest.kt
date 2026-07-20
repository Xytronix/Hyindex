package com.hyindex.knowledge.extraction

import com.hyindex.knowledge.core.extraction.GameDataType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class LocalizationChunksTest {

    @Test
    fun `server lang keys split by top-level namespace into separate chunks`() {
        val root = Files.createTempDirectory("assets-loc")
        val langPath = "Server/Languages/en-US/server.lang"
        val itemLines = (1..40).joinToString("\n") { "items.Item$it.name = Item Name $it" }
        val commandLines = (1..20).joinToString("\n") { "commands.Cmd$it.msg = Command Message $it" }
        val langContent = "$itemLines\n$commandLines"
        val langFile = root.resolve(langPath)
        Files.createDirectories(langFile.parent)
        Files.writeString(langFile, langContent)

        val result = GameDataParser.parseAssetsTree(root)
        val locChunks = result.chunks.filter { it.type == GameDataType.LOCALIZATION }

        val namespaces = locChunks.map { it.id.substringAfterLast(':') }.toSet()
        assertThat(namespaces).contains("items")
        assertThat(namespaces).contains("commands")
        assertThat(namespaces).hasSize(2)

        val itemsChunk = locChunks.first { it.id.endsWith(":items") }
        assertThat(itemsChunk.textForEmbedding).contains("Item Name 1")
        assertThat(itemsChunk.textForEmbedding).doesNotContain("Command Message")

        val commandsChunk = locChunks.first { it.id.endsWith(":commands") }
        assertThat(commandsChunk.textForEmbedding).contains("Command Message 1")
        assertThat(commandsChunk.textForEmbedding).doesNotContain("Item Name")

        File(root.toString()).deleteRecursively()
    }

    @Test
    fun `small nested lang file produces a single chunk`() {
        val root = Files.createTempDirectory("assets-loc-small")
        val langPath = "Common/Languages/en-US/avatarCustomization/faces.lang"
        val langContent = """
            Face_Neutral.name = Neutral
            Face_Scar.name = Scarred
        """.trimIndent()
        val langFile = root.resolve(langPath)
        Files.createDirectories(langFile.parent)
        Files.writeString(langFile, langContent)

        val result = GameDataParser.parseAssetsTree(root)
        val locChunks = result.chunks.filter { it.type == GameDataType.LOCALIZATION }

        assertThat(locChunks).hasSize(1)
        assertThat(locChunks.first().textForEmbedding).contains("Neutral")
        assertThat(locChunks.first().textForEmbedding).contains("Scarred")

        File(root.toString()).deleteRecursively()
    }

    @Test
    fun `fallback lang produces no localization chunks`() {
        val root = Files.createTempDirectory("assets-loc-fallback")
        val fallbackPath = "Server/Languages/fallback.lang"
        val fallbackFile = root.resolve(fallbackPath)
        Files.createDirectories(fallbackFile.parent)
        Files.writeString(fallbackFile, "en-GB = en-US\n")

        val result = GameDataParser.parseAssetsTree(root)
        val locChunks = result.chunks.filter { it.type == GameDataType.LOCALIZATION }
        assertThat(locChunks).isEmpty()

        File(root.toString()).deleteRecursively()
    }
}
