package com.blackgamerz.jmteg.compat;

import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Centralized reflection cache for JEG and inventory helper lookups.
 * Other classes call these getters instead of repeatedly doing Class.forName/getMethod.
 */
public final class ReflectionCache {
    private static final Logger LOGGER = LogManager.getLogger("JMT-ReflectionCache");

    private ReflectionCache() {}

    private static volatile Class<?> jegGunItemClass;
    private static volatile Class<?> jegCommonGunClass;
    private static volatile Method jeg_getModifiedGun;
    private static volatile Method jeg_findAmmoStack;
    private static volatile Method jeg_shrinkFromAmmoPool_1;
    private static volatile Method jeg_shrinkFromAmmoPool_2;

    static {
        try {
            jegGunItemClass = Class.forName("ttv.migami.jeg.item.GunItem");
        } catch (Throwable ignored) {}
        try {
            jegCommonGunClass = Class.forName("ttv.migami.jeg.common.Gun");
        } catch (Throwable ignored) {}

        if (jegGunItemClass != null) {
            try { jeg_getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class); } catch (Throwable ignored) {}
        }
        if (jegCommonGunClass != null) {
            try { jeg_findAmmoStack = jegCommonGunClass.getMethod("findAmmoStack", ItemStack.class); } catch (Throwable ignored) {}
            try { jeg_shrinkFromAmmoPool_1 = jegCommonGunClass.getMethod("shrinkFromAmmoPool", net.minecraft.world.item.ItemStack.class, int.class); } catch (Throwable ignored) {}
            try { jeg_shrinkFromAmmoPool_2 = jegCommonGunClass.getMethod("shrinkFromAmmoPool", net.minecraft.world.entity.PathfinderMob.class, net.minecraft.world.item.ItemStack.class, int.class); } catch (Throwable ignored) {}
        }
    }

    public static Class<?> getJegGunItemClass() { return jegGunItemClass; }
    public static Method getJeg_getModifiedGun() { return jeg_getModifiedGun; }
    public static Method getJeg_findAmmoStack() { return jeg_findAmmoStack; }
    public static Method getJeg_shrinkFromAmmoPool_1() { return jeg_shrinkFromAmmoPool_1; }
    public static Method getJeg_shrinkFromAmmoPool_2() { return jeg_shrinkFromAmmoPool_2; }

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