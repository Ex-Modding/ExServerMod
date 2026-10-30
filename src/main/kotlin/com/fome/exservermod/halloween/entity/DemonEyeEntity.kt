package com.fome.exservermod.halloween.entity

import com.fome.exservermod.halloween.HalloweenEvent
import com.fome.exservermod.halloween.HalloweenPhase
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class DemonEyeEntity(
    type: EntityType<out DemonEyeEntity>,
    level: Level,
) : Monster(type, level) {
    companion object {
        val DATA_VARIANT: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(DemonEyeEntity::class.java, EntityDataSerializers.INT)

        fun createAttributes(): AttributeSupplier.Builder =
            Monster
                .createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 5.0)
                .add(Attributes.FLYING_SPEED, 0.22)
                .add(Attributes.MOVEMENT_SPEED, 0.18)
                .add(Attributes.FOLLOW_RANGE, 30.0)
    }

    private var diveCooldownTicks: Int = Random.nextInt(60, 100)
    private var isDiving: Boolean = false
    private var diveTicksLeft: Int = 0
    private var targetDivePos: Vec3? = null
    private var isRetreating: Boolean = false
    private var retreatTicksLeft: Int = 0
    private var targetRetreatPos: Vec3? = null
    private var windupTicks: Int = 0
    private var stunTicks: Int = 0

    private var wanderTarget: Vec3? = null
    private var wanderTicksLeft: Int = 0
    private var cachedGroundY: Int = 0

    init {
        isNoGravity = true
        noPhysics = false
    }

    fun getVariant(): Int = entityData.get(DATA_VARIANT)

    fun setVariant(variant: Int) {
        entityData.set(DATA_VARIANT, variant.coerceIn(0, 3))
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_VARIANT, Random.nextInt(4))
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        setVariant(input.getIntOr("Variant", 0))
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putInt("Variant", getVariant())
    }

    override fun canAttack(target: LivingEntity): Boolean {
        if (target is Player && (target.isCreative || target.isSpectator)) {
            return false
        }
        return super.canAttack(target)
    }

    override fun getAmbientSound(): SoundEvent = SoundEvents.PHANTOM_AMBIENT

    override fun getHurtSound(damageSource: DamageSource): SoundEvent = SoundEvents.PHANTOM_HURT

    override fun getDeathSound(): SoundEvent = SoundEvents.PHANTOM_DEATH

    override fun hurtServer(
        serverLevel: ServerLevel,
        damageSource: DamageSource,
        amount: Float,
    ): Boolean {
        val hit = super.hurtServer(serverLevel, damageSource, amount)
        if (hit) {
            stunTicks = 25
            isDiving = false
            isRetreating = false
            windupTicks = 0

            val attacker = damageSource.entity
            if (attacker != null) {
                val away =
                    position()
                        .subtract(attacker.position())
                        .normalize()
                        .scale(0.28)
                        .add(0.0, 0.12, 0.0)
                setDeltaMovement(away)
            }

            val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
            serverLevel.sendParticles(bloodOption, x, y + 0.35, z, 8, 0.2, 0.2, 0.2, 0.08)
        }
        return hit
    }

    override fun tick() {
        super.tick()
        isNoGravity = true

        val sLevel = level() as? ServerLevel ?: return

        // Blood trail in flight (redstone splatter & crimson spore droplets, NO HEARTS)
        if (tickCount % 3 == 0) {
            val mov = deltaMovement
            val trailOffset = if (mov.lengthSqr() > 0.001) mov.normalize().scale(-0.4) else Vec3.ZERO
            val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
            sLevel.sendParticles(
                bloodOption,
                x + trailOffset.x,
                y + 0.35 + trailOffset.y,
                z + trailOffset.z,
                1,
                0.04,
                0.04,
                0.04,
                0.01,
            )
            sLevel.sendParticles(
                ParticleTypes.CRIMSON_SPORE,
                x + trailOffset.x,
                y + 0.35 + trailOffset.y,
                z + trailOffset.z,
                2,
                0.05,
                0.05,
                0.05,
                0.01,
            )
        }

        // Strict 30 blocks player detection radius (ignoring creative & spectator players)
        var currentTarget = target
        if (currentTarget == null ||
            !currentTarget.isAlive ||
            currentTarget.isSpectator ||
            (currentTarget is Player && currentTarget.isCreative) ||
            currentTarget.distanceToSqr(this) > 900.0
        ) {
            if (tickCount % 10 == 0 || currentTarget == null) {
                val nearest =
                    sLevel
                        .players()
                        .filter { p ->
                            p.isAlive && !p.isSpectator && !p.isCreative && p.distanceToSqr(this) <= 900.0
                        }.minByOrNull { it.distanceToSqr(this) }
                currentTarget = nearest
                target = currentTarget
            }
        }

        // Current ground level directly below the eye (cached every second)
        if (tickCount % 20 == 0 || cachedGroundY == 0) {
            cachedGroundY = sLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)
        }
        val groundY = cachedGroundY
        val maxAllowedY = groundY + 50.0

        if (currentTarget != null && currentTarget.isAlive) {
            // Player is within 30 blocks
            val distToTarget = position().distanceTo(currentTarget.position())

            if (stunTicks > 0) {
                stunTicks--
                val recoilVel = deltaMovement.scale(0.85)
                setDeltaMovement(recoilVel)
            } else if (windupTicks > 0) {
                windupTicks--
                // Windup pause/jitter right before the dash
                val slowVel = deltaMovement.scale(0.65)
                setDeltaMovement(slowVel)

                if (windupTicks <= 0) {
                    isDiving = true
                    diveTicksLeft = 45
                    targetDivePos = currentTarget.eyePosition
                    sLevel.playSound(null, x, y, z, SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 0.9f, 1.2f)
                }
            } else if (isDiving) {
                diveTicksLeft--
                val targetPos = targetDivePos ?: currentTarget.eyePosition
                val diff = targetPos.subtract(position())
                val len = diff.length()
                val distToEye = position().distanceTo(currentTarget.eyePosition)

                if (distToEye < 1.35 || distToTarget < 1.35) {
                    // Hit player with EYE (front) - 1 HP physical mob attack
                    currentTarget.hurtServer(sLevel, damageSources().mobAttack(this), 1.0f)

                    sLevel.playSound(null, x, y, z, SoundEvents.FOX_BITE, SoundSource.HOSTILE, 1.2f, 1.0f)
                    val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
                    sLevel.sendParticles(bloodOption, x, y + 0.35, z, 15, 0.25, 0.25, 0.25, 0.1)

                    // Bounce back gently
                    val bounce = diff.normalize().scale(-0.25).add(0.0, 0.2, 0.0)
                    setDeltaMovement(bounce)
                    isDiving = false
                    isRetreating = false
                    diveCooldownTicks = Random.nextInt(70, 110)
                } else if (diveTicksLeft <= 0 || len < 1.0 || len > 28.0) {
                    // Missed player! Retreat 5 blocks away and try to attack again
                    isDiving = false
                    isRetreating = true
                    retreatTicksLeft = 35
                    val away = position().subtract(currentTarget.eyePosition)
                    val horizAway =
                        Vec3(away.x, 0.0, away.z).let {
                            if (it.lengthSqr() < 0.01) Vec3(1.0, 0.0, 0.0) else it.normalize()
                        }
                    val retreatY = currentTarget.eyeY + 1.2
                    targetRetreatPos =
                        Vec3(
                            currentTarget.x + horizAway.x * 5.0,
                            retreatY,
                            currentTarget.z + horizAway.z * 5.0,
                        )
                    sLevel.playSound(null, x, y, z, SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 0.9f, 1.3f)
                } else {
                    // Slow, controllable dash (0.28 blocks/tick) - easy for player to dodge/hit!
                    val diveVelocity = diff.normalize().scale(0.28)
                    setDeltaMovement(diveVelocity)
                }
            } else if (isRetreating) {
                retreatTicksLeft--
                val targetPos = targetRetreatPos ?: currentTarget.eyePosition.add(0.0, 1.5, 0.0)
                val diff = targetPos.subtract(position())
                val distToRetreat = diff.length()

                if (distToRetreat < 0.8 || retreatTicksLeft <= 0) {
                    // Reached 5 blocks retreat: wind up and try attacking again immediately!
                    isRetreating = false
                    windupTicks = 12
                    sLevel.playSound(null, x, y, z, SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 0.9f, 1.2f)
                } else {
                    val retreatVel = diff.normalize().scale(0.24)
                    setDeltaMovement(retreatVel)
                }
            } else {
                diveCooldownTicks--
                if (diveCooldownTicks <= 0 && distToTarget < 15.0) {
                    // Start windup before lunge
                    windupTicks = 16
                    diveCooldownTicks = 80
                } else {
                    // Slow, steady approach (hovering above player eye level)
                    val desiredY = currentTarget.eyeY + 0.8 + sin(tickCount * 0.06) * 0.4
                    val targetPos = Vec3(currentTarget.x, desiredY, currentTarget.z)
                    val diff = targetPos.subtract(position())

                    // Approach speed: 0.16 (slow enough for running player to catch up!)
                    val moveSpeed = if (distToTarget > 10.0) 0.18 else 0.13
                    val vel = diff.normalize().scale(moveSpeed)
                    setDeltaMovement(vel)
                }
            }

            // Look directly at target with FRONT of eye (pupil)
            val dx = currentTarget.x - x
            val dy = (currentTarget.eyeY - 0.2) - y
            val dz = currentTarget.z - z
            val horizontalDist = sqrt(dx * dx + dz * dz)
            val targetYaw = (Mth.atan2(dz, dx) * (180.0 / Math.PI)).toFloat() - 90.0f
            val targetPitch = (-(Mth.atan2(dy, horizontalDist) * (180.0 / Math.PI))).toFloat()
            yRot = targetYaw
            yHeadRot = targetYaw
            yBodyRot = targetYaw
            xRot = targetPitch
        } else {
            isDiving = false
            isRetreating = false
            windupTicks = 0
            // NO player within 30 blocks: chaotic wandering, but STRICTLY capped at <= 50 blocks above ground
            if (y >= maxAllowedY) {
                // Must descend immediately
                val descendVel = Vec3(deltaMovement.x * 0.7, -0.22, deltaMovement.z * 0.7)
                setDeltaMovement(descendVel)
            } else if (y <= groundY + 2.5) {
                // Too close to ground, lift up slightly
                val ascendVel = Vec3(deltaMovement.x * 0.7, 0.15, deltaMovement.z * 0.7)
                setDeltaMovement(ascendVel)
            } else {
                wanderTicksLeft--
                if (wanderTicksLeft <= 0 || wanderTarget == null || position().distanceToSqr(wanderTarget!!) < 2.0) {
                    wanderTicksLeft = Random.nextInt(40, 80)
                    val wanderAngle = Random.nextDouble(0.0, 2 * Math.PI)
                    val wanderDist = Random.nextDouble(4.0, 12.0)
                    val wx = x + cos(wanderAngle) * wanderDist
                    val wz = z + sin(wanderAngle) * wanderDist
                    val targetGroundY = sLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, wx.toInt(), wz.toInt())
                    // Target height between +4 and +20 blocks from ground (well below 50 blocks cap!)
                    val wy = (targetGroundY + Random.nextDouble(4.0, 18.0)).coerceAtMost(targetGroundY + 45.0)
                    wanderTarget = Vec3(wx, wy, wz)
                }

                val wanderDiff = wanderTarget!!.subtract(position())
                val wanderSpeed = 0.08
                val wanderVel = wanderDiff.normalize().scale(wanderSpeed)
                setDeltaMovement(wanderVel)

                // Face wandering direction with pupil
                if (wanderVel.horizontalDistanceSqr() > 0.0005) {
                    val wdx = wanderVel.x
                    val wdz = wanderVel.z
                    val wdy = wanderVel.y
                    val wHoriz = sqrt(wdx * wdx + wdz * wdz)
                    val wYaw = (Mth.atan2(wdz, wdx) * (180.0 / Math.PI)).toFloat() - 90.0f
                    val wPitch = (-(Mth.atan2(wdy, wHoriz) * (180.0 / Math.PI))).toFloat()
                    yRot = wYaw
                    yHeadRot = wYaw
                    yBodyRot = wYaw
                    xRot = wPitch
                }
            }
        }
    }

    override fun travel(travelVector: Vec3) {
        if (isEffectiveAi) {
            move(MoverType.SELF, deltaMovement)
        } else {
            super.travel(travelVector)
        }
    }

    override fun die(damageSource: DamageSource) {
        super.die(damageSource)
        val sLevel = level() as? ServerLevel ?: return
        val killer = damageSource.entity as? ServerPlayer
        val amount = Random.nextInt(1, 4)
        if (killer != null) {
            val newBal = HalloweenEvent.balanceManager?.addCandies(killer, amount, sendActionBar = true) ?: 0
            val timeLeft =
                if (HalloweenEvent.instance?.phase == HalloweenPhase.ACTIVE_GATHERING) {
                    " | ⏱ ${HalloweenEvent.instance?.formattedTimeLeft()}"
                } else {
                    ""
                }
            killer.sendSystemMessage(
                Component
                    .translatable("exservermod.halloween.candy_mob_kill", amount, newBal, timeLeft)
                    .withStyle(ChatFormatting.GOLD),
                true,
            )
        }

        // Blood burst upon death (redstone block particles, ZERO hearts!)
        val bloodOption = BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState())
        sLevel.sendParticles(
            bloodOption,
            x,
            y + 0.35,
            z,
            16,
            0.3,
            0.3,
            0.3,
            0.12,
        )
    }
}
