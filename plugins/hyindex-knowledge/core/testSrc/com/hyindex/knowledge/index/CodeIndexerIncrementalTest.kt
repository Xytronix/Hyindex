package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CodeIndexerIncrementalTest {
    private fun writeJava(root: File, rel: String, body: String) {
        val f = File(root, rel); f.parentFile.mkdirs(); f.writeText(body)
    }

    private fun ctxFor(base: File, decompile: File): IndexContext {
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "v1")
        val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), StdoutLogProvider)
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), StdoutLogProvider), StdoutLogProvider,
        )
        return IndexContext(cfg, db, cache, StdoutLogProvider, NoopProgressReporter, decompileDir = decompile)
    }

    // (display_name, chunk_index) for every embedded code node, ordered by ordinal.
    private fun codeNodes(db: KnowledgeDatabase): List<Pair<String, Int>> =
        db.query(
            "SELECT display_name, chunk_index FROM nodes WHERE corpus='code' AND embedding_text IS NOT NULL ORDER BY chunk_index",
        ) { rs -> rs.getString("display_name") to rs.getInt("chunk_index") }

    @Test
    fun `incremental reindex keeps unchanged files and only updates the delta`() {
        val base = Files.createTempDirectory("hyindex-inc").toFile()
        val decompile = File(base, "decompiled").apply { mkdirs() }
        writeJava(decompile, "com/hypixel/hytale/A.java", "package com.hypixel.hytale; class A { void a() { int x = 1; } }")
        writeJava(decompile, "com/hypixel/hytale/B.java", "package com.hypixel.hytale; class B { void b() { int y = 2; } }")

        val ctx = ctxFor(base, decompile)
        val r1 = CodeIndexer(ctx).index()
        assertThat(r1.skipped).isFalse()
        val after1 = codeNodes(ctx.db)
        assertThat(after1).isNotEmpty()
        assertThat(after1.map { it.second }).containsExactlyElementsOf(after1.indices.toList())
        assertThat(after1.map { it.first }).anySatisfy { assertThat(it).contains("A#a") }
        assertThat(after1.map { it.first }).anySatisfy { assertThat(it).contains("B#b") }

        // No source change -> corpus is skipped.
        assertThat(CodeIndexer(ctx).index().skipped).isTrue()

        // Change only B (add method c); A is untouched.
        writeJava(decompile, "com/hypixel/hytale/B.java", "package com.hypixel.hytale; class B { void b() { int y = 2; } void c() { int z = 3; } }")
        val r2 = CodeIndexer(ctx).index()
        assertThat(r2.skipped).isFalse()

        val after2 = codeNodes(ctx.db)
        val names2 = after2.map { it.first }
        // ordinals stay a contiguous 0..N-1 range after the incremental rebuild
        assertThat(after2.map { it.second }).containsExactlyElementsOf(after2.indices.toList())
        // A retained, B gained c, b still present
        assertThat(names2).anySatisfy { assertThat(it).contains("A#a") }
        assertThat(names2).anySatisfy { assertThat(it).contains("B#b") }
        assertThat(names2).anySatisfy { assertThat(it).contains("B#c") }
        assertThat(after2.size).isEqualTo(after1.size + 1)
        assertThat(File(ctx.indexDir, "hnsw/code.hnsw")).exists()

        base.deleteRecursively()
    }

    @Test
    fun `deleting a file removes its nodes and keeps ordinals contiguous`() {
        val base = Files.createTempDirectory("hyindex-inc-del").toFile()
        val decompile = File(base, "decompiled").apply { mkdirs() }
        writeJava(decompile, "com/hypixel/hytale/A.java", "package com.hypixel.hytale; class A { void a() { int x = 1; } }")
        writeJava(decompile, "com/hypixel/hytale/B.java", "package com.hypixel.hytale; class B { void b() { int y = 2; } }")
        val ctx = ctxFor(base, decompile)
        CodeIndexer(ctx).index()

        File(decompile, "com/hypixel/hytale/B.java").delete()
        val r = CodeIndexer(ctx).index()
        assertThat(r.skipped).isFalse()

        val after = codeNodes(ctx.db)
        val names = after.map { it.first }
        assertThat(names).noneSatisfy { assertThat(it).contains("B#") }
        assertThat(names).anySatisfy { assertThat(it).contains("A#a") }
        assertThat(after.map { it.second }).containsExactlyElementsOf(after.indices.toList())

        base.deleteRecursively()
    }
}
