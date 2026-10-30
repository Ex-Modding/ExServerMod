package com.fome.exservermod.waves

import com.fome.exservermod.halloween.HalloweenEvent
import com.fome.exservermod.island.NightmareIslandManager
import com.fome.exservermod.mixin.CreeperAccessor
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Witch
import net.minecraft.world.entity.monster.hoglin.Hoglin
import net.minecraft.world.entity.monster.illager.Vindicator
import net.minecraft.world.entity.monster.piglin.AbstractPiglin
import net.minecraft.world.entity.monster.piglin.PiglinBrute
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.warden.Warden
import net.minecraft.world.entity.monster.warden.WardenAi
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.projectile.EvokerFangs
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.Enchantable
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.Repairable
import net.minecraft.world.item.equipment.Equippable
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Server-side engine for the wave defense mini-game:
 * 11 nightmare waves, raid bossbar, custom mob mechanics (Blaze champion, Witch coven,
 * Ghost creeper lingering cloud, Spectral vexes), permadeath failure with spectral dissolve,
 * anti-turtle wall-piercing attacks for mansion hideouts, zero world damage, and rewards.
 */
class WaveManager {
    companion object {
        const val SPAWN_RING_MIN = 14.0
        const val SPAWN_RING_MAX = 45.0
        const val VICTORY_XP = 750
        const val CLEANUP_CHECK_INTERVAL_TICKS = 20
        const val LOCATE_COOLDOWN_MS = 60_000L
        const val TAG_WAVE_MOB = "ex_wave_mob"
        const val TAG_WAVE_PROJECTILE = "ex_wave_projectile"

        private var activeInstance: WaveManager? = null

        @JvmStatic
        fun isWaveProjectile(entity: Entity): Boolean {
            if (entity.entityTags().contains(TAG_WAVE_PROJECTILE)) {
                return true
            }
            if (entity is Projectile) {
                val owner = entity.owner
                if (owner != null && activeInstance?.trackedMobs?.contains(owner.uuid) == true) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun isTrackedMob(uuid: UUID): Boolean = activeInstance?.trackedMobs?.contains(uuid) == true
    }

    init {
        activeInstance = this
    }

    val game = WaveDefenseGame()

    /** Mobs spawned by the current wave, keyed by entity UUID. */
    val trackedMobs: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Roles of tracked mobs for custom AI/abilities. */
    private val trackedMobRoles: MutableMap<UUID, WaveMobRole> = ConcurrentHashMap()

    /** Total mobs expected in the current wave (used for boss bar progress). */
    private var currentWaveTotalMobs: Int = 0

    /** Raid-style boss bar displayed to defenders. */
    private val bossBar: ServerBossEvent =
        ServerBossEvent(
            UUID.randomUUID(),
            Component.empty(),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10,
        ).apply {
            isVisible = false
        }

    private var arenaCenter: Vec3? = null
    private var arenaLevel: ServerLevel? = null

    // Ability cooldown timers (in ticks)
    private var blazeHellfireTimer: Int = 0
    private var blazeHeatWaveTimer: Int = 0
    private var witchVolleyTimer: Int = 0
    private var witchDashTimer: Int = 0
    private var antiTurtleTimer: Int = 0
    private var potionIndex: Int = 0

    // Defender position tracker to detect camping inside mansion rooms (stationary > 8s)
    private val defenderStationaryTicks: MutableMap<UUID, Pair<Vec3, Int>> = ConcurrentHashMap()

    private var lastLocateTimestamp: Long = 0L

    private val checkpointFile: File by lazy {
        FabricLoader
            .getInstance()
            .configDir
            .resolve("exservermod/wave_checkpoint.json")
            .toFile()
    }
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun getSavedCheckpoint(): Int? =
        try {
            if (!checkpointFile.exists()) {
                null
            } else {
                val data = gson.fromJson(checkpointFile.readText(), WaveCheckpointData::class.java)
                if (data != null && data.wave in 2..WaveDefenseGame.TOTAL_WAVES) data.wave else null
            }
        } catch (_: Exception) {
            null
        }

    fun saveCheckpoint(wave: Int) {
        try {
            checkpointFile.parentFile?.mkdirs()
            val data = WaveCheckpointData(wave, System.currentTimeMillis())
            checkpointFile.writeText(gson.toJson(data))
        } catch (_: Exception) {
        }
    }

    fun clearCheckpoint() {
        try {
            if (checkpointFile.exists()) {
                checkpointFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun createHorsemanLantern(level: ServerLevel): ItemStack {
        val stack = ItemStack(Items.JACK_O_LANTERN)
        stack.set(
            DataComponents.CUSTOM_NAME,
            Component
                .translatable("exservermod.waves.horseman_lantern.name")
                .withStyle { it.withItalic(false).withColor(ChatFormatting.GOLD) },
        )
        stack.set(
            DataComponents.LORE,
            ItemLore(
                listOf(
                    Component
                        .translatable("exservermod.waves.horseman_lantern.lore1")
                        .withStyle { it.withItalic(true).withColor(ChatFormatting.DARK_PURPLE) },
                    Component
                        .translatable("exservermod.waves.horseman_lantern.lore2")
                        .withStyle { it.withItalic(false).withColor(ChatFormatting.YELLOW) },
                    Component
                        .translatable("exservermod.waves.horseman_lantern.lore3")
                        .withStyle { it.withItalic(false).withColor(ChatFormatting.AQUA) },
                ),
            ),
        )
        val equippable =
            Equippable
                .builder(EquipmentSlot.HEAD)
                .setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC)
                .setSwappable(true)
                .build()
        stack.set(DataComponents.EQUIPPABLE, equippable)
        enchantItem(level, stack, Enchantments.PROTECTION, 4)
        val tag =
            CompoundTag().apply {
                putBoolean("ex_horseman_lantern", true)
            }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return stack
    }

    fun isHorsemanLantern(stack: ItemStack): Boolean {
        if (stack.item != Items.JACK_O_LANTERN) return false
        val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
        return customData.copyTag().getBooleanOr("ex_horseman_lantern", false)
    }

    fun broadcastTrickOrTreatPrompt(server: MinecraftServer) {
        val savedCheckpoint = getSavedCheckpoint()
        val seconds = WaveDefenseGame.TRICK_OR_TREAT_SECONDS
        val header = Component.translatable("exservermod.waves.tot.title").withStyle(ChatFormatting.GOLD)
        val subtitle = Component.translatable("exservermod.waves.tot.subtitle", seconds).withStyle(ChatFormatting.YELLOW)
        val trickBtn =
            Component.translatable("exservermod.waves.tot.button_trick").withStyle { style ->
                style
                    .withColor(ChatFormatting.RED)
                    .withBold(true)
                    .withClickEvent(ClickEvent.RunCommand("/waves choice trick"))
                    .withHoverEvent(HoverEvent.ShowText(Component.translatable("exservermod.waves.tot.hover_trick")))
            }
        val treatBtn =
            if (savedCheckpoint != null) {
                Component.translatable("exservermod.waves.tot.button_treat_resume", savedCheckpoint).withStyle { style ->
                    style
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(ClickEvent.RunCommand("/waves choice treat"))
                        .withHoverEvent(
                            HoverEvent.ShowText(Component.translatable("exservermod.waves.tot.hover_treat_resume", savedCheckpoint)),
                        )
                }
            } else {
                Component.translatable("exservermod.waves.tot.button_treat").withStyle { style ->
                    style
                        .withColor(ChatFormatting.GOLD)
                        .withBold(true)
                        .withClickEvent(ClickEvent.RunCommand("/waves choice treat"))
                        .withHoverEvent(HoverEvent.ShowText(Component.translatable("exservermod.waves.tot.hover_treat")))
                }
            }
        val buttonsLine =
            Component
                .empty()
                .append("   ")
                .append(trickBtn)
                .append("    ")
                .append(treatBtn)

        server.playerList.broadcastSystemMessage(Component.empty(), false)
        server.playerList.broadcastSystemMessage(header, false)
        server.playerList.broadcastSystemMessage(subtitle, false)
        server.playerList.broadcastSystemMessage(buttonsLine, false)
        server.playerList.broadcastSystemMessage(Component.empty(), false)

        playSoundToDefenders(server, SoundEvents.WITHER_SPAWN, 1.0f, 1.5f)
        playSoundToDefenders(server, SoundEvents.GHAST_SCREAM, 0.8f, 0.9f)
    }

    fun handleHalloweenChoice(
        player: ServerPlayer,
        choice: HalloweenChoice,
    ): Boolean {
        val server = player.level().server ?: return false
        if (game.phase != WavePhase.TRICK_OR_TREAT) {
            player.sendSystemMessage(Component.translatable("exservermod.waves.error.not_in_choice").withStyle(ChatFormatting.RED))
            return false
        }
        if (!game.defenders.contains(player.uuid)) {
            player.sendSystemMessage(Component.translatable("exservermod.waves.error.not_a_defender").withStyle(ChatFormatting.RED))
            return false
        }
        when (choice) {
            HalloweenChoice.TRICK -> {
                if (!game.chooseTrick()) return false
                server.playerList.broadcastSystemMessage(
                    Component.translatable("exservermod.waves.tot.announced_trick", player.name).withStyle(ChatFormatting.DARK_RED),
                    false,
                )
                playSoundToDefenders(server, SoundEvents.ENDERMAN_SCREAM, 1.2f, 0.7f)
                playSoundToDefenders(server, SoundEvents.WITHER_SHOOT, 1.0f, 0.8f)
                return true
            }
            HalloweenChoice.TREAT -> {
                val savedCheckpoint = getSavedCheckpoint()
                if (!game.chooseTreat(savedCheckpoint)) return false
                if (savedCheckpoint != null) {
                    clearCheckpoint()
                    server.playerList.broadcastSystemMessage(
                        Component
                            .translatable(
                                "exservermod.waves.tot.announced_treat_resume",
                                player.name,
                                savedCheckpoint,
                            ).withStyle(ChatFormatting.AQUA),
                        false,
                    )
                    playSoundToDefenders(server, SoundEvents.PORTAL_TRAVEL, 1.0f, 1.2f)
                    playSoundToDefenders(server, SoundEvents.PLAYER_LEVELUP, 1.2f, 1.1f)
                } else {
                    for (uuid in game.defenders) {
                        val defender = server.playerList.getPlayer(uuid) ?: continue
                        val stack = ItemStack(Items.EXPERIENCE_BOTTLE, 64)
                        if (!defender.inventory.add(stack)) {
                            defender.drop(stack, false)
                        }
                    }
                    server.playerList.broadcastSystemMessage(
                        Component.translatable("exservermod.waves.tot.announced_treat_fresh", player.name).withStyle(ChatFormatting.GOLD),
                        false,
                    )
                    playSoundToDefenders(server, SoundEvents.PLAYER_LEVELUP, 1.2f, 1.2f)
                    playSoundToDefenders(server, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f, 1.0f)
                }
                return true
            }
            HalloweenChoice.NONE -> return false
        }
    }

    fun register() {
        registerTick()
        registerEntityDeath()
        registerServerStopping()
    }

    private fun registerTick() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            when (val result = game.tick()) {
                is WaveTickResult.WaveStarted -> spawnWave(server, result)
                is WaveTickResult.Victory -> onVictory(server)
                is WaveTickResult.TrickWaveCleared -> {
                    server.playerList.broadcastSystemMessage(
                        Component.translatable("exservermod.waves.tot.trick_cleared").withStyle(ChatFormatting.GOLD),
                        false,
                    )
                    playSoundToDefenders(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f)
                }
                is WaveTickResult.PhaseChanged -> {
                    if (result.oldPhase == WavePhase.TRICK_OR_TREAT && result.newPhase == WavePhase.INTERMISSION) {
                        server.playerList.broadcastSystemMessage(
                            Component.translatable("exservermod.waves.tot.timeout").withStyle(ChatFormatting.GRAY),
                            false,
                        )
                        playSoundToDefenders(server, SoundEvents.NOTE_BLOCK_BASS, 1.0f, 0.8f)
                    }
                }
                else -> {}
            }

            if (server.tickCount % 20 == 0) {
                for (player in server.playerList.players) {
                    val helmet = player.getItemBySlot(EquipmentSlot.HEAD)
                    if (isHorsemanLantern(helmet)) {
                        player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, 240, 0, false, false, true))
                    }
                }
            }

            if (game.isActive()) {
                updateBossBar(server)
            } else if (bossBar.isVisible) {
                bossBar.removeAllPlayers()
                bossBar.isVisible = false
            }

            if (game.isFighting()) {
                tickAbilities(server)
            }

            if (server.tickCount % CLEANUP_CHECK_INTERVAL_TICKS == 0) {
                reapMissingMobs()
                if (game.isFighting() && trackedMobs.isEmpty()) {
                    onWaveCleared(server)
                }

                if (game.isActive()) {
                    val onlineUuids =
                        server.playerList.players
                            .map { it.uuid }
                            .toSet()
                    if (game.areAllDefendersOffline(onlineUuids)) {
                        abort(server, Component.translatable("exservermod.waves.abort_no_defenders"))
                    }
                }
            }

            if (game.isActive()) {
                for (uuid in game.defenders) {
                    val defender = server.playerList.getPlayer(uuid) ?: continue
                    if (defender.isFallFlying) {
                        defender.stopFallFlying()
                        defender.sendSystemMessage(
                            Component.translatable("exservermod.waves.elytra_forbidden").withStyle(ChatFormatting.RED),
                            true,
                        )
                    }
                }
            }

            if (server.tickCount % WaveDefenseGame.TICKS_PER_SECOND == 0 && game.isActive()) {
                renderHud(server)
            }
        }
    }

    private fun registerEntityDeath() {
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
            val server = entity.level().server ?: return@register

            // Hardcore permadeath: death of ANY defender ends the defense immediately!
            if (entity is ServerPlayer && game.isActive() && game.defenders.contains(entity.uuid)) {
                onDefenderDied(server, entity)
                return@register
            }

            if (trackedMobs.remove(entity.uuid)) {
                val role = trackedMobRoles.remove(entity.uuid)
                game.registerKill()

                // Halloween event drop: 3 candies for standard mob, 200 for boss/champion
                val killer =
                    when (val damager = damageSource.entity) {
                        is ServerPlayer -> damager
                        is TamableAnimal -> damager.owner as? ServerPlayer
                        else ->
                            (entity.killCredit as? ServerPlayer)
                                ?: (entity.lastHurtByMob as? ServerPlayer)
                                ?: game.defenders
                                    .mapNotNull { server.playerList.getPlayer(it) }
                                    .minByOrNull { it.distanceToSqr(entity) }
                    }
                if (killer != null && game.defenders.contains(killer.uuid)) {
                    val isBossOrChamp =
                        role == WaveMobRole.CHAMPION ||
                            role == WaveMobRole.BLAZE_CHAMPION ||
                            role == WaveMobRole.WITCH_CHAMPION
                    val candyAmount = if (isBossOrChamp) 200 else HalloweenEvent.config.mobCandyDrop
                    val newBal = HalloweenEvent.balanceManager?.addCandies(killer, candyAmount, sendActionBar = false) ?: 0
                    val timeLeft =
                        if (HalloweenEvent.instance?.phase ==
                            com.fome.exservermod.halloween.HalloweenPhase.ACTIVE_GATHERING
                        ) {
                            " | ⏱ ${HalloweenEvent.instance?.formattedTimeLeft()}"
                        } else {
                            ""
                        }
                    killer.sendSystemMessage(
                        Component
                            .translatable(
                                "exservermod.halloween.candy_mob_kill",
                                candyAmount,
                                newBal,
                                timeLeft,
                            ).withStyle(ChatFormatting.GOLD),
                        true,
                    )
                    val sLevel = entity.level() as? ServerLevel
                    sLevel?.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        entity.x,
                        entity.y + 0.5,
                        entity.z,
                        6,
                        0.2,
                        0.2,
                        0.2,
                        0.05,
                    )
                }

                // Ghost creeper death / explosion creates lingering spectral cloud
                if (role == WaveMobRole.GHOST_CREEPER) {
                    spawnGhostCloud(entity)
                }

                if (game.isFighting() && trackedMobs.isEmpty()) {
                    onWaveCleared(server)
                }
            }
        }
    }

    private fun registerServerStopping() {
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            setArenaChunksForced(arenaLevel, arenaCenter, false)
            clearMobs()
            bossBar.removeAllPlayers()
            bossBar.isVisible = false
            game.stop()
        }
    }

    fun tryStart(
        source: ServerPlayer,
        defenders: List<ServerPlayer>,
        customCenter: Vec3? = null,
    ): Boolean {
        if (game.isActive()) {
            return false
        }
        val level = source.level()
        arenaCenter = customCenter ?: source.position()
        arenaLevel = level
        currentWaveTotalMobs = 0
        blazeHellfireTimer = 0
        blazeHeatWaveTimer = 0
        witchVolleyTimer = 0
        witchDashTimer = 0
        antiTurtleTimer = 0
        potionIndex = 0
        lastLocateTimestamp = 0L
        defenderStationaryTicks.clear()

        val started = game.start(defenders.map { it.uuid })
        if (started) {
            bossBar.isVisible = true
            bossBar.removeAllPlayers()
            for (player in defenders) {
                bossBar.addPlayer(player)
            }
            setArenaChunksForced(level, arenaCenter, true)
        }
        return started
    }

    fun tryStop(server: MinecraftServer): Boolean {
        if (!game.isActive()) {
            return false
        }
        abort(server, Component.translatable("exservermod.waves.stopped_generic"))
        return true
    }

    fun isRunning(): Boolean = game.isActive()

    fun statusLine(): Component =
        Component
            .translatable(
                "exservermod.waves.status",
                game.currentWave.coerceAtLeast(1),
                WaveDefenseGame.TOTAL_WAVES,
                game.kills,
                Component.translatable(game.phase.translationKey),
            ).withStyle(ChatFormatting.GOLD)

    private fun abort(
        server: MinecraftServer,
        reason: MutableComponent,
    ) {
        setArenaChunksForced(arenaLevel, arenaCenter, false)
        clearMobs()
        bossBar.removeAllPlayers()
        bossBar.isVisible = false
        game.stop()
        server.playerList.broadcastSystemMessage(reason.withStyle(ChatFormatting.GRAY), false)
    }

    /**
     * Permadeath handler: when ONE defender dies, the run is terminated.
     * All wave mobs dissolve into vapor/particles with ghost sounds.
     */
    private fun onDefenderDied(
        server: MinecraftServer,
        fallenDefender: ServerPlayer,
    ) {
        if (!game.isActive()) return
        val currentWave = game.currentWave
        if (game.triggerSecondChanceRecovery()) {
            clearMobs()
            playSoundToDefenders(server, SoundEvents.TOTEM_USE, 1.5f, 1.0f)
            playSoundToDefenders(server, SoundEvents.BELL_RESONATE, 2.0f, 0.8f)
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable(
                        "exservermod.waves.tot.second_chance_used",
                        fallenDefender.name,
                        WaveDefenseGame.RECOVERY_SECONDS,
                        currentWave,
                    ).withStyle(ChatFormatting.GOLD),
                false,
            )
            return
        }
        val kills = game.kills
        val defendersList = game.defenders.toSet() + fallenDefender.uuid
        val shouldSaveCheckpoint = game.willSaveCheckpointOnDeath && currentWave >= 2
        if (shouldSaveCheckpoint) {
            saveCheckpoint(currentWave)
            server.playerList.broadcastSystemMessage(
                Component.translatable("exservermod.waves.tot.checkpoint_saved", currentWave).withStyle(ChatFormatting.YELLOW),
                false,
            )
        }

        setArenaChunksForced(arenaLevel, arenaCenter, false)

        // Dissolve all wave mobs with vapor and ghost audio cues
        dissolveAllMobsWithSpookyEffects()

        bossBar.removeAllPlayers()
        bossBar.isVisible = false
        game.stop()

        for (uuid in defendersList) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            val anim = ClientboundSetTitlesAnimationPacket(10, 90, 20)
            val title =
                ClientboundSetTitleTextPacket(
                    Component
                        .translatable("exservermod.waves.defeat_title")
                        .withStyle(ChatFormatting.DARK_RED),
                )
            val subtitle =
                ClientboundSetSubtitleTextPacket(
                    Component
                        .translatable(
                            "exservermod.waves.defeat_subtitle",
                            fallenDefender.name,
                            currentWave,
                            WaveDefenseGame.TOTAL_WAVES,
                        ).withStyle(ChatFormatting.RED),
                )
            player.connection.send(anim)
            player.connection.send(title)
            player.connection.send(subtitle)
        }

        playSoundToPlayers(server, defendersList, SoundEvents.WITHER_DEATH, 1.2f, 0.6f)
        playSoundToPlayers(server, defendersList, SoundEvents.VEX_DEATH, 1.5f, 0.8f)

        server.playerList.broadcastSystemMessage(
            Component
                .translatable(
                    "exservermod.waves.defeat_chat",
                    fallenDefender.name,
                    currentWave,
                    WaveDefenseGame.TOTAL_WAVES,
                    kills,
                ).withStyle(ChatFormatting.RED),
            false,
        )
    }

    private fun dissolveAllMobsWithSpookyEffects() {
        val level = arenaLevel ?: return
        for (uuid in trackedMobs) {
            val entity = level.getEntity(uuid)
            if (entity is LivingEntity && entity.isAlive) {
                val cx = entity.x
                val cy = entity.y + entity.bbHeight / 2.0
                val cz = entity.z
                level.sendParticles(ParticleTypes.POOF, cx, cy, cz, 30, 0.4, 0.4, 0.4, 0.08)
                level.sendParticles(ParticleTypes.CLOUD, cx, cy, cz, 20, 0.3, 0.3, 0.3, 0.05)
                level.sendParticles(ParticleTypes.SOUL, cx, cy, cz, 15, 0.2, 0.25, 0.2, 0.05)
                level.playSound(null, cx, cy, cz, SoundEvents.SOUL_ESCAPE, SoundSource.HOSTILE, 1.0f, 1.2f)
                level.playSound(null, cx, cy, cz, SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.8f, 1.5f)
                entity.discard()
            }
        }
        trackedMobs.clear()
        trackedMobRoles.clear()
    }

    private fun clearMobs() {
        val level = arenaLevel
        if (level != null) {
            for (uuid in trackedMobs) {
                val entity = level.getEntity(uuid)
                if (entity is Mob && entity.isAlive) {
                    entity.discard()
                }
            }
        }
        trackedMobs.clear()
        trackedMobRoles.clear()
    }

    private fun reapMissingMobs() {
        val level = arenaLevel ?: return
        val iterator = trackedMobs.iterator()
        while (iterator.hasNext()) {
            val uuid = iterator.next()
            val entity = level.getEntity(uuid)
            if (entity == null || !entity.isAlive) {
                iterator.remove()
                trackedMobRoles.remove(uuid)
                game.registerKill()
            }
        }
    }

    private fun spawnWave(
        server: MinecraftServer,
        result: WaveTickResult.WaveStarted,
    ) {
        val level = arenaLevel ?: return
        val center = arenaCenter ?: return

        currentWaveTotalMobs = WaveDefenseGame.totalMobsInWave(result.groups)
        if (result.isTrick) {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable(
                        "exservermod.waves.wave_announce_trick",
                        result.wave,
                        result.totalWaves,
                    ).withStyle(ChatFormatting.DARK_RED),
                false,
            )
            playSoundToDefenders(server, SoundEvents.WITHER_SPAWN, 1.5f, 0.6f)
            playSoundToDefenders(server, SoundEvents.ENDER_DRAGON_GROWL, 1.5f, 0.7f)
        } else {
            announceWave(server, result.wave, result.totalWaves, WaveDefenseGame.isBossWave(result.wave))
        }

        var spawnedAny = false
        for (group in result.groups) {
            val mobId = Identifier.tryParse(group.mobId) ?: continue
            val mobType = BuiltInRegistries.ENTITY_TYPE.getOptional(mobId).orElse(null) ?: continue
            repeat(group.count) {
                val pos = findSpawnPos(level, center) ?: return@repeat
                val mob = spawnMob(level, mobType, pos, group.role) ?: return@repeat
                mob.addTag("wave_mob")
                trackedMobs.add(mob.uuid)
                trackedMobRoles[mob.uuid] = group.role
                spawnedAny = true
            }
        }

        if (!spawnedAny) {
            trackedMobs.clear()
            trackedMobRoles.clear()
            onWaveCleared(server)
        } else {
            currentWaveTotalMobs = trackedMobs.size
        }
    }

    private fun spawnMob(
        level: ServerLevel,
        mobType: EntityType<*>,
        pos: BlockPos,
        role: WaveMobRole,
    ): Mob? {
        val mob = mobType.create(level, EntitySpawnReason.EVENT) as? Mob ?: return null

        // Zero world damage for creepers via Mixin Accessor (guaranteed safe across mappings)
        if (mob is Creeper) {
            (mob as CreeperAccessor).`exservermod$setExplosionRadius`(0)
        }

        // Prevent zombies from breaking doors in the arena
        if (mob is Zombie) {
            mob.setCanBreakDoors(false)
        }

        mob.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        mob.yRot = Random.nextFloat() * 360f
        mob.xRot = 0f
        mob.setPersistenceRequired()
        mob.addTag(TAG_WAVE_MOB)

        // Ensure wave mobs never drop gear or disrupt survival economy
        for (slot in EquipmentSlot.entries) {
            mob.setDropChance(slot, 0.0f)
        }

        // Instant aggro and pursuit: charge the nearest defender immediately
        val nearestDefender =
            game.defenders
                .mapNotNull { level.server.playerList.getPlayer(it) }
                .filter { it.isAlive && it.level().dimension() == level.dimension() }
                .minByOrNull { it.distanceToSqr(mob.x, mob.y, mob.z) }

        if (nearestDefender != null) {
            mob.target = nearestDefender
            if (mob is Warden) {
                mob.increaseAngerAt(nearestDefender, 150, true)
            }
        }
        mob.getAttribute(Attributes.FOLLOW_RANGE)?.let { it.baseValue = 128.0 }

        when (role) {
            WaveMobRole.BLAZE_CHAMPION -> configureBlazeChampion(mob)
            WaveMobRole.WITCH_CHAMPION -> configureWitchChampion(mob)
            WaveMobRole.GHOST_CREEPER -> configureGhostCreeper(mob)
            WaveMobRole.CHAMPION -> configureDreadChampion(level, mob)
            WaveMobRole.SPECTRAL_VEX -> configureSpectralVex(mob)
            WaveMobRole.STANDARD -> configureMobEquipment(level, mob, game.currentWave)
        }

        level.addFreshEntity(mob)
        return mob
    }

    private fun configureBlazeChampion(mob: Mob) {
        mob.setCustomName(
            Component
                .translatable("exservermod.waves.mob.blaze_champion")
                .withStyle(ChatFormatting.GOLD),
        )
        mob.isCustomNameVisible = false
        mob.addTag("ex_blaze_champion")

        mob.getAttribute(Attributes.MAX_HEALTH)?.let { it.baseValue = 120.0 }
        mob.health = 120.0f
        mob.getAttribute(Attributes.ARMOR)?.let { it.baseValue = 10.0 }
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.let { it.baseValue = 0.8 }
        mob.addEffect(MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false))
    }

    private fun configureWitchChampion(mob: Mob) {
        mob.setCustomName(
            Component
                .translatable("exservermod.waves.mob.witch_champion")
                .withStyle(ChatFormatting.DARK_PURPLE),
        )
        mob.isCustomNameVisible = false
        mob.addTag("ex_witch_champion")

        mob.getAttribute(Attributes.MAX_HEALTH)?.let { it.baseValue = 140.0 }
        mob.health = 140.0f
        mob.getAttribute(Attributes.MOVEMENT_SPEED)?.let { it.baseValue = 0.32 }
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.let { it.baseValue = 0.7 }
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.BRUSH))
        mob.setDropChance(EquipmentSlot.MAINHAND, 0.0f)
        mob.addEffect(MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false))
    }

    private fun configureGhostCreeper(mob: Mob) {
        mob.setCustomName(
            Component
                .translatable("exservermod.waves.mob.ghost_creeper")
                .withStyle(ChatFormatting.DARK_AQUA),
        )
        mob.isCustomNameVisible = false
        mob.addTag("ex_ghost_creeper")
        mob.getAttribute(Attributes.MOVEMENT_SPEED)?.let { it.baseValue = 0.28 }
    }

    private fun configureDreadChampion(
        level: ServerLevel,
        mob: Mob,
    ) {
        mob.setCustomName(
            Component
                .translatable("exservermod.waves.mob.dread_champion")
                .withStyle(ChatFormatting.RED),
        )
        mob.isCustomNameVisible = false
        mob.addTag("ex_dread_champion")

        // Guaranteed terrifying baby zombie Dread Champion
        if (mob is Zombie) {
            mob.setBaby(true)
        }

        mob.getAttribute(Attributes.MAX_HEALTH)?.let { it.baseValue = 100.0 }
        mob.health = 100.0f
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.let { it.baseValue = 0.8 }
        mob.getAttribute(Attributes.MOVEMENT_SPEED)?.let { it.baseValue = 0.35 }

        val helmet = ItemStack(Items.NETHERITE_HELMET)
        val chest = ItemStack(Items.NETHERITE_CHESTPLATE)
        val legs = ItemStack(Items.NETHERITE_LEGGINGS)
        val boots = ItemStack(Items.NETHERITE_BOOTS)
        val sword = ItemStack(Items.NETHERITE_SWORD)

        enchantItem(level, chest, Enchantments.PROTECTION, 4)
        enchantItem(level, sword, Enchantments.SHARPNESS, 5)

        mob.setItemSlot(EquipmentSlot.HEAD, helmet)
        mob.setItemSlot(EquipmentSlot.CHEST, chest)
        mob.setItemSlot(EquipmentSlot.LEGS, legs)
        mob.setItemSlot(EquipmentSlot.FEET, boots)
        mob.setItemSlot(EquipmentSlot.MAINHAND, sword)

        for (slot in EquipmentSlot.entries) {
            mob.setDropChance(slot, 0.0f)
        }

        mob.addEffect(MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 1, false, false))
        mob.addEffect(MobEffectInstance(MobEffects.REGENERATION, MobEffectInstance.INFINITE_DURATION, 1, false, false))
        mob.addEffect(MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 1, false, false))
        mob.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false))
    }

    private fun configureSpectralVex(mob: Mob) {
        mob.setCustomName(
            Component
                .translatable("exservermod.waves.mob.spectral_vex")
                .withStyle(ChatFormatting.AQUA),
        )
        mob.isCustomNameVisible = false
        mob.addTag("ex_spectral_vex")
        mob.getAttribute(Attributes.MAX_HEALTH)?.let { it.baseValue = 24.0 }
        mob.health = 24.0f
    }

    private fun configureWaveWarden(mob: Warden) {
        mob.getAttribute(Attributes.MAX_HEALTH)?.let { it.baseValue = 350.0 }
        mob.health = 350.0f
        mob.getAttribute(Attributes.ATTACK_DAMAGE)?.let { it.baseValue = 12.0 }
        mob.getAttribute(Attributes.MOVEMENT_SPEED)?.let { it.baseValue = 0.30 }
        mob.getAttribute(Attributes.FOLLOW_RANGE)?.let { it.baseValue = 128.0 }
        mob.setPersistenceRequired()
        WardenAi.setDigCooldown(mob)
        SonicBoom.setCooldown(mob, 100_000)
    }

    private fun enchantItem(
        level: ServerLevel,
        stack: ItemStack,
        key: ResourceKey<Enchantment>,
        enchantLevel: Int,
    ) {
        try {
            val holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key)
            stack.enchant(holder, enchantLevel)
        } catch (_: Exception) {
        }
    }

    private fun configureMobEquipment(
        level: ServerLevel,
        mob: Mob,
        wave: Int,
    ) {
        mob.isCustomNameVisible = false

        // Undead sun protection: infinite fire resistance
        val isUndead = mob is Zombie || mob is AbstractSkeleton
        if (isUndead) {
            mob.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false))
        }

        // Prevent Nether mobs from zombifying in Overworld
        if (mob is AbstractPiglin) {
            mob.setImmuneToZombification(true)
        }
        if (mob is Hoglin) {
            mob.setImmuneToZombification(true)
            mob.getAttribute(Attributes.MAX_HEALTH)?.let { it.baseValue = 120.0 }
            mob.health = 120.0f
            mob.getAttribute(Attributes.MOVEMENT_SPEED)?.let { it.baseValue = 0.32 }
            return
        }

        // Warden anger & durability
        if (mob is Warden) {
            configureWaveWarden(mob)
            return
        }

        // Skeletons, Bogged, Strays: always give bows, powerful enchantments on later waves
        if (mob is AbstractSkeleton) {
            val bow = ItemStack(Items.BOW)
            when {
                wave >= 8 -> {
                    enchantItem(level, bow, Enchantments.POWER, 5)
                    enchantItem(level, bow, Enchantments.PUNCH, 5)
                    enchantItem(level, bow, Enchantments.FLAME, 1)
                }
                wave >= 4 -> {
                    enchantItem(level, bow, Enchantments.POWER, 4)
                    enchantItem(level, bow, Enchantments.PUNCH, 2)
                }
                wave >= 2 -> {
                    enchantItem(level, bow, Enchantments.POWER, 2)
                }
            }
            mob.setItemSlot(EquipmentSlot.MAINHAND, bow)
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.0f)

            // Helmets to prevent burning in daylight
            val helmetItem =
                when {
                    wave >= 8 -> Items.NETHERITE_HELMET
                    wave >= 4 -> Items.DIAMOND_HELMET
                    else -> Items.IRON_HELMET
                }
            mob.setItemSlot(EquipmentSlot.HEAD, ItemStack(helmetItem))
            mob.setDropChance(EquipmentSlot.HEAD, 0.0f)

            if (wave >= 8) {
                mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.NETHERITE_CHESTPLATE))
                mob.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.NETHERITE_LEGGINGS))
                mob.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.NETHERITE_BOOTS))
            } else if (wave >= 5) {
                mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.DIAMOND_CHESTPLATE))
            }
            for (slot in EquipmentSlot.entries) {
                mob.setDropChance(slot, 0.0f)
            }
            return
        }

        // Zombies, Husks, Drowned, Wither Skeletons
        if (mob is Zombie || mob is WitherSkeleton) {
            when {
                wave >= 8 -> {
                    // Guaranteed terrifying baby zombies on late waves (wave 9, 11)
                    if (mob is Zombie && Random.nextFloat() < 0.45f) {
                        mob.setBaby(true)
                    }
                    // Full Netherite + Strength II + Regen II + Speed I
                    val helmet = ItemStack(Items.NETHERITE_HELMET)
                    val chest = ItemStack(Items.NETHERITE_CHESTPLATE)
                    val legs = ItemStack(Items.NETHERITE_LEGGINGS)
                    val boots = ItemStack(Items.NETHERITE_BOOTS)
                    val weapon = ItemStack(Items.NETHERITE_SWORD)

                    enchantItem(level, chest, Enchantments.PROTECTION, 4)
                    enchantItem(level, weapon, Enchantments.SHARPNESS, 5)

                    mob.setItemSlot(EquipmentSlot.HEAD, helmet)
                    mob.setItemSlot(EquipmentSlot.CHEST, chest)
                    mob.setItemSlot(EquipmentSlot.LEGS, legs)
                    mob.setItemSlot(EquipmentSlot.FEET, boots)
                    mob.setItemSlot(EquipmentSlot.MAINHAND, weapon)

                    mob.addEffect(MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 1, false, false))
                    mob.addEffect(MobEffectInstance(MobEffects.REGENERATION, MobEffectInstance.INFINITE_DURATION, 1, false, false))
                    mob.addEffect(MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 0, false, false))
                }
                wave >= 5 -> {
                    // Full Diamond
                    mob.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.DIAMOND_HELMET))
                    mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.DIAMOND_CHESTPLATE))
                    mob.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.DIAMOND_LEGGINGS))
                    mob.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.DIAMOND_BOOTS))
                    mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.DIAMOND_SWORD))
                    mob.addEffect(MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 0, false, false))
                }
                wave >= 2 -> {
                    // Full Iron armor already from wave 2!
                    mob.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
                    mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.IRON_CHESTPLATE))
                    mob.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.IRON_LEGGINGS))
                    mob.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.IRON_BOOTS))
                    mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
                }
                else -> {
                    // Wave 1: Chainmail with iron helmet
                    mob.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
                    mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.CHAINMAIL_CHESTPLATE))
                    mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
                }
            }

            for (slot in EquipmentSlot.entries) {
                mob.setDropChance(slot, 0.0f)
            }
            return
        }

        // Vindicators
        if (mob is Vindicator) {
            val axeItem =
                when {
                    wave >= 8 -> Items.NETHERITE_AXE
                    wave >= 4 -> Items.DIAMOND_AXE
                    else -> Items.IRON_AXE
                }
            val axe = ItemStack(axeItem)
            if (wave >= 8) {
                enchantItem(level, axe, Enchantments.SHARPNESS, 4)
            }
            mob.setItemSlot(EquipmentSlot.MAINHAND, axe)
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.0f)
            mob.addEffect(MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 0, false, false))
            return
        }

        // Piglin Brutes
        if (mob is PiglinBrute) {
            val axe = ItemStack(if (wave >= 8) Items.NETHERITE_AXE else Items.DIAMOND_AXE)
            mob.setItemSlot(EquipmentSlot.MAINHAND, axe)
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.0f)
            mob.addEffect(MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 0, false, false))
            return
        }
    }

    fun triggerLocateBell(player: ServerPlayer) {
        val server = player.level().server
        if (!game.isFighting()) {
            player.sendSystemMessage(
                Component.translatable("exservermod.waves.locate_not_fighting").withStyle(ChatFormatting.RED),
            )
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastLocateTimestamp
        if (elapsed < LOCATE_COOLDOWN_MS) {
            val remainingSec = ((LOCATE_COOLDOWN_MS - elapsed) / 1000).coerceAtLeast(1)
            player.sendSystemMessage(
                Component.translatable("exservermod.waves.locate_cooldown", remainingSec).withStyle(ChatFormatting.RED),
            )
            return
        }
        lastLocateTimestamp = now

        var count = 0
        val level = arenaLevel ?: player.level()
        for (uuid in trackedMobs) {
            val mob = level.getEntity(uuid) as? Mob ?: continue
            if (mob.isAlive) {
                mob.addEffect(MobEffectInstance(MobEffects.GLOWING, 300, 0, false, false))
                count++
            }
        }

        playSoundToDefenders(server, SoundEvents.BELL_RESONATE, 2.0f, 1.0f)
        playSoundToDefenders(server, SoundEvents.RAID_HORN, 1.5f, 1.2f)

        for (uuid in game.defenders) {
            val defender = server.playerList.getPlayer(uuid) ?: continue
            defender.sendSystemMessage(
                Component.translatable("exservermod.waves.locate_success", count).withStyle(ChatFormatting.GOLD),
            )
        }
    }

    private fun spawnGhostCloud(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val cloud =
            AreaEffectCloud(level, entity.x, entity.y, entity.z).apply {
                radius = 3.8f
                duration = 180
                waitTime = 0
                setCustomParticle(ParticleTypes.SQUID_INK)
                addEffect(MobEffectInstance(MobEffects.DARKNESS, 120, 0))
                addEffect(MobEffectInstance(MobEffects.SLOWNESS, 100, 1))
                addEffect(MobEffectInstance(MobEffects.POISON, 80, 0))
            }
        level.addFreshEntity(cloud)
        level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.2f, 0.9f)
        level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.SOUL_ESCAPE, SoundSource.HOSTILE, 1.0f, 1.2f)
    }

    /**
     * Scripted custom mob mechanics and anti-turtle wall-piercing attacks.
     */
    private fun tickAbilities(server: MinecraftServer) {
        val level = arenaLevel ?: return
        val defenders = game.defenders.mapNotNull { server.playerList.getPlayer(it) }.filter { it.isAlive }
        if (defenders.isEmpty()) return

        blazeHellfireTimer++
        blazeHeatWaveTimer++
        witchVolleyTimer++
        witchDashTimer++
        antiTurtleTimer++

        // 0. Continuous horde pursuit: re-target nearest alive defender every second
        if (server.tickCount % 20 == 0) {
            for (uuid in trackedMobs) {
                val mob = level.getEntity(uuid) as? Mob ?: continue
                if (mob is Warden && mob.isAlive) {
                    WardenAi.setDigCooldown(mob)
                    SonicBoom.setCooldown(mob, 100_000)
                    val nearest =
                        defenders
                            .filter { it.level().dimension() == mob.level().dimension() }
                            .minByOrNull { it.distanceToSqr(mob.x, mob.y, mob.z) }
                    if (nearest != null) {
                        mob.target = nearest
                        mob.setAttackTarget(nearest)
                        mob.brain.eraseMemory(MemoryModuleType.ROAR_TARGET)
                        mob.brain.setMemory(MemoryModuleType.ATTACK_TARGET, nearest)
                        mob.navigation.moveTo(nearest, 1.25)
                    }
                    continue
                }
                if (mob.isAlive && (mob.target == null || !mob.target!!.isAlive)) {
                    val nearest =
                        defenders
                            .filter { it.level().dimension() == mob.level().dimension() }
                            .minByOrNull { it.distanceToSqr(mob.x, mob.y, mob.z) }
                    if (nearest != null) {
                        mob.target = nearest
                    }
                }
            }
        }

        // 1. Blaze Champion: Hellfire barrage every 8s (160 ticks)
        if (blazeHellfireTimer >= 160) {
            blazeHellfireTimer = 0
            for ((uuid, role) in trackedMobRoles) {
                if (role == WaveMobRole.BLAZE_CHAMPION) {
                    val blaze = level.getEntity(uuid) as? Blaze ?: continue
                    if (!blaze.isAlive) continue
                    val target = defenders.minByOrNull { it.distanceToSqr(blaze) } ?: continue
                    if (target.distanceToSqr(blaze) <= 30.0 * 30.0) {
                        launchHellfireBarrage(level, blaze, target)
                    }
                }
            }
        }

        // 2. Blaze Champion: Infernal Heat Wave (pierces mansion walls) every 12s (240 ticks)
        if (blazeHeatWaveTimer >= 240) {
            blazeHeatWaveTimer = 0
            for ((uuid, role) in trackedMobRoles) {
                if (role == WaveMobRole.BLAZE_CHAMPION) {
                    val blaze = level.getEntity(uuid) as? Blaze ?: continue
                    if (!blaze.isAlive) continue
                    triggerHeatWave(level, blaze, defenders)
                }
            }
        }

        // 3. Witch Champion: Broom Dash every 9s (180 ticks)
        if (witchDashTimer >= 180) {
            witchDashTimer = 0
            for ((uuid, role) in trackedMobRoles) {
                if (role == WaveMobRole.WITCH_CHAMPION) {
                    val witch = level.getEntity(uuid) as? Witch ?: continue
                    if (!witch.isAlive) continue
                    witch.addEffect(MobEffectInstance(MobEffects.SPEED, 80, 1))
                    witch.addEffect(MobEffectInstance(MobEffects.LEVITATION, 20, 0))
                    witch.addEffect(MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0))
                    level.sendParticles(ParticleTypes.WITCH, witch.x, witch.y + 1.0, witch.z, 20, 0.4, 0.4, 0.4, 0.05)
                    level.playSound(null, witch.x, witch.y, witch.z, SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.2f, 1.0f)
                    level.playSound(null, witch.x, witch.y, witch.z, SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 1.2f, 0.8f)
                }
            }
        }

        // 4. Witch Champion: Potion Volley every 7s (140 ticks)
        if (witchVolleyTimer >= 140) {
            witchVolleyTimer = 0
            for ((uuid, role) in trackedMobRoles) {
                if (role == WaveMobRole.WITCH_CHAMPION) {
                    val witch = level.getEntity(uuid) as? Witch ?: continue
                    if (!witch.isAlive) continue
                    val target = defenders.minByOrNull { it.distanceToSqr(witch) } ?: continue
                    if (target.distanceToSqr(witch) <= 26.0 * 26.0) {
                        launchWitchVolley(level, witch, target)
                    }
                }
            }
        }

        // 5. Anti-Turtle Mechanic: Wall-piercing Evoker Fangs strike every 14s (280 ticks)
        // Also tracks defenders stationary > 8 seconds in the mansion to flush them out.
        checkStationaryDefenders(defenders)
        if (antiTurtleTimer >= 280) {
            antiTurtleTimer = 0
            val hasChampion =
                trackedMobRoles.values.any {
                    it == WaveMobRole.BLAZE_CHAMPION || it == WaveMobRole.WITCH_CHAMPION || it == WaveMobRole.CHAMPION
                }
            if (hasChampion || game.currentWave >= 4) {
                for (defender in defenders) {
                    triggerSpectralFangs(level, defender)
                }
            }
        }
    }

    private fun launchHellfireBarrage(
        level: ServerLevel,
        blaze: Blaze,
        target: ServerPlayer,
    ) {
        val dx = target.x - blaze.x
        val dy = (target.eyeY - 0.2) - blaze.eyeY
        val dz = target.z - blaze.z

        level.playSound(null, blaze.x, blaze.y, blaze.z, SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.4f, 0.9f)
        for (i in -1..1) {
            val spreadX = dx + i * 0.35
            val spreadZ = dz - i * 0.35
            val dir = Vec3(spreadX, dy, spreadZ).normalize()
            val fireball = SmallFireball(level, blaze, dir)
            fireball.setPos(blaze.x, blaze.eyeY - 0.1, blaze.z)
            fireball.addTag(TAG_WAVE_PROJECTILE)
            level.addFreshEntity(fireball)
        }
    }

    private fun triggerHeatWave(
        level: ServerLevel,
        blaze: Blaze,
        defenders: List<ServerPlayer>,
    ) {
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, blaze.x, blaze.eyeY, blaze.z, 40, 1.2, 0.8, 1.2, 0.08)
        level.playSound(null, blaze.x, blaze.y, blaze.z, SoundEvents.BLAZE_BURN, SoundSource.HOSTILE, 1.5f, 0.8f)

        for (defender in defenders) {
            if (defender.distanceToSqr(blaze) <= 24.0 * 24.0) {
                defender.igniteForSeconds(3.0f)
                defender.hurtServer(level, level.damageSources().magic(), 4.0f)
                level.sendParticles(ParticleTypes.FLAME, defender.x, defender.y + 1.0, defender.z, 20, 0.4, 0.5, 0.4, 0.05)
                level.sendParticles(ParticleTypes.LAVA, defender.x, defender.y + 1.0, defender.z, 6, 0.3, 0.3, 0.3, 0.02)
                defender.connection.send(
                    ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIRECHARGE_USE),
                        SoundSource.PLAYERS,
                        defender.x,
                        defender.y,
                        defender.z,
                        1.0f,
                        0.9f,
                        level.random.nextLong(),
                    ),
                )
            }
        }
    }

    private fun launchWitchVolley(
        level: ServerLevel,
        witch: Witch,
        target: ServerPlayer,
    ) {
        val potions = listOf(Potions.SLOWNESS, Potions.HARMING, Potions.POISON, Potions.WEAKNESS)
        val potionHolder = potions[potionIndex % potions.size]
        potionIndex++

        val stack = PotionContents.createItemStack(Items.SPLASH_POTION, potionHolder)
        val potion = ThrownSplashPotion(level, witch, stack)
        val dx = target.x - witch.x
        val dy = (target.eyeY - 0.2) - witch.eyeY
        val dz = target.z - witch.z
        potion.shoot(dx, dy + 0.3, dz, 0.85f, 6.0f)
        level.addFreshEntity(potion)
        level.playSound(null, witch.x, witch.y, witch.z, SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 1.2f, 1.0f)
    }

    private fun checkStationaryDefenders(defenders: List<ServerPlayer>) {
        val level = arenaLevel ?: return
        for (defender in defenders) {
            val last = defenderStationaryTicks[defender.uuid]
            val currentPos = defender.position()
            if (last != null && last.first.distanceToSqr(currentPos) <= 9.0) {
                val ticks = last.second + 1
                defenderStationaryTicks[defender.uuid] = last.first to ticks
                // Camped in one corner for > 8 seconds (160 ticks)
                if (ticks >= 160 && (ticks - 160) % 80 == 0) {
                    triggerSpectralFangs(level, defender)
                    defender.sendSystemMessage(
                        Component.translatable("exservermod.waves.anti_camp_warning").withStyle(ChatFormatting.RED),
                        true,
                    )
                }
            } else {
                defenderStationaryTicks[defender.uuid] = currentPos to 0
            }
        }
    }

    private fun triggerSpectralFangs(
        level: ServerLevel,
        defender: ServerPlayer,
    ) {
        level.playSound(null, defender.x, defender.y, defender.z, SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.0f, 1.1f)
        level.sendParticles(ParticleTypes.SOUL, defender.x, defender.y + 0.1, defender.z, 15, 0.4, 0.1, 0.4, 0.02)

        val owner: LivingEntity = trackedMobs.firstNotNullOfOrNull { level.getEntity(it) as? LivingEntity } ?: defender

        // Cluster of 3 fangs centered at the player's position, penetrating any mansion floor
        for (i in 0..2) {
            val angle = i * (2.0 * Math.PI / 3.0)
            val fx = defender.x + 0.8 * cos(angle)
            val fz = defender.z + 0.8 * sin(angle)
            val fangs = EvokerFangs(level, fx, defender.y, fz, 0f, 20, owner)
            level.addFreshEntity(fangs)
        }
    }

    private fun updateBossBar(server: MinecraftServer) {
        val onlineDefenders = game.defenders.mapNotNull { server.playerList.getPlayer(it) }.toSet()
        for (player in onlineDefenders) {
            if (!bossBar.players.contains(player)) {
                bossBar.addPlayer(player)
            }
        }
        for (player in bossBar.players.toList()) {
            if (!onlineDefenders.contains(player)) {
                bossBar.removePlayer(player)
            }
        }

        when (game.phase) {
            WavePhase.COUNTDOWN -> {
                val secondsLeft = (game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND).coerceAtLeast(1)
                bossBar.name =
                    Component.translatable(
                        "exservermod.waves.bossbar.countdown",
                        WaveDefenseGame.TOTAL_WAVES,
                        secondsLeft,
                    )
                bossBar.progress = (game.remainingTicks.toFloat() / WaveDefenseGame.COUNTDOWN_TICKS.toFloat()).coerceIn(0.0f, 1.0f)
                bossBar.color = BossEvent.BossBarColor.YELLOW
                bossBar.overlay = BossEvent.BossBarOverlay.PROGRESS
            }
            WavePhase.FIGHT -> {
                val remaining = trackedMobs.size
                val total = currentWaveTotalMobs.coerceAtLeast(1)
                bossBar.name =
                    if (game.isTrickWave && game.currentWave == 2) {
                        Component.translatable(
                            "exservermod.waves.bossbar.fight_trick",
                            game.currentWave,
                            WaveDefenseGame.TOTAL_WAVES,
                            remaining,
                        )
                    } else {
                        Component.translatable(
                            "exservermod.waves.bossbar.fight",
                            game.currentWave,
                            WaveDefenseGame.TOTAL_WAVES,
                            remaining,
                        )
                    }
                bossBar.progress = (remaining.toFloat() / total.toFloat()).coerceIn(0.0f, 1.0f)
                bossBar.color =
                    if (WaveDefenseGame.isBossWave(game.currentWave)) {
                        BossEvent.BossBarColor.PURPLE
                    } else {
                        BossEvent.BossBarColor.RED
                    }
                bossBar.overlay = BossEvent.BossBarOverlay.NOTCHED_10
            }
            WavePhase.TRICK_OR_TREAT -> {
                val secondsLeft = (game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND).coerceAtLeast(1)
                bossBar.name = Component.translatable("exservermod.waves.bossbar.trick_or_treat", secondsLeft)
                bossBar.progress = (game.remainingTicks.toFloat() / WaveDefenseGame.TRICK_OR_TREAT_TICKS.toFloat()).coerceIn(0.0f, 1.0f)
                bossBar.color = BossEvent.BossBarColor.YELLOW
                bossBar.overlay = BossEvent.BossBarOverlay.PROGRESS
            }
            WavePhase.INTERMISSION -> {
                val secondsLeft = (game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND).coerceAtLeast(1)
                bossBar.name =
                    Component.translatable(
                        "exservermod.waves.bossbar.intermission",
                        game.currentWave,
                        WaveDefenseGame.TOTAL_WAVES,
                        secondsLeft,
                    )
                bossBar.progress = (game.remainingTicks.toFloat() / WaveDefenseGame.INTERMISSION_TICKS.toFloat()).coerceIn(0.0f, 1.0f)
                bossBar.color = BossEvent.BossBarColor.GREEN
                bossBar.overlay = BossEvent.BossBarOverlay.PROGRESS
            }
            WavePhase.RECOVERY -> {
                val secondsLeft = (game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND).coerceAtLeast(1)
                val restartingWave = game.currentWave + 1
                bossBar.name = Component.translatable("exservermod.waves.bossbar.recovery", secondsLeft, restartingWave)
                bossBar.progress = (game.remainingTicks.toFloat() / WaveDefenseGame.RECOVERY_TICKS.toFloat()).coerceIn(0.0f, 1.0f)
                bossBar.color = BossEvent.BossBarColor.YELLOW
                bossBar.overlay = BossEvent.BossBarOverlay.PROGRESS
            }
            else -> {}
        }
    }

    private fun findSpawnPos(
        level: ServerLevel,
        center: Vec3,
    ): BlockPos? {
        repeat(40) {
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val dist = Random.nextDouble(SPAWN_RING_MIN, SPAWN_RING_MAX)
            val x = (center.x + dist * cos(angle)).toInt()
            val z = (center.z + dist * sin(angle)).toInt()

            if (!NightmareIslandManager.isLocationOnIsland(x.toDouble(), z.toDouble())) {
                return@repeat
            }

            val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            val pos = BlockPos(x, surfaceY, z)
            // Absolute requirement: mob must see the open sky (never in caves, holes or tunnels!)
            if (level.canSeeSky(pos) && isSpawnable(level, pos)) {
                return pos
            }
        }
        return null
    }

    private fun isSpawnable(
        level: ServerLevel,
        pos: BlockPos,
    ): Boolean {
        // Island ground height guard: natural island ground is Y 60..76. High house roofs are Y 78+
        if (pos.y !in 60..76) return false

        val floorPos = pos.below()
        val floor = level.getBlockState(floorPos)

        if (!floor.isFaceSturdy(level, floorPos, Direction.UP)) return false
        if (!level.getFluidState(floorPos).isEmpty || !level.getFluidState(pos).isEmpty) return false

        val floorBlock = floor.block
        if (floorBlock is StairBlock || floorBlock is SlabBlock || floorBlock is FenceBlock) return false

        // Strict natural island ground: podzol, granite, dirt, coarse dirt, grass block, stone
        val isNaturalGround =
            floorBlock == Blocks.PODZOL ||
                floorBlock == Blocks.GRANITE ||
                floorBlock == Blocks.DIRT ||
                floorBlock == Blocks.COARSE_DIRT ||
                floorBlock == Blocks.GRASS_BLOCK ||
                floorBlock == Blocks.ROOTED_DIRT ||
                floorBlock == Blocks.STONE

        if (!isNaturalGround) return false

        return level.getBlockState(pos).isAir && level.getBlockState(pos.above()).isAir
    }

    private fun onWaveCleared(server: MinecraftServer) {
        when (val cleared = game.onWaveCleared()) {
            is WaveTickResult.Victory -> Unit
            is WaveTickResult.TrickWaveCleared -> {
                server.playerList.broadcastSystemMessage(
                    Component.translatable("exservermod.waves.tot.trick_cleared").withStyle(ChatFormatting.GOLD),
                    false,
                )
                playSoundToDefenders(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f)
            }
            is WaveTickResult.PhaseChanged -> {
                if (cleared.newPhase == WavePhase.TRICK_OR_TREAT) {
                    broadcastTrickOrTreatPrompt(server)
                } else if (cleared.newPhase == WavePhase.INTERMISSION) {
                    server.playerList.broadcastSystemMessage(
                        Component
                            .translatable(
                                "exservermod.waves.wave_cleared",
                                game.currentWave,
                                WaveDefenseGame.INTERMISSION_SECONDS,
                            ).withStyle(ChatFormatting.GREEN),
                        false,
                    )
                    playSoundToDefenders(server, SoundEvents.PLAYER_LEVELUP, 0.9f, 1.2f)
                }
            }
            else -> {}
        }
    }

    private fun onVictory(server: MinecraftServer) {
        val level = arenaLevel
        val center = arenaCenter
        val wasTrick = game.halloweenChoice == HalloweenChoice.TRICK
        val spearChance = if (wasTrick) 1.0f else 0.67f
        val breezeStacks = if (wasTrick) 4 else 2
        val goldenAppleCount = if (wasTrick) 64 else 32
        val expStacks = if (wasTrick) 2 else 1

        clearCheckpoint()
        setArenaChunksForced(level, center, false)
        clearMobs()
        bossBar.removeAllPlayers()
        bossBar.isVisible = false

        val defenders = game.defenders.mapNotNull { server.playerList.getPlayer(it) }
        for (defender in defenders) {
            val anim = ClientboundSetTitlesAnimationPacket(10, 80, 20)
            val title =
                ClientboundSetTitleTextPacket(
                    Component.translatable("exservermod.waves.victory").withStyle(ChatFormatting.GOLD),
                )
            val subtitle =
                ClientboundSetSubtitleTextPacket(
                    Component
                        .translatable("exservermod.waves.victory_subtitle", WaveDefenseGame.TOTAL_WAVES, game.kills)
                        .withStyle(ChatFormatting.YELLOW),
                )
            defender.connection.send(anim)
            defender.connection.send(title)
            defender.connection.send(subtitle)

            // Award epic veteran rewards to each surviving defender
            defender.giveExperienceLevels(50)

            val rewardLevel = level ?: server.overworld()
            val rewards =
                mutableListOf(
                    createHorsemanLantern(rewardLevel),
                    ItemStack(Items.HEAVY_CORE, 1),
                    ItemStack(Items.GOLDEN_APPLE, goldenAppleCount),
                    ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2),
                    ItemStack(Items.NETHERITE_INGOT, 4),
                    ItemStack(Items.NETHER_STAR, 1).apply {
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component
                                .translatable("exservermod.waves.trophy_name")
                                .withStyle { it.withItalic(false).withColor(ChatFormatting.GOLD) },
                        )
                    },
                    ItemStack(Items.STICK, 1).apply {
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component
                                .translatable("exservermod.waves.reward.knockback_stick")
                                .withStyle { it.withItalic(false).withColor(ChatFormatting.AQUA) },
                        )
                        enchantItem(rewardLevel, this, Enchantments.KNOCKBACK, 10)
                    },
                    PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_STRENGTH),
                    PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_STRENGTH),
                    PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_REGENERATION),
                    PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_REGENERATION),
                    PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_SWIFTNESS),
                    PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_SWIFTNESS),
                )

            repeat(breezeStacks) {
                rewards.add(ItemStack(Items.BREEZE_ROD, 64))
            }
            repeat(expStacks) {
                rewards.add(ItemStack(Items.EXPERIENCE_BOTTLE, 64))
            }

            if (Random.nextFloat() < spearChance) {
                val spear =
                    ItemStack(Items.NETHERITE_SPEAR).apply {
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component
                                .translatable("exservermod.waves.reward.spartan_spear.name")
                                .withStyle { it.withItalic(false).withColor(ChatFormatting.GOLD) },
                        )
                        set(
                            DataComponents.LORE,
                            ItemLore(
                                listOf(
                                    Component
                                        .translatable("exservermod.waves.reward.spartan_spear.lore1")
                                        .withStyle { it.withItalic(true).withColor(ChatFormatting.GRAY) },
                                    Component
                                        .translatable("exservermod.waves.reward.spartan_spear.lore2")
                                        .withStyle { it.withItalic(false).withColor(ChatFormatting.RED) },
                                    Component
                                        .translatable("exservermod.waves.reward.spartan_spear.lore3")
                                        .withStyle { it.withItalic(false).withColor(ChatFormatting.DARK_RED) },
                                ),
                            ),
                        )
                        enchantItem(rewardLevel, this, Enchantments.SHARPNESS, 10)
                        enchantItem(rewardLevel, this, Enchantments.LUNGE, 5)
                        set(DataComponents.ENCHANTABLE, Enchantable(0))
                        set(DataComponents.REPAIRABLE, Repairable(HolderSet.empty()))
                        set(DataComponents.REPAIR_COST, 1_000_000)
                    }
                rewards.add(spear)
                server.playerList.broadcastSystemMessage(
                    Component
                        .translatable("exservermod.waves.reward.spartan_spear_drop", defender.name)
                        .withStyle(ChatFormatting.GOLD),
                    false,
                )
            }

            for (reward in rewards) {
                if (!defender.inventory.add(reward)) {
                    defender.drop(reward, false)
                }
            }
        }

        if (wasTrick) {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable("exservermod.waves.victory_chat_trick", game.kills)
                    .withStyle(ChatFormatting.GOLD),
                false,
            )
        } else {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable("exservermod.waves.victory_chat", game.kills)
                    .withStyle(ChatFormatting.GOLD),
                false,
            )
        }

        if (level != null && center != null) {
            val fireworkStack = ItemStack(Items.FIREWORK_ROCKET)
            val explosions =
                listOf(
                    FireworkExplosion(
                        FireworkExplosion.Shape.LARGE_BALL,
                        IntArrayList(intArrayOf(0xFFD700, 0x00FF88, 0x00E5FF, 0xFF4500)),
                        IntArrayList(intArrayOf(0xFFFFFF)),
                        true,
                        true,
                    ),
                )
            fireworkStack.set(DataComponents.FIREWORKS, Fireworks(2, explosions))
            val firework = FireworkRocketEntity(level, center.x, center.y + 1.5, center.z, fireworkStack)
            level.addFreshEntity(firework)

            level.playSound(null, center.x, center.y, center.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.2f, 1.0f)
        }

        playSoundToDefenders(server, SoundEvents.PLAYER_LEVELUP, 1.2f, 1.0f)
    }

    private fun announceWave(
        server: MinecraftServer,
        wave: Int,
        totalWaves: Int,
        isBoss: Boolean,
    ) {
        val key = if (isBoss) "exservermod.waves.wave_announce_boss" else "exservermod.waves.wave_announce"
        val color = if (isBoss) ChatFormatting.RED else ChatFormatting.YELLOW
        server.playerList.broadcastSystemMessage(
            Component.translatable(key, wave, totalWaves).withStyle(color),
            false,
        )
        if (isBoss) {
            playSoundToDefenders(server, SoundEvents.WARDEN_SONIC_BOOM, 1.5f, 0.8f)
            playSoundToDefenders(server, SoundEvents.WITHER_SPAWN, 1.2f, 1.0f)
        } else {
            playSoundToDefenders(server, SoundEvents.RAID_HORN, 1.5f, 1.0f)
        }
    }

    private fun renderHud(server: MinecraftServer) {
        val defenders = game.defenders.mapNotNull { uuid -> server.playerList.getPlayer(uuid) }
        if (defenders.isEmpty()) return

        val message =
            when (game.phase) {
                WavePhase.COUNTDOWN -> {
                    val secondsLeft = game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND
                    if (secondsLeft in 1..5) {
                        playSoundToDefenders(server, SoundEvents.NOTE_BLOCK_PLING, 0.8f, 1.1f)
                    }
                    Component
                        .translatable("exservermod.waves.actionbar.countdown", secondsLeft)
                        .withStyle(ChatFormatting.YELLOW)
                }
                WavePhase.TRICK_OR_TREAT -> {
                    val secondsLeft = (game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND).coerceAtLeast(1)
                    Component
                        .translatable("exservermod.waves.actionbar.trick_or_treat", secondsLeft)
                        .withStyle(ChatFormatting.GOLD)
                }
                WavePhase.FIGHT -> return // Single raid bossbar at top is used; no bottom HUD duplication
                WavePhase.INTERMISSION -> {
                    val secondsLeft = game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND
                    Component
                        .translatable("exservermod.waves.actionbar.intermission", game.currentWave + 1, secondsLeft)
                        .withStyle(ChatFormatting.GREEN)
                }
                WavePhase.RECOVERY -> {
                    val secondsLeft = (game.remainingTicks / WaveDefenseGame.TICKS_PER_SECOND).coerceAtLeast(1)
                    val restartingWave = game.currentWave + 1
                    Component
                        .translatable("exservermod.waves.actionbar.recovery", secondsLeft, restartingWave)
                        .withStyle(ChatFormatting.GOLD)
                }
                else -> return
            }

        for (defender in defenders) {
            defender.sendSystemMessage(message, true)
        }
    }

    private fun playSoundToPlayers(
        server: MinecraftServer,
        playerUuids: Collection<UUID>,
        sound: Holder<SoundEvent>,
        volume: Float,
        pitch: Float,
    ) {
        for (uuid in playerUuids) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            player.connection.send(
                ClientboundSoundPacket(
                    sound,
                    SoundSource.PLAYERS,
                    player.x,
                    player.y,
                    player.z,
                    volume,
                    pitch,
                    player.level().random.nextLong(),
                ),
            )
        }
    }

    private fun playSoundToPlayers(
        server: MinecraftServer,
        playerUuids: Collection<UUID>,
        sound: SoundEvent,
        volume: Float,
        pitch: Float,
    ) {
        playSoundToPlayers(server, playerUuids, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)
    }

    private fun playSoundToDefenders(
        server: MinecraftServer,
        sound: Holder<SoundEvent>,
        volume: Float,
        pitch: Float,
    ) {
        playSoundToPlayers(server, game.defenders, sound, volume, pitch)
    }

    private fun playSoundToDefenders(
        server: MinecraftServer,
        sound: SoundEvent,
        volume: Float,
        pitch: Float,
    ) {
        playSoundToDefenders(server, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)
    }

    private fun setArenaChunksForced(
        level: ServerLevel?,
        center: Vec3?,
        forced: Boolean,
    ) {
        if (level == null || center == null) return
        val chunkX = center.x.toInt() shr 4
        val chunkZ = center.z.toInt() shr 4
        for (dx in -2..2) {
            for (dz in -2..2) {
                try {
                    level.setChunkForced(chunkX + dx, chunkZ + dz, forced)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun trackedMobCount(): Int = trackedMobs.size
}
