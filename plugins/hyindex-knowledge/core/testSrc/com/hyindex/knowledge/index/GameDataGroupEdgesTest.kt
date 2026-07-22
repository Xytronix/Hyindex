// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GameDataGroupEdgesTest {
    private val roles = mapOf(
        "kweebec_razorleaf" to listOf("gamedata:role:Kweebec_Razorleaf"),
        "kweebec_merchant" to listOf("gamedata:role:Kweebec_Merchant"),
        "chicken" to listOf("gamedata:role:Chicken"),
        "wolf" to listOf("gamedata:role:Wolf"),
    )

    @Test
    fun `matchGroupRoles expands include globs against role names`() {
        val matched = matchGroupRoles(listOf("Kweebec*", "*Chicken*"), emptyList(), roles)
        assertEquals(
            setOf(
                "gamedata:role:Kweebec_Razorleaf",
                "gamedata:role:Kweebec_Merchant",
                "gamedata:role:Chicken",
            ),
            matched,
        )
    }

    @Test
    fun `matchGroupRoles honors exclude globs`() {
        val matched = matchGroupRoles(listOf("Kweebec*"), listOf("Kweebec_Merchant"), roles)
        assertEquals(setOf("gamedata:role:Kweebec_Razorleaf"), matched)
    }

    @Test
    fun `matchGroupRoles returns empty when there are no include globs`() {
        assertTrue(matchGroupRoles(emptyList(), listOf("Kweebec*"), roles).isEmpty())
    }

    @Test
    fun `globToRegex handles prefix suffix contains and exact`() {
        assertTrue(globToRegex("Kweebec*").matches("kweebec_elder"))
        assertTrue(globToRegex("*Chicken*").matches("robo_chicken_v2"))
        assertTrue(globToRegex("*_Piglet").matches("boar_piglet"))
        assertTrue(globToRegex("Mouse").matches("mouse"))
        assertFalse(globToRegex("Mouse").matches("mouse_field"))
    }
}
