package com.fome.exservermod.halloween.entity

import com.fome.exservermod.halloween.HalloweenEvent
import com.fome.exservermod.halloween.item.CandyPouchItem
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID

class CandyEntity(
    type: EntityType<*>,
    level: Level,
) : Entity(type, level),
    ItemSupplier {
    override fun getItem(): ItemStack = ItemStack(Items.PUMPKIN_PIE)

    override fun getName(): Component = Component.translatable("exservermod.halloween.candy_name")

    override fun getDisplayName(): Component = Component.translatable("exservermod.halloween.candy_name")

    override fun hurtServer(
        level: ServerLevel,
        source: DamageSource,
        damage: Float,
    ): Boolean = false

    private var activeChannelerUuid: UUID? = null
    private var channelProgressTicks: Int = 0

    init {
        noPhysics = true
        customName = Component.translatable("exservermod.halloween.candy_name")
        addTag("marker")
        addTag("no_health")
        addTag("hide_health")
        addTag("no_display")
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        // No client-synced custom variables needed; client interpolates rotation via tickCount
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        channelProgressTicks = input.getIntOr("ChannelProgress", 0)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt("ChannelProgress", channelProgressTicks)
    }

    override fun tick() {
        super.tick()

        val sLevel = level() as? ServerLevel ?: return

        // Ambient particles
        if (tickCount % 5 == 0) {
            sLevel.sendParticles(
                ParticleTypes.FLAME,
                x,
                y + 0.3,
                z,
                2,
                0.15,
                0.15,
                0.15,
                0.01,
            )
        }

        // Proximity detection
        val nearestPlayer = sLevel.getNearestPlayer(this, 3.0) as? ServerPlayer
        if (nearestPlayer == null) {
            resetChannel()
            return
        }

        // Verify if currently channeling
        if (activeChannelerUuid == nearestPlayer.uuid) {
            val (requiredTicks, rewardYield) = getPouchStats(nearestPlayer)
            channelProgressTicks++

            nearestPlayer.sendSystemMessage(
                Component.translatable("exservermod.halloween.collect_prompt_channeling"),
                true,
            )

            if (channelProgressTicks >= requiredTicks) {
                // Successfully gathered!
                val newBalance = HalloweenEvent.balanceManager?.addCandies(nearestPlayer, rewardYield, sendActionBar = false) ?: 0
                val timeLeft = HalloweenEvent.instance?.formattedTimeLeft() ?: "0:00"

                val collectMsg =
                    Component
                        .translatable(
                            "exservermod.halloween.candy_collected_with_time",
                            rewardYield,
                            newBalance,
                            timeLeft,
                        ).withStyle(ChatFormatting.GOLD)

                nearestPlayer.sendSystemMessage(collectMsg, false)
                nearestPlayer.sendSystemMessage(collectMsg, true)

                sLevel.playSound(
                    null,
                    x,
                    y,
                    z,
                    SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS,
                    1.2f,
                    1.4f,
                )
                sLevel.playSound(
                    null,
                    x,
                    y,
                    z,
                    SoundEvents.PLAYER_LEVELUP,
                    SoundSource.PLAYERS,
                    0.8f,
                    1.6f,
                )
                sLevel.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    x,
                    y + 0.5,
                    z,
                    15,
                    0.3,
                    0.3,
                    0.3,
                    0.1,
                )

                discard()
            }
        } else {
            nearestPlayer.sendSystemMessage(
                Component.translatable("exservermod.halloween.collect_prompt"),
                true,
            )
        }
    }

    override fun interact(
        player: Player,
        hand: InteractionHand,
        location: Vec3,
    ): InteractionResult {
        if (player is ServerPlayer && hand == InteractionHand.MAIN_HAND) {
            activeChannelerUuid = player.uuid
            return InteractionResult.SUCCESS
        }
        return InteractionResult.PASS
    }

    fun setChanneling(
        playerUuid: UUID,
        isHolding: Boolean,
    ) {
        if (isHolding) {
            activeChannelerUuid = playerUuid
        } else if (activeChannelerUuid == playerUuid) {
            resetChannel()
        }
    }

    private fun resetChannel() {
        activeChannelerUuid = null
        channelProgressTicks = 0
    }

    private fun getPouchStats(player: ServerPlayer): Pair<Int, Int> {
        val mainHand = player.mainHandItem.item as? CandyPouchItem
        val offHand = player.offhandItem.item as? CandyPouchItem

        val bestPouch =
            when {
                mainHand != null && offHand != null -> if (mainHand.tier >= offHand.tier) mainHand else offHand
                mainHand != null -> mainHand
                offHand != null -> offHand
                else -> null
            }

        return if (bestPouch != null) {
            Pair(bestPouch.channelDurationTicks, bestPouch.candyReward)
        } else {
            Pair(100, 25) // Base: 5.0 seconds, +25 candies
        }
    }
}
