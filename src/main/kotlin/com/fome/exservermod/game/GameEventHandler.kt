package com.fome.exservermod.game

import com.fome.exservermod.afk.AfkManager
import com.fome.exservermod.compat.bluemap.BlueMapCompat
import com.fome.exservermod.disguise.DisguiseManager
import com.fome.exservermod.disguise.MobPool
import com.fome.exservermod.landmarks.LandmarkManager
import com.fome.exservermod.network.DisguiseSyncPayload
import com.fome.exservermod.network.RouletteCanceledPayload
import com.fome.exservermod.network.RouletteFinishedPayload
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot
import kotlin.random.Random

class GameEventHandler(
    private val game: HideAndSeekGame,
    private val landmarks: LandmarkManager,
    private val disguiseManager: DisguiseManager = DisguiseManager(),
) {
    companion object {
        const val HINT_INTERVAL_TICKS = 45 * HideAndSeekGame.TICKS_PER_SECOND // 45 seconds = 900 ticks
        const val ACOUSTIC_TAUNT_INTERVAL_TICKS = 45 * HideAndSeekGame.TICKS_PER_SECOND // 45 seconds = 900 ticks
        const val INTELLECTUAL_HINT_INTERVAL_TICKS = 90 * HideAndSeekGame.TICKS_PER_SECOND // 90 seconds = 1800 ticks
        const val TRACKER_COMPASS_DURATION_TICKS = 20 * HideAndSeekGame.TICKS_PER_SECOND // 20 seconds = 400 ticks
        const val LATE_GAME_HINT_TIME_TICKS = 10 * 60 * HideAndSeekGame.TICKS_PER_SECOND // 10 minutes = 12000 ticks
        const val ECHO_PULSE_INTERVAL_TICKS = 30 * HideAndSeekGame.TICKS_PER_SECOND // 30 seconds = 600 ticks
        const val MOVEMENT_ALERT_DISTANCE = 20.0
        const val SECTOR_OFFSET_MIN = 15.0
        const val SECTOR_OFFSET_MAX = 30.0
        const val ECHO_STOP_DISTANCE = 3.0
        const val ECHO_RAY_STEP = 1.5
        const val CLOSE_DISTANCE_BLOCKS = 5.0
    }

    private var hiderInitialX: Double? = null
    private var hiderInitialZ: Double? = null
    private var lastAlertedX: Double? = null
    private var lastAlertedZ: Double? = null
    private var activeLaserTicks: Int = 0
    private var laserStart: Vec3? = null
    private var laserEnd: Vec3? = null
    private var laserLevel: ServerLevel? = null
    private var laserHiderPos: Vec3? = null
    private var laserIsUnderground: Boolean = false
    private var laserSurfaceY: Int = 0
    private var lastPhase: GamePhase = GamePhase.IDLE
    private var trackerCompassRemovalTick: Int? = null
    private val hintEngine = HintEngine()

    fun register() {
        registerDamageProtection()
        registerBlockBreakProtection()
        registerAttackHandler()
        registerServerTick()
        registerJoin()
        registerDisconnect()
        registerServerStarted()
        registerServerStopping()
        registerRouletteFinished()
    }

    private fun registerDamageProtection() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            if (entity is ServerPlayer &&
                (game.isGameActive() || game.phase == GamePhase.INTERMISSION) &&
                game.isParticipant(entity.uuid)
            ) {
                false
            } else {
                true
            }
        }
    }

    private fun registerBlockBreakProtection() {
        PlayerBlockBreakEvents.BEFORE.register { _, player, _, _, _ ->
            if (player is ServerPlayer && game.isGameActive() && game.isParticipant(player.uuid)) {
                false
            } else {
                true
            }
        }
    }

    private fun registerAttackHandler() {
        AttackEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if (!level.isClientSide && player is ServerPlayer && entity is ServerPlayer) {
                if (game.phase == GamePhase.SEEKING && player.uuid == game.seekerUuid && entity.uuid == game.hiderUuid) {
                    val caught = game.onSeekerHitHider(player.uuid, entity.uuid)
                    if (caught) {
                        hiderInitialX = null
                        hiderInitialZ = null
                        lastAlertedX = null
                        lastAlertedZ = null
                        activeLaserTicks = 0
                        level.server?.let { clearTrackerCompasses(it) }

                        val elapsedSeconds = game.elapsedSeekingTicks / HideAndSeekGame.TICKS_PER_SECOND
                        val minutes = elapsedSeconds / 60
                        val seconds = elapsedSeconds % 60
                        val timeFormatted = String.format("%02d:%02d", minutes, seconds)

                        // Prominent screen TITLE to both participants
                        val animPacket = ClientboundSetTitlesAnimationPacket(10, 100, 20)
                        val titlePacket =
                            ClientboundSetTitleTextPacket(
                                Component.translatable("exservermod.game.title.caught").withStyle(ChatFormatting.RED),
                            )
                        val subtitlePacket =
                            ClientboundSetSubtitleTextPacket(
                                Component
                                    .translatable(
                                        "exservermod.game.subtitle.caught",
                                        timeFormatted,
                                    ).withStyle(ChatFormatting.GOLD),
                            )

                        player.connection.send(animPacket)
                        player.connection.send(titlePacket)
                        player.connection.send(subtitlePacket)

                        entity.connection.send(animPacket)
                        entity.connection.send(titlePacket)
                        entity.connection.send(subtitlePacket)

                        val server = level.server
                        if (server != null) {
                            playSoundToParticipants(server, SoundEvents.PLAYER_LEVELUP, 1.2f, 1.0f)
                            playSoundToParticipants(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
                        }

                        // Spawn celebration fireworks over seeker
                        val fireworkStack = ItemStack(Items.FIREWORK_ROCKET)
                        val explosions =
                            listOf(
                                FireworkExplosion(
                                    FireworkExplosion.Shape.LARGE_BALL,
                                    IntArrayList(intArrayOf(0xFFD700, 0xFF4500, 0x00FF00)),
                                    IntArrayList(intArrayOf(0xFFFFFF)),
                                    true,
                                    true,
                                ),
                            )
                        fireworkStack.set(DataComponents.FIREWORKS, Fireworks(1, explosions))
                        val firework = FireworkRocketEntity(player.level(), player.x, player.y + 1.5, player.z, fireworkStack)
                        player.level().addFreshEntity(firework)

                        val catchMessage =
                            Component
                                .translatable(
                                    "exservermod.game.catch_announcement",
                                    player.name,
                                    entity.name,
                                    elapsedSeconds,
                                ).withStyle(ChatFormatting.GOLD)
                        level.server?.playerList?.broadcastSystemMessage(catchMessage, false)

                        val continueButton =
                            Component.translatable("exservermod.game.button.continue_round_action").withStyle { style ->
                                style
                                    .withColor(ChatFormatting.GREEN)
                                    .withClickEvent(ClickEvent.RunCommand("/games continue"))
                                    .withHoverEvent(
                                        HoverEvent.ShowText(Component.translatable("exservermod.game.button.continue_round.hover_catch")),
                                    )
                            }
                        val leaveButton =
                            Component.translatable("exservermod.game.button.leave_action").withStyle { style ->
                                style
                                    .withColor(ChatFormatting.RED)
                                    .withClickEvent(ClickEvent.RunCommand("/games leave"))
                                    .withHoverEvent(
                                        HoverEvent.ShowText(Component.translatable("exservermod.game.button.leave.hover")),
                                    )
                            }
                        val buttonsRow =
                            Component
                                .empty()
                                .append(continueButton)
                                .append("   ")
                                .append(leaveButton)
                        level.server?.playerList?.broadcastSystemMessage(buttonsRow, false)

                        if (game.mode == GameMode.MOB) {
                            val disguise = disguiseManager.getDisguise(entity.uuid)
                            if (disguise != null) {
                                val revealMsg =
                                    Component
                                        .translatable(
                                            "exservermod.disguise.revealed",
                                            entity.name,
                                            disguise.description,
                                        ).withStyle(ChatFormatting.GOLD)
                                level.server?.playerList?.broadcastSystemMessage(revealMsg, false)
                            }
                            disguiseManager.clearDisguise(entity)
                        }

                        BlueMapCompat.setPlayersVisibility(listOf(player.uuid, entity.uuid), true)

                        return@register InteractionResult.SUCCESS
                    }
                }
            }
            InteractionResult.PASS
        }
    }

    private fun registerServerTick() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (activeLaserTicks > 0) {
                activeLaserTicks--
                if (activeLaserTicks % 2 == 0) {
                    tickActiveLaser()
                }
            }
            RouletteManager.checkTimeouts(server, game, disguiseManager)
            val transition = game.tick()
            if (transition is TickResult.PhaseChanged) {
                when (transition.newPhase) {
                    GamePhase.SEEKING -> {
                        playSoundToParticipants(server, SoundEvents.RAID_HORN, 2.0f, 1.0f)
                        val seeker = game.seekerUuid?.let { server.playerList.getPlayer(it) }
                        val seekerComponent: Component =
                            seeker?.name
                                ?: Component.translatable("exservermod.game.fallback.seeker")
                        server.playerList.broadcastSystemMessage(
                            Component
                                .translatable("exservermod.game.seeking_announcement", seekerComponent)
                                .withStyle(ChatFormatting.RED),
                            false,
                        )
                    }
                    GamePhase.IDLE -> {
                        server.playerList.broadcastSystemMessage(
                            Component
                                .translatable("exservermod.game.reset_idle")
                                .withStyle(ChatFormatting.GRAY),
                            false,
                        )
                    }
                    else -> {}
                }
            }

            val currentPhase = game.phase
            if (currentPhase != lastPhase) {
                val oldPhase = lastPhase
                lastPhase = currentPhase
                onPhaseChanged(server, oldPhase, currentPhase)
            }

            val removalTick = trackerCompassRemovalTick
            if (removalTick != null && server.tickCount >= removalTick) {
                trackerCompassRemovalTick = null
                TrackerCompassManager.purgeAll(server)
                val seeker = game.seekerUuid?.let { server.playerList.getPlayer(it) }
                if (seeker != null) {
                    seeker.sendSystemMessage(
                        Component
                            .translatable("exservermod.game.tracker_compass_expired")
                            .withStyle(ChatFormatting.GRAY),
                    )
                    playSoundToPlayer(seeker, SoundEvents.ITEM_BREAK, 1.0f, 0.9f)
                }
            }

            if (game.phase == GamePhase.SEEKING) {
                val hider = game.hiderUuid?.let { server.playerList.getPlayer(it) }
                val seeker = game.seekerUuid?.let { server.playerList.getPlayer(it) }

                // Elytra invariant: strictly forbidden for hider during seeking phase
                if (hider != null && hider.isFallFlying) {
                    hider.stopFallFlying()
                    hider.sendSystemMessage(
                        Component
                            .translatable("exservermod.game.elytra_forbidden")
                            .withStyle(ChatFormatting.RED),
                        true,
                    )
                }

                // Periodic movement alert: triggers every 20 meters the hider travels
                if (hider != null) {
                    val lx = lastAlertedX
                    val lz = lastAlertedZ
                    if (lx == null || lz == null) {
                        lastAlertedX = hider.x
                        lastAlertedZ = hider.z
                    } else {
                        val distance = hypot(hider.x - lx, hider.z - lz)
                        if (distance >= MOVEMENT_ALERT_DISTANCE) {
                            lastAlertedX = hider.x
                            lastAlertedZ = hider.z

                            val dim =
                                hider
                                    .level()
                                    .dimension()
                                    .identifier()
                                    .toString()
                            val closest = landmarks.findClosest(hider.x, hider.z, dim)
                            val alertMsg =
                                if (closest != null) {
                                    val landmarkDist = hypot(hider.x - closest.x, hider.z - closest.z).toInt()
                                    Component.translatable(
                                        "exservermod.hideandseek.alert.moved_landmark",
                                        closest.name,
                                        landmarkDist,
                                    )
                                } else {
                                    Component.translatable(
                                        "exservermod.hideandseek.alert.moved_coords",
                                        hider.blockX,
                                        hider.blockZ,
                                    )
                                }
                            server.playerList.broadcastSystemMessage(alertMsg.withStyle(ChatFormatting.GOLD), false)

                            val seeker = game.seekerUuid?.let { server.playerList.getPlayer(it) }
                            if (seeker != null) {
                                if (seeker.level().dimension() != hider.level().dimension()) {
                                    server.playerList.broadcastSystemMessage(
                                        Component
                                            .translatable("exservermod.hideandseek.hint.distance_diff_dimension")
                                            .withStyle(ChatFormatting.YELLOW),
                                        false,
                                    )
                                } else {
                                    val distBlocks = hypot(hider.x - seeker.x, hider.z - seeker.z).toInt()
                                    server.playerList.broadcastSystemMessage(
                                        Component
                                            .translatable("exservermod.hideandseek.hint.seeker_distance", distBlocks)
                                            .withStyle(ChatFormatting.YELLOW),
                                        false,
                                    )
                                }
                            }
                            playSoundToParticipants(server, SoundEvents.BELL_BLOCK, 1.2f, 1.0f)
                        }
                    }
                }

                // Unified periodic hint rotation (every 45 seconds = 900 ticks):
                // distance every other hint, varied hints from a no-repeat bag
                if (game.elapsedSeekingTicks > 0 && game.elapsedSeekingTicks % HINT_INTERVAL_TICKS == 0) {
                    if (hider != null && seeker != null) {
                        broadcastRotatingHint(server, seeker, hider)
                    }
                }

                // Acoustic sound taunt (every 45 seconds = 900 ticks, radius 35-40 blocks)
                if (game.mode == GameMode.MOB &&
                    game.elapsedSeekingTicks > 0 &&
                    game.elapsedSeekingTicks % ACOUSTIC_TAUNT_INTERVAL_TICKS == 0
                ) {
                    if (hider != null) {
                        val mob = disguiseManager.getDisguise(hider.uuid)
                        if (mob != null) {
                            val sound = MobPool.getAmbientSound(mob)
                            hider.level().playSound(null, hider.x, hider.y, hider.z, sound, SoundSource.NEUTRAL, 2.5f, 1.0f)
                        }
                    }
                }

                // Intellectual categorical hint (every 90 seconds = 1800 ticks)
                if (game.mode == GameMode.MOB &&
                    game.elapsedSeekingTicks > 0 &&
                    game.elapsedSeekingTicks % INTELLECTUAL_HINT_INTERVAL_TICKS == 0
                ) {
                    if (hider != null) {
                        val mob = disguiseManager.getDisguise(hider.uuid)
                        if (mob != null) {
                            val (hint, category) = MobPool.getNextHint(mob, game.hintCategoryHistory)
                            game.recordHintCategory(category)
                            server.playerList.broadcastSystemMessage(
                                hint.withStyle(ChatFormatting.LIGHT_PURPLE),
                                false,
                            )
                        }
                    }
                }

                // Late-game echo pulse (after 5 minutes, every 30 seconds):
                // a glow ray the seeker can literally follow; it dissolves 3 blocks
                // short of the hider so it never pinpoints the exact spot
                if (game.elapsedSeekingTicks >= LATE_GAME_HINT_TIME_TICKS &&
                    (game.elapsedSeekingTicks - LATE_GAME_HINT_TIME_TICKS) % ECHO_PULSE_INTERVAL_TICKS == 0
                ) {
                    if (seeker != null && hider != null) {
                        launchEchoPulse(server, seeker, hider)
                    }
                }
            }

            // Anti-stuck reminder for disguised hider
            if (game.isGameActive() && game.mode == GameMode.MOB && server.tickCount % 40 == 0) {
                val hider = game.hiderUuid?.let { server.playerList.getPlayer(it) }
                if (hider != null && disguiseManager.isDisguised(hider.uuid) && disguiseManager.isPlayerStuck(hider)) {
                    val unstuckBtn =
                        Component.translatable("exservermod.disguise.button.unstuck").withStyle { style ->
                            style
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(ClickEvent.RunCommand("/games unstuck"))
                                .withHoverEvent(
                                    HoverEvent.ShowText(Component.translatable("exservermod.disguise.button.unstuck.hover")),
                                )
                        }
                    hider.sendSystemMessage(
                        Component
                            .translatable("exservermod.disguise.stuck_reminder")
                            .withStyle(ChatFormatting.RED)
                            .append(" ")
                            .append(unstuckBtn),
                    )
                }
            }

            // Action bar countdown & seeking timer every second
            if (server.tickCount % HideAndSeekGame.TICKS_PER_SECOND == 0) {
                if (game.phase == GamePhase.HIDING) {
                    val secondsLeft = game.remainingTicks / HideAndSeekGame.TICKS_PER_SECOND
                    val msg =
                        Component
                            .translatable("exservermod.game.actionbar.hiding", secondsLeft)
                            .withStyle(ChatFormatting.YELLOW)
                    game.hiderUuid?.let { server.playerList.getPlayer(it)?.sendSystemMessage(msg, true) }
                    game.seekerUuid?.let { server.playerList.getPlayer(it)?.sendSystemMessage(msg, true) }

                    // Ticking note block sound for 10..1 seconds with rising pitch
                    if (secondsLeft in 1..10) {
                        val pitch = 0.6f + (10 - secondsLeft) * 0.14f
                        playSoundToParticipants(server, SoundEvents.NOTE_BLOCK_PLING, 1.0f, pitch)
                    }
                } else if (game.phase == GamePhase.SEEKING) {
                    val elapsedSeconds = game.elapsedSeekingTicks / HideAndSeekGame.TICKS_PER_SECOND
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    val timeFormatted = String.format("%02d:%02d", minutes, seconds)
                    val seekerComponent: Component =
                        game.seekerUuid?.let {
                            server.playerList
                                .getPlayer(it)
                                ?.name
                        } ?: Component.translatable("exservermod.game.fallback.seeker")
                    val msg =
                        Component
                            .translatable("exservermod.game.actionbar.seeking", timeFormatted, seekerComponent)
                            .withStyle(ChatFormatting.GOLD)
                    game.hiderUuid?.let { server.playerList.getPlayer(it)?.sendSystemMessage(msg, true) }
                    game.seekerUuid?.let { server.playerList.getPlayer(it)?.sendSystemMessage(msg, true) }
                } else if (game.phase == GamePhase.INTERMISSION) {
                    val secondsLeft = game.remainingTicks / HideAndSeekGame.TICKS_PER_SECOND
                    val nextHiderComponent: Component =
                        game.seekerUuid?.let {
                            server.playerList
                                .getPlayer(it)
                                ?.name
                        } ?: Component.translatable("exservermod.game.fallback.hider")
                    val msg =
                        Component
                            .translatable("exservermod.game.actionbar.intermission", nextHiderComponent, secondsLeft)
                            .withStyle(ChatFormatting.AQUA)
                    game.hiderUuid?.let { server.playerList.getPlayer(it)?.sendSystemMessage(msg, true) }
                    game.seekerUuid?.let { server.playerList.getPlayer(it)?.sendSystemMessage(msg, true) }
                }
            }
        }
    }

    private fun broadcastRotatingHint(
        server: MinecraftServer,
        seeker: ServerPlayer,
        hider: ServerPlayer,
    ) {
        val hint = hintEngine.nextHint()
        when (hint.type) {
            HintEngine.SeekerHintType.DISTANCE -> broadcastDistanceHint(server, seeker, hider)
            HintEngine.SeekerHintType.VARIED ->
                when (HintEngine.VariedHint.entries[hint.variedOrdinal]) {
                    HintEngine.VariedHint.LANDMARK -> broadcastLandmarkHint(server, hider)
                    HintEngine.VariedHint.BLOCK_UNDER -> broadcastBlockUnderHint(server, hider)
                    HintEngine.VariedHint.SKY_ACCESS -> broadcastSkyAccessHint(server, hider)
                    HintEngine.VariedHint.HEIGHT_DIFF -> broadcastHeightDiffHint(server, seeker, hider)
                }
        }
    }

    private fun broadcastDistanceHint(
        server: MinecraftServer,
        seeker: ServerPlayer,
        hider: ServerPlayer,
    ) {
        if (seeker.level().dimension() != hider.level().dimension()) {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable("exservermod.hideandseek.hint.distance_diff_dimension")
                    .withStyle(ChatFormatting.GOLD),
                false,
            )
            return
        }

        val dist = seeker.position().distanceTo(hider.position())
        val message =
            if (dist < CLOSE_DISTANCE_BLOCKS) {
                Component
                    .translatable("exservermod.hideandseek.hint.distance_close")
                    .withStyle(ChatFormatting.GOLD)
            } else {
                val tier = HideAndSeekGame.calculateDistanceTier(dist)
                Component
                    .translatable("exservermod.hideandseek.hint.distance", tier)
                    .withStyle(ChatFormatting.GOLD)
            }
        server.playerList.broadcastSystemMessage(message, false)
        playSoundToParticipants(server, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f)
    }

    private fun broadcastLandmarkHint(
        server: MinecraftServer,
        hider: ServerPlayer,
    ) {
        val dim =
            hider
                .level()
                .dimension()
                .identifier()
                .toString()
        val closest = landmarks.findClosest(hider.x, hider.z, dim)
        if (closest != null) {
            val landmarkDist = hypot(hider.x - closest.x, hider.z - closest.z).toInt()
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable("exservermod.hideandseek.hint.closest_landmark", closest.name, landmarkDist)
                    .withStyle(ChatFormatting.AQUA),
                false,
            )
        } else {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable("exservermod.hideandseek.hint.no_landmarks")
                    .withStyle(ChatFormatting.GRAY),
                false,
            )
        }
    }

    private fun broadcastBlockUnderHint(
        server: MinecraftServer,
        hider: ServerPlayer,
    ) {
        val below = hider.level().getBlockState(hider.blockPosition().below())
        server.playerList.broadcastSystemMessage(
            Component
                .translatable("exservermod.hideandseek.hint.block_under", below.block.name)
                .withStyle(ChatFormatting.AQUA),
            false,
        )
    }

    private fun broadcastSkyAccessHint(
        server: MinecraftServer,
        hider: ServerPlayer,
    ) {
        val openSky = hider.level().canSeeSkyFromBelowWater(hider.blockPosition())
        val key = if (openSky) "exservermod.hideandseek.hint.sky_open" else "exservermod.hideandseek.hint.sky_hidden"
        server.playerList.broadcastSystemMessage(
            Component
                .translatable(key)
                .withStyle(ChatFormatting.AQUA),
            false,
        )
    }

    private fun broadcastHeightDiffHint(
        server: MinecraftServer,
        seeker: ServerPlayer,
        hider: ServerPlayer,
    ) {
        val dy = kotlin.math.round(hider.y - seeker.y).toInt()
        val message =
            when {
                kotlin.math.abs(dy) <= 2 ->
                    Component.translatable("exservermod.hideandseek.hint.height_same")
                dy > 0 ->
                    Component.translatable("exservermod.hideandseek.hint.height_above", dy)
                else ->
                    Component.translatable("exservermod.hideandseek.hint.height_below", -dy)
            }
        server.playerList.broadcastSystemMessage(
            message.withStyle(ChatFormatting.AQUA),
            false,
        )
    }

    /**
     * Reworked echo locator: instead of a single wobbly vibration, draws a
     * straight glow ray from the seeker towards the hider that dissolves
     * ECHO_STOP_DISTANCE blocks short of the target. Easy to follow visually.
     */
    private fun launchEchoPulse(
        server: MinecraftServer,
        seeker: ServerPlayer,
        hider: ServerPlayer,
    ) {
        if (seeker.level().dimension() != hider.level().dimension()) {
            return
        }

        val start = Vec3(seeker.x, seeker.eyeY, seeker.z)
        val target = Vec3(hider.x, hider.y + 1.0, hider.z)
        val delta = target.subtract(start)
        val dist = delta.length()
        if (dist <= ECHO_STOP_DISTANCE + 1.0) {
            return
        }

        val unit = delta.normalize()
        val end = target.subtract(unit.scale(ECHO_STOP_DISTANCE))
        val length = start.distanceTo(end)
        val steps = (length / ECHO_RAY_STEP).toInt().coerceIn(4, 48)
        val level = seeker.level()

        val surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, hider.blockX, hider.blockZ)
        val isUnderground = (surfaceY - hider.blockY) >= 3

        activeLaserTicks = 100 // Persist for 5 seconds (100 ticks)
        laserStart = start
        laserEnd = end
        laserLevel = level
        laserHiderPos = Vec3(hider.x, hider.y, hider.z)
        laserIsUnderground = isUnderground
        laserSurfaceY = surfaceY

        var i = 0
        while (i <= steps) {
            val t = i.toDouble() / steps
            val p = start.add(unit.scale(length * t))
            level.sendParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0)
            i++
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, end.x, end.y, end.z, 12, 0.25, 0.25, 0.25, 0.02)
        level.sendParticles(ParticleTypes.SONIC_BOOM, end.x, end.y, end.z, 1, 0.0, 0.0, 0.0, 0.0)

        // Text aid for the seeker: rough distance plus compass direction
        val directionKey = compassDirectionKey(unit.x, unit.z)
        if (isUnderground) {
            seeker.sendSystemMessage(
                Component
                    .translatable(
                        "exservermod.game.echo_pulse_underground",
                        (surfaceY - hider.blockY).coerceAtLeast(1),
                        dist.toInt(),
                        Component.translatable(directionKey),
                    ).withStyle(ChatFormatting.DARK_AQUA),
                false,
            )
        } else {
            seeker.sendSystemMessage(
                Component
                    .translatable(
                        "exservermod.game.echo_pulse_launched",
                        dist.toInt(),
                        Component.translatable(directionKey),
                    ).withStyle(ChatFormatting.DARK_AQUA),
                false,
            )
        }
        playSoundToParticipants(server, SoundEvents.WARDEN_SONIC_BOOM, 0.8f, 1.6f)
    }

    private fun tickActiveLaser() {
        val s = laserStart ?: return
        val e = laserEnd ?: return
        val lvl = laserLevel ?: return

        val delta = e.subtract(s)
        val len = delta.length()
        if (len > 0.0) {
            val u = delta.normalize()
            val steps = (len / ECHO_RAY_STEP).toInt().coerceIn(4, 48)
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val p = s.add(u.scale(len * t))
                lvl.sendParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0.04, 0.04, 0.04, 0.0)
            }
            lvl.sendParticles(ParticleTypes.END_ROD, e.x, e.y, e.z, 2, 0.1, 0.1, 0.1, 0.01)
        }

        if (laserIsUnderground) {
            val hp = laserHiderPos
            val sy = laserSurfaceY
            if (hp != null && sy > hp.y) {
                val ySteps = ((sy + 2 - hp.y) / 1.0).toInt().coerceIn(3, 30)
                for (i in 0..ySteps) {
                    val y = hp.y + i * 1.0
                    lvl.sendParticles(ParticleTypes.PORTAL, hp.x, y, hp.z, 2, 0.1, 0.1, 0.1, 0.02)
                }
                lvl.sendParticles(ParticleTypes.GLOW, hp.x, sy + 1.0, hp.z, 3, 0.2, 0.2, 0.2, 0.01)
            }
        }
    }

    /**
     * Maps a 2D direction vector to one of 8 compass keys (0 = north ... 7 = northwest).
     */
    private fun compassDirectionKey(
        dx: Double,
        dz: Double,
    ): String {
        val angle = Math.toDegrees(kotlin.math.atan2(dx, -dz))
        val normalized = (angle + 360.0 + 22.5) % 360.0
        val index = (normalized / 45.0).toInt().coerceIn(0, 7)
        return "exservermod.hideandseek.hint.dir.$index"
    }

    private fun registerJoin() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            TrackerCompassManager.purgePlayer(handler.player)
            for ((uuid, mobType) in disguiseManager.disguises) {
                val mobId = BuiltInRegistries.ENTITY_TYPE.getKey(mobType).toString()
                ServerPlayNetworking.send(handler.player, DisguiseSyncPayload(uuid, mobId, true))
            }
        }
    }

    private fun registerServerStarted() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            TrackerCompassManager.purgeAll(server)
        }
    }

    private fun playSoundToParticipants(
        server: MinecraftServer,
        sound: Holder<SoundEvent>,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        val participants = listOfNotNull(game.hiderUuid, game.seekerUuid)
        for (uuid in participants) {
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

    private fun playSoundToParticipants(
        server: MinecraftServer,
        sound: SoundEvent,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        playSoundToParticipants(server, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)
    }

    private fun playSoundToPlayer(
        player: ServerPlayer,
        sound: Holder<SoundEvent>,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
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

    private fun playSoundToPlayer(
        player: ServerPlayer,
        sound: SoundEvent,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        playSoundToPlayer(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)
    }

    private fun registerDisconnect() {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            disguiseManager.clearDisguise(handler.player)
            BlueMapCompat.setPlayerVisibility(handler.player.uuid, true)
            TrackerCompassManager.purgePlayer(handler.player)

            // Carpet fake players (AFK bot) fire this on /player kill or server
            // restart; they must never tear down an active round.
            if (AfkManager.isBotPlayer(handler.player)) {
                return@register
            }

            if (game.isParticipant(handler.player.uuid)) {
                clearTrackerCompasses(server)
                RouletteManager.abortAll(server)
                game.leave(handler.player.uuid)
                server.playerList.broadcastSystemMessage(
                    Component
                        .translatable("exservermod.game.cancelled", handler.player.name)
                        .withStyle(ChatFormatting.GRAY),
                    false,
                )
            }
        }
    }

    private fun registerServerStopping() {
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            RouletteManager.clear()
            disguiseManager.clearAll(server)
            clearTrackerCompasses(server)
            val participants = listOfNotNull(game.hiderUuid, game.seekerUuid)
            BlueMapCompat.setPlayersVisibility(participants, true)
        }
    }

    private fun registerRouletteFinished() {
        ServerPlayNetworking.registerGlobalReceiver(RouletteFinishedPayload.TYPE) { _, ctx ->
            ctx.server().execute {
                RouletteManager.applyDisguise(ctx.player(), disguiseManager)
            }
        }

        // Player closed the wheel without finishing it: drop the pending disguise
        // and abort the round instead of silently continuing it.
        ServerPlayNetworking.registerGlobalReceiver(RouletteCanceledPayload.TYPE) { _, ctx ->
            ctx.server().execute {
                val player = ctx.player()
                val hadPending = RouletteManager.cancel(player.uuid)
                if (hadPending && game.isGameActive() && player.uuid == game.hiderUuid) {
                    val participants = listOfNotNull(game.hiderUuid, game.seekerUuid, player.uuid)
                    clearTrackerCompasses(ctx.server())
                    game.leave(player.uuid)
                    disguiseManager.clearAll(ctx.server())
                    BlueMapCompat.setPlayersVisibility(participants, true)
                    ctx.server().playerList.broadcastSystemMessage(
                        Component
                            .translatable("exservermod.game.cancelled_roulette", player.name)
                            .withStyle(ChatFormatting.GRAY),
                        false,
                    )
                }
            }
        }
    }

    private fun onPhaseChanged(
        server: MinecraftServer,
        oldPhase: GamePhase,
        newPhase: GamePhase,
    ) {
        val participants = listOfNotNull(game.hiderUuid, game.seekerUuid)
        when (newPhase) {
            GamePhase.SEEKING -> {
                BlueMapCompat.setPlayersVisibility(participants, false)
                hintEngine.reset()

                val seeker = game.seekerUuid?.let { server.playerList.getPlayer(it) }
                if (seeker != null) {
                    val emergencyHintBtn =
                        Component.translatable("exservermod.game.button.hint_emergency").withStyle { style ->
                            style
                                .withColor(ChatFormatting.LIGHT_PURPLE)
                                .withClickEvent(ClickEvent.RunCommand("/games hint"))
                                .withHoverEvent(
                                    HoverEvent.ShowText(Component.translatable("exservermod.game.button.hint_emergency.hover")),
                                )
                        }
                    seeker.sendSystemMessage(emergencyHintBtn)
                }

                val hider = game.hiderUuid?.let { server.playerList.getPlayer(it) }
                if (hider != null) {
                    hiderInitialX = hider.x
                    hiderInitialZ = hider.z
                    lastAlertedX = hider.x
                    lastAlertedZ = hider.z
                }
                activeLaserTicks = 0

                if (seeker != null && hider != null) {
                    grantSectorCompass(server, seeker, hider)
                }
            }
            GamePhase.HIDING -> {
                BlueMapCompat.setPlayersVisibility(participants, false)
                hiderInitialX = null
                hiderInitialZ = null
                lastAlertedX = null
                lastAlertedZ = null
                activeLaserTicks = 0
                disguiseManager.resetUnstuckCounters()
                clearTrackerCompasses(server)
            }
            GamePhase.INTERMISSION -> {
                BlueMapCompat.setPlayersVisibility(participants, true)
                hiderInitialX = null
                hiderInitialZ = null
                lastAlertedX = null
                lastAlertedZ = null
                activeLaserTicks = 0
                clearTrackerCompasses(server)
            }
            GamePhase.IDLE -> {
                BlueMapCompat.setPlayersVisibility(participants, true)
                disguiseManager.clearAll(server)
                hiderInitialX = null
                hiderInitialZ = null
                lastAlertedX = null
                lastAlertedZ = null
                activeLaserTicks = 0
                clearTrackerCompasses(server)
            }
            GamePhase.VOTING -> {
                hiderInitialX = null
                hiderInitialZ = null
                lastAlertedX = null
                lastAlertedZ = null
                activeLaserTicks = 0
                clearTrackerCompasses(server)
            }
        }
    }

    private fun grantSectorCompass(
        server: MinecraftServer,
        seeker: ServerPlayer,
        hider: ServerPlayer,
    ) {
        val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
        val offsetDist = Random.nextDouble(SECTOR_OFFSET_MIN, SECTOR_OFFSET_MAX)
        val sectorPos =
            BlockPos(
                (hider.x + offsetDist * kotlin.math.cos(angle)).toInt(),
                hider.blockY,
                (hider.z + offsetDist * kotlin.math.sin(angle)).toInt(),
            )
        val targetPos = GlobalPos.of(hider.level().dimension(), sectorPos)
        val compass = TrackerCompassManager.createTrackerCompass(targetPos)

        if (!seeker.inventory.add(compass)) {
            seeker.drop(compass, false, Prediction.SERVER_ONLY)
        }
        seeker.inventoryMenu.broadcastChanges()

        seeker.sendSystemMessage(
            Component
                .translatable("exservermod.game.tracker_compass_given")
                .withStyle(ChatFormatting.GOLD),
        )
        playSoundToPlayer(seeker, SoundEvents.ITEM_PICKUP, 1.0f, 1.0f)
        trackerCompassRemovalTick = server.tickCount + TRACKER_COMPASS_DURATION_TICKS
    }

    private fun clearTrackerCompasses(server: MinecraftServer) {
        trackerCompassRemovalTick = null
        TrackerCompassManager.purgeAll(server)
    }
}
