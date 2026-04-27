package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/**
 * This implementation uses reflection to invoke JEG if present.
 * Returns silently if anything is missing or errors.
 */
public class ReflectiveJEGCompat implements IJEGCompat {
    private final Method performGunAttackMethod;
    private final Class<?> aiGunEventClass;

    public ReflectiveJEGCompat() {
        Method method = null;
        Class<?> clazz = null;
        try {
            clazz = Class.forName("ttv.migami.jeg.entity.ai.AIGunEvent");
            // (Mob, LivingEntity, ItemStack, Gun, float, boolean)
            method = clazz.getDeclaredMethod("performGunAttack",
                    Mob.class, LivingEntity.class, ItemStack.class,
                    Class.forName("ttv.migami.jeg.common.Gun"),
                    float.class, boolean.class
            );
        } catch (Exception e) {
            // JEG not present or method signature changed; fallback will be used
            method = null;
            clazz = null;
        }
        this.performGunAttackMethod = method;
        this.aiGunEventClass = clazz;
    }

    @Override
    public void performGunAttack(Mob shooter, LivingEntity target, ItemStack itemStack, Object gun, float spreadModifier, boolean slowShot) {
        if (performGunAttackMethod != null && aiGunEventClass != null) {
            try {
                performGunAttackMethod.invoke(null, shooter, target, itemStack, gun, spreadModifier, slowShot);
            } catch (Exception e) {
                // Log as desired, or silently fallback
            }
        }
    }
}