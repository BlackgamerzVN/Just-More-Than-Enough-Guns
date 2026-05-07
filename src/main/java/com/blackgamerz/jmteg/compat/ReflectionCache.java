package com.blackgamerz.jmteg.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Centralized reflection cache for JEG and inventory helper lookups.
 * Other classes call these getters instead of repeatedly doing Class.forName/getMethod.
 *
 * <p>Phase-2 additions cache the Gun inner-class methods and GunEventBus so that
 * {@link com.blackgamerz.jmteg.jegcompat.ReflectiveJEGCompat} can implement all
 * {@link com.blackgamerz.jmteg.jegcompat.IJEGCompat} primitives without any hard
 * compile-time dependency on JEG classes.</p>
 */
public final class ReflectionCache {
    private static final Logger LOGGER = LogManager.getLogger("JMT-ReflectionCache");

    private ReflectionCache() {}

    // ── Top-level JEG classes ─────────────────────────────────────────────────
    private static volatile Class<?> jegGunItemClass;
    private static volatile Class<?> jegCommonGunClass;

    // ── Gun inner/static-nested class handles ────────────────────────────────
    private static volatile Class<?> jegGunGeneralClass;
    private static volatile Class<?> jegGunReloadsClass;
    private static volatile Class<?> jegGunSoundsClass;
    private static volatile Class<?> jegGunProjectileClass;

    // ── GunEventBus class ─────────────────────────────────────────────────────
    private static volatile Class<?> jegGunEventBusClass;

    // ── GunItem instance methods ──────────────────────────────────────────────
    private static volatile Method jeg_getModifiedGun;

    // ── Gun ammo/pool helpers ─────────────────────────────────────────────────
    private static volatile Method jeg_findAmmoStack;
    private static volatile Method jeg_shrinkFromAmmoPool_1;
    private static volatile Method jeg_shrinkFromAmmoPool_2;

    // ── Gun → sub-object accessors ────────────────────────────────────────────
    private static volatile Method jeg_gun_getGeneral;
    private static volatile Method jeg_gun_getReloads;
    private static volatile Method jeg_gun_getSounds;
    private static volatile Method jeg_gun_getProjectile;

    // ── Gun.General methods ───────────────────────────────────────────────────
    /** @return int – fire rate (ticks between shots) */
    private static volatile Method jeg_general_getRate;

    // ── Gun.Reloads methods ───────────────────────────────────────────────────
    /** @return int – reload duration in ticks */
    private static volatile Method jeg_reloads_getReloadTimer;
    /** @return int – max magazine / tube capacity */
    private static volatile Method jeg_reloads_getMaxAmmo;

    // ── Gun.Sounds methods ────────────────────────────────────────────────────
    /** @return ResourceLocation (nullable) – sound event id for the fire sound */
    private static volatile Method jeg_sounds_getFire;

    // ── Gun.Projectile methods ────────────────────────────────────────────────
    /** @return double – base projectile speed (blocks per tick) */
    private static volatile Method jeg_projectile_getSpeed;
    /** @return boolean – whether gravity is applied to the projectile */
    private static volatile Method jeg_projectile_isGravity;

    // ── GunEventBus static methods ────────────────────────────────────────────
    /** static void ejectCasing(Level, LivingEntity) */
    private static volatile Method jeg_gunEventBus_ejectCasing;

    static {
        // ── Load top-level classes ─────────────────────────────────────────
        try { jegGunItemClass    = Class.forName("ttv.migami.jeg.item.GunItem");   } catch (Throwable ignored) {}
        try { jegCommonGunClass  = Class.forName("ttv.migami.jeg.common.Gun");     } catch (Throwable ignored) {}

        // ── Load Gun inner classes (static-nested, so '$' separator) ───────
        try { jegGunGeneralClass   = Class.forName("ttv.migami.jeg.common.Gun$General");   } catch (Throwable ignored) {}
        try { jegGunReloadsClass   = Class.forName("ttv.migami.jeg.common.Gun$Reloads");   } catch (Throwable ignored) {}
        try { jegGunSoundsClass    = Class.forName("ttv.migami.jeg.common.Gun$Sounds");    } catch (Throwable ignored) {}
        try { jegGunProjectileClass = Class.forName("ttv.migami.jeg.common.Gun$Projectile"); } catch (Throwable ignored) {}

        try { jegGunEventBusClass  = Class.forName("ttv.migami.jeg.event.GunEventBus");   } catch (Throwable ignored) {}

        // ── GunItem methods ────────────────────────────────────────────────
        if (jegGunItemClass != null) {
            try { jeg_getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class); } catch (Throwable ignored) {}
        }

        // ── Gun ammo helpers ───────────────────────────────────────────────
        if (jegCommonGunClass != null) {
            try { jeg_findAmmoStack = jegCommonGunClass.getMethod("findAmmoStack", ItemStack.class); } catch (Throwable ignored) {}
            try { jeg_shrinkFromAmmoPool_1 = jegCommonGunClass.getMethod("shrinkFromAmmoPool", ItemStack.class, int.class); } catch (Throwable ignored) {}
            try { jeg_shrinkFromAmmoPool_2 = jegCommonGunClass.getMethod("shrinkFromAmmoPool", net.minecraft.world.entity.PathfinderMob.class, ItemStack.class, int.class); } catch (Throwable ignored) {}

            // Gun sub-object getters
            try { jeg_gun_getGeneral    = jegCommonGunClass.getMethod("getGeneral");    } catch (Throwable ignored) {}
            try { jeg_gun_getReloads    = jegCommonGunClass.getMethod("getReloads");    } catch (Throwable ignored) {}
            try { jeg_gun_getSounds     = jegCommonGunClass.getMethod("getSounds");     } catch (Throwable ignored) {}
            try { jeg_gun_getProjectile = jegCommonGunClass.getMethod("getProjectile"); } catch (Throwable ignored) {}
        }

        // ── Gun.General methods ────────────────────────────────────────────
        if (jegGunGeneralClass != null) {
            try { jeg_general_getRate = jegGunGeneralClass.getMethod("getRate"); } catch (Throwable ignored) {}
        }

        // ── Gun.Reloads methods ────────────────────────────────────────────
        if (jegGunReloadsClass != null) {
            try { jeg_reloads_getReloadTimer = jegGunReloadsClass.getMethod("getReloadTimer"); } catch (Throwable ignored) {}
            try { jeg_reloads_getMaxAmmo     = jegGunReloadsClass.getMethod("getMaxAmmo");     } catch (Throwable ignored) {}
        }

        // ── Gun.Sounds methods ─────────────────────────────────────────────
        if (jegGunSoundsClass != null) {
            try { jeg_sounds_getFire = jegGunSoundsClass.getMethod("getFire"); } catch (Throwable ignored) {}
        }

        // ── Gun.Projectile methods ─────────────────────────────────────────
        if (jegGunProjectileClass != null) {
            try { jeg_projectile_getSpeed   = jegGunProjectileClass.getMethod("getSpeed");   } catch (Throwable ignored) {}
            try { jeg_projectile_isGravity  = jegGunProjectileClass.getMethod("isGravity");  } catch (Throwable ignored) {}
        }

        // ── GunEventBus static methods ─────────────────────────────────────
        if (jegGunEventBusClass != null) {
            try { jeg_gunEventBus_ejectCasing = jegGunEventBusClass.getMethod("ejectCasing", Level.class, LivingEntity.class); } catch (Throwable ignored) {}
        }
    }

    // ── Public getters ────────────────────────────────────────────────────────

    public static Class<?> getJegGunItemClass() { return jegGunItemClass; }
    public static Method getJeg_getModifiedGun() { return jeg_getModifiedGun; }
    public static Method getJeg_findAmmoStack() { return jeg_findAmmoStack; }
    public static Method getJeg_shrinkFromAmmoPool_1() { return jeg_shrinkFromAmmoPool_1; }
    public static Method getJeg_shrinkFromAmmoPool_2() { return jeg_shrinkFromAmmoPool_2; }

    public static Method getJeg_gun_getGeneral()    { return jeg_gun_getGeneral; }
    public static Method getJeg_gun_getReloads()    { return jeg_gun_getReloads; }
    public static Method getJeg_gun_getSounds()     { return jeg_gun_getSounds; }
    public static Method getJeg_gun_getProjectile() { return jeg_gun_getProjectile; }

    public static Method getJeg_general_getRate()          { return jeg_general_getRate; }
    public static Method getJeg_reloads_getReloadTimer()   { return jeg_reloads_getReloadTimer; }
    public static Method getJeg_reloads_getMaxAmmo()       { return jeg_reloads_getMaxAmmo; }
    public static Method getJeg_sounds_getFire()           { return jeg_sounds_getFire; }
    public static Method getJeg_projectile_getSpeed()      { return jeg_projectile_getSpeed; }
    public static Method getJeg_projectile_isGravity()     { return jeg_projectile_isGravity; }
    public static Method getJeg_gunEventBus_ejectCasing()  { return jeg_gunEventBus_ejectCasing; }

    // Generic helpers for inventory reflection used by compat classes
    public static Object tryGetInventoryObject(net.minecraft.world.entity.PathfinderMob mob) {
        if (mob == null) return null;
        try {
            Method m = mob.getClass().getMethod("getInventory");
            return m.invoke(mob);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object tryExtractItemsObjectFromInventory(Object inv) {
        if (inv == null) return null;
        try {
            Field f = inv.getClass().getField("items");
            return f.get(inv);
        } catch (Throwable t) {
            try {
                Method getItems = inv.getClass().getMethod("getItems");
                return getItems.invoke(inv);
            } catch (Throwable tt) {
                return null;
            }
        }
    }

    public static void tryWriteBackInventoryItem(Object inv, int index, net.minecraft.world.item.ItemStack stack) {
        if (inv == null) return;
        try {
            Method setItem = inv.getClass().getMethod("setItem", int.class, net.minecraft.world.item.ItemStack.class);
            setItem.invoke(inv, index, stack);
            return;
        } catch (Throwable ignored) {}
        try {
            Method setStack = inv.getClass().getMethod("setStack", int.class, net.minecraft.world.item.ItemStack.class);
            setStack.invoke(inv, index, stack);
        } catch (Throwable ignored) {}
    }

    // Small utility: find method in a class (used earlier)
    public static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        if (clazz == null) return null;
        try { return clazz.getMethod(name, params); } catch (Throwable t) { return null; }
    }
}