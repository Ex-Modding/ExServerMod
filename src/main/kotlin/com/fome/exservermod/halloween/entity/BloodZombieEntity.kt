package com.fome.exservermod.halloween.entity

import com.fome.exservermod.halloween.HalloweenEvent
import com.fome.exservermod.halloween.HalloweenPhase
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import kotlin.random.Random

class BloodZombieEntity(
    type: EntityType<out BloodZombieEntity>,
    level: Level,
) : Zombie(type, level) {
    override fun isSunSensitive(): Boolean = false

    override fun shouldBeSaved(): Boolean = false

    override fun hurtServer(
        serverLevel: ServerLevel,
        damageSource: DamageSource,
        amount: Float,
    ): Boolean {
        val hit = super.hurtServer(serverLevel, damageSource, amount)
        if (hit) {
            val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
            serverLevel.sendParticles(bloodOption, x, y + 1.0, z, 10, 0.25, 0.35, 0.25, 0.08)
        }
        return hit
    }

    override fun tick() {
        super.tick()
        if (HalloweenEvent.instance?.phase == HalloweenPhase.IDLE) {
            discard()
            return
        }
        val sLevel = level() as? ServerLevel ?: return
        if (tickCount % 6 == 0) {
            val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
            sLevel.sendParticles(
                bloodOption,
                x,
                y + 1.1,
                z,
                2,
                0.15,
                0.2,
                0.15,
                0.02,
            )
            sLevel.sendParticles(
                ParticleTypes.CRIMSON_SPORE,
                x,
                y + 1.2,
                z,
                1,
                0.1,
                0.1,
                0.1,
                0.01,
            )
        }
    }

    override fun doHurtTarget(
        serverLevel: ServerLevel,
        target: Entity,
    ): Boolean {
        val hit = super.doHurtTarget(serverLevel, target)
        if (hit && target is ServerPlayer) {
            val stealAmount = Random.nextInt(5, 16)
            val stolen = HalloweenEvent.balanceManager?.removeCandies(target, stealAmount) ?: 0
            if (stolen > 0) {
                target.sendSystemMessage(
                    Component.translatable("exservermod.halloween.candy_stolen", stolen).withStyle(ChatFormatting.RED),
                    true,
                )
                serverLevel.playSound(
                    null,
                    target.x,
                    target.y,
                    target.z,
                    SoundEvents.ZOMBIE_ATTACK_IRON_DOOR,
                    SoundSource.HOSTILE,
                    1.0f,
                    1.4f,
                )
            }
        }
        return hit
    }

    override fun die(damageSource: DamageSource) {
        super.die(damageSource)
        val sLevel = level() as? ServerLevel ?: return
        val killer = damageSource.entity as? ServerPlayer
        val amount = Random.nextInt(2, 6)
        if (killer != null) {
            val newBal = HalloweenEvent.balanceManager?.addCandies(killer, amount, sendActionBar = true) ?: 0
            val timeLeft =
                if (HalloweenEvent.instance?.phase == HalloweenPhase.ACTIVE_GATHERING) {
                    " | ⏱ ${HalloweenEvent.instance?.formattedTimeLeft()}"
                } else {
                    ""
                }
            killer.sendSystemMessage(
                Component
                    .translatable("exservermod.halloween.candy_mob_kill", amount, newBal, timeLeft)
                    .withStyle(ChatFormatting.GOLD),
                true,
            )
        }
        val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
        sLevel.sendParticles(
            bloodOption,
            x,
            y + 1.0,
            z,
            20,
            0.3,
            0.5,
            0.3,
            0.15,
        )
    }
}
