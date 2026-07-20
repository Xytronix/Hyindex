// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class GitSourceProviderAuthTest {

    @Test
    fun `authArgs builds an http extraHeader with base64 basic auth when token present`() {
        val expected = Base64.getEncoder().encodeToString(("x-access-token:ghp_x").toByteArray())
        assertEquals(
            listOf("-c", "http.extraHeader=Authorization: Basic $expected"),
            GitSourceProvider.authArgs("ghp_x"),
        )
    }

    @Test
    fun `authArgs returns empty list when token is null`() {
        assertEquals(emptyList<String>(), GitSourceProvider.authArgs(null))
    }
}
