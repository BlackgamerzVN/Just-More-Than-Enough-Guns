package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;


import net.minecraft.world.entity.LivingEntity;

/**
 * JEG compatibility helpers (cached reflection via ReflectionCache).
 * Best-effort, safe if JEG absent.
 */
public final class JustEnoughGunsCompat {
    private static final Logger LOGGER = LogManager.getLogger("JMT-JEG-Compat");

    private JustEnoughGunsCompat() {}

    public static boolean isJegGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            Class<?> gunItemClass = ReflectionCache.getJegGunItemClass();
            if (gunItemClass == null) return false;
            Item item = stack.getItem();
            return gunItemClass.isInstance(item);
        } catch (Throwable t) {
            LOGGER.debug("isJegGun reflection error", t);
            return false;
        }
    }

    public static Integer getJegGunMaxAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            Method getModifiedGun = ReflectionCache.getJeg_getModifiedGun();
            if (getModifiedGun == null) return null;
            Object modifiedGun = getModifiedGun.invoke(stack.getItem(), stack);
            if (modifiedGun == null) return null;

            Method getReloads = ReflectionCache.findMethod(modifiedGun.getClass(), "getReloads");
            if (getReloads == null) return null;
            Object reloads = getReloads.invoke(modifiedGun);
            if (reloads == null) return null;

            Method getMaxAmmo = ReflectionCache.findMethod(reloads.getClass(), "getMaxAmmo");
            if (getMaxAmmo == null) return null;
            Object maxObj = getMaxAmmo.invoke(reloads);
            if (maxObj == null) return null;
            if (maxObj instanceof Integer) return (Integer) maxObj;
            return Integer.parseInt(maxObj.toString());
        } catch (Throwable t) {
            LOGGER.debug("getJegGunMaxAmmo failed", t);
            return null;
        }
    }

    public static void consumeAmmoOnGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        try {
            CompoundTag tag = stack.getOrCreateTag();
            if (tag.contains("AmmoCount")) {
                int cur = tag.getInt("AmmoCount");
                if (cur > 0) tag.putInt("AmmoCount", cur - 1);
            }
        } catch (Throwable t) {
            LOGGER.debug("consumeAmmoOnGun failed", t);
        }
    }

    public static boolean hasJegGunAmmo(LivingEntity entity, ItemStack gunStack) {
        try {
            // Get the modified gun object via reflection
            Object gun = gunStack.getItem().getClass()
                    .getMethod("getModifiedGun", ItemStack.class)
                    .invoke(gunStack.getItem(), gunStack);

            if (gun != null) {
                // Get projectile
                Object projectile = gun.getClass().getMethod("getProjectile").invoke(gun);
                // Get ammo item
                Object ammoItem = projectile.getClass().getMethod("getItem").invoke(projectile);

                // Try Recruit inventory (reflection for cross-mod support)
                try {
                    Class<?> recruitClass = Class.forName("com.talhanation.recruits.entities.RecruitEntity");
                    if (recruitClass.isInstance(entity)) {
                        Object inventory = recruitClass.getMethod("getInventory").invoke(entity);
                        int size = (int) inventory.getClass().getMethod("getContainerSize").invoke(inventory);
                        for (int i = 0; i < size; i++) {
                            ItemStack stack = (ItemStack) inventory.getClass()
                                    .getMethod("getItem", int.class).invoke(inventory, i);
                            if (!stack.isEmpty() && stack.getItem() == ammoItem) {
                                return true;
                            }
                        }
                        return false;
                    }
                } catch (ClassNotFoundException ignored) {
                }
                // Fallback to hands
                ItemStack mainhand = entity.getMainHandItem();
                if (!mainhand.isEmpty() && mainhand.getItem() == ammoItem) return true;
                ItemStack offhand = entity.getOffhandItem();
                if (!offhand.isEmpty() && offhand.getItem() == ammoItem) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

}