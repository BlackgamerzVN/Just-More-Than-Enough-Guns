package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public interface IJEGCompat {
    void performGunAttack(Mob shooter, LivingEntity target, ItemStack itemStack, Object gun, float spreadModifier, boolean slowShot);

    /**
     * Returns the base projectile speed (blocks per tick) for the given gun stack.
     * Reads {@code gun.getProjectile().getSpeed()} via JEG reflection.
     * Returns {@code 3.0f} if JEG is absent or the value cannot be read.
     */
    float getProjectileSpeed(ItemStack gun);

    /**
     * Returns the projectile gravity magnitude (positive, blocks per tick²) for the given gun stack.
     * Reads {@code gun.getProjectile().getGravity()} via JEG reflection.
     * Returns {@code 0.04f} if JEG is absent or the value cannot be read.
     */
    float getProjectileGravity(ItemStack gun);

    /**
     * Returns the reload duration in ticks for the given gun stack.
     * Tries {@code GunItem.getUseDuration(stack)} then {@code gun.getReloads().getReloadTime()}
     * and {@code .getTime()}, guarded by {@code MAX_VALID_RELOAD_TICKS = 72000} against
     * Minecraft's default use-duration sentinel.
     * Returns {@code 0} if JEG is absent or the value cannot be read.
     */
    int getReloadTicks(ItemStack gun);
}