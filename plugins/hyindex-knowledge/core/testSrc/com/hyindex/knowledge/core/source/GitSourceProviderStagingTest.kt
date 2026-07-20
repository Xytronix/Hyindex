package com.hyindex.knowledge.core.source

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GitSourceProviderStagingTest {
    private fun write(root: File, rel: String, body: String) =
        File(root, rel).apply { parentFile.mkdirs(); writeText(body) }

    @Test
    fun `stages main + generated sources flat, excludes tests, com-rooted`() {
        val wt = Files.createTempDirectory("wt").toFile()

        write(wt, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java", "package com.hypixel.hytale.codec; class Codec {}")
        write(wt, "NPC/src/main/java/com/hypixel/hytale/server/npc/NpcPlugin.java", "package com.hypixel.hytale.server.npc; class NpcPlugin {}")
        write(wt, "NPC/src/test/java/com/hypixel/hytale/server/npc/NpcTest.java", "package com.hypixel.hytale.server.npc; class NpcTest {}")
        write(wt, "libs/ConcurrentFastUtil/src/main/java/com/hypixel/fastutil/FastThing.java", "package com.hypixel.fastutil; class FastThing {}")
        write(wt, "HytaleServer/Protocol/target/generated-sources/java/com/hypixel/hytale/protocol/packets/Packet.java", "package com.hypixel.hytale.protocol.packets; class Packet {}")
        write(wt, "HytaleServer/Protocol/target/generated-test-sources/java/com/hypixel/hytale/protocol/TestPacket.java", "package com.hypixel.hytale.protocol; class TestPacket {}")

        val stage = Files.createTempDirectory("stage").toFile()
        GitSourceProvider.stageFlatRoot(wt, stage)

        fun rel(p: String) = File(stage, p)
        assertThat(rel("com/hypixel/hytale/codec/Codec.java")).exists()
        assertThat(rel("com/hypixel/hytale/server/npc/NpcPlugin.java")).exists()
        assertThat(rel("com/hypixel/hytale/protocol/packets/Packet.java")).exists()
        assertThat(rel("com/hypixel/fastutil/FastThing.java")).exists()

        assertThat(rel("com/hypixel/hytale/server/npc/NpcTest.java")).doesNotExist()
        assertThat(rel("com/hypixel/hytale/protocol/TestPacket.java")).doesNotExist()

        val staged = stage.walkTopDown().filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(stage).invariantSeparatorsPath }.toList()
        assertThat(staged).isNotEmpty()
        assertThat(staged).allMatch { it.startsWith("com/") }

        wt.deleteRecursively(); stage.deleteRecursively()
    }
}
