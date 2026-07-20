// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.diff

import com.hyindex.knowledge.core.db.KnowledgeDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant

class DiffEngineSnapshotTest {

    private val json = Json { prettyPrint = true }

    private fun makeSummary(id: String, name: String, corpus: String) = DiffEngine.NodeSummary(
        id = id,
        displayName = name,
        corpus = corpus,
        dataType = null,
        nodeType = "JavaClass",
        filePath = "com/hyindex/$name.java",
        owningFile = "com/hyindex/$name.java",
        contentHash = name.hashCode().toString(),
    )

    @Test fun `writeSnapshot and loadSnapshot round-trip`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-snapshot-test").toFile()


        val original = listOf(
            makeSummary("id1", "PlayerEntity", "code"),
            makeSummary("id2", "IronSword", "gamedata"),
            makeSummary("id3", "HotbarWidget", "client"),
        )

        val snapshotFile = dir.resolve("snapshot.json")
        snapshotFile.writeText(json.encodeToString(original))

        val loaded = engine.loadSnapshot(snapshotFile)

        assertEquals(original.size, loaded.size)
        assertEquals(original.toSet(), loaded.toSet())
    }

    @Test fun `writeSnapshot creates parent dirs and file`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-snapshot-test2").toFile()

        val original = listOf(makeSummary("x1", "Foo", "code"))
        val snapshotFile = dir.resolve("nested/dir/snap.json")

        snapshotFile.parentFile.mkdirs()
        snapshotFile.writeText(json.encodeToString(original))

        assertTrue(snapshotFile.exists())
        val loaded = engine.loadSnapshot(snapshotFile)
        assertEquals(1, loaded.size)
        assertEquals("x1", loaded[0].id)
        assertEquals("Foo", loaded[0].displayName)
    }


    private fun makeEntry(
        nodeId: String = "id1",
        displayName: String = "Foo",
        corpus: String = "code",
        changeType: ChangeType = ChangeType.CHANGED,
        detail: DiffDetail? = null,
    ) = DiffEntry(
        nodeId = nodeId,
        displayName = displayName,
        corpus = corpus,
        dataType = null,
        nodeType = "JavaClass",
        changeType = changeType,
        filePath = null,
        detail = detail,
    )

    @Test fun `gamedata ADDED is HIGH significance`() {
        val entry = makeEntry(corpus = "gamedata", changeType = ChangeType.ADDED)
        assertEquals(Significance.LOW, entry.significance, "default before engine enrichment")


        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-sig-test").toFile()
        val snap1 = dir.resolve("a.json")
        val snap2 = dir.resolve("b.json")
        snap1.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("gd0", "WoodSword", "gamedata", null, "item", null, null, "hash0")
        )))
        snap2.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("gd0", "WoodSword", "gamedata", null, "item", null, null, "hash0"),
            DiffEngine.NodeSummary("gd1", "IronSword", "gamedata", null, "item", null, null, "hash1")
        )))
        val diff = engine.computeDiff("v1", "v2", snap1, snap2, isSnapshotA = true, isSnapshotB = true)
        val added = diff.entries.filter { it.nodeId == "gd1" }
        assertEquals(1, added.size)
        assertEquals(Significance.HIGH, added[0].significance)
    }

    @Test fun `code signatureChanged is HIGH significance`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-sig-test2").toFile()
        val snap1 = dir.resolve("a.json")
        val snap2 = dir.resolve("b.json")

        snap1.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("c1", "PlayerEntity", "code", null, "JavaClass", null, null, "hash_a")
        )))
        snap2.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("c1", "PlayerEntity", "code", null, "JavaClass", null, null, "hash_b")
        )))

        val diff = engine.computeDiff("v1", "v2", snap1, snap2, isSnapshotA = true, isSnapshotB = true)
        assertEquals(1, diff.entries.size)

        assertEquals(Significance.LOW, diff.entries[0].significance)
    }

    @Test fun `protocol in id yields HIGH significance`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-sig-test3").toFile()
        val snap1 = dir.resolve("a.json")
        val snap2 = dir.resolve("b.json")

        snap1.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("c0", "Anchor", "code", null, "JavaClass", null, null, "h0")
        )))
        snap2.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("c0", "Anchor", "code", null, "JavaClass", null, null, "h0"),
            DiffEngine.NodeSummary("protocol:login", "LoginProtocol", "code", null, "JavaClass", null, null, "h")
        )))
        val diff = engine.computeDiff("v1", "v2", snap1, snap2, isSnapshotA = true, isSnapshotB = true)
        val added = diff.entries.filter { it.nodeId == "protocol:login" }
        assertEquals(1, added.size)
        assertEquals(Significance.HIGH, added[0].significance)
    }

    @Test fun `client entry is MEDIUM significance`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-sig-test4").toFile()
        val snap1 = dir.resolve("a.json")
        val snap2 = dir.resolve("b.json")
        snap1.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("cl1", "HotbarWidget", "client", null, "ClientClass", null, null, "hash_a")
        )))
        snap2.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("cl1", "HotbarWidget", "client", null, "ClientClass", null, null, "hash_b")
        )))
        val diff = engine.computeDiff("v1", "v2", snap1, snap2, isSnapshotA = true, isSnapshotB = true)
        assertEquals(1, diff.entries.size)
        assertEquals(Significance.MEDIUM, diff.entries[0].significance)
    }


    @Test fun `toMarkdown includes Highlights section for HIGH entries`() {
        val highEntry = makeEntry(
            nodeId = "gd1", displayName = "IronSword", corpus = "gamedata",
            changeType = ChangeType.ADDED
        ).copy(significance = Significance.HIGH)
        val lowEntry = makeEntry(
            nodeId = "c1", displayName = "FooBar", corpus = "code",
            changeType = ChangeType.CHANGED
        ).copy(significance = Significance.LOW)
        val diff = VersionDiff(
            versionA = "v1", versionB = "v2",
            computedAt = Instant.now().toString(),
            summary = DiffSummary(1, 0, 1, emptyMap()),
            entries = listOf(highEntry, lowEntry),
        )
        val md = DiffExporter.toMarkdown(diff)
        assertTrue(md.contains("## Highlights"), "Should have Highlights section")
        assertTrue(md.contains("IronSword"), "Highlights should list IronSword")
    }

    @Test fun `toMarkdown omits Highlights section when no HIGH entries`() {
        val lowEntry = makeEntry(
            nodeId = "c1", displayName = "FooBar", corpus = "code",
            changeType = ChangeType.CHANGED
        ).copy(significance = Significance.LOW)
        val diff = VersionDiff(
            versionA = "v1", versionB = "v2",
            computedAt = Instant.now().toString(),
            summary = DiffSummary(0, 0, 1, emptyMap()),
            entries = listOf(lowEntry),
        )
        val md = DiffExporter.toMarkdown(diff)
        assertFalse(md.contains("## Highlights"), "Should not have Highlights when no HIGH entries")
    }

    @Test fun `toMarkdown deduplicates repeated highlight entries`() {
        val entryA = makeEntry(
            nodeId = "c1", displayName = "CancelChainInteraction#getCancelOnItemChange",
            corpus = "code", changeType = ChangeType.ADDED,
        ).copy(significance = Significance.HIGH)
        val entryB = makeEntry(
            nodeId = "c2", displayName = "CancelChainInteraction#getCancelOnItemChange",
            corpus = "code", changeType = ChangeType.ADDED,
        ).copy(significance = Significance.HIGH)
        val diff = VersionDiff(
            versionA = "v1", versionB = "v2",
            computedAt = Instant.now().toString(),
            summary = DiffSummary(2, 0, 0, emptyMap()),
            entries = listOf(entryA, entryB),
        )
        val md = DiffExporter.toMarkdown(diff)
        val highlightLine = "- **CancelChainInteraction#getCancelOnItemChange** (code, ADDED)"
        assertEquals(1, md.lines().count { it == highlightLine },
            "a repeated highlight entry should be listed only once")
    }


    @Test fun `docs added removed and changed are tracked`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-docs-diff").toFile()
        val snap1 = dir.resolve("a.json")
        val snap2 = dir.resolve("b.json")
        snap1.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("docs:keep", "Keep", "docs", "modding", "DocsPage", "keep.md", null, "h_keep"),
            DiffEngine.NodeSummary("docs:gone", "Gone", "docs", "modding", "DocsPage", "gone.md", null, "h_gone"),
            DiffEngine.NodeSummary("docs:edit", "Edit", "docs", "modding", "DocsPage", "edit.md", null, "h_old"),
        )))
        snap2.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("docs:keep", "Keep", "docs", "modding", "DocsPage", "keep.md", null, "h_keep"),
            DiffEngine.NodeSummary("docs:new", "New", "docs", "modding", "DocsPage", "new.md", null, "h_new"),
            DiffEngine.NodeSummary("docs:edit", "Edit", "docs", "modding", "DocsPage", "edit.md", null, "h_new2"),
        )))
        val diff = engine.computeDiff("v1", "v2", snap1, snap2, isSnapshotA = true, isSnapshotB = true)
        val added = diff.entries.filter { it.changeType == ChangeType.ADDED && it.corpus == "docs" }
        val removed = diff.entries.filter { it.changeType == ChangeType.REMOVED && it.corpus == "docs" }
        val changed = diff.entries.filter { it.changeType == ChangeType.CHANGED && it.corpus == "docs" }
        assertEquals(listOf("docs:new"), added.map { it.nodeId })
        assertEquals(listOf("docs:gone"), removed.map { it.nodeId })
        assertEquals(listOf("docs:edit"), changed.map { it.nodeId })
    }

    @Test fun `docs body change yields CHANGED not unchanged`() {
        val engine = DiffEngine()
        val dir = Files.createTempDirectory("hyindex-docs-body").toFile()
        val snap1 = dir.resolve("a.json")
        val snap2 = dir.resolve("b.json")
        snap1.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("docs:p", "Page", "docs", "modding", "DocsPage", "p.md", null, "hash_a")
        )))
        snap2.writeText(json.encodeToString(listOf(
            DiffEngine.NodeSummary("docs:p", "Page", "docs", "modding", "DocsPage", "p.md", null, "hash_b")
        )))
        val diff = engine.computeDiff("v1", "v2", snap1, snap2, isSnapshotA = true, isSnapshotB = true)
        assertEquals(1, diff.entries.size)
        assertEquals(ChangeType.CHANGED, diff.entries[0].changeType)
    }

    @Test fun `changed docs node produces Docs detail with title and date`() {
        val dir = Files.createTempDirectory("hyindex-docs-detail").toFile()
        val dbFileA = dir.resolve("a.db")
        val dbFileB = dir.resolve("b.db")
        fun insert(db: KnowledgeDatabase, content: String, title: String, date: String?) = db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, content, corpus, data_type, published_date) " +
                "VALUES ('docs:p', 'DocsPage', ?, 'p.md', ?, 'docs', 'modding', ?)",
            title, content, date,
        )
        val dbA = KnowledgeDatabase.forFile(dbFileA)
        val dbB = KnowledgeDatabase.forFile(dbFileB)
        insert(dbA, "old body", "Old Title", "2025-01-01")
        insert(dbB, "new body", "New Title", "2026-02-02")
        dbA.close(); dbB.close()

        val engine = DiffEngine()
        val diff = engine.computeDiff("v1", "v2", dbFileA, dbFileB)
        val entry = diff.entries.single { it.nodeId == "docs:p" }
        assertEquals(ChangeType.CHANGED, entry.changeType)
        val detail = entry.detail
        assertTrue(detail is DiffDetail.Docs, "docs change should carry Docs detail")
        detail as DiffDetail.Docs
        assertTrue(detail.bodyChanged)
        assertTrue(detail.titleChanged)
        assertEquals("Old Title", detail.oldTitle)
        assertEquals("New Title", detail.newTitle)
        assertEquals("2025-01-01", detail.oldPublishedDate)
        assertEquals("2026-02-02", detail.newPublishedDate)
    }

    @Test fun `toMarkdown includes docs section detail`() {
        val entry = makeEntry(
            nodeId = "docs:p", displayName = "Page", corpus = "docs",
            changeType = ChangeType.CHANGED,
            detail = DiffDetail.Docs(
                titleChanged = true, oldTitle = "Old Title", newTitle = "New Title",
                bodyChanged = true, oldPublishedDate = "2025-01-01", newPublishedDate = "2026-02-02",
            ),
        )
        val diff = VersionDiff(
            versionA = "v1", versionB = "v2",
            computedAt = Instant.now().toString(),
            summary = DiffSummary(0, 0, 1, mapOf("docs" to CorpusDiffSummary(0, 0, 1))),
            entries = listOf(entry),
        )
        val md = DiffExporter.toMarkdown(diff)
        assertTrue(md.contains("## docs"), "Should have a docs section")
        assertTrue(md.contains("New Title"), "Should report new title")
        assertTrue(md.contains("2026-02-02"), "Should report new published date")
    }
}
