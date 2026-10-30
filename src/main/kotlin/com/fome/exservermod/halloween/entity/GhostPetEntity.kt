package com.fome.exservermod.halloween.entity

import net.minecraft.ChatFormatting
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.animal.allay.Allay
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class GhostPetEntity(
    type: EntityType<out Allay>,
    level: Level,
) : Allay(type, level) {
    var ownerUuid: UUID? = null
    var isWaiting: Boolean = false
    private var waitAnchorPos: Vec3? = null

    init {
        noPhysics = true
        isInvulnerable = true
        isCustomNameVisible = true
    }

    override fun hurtServer(
        serverLevel: ServerLevel,
        damageSource: DamageSource,
        f: Float,
    ): Boolean = false

    override fun mobInteract(
        player: Player,
        hand: InteractionHand,
    ): InteractionResult {
        if (hand == InteractionHand.MAIN_HAND && player.uuid == ownerUuid) {
            isWaiting = !isWaiting
            val sLevel = level() as? ServerLevel
            if (isWaiting) {
                val groundY = sLevel?.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ) ?: y.toInt()
                val waitY = maxOf(y, groundY.toDouble() + 1.2)
                waitAnchorPos = Vec3(x, waitY, z)
                setPos(x, waitY, z)
                setDeltaMovement(Vec3.ZERO)
                player.sendOverlayMessage(
                    Component.translatable("exservermod.halloween.ghost_waiting").withStyle(ChatFormatting.AQUA),
                )
                sLevel?.playSound(null, x, y, z, SoundEvents.ALLAY_ITEM_TAKEN, SoundSource.NEUTRAL, 1.2f, 1.5f)
            } else {
                waitAnchorPos = null
                player.sendOverlayMessage(
                    Component.translatable("exservermod.halloween.ghost_following").withStyle(ChatFormatting.AQUA),
                )
                sLevel?.playSound(null, x, y, z, SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, SoundSource.NEUTRAL, 1.2f, 1.3f)
            }
            return InteractionResult.SUCCESS
        }
        return super.mobInteract(player, hand)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        val uuidStr = input.getStringOr("OwnerUuid", "")
        if (uuidStr.isNotEmpty()) {
            try {
                ownerUuid = UUID.fromString(uuidStr)
            } catch (_: IllegalArgumentException) {
                // Ignore malformed UUID
            }
        }
        isWaiting = input.getBooleanOr("IsWaiting", false)
        if (isWaiting) {
            waitAnchorPos = Vec3(x, y, z)
        }
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putString("OwnerUuid", ownerUuid?.toString() ?: "")
        output.putBoolean("IsWaiting", isWaiting)
    }

    override fun tick() {
        super.tick()
        noPhysics = true

        if (hasEffect(MobEffects.GLOWING)) {
            removeEffect(MobEffects.GLOWING)
        }
        if (hasGlowingTag()) {
            setGlowingTag(false)
        }

        val sLevel = level() as? ServerLevel ?: return
        val currentOwnerUuid = ownerUuid ?: return

        // Atmospheric bright soul-blue flame, ascending souls, and cyan dust particles
        if (tickCount % 2 == 0) {
            sLevel.sendParticles(
                ParticleTypes.SOUL_FIRE_FLAME,
                x,
                y + 0.35,
                z,
                2,
                0.12,
                0.12,
                0.12,
                0.005,
            )
            sLevel.sendParticles(
                DustParticleOptions(0x00E5FF, 1.2f),
                x,
                y + 0.35,
                z,
                2,
                0.15,
                0.15,
                0.15,
                0.01,
            )
        }
        if (tickCount % 12 == 0) {
            sLevel.sendParticles(
                ParticleTypes.SOUL,
                x,
                y + 0.3,
                z,
                1,
                0.05,
                0.05,
                0.05,
                0.015,
            )
            sLevel.sendParticles(
                ParticleTypes.SCULK_SOUL,
                x,
                y + 0.35,
                z,
                1,
                0.08,
                0.08,
                0.08,
                0.01,
            )
        }

        // Maintain slow ghostly flight when sitting / waiting
        if (isWaiting) {
            val anchor = waitAnchorPos ?: Vec3(x, y, z).also { waitAnchorPos = it }
            val slowBobY = anchor.y + sin(tickCount * 0.04) * 0.2
            val slowDriftX = anchor.x + cos(tickCount * 0.02) * 0.3
            val slowDriftZ = anchor.z + sin(tickCount * 0.02) * 0.3
            val targetWaitPos = Vec3(slowDriftX, slowBobY, slowDriftZ)

            val diff = targetWaitPos.subtract(position())
            val slowVel = diff.scale(0.08)
            setDeltaMovement(slowVel)
            move(MoverType.SELF, slowVel)

            yRot = (yRot + 0.5f) % 360.0f
            yHeadRot = yRot
            return
        }

        // Locate owner
        var owner = sLevel.server.playerList.getPlayer(currentOwnerUuid)
        if (owner == null) {
            for (otherLevel in sLevel.server.allLevels) {
                val p = otherLevel.getPlayerByUUID(currentOwnerUuid)
                if (p != null) {
                    teleportTo(otherLevel, p.x, p.y + 1.2, p.z, emptySet(), 0.0f, 0.0f, false)
                    return
                }
            }
            return
        }

        if (owner.level() != sLevel) {
            val targetLevel = owner.level()
            teleportTo(targetLevel, owner.x, owner.y + 1.2, owner.z, emptySet(), 0.0f, 0.0f, false)
            return
        }

        // Calculate position strictly in rear sector: owner.yRot + 140 degrees, distance 1.4, eyeY + 0.35
        val rad = Math.toRadians((owner.yRot + 140.0) % 360.0)
        val targetX = owner.x - sin(rad) * 1.4
        val targetZ = owner.z + cos(rad) * 1.4
        val targetY = owner.eyeY + 0.35
        val target = Vec3(targetX, targetY, targetZ)

        val dist = position().distanceTo(target)
        if (dist > 24.0) {
            teleportTo(targetX, targetY, targetZ)
            setDeltaMovement(Vec3.ZERO)
        } else {
            val diff = target.subtract(position())
            val velocity = diff.scale(0.22)
            setDeltaMovement(velocity)
            move(MoverType.SELF, velocity)
        }

        yRot = owner.yRot
        yHeadRot = owner.yRot
    }
}
