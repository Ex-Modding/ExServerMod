package com.fome.exservermod.mixin;

import com.fome.exservermod.game.TrackerCompassManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {

    @Inject(
        method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void exservermod$interceptTrackerCompassDrop(
        ItemStack stack,
        boolean throwRandomly,
        Prediction prediction,
        CallbackInfoReturnable<ItemEntity> cir
    ) {
        if (TrackerCompassManager.isTrackerCompass(stack)) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            player.sendSystemMessage(
                Component.translatable("exservermod.game.tracker_compass_expired").withStyle(ChatFormatting.GRAY)
            );
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0f, 0.9f);
            cir.setReturnValue(null);
        }
    }
}
