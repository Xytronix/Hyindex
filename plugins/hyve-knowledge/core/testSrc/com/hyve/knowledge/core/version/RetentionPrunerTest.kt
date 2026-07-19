package com.hyve.knowledge.core.version

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class RetentionPrunerTest {
    private fun makeSlugDir(base: File, slug: String, patchline: String, buildNumber: Int, date: String) {
        val d = File(base, "versions/$slug").apply { mkdirs() }
        File(d, "knowledge.db").writeText("x")
        File(d, "version_meta.json").writeText(
            """{"patchline":"$patchline","version":"b${buildNumber}_$date-abc","date":"$date","shortHash":"abc","fullRevision":"abc123","slug":"$slug","buildNumber":$buildNumber,"protocolCrc":0,"indexedAt":"${date}T00:00:00Z"}"""
        )
    }

    @Test
    fun `keep 0 keeps everything`() {
        val base = Files.createTempDirectory("prune-zero").toFile()
        makeSlugDir(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        makeSlugDir(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")

        RetentionPruner.pruneRetention(base, "release", 0)

        val dirs = File(base, "versions").listFiles { f -> f.isDirectory }!!
        assertThat(dirs).hasSize(2)
        base.deleteRecursively()
    }

    @Test
    fun `keep 1 deletes all but the newest`() {
        val base = Files.createTempDirectory("prune-one").toFile()
        makeSlugDir(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        makeSlugDir(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")
        makeSlugDir(base, "release_b99_2026-06-18-ccc", "release", 99, "2026-06-18")

        RetentionPruner.pruneRetention(base, "release", 1)

        val dirs = File(base, "versions").listFiles { f -> f.isDirectory }!!
        assertThat(dirs).hasSize(1)
        assertThat(dirs.first().name).isEqualTo("release_b101_2026-06-22-bbb")
        base.deleteRecursively()
    }

    @Test
    fun `keep 2 deletes all but the two newest`() {
        val base = Files.createTempDirectory("prune-two").toFile()
        makeSlugDir(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        makeSlugDir(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")
        makeSlugDir(base, "release_b99_2026-06-18-ccc", "release", 99, "2026-06-18")

        RetentionPruner.pruneRetention(base, "release", 2)

        val dirs = File(base, "versions").listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertThat(dirs).containsExactlyInAnyOrder("release_b100_2026-06-20-aaa", "release_b101_2026-06-22-bbb")
        base.deleteRecursively()
    }

    @Test
    fun `only prunes dirs for the given patchline`() {
        val base = Files.createTempDirectory("prune-patchline").toFile()
        makeSlugDir(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        makeSlugDir(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")
        makeSlugDir(base, "pre-release_b117_2026-06-22-ccc", "pre-release", 117, "2026-06-22")

        RetentionPruner.pruneRetention(base, "release", 1)

        val dirs = File(base, "versions").listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertThat(dirs).contains("release_b101_2026-06-22-bbb")
        assertThat(dirs).contains("pre-release_b117_2026-06-22-ccc")
        assertThat(dirs).doesNotContain("release_b100_2026-06-20-aaa")
        base.deleteRecursively()
    }
}
