package com.blackgamerz.jmteg.jegcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import com.blackgamerz.jmteg.util.AmmoUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.List;

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

    public static boolean hasAmmo(ItemStack stack) {
        if (!isJegGun(stack)) return false;
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null) return true; // unknown -> allow
            if (tag.contains("IgnoreAmmo")) {
                try { return tag.getBoolean("IgnoreAmmo"); } catch (Throwable ignored) {}
            }
            if (tag.contains("AmmoCount", Tag.TAG_INT)) {
                try { return tag.getInt("AmmoCount") > 0; } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable t) {
            LOGGER.debug("hasAmmo error", t);
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

    /**
     * Best-effort: count ammo items in mob inventory relevant to this gun.
     * Prefers fast checks (main/offhand) then small inventory scan.
     */
    public static int countAmmoInInventory(PathfinderMob mob, ItemStack gun) {
        if (mob == null || gun == null || gun.isEmpty()) return 0;
        int total = 0;
        try {
            ItemStack m = mob.getMainHandItem();
            if (m != null && !m.isEmpty() && AmmoUtils.matchesAmmo(gun, m)) total += m.getCount();
            ItemStack o = mob.getOffhandItem();
            if (o != null && !o.isEmpty() && AmmoUtils.matchesAmmo(gun, o)) total += o.getCount();

            Object inv = ReflectionCache.tryGetInventoryObject(mob);
            Object items = ReflectionCache.tryExtractItemsObjectFromInventory(inv);
            if (items instanceof List) {
                @SuppressWarnings("unchecked")
                List<ItemStack> list = (List<ItemStack>) items;
                for (ItemStack s : list) {
                    if (s != null && !s.isEmpty() && AmmoUtils.matchesAmmo(gun, s)) total += s.getCount();
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventory error", t);
        }
        return total;
    }

    /**
     * Attempt to consume required ammo from mob inventory. Returns consumed count.
     */
    public static int consumeAmmoFromInventory(PathfinderMob mob, ItemStack gun, int required) {
        if (mob == null || gun == null || gun.isEmpty() || required <= 0) return 0;
        int remaining = required;

        // Try JEG helper shrink if available (best-effort)
        try {
            Method findAmmo = ReflectionCache.getJeg_findAmmoStack();
            if (findAmmo != null) {
                Object ammoObj = findAmmo.invoke(null, gun);
                if (ammoObj instanceof ItemStack) {
                    ItemStack ammoStack = (ItemStack) ammoObj;
                    // If JEG offers shrink, try it via reflection cache
                    Method shrink1 = ReflectionCache.getJeg_shrinkFromAmmoPool_1();
                    Method shrink2 = ReflectionCache.getJeg_shrinkFromAmmoPool_2();
                    if (shrink1 != null) {
                        try { shrink1.invoke(null, ammoStack, remaining); } catch (Throwable ignored) {}
                    } else if (shrink2 != null) {
                        try { shrink2.invoke(null, mob, ammoStack, remaining); } catch (Throwable ignored) {}
                    }
                    // best-effort: use ammoStack.getCount in later checks if signaled by JEG
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("JEG-specific consume attempt failed", t);
        }

        // Generic: main/offhand then inventory list
        try {
            ItemStack m = mob.getMainHandItem();
            if (m != null && !m.isEmpty() && AmmoUtils.matchesAmmo(gun, m) && remaining > 0) {
                int take = Math.min(remaining, m.getCount());
                m.shrink(take);
                remaining -= take;
                try { mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, m); } catch (Throwable ignored) {}
            }
            ItemStack o = mob.getOffhandItem();
            if (o != null && !o.isEmpty() && AmmoUtils.matchesAmmo(gun, o) && remaining > 0) {
                int take = Math.min(remaining, o.getCount());
                o.shrink(take);
                remaining -= take;
                try { mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, o); } catch (Throwable ignored) {}
            }

            if (remaining <= 0) return required;

            Object inv = ReflectionCache.tryGetInventoryObject(mob);
            Object items = ReflectionCache.tryExtractItemsObjectFromInventory(inv);
            if (items instanceof List) {
                @SuppressWarnings("unchecked")
                List<net.minecraft.world.item.ItemStack> list = (List<net.minecraft.world.item.ItemStack>) items;
                for (int i = 0; i < list.size() && remaining > 0; i++) {
                    net.minecraft.world.item.ItemStack s = list.get(i);
                    if (s == null || s.isEmpty()) continue;
                    if (!AmmoUtils.matchesAmmo(gun, s)) continue;
                    int take = Math.min(remaining, s.getCount());
                    s.shrink(take);
                    remaining -= take;
                    ReflectionCache.tryWriteBackInventoryItem(inv, i, s);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("consumeAmmoFromInventory generic failed", t);
        }

        return required - remaining;
    }
}