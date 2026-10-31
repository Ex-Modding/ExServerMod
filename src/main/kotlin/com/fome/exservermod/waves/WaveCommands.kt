package com.fome.exservermod.waves

import com.fome.exservermod.ExServerMod
import com.fome.exservermod.afk.AfkManager
import com.fome.exservermod.game.GamePhase
import com.fome.exservermod.game.RouletteManager
import com.fome.exservermod.island.NightmareIslandManager
import com.fome.exservermod.landmarks.LandmarkManager
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

class WaveCommands(
    private val landmarkManager: LandmarkManager,
) {
    private fun isOp(source: CommandSourceStack): Boolean = source.player?.let { p -> source.server.playerList.isOp(p.nameAndId()) } ?: true

    private fun findConsoleAnchor(source: CommandSourceStack): ServerPlayer? {
        val server = source.server
        return server.playerList.players.firstOrNull {
            !AfkManager.isBotPlayer(it) &&
                it.level().dimension() == Level.OVERWORLD &&
                NightmareIslandManager.isLocationOnIsland(it.x, it.z)
        } ?: server.playerList.players.firstOrNull { !AfkManager.isBotPlayer(it) }
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands
                    .literal("waves")
                    .executes { ctx ->
                        showStatus(ctx.source)
                        1
                    }.then(
                        Commands
                            .literal("start")
                            .executes { ctx ->
                                val player = ctx.source.player
                                if (player != null) {
                                    handleStart(ctx.source, player, null)
                                } else {
                                    val anchor = findConsoleAnchor(ctx.source)
                                    if (anchor != null) {
                                        handleStart(ctx.source, anchor, null)
                                    } else {
                                        ctx.source.sendSystemMessage(
                                            Component
                                                .translatable(
                                                    "exservermod.waves.error.island_only",
                                                    NightmareIslandManager.ISLAND_CENTER_X.toInt(),
                                                    NightmareIslandManager.ISLAND_CENTER_Z.toInt(),
                                                ).withStyle(ChatFormatting.RED),
                                        )
                                    }
                                }
                                1
                            }.then(
                                Commands
                                    .argument("landmark", StringArgumentType.string())
                                    .suggests { _, builder ->
                                        SharedSuggestionProvider.suggest(
                                            landmarkManager.getAllLandmarks().map { "\"${it.name}\"" },
                                            builder,
                                        )
                                    }.executes { ctx ->
                                        val player = ctx.source.player
                                        val landmark = StringArgumentType.getString(ctx, "landmark")
                                        if (player != null) {
                                            handleStart(ctx.source, player, landmark)
                                        } else {
                                            val anchor = findConsoleAnchor(ctx.source)
                                            if (anchor != null) {
                                                handleStart(ctx.source, anchor, landmark)
                                            } else {
                                                ctx.source.sendSystemMessage(
                                                    Component
                                                        .translatable(
                                                            "exservermod.waves.error.island_only",
                                                            NightmareIslandManager.ISLAND_CENTER_X.toInt(),
                                                            NightmareIslandManager.ISLAND_CENTER_Z.toInt(),
                                                        ).withStyle(ChatFormatting.RED),
                                                )
                                            }
                                        }
                                        1
                                    },
                            ),
                    ).then(
                        Commands
                            .literal("stop")
                            .executes { ctx ->
                                handleStop(ctx.source)
                                1
                            },
                    ).then(
                        Commands
                            .literal("reward")
                            .requires { isOp(it) }
                            .executes { ctx ->
                                val player = ctx.source.player
                                if (player != null) {
                                    ExServerMod.waveManager.giveDefenderVictoryRewards(ctx.source.server, player)
                                    ctx.source.sendSystemMessage(
                                        Component
                                            .translatable("exservermod.waves.reward.granted", player.name.string)
                                            .withStyle(ChatFormatting.GREEN),
                                    )
                                } else {
                                    ctx.source.sendSystemMessage(
                                        Component
                                            .translatable("exservermod.waves.reward.specify_player")
                                            .withStyle(ChatFormatting.RED),
                                    )
                                }
                                1
                            }.then(
                                Commands
                                    .argument("player", EntityArgument.player())
                                    .executes { ctx ->
                                        val target = EntityArgument.getPlayer(ctx, "player")
                                        ExServerMod.waveManager.giveDefenderVictoryRewards(ctx.source.server, target)
                                        ctx.source.sendSystemMessage(
                                            Component
                                                .translatable("exservermod.waves.reward.granted", target.name.string)
                                                .withStyle(ChatFormatting.GREEN),
                                        )
                                        1
                                    },
                            ),
                    ).then(
                        Commands
                            .literal("status")
                            .executes { ctx ->
                                showStatus(ctx.source)
                                1
                            },
                    ).then(
                        Commands
                            .literal("locate")
                            .executes { ctx ->
                                val player = ctx.source.player
                                if (player != null) {
                                    handleLocate(player)
                                }
                                1
                            },
                    ).then(
                        Commands
                            .literal("glow")
                            .executes { ctx ->
                                val player = ctx.source.player
                                if (player != null) {
                                    handleLocate(player)
                                }
                                1
                            },
                    ).then(
                        Commands
                            .literal("bell")
                            .executes { ctx ->
                                val player = ctx.source.player
                                if (player != null) {
                                    handleLocate(player)
                                }
                                1
                            },
                    ).then(
                        Commands
                            .literal("choice")
                            .then(
                                Commands.literal("trick").executes { ctx ->
                                    val player = ctx.source.player
                                    if (player != null) {
                                        ExServerMod.waveManager.handleHalloweenChoice(player, HalloweenChoice.TRICK)
                                    }
                                    1
                                },
                            ).then(
                                Commands.literal("treat").executes { ctx ->
                                    val player = ctx.source.player
                                    if (player != null) {
                                        ExServerMod.waveManager.handleHalloweenChoice(player, HalloweenChoice.TREAT)
                                    }
                                    1
                                },
                            ),
                    ).then(
                        Commands.literal("trick").executes { ctx ->
                            val player = ctx.source.player
                            if (player != null) {
                                ExServerMod.waveManager.handleHalloweenChoice(player, HalloweenChoice.TRICK)
                            }
                            1
                        },
                    ).then(
                        Commands.literal("treat").executes { ctx ->
                            val player = ctx.source.player
                            if (player != null) {
                                ExServerMod.waveManager.handleHalloweenChoice(player, HalloweenChoice.TREAT)
                            }
                            1
                        },
                    ),
            )
        }
    }

    private fun showStatus(source: CommandSourceStack) {
        val player = source.player
        val game = ExServerMod.waveManager
        if (player != null) {
            if (!game.isRunning()) {
                val startBtn =
                    Component.translatable("exservermod.waves.button.start").withStyle { style ->
                        style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(ClickEvent.RunCommand("/waves start"))
                            .withHoverEvent(HoverEvent.ShowText(Component.translatable("exservermod.waves.button.start.hover")))
                    }
                player.sendSystemMessage(
                    Component
                        .translatable("exservermod.waves.idle_intro")
                        .withStyle(ChatFormatting.AQUA)
                        .append(" ")
                        .append(startBtn),
                )
                player.sendSystemMessage(
                    Component.translatable("exservermod.waves.rules").withStyle(ChatFormatting.GRAY),
                )
            } else {
                player.sendSystemMessage(game.statusLine())
                val stopBtn =
                    Component.translatable("exservermod.waves.button.stop").withStyle { style ->
                        style
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(ClickEvent.RunCommand("/waves stop"))
                            .withHoverEvent(HoverEvent.ShowText(Component.translatable("exservermod.waves.button.stop.hover")))
                    }
                player.sendSystemMessage(stopBtn)
            }
        } else {
            if (!game.isRunning()) {
                source.sendSystemMessage(Component.translatable("exservermod.waves.idle_intro").withStyle(ChatFormatting.AQUA))
            } else {
                source.sendSystemMessage(game.statusLine())
            }
        }
    }

    private fun handleStart(
        source: CommandSourceStack,
        player: ServerPlayer,
        landmarkName: String?,
    ) {
        val server = source.server

        if (ExServerMod.game.isGameActive() || ExServerMod.game.phase == GamePhase.INTERMISSION) {
            source.sendSystemMessage(
                Component.translatable("exservermod.waves.error.hideandseek_running").withStyle(ChatFormatting.RED),
            )
            return
        }

        // AFK bot Steve is excluded from wave defense
        val defenders = server.playerList.players.filter { !AfkManager.isBotPlayer(it) }
        if (defenders.isEmpty()) {
            source.sendSystemMessage(
                Component.translatable("exservermod.waves.error.no_defenders").withStyle(ChatFormatting.RED),
            )
            return
        }

        // Close any stale roulette wheel, clear pending disguises before the fight
        RouletteManager.abortAll(server)
        ExServerMod.disguiseManager.clearAll(server)

        var arenaPos: Vec3? = null
        var displayArenaName: String? = null

        val level = player.level()

        // Wave defense is restricted exclusively to Nightmare Island
        if (level.dimension() != Level.OVERWORLD ||
            !NightmareIslandManager.isLocationOnIsland(player.x, player.z)
        ) {
            source.sendSystemMessage(
                Component
                    .translatable(
                        "exservermod.waves.error.island_only",
                        NightmareIslandManager.ISLAND_CENTER_X.toInt(),
                        NightmareIslandManager.ISLAND_CENTER_Z.toInt(),
                    ).withStyle(ChatFormatting.RED),
            )
            return
        }

        if (landmarkName != null) {
            val clean = landmarkName.trim().removeSurrounding("\"")
            val lm = landmarkManager.getLandmark(clean)
            if (lm == null) {
                source.sendSystemMessage(
                    Component.translatable("exservermod.afk.landmark_not_found", clean).withStyle(ChatFormatting.RED),
                )
                return
            }
            val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, lm.x.toInt(), lm.z.toInt())
            val y = if (surfaceY > level.minY) surfaceY.toDouble() else player.y
            arenaPos = Vec3(lm.x, y, lm.z)
            displayArenaName = lm.name
        } else {
            // Default directly to Nightmare Island center arena
            val surfaceY =
                level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING,
                    NightmareIslandManager.ISLAND_CENTER_X.toInt(),
                    NightmareIslandManager.ISLAND_CENTER_Z.toInt(),
                )
            val y = if (surfaceY in 60..78) surfaceY.toDouble() else NightmareIslandManager.ISLAND_CENTER_Y
            arenaPos = Vec3(NightmareIslandManager.ISLAND_CENTER_X, y, NightmareIslandManager.ISLAND_CENTER_Z)
        }

        if (!ExServerMod.waveManager.tryStart(player, defenders, arenaPos)) {
            source.sendSystemMessage(
                Component.translatable("exservermod.waves.error.already_running").withStyle(ChatFormatting.RED),
            )
            return
        }

        if (displayArenaName != null) {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable(
                        "exservermod.waves.started_arena",
                        displayArenaName,
                        WaveDefenseGame.TOTAL_WAVES,
                    ).withStyle(ChatFormatting.GOLD),
                false,
            )
        } else {
            server.playerList.broadcastSystemMessage(
                Component
                    .translatable(
                        "exservermod.waves.started",
                        WaveDefenseGame.TOTAL_WAVES,
                    ).withStyle(ChatFormatting.GOLD),
                false,
            )
        }

        server.playerList.broadcastSystemMessage(
            Component.translatable("exservermod.waves.started_hint").withStyle(ChatFormatting.YELLOW),
            false,
        )
    }

    private fun handleLocate(player: ServerPlayer) {
        val game = ExServerMod.waveManager
        if (!game.isRunning()) {
            player.sendSystemMessage(
                Component.translatable("exservermod.waves.error.not_running").withStyle(ChatFormatting.RED),
            )
            return
        }
        game.triggerLocateBell(player)
    }

    private fun handleStop(source: CommandSourceStack) {
        val server = source.server

        if (!ExServerMod.waveManager.tryStop(server)) {
            source.sendSystemMessage(
                Component.translatable("exservermod.waves.error.not_running").withStyle(ChatFormatting.RED),
            )
            return
        }

        val startBtn =
            Component.translatable("exservermod.waves.button.start").withStyle { style ->
                style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(ClickEvent.RunCommand("/waves start"))
                    .withHoverEvent(HoverEvent.ShowText(Component.translatable("exservermod.waves.button.start.hover")))
            }
        val stoppedMsg =
            source.player?.let { p ->
                Component.translatable("exservermod.waves.stopped_by", p.name).withStyle(ChatFormatting.GRAY)
            } ?: Component.translatable("exservermod.waves.stopped_generic").withStyle(ChatFormatting.GRAY)

        server.playerList.broadcastSystemMessage(
            stoppedMsg.append(" ").append(startBtn),
            false,
        )
    }
}
