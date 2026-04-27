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
}