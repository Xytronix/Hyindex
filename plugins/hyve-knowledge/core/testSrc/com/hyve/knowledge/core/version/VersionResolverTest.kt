// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.version

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class VersionResolverTest {
    private fun ver(base: File, dir: String, patchline: String, buildNumber: Int? = null, date: String = "2026-06-22") {
        val d = File(base, "versions/$dir").apply { mkdirs() }
        File(d, "knowledge.db").writeText("x")
        val bn = if (buildNumber != null) ""","buildNumber":$buildNumber""" else ""
        File(d, "version_meta.json").writeText("""{"patchline":"$patchline","date":"$date"$bn,"indexedAt":"${date}T00:00:00Z"}""")
    }

    @Test
    fun `picks latest dir per patchline by buildNumber then date`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        ver(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")
        ver(base, "pre-release_b117_2026-06-22-ccc", "pre-release", 117, "2026-06-22")
        val map = VersionResolver.resolveAll(base)
        assertThat(map["release"]).isEqualTo("release_b101_2026-06-22-bbb")
        assertThat(map["pre-release"]).isEqualTo("pre-release_b117_2026-06-22-ccc")
        assertThat(VersionResolver.latestSlug(base, "release")).isEqualTo("release_b101_2026-06-22-bbb")
        base.deleteRecursively()
    }

    @Test
    fun `tolerates the current bare-patchline layout and ignores incomplete dirs`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "release", "release")            // current layout: dir name == patchline
        File(base, "versions/half").apply { mkdirs() }   // no version_meta/db -> ignored
        val map = VersionResolver.resolveAll(base)
        assertThat(map["release"]).isEqualTo("release")
        base.deleteRecursively()
    }

    @Test
    fun `listSlugs returns only matching patchline newest-first`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        ver(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")
        ver(base, "pre-release_b117_2026-06-22-ccc", "pre-release", 117, "2026-06-22")
        val slugs = VersionResolver.listSlugs(base, "release")
        assertThat(slugs).containsExactly("release_b101_2026-06-22-bbb", "release_b100_2026-06-20-aaa")
        base.deleteRecursively()
    }

    @Test
    fun `resolveSlug matches by build number with and without b prefix`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        ver(base, "release_b101_2026-06-22-bbb", "release", 101, "2026-06-22")
        assertThat(VersionResolver.resolveSlug(base, "release", "b100")).isEqualTo("release_b100_2026-06-20-aaa")
        assertThat(VersionResolver.resolveSlug(base, "release", "100")).isEqualTo("release_b100_2026-06-20-aaa")
        base.deleteRecursively()
    }

    @Test
    fun `resolveSlug matches by version part and substring`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        assertThat(VersionResolver.resolveSlug(base, "release", "b100_2026-06-20-aaa")).isEqualTo("release_b100_2026-06-20-aaa")
        assertThat(VersionResolver.resolveSlug(base, "release", "aaa")).isEqualTo("release_b100_2026-06-20-aaa")
        base.deleteRecursively()
    }

    @Test
    fun `resolveSlug returns null for unknown version`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "release_b100_2026-06-20-aaa", "release", 100, "2026-06-20")
        assertThat(VersionResolver.resolveSlug(base, "release", "nope")).isNull()
        base.deleteRecursively()
    }

    @Test
    fun `release query does not match a pre-release dir`() {
        val base = Files.createTempDirectory("kn").toFile()
        ver(base, "pre-release_b117_2026-06-22-ccc", "pre-release", 117, "2026-06-22")
        assertThat(VersionResolver.listSlugs(base, "release")).isEmpty()
        assertThat(VersionResolver.resolveSlug(base, "release", "b117")).isNull()
        base.deleteRecursively()
    }
}
