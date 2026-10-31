package com.fome.exservermod.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = ClientRegistrySyncHandler.class, remap = false)
public abstract class ClientRegistrySyncHandlerMixin {

    @Inject(method = "apply", at = @At("HEAD"))
    private static void exservermod$stripUnknownRemoteRegistryEntries(RegistrySyncPayload payload, CallbackInfo ci) {
        if (payload == null || payload.registryMap() == null) {
            return;
        }

        payload.registryMap().entrySet().removeIf(e -> BuiltInRegistries.REGISTRY.getValue(e.getKey()) == null);

        for (Map.Entry<Identifier, Object2IntMap<Identifier>> entry : payload.registryMap().entrySet()) {
            Identifier registryId = entry.getKey();
            Registry<?> localRegistry = BuiltInRegistries.REGISTRY.getValue(registryId);
            if (localRegistry != null) {
                entry.getValue().keySet().removeIf(id -> !localRegistry.containsKey(id));
            }
        }
    }
}
