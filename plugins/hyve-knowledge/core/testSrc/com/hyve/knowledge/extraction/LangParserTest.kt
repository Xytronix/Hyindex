package com.hyve.knowledge.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LangParserTest {

    @Test
    fun `parses key=value with comments, continuation, first-equals`() {
        val text = "# header\n\nMorning.name = Bedhead\nx.msg = a={b}, c=d\nwrap = line1 \\\n    line2\n"
        val m = LangParser.parse(text)
        assertThat(m["Morning.name"]).isEqualTo("Bedhead")
        assertThat(m["x.msg"]).isEqualTo("a={b}, c=d")
        assertThat(m["wrap"]).isEqualTo("line1 line2")
    }

    @Test
    fun `derivePrefix from Languages-relative path`() {
        assertThat(LangParser.derivePrefix("Server/Languages/en-US/server.lang")).isEqualTo("server")
        assertThat(LangParser.derivePrefix("Common/Languages/en-US/avatarCustomization/faces.lang"))
            .isEqualTo("avatarCustomization.faces")
    }

    @Test
    fun `strips BOM`() {
        val bom = "﻿key = value"
        val m = LangParser.parse(bom)
        assertThat(m["key"]).isEqualTo("value")
    }

    @Test
    fun `value containing equals uses first equals only`() {
        val m = LangParser.parse("eq.key = a=b=c")
        assertThat(m["eq.key"]).isEqualTo("a=b=c")
    }
}
