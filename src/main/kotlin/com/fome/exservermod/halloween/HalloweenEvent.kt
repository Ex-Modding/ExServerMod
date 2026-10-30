package com.fome.exservermod.halloween

import com.fome.exservermod.halloween.entity.BloodZombieEntity
import com.fome.exservermod.halloween.entity.CandyEntity
import com.fome.exservermod.halloween.entity.DemonEyeEntity
import com.fome.exservermod.halloween.entity.GhostPetEntity
import com.fome.exservermod.halloween.entity.HalloweenMerchantEntity
import com.fome.exservermod.halloween.network.CandyChannelPayload
import com.fome.exservermod.halloween.network.HalloweenSkySyncPayload
import com.fome.exservermod.halloween.network.MerchantBuyPayload
import com.fome.exservermod.halloween.network.OpenGhostNamingPayload
import com.fome.exservermod.halloween.network.OpenMerchantGuiPayload
import com.fome.exservermod.halloween.network.SubmitGhostNamePayload
import com.fome.exservermod.landmarks.LandmarkManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class HalloweenPhase {
    IDLE,
    ACTIVE_GATHERING,
    MERCHANT_ONLY,
}

class HalloweenEvent(
    private val landmarks: LandmarkManager,
    configFile: File,
    storageFile: File,
) {
    companion object {
        var instance: HalloweenEvent? = null
            private set
        var balanceManager: HalloweenBalanceManager? = null
        var config: HalloweenConfig = HalloweenConfig()
        var activeMerchantWares: List<String> = emptyList()

        val ALL_WARES_POOL =
            listOf(
                "pumpkin_bow",
                "ghost_pet",
                "golden_apple",
                "golden_carrots",
                "totem",
                "fireworks_shulker",
                "wither_skulls",
                "netherite_ingot",
                "slime_blocks",
            )
    }

    private val petsFile = File(storageFile.parentFile, "halloween_ghost_pets.json")
    private val playerPets: MutableMap<UUID, UUID> = ConcurrentHashMap()
    private val pendingPetSummons: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    init {
        instance = this
        config = HalloweenConfig.loadOrCreate(configFile)
        balanceManager = HalloweenBalanceManager(storageFile)
        loadPlayerPets()
    }

    private fun loadPlayerPets() {
        if (!petsFile.exists()) return
        try {
            val json = petsFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded: Map<String, String>? = Gson().fromJson(json, type)
            playerPets.clear()
            loaded?.forEach { (k, v) ->
                try {
                    playerPets[UUID.fromString(k)] = UUID.fromString(v)
                } catch (_: IllegalArgumentException) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun savePlayerPets() {
        try {
            petsFile.parentFile?.mkdirs()
            val map = playerPets.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
            petsFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(map), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun hasActiveGhostPet(player: ServerPlayer): Boolean {
        val petUuid = playerPets[player.uuid] ?: return false
        val server = player.level().server
        for (lvl in server.allLevels) {
            val entity = lvl.getEntity(petUuid)
            if (entity != null) {
                if (!entity.isAlive || entity.isRemoved) {
                    playerPets.remove(player.uuid)
                    savePlayerPets()
                    return false
                }
                return true
            }
        }
        return true
    }

    var phase: HalloweenPhase = HalloweenPhase.IDLE
        private set

    var ticksUntilNextEvent: Int = config.eventCooldownSeconds * 20
        private set

    var activeGatheringTicksLeft: Int = 0
        private set

    var merchantGraceTicksLeft: Int = 0
        private set

    private var spawnedMerchantUuid: UUID? = null
    private val spawnedCandies: MutableList<CandyEntity> = mutableListOf()
    private val spawnedBloodMobs: MutableList<Mob> = mutableListOf()

    fun register() {
        registerServerTick()
        registerPackets()
        registerPlayerJoin()
        registerServerLifecycle()
    }

    fun formattedTimeLeft(): String {
        val totalSeconds =
            when (phase) {
                HalloweenPhase.ACTIVE_GATHERING -> (activeGatheringTicksLeft / 20).coerceAtLeast(0)
                HalloweenPhase.MERCHANT_ONLY -> (merchantGraceTicksLeft / 20).coerceAtLeast(0)
                HalloweenPhase.IDLE -> (ticksUntilNextEvent / 20).coerceAtLeast(0)
            }
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun registerServerTick() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (server.tickCount % 600 == 0) {
                balanceManager?.saveIfDirty(sync = false)
            }
            when (phase) {
                HalloweenPhase.IDLE -> {
                    ticksUntilNextEvent--
                    if (ticksUntilNextEvent <= 0) {
                        startEvent(server)
                    }
                }

                HalloweenPhase.ACTIVE_GATHERING -> {
                    activeGatheringTicksLeft--

                    maintainNightTime(server)

                    // Broadcast Action Bar HUD every second with time remaining
                    if (server.tickCount % 20 == 0) {
                        val timeLeft = formattedTimeLeft()
                        for (player in server.playerList.players) {
                            balanceManager?.sendBalanceActionBar(player, timeFormatted = timeLeft)
                        }
                    }

                    // Spawn blood mobs every 200 ticks (10s)
                    if (server.tickCount % 200 == 0) {
                        tickBloodMobSpawning(server)
                    }

                    // Replenish candies near players and cleanup far candies every 100 ticks (5s)
                    if (server.tickCount % 100 == 0) {
                        spawnCandiesAroundPlayers(server)
                        cleanupFarCandies(server)
                        cleanupFarBloodMobs(server)
                    }

                    // Ensure merchant is spawned
                    if (spawnedMerchantUuid == null || server.overworld().getEntity(spawnedMerchantUuid!!) == null) {
                        spawnMerchant(server)
                    }

                    if (activeGatheringTicksLeft <= 0) {
                        endGathering(server)
                    }
                }

                HalloweenPhase.MERCHANT_ONLY -> {
                    merchantGraceTicksLeft--

                    if (server.tickCount % 40 == 0) {
                        val timeLeft = formattedTimeLeft()
                        for (player in server.playerList.players) {
                            balanceManager?.sendBalanceActionBar(player, timeFormatted = timeLeft)
                        }
                    }

                    if (merchantGraceTicksLeft <= 0) {
                        endEvent(server)
                    }
                }
            }
        }
    }

    fun startEvent(server: MinecraftServer) {
        phase = HalloweenPhase.ACTIVE_GATHERING
        activeGatheringTicksLeft = config.eventDurationSeconds * 20
        merchantGraceTicksLeft = config.merchantGraceSeconds * 20
        ticksUntilNextEvent = config.eventCooldownSeconds * 20

        // Pick 6-8 randomized wares for this event
        activeMerchantWares = ALL_WARES_POOL.shuffled().take(Random.nextInt(6, 9))

        // Clean any lingering entities
        clearCandies()
        clearBloodMobs(server)

        val overworld = server.overworld()
        setNightTime(server)

        // Spawn merchant near active player right away
        spawnMerchant(server)

        // Sync blood sky to clients
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, HalloweenSkySyncPayload(true))
        }

        // Broadcast announcements
        server.playerList.broadcastSystemMessage(
            Component.translatable("exservermod.halloween.event_started").withStyle(ChatFormatting.GOLD),
            false,
        )

        for (player in overworld.players()) {
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.WITHER_SPAWN, SoundSource.AMBIENT, 0.8f, 0.5f)
        }

        // Spawn candies around players
        spawnCandiesAroundPlayers(server)
    }

    private fun spawnCandiesAroundPlayers(server: MinecraftServer) {
        val overworld = server.overworld()
        val players = overworld.players()
        if (players.isEmpty()) return

        spawnedCandies.removeAll { !it.isAlive }

        for (player in players) {
            val playerPos = player.position()
            val nearbyCount = spawnedCandies.count { it.isAlive && it.position().distanceTo(playerPos) <= 35.0 }
            val needed = 12 - nearbyCount
            if (needed <= 0) continue

            val toSpawn = minOf(needed, 8)
            for (i in 0 until toSpawn) {
                val angle = Random.nextDouble(0.0, 2 * Math.PI)
                val dist = Random.nextDouble(5.0, config.candySpawnRadius.toDouble())
                val spawnX = player.x + cos(angle) * dist
                val spawnZ = player.z + sin(angle) * dist

                val groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX.toInt(), spawnZ.toInt())
                val targetPos = BlockPos(spawnX.toInt(), groundY, spawnZ.toInt())

                // Strictly avoid caves / indoor blocks
                if (!overworld.canSeeSky(targetPos.above())) continue

                // Check distance separation from already spawned candies
                val candyVec = Vec3(spawnX, groundY + 1.0, spawnZ)
                if (spawnedCandies.any { it.isAlive && it.position().distanceTo(candyVec) < config.minCandySeparation }) continue

                val candy = CandyEntity(HalloweenEntities.CANDY, overworld)
                candy.setPos(spawnX, groundY + 1.0, spawnZ)
                overworld.addFreshEntity(candy)
                spawnedCandies.add(candy)
            }
        }
    }

    private fun cleanupFarCandies(server: MinecraftServer) {
        val overworld = server.overworld()
        val players = overworld.players()
        if (players.isEmpty()) return

        val iterator = spawnedCandies.iterator()
        while (iterator.hasNext()) {
            val candy = iterator.next()
            if (!candy.isAlive) {
                iterator.remove()
                continue
            }
            val pos = candy.position()
            val nearestPlayerDist = players.minOfOrNull { it.position().distanceTo(pos) } ?: Double.MAX_VALUE
            if (nearestPlayerDist > 80.0) {
                candy.discard()
                iterator.remove()
            }
        }
    }

    private fun isGlassOrLeaves(state: BlockState): Boolean {
        if (state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.IMPERMEABLE)) return true
        val desc = state.block.descriptionId.lowercase()
        return desc.contains("glass") || desc.contains("leaves")
    }

    private fun isClearOrSkyAbove(
        level: ServerLevel,
        pos: BlockPos,
    ): Boolean {
        val maxY = level.maxY
        for (y in (pos.y + 2)..maxY) {
            val checkPos = BlockPos(pos.x, y, pos.z)
            val state = level.getBlockState(checkPos)
            if (state.isAir) continue
            if (!isGlassOrLeaves(state)) {
                return false
            }
        }
        return true
    }

    private fun spawnMerchant(server: MinecraftServer) {
        val overworld = server.overworld()
        val allLandmarks = landmarks.getAllLandmarks().filter { it.dimension == "minecraft:overworld" }
        val activePlayer = overworld.players().firstOrNull()

        val (anchorX, anchorZ, landmarkName) =
            if (activePlayer != null) {
                val nearbyLm =
                    allLandmarks
                        .minByOrNull {
                            val dx = it.x - activePlayer.x
                            val dz = it.z - activePlayer.z
                            dx * dx + dz * dz
                        }?.takeIf {
                            val dx = it.x - activePlayer.x
                            val dz = it.z - activePlayer.z
                            (dx * dx + dz * dz) <= 40000.0
                        }
                Triple(activePlayer.x, activePlayer.z, nearbyLm?.name ?: "Окрестности")
            } else if (allLandmarks.isNotEmpty()) {
                val lm = allLandmarks.random()
                Triple(lm.x, lm.z, lm.name)
            } else {
                Triple(0.0, 0.0, "Спавн")
            }

        var chosenPos: BlockPos? = null
        var chosenDistance = 0.0

        for (attempt in 0 until 100) {
            val distance = Random.nextDouble(10.0, 25.0)
            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val targetX = (anchorX + cos(angle) * distance).toInt()
            val targetZ = (anchorZ + sin(angle) * distance).toInt()

            val groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ)
            val candidatePos = BlockPos(targetX, groundY, targetZ)
            val floor = overworld.getBlockState(candidatePos.below())
            val feet = overworld.getBlockState(candidatePos)
            val head = overworld.getBlockState(candidatePos.above())

            if (!feet.isAir || !head.isAir || floor.isAir || !overworld.getFluidState(candidatePos.below()).isEmpty) {
                continue
            }

            if (isClearOrSkyAbove(overworld, candidatePos)) {
                chosenPos = candidatePos
                chosenDistance = distance
                break
            }
        }

        if (chosenPos == null) {
            val targetX = anchorX.toInt() + 5
            val targetZ = anchorZ.toInt() + 5
            val groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, targetX, targetZ)
            chosenPos = BlockPos(targetX, groundY, targetZ)
            chosenDistance = 10.0
        }

        val oldUuid = spawnedMerchantUuid
        if (oldUuid != null) {
            val oldEntity = overworld.getEntity(oldUuid)
            oldEntity?.discard()
        }

        val standingBlock = overworld.getBlockState(chosenPos.below()).block
        val standingBlockName = standingBlock.name.string

        val merchant = HalloweenMerchantEntity(HalloweenEntities.HALLOWEEN_MERCHANT, overworld)
        merchant.setPos(chosenPos.x + 0.5, chosenPos.y.toDouble(), chosenPos.z + 0.5)
        overworld.addFreshEntity(merchant)
        spawnedMerchantUuid = merchant.uuid

        server.playerList.broadcastSystemMessage(
            Component
                .translatable(
                    "exservermod.halloween.merchant_spawned_full",
                    landmarkName,
                    chosenDistance.toInt(),
                    standingBlockName,
                    chosenPos.x,
                    chosenPos.y,
                    chosenPos.z,
                ).withStyle(ChatFormatting.LIGHT_PURPLE),
            false,
        )

        for (player in overworld.players()) {
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.WANDERING_TRADER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f)
        }
    }

    fun skipGathering(server: MinecraftServer) {
        if (phase == HalloweenPhase.ACTIVE_GATHERING) {
            if (spawnedMerchantUuid == null) {
                spawnMerchant(server)
            }
            endGathering(server)
        }
    }

    fun spawnMerchantInFrontOf(
        server: MinecraftServer,
        player: ServerPlayer,
    ) {
        if (activeMerchantWares.isEmpty()) {
            activeMerchantWares = ALL_WARES_POOL.shuffled().take(Random.nextInt(6, 9))
        }
        val overworld = server.overworld()
        val look = player.lookAngle
        val spawnX = player.x + look.x * 2.0
        val spawnZ = player.z + look.z * 2.0
        val groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX.toInt(), spawnZ.toInt())
        val candidatePos = BlockPos(spawnX.toInt(), groundY, spawnZ.toInt())

        val oldMerchantUuid = spawnedMerchantUuid
        if (oldMerchantUuid != null) {
            val oldMerchant = overworld.getEntity(oldMerchantUuid)
            oldMerchant?.discard()
        }

        val merchant = HalloweenMerchantEntity(HalloweenEntities.HALLOWEEN_MERCHANT, overworld)
        merchant.setPos(candidatePos.x + 0.5, candidatePos.y.toDouble(), candidatePos.z + 0.5)
        overworld.addFreshEntity(merchant)
        spawnedMerchantUuid = merchant.uuid

        overworld.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            merchant.x,
            merchant.y + 1.0,
            merchant.z,
            15,
            0.3,
            0.5,
            0.3,
            0.1,
        )

        overworld.playSound(
            null,
            merchant.x,
            merchant.y,
            merchant.z,
            SoundEvents.WANDERING_TRADER_YES,
            SoundSource.NEUTRAL,
            1.0f,
            1.0f,
        )

        player.sendSystemMessage(
            Component.translatable("exservermod.halloween.merchant_spawned_cmd").withStyle(ChatFormatting.LIGHT_PURPLE),
        )
    }

    private fun endGathering(server: MinecraftServer) {
        phase = HalloweenPhase.MERCHANT_ONLY
        clearCandies()
        clearBloodMobs(server)

        if (spawnedMerchantUuid == null || server.overworld().getEntity(spawnedMerchantUuid!!) == null) {
            spawnMerchant(server)
        }

        // Turn off blood sky
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, HalloweenSkySyncPayload(false))
        }

        server.playerList.broadcastSystemMessage(
            Component.translatable("exservermod.halloween.event_gathering_ended").withStyle(ChatFormatting.YELLOW),
            false,
        )
    }

    fun endEvent(server: MinecraftServer) {
        phase = HalloweenPhase.IDLE
        ticksUntilNextEvent = config.eventCooldownSeconds * 20
        clearCandies()
        clearBloodMobs(server)

        // Despawn merchant
        val merchantUuid = spawnedMerchantUuid
        if (merchantUuid != null) {
            val overworld = server.overworld()
            val merchant = overworld.getEntity(merchantUuid)
            if (merchant != null) {
                overworld.sendParticles(
                    ParticleTypes.POOF,
                    merchant.x,
                    merchant.y + 1.0,
                    merchant.z,
                    20,
                    0.5,
                    0.5,
                    0.5,
                    0.05,
                )
                merchant.discard()
            }
            spawnedMerchantUuid = null
        }

        // Restore normal sky
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, HalloweenSkySyncPayload(false))
        }

        server.playerList.broadcastSystemMessage(
            Component.translatable("exservermod.halloween.event_ended").withStyle(ChatFormatting.RED),
            false,
        )
    }

    private fun clearCandies() {
        for (candy in spawnedCandies) {
            if (candy.isAlive) {
                candy.discard()
            }
        }
        spawnedCandies.clear()
    }

    private fun tickBloodMobSpawning(server: MinecraftServer) {
        val overworld = server.overworld()
        val players = overworld.players()
        if (players.isEmpty()) return

        spawnedBloodMobs.removeAll { !it.isAlive }

        for (player in players) {
            val playerPos = player.position()
            val nearbyCount = spawnedBloodMobs.count { it.isAlive && it.position().distanceTo(playerPos) <= 40.0 }
            if (nearbyCount >= 4) continue

            val toSpawn = Random.nextInt(1, 3).coerceAtMost(4 - nearbyCount)
            for (i in 0 until toSpawn) {
                val angle = Random.nextDouble(0.0, 2 * Math.PI)
                val dist = Random.nextDouble(14.0, 28.0)
                val spawnX = player.x + cos(angle) * dist
                val spawnZ = player.z + sin(angle) * dist

                if (Random.nextBoolean()) {
                    val groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, spawnX.toInt(), spawnZ.toInt())
                    val spawnY = maxOf(player.y + Random.nextDouble(3.0, 6.0), groundY + 3.0)
                    val eye = DemonEyeEntity(HalloweenEntities.DEMON_EYE, overworld)
                    eye.setPos(spawnX, spawnY, spawnZ)
                    overworld.addFreshEntity(eye)
                    spawnedBloodMobs.add(eye)
                } else {
                    val groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX.toInt(), spawnZ.toInt())
                    val targetPos = BlockPos(spawnX.toInt(), groundY, spawnZ.toInt())
                    val floor = overworld.getBlockState(targetPos.below())
                    if (floor.isAir || !overworld.getFluidState(targetPos).isEmpty) continue

                    val zombie = BloodZombieEntity(HalloweenEntities.BLOOD_ZOMBIE, overworld)
                    zombie.setPos(spawnX, groundY.toDouble(), spawnZ)
                    overworld.addFreshEntity(zombie)
                    spawnedBloodMobs.add(zombie)
                }
            }
        }
    }

    private fun clearBloodMobs(server: MinecraftServer) {
        val overworld = server.overworld()
        for (mob in spawnedBloodMobs) {
            if (mob.isAlive) {
                overworld.sendParticles(
                    ParticleTypes.POOF,
                    mob.x,
                    mob.y + 0.5,
                    mob.z,
                    10,
                    0.2,
                    0.2,
                    0.2,
                    0.02,
                )
                mob.discard()
            }
        }
        spawnedBloodMobs.clear()
    }

    private fun cleanupFarBloodMobs(server: MinecraftServer) {
        val overworld = server.overworld()
        val players = overworld.players()
        if (players.isEmpty()) return

        val iterator = spawnedBloodMobs.iterator()
        while (iterator.hasNext()) {
            val mob = iterator.next()
            if (!mob.isAlive) {
                iterator.remove()
                continue
            }
            val pos = mob.position()
            val nearestPlayerDist = players.minOfOrNull { it.position().distanceTo(pos) } ?: Double.MAX_VALUE
            if (nearestPlayerDist > 80.0) {
                mob.discard()
                iterator.remove()
            }
        }
    }

    private fun setNightTime(server: MinecraftServer) {
        val overworld = server.overworld()
        val clock =
            overworld
                .dimensionTypeRegistration()
                .value()
                .defaultClock()
                .orElse(null)
        if (clock != null) {
            val current = server.clockManager().getTotalTicks(clock)
            val dayTicks = current % 24000L
            val needed = if (dayTicks <= 18000L) 18000L - dayTicks else (24000L - dayTicks) + 18000L
            server.clockManager().setTotalTicks(clock, current + needed)
        }
    }

    private fun maintainNightTime(server: MinecraftServer) {
        val overworld = server.overworld()
        val clock =
            overworld
                .dimensionTypeRegistration()
                .value()
                .defaultClock()
                .orElse(null)
        if (clock != null) {
            val ticks = server.clockManager().getTotalTicks(clock)
            val dayTicks = ticks % 24000L
            if (dayTicks < 13000L || dayTicks > 23000L) {
                val needed = if (dayTicks < 13000L) 18000L - dayTicks else (24000L - dayTicks) + 18000L
                server.clockManager().setTotalTicks(clock, ticks + needed)
            }
        }
    }

    private fun registerPackets() {
        ServerPlayNetworking.registerGlobalReceiver(CandyChannelPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            val entity = player.level().getEntity(payload.candyEntityId) as? CandyEntity
            entity?.setChanneling(player.uuid, payload.isHolding)
        }

        ServerPlayNetworking.registerGlobalReceiver(MerchantBuyPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            handleMerchantPurchase(player, payload.itemKey)
        }

        ServerPlayNetworking.registerGlobalReceiver(SubmitGhostNamePayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            handleGhostNaming(player, payload.petName)
        }
    }

    private fun giveOrDrop(
        player: ServerPlayer,
        stack: ItemStack,
    ) {
        if (!player.inventory.add(stack)) {
            player.drop(stack, false)
        }
    }

    private fun handleMerchantPurchase(
        player: ServerPlayer,
        itemKey: String,
    ) {
        if (phase == HalloweenPhase.IDLE) return
        if (!activeMerchantWares.contains(itemKey)) return

        val merchantNearby =
            player
                .level()
                .getEntitiesOfClass(
                    HalloweenMerchantEntity::class.java,
                    player.boundingBox.inflate(8.0),
                ).isNotEmpty()
        if (!merchantNearby) return

        if (itemKey == "ghost_pet" && hasActiveGhostPet(player)) {
            player.sendSystemMessage(
                Component.translatable("exservermod.halloween.already_have_pet").withStyle(ChatFormatting.RED),
                true,
            )
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f)
            return
        }

        val cost = config.prices[itemKey] ?: return
        val mgr = balanceManager ?: return

        if (!mgr.spendCandies(player, cost)) {
            player.sendSystemMessage(
                Component.translatable("exservermod.halloween.not_enough_candies").withStyle(ChatFormatting.RED),
                true,
            )
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f)
            return
        }

        player.level().playSound(null, player.x, player.y, player.z, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f)

        when (itemKey) {
            "ghost_pet" -> {
                pendingPetSummons.add(player.uuid)
                ServerPlayNetworking.send(player, OpenGhostNamingPayload.INSTANCE)
            }

            "pumpkin_bow" -> {
                giveOrDrop(player, ItemStack(HalloweenItems.PUMPKIN_BOW))
            }

            "golden_apple" -> {
                giveOrDrop(player, ItemStack(Items.ENCHANTED_GOLDEN_APPLE))
            }

            "golden_carrots" -> {
                giveOrDrop(player, ItemStack(Items.GOLDEN_CARROT, 64))
            }

            "totem" -> {
                giveOrDrop(player, ItemStack(Items.TOTEM_OF_UNDYING))
            }

            "fireworks_shulker" -> {
                giveOrDrop(player, createFireworksShulker())
            }

            "wither_skulls" -> {
                giveOrDrop(player, ItemStack(Items.WITHER_SKELETON_SKULL, 3))
            }

            "netherite_ingot" -> {
                giveOrDrop(player, ItemStack(Items.NETHERITE_INGOT))
            }

            "slime_blocks" -> {
                giveOrDrop(player, ItemStack(Items.SLIME_BLOCK, 16))
            }
        }

        if (itemKey != "ghost_pet") {
            // Refresh merchant GUI for normal items
            val newBalance = mgr.getBalance(player.uuid)
            val prices = activeMerchantWares.map { config.prices[it] ?: 100 }
            val hasPet = hasActiveGhostPet(player)
            ServerPlayNetworking.send(player, OpenMerchantGuiPayload(newBalance, activeMerchantWares, prices, hasPet))
        }
    }

    private fun handleGhostNaming(
        player: ServerPlayer,
        petName: String,
    ) {
        if (!pendingPetSummons.remove(player.uuid)) {
            return
        }
        val sLevel = player.level()

        // Despawn old pet if any
        val oldPetUuid = playerPets[player.uuid]
        if (oldPetUuid != null) {
            for (lvl in sLevel.server.allLevels.toList()) {
                val old = lvl.getEntity(oldPetUuid)
                old?.discard()
            }
        }

        val cleanName = petName.trim().ifEmpty { "Призрак" }
        val pet = GhostPetEntity(HalloweenEntities.GHOST_PET, sLevel)
        pet.ownerUuid = player.uuid
        pet.customName = Component.literal(cleanName)
        pet.setPos(player.x, player.y + 1.2, player.z)
        sLevel.addFreshEntity(pet)

        playerPets[player.uuid] = pet.uuid
        savePlayerPets()

        player.level().playSound(
            null,
            player.x,
            player.y,
            player.z,
            SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM,
            SoundSource.NEUTRAL,
            1.5f,
            1.2f,
        )
        player.sendSystemMessage(
            Component.translatable("exservermod.halloween.ghost_summoned", cleanName).withStyle(ChatFormatting.AQUA),
            false,
        )
    }

    private fun createFireworksShulker(): ItemStack {
        val shulker = ItemStack(Items.SHULKER_BOX)
        val rocket = ItemStack(Items.FIREWORK_ROCKET, 64)
        val explosion = FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntArrayList(listOf(0xFFA500)), IntArrayList(), true, true)
        rocket.set(DataComponents.FIREWORKS, Fireworks(3, listOf(explosion)))

        val stacks = List(27) { rocket.copy() }
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks))
        return shulker
    }

    private fun registerPlayerJoin() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            ServerPlayNetworking.send(player, HalloweenSkySyncPayload(phase == HalloweenPhase.ACTIVE_GATHERING))

            // Teleport pet if loaded in world and not waiting
            val petUuid = playerPets[player.uuid]
            if (petUuid != null) {
                val server = player.level().server
                for (lvl in server.allLevels) {
                    val pet = lvl.getEntity(petUuid) as? GhostPetEntity
                    if (pet != null) {
                        if (!pet.isWaiting && (pet.level() != player.level() || pet.distanceToSqr(player) > 576.0)) {
                            pet.teleportTo(player.level(), player.x, player.y + 1.2, player.z, emptySet(), 0.0f, 0.0f, false)
                        }
                        break
                    }
                }
            }
        }
    }

    private fun registerServerLifecycle() {
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            clearCandies()
            clearBloodMobs(server)
            balanceManager?.save()
            savePlayerPets()
        }
    }
}
