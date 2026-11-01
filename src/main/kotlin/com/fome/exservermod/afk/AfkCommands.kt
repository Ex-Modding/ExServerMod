package com.fome.exservermod.afk

import com.fome.exservermod.landmarks.DimensionFormatter
import com.fome.exservermod.landmarks.Landmark
import com.fome.exservermod.landmarks.LandmarkManager
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

class AfkCommands(
    private val landmarkManager: LandmarkManager,
) {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands
                    .literal("afk")
                    .executes { ctx ->
                        handleStatus(ctx.source)
                        1
                    }.then(
                        Commands
                            .literal("tp")
                            .then(
                                Commands
                                    .argument("landmark", StringArgumentType.string())
                                    .suggests { _, builder ->
                                        SharedSuggestionProvider.suggest(landmarkManager.getAllLandmarks().map { it.name }, builder)
                                    }.executes { ctx ->
                                        handleTeleport(ctx, StringArgumentType.getString(ctx, "landmark"))
                                        1
                                    },
                            ),
                    ).then(
                        Commands
                            .literal("random")
                            .executes { ctx ->
                                handleRandom(ctx.source)
                                1
                            },
                    ).then(
                        Commands
                            .literal("here")
                            .executes { ctx ->
                                handleHere(ctx.source)
                                1
                            },
                    ).then(
                        Commands
                            .literal("spawn")
                            .executes { ctx ->
                                handleSpawn(ctx.source, null)
                                1
                            }.then(
                                Commands
                                    .argument("landmark", StringArgumentType.string())
                                    .suggests { _, builder ->
                                        SharedSuggestionProvider.suggest(landmarkManager.getAllLandmarks().map { it.name }, builder)
                                    }.executes { ctx ->
                                        handleSpawn(ctx.source, StringArgumentType.getString(ctx, "landmark"))
                                        1
                                    },
                            ),
                    ).then(
                        Commands
                            .literal("kill")
                            .executes { ctx ->
                                handleKill(ctx.source)
                                1
                            },
                    ),
            )
        }
    }

    private fun handleStatus(source: CommandSourceStack) {
        val server = source.server
        val bot = AfkManager.findBot(server)

        source.sendSuccess(
            { Component.translatable("exservermod.afk.status_header", AfkManager.BOT_NAME).withStyle(ChatFormatting.GOLD) },
            false,
        )

        if (bot == null) {
            source.sendSuccess({ Component.translatable("exservermod.afk.bot_offline").withStyle(ChatFormatting.GRAY) }, false)
        } else {
            source.sendSuccess(
                {
                    Component
                        .translatable(
                            "exservermod.afk.bot_online",
                            bot.blockX,
                            bot.blockY,
                            bot.blockZ,
                            DimensionFormatter.format(bot.level().dimension().identifier()),
                        ).withStyle(ChatFormatting.GREEN)
                },
                false,
            )
        }

        val landmarks = landmarkManager.getAllLandmarks()
        if (landmarks.isEmpty()) {
            source.sendSuccess({ Component.translatable("exservermod.afk.no_landmarks").withStyle(ChatFormatting.GRAY) }, false)
            return
        }

        source.sendSuccess(
            { Component.translatable("exservermod.afk.landmarks_header", landmarks.size).withStyle(ChatFormatting.AQUA) },
            false,
        )
        landmarks.sortedBy { it.name.lowercase() }.forEach { landmark ->
            source.sendSuccess({ buildLandmarkButton(landmark.name) }, false)
        }
    }

    private fun buildLandmarkButton(landmarkName: String): Component =
        Component
            .translatable("exservermod.afk.button.goto", landmarkName)
            .withStyle { style ->
                style
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(ClickEvent.RunCommand("/afk tp \"$landmarkName\""))
                    .withHoverEvent(HoverEvent.ShowText(Component.translatable("exservermod.afk.button.goto.hover", landmarkName)))
            }

    private fun handleTeleport(
        ctx: CommandContext<CommandSourceStack>,
        landmarkName: String,
    ) {
        val source = ctx.source
        val server = source.server
        val bot = AfkManager.findBot(server)
        if (bot == null) {
            source.sendSuccess({ Component.translatable("exservermod.afk.bot_offline").withStyle(ChatFormatting.RED) }, false)
            return
        }

        val landmark =
            landmarkManager.getLandmark(landmarkName.trim().removeSurrounding("\""))
                ?: run {
                    source.sendSuccess(
                        { Component.translatable("exservermod.afk.landmark_not_found", landmarkName).withStyle(ChatFormatting.RED) },
                        false,
                    )
                    return
                }

        executeTeleport(source, server, bot, landmark)
    }

    private fun handleRandom(source: CommandSourceStack) {
        val server = source.server
        val bot = AfkManager.findBot(server)
        if (bot == null) {
            source.sendSuccess({ Component.translatable("exservermod.afk.bot_offline").withStyle(ChatFormatting.RED) }, false)
            return
        }

        val landmarks = landmarkManager.getAllLandmarks()
        if (landmarks.isEmpty()) {
            source.sendSuccess({ Component.translatable("exservermod.afk.no_landmarks").withStyle(ChatFormatting.RED) }, false)
            return
        }

        val nearest =
            landmarkManager.findClosest(
                bot.x,
                bot.z,
                bot
                    .level()
                    .dimension()
                    .identifier()
                    .toString(),
            )
        val landmark =
            AfkManager.pickRandomLandmark(landmarks, nearest?.name)
                ?: run {
                    source.sendSuccess({ Component.translatable("exservermod.afk.no_landmarks").withStyle(ChatFormatting.RED) }, false)
                    return
                }

        executeTeleport(source, server, bot, landmark)
    }

    private fun handleHere(source: CommandSourceStack) {
        val player = source.player
        if (player == null) {
            source.sendSuccess({ Component.translatable("exservermod.afk.players_only").withStyle(ChatFormatting.RED) }, false)
            return
        }

        val server = player.level().server
        val bot = AfkManager.findBot(server)
        if (bot == null) {
            player.sendSystemMessage(Component.translatable("exservermod.afk.bot_offline").withStyle(ChatFormatting.RED))
            return
        }

        val pos = findSafeNear(player)
        if (pos == null) {
            player.sendSystemMessage(Component.translatable("exservermod.afk.unsafe_spot").withStyle(ChatFormatting.RED))
            return
        }

        AfkManager.teleportToPos(bot, player.level(), Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5))
        player.sendSystemMessage(
            Component.translatable("exservermod.afk.teleported_here", AfkManager.BOT_NAME).withStyle(ChatFormatting.GREEN),
        )
    }

    private fun findSafeNear(player: ServerPlayer): BlockPos? {
        val base = player.blockPosition()
        if (isStandable(player.level(), base)) return base
        for (dx in -2..2) {
            for (dz in -2..2) {
                if (dx == 0 && dz == 0) continue
                val candidate = base.offset(dx, 0, dz)
                if (isStandable(player.level(), candidate)) return candidate
            }
        }
        return null
    }

    private fun isStandable(
        level: Level,
        pos: BlockPos,
    ): Boolean {
        val floor = level.getBlockState(pos.below())
        return floor.isFaceSturdy(level, pos.below(), Direction.UP) &&
            level.getBlockState(pos).isAir &&
            level.getBlockState(pos.above()).isAir
    }

    private fun handleSpawn(
        source: CommandSourceStack,
        landmarkName: String?,
    ) {
        val server = source.server
        if (AfkManager.isBotOnline(server)) {
            source.sendSuccess({ Component.translatable("exservermod.afk.already_online").withStyle(ChatFormatting.YELLOW) }, false)
            return
        }

        val landmarks = landmarkManager.getAllLandmarks()
        val landmark =
            when {
                landmarkName != null -> {
                    landmarkManager.getLandmark(landmarkName.trim().removeSurrounding("\"")) ?: run {
                        source.sendSuccess(
                            { Component.translatable("exservermod.afk.landmark_not_found", landmarkName).withStyle(ChatFormatting.RED) },
                            false,
                        )
                        return
                    }
                }
                landmarks.isNotEmpty() -> AfkManager.pickRandomLandmark(landmarks)
                else -> null
            }

        if (landmark != null) {
            val level = AfkManager.resolveLevel(server, landmark.dimension) ?: server.overworld()
            val pos = AfkManager.findSafeSurface(level, landmark.x, landmark.z)
            if (pos == null) {
                source.sendSuccess({ Component.translatable("exservermod.afk.unsafe_spot").withStyle(ChatFormatting.RED) }, false)
                return
            }

            val inDimension = if (landmark.dimension != "minecraft:overworld") " in ${landmark.dimension}" else ""
            val command = "player ${AfkManager.BOT_NAME} spawn at ${pos.x + 0.5} ${pos.y} ${pos.z + 0.5}$inDimension"
            val executed =
                try {
                    server.commands.performPrefixedCommand(
                        server.createCommandSourceStack(),
                        command,
                    )
                    true
                } catch (_: Exception) {
                    false
                }

            if (executed) {
                source.sendSuccess(
                    {
                        Component
                            .translatable(
                                "exservermod.afk.spawn_at_landmark",
                                AfkManager.BOT_NAME,
                                landmark.name,
                            ).withStyle(ChatFormatting.GREEN)
                    },
                    false,
                )
            } else {
                source.sendSuccess({ Component.translatable("exservermod.afk.spawn_failed").withStyle(ChatFormatting.RED) }, false)
            }
        } else {
            source.sendSuccess({ Component.translatable("exservermod.afk.no_landmarks_hint").withStyle(ChatFormatting.YELLOW) }, false)
        }
    }

    private fun handleKill(source: CommandSourceStack) {
        val server = source.server
        if (!AfkManager.isBotOnline(server)) {
            source.sendSuccess({ Component.translatable("exservermod.afk.bot_offline").withStyle(ChatFormatting.YELLOW) }, false)
            return
        }

        val executed =
            try {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "player ${AfkManager.BOT_NAME} kill",
                )
                true
            } catch (_: Exception) {
                false
            }

        if (executed) {
            source.sendSuccess(
                { Component.translatable("exservermod.afk.killed", AfkManager.BOT_NAME).withStyle(ChatFormatting.GREEN) },
                false,
            )
        } else {
            source.sendSuccess({ Component.translatable("exservermod.afk.spawn_failed").withStyle(ChatFormatting.RED) }, false)
        }
    }

    private fun executeTeleport(
        source: CommandSourceStack,
        server: MinecraftServer,
        bot: ServerPlayer,
        landmark: Landmark,
    ) {
        when (AfkManager.teleportToLandmark(server, bot, landmark)) {
            AfkTeleportResult.SUCCESS ->
                source.sendSuccess(
                    {
                        Component
                            .translatable(
                                "exservermod.afk.teleported",
                                AfkManager.BOT_NAME,
                                landmark.name,
                            ).withStyle(ChatFormatting.GREEN)
                    },
                    false,
                )
            AfkTeleportResult.WRONG_DIMENSION ->
                source.sendSuccess(
                    {
                        Component
                            .translatable(
                                "exservermod.afk.wrong_dimension",
                                DimensionFormatter.format(landmark.dimension),
                            ).withStyle(ChatFormatting.RED)
                    },
                    false,
                )
            AfkTeleportResult.UNSAFE_SPOT ->
                source.sendSuccess({ Component.translatable("exservermod.afk.unsafe_spot").withStyle(ChatFormatting.RED) }, false)
        }
    }
}
