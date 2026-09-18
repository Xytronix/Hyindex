// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.extraction

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientUIParserTest {

    @Test
    fun `indexes ui and xaml templates and real config json`() {
        assertTrue(ClientUIParser.isIndexableClientFile("Client/Data/Game/Interface/InGame/Hud/Hotbar.ui"))
        assertTrue(ClientUIParser.isIndexableClientFile("Client/Data/Shared/UI/DesignSystem/Button.xaml"))
        assertTrue(ClientUIParser.isIndexableClientFile("Client/Data/Game/Interface/InGame/Pages/Inventory/InventoryPage.json"))
    }

    @Test
    fun `excludes font glyph atlases`() {
        assertFalse(ClientUIParser.isIndexableClientFile("Client/Data/Shared/UI/Fonts/NotoSansKR-Medium/NotoSansKR-Medium_40.json"))
    }

    @Test
    fun `excludes theme palette tables`() {
        assertFalse(ClientUIParser.isIndexableClientFile("Client/Data/Shared/UI/Theme/Colors.json"))
    }

    @Test
    fun `excludes per-locale language strings`() {
        assertFalse(ClientUIParser.isIndexableClientFile("Client/Data/Shared/Language/en-US/strings.json"))
    }

    @Test
    fun `excludes license texts`() {
        assertFalse(ClientUIParser.isIndexableClientFile("Client/Data/Licenses/ThirdParty.json"))
    }

    @Test
    fun `excludes non-ui extensions`() {
        assertFalse(ClientUIParser.isIndexableClientFile("Client/Data/Shared/UI/Fonts/NotoSansKR-Medium/atlas.png"))
        assertFalse(ClientUIParser.isIndexableClientFile("Client/Data/Shared/Language/en-US/strings.lang"))
    }

    @Test
    fun `keeps legit ui whose name resembles an excluded category`() {


        assertTrue(ClientUIParser.isIndexableClientFile("Client/Data/Game/Interface/Common/ColorOptionGrid/Grid.ui"))
    }

    @Test
    fun `embedding text retains the tail of official-sized ui files`() {
        val marker = "TAIL_BINDING_MARKER"
        val content = "x".repeat(30_000) + marker
        val chunk = ClientUIChunk(
            id = "ui:Custom/Common.ui",
            type = ClientUIType.UI,
            name = "Common",
            filePath = "/tmp/Common.ui",
            relativePath = "Custom/Common.ui",
            fileHash = "hash",
            content = content,
            category = "Common",
            textForEmbedding = "",
        )

        assertTrue(ClientUIParser.buildEmbeddingText(chunk).contains(marker))
    }

    @Test
    fun `parseClientData skips outbound ui symlink`() {
        val root = java.nio.file.Files.createTempDirectory("client-link").toFile()
        val ui = java.io.File(root, "Hud.ui")
        ui.writeText("<Page Name=\"Hud\"></Page>")
        val outside = java.nio.file.Files.createTempDirectory("secret-ui").toFile()
        val secret = java.io.File(outside, "Secret.ui").apply { writeText("<Page Name=\"SecretLeak\"></Page>") }
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "Leak.ui").toPath(), secret.toPath())
        val result = ClientUIParser.parseClientData(root)
        assertTrue(result.chunks.none { it.name.contains("Secret") || it.content.contains("SecretLeak") })
        assertTrue(result.chunks.any { it.name == "Hud" })
        root.deleteRecursively(); outside.deleteRecursively()
    }
}
