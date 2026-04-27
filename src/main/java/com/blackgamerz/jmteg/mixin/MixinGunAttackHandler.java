package com.blackgamerz.jmteg.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "ttv.migami.jeg.entity.ai.GunAttackHandler")
public class MixinGunAttackHandler {
    @Inject(
            method = "performGunAttack",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void jmteg_finiteAmmoForMobs(Object shooter, Object target, Object itemStack, Object modifiedGun, float spreadModifier, boolean slowShot, CallbackInfo ci) {
        // Use reflection or cast to base interfaces if needed, otherwise just guard:
        if (itemStack == null) return;

        // Example using reflection for AmmoCount:
        try {
            java.lang.reflect.Method getOrCreateTag = itemStack.getClass().getMethod("getOrCreateTag");
            Object tag = getOrCreateTag.invoke(itemStack);
            java.lang.reflect.Method getInt = tag.getClass().getMethod("getInt", String.class);
            Integer ammo = (Integer) getInt.invoke(tag, "AmmoCount");
            if (ammo <= 0) {
                ci.cancel();
            }
        } catch (Exception ignored) {}
    }

    @Inject(
            method = "performGunAttack",
            at = @At("RETURN")
    )
    private static void jmteg_decrementAmmoAfterShot(Object shooter, Object target, Object itemStack, Object modifiedGun, float spreadModifier, boolean slowShot, CallbackInfo ci) {
        if (itemStack == null) return;
        try {
            java.lang.reflect.Method getOrCreateTag = itemStack.getClass().getMethod("getOrCreateTag");
            Object tag = getOrCreateTag.invoke(itemStack);
            java.lang.reflect.Method getInt = tag.getClass().getMethod("getInt", String.class);
            java.lang.reflect.Method putInt = tag.getClass().getMethod("putInt", String.class, int.class);
            Integer ammo = (Integer) getInt.invoke(tag, "AmmoCount");
            if (ammo > 0) {
                putInt.invoke(tag, "AmmoCount", ammo - 1);
            }
        } catch (Exception ignored) {}
    }
}