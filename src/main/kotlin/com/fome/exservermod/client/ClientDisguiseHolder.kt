package com.fome.exservermod.client

import com.fome.exservermod.mixin.WalkAnimationStateAccessor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ClientDisguiseHolder {
    private val disguises = ConcurrentHashMap<UUID, String>()
    private val dummyEntities = ConcurrentHashMap<UUID, Entity>()

    fun setDisguise(
        playerUuid: UUID,
        mobId: String,
        isDisguised: Boolean,
    ) {
        if (!isDisguised || mobId.isBlank()) {
            disguises.remove(playerUuid)
            dummyEntities.remove(playerUuid)?.discard()
        } else {
            val prev = disguises.put(playerUuid, mobId)
            if (prev != mobId) {
                dummyEntities.remove(playerUuid)?.discard()
            }
        }
    }

    fun isDisguised(playerUuid: UUID): Boolean = disguises.containsKey(playerUuid)

    fun getDisguiseMobId(playerUuid: UUID): String? = disguises[playerUuid]

    fun clear() {
        disguises.clear()
        dummyEntities.values.forEach { it.discard() }
        dummyEntities.clear()
    }

    fun getDisguiseEntity(player: Player): Entity? {
        val mobId = disguises[player.uuid] ?: return null
        val identifier = Identifier.tryParse(mobId) ?: return null
        val entityType: EntityType<*> = BuiltInRegistries.ENTITY_TYPE.getOptional(identifier).orElse(null) ?: return null

        var dummy = dummyEntities[player.uuid]
        if (dummy == null || dummy.type != entityType || dummy.level() != player.level() || !dummy.isAlive) {
            dummy = entityType.create(player.level(), EntitySpawnReason.EVENT)
            if (dummy != null) {
                dummy.id = player.id
                val px = player.x
                val py = player.y
                val pz = player.z
                dummy.setPos(px, py, pz)
                dummy.xo = px
                dummy.yo = py
                dummy.zo = pz
                dummy.xOld = px
                dummy.yOld = py
                dummy.zOld = pz
                dummy.tickCount = player.tickCount
                dummyEntities[player.uuid] = dummy
            }
        }
        return dummy
    }

    fun syncEntity(
        player: Player,
        mob: Entity,
        partialTick: Float,
    ) {
        mob.id = player.id
        mob.tickCount = player.tickCount

        mob.setPos(player.x, player.y, player.z)
        mob.xo = player.xo
        mob.yo = player.yo
        mob.zo = player.zo
        mob.xOld = player.xOld
        mob.yOld = player.yOld
        mob.zOld = player.zOld

        mob.yRot = player.yRot
        mob.yRotO = player.yRotO
        mob.xRot = player.xRot
        mob.xRotO = player.xRotO
        mob.isInvisible = false
        mob.setOnGround(player.onGround())
        mob.isShiftKeyDown = player.isShiftKeyDown
        mob.isSprinting = player.isSprinting
        mob.isSwimming = player.isSwimming
        mob.pose = player.pose
        mob.deltaMovement = player.deltaMovement

        if (mob is LivingEntity) {
            mob.yHeadRot = player.yHeadRot
            mob.yHeadRotO = player.yHeadRotO
            mob.yBodyRot = player.yBodyRot
            mob.yBodyRotO = player.yBodyRotO

            val playerWalk = player.walkAnimation as WalkAnimationStateAccessor
            val mobWalk = mob.walkAnimation as WalkAnimationStateAccessor
            mobWalk.speedOld = playerWalk.speedOld
            mobWalk.speed = playerWalk.speed
            mobWalk.position = playerWalk.position
            mobWalk.positionScale = playerWalk.positionScale

            mob.hurtTime = player.hurtTime
            mob.hurtDuration = player.hurtDuration
            mob.deathTime = player.deathTime
            val swing = player.currentSwing
            if (swing != null && mob.currentSwing == null) {
                mob.swing(swing.hand(), swing.animation(), false)
            }
        }
    }
}
