package com.fome.exservermod.island

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Manages atmospheric paranormal events exclusive to Nightmare Island:
 * - Clawing zombie hand emerging from under the soil and evaporating on approach.
 * - Scurrying haunted pumpkins and witch hats that vanish or solidify when spotted.
 * - Semi-transparent spectral ghost apparitions shrouded in mist that dissolve upon eye contact.
 */
class NightmareIslandManager {
    companion object {
        const val ISLAND_CENTER_X = 2250.0
        const val ISLAND_CENTER_Y = 63.0
        const val ISLAND_CENTER_Z = 3080.0

        // 9x9 chunk perimeter (Chunks 136..144 in X, 188..196 in Z)
        const val ISLAND_MIN_X = 2176.0
        const val ISLAND_MAX_X = 2319.0
        const val ISLAND_MIN_Z = 3008.0
        const val ISLAND_MAX_Z = 3151.0

        const val TAG_PARANORMAL_GHOST = "ex_paranormal_ghost"
        const val TAG_PARANORMAL_HAND = "ex_paranormal_hand"
        const val TAG_PARANORMAL_RUNNER = "ex_paranormal_runner"

        fun isLocationOnIsland(
            x: Double,
            z: Double,
        ): Boolean = x in ISLAND_MIN_X..ISLAND_MAX_X && z in ISLAND_MIN_Z..ISLAND_MAX_Z

        fun isPlayerOnIsland(player: ServerPlayer): Boolean =
            player.level().dimension() == Level.OVERWORLD && isLocationOnIsland(player.x, player.z)
    }

    private data class TrackedHand(
        val entityUuid: UUID,
        val targetY: Double,
        var currentY: Double,
        var ticksAlive: Int = 0,
    )

    private data class TrackedGhost(
        val entityUuid: UUID,
        var ticksAlive: Int = 0,
    )

    private data class TrackedRunner(
        val entityUuid: UUID,
        val isPumpkin: Boolean,
        var ticksAlive: Int = 0,
    )

    private val trackedHands: MutableMap<UUID, TrackedHand> = ConcurrentHashMap()
    private val trackedGhosts: MutableMap<UUID, TrackedGhost> = ConcurrentHashMap()
    private val trackedRunners: MutableMap<UUID, TrackedRunner> = ConcurrentHashMap()

    private var ghostSpawnCooldown = 200
    private var handSpawnCooldown = 350
    private var runnerSpawnCooldown = 450

    fun tick(level: ServerLevel) {
        if (level.dimension() != Level.OVERWORLD) return

        val islandPlayers = level.players().filter { isPlayerOnIsland(it) && it.isAlive }
        if (islandPlayers.isEmpty()) {
            if (trackedHands.isNotEmpty() || trackedGhosts.isNotEmpty() || trackedRunners.isNotEmpty()) {
                clearAll(level)
            }
            return
        }

        tickGhosts(level, islandPlayers)
        tickHands(level, islandPlayers)
        tickRunners(level, islandPlayers)

        handleSpawning(level, islandPlayers)
    }

    private fun handleSpawning(
        level: ServerLevel,
        islandPlayers: List<ServerPlayer>,
    ) {
        ghostSpawnCooldown--
        if (ghostSpawnCooldown <= 0) {
            ghostSpawnCooldown = Random.nextInt(260, 420)
            if (trackedGhosts.size < 4) {
                val anchor = islandPlayers.random()
                spawnGhostNear(level, anchor)
            }
        }

        handSpawnCooldown--
        if (handSpawnCooldown <= 0) {
            handSpawnCooldown = Random.nextInt(380, 600)
            if (trackedHands.size < 2) {
                val anchor = islandPlayers.random()
                spawnHandNear(level, anchor)
            }
        }

        runnerSpawnCooldown--
        if (runnerSpawnCooldown <= 0) {
            runnerSpawnCooldown = Random.nextInt(450, 750)
            if (trackedRunners.size < 2) {
                val anchor = islandPlayers.random()
                spawnRunnerNear(level, anchor)
            }
        }
    }

    private fun tickGhosts(
        level: ServerLevel,
        players: List<ServerPlayer>,
    ) {
        val iterator = trackedGhosts.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, ghostData) = iterator.next()
            ghostData.ticksAlive++

            val entity = level.getEntity(uuid) as? Mob
            if (entity == null || !entity.isAlive) {
                iterator.remove()
                continue
            }

            if (ghostData.ticksAlive > 700) {
                evaporateEntity(level, entity, silent = true)
                iterator.remove()
                continue
            }

            // Aura of dark fog & soul particles
            if (ghostData.ticksAlive % 3 == 0) {
                level.sendParticles(
                    ParticleTypes.SOUL,
                    entity.x + (Random.nextDouble() - 0.5) * 0.6,
                    entity.y + 0.3 + Random.nextDouble() * 1.2,
                    entity.z + (Random.nextDouble() - 0.5) * 0.6,
                    1,
                    0.0,
                    0.02,
                    0.0,
                    0.01,
                )
                level.sendParticles(
                    ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    entity.x + (Random.nextDouble() - 0.5) * 0.4,
                    entity.y + 0.2 + Random.nextDouble() * 1.0,
                    entity.z + (Random.nextDouble() - 0.5) * 0.4,
                    1,
                    0.0,
                    0.01,
                    0.0,
                    0.005,
                )
            }

            // Evaporate on eye contact or close proximity
            var vaporized = false
            for (player in players) {
                val toGhost = Vec3(entity.x, entity.y + entity.bbHeight * 0.6, entity.z).subtract(player.eyePosition)
                val dist = toGhost.length()
                if (dist <= 30.0) {
                    val dot = player.lookAngle.dot(toGhost.normalize())
                    if (dot > 0.93 || dist <= 3.5) {
                        evaporateEntity(level, entity, silent = false)
                        iterator.remove()
                        vaporized = true
                        break
                    }
                }
            }
            if (vaporized) continue
        }
    }

    private fun tickHands(
        level: ServerLevel,
        players: List<ServerPlayer>,
    ) {
        val iterator = trackedHands.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, handData) = iterator.next()
            handData.ticksAlive++

            val entity = level.getEntity(uuid) as? Mob
            if (entity == null || !entity.isAlive) {
                iterator.remove()
                continue
            }

            if (handData.ticksAlive > 360) {
                evaporateEntity(level, entity, silent = true)
                iterator.remove()
                continue
            }

            // Rising crawl animation
            if (handData.currentY < handData.targetY) {
                handData.currentY += 0.04
                if (handData.currentY > handData.targetY) {
                    handData.currentY = handData.targetY
                }
                entity.setPos(entity.x, handData.currentY, entity.z)

                val groundBlock = level.getBlockState(BlockPos(entity.blockX, handData.targetY.toInt() - 1, entity.blockZ))
                level.sendParticles(
                    BlockParticleOption(ParticleTypes.BLOCK, groundBlock),
                    entity.x,
                    handData.targetY,
                    entity.z,
                    4,
                    0.25,
                    0.1,
                    0.25,
                    0.05,
                )
            }

            // Evaporate on close approach or gaze
            var vaporized = false
            for (player in players) {
                val toHand = Vec3(entity.x, entity.y + 0.8, entity.z).subtract(player.eyePosition)
                val dist = toHand.length()
                if (dist <= 4.5 || (dist <= 18.0 && player.lookAngle.dot(toHand.normalize()) > 0.94)) {
                    evaporateEntity(level, entity, silent = false)
                    iterator.remove()
                    vaporized = true
                    break
                }
            }
            if (vaporized) continue
        }
    }

    private fun tickRunners(
        level: ServerLevel,
        players: List<ServerPlayer>,
    ) {
        val iterator = trackedRunners.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, runnerData) = iterator.next()
            runnerData.ticksAlive++

            val entity = level.getEntity(uuid) as? Mob
            if (entity == null || !entity.isAlive) {
                iterator.remove()
                continue
            }

            if (runnerData.ticksAlive > 400) {
                evaporateEntity(level, entity, silent = true)
                iterator.remove()
                continue
            }

            // Scurry navigation
            if (runnerData.ticksAlive % 25 == 0 && entity.navigation.isDone) {
                val targetX = entity.x + Random.nextDouble(-8.0, 8.0)
                val targetZ = entity.z + Random.nextDouble(-8.0, 8.0)
                val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, targetX.toInt(), targetZ.toInt())
                entity.navigation.moveTo(targetX, surfaceY.toDouble(), targetZ, 1.35)
            }

            // Check gaze or proximity
            var vaporized = false
            for (player in players) {
                val toRunner = Vec3(entity.x, entity.y + 0.5, entity.z).subtract(player.eyePosition)
                val dist = toRunner.length()
                if (dist <= 3.5 || (dist <= 24.0 && player.lookAngle.dot(toRunner.normalize()) > 0.93)) {
                    if (runnerData.isPumpkin && dist > 5.0 && Random.nextFloat() < 0.4f) {
                        val pos = BlockPos(entity.blockX, (entity.y + 0.1).toInt(), entity.blockZ)
                        if (level.getBlockState(pos).isAir &&
                            level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                        ) {
                            level.setBlock(pos, Blocks.CARVED_PUMPKIN.defaultBlockState(), 3)
                        }
                    }
                    evaporateEntity(level, entity, silent = false)
                    iterator.remove()
                    vaporized = true
                    break
                }
            }
            if (vaporized) continue
        }
    }

    private fun spawnGhostNear(
        level: ServerLevel,
        player: ServerPlayer,
    ) {
        val pos = findNaturalGroundNear(level, player.blockX, player.blockZ, 12, 26) ?: return

        val ghostTypes: List<EntityType<out Mob>> =
            listOf(
                EntityTypes.SKELETON,
                EntityTypes.ZOMBIE,
                EntityTypes.STRAY,
                EntityTypes.WITHER_SKELETON,
            )
        val type = ghostTypes.random()
        val mob = type.create(level, EntitySpawnReason.EVENT) ?: return

        mob.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        mob.isNoAi = true
        mob.isSilent = true
        mob.setPermanentlyInvulnerable(true)
        mob.setPersistenceRequired()
        mob.addTag(TAG_PARANORMAL_GHOST)

        // Turn to stare towards the player
        val lookAngle = Vec3(player.x - mob.x, 0.0, player.z - mob.z).normalize()
        mob.yRot = (Math.toDegrees(kotlin.math.atan2(-lookAngle.x, lookAngle.z))).toFloat()
        mob.yHeadRot = mob.yRot

        for (slot in EquipmentSlot.entries) {
            mob.setDropChance(slot, 0.0f)
        }

        level.addFreshEntity(mob)
        trackedGhosts[mob.uuid] = TrackedGhost(mob.uuid)
    }

    private fun spawnHandNear(
        level: ServerLevel,
        player: ServerPlayer,
    ) {
        val pos = findNaturalGroundNear(level, player.blockX, player.blockZ, 8, 16) ?: return

        val zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: return
        val targetY = pos.y - 0.25
        val initialY = pos.y - 1.25

        zombie.setPos(pos.x + 0.5, initialY, pos.z + 0.5)
        zombie.isNoAi = true
        zombie.isSilent = true
        zombie.setPermanentlyInvulnerable(true)
        zombie.setPersistenceRequired()
        zombie.addTag(TAG_PARANORMAL_HAND)

        for (slot in EquipmentSlot.entries) {
            zombie.setDropChance(slot, 0.0f)
        }

        level.addFreshEntity(zombie)
        trackedHands[zombie.uuid] = TrackedHand(zombie.uuid, targetY, initialY)
    }

    private fun spawnRunnerNear(
        level: ServerLevel,
        player: ServerPlayer,
    ) {
        val pos = findNaturalGroundNear(level, player.blockX, player.blockZ, 10, 20) ?: return

        val runner = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: return
        val isPumpkin = Random.nextBoolean()

        runner.setBaby(true)
        runner.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        runner.isSilent = true
        runner.setPermanentlyInvulnerable(true)
        runner.setPersistenceRequired()
        runner.addTag(TAG_PARANORMAL_RUNNER)

        // Permanent invisible body without particle trail
        runner.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 10000, 0, false, false))

        if (isPumpkin) {
            runner.setItemSlot(EquipmentSlot.HEAD, ItemStack(Blocks.CARVED_PUMPKIN))
        } else {
            // Witch hat / Netherite helmet visual
            runner.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.NETHERITE_HELMET))
        }

        for (slot in EquipmentSlot.entries) {
            runner.setDropChance(slot, 0.0f)
        }

        level.addFreshEntity(runner)
        trackedRunners[runner.uuid] = TrackedRunner(runner.uuid, isPumpkin)
    }

    private fun evaporateEntity(
        level: ServerLevel,
        entity: LivingEntity,
        silent: Boolean,
    ) {
        level.sendParticles(
            ParticleTypes.POOF,
            entity.x,
            entity.y + entity.bbHeight * 0.5,
            entity.z,
            12,
            0.3,
            0.3,
            0.3,
            0.05,
        )
        level.sendParticles(
            ParticleTypes.SOUL,
            entity.x,
            entity.y + entity.bbHeight * 0.5,
            entity.z,
            14,
            0.25,
            0.3,
            0.25,
            0.08,
        )
        level.sendParticles(
            ParticleTypes.CAMPFIRE_COSY_SMOKE,
            entity.x,
            entity.y + entity.bbHeight * 0.3,
            entity.z,
            6,
            0.2,
            0.2,
            0.2,
            0.02,
        )

        if (!silent) {
            level.playSound(
                null,
                entity.x,
                entity.y,
                entity.z,
                SoundEvents.SOUL_ESCAPE,
                SoundSource.HOSTILE,
                0.9f,
                0.75f + Random.nextFloat() * 0.3f,
            )
        }

        entity.discard()
    }

    private fun findNaturalGroundNear(
        level: ServerLevel,
        centerX: Int,
        centerZ: Int,
        minDist: Int,
        maxDist: Int,
    ): BlockPos? {
        repeat(20) {
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val dist = Random.nextDouble(minDist.toDouble(), maxDist.toDouble())
            val x = (centerX + dist * cos(angle)).toInt()
            val z = (centerZ + dist * sin(angle)).toInt()

            if (!isLocationOnIsland(x.toDouble(), z.toDouble())) return@repeat

            val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            // Island ground is strictly Y 60..76 (exclude high roofs)
            if (surfaceY !in 60..76) return@repeat

            val pos = BlockPos(x, surfaceY, z)
            val floorPos = pos.below()
            val floor = level.getBlockState(floorPos)

            if (!floor.isFaceSturdy(level, floorPos, Direction.UP)) return@repeat
            if (!level.getFluidState(floorPos).isEmpty || !level.getFluidState(pos).isEmpty) return@repeat

            val floorBlock = floor.block
            if (floorBlock is StairBlock || floorBlock is SlabBlock || floorBlock is FenceBlock) return@repeat

            // Preferred island terrain: podzol, granite, dirt, coarse dirt, grass block
            val isNaturalGround =
                floorBlock == Blocks.PODZOL ||
                    floorBlock == Blocks.GRANITE ||
                    floorBlock == Blocks.DIRT ||
                    floorBlock == Blocks.COARSE_DIRT ||
                    floorBlock == Blocks.GRASS_BLOCK ||
                    floorBlock == Blocks.STONE

            if (isNaturalGround && level.getBlockState(pos).isAir && level.getBlockState(pos.above()).isAir) {
                return pos
            }
        }
        return null
    }

    fun clearAll(level: ServerLevel) {
        for (uuid in trackedGhosts.keys) {
            level.getEntity(uuid)?.discard()
        }
        for (uuid in trackedHands.keys) {
            level.getEntity(uuid)?.discard()
        }
        for (uuid in trackedRunners.keys) {
            level.getEntity(uuid)?.discard()
        }
        trackedGhosts.clear()
        trackedHands.clear()
        trackedRunners.clear()
    }
}
