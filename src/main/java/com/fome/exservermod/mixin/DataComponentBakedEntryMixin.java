package com.fome.exservermod.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.core.component.DataComponentInitializers$BakedEntry")
public abstract class DataComponentBakedEntryMixin {

    @Shadow
    @Final
    private Holder.Reference<?> element;

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void exservermod$skipNullElement(CallbackInfo ci) {
        if (this.element == null) {
            ci.cancel();
        }
    }
}
