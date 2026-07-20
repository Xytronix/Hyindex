package com.hyindex.knowledge.extraction

import com.hyindex.knowledge.core.extraction.GameDataType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameDataClassifyTest {
    private fun t(p: String) = GameDataParser.classifyForTest(p)

    @Test fun `recovers dropped Server categories and never silently drops under Server`() {
        assertThat(t("Server/Audio/SoundEvents/Foo.json")).isEqualTo(GameDataType.AUDIO)
        assertThat(t("Server/NPC/Spawn/World/Foo.json")).isEqualTo(GameDataType.NPC_SPAWN)
        assertThat(t("Server/ProjectileConfigs/Foo.json")).isEqualTo(GameDataType.PROJECTILE_CONFIG)
        assertThat(t("Server/Models/Foo.json")).isEqualTo(GameDataType.MODEL)
        assertThat(t("Server/Item/Animations/Foo.json")).isEqualTo(GameDataType.ANIMATION)
        assertThat(t("Server/SomethingBrandNew/Foo.json")).isEqualTo(GameDataType.MISC)
        assertThat(t("Server/Item/Items/Sword.json")).isEqualTo(GameDataType.ITEM)
        assertThat(t("Schema/Item.json")).isNull()
    }
}
