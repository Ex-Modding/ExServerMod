package com.fome.exservermod.afk

import com.fome.exservermod.landmarks.Landmark
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.random.Random

/**
 * AFK chunk-loader bot (Carpet fake player, default nick "Steve").
 *
 * The bot must never participate in mini-games (hide & seek, waves) and can be
 * relocated to any landmark registered via /location-add.
 */
object AfkManager {
    const val BOT_NAME = "Steve"

    /**
     * Carpet spawns fake players as a patched ServerPlayer subclass; detect it
     * by class name so no compile-time Carpet dependency is needed.
     */
    fun isBotPlayer(player: ServerPlayer): Boolean =
        player.gameProfile.name == BOT_NAME ||
            player.javaClass.name.contains("EntityPlayerMPFake", ignoreCase = true) ||
            player.javaClass.name.contains("Fake", ignoreCase = true)

    fun findBot(server: MinecraftServer): ServerPlayer? = server.playerList.getPlayer(BOT_NAME)

    fun isBotOnline(server: MinecraftServer): Boolean = findBot(server) != null

    fun resolveLevel(
        server: MinecraftServer,
        dimension: String,
    ): ServerLevel? {
        val id = Identifier.tryParse(dimension) ?: return null
        val key = ResourceKey.create(Registries.DIMENSION, id)
        return server.getLevel(key)
    }

    /**
     * Teleports the bot to a landmark center at a safe surface height.
     */
    fun teleportToLandmark(
        server: MinecraftServer,
        bot: ServerPlayer,
        landmark: Landmark,
    ): AfkTeleportResult {
        val targetLevel = resolveLevel(server, landmark.dimension) ?: return AfkTeleportResult.WRONG_DIMENSION

        val pos =
            findSafeSurface(targetLevel, landmark.x, landmark.z)
                ?: return AfkTeleportResult.UNSAFE_SPOT

        teleportBot(bot, targetLevel, Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5))
        return AfkTeleportResult.SUCCESS
    }

    fun teleportToPos(
        bot: ServerPlayer,
        level: ServerLevel,
        pos: Vec3,
    ) {
        teleportBot(bot, level, Vec3(pos.x, pos.y, pos.z))
    }

    private fun teleportBot(
        bot: ServerPlayer,
        level: ServerLevel,
        target: Vec3,
    ) {
        level.sendParticles(
            ParticleTypes.PORTAL,
            bot.x,
            bot.y + 1.0,
            bot.z,
            40,
            0.4,
            0.8,
            0.4,
            0.1,
        )

        if (bot.level().dimension() != level.dimension()) {
            bot.teleportTo(level, target.x, target.y, target.z, emptySet(), bot.yRot, bot.xRot, false)
        } else {
            bot.teleportTo(target.x, target.y, target.z)
        }

        level.sendParticles(ParticleTypes.PORTAL, target.x, target.y + 1.0, target.z, 40, 0.4, 0.8, 0.4, 0.1)
        level.playSound(null, target.x, target.y, target.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.0f)
    }

    /**
     * Finds the topmost safe standable block column near (x, z).
     * Tries the exact column first, then a small ring of offsets.
     */
    fun findSafeSurface(
        level: Level,
        x: Double,
        z: Double,
    ): BlockPos? {
        val baseX = x.toInt()
        val baseZ = z.toInt()

        val offsets =
            buildList {
                add(0 to 0)
                var r = 1
                while (r <= 3) {
                    for (dx in -r..r) {
                        for (dz in -r..r) {
                            if (maxOf(abs(dx), abs(dz)) == r) {
                                add(dx to dz)
                            }
                        }
                    }
                    r++
                }
            }

        for ((dx, dz) in offsets) {
            val cx = baseX + dx
            val cz = baseZ + dz
            val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz)
            val pos = BlockPos(cx, surfaceY, cz)
            val floor = level.getBlockState(pos.below())
            val feet = level.getBlockState(pos)
            val head = level.getBlockState(pos.above())

            if (feet.isAir && head.isAir && !floor.isAir && level.getFluidState(pos.below()).isEmpty) {
                return pos
            }
        }
        return null
    }

    /**
     * Picks a random landmark, optionally excluding one by name (so "random"
     * actually moves the bot somewhere new).
     */
    fun pickRandomLandmark(
        landmarks: List<Landmark>,
        excludeName: String? = null,
        random: Random = Random,
    ): Landmark? {
        val pool = landmarks.filter { it.name != excludeName }
        if (pool.isEmpty()) return landmarks.firstOrNull()
        return pool[random.nextInt(pool.size)]
    }
}

enum class AfkTeleportResult {
    SUCCESS,
    WRONG_DIMENSION,
    UNSAFE_SPOT,
}
