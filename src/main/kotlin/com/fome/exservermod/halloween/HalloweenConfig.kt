package com.fome.exservermod.halloween

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

data class HalloweenConfig(
    val enabled: Boolean = false,
    val eventCooldownSeconds: Int = 90 * 60, // 1.5 hours
    val eventDurationSeconds: Int = 10 * 60, // 10 minutes
    val merchantGraceSeconds: Int = 5 * 60, // 5 minutes after candy gathering
    val candySpawnRadius: Int = 25,
    val minCandySeparation: Double = 3.5,
    val mobCandyDrop: Int = 3,
    val prices: Map<String, Int> =
        mapOf(
            "pumpkin_bow" to 150,
            "ghost_pet" to 500,
            "golden_apple" to 200,
            "golden_carrots" to 75,
            "totem" to 250,
            "fireworks_shulker" to 100,
            "wither_skulls" to 300,
            "netherite_ingot" to 400,
            "slime_blocks" to 50,
        ),
) {
    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun loadOrCreate(file: File): HalloweenConfig {
            if (!file.exists()) {
                val default = HalloweenConfig()
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(gson.toJson(default), Charsets.UTF_8)
                } catch (_: Exception) {
                    // Non-fatal configuration write failure
                }
                return default
            }
            return try {
                gson.fromJson(file.readText(Charsets.UTF_8), HalloweenConfig::class.java) ?: HalloweenConfig()
            } catch (_: Exception) {
                HalloweenConfig()
            }
        }
    }
}
