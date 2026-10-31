package com.fome.exservermod.mixin;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;
import java.util.Objects;

@Mixin(MappedRegistry.class)
public abstract class MappedRegistrySafeIteratorMixin<T> {

    @WrapOperation(
        method = "iterator",
        at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/collect/Iterators;transform(Ljava/util/Iterator;Lcom/google/common/base/Function;)Ljava/util/Iterator;"
        )
    )
    private Iterator<T> exservermod$safeTransform(
        Iterator<Holder.Reference<T>> iterator,
        Function<? super Holder.Reference<T>, ? extends T> originalFunction,
        Operation<Iterator<T>> original
    ) {
        return Iterators.filter(
            Iterators.transform(iterator, ref -> (ref != null && ref.isBound()) ? ref.value() : null),
            Objects::nonNull
        );
    }
}
