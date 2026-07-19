package com.hyve.knowledge.index

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.EmbeddingCacheDatabase
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.index.EmbeddingCacheService
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.logging.StdoutLogProvider
import com.hyve.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CodeIndexerTest {
    @Test fun `indexes a single decompiled java method into the code corpus`() {
        val base = Files.createTempDirectory("hyve-code").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Demo.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale;\npublic class Demo { public int add(int a,int b){ return a+b; } }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val result = CodeIndexer(ctx).index()

        assertTrue(result.ok, "expected success, got ${result.error}")
        val count = db.query("SELECT COUNT(*) FROM nodes WHERE corpus='code'") { it.getInt(1) }.firstOrNull() ?: 0
        assertTrue(count >= 1, "expected >=1 code node, got $count")
        db.close()
    }

    @Test fun `both overloads of a method persist as distinct code nodes`() {
        val base = Files.createTempDirectory("hyve-code-overload").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Over.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale;\npublic class Over { public void add(int a){} public void add(String a){} }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val count = db.query(
            "SELECT COUNT(*) FROM nodes WHERE corpus='code' AND display_name='Over#add'"
        ) { it.getInt(1) }.firstOrNull() ?: 0
        assertEquals(2, count, "both overloads must persist as distinct nodes, not be collapsed by INSERT OR REPLACE")
        db.close()
    }

    @Test fun `writes per-method facet metadata json to the node metadata column`() {
        val base = Files.createTempDirectory("hyve-code-meta").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Demo.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale;\npublic class Demo { public int add(int a,int b){ return wrap(a,b); } }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val metadata = db.query(
            "SELECT metadata FROM nodes WHERE id = 'com.hypixel.hytale.Demo#add'"
        ) { it.getString(1) }.firstOrNull()
        assertTrue(metadata != null, "method node must have metadata json")
        assertTrue(metadata!!.contains("\"visibility\":\"public\""), "metadata must record visibility, got $metadata")
        assertTrue(metadata.contains("\"thin\":true"), "single return-of-call body must be thin, got $metadata")
        db.close()
    }

    @Test fun `indexes an unparseable file as a file-level fallback node`() {
        val base = Files.createTempDirectory("hyve-code-fallback").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/component/Store.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale.component;\npublic class Store { void broken() { int x = ; } }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val result = CodeIndexer(ctx).index()

        assertTrue(result.ok, "expected success, got ${result.error}")
        val rows = db.query(
            "SELECT display_name FROM nodes WHERE id = 'com.hypixel.hytale.component.Store' AND node_type = 'JavaFile'"
        ) { it.getString(1) }
        assertEquals(1, rows.size, "expected one JavaFile fallback node for the unparseable file")
        assertEquals("Store", rows.first())
        db.close()
    }

    @Test fun `a sealed type emits PERMITS edges to its permitted subtypes`() {
        val base = Files.createTempDirectory("hyve-permits").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Shape.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText(
            "package com.hypixel.hytale;\n" +
                "public sealed interface Shape permits Circle, Square { double area(); }\n" +
                "final class Circle implements Shape { public double area(){ return 1; } }\n" +
                "final class Square implements Shape { public double area(){ return 2; } }\n"
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val shapeExists = db.query("SELECT COUNT(*) FROM nodes WHERE id = 'class:com.hypixel.hytale.Shape'") { it.getInt(1) }.first()
        assertEquals(1, shapeExists, "the sealed type must parse and produce a class node")
        val permits = db.query(
            "SELECT target_id FROM edges WHERE edge_type = 'PERMITS' AND source_id = 'class:com.hypixel.hytale.Shape'"
        ) { it.getString(1) }.toSet()
        assertTrue("class:com.hypixel.hytale.Circle" in permits, "expected PERMITS Circle; got $permits")
        assertTrue("class:com.hypixel.hytale.Square" in permits, "expected PERMITS Square; got $permits")
        db.close()
    }

    @Test fun `captures sealed and non-sealed modifiers on the class node`() {
        val base = Files.createTempDirectory("hyve-sealed-mod").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Shape.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText(
            "package com.hypixel.hytale;\n" +
                "public sealed interface Shape permits Sub { double area(); }\n" +
                "non-sealed interface Sub extends Shape { default double area(){ return 0; } }\n"
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val shapeMeta = db.query("SELECT metadata FROM nodes WHERE id = 'class:com.hypixel.hytale.Shape'") { it.getString(1) }.firstOrNull()
        val subMeta = db.query("SELECT metadata FROM nodes WHERE id = 'class:com.hypixel.hytale.Sub'") { it.getString(1) }.firstOrNull()
        assertTrue(shapeMeta != null && shapeMeta.contains("sealed"), "Shape must record the sealed modifier; got $shapeMeta")
        assertTrue(subMeta != null && subMeta.contains("non-sealed"), "Sub must record the non-sealed modifier; got $subMeta")
        db.close()
    }

    @Test fun `emits an INSTANCEOF edge from a method to the checked type`() {
        val base = Files.createTempDirectory("hyve-instanceof").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Widget.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText(
            "package com.hypixel.hytale;\n" +
                "public class Widget { public int id(){ return 1; } }\n" +
                "class Checker { public boolean test(Object o){ return o instanceof Widget; } }\n"
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val targets = db.query(
            "SELECT target_id FROM edges WHERE edge_type = 'INSTANCEOF' AND source_id = 'com.hypixel.hytale.Checker#test'"
        ) { it.getString(1) }.toSet()
        assertTrue("class:com.hypixel.hytale.Widget" in targets, "expected an INSTANCEOF edge to Widget; got $targets")
        db.close()
    }

    @Test fun `emits DECLARED_IN edges from each method node to its class node`() {
        val base = Files.createTempDirectory("hyve-declared-in").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Demo.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale;\npublic class Demo { public int add(int a,int b){ return a+b; } public int sub(int a,int b){ return a-b; } }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val edges = db.query(
            "SELECT source_id, owning_file_id FROM edges WHERE edge_type = 'DECLARED_IN' AND target_id = 'class:com.hypixel.hytale.Demo'"
        ) { it.getString(1) to it.getString(2) }
        val sources = edges.map { it.first }.toSet()
        assertTrue("com.hypixel.hytale.Demo#add" in sources, "expected DECLARED_IN edge for add, got $sources")
        assertTrue("com.hypixel.hytale.Demo#sub" in sources, "expected DECLARED_IN edge for sub, got $sources")
        assertTrue(edges.all { it.second == "com/hypixel/hytale/Demo.java" }, "each edge must carry the owning-file key, got ${edges.map { it.second }}")
        db.close()
    }

    @Test fun `nested class relations use the dotted FQN so IMPLEMENTS edges resolve to the class node`() {
        val base = Files.createTempDirectory("hyve-nested-fqn").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Outer.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText(
            "package com.hypixel.hytale;\n" +
                "public class Outer {\n" +
                "  public interface Leaf { void f(); }\n" +
                "  public static class Inner implements Leaf { public void f(){} }\n" +
                "}\n"
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val implSources = db.query("SELECT source_id FROM edges WHERE edge_type = 'IMPLEMENTS'") { it.getString(1) }
        assertTrue(
            "class:com.hypixel.hytale.Outer.Inner" in implSources,
            "nested IMPLEMENTS source must be the dotted FQN, not the simple name; got $implSources",
        )
        val nodeCount = db.query(
            "SELECT COUNT(*) FROM nodes WHERE id = 'class:com.hypixel.hytale.Outer.Inner'"
        ) { it.getInt(1) }.first()
        assertEquals(1, nodeCount, "the nested class node must exist so the edge is not orphaned")
        db.close()
    }

    @Test fun `emits no DECLARED_IN edge for a class with no methods`() {
        val base = Files.createTempDirectory("hyve-declared-in-empty").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Empty.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale;\npublic class Empty { }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val count = db.query("SELECT COUNT(*) FROM edges WHERE edge_type = 'DECLARED_IN'") { it.getInt(1) }.firstOrNull() ?: 0
        assertEquals(0, count, "a class with no methods must produce no DECLARED_IN edge")
        db.close()
    }

    @Test fun `emits a CALLS edge from a calling method to a resolved callee method`() {
        val base = Files.createTempDirectory("hyve-calls").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val caller = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Caller.java")
        caller.parentFile.mkdirs()
        caller.writeText("package com.hypixel.hytale;\npublic class Caller { void run(){ new Callee().ping(); } }\n")
        val callee = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Callee.java")
        callee.writeText("package com.hypixel.hytale;\npublic class Callee { public void ping(){} }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val edges = db.query(
            "SELECT target_id, owning_file_id, target_resolved FROM edges WHERE edge_type = 'CALLS' AND source_id = 'com.hypixel.hytale.Caller#run'"
        ) { Triple(it.getString(1), it.getString(2), it.getInt(3)) }
        assertTrue(edges.isNotEmpty(), "expected a CALLS edge from Caller#run, got none")
        assertTrue(edges.all { it.second == "com/hypixel/hytale/Caller.java" }, "CALLS edge must carry the owning-file key, got ${edges.map { it.second }}")
        val resolved = edges.filter { it.third == 1 }
        assertTrue(resolved.any { it.first == "com.hypixel.hytale.Callee#ping" }, "expected a method-precise CALLS edge to Callee#ping, got ${edges.map { it.first }}")
        db.close()
    }

    @Test fun `unresolved CALLS callee is recorded with target_resolved zero and no crash`() {
        val base = Files.createTempDirectory("hyve-calls-unresolved").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val caller = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Lonely.java")
        caller.parentFile.mkdirs()
        caller.writeText("package com.hypixel.hytale;\npublic class Lonely { void run(){ new Nowhere().vanish(); } }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val edges = db.query(
            "SELECT target_resolved FROM edges WHERE edge_type = 'CALLS' AND source_id = 'com.hypixel.hytale.Lonely#run'"
        ) { it.getInt(1) }
        assertTrue(edges.isNotEmpty(), "expected a CALLS edge even for an unresolved callee")
        assertTrue(edges.all { it == 0 }, "an unresolved callee must set target_resolved=0, got $edges")
        db.close()
    }

    @Test fun `logs a CALLS resolution summary counting method-precise and class-granular edges`() {
        val base = Files.createTempDirectory("hyve-calls-summary").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val caller = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Caller.java")
        caller.parentFile.mkdirs()
        caller.writeText("package com.hypixel.hytale;\npublic class Caller { void run(){ new Callee().ping(); new Nowhere().vanish(); } }\n")
        val callee = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Callee.java")
        callee.writeText("package com.hypixel.hytale;\npublic class Callee { public void ping(){} }\n")
        val captured = mutableListOf<String>()
        val log = object : com.hyve.knowledge.core.logging.LogProvider {
            override fun info(message: String) { captured.add(message) }
            override fun warn(message: String, throwable: Throwable?) {}
            override fun error(message: String, throwable: Throwable?) {}
            override fun debug(message: String) {}
        }
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "expected success")

        val summary = captured.firstOrNull { it.startsWith("CALLS resolution:") }
        assertTrue(summary != null, "expected a CALLS resolution summary log line, got $captured")
        assertTrue(summary!!.contains("method-precise=2"), "expected two method-precise calls (Callee ctor + ping), got $summary")
        assertTrue(summary.contains("class-granular=1"), "expected one class-granular fallback, got $summary")
        db.close()
    }

    @Test fun `force re-chunks unchanged files instead of skipping`() {
        val base = Files.createTempDirectory("hyve-force").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Demo.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale;\npublic class Demo { public int add(int a,int b){ return a+b; } }\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(ctx).index().ok, "initial index should succeed")
        assertTrue(CodeIndexer(ctx).index().skipped, "re-index of unchanged files should skip")
        val forced = CodeIndexer(ctx).index(force = true)
        assertFalse(forced.skipped, "force should bypass the unchanged-skip")
        assertTrue(forced.indexed >= 1, "force should re-chunk at least one method")
        db.close()
    }
}
