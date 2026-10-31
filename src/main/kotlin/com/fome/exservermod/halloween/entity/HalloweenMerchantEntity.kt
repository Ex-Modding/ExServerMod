package com.fome.exservermod.halloween.entity

import com.fome.exservermod.halloween.HalloweenEvent
import com.fome.exservermod.halloween.network.OpenMerchantGuiPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.trading.MerchantOffers
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import kotlin.random.Random

class HalloweenMerchantEntity(
    type: EntityType<out WanderingTrader>,
    level: Level,
) : WanderingTrader(type, level) {
    init {
        customName = Component.translatable("exservermod.halloween.merchant_title")
        isCustomNameVisible = true
        setPermanentlyInvulnerable(true)
        setPersistenceRequired()
        despawnDelay = -1
        setGlowingTag(true)
        addEffect(MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false, false))
    }

    override fun getName(): Component = Component.translatable("exservermod.halloween.merchant_title")

    override fun getDisplayName(): Component = Component.translatable("exservermod.halloween.merchant_title")

    override fun registerGoals() {
        // Only keep float and look goals:
        // NEVER wander away, NEVER panic, NEVER avoid zombies/illagers, NEVER drink invisibility potions
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(2, RandomLookAroundGoal(this))
    }

    override fun aiStep() {
        super.aiStep()
        despawnDelay = -1 // Prevent automatic despawn
        if (!hasEffect(MobEffects.GLOWING)) {
            addEffect(MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false, false))
        }
        if (!hasGlowingTag()) {
            setGlowingTag(true)
        }
    }

    override fun isCurrentlyGlowing(): Boolean = true

    override fun teleportTo(
        x: Double,
        y: Double,
        z: Double,
    ) {
        // Prevent teleportation
    }

    override fun randomTeleport(
        x: Double,
        y: Double,
        z: Double,
        broadcast: Boolean,
        avoidanceTag: TagKey<Block>,
    ): Boolean = false

    override fun canTeleport(
        from: Level,
        to: Level,
    ): Boolean = false

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun isPushable(): Boolean = false

    override fun isPushedByFluid(): Boolean = false

    override fun mobInteract(
        player: Player,
        hand: InteractionHand,
    ): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS
        }
        if (player is ServerPlayer) {
            val balance = HalloweenEvent.balanceManager?.getBalance(player.uuid) ?: 0
            val wares =
                if (HalloweenEvent.activeMerchantWares.isNotEmpty()) {
                    HalloweenEvent.activeMerchantWares
                } else {
                    val count = minOf(HalloweenEvent.ALL_WARES_POOL.size, Random.nextInt(6, 9))
                    val generated = HalloweenEvent.ALL_WARES_POOL.shuffled().take(count)
                    HalloweenEvent.activeMerchantWares = generated
                    generated
                }
            val prices = wares.map { HalloweenEvent.config.prices[it] ?: 100 }
            val hasPet = HalloweenEvent.instance?.hasActiveGhostPet(player) ?: false

            ServerPlayNetworking.send(player, OpenMerchantGuiPayload(balance, wares, prices, hasPet))

            level().playSound(
                null,
                x,
                y,
                z,
                SoundEvents.VILLAGER_YES,
                SoundSource.NEUTRAL,
                1.0f,
                1.0f,
            )
            return InteractionResult.SUCCESS
        }
        return InteractionResult.CONSUME
    }

    override fun getOffers(): MerchantOffers = MerchantOffers()

    override fun updateTrades(level: ServerLevel) {
    }

    override fun openTradingScreen(
        player: Player,
        displayName: Component,
        level: Int,
    ) {
    }
}
