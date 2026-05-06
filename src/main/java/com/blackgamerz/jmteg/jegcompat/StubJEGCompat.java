package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * This fallback does nothing —- always safe
 */
public class StubJEGCompat implements IJEGCompat {
    @Override
    public void performGunAttack(Mob shooter, LivingEntity target, ItemStack itemStack, Object gun, float spreadModifier, boolean slowShot) {
        // Optionally: log or do nothing.
    }

    @Override
    public float getProjectileSpeed(ItemStack gun) {
        return 3.0f;
    }

    @Override
    public float getProjectileGravity(ItemStack gun) {
        return 0.04f;
    }

    @Override
    public int getReloadTicks(ItemStack gun) {
        return 0;
    }
}