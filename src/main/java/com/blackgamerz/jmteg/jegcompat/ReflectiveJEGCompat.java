package com.blackgamerz.jmteg.jegcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;

/**
 * Reflective implementation of {@link IJEGCompat}.
 *
 * <p>All JEG type references are resolved at construction time via
 * {@link ReflectionCache} and stored as {@code Method} handles. Every public
 * method falls back to the documented default value on any {@link Throwable},
 * so callers never need to guard against {@code null} or exceptions.</p>
 *
 * <p>Instances are created by {@link JEGCompatManager} only when the JEG mod is
 * present in the mod list. If a particular method handle could not be resolved
 * (e.g., a future JEG version changed a signature) the method silently returns
 * its safe default — it does <em>not</em> throw.</p>
 */
public class ReflectiveJEGCompat implements IJEGCompat {

    // ── Cached method handles resolved once at construction ───────────────────
    private final Method performGunAttackMethod;   // AIGunEvent.performGunAttack(…)
    private final Method ejectCasingMethod;        // GunEventBus.ejectCasing(Level, LivingEntity)
    private final Method getModifiedGunMethod;     // GunItem.getModifiedGun(ItemStack)

    // Gun → sub-object getters
    private final Method gunGetGeneralMethod;      // Gun.getGeneral()
    private final Method gunGetReloadsMethod;      // Gun.getReloads()
    private final Method gunGetSoundsMethod;       // Gun.getSounds()
    private final Method gunGetProjectileMethod;   // Gun.getProjectile()

    // Gun.General methods
    private final Method generalGetRateMethod;     // Gun.General.getRate() → int

    // Gun.Reloads methods
    private final Method reloadsGetReloadTimerMethod; // Gun.Reloads.getReloadTimer() → int
    private final Method reloadsGetMaxAmmoMethod;     // Gun.Reloads.getMaxAmmo() → int

    // Gun.Sounds methods
    private final Method soundsGetFireMethod;      // Gun.Sounds.getFire() → ResourceLocation

    // Gun.Projectile methods
    private final Method projectileGetSpeedMethod; // Gun.Projectile.getSpeed() → double
    private final Method projectileIsGravityMethod;// Gun.Projectile.isGravity() → boolean

    public ReflectiveJEGCompat() {
        // AIGunEvent.performGunAttack
        Method performAttack = null;
        try {
            Class<?> aiGunEventClass = Class.forName("ttv.migami.jeg.entity.ai.AIGunEvent");
            performAttack = aiGunEventClass.getDeclaredMethod("performGunAttack",
                    Mob.class, LivingEntity.class, ItemStack.class,
                    Class.forName("ttv.migami.jeg.common.Gun"),
                    float.class, boolean.class);
        } catch (Throwable ignored) {}
        this.performGunAttackMethod = performAttack;

        // Assign all remaining handles from ReflectionCache (populated in the same static block)
        this.ejectCasingMethod         = ReflectionCache.getJeg_gunEventBus_ejectCasing();
        this.getModifiedGunMethod      = ReflectionCache.getJeg_getModifiedGun();

        this.gunGetGeneralMethod       = ReflectionCache.getJeg_gun_getGeneral();
        this.gunGetReloadsMethod       = ReflectionCache.getJeg_gun_getReloads();
        this.gunGetSoundsMethod        = ReflectionCache.getJeg_gun_getSounds();
        this.gunGetProjectileMethod    = ReflectionCache.getJeg_gun_getProjectile();

        this.generalGetRateMethod      = ReflectionCache.getJeg_general_getRate();
        this.reloadsGetReloadTimerMethod = ReflectionCache.getJeg_reloads_getReloadTimer();
        this.reloadsGetMaxAmmoMethod   = ReflectionCache.getJeg_reloads_getMaxAmmo();
        this.soundsGetFireMethod       = ReflectionCache.getJeg_sounds_getFire();
        this.projectileGetSpeedMethod  = ReflectionCache.getJeg_projectile_getSpeed();
        this.projectileIsGravityMethod = ReflectionCache.getJeg_projectile_isGravity();
    }

    // ── Firing ────────────────────────────────────────────────────────────────

    @Override
    public void performGunAttack(Mob shooter, LivingEntity target, ItemStack itemStack, Object gun, float spreadModifier, boolean slowShot) {
        if (performGunAttackMethod == null) return;
        try {
            performGunAttackMethod.invoke(null, shooter, target, itemStack, gun, spreadModifier, slowShot);
        } catch (Throwable ignored) {}
    }

    @Override
    public void ejectCasing(Level level, LivingEntity shooter) {
        if (ejectCasingMethod == null) return;
        try {
            ejectCasingMethod.invoke(null, level, shooter);
        } catch (Throwable ignored) {}
    }

    // ── Gun-data queries ──────────────────────────────────────────────────────

    @Override
    public Object getModifiedGun(ItemStack stack) {
        if (stack == null || stack.isEmpty() || getModifiedGunMethod == null) return null;
        try {
            return getModifiedGunMethod.invoke(stack.getItem(), stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public ResourceLocation getFireSound(Object gun) {
        if (gun == null || gunGetSoundsMethod == null || soundsGetFireMethod == null) return null;
        try {
            Object sounds = gunGetSoundsMethod.invoke(gun);
            if (sounds == null) return null;
            Object val = soundsGetFireMethod.invoke(sounds);
            return val instanceof ResourceLocation rl ? rl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public int getGunRate(Object gun) {
        if (gun == null || gunGetGeneralMethod == null || generalGetRateMethod == null) return 20;
        try {
            Object general = gunGetGeneralMethod.invoke(gun);
            if (general == null) return 20;
            Object val = generalGetRateMethod.invoke(general);
            return val instanceof Number n ? n.intValue() : 20;
        } catch (Throwable ignored) {
            return 20;
        }
    }

    @Override
    public int getGunReloadTimer(Object gun) {
        if (gun == null || gunGetReloadsMethod == null || reloadsGetReloadTimerMethod == null) return 0;
        try {
            Object reloads = gunGetReloadsMethod.invoke(gun);
            if (reloads == null) return 0;
            Object val = reloadsGetReloadTimerMethod.invoke(reloads);
            return val instanceof Number n ? n.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public int getGunMaxAmmo(Object gun) {
        if (gun == null || gunGetReloadsMethod == null || reloadsGetMaxAmmoMethod == null) return 1;
        try {
            Object reloads = gunGetReloadsMethod.invoke(gun);
            if (reloads == null) return 1;
            Object val = reloadsGetMaxAmmoMethod.invoke(reloads);
            return val instanceof Number n ? Math.max(1, n.intValue()) : 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    // ── Ballistic queries ─────────────────────────────────────────────────────

    @Override
    public float getProjectileSpeed(ItemStack stack) {
        Object gun = getModifiedGun(stack);
        if (gun == null || gunGetProjectileMethod == null || projectileGetSpeedMethod == null) return 3.0f;
        try {
            Object proj = gunGetProjectileMethod.invoke(gun);
            if (proj == null) return 3.0f;
            Object val = projectileGetSpeedMethod.invoke(proj);
            return val instanceof Number n ? n.floatValue() : 3.0f;
        } catch (Throwable ignored) {
            return 3.0f;
        }
    }

    @Override
    public float getProjectileGravity(ItemStack stack) {
        Object gun = getModifiedGun(stack);
        if (gun == null || gunGetProjectileMethod == null || projectileIsGravityMethod == null) return 0.04f;
        try {
            Object proj = gunGetProjectileMethod.invoke(gun);
            if (proj == null) return 0.04f;
            Object val = projectileIsGravityMethod.invoke(proj);
            // isGravity() == true  → use standard gravity constant
            // isGravity() == false → projectile is not affected by gravity
            return Boolean.TRUE.equals(val) ? 0.04f : 0.0f;
        } catch (Throwable ignored) {
            return 0.04f;
        }
    }

    @Override
    public int getReloadTicks(ItemStack stack) {
        return getGunReloadTimer(getModifiedGun(stack));
    }
}