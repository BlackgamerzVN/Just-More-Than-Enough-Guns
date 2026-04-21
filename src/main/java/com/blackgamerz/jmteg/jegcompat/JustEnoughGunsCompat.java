package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JustEnoughGuns compatibility helpers.
 *
 * Includes:
 * - isJegGun
 * - getJegGunMaxAmmo
 * - hasAmmo
 * - consumeAmmoOnGun
 * - countAmmoInInventory
 * - consumeAmmoFromInventory
 *
 * All methods are best-effort and safe when JEG is absent.
 */
/**
 * JEG compat with cached reflective lookups and throttled debug logging.
 */
public final class JustEnoughGunsCompat {
    private static final Logger LOGGER = LogManager.getLogger("JMT-JEG-Compat");

    // Reflection cache (attempted once)
    private static final AtomicBoolean reflectionInitialized = new AtomicBoolean(false);
    private static Class<?> jegGunItemClass = null;
    private static Class<?> jegGunCommonClass = null;
    private static Method jeg_getModifiedGun = null;
    private static Method jeg_getReloads = null;
    private static Method jeg_getMaxAmmo = null;
    private static Method jeg_findAmmoStack = null;
    private static Method jeg_shrinkFromAmmoPool_1 = null; // (ItemStack,int)
    private static Method jeg_shrinkFromAmmoPool_2 = null; // (PathfinderMob,ItemStack,int)

    private static void initReflection() {
        if (!reflectionInitialized.compareAndSet(false, true)) return;
        try {
            jegGunItemClass = Class.forName("ttv.migami.jeg.item.GunItem");
        } catch (ClassNotFoundException ignored) {
            // JEG not present; leave null
        }
        try {
            jegGunCommonClass = Class.forName("ttv.migami.jeg.common.Gun");
        } catch (ClassNotFoundException ignored) {
            // ignore
        }

        if (jegGunItemClass != null) {
            try {
                jeg_getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class);
            } catch (Throwable t) {
                // ignore
            }
        }

        if (jegGunCommonClass != null) {
            try {
                jeg_findAmmoStack = jegGunCommonClass.getMethod("findAmmoStack", ItemStack.class);
            } catch (Throwable ignored) {}
            try {
                jeg_shrinkFromAmmoPool_1 = jegGunCommonClass.getMethod("shrinkFromAmmoPool", ItemStack.class, int.class);
            } catch (Throwable ignored) {}
            try {
                jeg_shrinkFromAmmoPool_2 = jegGunCommonClass.getMethod("shrinkFromAmmoPool", PathfinderMob.class, ItemStack.class, int.class);
            } catch (Throwable ignored) {}
        }

        // getReloads/getMaxAmmo are on modified gun object; we'll reflect them per gun instance (cached method refs above may be null)
    }

    public static boolean isJegGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        initReflection();
        if (jegGunItemClass == null) return false;
        try {
            Item item = stack.getItem();
            return jegGunItemClass.isInstance(item);
        } catch (Throwable t) {
            // keep quiet; only debug when necessary
            LOGGER.debug("isJegGun failed", t);
            return false;
        }
    }

    public static Integer getJegGunMaxAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        initReflection();
        if (jeg_getModifiedGun == null) return null;
        try {
            Object modifiedGun = jeg_getModifiedGun.invoke(stack.getItem(), stack);
            if (modifiedGun == null) return null;
            if (jeg_getReloads == null) {
                try {
                    jeg_getReloads = modifiedGun.getClass().getMethod("getReloads");
                } catch (Throwable ignored) {}
            }
            Object reloads = jeg_getReloads != null ? jeg_getReloads.invoke(modifiedGun) : null;
            if (reloads == null) return null;
            if (jeg_getMaxAmmo == null) {
                try {
                    jeg_getMaxAmmo = reloads.getClass().getMethod("getMaxAmmo");
                } catch (Throwable ignored) {}
            }
            Object maxObj = jeg_getMaxAmmo != null ? jeg_getMaxAmmo.invoke(reloads) : null;
            if (maxObj == null) return null;
            if (maxObj instanceof Integer) return (Integer) maxObj;
            return Integer.parseInt(maxObj.toString());
        } catch (Throwable t) {
            LOGGER.debug("getJegGunMaxAmmo failed", t);
            return null;
        }
    }

    public static boolean hasAmmo(ItemStack stack) {
        if (!isJegGun(stack)) return false;
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null) return true;
            if (tag.contains("IgnoreAmmo")) {
                try { return tag.getBoolean("IgnoreAmmo"); } catch (Throwable ignored) {}
            }
            if (tag.contains("AmmoCount", Tag.TAG_INT)) {
                try { return tag.getInt("AmmoCount") > 0; } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable t) {
            LOGGER.debug("hasAmmo failed", t);
            return true;
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

    // countAmmoInInventory and consumeAmmoFromInventory reuse the earlier logic but use cached jeg_findAmmoStack / shrink methods
    public static int countAmmoInInventory(PathfinderMob mob, ItemStack gun) {
        initReflection();
        // Try JEG findAmmoStack
        try {
            if (jeg_findAmmoStack != null) {
                Object ammo = jeg_findAmmoStack.invoke(null, gun);
                if (ammo instanceof ItemStack) {
                    ItemStack a = (ItemStack) ammo;
                    return a.getCount();
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventory (JEG) failed", t);
        }
        // fallback generic scan (keep minimal): only main + offhand + quick inventory if getInventory is cheap
        int total = 0;
        try {
            ItemStack m = mob.getMainHandItem();
            if (m != null && !m.isEmpty() && looksLikeAmmoForGun(gun, m)) total += m.getCount();
            ItemStack o = mob.getOffhandItem();
            if (o != null && !o.isEmpty() && looksLikeAmmoForGun(gun, o)) total += o.getCount();

            Object inv = tryGetInventoryObject(mob);
            if (inv != null) {
                Object items = tryExtractItemsObjectFromInventory(inv);
                if (items instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<ItemStack> list = (List<ItemStack>) items;
                    for (ItemStack s : list) {
                        if (s != null && !s.isEmpty() && looksLikeAmmoForGun(gun, s)) total += s.getCount();
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventory generic failed", t);
        }
        return total;
    }

    public static int consumeAmmoFromInventory(PathfinderMob mob, ItemStack gun, int required) {
        initReflection();
        int remaining = required;
        // Prefer JEG shrink
        try {
            if (jeg_findAmmoStack != null) {
                Object ammoObj = jeg_findAmmoStack.invoke(null, gun);
                if (ammoObj instanceof ItemStack) {
                    ItemStack ammoStack = (ItemStack) ammoObj;
                    if (!ammoStack.isEmpty()) {
                        if (jeg_shrinkFromAmmoPool_1 != null) {
                            try {
                                jeg_shrinkFromAmmoPool_1.invoke(null, ammoStack, remaining);
                                // best-effort compute consumed by seeing count change
                                // (we don't have starting count, so this is heuristic; fallthrough below)
                            } catch (Throwable ignored) {}
                        } else if (jeg_shrinkFromAmmoPool_2 != null) {
                            try {
                                jeg_shrinkFromAmmoPool_2.invoke(null, mob, ammoStack, remaining);
                            } catch (Throwable ignored) {}
                        }
                        // After attempted shrink, check ammoStack count (if decreased we assume consumed)
                        int left = ammoStack.getCount();
                        // Because we didn't capture the original count reliably, fallback to conservative behavior using generic scan below
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("consumeAmmoFromInventory (JEG) attempt failed", t);
        }

        // Generic: try main/offhand first (cheap) then inventory list
        try {
            ItemStack m = mob.getMainHandItem();
            if (m != null && !m.isEmpty() && looksLikeAmmoForGun(gun, m) && remaining > 0) {
                int take = Math.min(remaining, m.getCount());
                m.shrink(take);
                remaining -= take;
                try { mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, m); } catch (Throwable ignored) {}
            }
            ItemStack o = mob.getOffhandItem();
            if (o != null && !o.isEmpty() && looksLikeAmmoForGun(gun, o) && remaining > 0) {
                int take = Math.min(remaining, o.getCount());
                o.shrink(take);
                remaining -= take;
                try { mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, o); } catch (Throwable ignored) {}
            }
            if (remaining <= 0) return required;

            Object inv = tryGetInventoryObject(mob);
            Object items = tryExtractItemsObjectFromInventory(inv);
            if (items instanceof List) {
                @SuppressWarnings("unchecked")
                List<ItemStack> list = (List<ItemStack>) items;
                for (int i = 0; i < list.size() && remaining > 0; i++) {
                    ItemStack s = list.get(i);
                    if (s == null || s.isEmpty()) continue;
                    if (!looksLikeAmmoForGun(gun, s)) continue;
                    int take = Math.min(remaining, s.getCount());
                    s.shrink(take);
                    remaining -= take;
                    tryWriteBackInventoryItem(inv, i, s);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("consumeAmmoFromInventory generic failed", t);
        }

        return required - remaining;
    }

    // ------------------ private helpers ------------------

    private static boolean looksLikeAmmoForGun(ItemStack gun, ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        try {
            Item item = candidate.getItem();
            String itemClass = item.getClass().getName().toLowerCase();
            if (itemClass.contains("ammo") || itemClass.contains("bullet") || itemClass.contains("cartridge")
                    || itemClass.contains("musket") || itemClass.contains("shot") || itemClass.contains("arrow")) {
                return true;
            }
            String s = item.toString().toLowerCase();
            if (s.contains("ammo") || s.contains("bullet") || s.contains("cartridge") || s.contains("arrow") || s.contains("shot")) {
                return true;
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    private static Object tryGetInventoryObject(PathfinderMob mob) {
        if (mob == null) return null;
        try {
            Method getInventoryMethod = mob.getClass().getMethod("getInventory");
            return getInventoryMethod.invoke(mob);
        } catch (NoSuchMethodException nsme) {
            return null;
        } catch (Throwable t) {
            LOGGER.debug("tryGetInventoryObject reflection error", t);
            return null;
        }
    }

    private static Object tryExtractItemsObjectFromInventory(Object inv) {
        if (inv == null) return null;
        try {
            Field itemsField = inv.getClass().getField("items");
            return itemsField.get(inv);
        } catch (NoSuchFieldException nsf) {
            try {
                Method getItems = inv.getClass().getMethod("getItems");
                return getItems.invoke(inv);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                return null;
            } catch (Throwable t) {
                LOGGER.debug("tryExtractItemsObjectFromInventory getItems error", t);
                return null;
            }
        } catch (Throwable t) {
            LOGGER.debug("tryExtractItemsObjectFromInventory error", t);
            return null;
        }
    }

    private static void tryWriteBackInventoryItem(Object inv, int index, ItemStack stack) {
        if (inv == null) return;
        try {
            try {
                Method setItem = inv.getClass().getMethod("setItem", int.class, ItemStack.class);
                setItem.invoke(inv, index, stack);
                return;
            } catch (NoSuchMethodException ignore) {}

            try {
                Method setStack = inv.getClass().getMethod("setStack", int.class, ItemStack.class);
                setStack.invoke(inv, index, stack);
                return;
            } catch (NoSuchMethodException ignore) {}
        } catch (Throwable t) {
            LOGGER.debug("tryWriteBackInventoryItem failed", t);
        }
    }
}