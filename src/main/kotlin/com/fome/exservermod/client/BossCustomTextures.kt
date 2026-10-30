package com.fome.exservermod.client

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import java.util.Locale

object BossCustomTextures {
    val WITCH_QUEEN: Identifier = Identifier.fromNamespaceAndPath("exservermod", "textures/entity/witch_queen.png")
    val INCINERATOR: Identifier = Identifier.fromNamespaceAndPath("exservermod", "textures/entity/incinerator.png")
    val DREAD_CHAMPION: Identifier = Identifier.fromNamespaceAndPath("exservermod", "textures/entity/dread_champion.png")
    val WARDEN_BOSS: Identifier = Identifier.fromNamespaceAndPath("exservermod", "textures/entity/warden_boss.png")

    fun getBossTextureFromComponent(component: Component?): Identifier? {
        if (component == null) return null
        val text = component.string.lowercase(Locale.ROOT)
        val contents = component.contents
        val key = if (contents is TranslatableContents) contents.key.lowercase(Locale.ROOT) else ""

        if (key == "exservermod.waves.mob.witch_champion" ||
            text.contains("верховная ведьма") ||
            text.contains("coven mistress")
        ) {
            return WITCH_QUEEN
        }
        if (key == "exservermod.waves.mob.blaze_champion" ||
            text.contains("пламенный чемпион") ||
            text.contains("infernal blaze") ||
            text.contains("испепелитель") ||
            text.contains("incinerator")
        ) {
            return INCINERATOR
        }
        if (key == "exservermod.waves.mob.dread_champion" ||
            text.contains("рыцарь преисподней") ||
            text.contains("dread knight")
        ) {
            return DREAD_CHAMPION
        }
        if (key.contains("warden_boss") ||
            text.contains("warden boss") ||
            text.contains("хранитель глубин")
        ) {
            return WARDEN_BOSS
        }
        return null
    }

    fun getBossTexture(name: String): Identifier? {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.contains("верховная ведьма") || lower.contains("coven mistress")) {
            return WITCH_QUEEN
        }
        if (lower.contains("пламенный чемпион") ||
            lower.contains("infernal blaze") ||
            lower.contains("испепелитель") ||
            lower.contains("incinerator")
        ) {
            return INCINERATOR
        }
        if (lower.contains("рыцарь преисподней") || lower.contains("dread knight")) {
            return DREAD_CHAMPION
        }
        if (lower.contains("warden boss") || lower.contains("хранитель глубин")) {
            return WARDEN_BOSS
        }
        return null
    }
}
