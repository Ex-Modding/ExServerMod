package com.fome.exservermod.halloween.client

import com.fome.exservermod.halloween.entity.CandyEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos

class CandyEntityRenderer(
    context: EntityRendererProvider.Context,
) : ThrownItemRenderer<CandyEntity>(context, 1.3f, false) {
    override fun getBlockLightLevel(
        entity: CandyEntity,
        pos: BlockPos,
    ): Int = 12

    override fun submit(
        state: ThrownItemRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        cameraRenderState: CameraRenderState,
    ) {
        poseStack.pushPose()
        val spin = (System.currentTimeMillis() % 3600L) * 0.1f
        poseStack.mulPose(Axis.YP.rotationDegrees(spin))
        super.submit(state, poseStack, submitNodeCollector, cameraRenderState)
        poseStack.popPose()
    }
}
