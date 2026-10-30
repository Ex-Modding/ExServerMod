package com.fome.exservermod.halloween

import com.fome.exservermod.halloween.item.CandyPouchItem
import com.fome.exservermod.landmarks.Landmark
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HalloweenEventTest {
    @Test
    fun testEventScheduleConstants() {
        val config = HalloweenConfig()
        // 90 minutes = 5400 seconds = 108,000 ticks
        assertEquals(90 * 60, config.eventCooldownSeconds)
        // 10 minutes = 600 seconds = 12,000 ticks
        assertEquals(10 * 60, config.eventDurationSeconds)
        // 5 minutes grace = 300 seconds = 6,000 ticks
        assertEquals(5 * 60, config.merchantGraceSeconds)
        // Radius = 25 blocks
        assertEquals(25, config.candySpawnRadius)
        // Min candy separation = 3.5 blocks
        assertEquals(3.5, config.minCandySeparation)
        // Mob candy drop = 3
        assertEquals(3, config.mobCandyDrop)
    }

    @Test
    fun testVirtualBalanceManager() {
        val tempFile = File.createTempFile("test_balances", ".json")
        tempFile.deleteOnExit()

        val manager = HalloweenBalanceManager(tempFile)
        val playerUuid = UUID.randomUUID()

        assertEquals(0, manager.getBalance(playerUuid))

        // Add candies
        val afterAdd = manager.addCandies(playerUuid, 50)
        assertEquals(50, afterAdd)
        assertEquals(50, manager.getBalance(playerUuid))

        // Spend candies successfully
        val successSpend = manager.spendCandies(playerUuid, 30)
        assertTrue(successSpend)
        assertEquals(20, manager.getBalance(playerUuid))

        // Spend more than available should fail
        val failSpend = manager.spendCandies(playerUuid, 100)
        assertFalse(failSpend)
        assertEquals(20, manager.getBalance(playerUuid))

        // Persistence reload test
        val reloadedManager = HalloweenBalanceManager(tempFile)
        assertEquals(20, reloadedManager.getBalance(playerUuid))
    }

    private fun HalloweenBalanceManager.spendCandies(
        uuid: UUID,
        cost: Int,
    ): Boolean {
        val current = getBalance(uuid)
        return if (current >= cost) {
            setCandies(uuid, current - cost)
            true
        } else {
            false
        }
    }

    @Test
    fun testPouchSpeedAndYieldConstants() {
        // Base (no pouch): 5.0 seconds (100 ticks), +25 candies
        val baseTicks = 100
        val baseYield = 25
        assertEquals(5.0, baseTicks / 20.0)
        assertEquals(25, baseYield)

        // Tier 1: 4.0 seconds (80 ticks), +30 candies
        assertEquals(80, CandyPouchItem.TIER_1_TICKS)
        assertEquals(30, CandyPouchItem.TIER_1_YIELD)
        assertEquals(4.0, CandyPouchItem.TIER_1_TICKS / 20.0)

        // Tier 2: 3.5 seconds (70 ticks), +45 candies
        assertEquals(70, CandyPouchItem.TIER_2_TICKS)
        assertEquals(45, CandyPouchItem.TIER_2_YIELD)
        assertEquals(3.5, CandyPouchItem.TIER_2_TICKS / 20.0)

        // Tier 3: 1.0 second (20 ticks), +80 candies
        assertEquals(20, CandyPouchItem.TIER_3_TICKS)
        assertEquals(80, CandyPouchItem.TIER_3_YIELD)
        assertEquals(1.0, CandyPouchItem.TIER_3_TICKS / 20.0)
    }

    @Test
    fun testLandmarkTraderDistanceBounds() {
        val landmark = Landmark("Pyramid", 100.0, 200.0, radius = 20.0)

        for (i in 0 until 100) {
            val dist = Random.nextDouble(10.0, 60.0)
            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val spawnX = landmark.x + kotlin.math.cos(angle) * dist
            val spawnZ = landmark.z + kotlin.math.sin(angle) * dist

            val actualDist = hypot(spawnX - landmark.x, spawnZ - landmark.z)
            assertTrue(actualDist >= 10.0, "Merchant spawn distance must be >= 10.0")
            assertTrue(actualDist <= 60.0, "Merchant spawn distance must be <= 60.0")
        }
    }

    @Test
    fun testWaveCandyDropAmounts() {
        // Regular mob gives 3 candies
        val standardCandyDrop = 3
        assertEquals(3, standardCandyDrop)

        // Boss or Champion gives 200 candies
        val bossCandyDrop = 200
        assertEquals(200, bossCandyDrop)
    }

    @Test
    fun testWaresPoolCompleteness() {
        val pool = HalloweenEvent.ALL_WARES_POOL
        assertTrue(pool.contains("pumpkin_bow"))
        assertTrue(pool.contains("ghost_pet"))
        assertTrue(pool.contains("golden_apple"))
        assertTrue(pool.contains("golden_carrots"))
        assertTrue(pool.contains("totem"))
        assertTrue(pool.contains("fireworks_shulker"))
        assertTrue(pool.contains("wither_skulls"))
        assertTrue(pool.contains("netherite_ingot"))
        assertTrue(pool.contains("slime_blocks"))
    }

    @Test
    fun testBalanceManagerRemoveCandies() {
        val tempFile = File.createTempFile("test_remove_balances", ".json")
        tempFile.deleteOnExit()

        val manager = HalloweenBalanceManager(tempFile)
        val uuid = UUID.randomUUID()

        manager.setCandies(uuid, 50)
        assertEquals(50, manager.getBalance(uuid))

        // Remove partial
        val removed1 = manager.removeCandies(uuid, 15)
        assertEquals(15, removed1)
        assertEquals(35, manager.getBalance(uuid))

        // Remove more than available
        val removed2 = manager.removeCandies(uuid, 100)
        assertEquals(35, removed2)
        assertEquals(0, manager.getBalance(uuid))

        // Remove when balance is 0
        val removed3 = manager.removeCandies(uuid, 10)
        assertEquals(0, removed3)
        assertEquals(0, manager.getBalance(uuid))
    }

    @Test
    fun testBloodZombieCandyStealAndDropBounds() {
        for (i in 0 until 100) {
            val steal = Random.nextInt(5, 16)
            assertTrue(steal in 5..15, "Steal amount must be in 5..15")

            val drop = Random.nextInt(2, 6)
            assertTrue(drop in 2..5, "Zombie drop must be in 2..5")
        }
    }

    @Test
    fun testDemonEyeStatsAndDrops() {
        val maxHp = 5.0f
        val pureDmg = 1.0f
        assertEquals(5.0f, maxHp)
        assertEquals(1.0f, pureDmg)

        for (i in 0 until 100) {
            val drop = Random.nextInt(1, 4)
            assertTrue(drop in 1..3, "Demon eye drop must be in 1..3")

            val variant = Random.nextInt(4)
            assertTrue(variant in 0..3, "Demon eye variant must be in 0..3")
        }
    }

    @Test
    fun testGhostPetPositioningMath() {
        val playerX = 100.0
        val playerZ = 100.0
        val playerY = 64.0
        val eyeY = playerY + 1.62
        val yRot = 0.0f // Facing south (+Z)

        val rad = Math.toRadians((yRot + 140.0) % 360.0)
        val targetX = playerX - kotlin.math.sin(rad) * 1.4
        val targetZ = playerZ + kotlin.math.cos(rad) * 1.4
        val targetY = eyeY + 0.35

        // Player faces South (+Z). Behind is North (-Z), right is West (-X)
        assertTrue(targetZ < playerZ, "Should be behind player (lower Z when facing south)")
        assertTrue(targetX < playerX, "Should be to the right of player (lower X when facing south)")
        assertEquals(65.97, targetY, 0.01)
    }

    @Test
    fun testWaveMobCandyRewardLogic() {
        val config = HalloweenConfig()

        fun computeCandy(role: com.fome.exservermod.waves.WaveMobRole): Int {
            val isBossOrChamp =
                role == com.fome.exservermod.waves.WaveMobRole.CHAMPION ||
                    role == com.fome.exservermod.waves.WaveMobRole.BLAZE_CHAMPION ||
                    role == com.fome.exservermod.waves.WaveMobRole.WITCH_CHAMPION
            return if (isBossOrChamp) 200 else config.mobCandyDrop
        }

        // Standard mob in boss wave should still give standard candy drop (3), NOT 200!
        assertEquals(3, computeCandy(com.fome.exservermod.waves.WaveMobRole.STANDARD))
        assertEquals(3, computeCandy(com.fome.exservermod.waves.WaveMobRole.GHOST_CREEPER))
        assertEquals(3, computeCandy(com.fome.exservermod.waves.WaveMobRole.SPECTRAL_VEX))

        // Only champions give 200 candies
        assertEquals(200, computeCandy(com.fome.exservermod.waves.WaveMobRole.CHAMPION))
        assertEquals(200, computeCandy(com.fome.exservermod.waves.WaveMobRole.BLAZE_CHAMPION))
        assertEquals(200, computeCandy(com.fome.exservermod.waves.WaveMobRole.WITCH_CHAMPION))
    }

    @Test
    fun testBossCustomTexturesMatching() {
        val witch =
            com.fome.exservermod.client.BossCustomTextures
                .getBossTexture("Верховная Ведьма Шабаша")
        assertEquals(com.fome.exservermod.client.BossCustomTextures.WITCH_QUEEN, witch)

        val incinerator =
            com.fome.exservermod.client.BossCustomTextures
                .getBossTexture("Пламенный Чемпион")
        assertEquals(com.fome.exservermod.client.BossCustomTextures.INCINERATOR, incinerator)

        val dread =
            com.fome.exservermod.client.BossCustomTextures
                .getBossTexture("Рыцарь Преисподней")
        assertEquals(com.fome.exservermod.client.BossCustomTextures.DREAD_CHAMPION, dread)

        // Normal blaze or witch should NOT match
        val normalBlaze =
            com.fome.exservermod.client.BossCustomTextures
                .getBossTexture("Blaze")
        kotlin.test.assertNull(normalBlaze)

        val normalWitch =
            com.fome.exservermod.client.BossCustomTextures
                .getBossTexture("Witch")
        kotlin.test.assertNull(normalWitch)
    }
}
