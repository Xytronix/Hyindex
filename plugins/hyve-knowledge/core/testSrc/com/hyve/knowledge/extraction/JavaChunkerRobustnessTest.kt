// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.extraction

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files


class JavaChunkerRobustnessTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `inline javadoc on real source is preserved in content`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Doc {
                /** Spawns an NPC. */
                public void spawn() {}
            }
        """.trimIndent()
        val file = File(tempDir, "Doc.java")
        file.writeText(source)
        val spawn = JavaChunker.chunkFile(file).firstOrNull { it.methodName == "spawn" }
        assertNotNull(spawn)

        assertTrue(spawn!!.content.contains("Spawns an NPC"), "javadoc should be inline in content")
        assertTrue(spawn.embeddingText.contains("Spawns an NPC"), "inline javadoc should flow into the embedding")
    }


    @Test
    fun `chunk lineStart points at the leading javadoc so it matches the returned content`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Doc {
                /**
                 * Adds two integers and returns the sum.
                 */
                public int add(int a, int b) { return a + b; }
            }
        """.trimIndent()
        val file = File(tempDir, "DocLine.java")
        file.writeText(source)
        val add = JavaChunker.chunkFile(file).single { it.methodName == "add" }

        assertTrue(add.content.contains("Adds two integers"), "javadoc should be inline in content")

        val lines = source.lines()
        assertTrue(
            lines[add.lineStart - 1].contains("/**"),
            "lineStart must point at the javadoc line, but pointed at: '${lines[add.lineStart - 1]}'",
        )
    }

    @Test
    fun `a file with a local record declaration is parsed, not dropped`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class RecordHolder {
                public void use() {
                    record Pair(int a, int b) {}
                    Pair p = new Pair(1, 2);
                    System.out.println(p);
                }
            }
        """.trimIndent()
        val file = File(tempDir, "RecordHolder.java")
        file.writeText(source)

        val chunks = JavaChunker.chunkFile(file)

        val use = chunks.firstOrNull { it.methodName == "use" }
        assertNotNull(use, "expected the 'use' method to be chunked; file was dropped instead")
        assertEquals("JavaMethod", use!!.nodeType)
    }

    @Test
    fun `methods of a top-level record are chunked`() {
        val source = """
            package com.hypixel.hytale.demo;
            public record Point(int x, int y) {
                public int sum() { return this.x + this.y; }
            }
        """.trimIndent()
        val file = File(tempDir, "Point.java")
        file.writeText(source)

        val chunks = JavaChunker.chunkFile(file)

        assertTrue(chunks.any { it.methodName == "sum" }, "expected record method 'sum' to be chunked")
    }


    @Test
    fun `an unparseable file with a recognizable type yields one file-level fallback node`() {
        val source = """
            package com.hypixel.hytale.component;
            public class Store {
                public void broken() {
                    int x = ;
                }
            }
        """.trimIndent()
        val file = File(tempDir, "Store.java")
        file.writeText(source)

        val chunks = JavaChunker.chunkFile(file)

        assertEquals(1, chunks.size, "expected exactly one fallback chunk for an unparseable file")
        val fallback = chunks.first()
        assertEquals("com.hypixel.hytale.component.Store", fallback.id)
        assertEquals("com.hypixel.hytale.component.Store", fallback.className)
        assertEquals("com.hypixel.hytale.component", fallback.packageName)
        assertEquals("JavaFile", fallback.nodeType)
        assertTrue(fallback.content.contains("class Store"), "fallback content keeps the raw source")
    }

    @Test
    fun `a method-less type with a type keyword in a comment uses the real AST type name`() {
        val source = """
            package com.hypixel.hytale.assetstore;
            // a class that holds constants
            public interface AssetConstants {
                String NAME = "x";
            }
        """.trimIndent()
        val file = File(tempDir, "AssetConstants.java")
        file.writeText(source)

        val chunk = JavaChunker.chunkFile(file).single()

        assertEquals("JavaType", chunk.nodeType)
        assertEquals(
            "com.hypixel.hytale.assetstore.AssetConstants", chunk.id,
            "must use the parsed type name, not a keyword pulled from a comment",
        )
    }

    @Test
    fun `an unparseable file ignores type keywords inside comments when naming the fallback`() {
        val source = """
            package com.hypixel.hytale.x;
            // a class that does stuff
            public class Broken { void m() { int y = ; } }
        """.trimIndent()
        val file = File(tempDir, "Broken.java")
        file.writeText(source)

        val chunk = JavaChunker.chunkFile(file).single()

        assertEquals("JavaFile", chunk.nodeType)
        assertEquals(
            "com.hypixel.hytale.x.Broken", chunk.id,
            "must not name the node after 'that' from the comment",
        )
    }


    @Test
    fun `a method-less enum yields one type-level node`() {
        val source = """
            package com.hypixel.hytale.component;
            public enum RemoveReason { ENTITY_DESTROYED, CHUNK_UNLOADED, MANUAL }
        """.trimIndent()
        val file = File(tempDir, "RemoveReason.java")
        file.writeText(source)

        val chunks = JavaChunker.chunkFile(file)

        assertEquals(1, chunks.size, "a method-less type should still produce one node")
        val node = chunks.first()
        assertEquals("com.hypixel.hytale.component.RemoveReason", node.id)
        assertEquals("JavaType", node.nodeType)
        assertTrue(node.content.contains("enum RemoveReason"))
    }

    @Test
    fun `a marker interface with no members yields a type-level node`() {
        val source = """
            package com.hypixel.hytale.component.system;
            public interface EcsEvent {}
        """.trimIndent()
        val file = File(tempDir, "EcsEvent.java")
        file.writeText(source)

        val chunks = JavaChunker.chunkFile(file)

        assertEquals(1, chunks.size)
        assertEquals("JavaType", chunks.first().nodeType)
    }

    @Test
    fun `genuinely unparseable source with no recognizable type stays empty`() {
        val file = File(tempDir, "Garbage.java")
        file.writeText("this is not valid java {{{")

        val chunks = JavaChunker.chunkFile(file)

        assertTrue(chunks.isEmpty(), "garbage with no detectable type must not produce a node")
    }

    @Test
    fun `class javadoc rides on a method-less type node`() {
        val dir = Files.createTempDirectory("jc").toFile()
        val f = File(dir, "Marker.java").apply { writeText(
            """
            package com.hypixel.hytale.demo;
            /** A marker interface for taggable things. */
            public interface Marker {}
            """.trimIndent()) }
        val chunk = JavaChunker.chunkFile(f).single()
        assertEquals("JavaType", chunk.nodeType)
        assertTrue(chunk.content.contains("marker interface"), "class javadoc should be inline in the type node content")
        dir.deleteRecursively()
    }

    @Test
    fun `a public method captures public visibility facets`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Vis {
                public void run() { doThing(); somethingElse(); }
            }
        """.trimIndent()
        val file = File(tempDir, "Vis.java")
        file.writeText(source)
        val run = JavaChunker.chunkFile(file).single { it.methodName == "run" }
        assertEquals("public", run.visibility)
        assertFalse(run.isStatic)
        assertFalse(run.isAbstract)
        assertTrue(run.annotations.isEmpty())
    }

    @Test
    fun `a one-line return delegate is flagged thin`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Thin {
                public int swapItems(int a, int b) { return fetcher.get().swapItems(a, b); }
            }
        """.trimIndent()
        val file = File(tempDir, "Thin.java")
        file.writeText(source)
        val swap = JavaChunker.chunkFile(file).single { it.methodName == "swapItems" }
        assertTrue(swap.thin, "a single return-of-call body should be thin")
    }

    @Test
    fun `a multi-statement method is not thin`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Fat {
                public int compute(int a, int b) { int c = a + b; return c * 2; }
            }
        """.trimIndent()
        val file = File(tempDir, "Fat.java")
        file.writeText(source)
        val compute = JavaChunker.chunkFile(file).single { it.methodName == "compute" }
        assertFalse(compute.thin, "a multi-statement body must not be thin")
    }

    @Test
    fun `an annotated private static method captures its facets`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Facets {
                @Override
                private static int helper(int a) { int x = a; return x; }
            }
        """.trimIndent()
        val file = File(tempDir, "Facets.java")
        file.writeText(source)
        val helper = JavaChunker.chunkFile(file).single { it.methodName == "helper" }
        assertEquals("private", helper.visibility)
        assertTrue(helper.isStatic)
        assertTrue(helper.annotations.contains("Override"), "leading annotation simple name should be captured")
    }

    @Test
    fun `overloaded methods get distinct ids so neither is dropped at index time`() {
        val source = """
            package com.hypixel.hytale.demo;
            public class Over {
                public void add(int a) {}
                public void add(String a) {}
                public void add(int a, int b) {}
            }
        """.trimIndent()
        val file = File(tempDir, "Over.java")
        file.writeText(source)

        val adds = JavaChunker.chunkFile(file).filter { it.methodName == "add" }

        assertEquals(3, adds.size, "every overload should be chunked")
        assertEquals(3, adds.map { it.id }.toSet().size, "overloads must have distinct ids or INSERT OR REPLACE collapses them")
        assertTrue(
            adds.any { it.id == "com.hypixel.hytale.demo.Over#add" },
            "the first-declared overload keeps the plain id so existing edges still resolve",
        )
    }

    @Test
    fun `in-source javadoc is inline in method content`() {
        val dir = Files.createTempDirectory("jc").toFile()
        val f = File(dir, "Foo.java").apply { parentFile.mkdirs(); writeText(
            """
            package com.hypixel.hytale.demo;
            /** Type doc for Foo. */
            public class Foo {
                /** Adds two numbers.
                 * @param a first
                 * @return the sum
                 */
                public int add(int a, int b) { return a + b; }
            }
            """.trimIndent()) }
        val chunks = JavaChunker.chunkFile(f)
        val add = chunks.single { it.methodName == "add" }
        assertTrue(add.content.contains("Adds two numbers"), "method javadoc should be inline in content")
        dir.deleteRecursively()
    }

    @Test
    fun `a class with a nested interface and a brace-nested static block is parsed, not dropped`() {
        val source = """
            package com.hypixel.hytale.logger;
            public class HytaleLogger {
                static {
                    if (!ready()) {
                        throw new IllegalStateException("not ready");
                    }
                }
                public interface Api {}
                public void log(String msg) { backend.log(msg); }
            }
        """.trimIndent()
        val file = File(tempDir, "HytaleLogger.java")
        file.writeText(source)

        val chunks = JavaChunker.chunkFile(file)

        val log = chunks.firstOrNull { it.methodName == "log" }
        assertNotNull(log, "a class static block with nested braces must not corrupt parsing when the file also declares an interface")
        assertEquals("JavaMethod", log!!.nodeType)
    }
}
