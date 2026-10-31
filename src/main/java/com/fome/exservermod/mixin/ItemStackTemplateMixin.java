package com.fome.exservermod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemStackTemplate.class)
public abstract class ItemStackTemplateMixin {

    @ModifyVariable(
        method = "<init>(Lnet/minecraft/core/Holder;ILnet/minecraft/core/component/DataComponentPatch;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private static int exservermod$fixZeroCount(int count) {
        return count <= 0 ? 1 : count;
    }

    @WrapOperation(
        method = "<init>(Lnet/minecraft/core/Holder;ILnet/minecraft/core/component/DataComponentPatch;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/core/Holder;)Z")
    )
    private boolean exservermod$allowAirTemplate(Holder<Item> instance, Holder<Item> other, Operation<Boolean> original) {
        return false;
    }
}
