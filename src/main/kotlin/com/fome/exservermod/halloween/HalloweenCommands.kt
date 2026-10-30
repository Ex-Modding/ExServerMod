package com.fome.exservermod.halloween

import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component

class HalloweenCommands(
    private val event: HalloweenEvent,
) {
    private fun isOp(source: net.minecraft.commands.CommandSourceStack): Boolean =
        source.player?.let { p -> source.server.playerList.isOp(p.nameAndId()) } ?: true

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            val eventNode =
                Commands
                    .literal("event")
                    .executes { ctx ->
                        sendStatus(ctx.source)
                        1
                    }.then(
                        Commands
                            .literal("start")
                            .requires { isOp(it) }
                            .executes { ctx ->
                                val server = ctx.source.server
                                event.startEvent(server)
                                1
                            },
                    ).then(
                        Commands
                            .literal("stop")
                            .requires { isOp(it) }
                            .executes { ctx ->
                                val server = ctx.source.server
                                event.endEvent(server)
                                1
                            },
                    ).then(
                        Commands
                            .literal("skip")
                            .requires { isOp(it) }
                            .executes { ctx ->
                                val server = ctx.source.server
                                if (event.phase == HalloweenPhase.ACTIVE_GATHERING) {
                                    event.skipGathering(server)
                                    ctx.source.sendSystemMessage(
                                        Component.translatable("exservermod.halloween.skipped").withStyle(ChatFormatting.YELLOW),
                                    )
                                } else {
                                    ctx.source.sendSystemMessage(
                                        Component
                                            .translatable(
                                                "exservermod.halloween.command.gathering_not_active",
                                            ).withStyle(ChatFormatting.RED),
                                    )
                                }
                                1
                            },
                    ).then(
                        Commands
                            .literal("spawnmerchant")
                            .requires { isOp(it) }
                            .executes { ctx ->
                                val server = ctx.source.server
                                val player = ctx.source.player
                                if (player != null) {
                                    event.spawnMerchantInFrontOf(server, player)
                                } else {
                                    ctx.source.sendSystemMessage(
                                        Component.translatable("exservermod.halloween.command.player_only").withStyle(ChatFormatting.RED),
                                    )
                                }
                                1
                            },
                    ).then(
                        Commands
                            .literal("status")
                            .executes { ctx ->
                                sendStatus(ctx.source)
                                1
                            },
                    ).then(
                        Commands
                            .literal("balance")
                            .executes { ctx ->
                                val player = ctx.source.player
                                if (player != null) {
                                    val bal = HalloweenEvent.balanceManager?.getBalance(player.uuid) ?: 0
                                    ctx.source.sendSystemMessage(
                                        Component
                                            .translatable("exservermod.halloween.candy_counter", bal)
                                            .withStyle(ChatFormatting.GOLD),
                                    )
                                }
                                1
                            },
                    ).then(
                        Commands
                            .literal("givecandy")
                            .requires { isOp(it) }
                            .then(
                                Commands
                                    .argument("amount", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        val player = ctx.source.player
                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                        if (player != null) {
                                            val newBal = HalloweenEvent.balanceManager?.addCandies(player, amount) ?: 0
                                            ctx.source.sendSystemMessage(
                                                Component.translatable(
                                                    "exservermod.halloween.command.given",
                                                    amount,
                                                    player.name.string,
                                                    newBal,
                                                ),
                                            )
                                        } else {
                                            ctx.source.sendSystemMessage(
                                                Component
                                                    .translatable(
                                                        "exservermod.halloween.command.specify_player",
                                                    ).withStyle(ChatFormatting.RED),
                                            )
                                        }
                                        1
                                    },
                            ).then(
                                Commands
                                    .argument("player", EntityArgument.player())
                                    .then(
                                        Commands
                                            .argument("amount", IntegerArgumentType.integer(1))
                                            .executes { ctx ->
                                                val target = EntityArgument.getPlayer(ctx, "player")
                                                val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                val newBal = HalloweenEvent.balanceManager?.addCandies(target, amount) ?: 0
                                                ctx.source.sendSystemMessage(
                                                    Component.translatable(
                                                        "exservermod.halloween.command.given",
                                                        amount,
                                                        target.name.string,
                                                        newBal,
                                                    ),
                                                )
                                                1
                                            },
                                    ),
                            ),
                    ).then(
                        Commands
                            .literal("setcandy")
                            .requires { isOp(it) }
                            .then(
                                Commands
                                    .argument("player", EntityArgument.player())
                                    .then(
                                        Commands
                                            .argument("amount", IntegerArgumentType.integer(0))
                                            .executes { ctx ->
                                                val target = EntityArgument.getPlayer(ctx, "player")
                                                val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                HalloweenEvent.balanceManager?.setCandies(target.uuid, amount)
                                                ctx.source.sendSystemMessage(
                                                    Component.translatable(
                                                        "exservermod.halloween.command.set",
                                                        target.name.string,
                                                        amount,
                                                    ),
                                                )
                                                1
                                            },
                                    ),
                            ),
                    )

            val registeredNode = dispatcher.register(eventNode)
            dispatcher.register(Commands.literal("halloween").redirect(registeredNode))
        }
    }

    private fun sendStatus(source: net.minecraft.commands.CommandSourceStack) {
        when (event.phase) {
            HalloweenPhase.IDLE -> {
                val seconds = event.ticksUntilNextEvent / 20
                val minutes = seconds / 60
                source.sendSystemMessage(
                    Component
                        .translatable(
                            "exservermod.halloween.status.idle",
                            minutes,
                            seconds % 60,
                        ).withStyle(ChatFormatting.YELLOW),
                )
            }

            HalloweenPhase.ACTIVE_GATHERING -> {
                val seconds = event.activeGatheringTicksLeft / 20
                val minutes = seconds / 60
                source.sendSystemMessage(
                    Component
                        .translatable(
                            "exservermod.halloween.status.gathering",
                            minutes,
                            seconds % 60,
                        ).withStyle(ChatFormatting.GOLD),
                )
            }

            HalloweenPhase.MERCHANT_ONLY -> {
                val seconds = event.merchantGraceTicksLeft / 20
                val minutes = seconds / 60
                source.sendSystemMessage(
                    Component
                        .translatable(
                            "exservermod.halloween.status.merchant_only",
                            minutes,
                            seconds % 60,
                        ).withStyle(ChatFormatting.LIGHT_PURPLE),
                )
            }
        }
    }
}
