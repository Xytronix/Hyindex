// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.db.KnowledgeDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GraphTraversalTest {

    private lateinit var db: KnowledgeDatabase
    private lateinit var traversal: GraphTraversal
    private lateinit var tempFile: File

    @BeforeEach
    fun setUp() {
        tempFile = Files.createTempFile("knowledge_test_", ".db").toFile()
        tempFile.deleteOnExit()
        db = KnowledgeDatabase.forFile(tempFile)

        for ((id, name, type) in listOf(
            Triple("gamedata:item:torch", "Torch", "item"),
            Triple("gamedata:item:wood_log", "Wood_Log", "item"),
            Triple("gamedata:item:resin", "Resin", "item"),
            Triple("gamedata:drop:goblin_drop", "Drop_Goblin", "drop"),
            Triple("gamedata:item:gold_coin", "Gold_Coin", "item"),
            Triple("gamedata:item:workbench", "Workbench", "item"),
            Triple("gamedata:shop:blacksmith", "Blacksmith", "shop"),
            Triple("gamedata:npc:goblin", "NPC_Goblin", "npc"),
            Triple("gamedata:npc:goblin_archer", "NPC_Goblin_Archer", "npc"),
            Triple("gamedata:npc:npc_blacksmith", "NPC_Blacksmith", "npc"),
            Triple("gamedata:group:goblin_party", "GoblinRaidingParty", "npc_group"),
            Triple("gamedata:recipe:torch_recipe", "TorchRecipe", "recipe"),
        )) {
            db.execute(
                "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, data_type, corpus) VALUES (?, 'GameData', ?, 'test.json', ?, 'gamedata')",
                id, name, type,
            )
        }

        for ((id, name) in listOf(
            "ui:InGame/CraftingScreen.ui" to "CraftingScreen",
            "ui:InGame/ShopPanel.ui" to "ShopPanel",
        )) {
            db.execute(
                "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'ui', ?, 'test.ui', 'client')",
                id, name,
            )
        }

        for ((src, tgt, type) in listOf(
            Triple("gamedata:item:torch", "gamedata:item:wood_log", "REQUIRES_ITEM"),
            Triple("gamedata:item:torch", "gamedata:item:resin", "REQUIRES_ITEM"),
            Triple("gamedata:npc:goblin", "gamedata:drop:goblin_drop", "DROPS_ON_DEATH"),
            Triple("gamedata:drop:goblin_drop", "gamedata:item:gold_coin", "DROPS_ITEM"),
            Triple("gamedata:shop:blacksmith", "gamedata:item:torch", "OFFERED_IN_SHOP"),
            Triple("gamedata:group:goblin_party", "gamedata:npc:goblin", "HAS_MEMBER"),
            Triple("gamedata:group:goblin_party", "gamedata:npc:goblin_archer", "HAS_MEMBER"),
            Triple("gamedata:recipe:torch_recipe", "gamedata:item:wood_log", "REQUIRES_ITEM"),
            Triple("gamedata:recipe:torch_recipe", "gamedata:item:torch", "PRODUCES_ITEM"),
            Triple("ui:InGame/CraftingScreen.ui", "gamedata:item:workbench", "UI_BINDS_TO"),
            Triple("ui:InGame/ShopPanel.ui", "gamedata:item:gold_coin", "UI_BINDS_TO"),
            Triple("ui:InGame/ShopPanel.ui", "gamedata:npc:npc_blacksmith", "UI_BINDS_TO"),
        )) {
            db.execute(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, ?)",
                src, tgt, type,
            )
        }

        traversal = GraphTraversal(db)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        tempFile.delete()
    }

    @Test
    fun `findRecipeInputs returns ingredients for Torch`() {
        val results = traversal.findRecipeInputs("gamedata:item:torch")
        val ids = results.map { it.nodeId }
        assertTrue(ids.contains("gamedata:item:wood_log"))
        assertTrue(ids.contains("gamedata:item:resin"))
    }

    @Test
    fun `findRecipeOutputs returns the output item for a recipe`() {
        val results = traversal.findRecipeOutputs("gamedata:recipe:torch_recipe")
        val ids = results.map { it.nodeId }
        assertTrue(ids.contains("gamedata:item:torch"))
        assertFalse(ids.contains("gamedata:item:wood_log"))
    }

    @Test
    fun `findRecipeOutputs returns the recipe that produces an item`() {
        val results = traversal.findRecipeOutputs("gamedata:item:torch")
        val ids = results.map { it.nodeId }
        assertTrue(ids.contains("gamedata:recipe:torch_recipe"))
    }

    @Test
    fun `findRecipeInputs returns ingredients for a recipe but not its output`() {
        val results = traversal.findRecipeInputs("gamedata:recipe:torch_recipe")
        val ids = results.map { it.nodeId }
        assertTrue(ids.contains("gamedata:item:wood_log"))
        assertFalse(ids.contains("gamedata:item:torch"))
    }

    @Test
    fun `findDropsFrom performs two-hop traversal from goblin to dropped items`() {
        val results = traversal.findDropsFrom("gamedata:npc:goblin")
        assertEquals(1, results.size)
        assertEquals("gamedata:item:gold_coin", results[0].nodeId)
    }

    @Test
    fun `findShopsSellingItem returns shops offering a given item`() {
        val results = traversal.findShopsSellingItem("gamedata:item:torch")
        assertEquals(1, results.size)
        assertEquals("gamedata:shop:blacksmith", results[0].nodeId)
    }

    @Test
    fun `findGroupMembers returns NPCs in a group`() {
        val results = traversal.findGroupMembers("gamedata:group:goblin_party")
        val ids = results.map { it.nodeId }
        assertEquals(2, results.size)
        assertTrue(ids.contains("gamedata:npc:goblin"))
        assertTrue(ids.contains("gamedata:npc:goblin_archer"))
    }

    @Test
    fun `findUIBindings returns gamedata entities bound to ShopPanel`() {
        val results = traversal.findUIBindings("ui:InGame/ShopPanel.ui")
        val ids = results.map { it.nodeId }
        assertEquals(2, results.size)
        assertTrue(ids.contains("gamedata:item:gold_coin"))
        assertTrue(ids.contains("gamedata:npc:npc_blacksmith"))
        assertTrue(results.all { it.corpus == "gamedata" })
    }

    @Test
    fun `findUIBindings returns single result for CraftingScreen`() {
        val results = traversal.findUIBindings("ui:InGame/CraftingScreen.ui")
        assertEquals(1, results.size)
        assertEquals("gamedata:item:workbench", results[0].nodeId)
    }

    @Test
    fun `findUIForGamedata returns UI screens that bind to Workbench`() {
        val results = traversal.findUIForGamedata("gamedata:item:workbench")
        assertEquals(1, results.size)
        assertEquals("ui:InGame/CraftingScreen.ui", results[0].nodeId)
        assertTrue(results.all { it.corpus == "client" })
    }

    @Test
    fun `findUIForGamedata returns ShopPanel for Gold_Coin`() {
        val results = traversal.findUIForGamedata("gamedata:item:gold_coin")
        assertEquals(1, results.size)
        assertEquals("ui:InGame/ShopPanel.ui", results[0].nodeId)
    }

    @Test
    fun `findUIForGamedata returns empty for gamedata node with no UI bindings`() {
        val results = traversal.findUIForGamedata("gamedata:item:resin")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findImplementingCode returns Java classes for gamedata node`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/ItemManager.java', 'code')",
            "class:com.hytale.ItemManager", "ItemManager",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'IMPLEMENTED_BY')",
            "gamedata:item:torch", "class:com.hytale.ItemManager",
        )

        val results = traversal.findImplementingCode("gamedata:item:torch")
        assertEquals(1, results.size)
        assertEquals("class:com.hytale.ItemManager", results[0].nodeId)
        assertEquals("code", results[0].corpus)
    }

    @Test
    fun `findGamedataForCode returns gamedata for Java class`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/ShopSystem.java', 'code')",
            "class:com.hytale.ShopSystem", "ShopSystem",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'IMPLEMENTED_BY')",
            "gamedata:shop:blacksmith", "class:com.hytale.ShopSystem",
        )

        val results = traversal.findGamedataForCode("class:com.hytale.ShopSystem")
        assertEquals(1, results.size)
        assertEquals("gamedata:shop:blacksmith", results[0].nodeId)
        assertEquals("gamedata", results[0].corpus)
    }

    @Test
    fun `findDocsReferences returns code and gamedata nodes for doc`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'doc', ?, 'docs/crafting.md', 'docs')",
            "docs:crafting-guide", "CraftingGuide",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/CraftingManager.java', 'code')",
            "class:CraftingManager", "CraftingManager",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'DOCS_REFERENCES')",
            "docs:crafting-guide", "class:CraftingManager",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'DOCS_REFERENCES')",
            "docs:crafting-guide", "gamedata:item:torch",
        )

        val results = traversal.findDocsReferences("docs:crafting-guide")
        assertEquals(2, results.size)
        val nodeIds = results.map { it.nodeId }.toSet()
        assertTrue(nodeIds.contains("class:CraftingManager"))
        assertTrue(nodeIds.contains("gamedata:item:torch"))
    }

    @Test
    fun `findImplementingCode returns empty for node with no code links`() {
        val results = traversal.findImplementingCode("gamedata:item:resin")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findDocsReferences returns empty for doc with no references`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'doc', ?, 'docs/intro.md', 'docs')",
            "docs:intro", "Introduction",
        )
        val results = traversal.findDocsReferences("docs:intro")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findByRelation returns classes extending a base class`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/BaseItem.java', 'code')",
            "class:com.hytale.BaseItem", "BaseItem",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/SwordItem.java', 'code')",
            "class:com.hytale.SwordItem", "SwordItem",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'EXTENDS')",
            "class:com.hytale.SwordItem", "class:com.hytale.BaseItem",
        )

        val results = traversal.findByRelation("BaseItem", "EXTENDS")
        assertTrue(results.isNotEmpty())
        assertEquals("class:com.hytale.SwordItem", results[0].nodeId)
    }

    @Test
    fun `findByRelation tries reverse direction when forward yields nothing`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/AbstractController.java', 'code')",
            "class:com.hytale.AbstractController", "AbstractController",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/NpcController.java', 'code')",
            "class:com.hytale.NpcController", "NpcController",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'EXTENDS')",
            "class:com.hytale.NpcController", "class:com.hytale.AbstractController",
        )

        val results = traversal.findByRelation("NpcController", "EXTENDS")
        assertTrue(results.isNotEmpty())
        assertEquals("class:com.hytale.AbstractController", results[0].nodeId)
    }

    @Test
    fun `findByName returns exact match before prefix match`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus, data_type) VALUES (?, 'JavaMethod', ?, 'src/Torch.java', 'code', NULL)",
            "method:Torch#light", "Torch#light",
        )

        val results = traversal.findByName("Torch")
        assertTrue(results.isNotEmpty())
        assertEquals("Torch", results[0].displayName)
    }

    @Test
    fun `findCallers returns methods that call the given method`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/Caller.java', 'code')",
            "com.hytale.Caller#run", "Caller#run",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/Callee.java', 'code')",
            "com.hytale.Callee#ping", "Callee#ping",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'CALLS')",
            "com.hytale.Caller#run", "com.hytale.Callee#ping",
        )

        val callers = traversal.findCallers("com.hytale.Callee#ping")
        assertEquals(1, callers.size)
        assertEquals("com.hytale.Caller#run", callers[0].nodeId)

        val callees = traversal.findCallees("com.hytale.Callee#ping")
        assertTrue(callees.isEmpty(), "Callee#ping calls nothing in this fixture")
    }

    @Test
    fun `findCallees returns methods that the given method calls`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/Caller.java', 'code')",
            "com.hytale.Caller#run", "Caller#run",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/Callee.java', 'code')",
            "com.hytale.Callee#ping", "Callee#ping",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'CALLS')",
            "com.hytale.Caller#run", "com.hytale.Callee#ping",
        )

        val callees = traversal.findCallees("com.hytale.Caller#run")
        assertEquals(1, callees.size)
        assertEquals("com.hytale.Callee#ping", callees[0].nodeId)

        val callers = traversal.findCallers("com.hytale.Caller#run")
        assertTrue(callers.isEmpty(), "nothing calls Caller#run in this fixture")
    }

    @Test
    fun `findCallers given a class id rolls up method-precise callers of its members`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/Foo.java', 'code')",
            "class:com.hytale.Foo", "Foo",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/Foo.java', 'code')",
            "com.hytale.Foo#bar", "Foo#bar",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/A.java', 'code')",
            "com.hytale.A#m", "A#m",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/B.java', 'code')",
            "com.hytale.B#m", "B#m",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'CALLS')",
            "com.hytale.A#m", "com.hytale.Foo#bar",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'CALLS')",
            "com.hytale.B#m", "class:com.hytale.Foo",
        )

        val ids = traversal.findCallers("class:com.hytale.Foo").map { it.nodeId }.toSet()
        assertEquals(
            setOf("com.hytale.A#m", "com.hytale.B#m"), ids,
            "class-level findCallers must include the method-precise caller (A#m) and the class-granular caller (B#m)",
        )
    }

    @Test
    fun `findCallees falls back to the class node when a method-precise target has no node`() {
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaMethod', ?, 'src/Caller.java', 'code')",
            "com.hytale.Caller#run", "Caller#run",
        )
        db.execute(
            "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'JavaClass', ?, 'src/Bar.java', 'code')",
            "class:com.hytale.Bar", "Bar",
        )
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'CALLS')",
            "com.hytale.Caller#run", "com.hytale.Bar#<init>",
        )

        val callees = traversal.findCallees("com.hytale.Caller#run")
        assertEquals(1, callees.size)
        assertEquals(
            "class:com.hytale.Bar", callees[0].nodeId,
            "a method-precise target with no method node must fall back to its class node",
        )
    }

    @Test
    fun `findRecipeInputs with virtual reference target returns no node`() {
        db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, target_resolved) VALUES (?, ?, 'REQUIRES_ITEM', 0)",
            "gamedata:item:torch", "virtual:resource:WoodResin",
        )

        val results = traversal.findRecipeInputs("gamedata:item:torch")
        val nodeIds = results.map { it.nodeId }
        assertFalse(nodeIds.contains("virtual:resource:WoodResin"))
        assertTrue(nodeIds.contains("gamedata:item:wood_log"))
    }
}
