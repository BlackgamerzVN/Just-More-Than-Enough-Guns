package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public interface IJEGCompat {

    // ── Firing ────────────────────────────────────────────────────────────────

    /** Fires the gun, spawning projectiles and sending bullet-trail packets. */
    void performGunAttack(Mob shooter, LivingEntity target, ItemStack itemStack, Object gun, float spreadModifier, boolean slowShot);

    /**
     * Spawns a bullet casing / shell at the shooter's position.
     * Delegates to {@code GunEventBus.ejectCasing(Level, LivingEntity)}.
     * No-op when JEG is absent.
     */
    void ejectCasing(Level level, LivingEntity shooter);

    // ── Gun-data queries (take a pre-resolved Gun object from getModifiedGun) ─

    /**
     * Returns the registry ID of the fire-sound event for this gun
     * ({@code gun.getSounds().getFire()}).
     * Returns {@code null} if JEG is absent, the sound is unset, or {@code gun} is null.
     */
    ResourceLocation getFireSound(Object gun);

    /**
     * Returns the fire-rate of this gun in ticks between shots
     * ({@code gun.getGeneral().getRate()}).
     * Returns {@code 20} (1 shot/s) when JEG is absent or {@code gun} is null.
     */
    int getGunRate(Object gun);

    /**
     * Returns the reload duration in ticks
     * ({@code gun.getReloads().getReloadTimer()}).
     * Returns {@code 0} when JEG is absent or {@code gun} is null.
     */
    int getGunReloadTimer(Object gun);

    /**
     * Returns the maximum ammo capacity (magazine / tube size)
     * ({@code gun.getReloads().getMaxAmmo()}).
     * Returns {@code 1} when JEG is absent or {@code gun} is null.
     */
    int getGunMaxAmmo(Object gun);

    /**
     * Resolves and returns the JEG {@code Gun} object for the given stack by invoking
     * {@code GunItem.getModifiedGun(ItemStack)}.
     * Returns {@code null} if JEG is absent, the item is not a {@code GunItem}, or
     * the stack is empty.
     */
    Object getModifiedGun(ItemStack stack);

    // ── Ballistic queries (take an ItemStack; internally call getModifiedGun) ─

    /**
     * Returns the base projectile speed (blocks per tick) for the given gun stack.
     * Reads {@code gun.getProjectile().getSpeed()} via JEG reflection.
     * Returns {@code 3.0f} if JEG is absent or the value cannot be read.
     */
    float getProjectileSpeed(ItemStack gun);

    /**
     * Returns the projectile gravity magnitude (positive, blocks per tick²) for the
     * given gun stack. Reads {@code gun.getProjectile().isGravity()} and maps it to
     * {@code 0.04f} (gravity on) or {@code 0.0f} (gravity off).
     * Returns {@code 0.04f} if JEG is absent or the value cannot be read.
     */
    float getProjectileGravity(ItemStack gun);

    /**
     * Returns the reload duration in ticks for the given gun stack.
     * Convenience wrapper: calls {@link #getModifiedGun(ItemStack)} then
     * {@link #getGunReloadTimer(Object)}.
     * Returns {@code 0} if JEG is absent or the value cannot be read.
     */
    int getReloadTicks(ItemStack gun);
}