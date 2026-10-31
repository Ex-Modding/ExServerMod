package com.fome.exservermod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.viaversion.viaversion.api.minecraft.item.StructuredItemTemplate", remap = false)
public abstract class ViaVersionTemplateItemMixin {

    @Redirect(
        method = "setIdentifier",
        at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/base/Preconditions;checkArgument(ZLjava/lang/Object;)V"
        ),
        require = 0
    )
    private void exservermod$ignoreIdentifierPrecondition(boolean expression, Object errorMessage) {
        // Allow empty or zero item identifiers during recipe remapping
    }

    @Redirect(
        method = "setAmount",
        at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/base/Preconditions;checkArgument(ZLjava/lang/Object;)V"
        ),
        require = 0
    )
    private void exservermod$ignoreAmountPrecondition(boolean expression, Object errorMessage) {
        // Allow empty or zero amounts during recipe remapping
    }
}
