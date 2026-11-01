package com.fome.exservermod

import com.fome.exservermod.afk.AfkCommands
import com.fome.exservermod.disguise.DisguiseManager
import com.fome.exservermod.game.GameCommands
import com.fome.exservermod.game.GameEventHandler
import com.fome.exservermod.game.HideAndSeekGame
import com.fome.exservermod.halloween.HalloweenCommands
import com.fome.exservermod.halloween.HalloweenEntities
import com.fome.exservermod.halloween.HalloweenEvent
import com.fome.exservermod.halloween.HalloweenItems
import com.fome.exservermod.halloween.network.CandyChannelPayload
import com.fome.exservermod.halloween.network.HalloweenSkySyncPayload
import com.fome.exservermod.halloween.network.MerchantBuyPayload
import com.fome.exservermod.halloween.network.OpenGhostNamingPayload
import com.fome.exservermod.halloween.network.OpenMerchantGuiPayload
import com.fome.exservermod.halloween.network.SubmitGhostNamePayload
import com.fome.exservermod.island.NightmareIslandManager
import com.fome.exservermod.landmarks.LandmarkCommands
import com.fome.exservermod.landmarks.LandmarkManager
import com.fome.exservermod.network.CloseRoulettePayload
import com.fome.exservermod.network.DisguiseSyncPayload
import com.fome.exservermod.network.OpenRoulettePayload
import com.fome.exservermod.network.RouletteCanceledPayload
import com.fome.exservermod.network.RouletteFinishedPayload
import com.fome.exservermod.sound.ModSounds
import com.fome.exservermod.waves.WaveCommands
import com.fome.exservermod.waves.WaveManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

object ExServerMod : ModInitializer {
    const val MOD_ID = "exservermod"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    val game = HideAndSeekGame()
    val disguiseManager = DisguiseManager()
    val landmarkManager by lazy {
        val configFile =
            FabricLoader
                .getInstance()
                .configDir
                .resolve("exservermod/landmarks.json")
                .toFile()
        LandmarkManager(configFile)
    }

    val waveManager = WaveManager()
    val islandManager = NightmareIslandManager()

    val halloweenEvent by lazy {
        val configDir = FabricLoader.getInstance().configDir.resolve("exservermod")
        val configFile = configDir.resolve("halloween.json").toFile()
        val storageFile = configDir.resolve("halloween_balances.json").toFile()
        HalloweenEvent(landmarkManager, configFile, storageFile)
    }

    override fun onInitialize() {
        logger.info("[ExServerMod] Initializing server mod.")

        ModSounds.register()
        HalloweenItems.register()
        HalloweenEntities.register()

        // Disguise & Roulette Payloads
        PayloadTypeRegistry.clientboundPlay().register(OpenRoulettePayload.TYPE, OpenRoulettePayload.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(CloseRoulettePayload.TYPE, CloseRoulettePayload.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(DisguiseSyncPayload.TYPE, DisguiseSyncPayload.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RouletteFinishedPayload.TYPE, RouletteFinishedPayload.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RouletteCanceledPayload.TYPE, RouletteCanceledPayload.STREAM_CODEC)

        // Halloween Event Payloads
        PayloadTypeRegistry.clientboundPlay().register(HalloweenSkySyncPayload.TYPE, HalloweenSkySyncPayload.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(OpenMerchantGuiPayload.TYPE, OpenMerchantGuiPayload.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(OpenGhostNamingPayload.TYPE, OpenGhostNamingPayload.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(CandyChannelPayload.TYPE, CandyChannelPayload.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(MerchantBuyPayload.TYPE, MerchantBuyPayload.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SubmitGhostNamePayload.TYPE, SubmitGhostNamePayload.STREAM_CODEC)

        GameEventHandler(game, landmarkManager, disguiseManager).register()
        GameCommands(game, disguiseManager, landmarkManager).register()
        LandmarkCommands(landmarkManager).register()
        if (!FabricLoader.getInstance().isModLoaded("exserverafk")) {
            AfkCommands(landmarkManager).register()
        }
        WaveCommands(landmarkManager).register()
        waveManager.register()

        halloweenEvent.register()
        HalloweenCommands(halloweenEvent).register()

        ServerTickEvents.END_SERVER_TICK.register { server ->
            val overworld = server.getLevel(Level.OVERWORLD)
            if (overworld != null) {
                islandManager.tick(overworld)
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            val overworld = server.getLevel(Level.OVERWORLD)
            if (overworld != null) {
                islandManager.clearAll(overworld)
            }
        }

        logger.info(
            "[ExServerMod] Core game, disguise engine, landmarks, AFK bot control, wave defense, " +
                "Nightmare Island, and Halloween event initialized.",
        )
    }
}
