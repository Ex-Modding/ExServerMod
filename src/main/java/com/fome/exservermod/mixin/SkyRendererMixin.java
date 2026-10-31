package com.fome.exservermod.mixin;

import com.fome.exservermod.halloween.client.HalloweenClientState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void exservermod$applyBloodSky(ClientLevel level, float f, Camera camera, SkyRenderState skyRenderState, CallbackInfo ci) {
        if (HalloweenClientState.isBloodSky() && level.dimension() == Level.OVERWORLD) {
            skyRenderState.skyColor = new Vector3f(0.4f, 0.04f, 0.04f);
            skyRenderState.sunriseAndSunsetColor = new Vector4f(0.533f, 0.067f, 0.067f, 1.0f);
        }
    }
}
