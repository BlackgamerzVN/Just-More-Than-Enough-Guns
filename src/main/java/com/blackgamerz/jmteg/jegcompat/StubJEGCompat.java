package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * No-op fallback used when JEG is not loaded.
 * All methods return safe defaults so callers never need a null check on
 * {@link JEGCompatManager#INSTANCE}.
 */
public class StubJEGCompat implements IJEGCompat {

    @Override
    public void performGunAttack(Mob shooter, LivingEntity target, ItemStack itemStack, Object gun, float spreadModifier, boolean slowShot) {}

    @Override
    public void ejectCasing(Level level, LivingEntity shooter) {}

    @Override
    public ResourceLocation getFireSound(Object gun) { return null; }

    @Override
    public int getGunRate(Object gun) { return 20; }

    @Override
    public int getGunReloadTimer(Object gun) { return 0; }

    @Override
    public int getGunMaxAmmo(Object gun) { return 1; }

    @Override
    public Object getModifiedGun(ItemStack stack) { return null; }

    @Override
    public float getProjectileSpeed(ItemStack gun) { return 3.0f; }

    @Override
    public float getProjectileGravity(ItemStack gun) { return 0.04f; }

    @Override
    public int getReloadTicks(ItemStack gun) { return 0; }
}