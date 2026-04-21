package com.blackgamerz.jmteg.compat;

import com.blackgamerz.jmteg.jegcompat.JustEnoughGunsCompat;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Sanitizes ItemStacks on entities so JustEnoughGuns-style infinite-ammo markers are removed/clamped.
 * Writes back modified ItemStacks using setItemSlot when possible.
 * Schedules re-sanitization a few ticks later to handle mods that mutate items later.
 */
public final class EntityWeaponSanitizer {
    private static final Logger LOGGER = LogManager.getLogger("JMT-EntityWeaponSanitizer");
    private static final int RESCHEDULE_TICKS = 3;
    private static final java.util.Set<java.util.UUID> rescheduleSet = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private EntityWeaponSanitizer() {}

    public static void sanitize(PathfinderMob mob) {

    // when scheduling:
        UUID id = mob.getUUID();
        if (rescheduleSet.add(id)) {
            DeferredTaskScheduler.schedule(() -> {
                try {
                    // Do sanitize only if mob still exists / alive
                    if (mob != null && mob.isAlive()) {
                        sanitize(mob);
                    }
                } catch (Throwable ex) {
                    LOGGER.debug("Rescheduled sanitize failed for {}: {}", id, ex.toString());
                } finally {
                    rescheduleSet.remove(id);
                }
            }, RESCHEDULE_TICKS);
        }
    }

    private static boolean sanitizeStackAndReturnIfChanged(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            CompoundTag tag = stack.getTag();
            boolean changed = false;
            if (tag == null) {
                tag = stack.getOrCreateTag();
            }

            // Force IgnoreAmmo to false (explicit)
            try {
                boolean before = tag.getBoolean("IgnoreAmmo");
                if (before) {
                    tag.putBoolean("IgnoreAmmo", false);
                    changed = true;
                    LOGGER.debug("Cleared IgnoreAmmo on {}", stack);
                }
            } catch (Throwable t) {
                // putBoolean might not exist in some mapping; ignore
            }

            // Remove Infinity-like enchantments
            try {
                if (tag.contains("Enchantments", Tag.TAG_LIST)) {
                    ListTag ench = tag.getList("Enchantments", Tag.TAG_COMPOUND);
                    if (ench != null && !ench.isEmpty()) {
                        ListTag filtered = new ListTag();
                        for (int i = 0; i < ench.size(); i++) {
                            CompoundTag e = ench.getCompound(i);
                            String id = e.contains("id") ? e.getString("id") : "";
                            if (id != null && id.toLowerCase().contains("infinity")) {
                                changed = true;
                                LOGGER.debug("Removed Infinity enchant {} from {}", id, stack);
                                continue;
                            }
                            filtered.add(e.copy());
                        }
                        tag.put("Enchantments", filtered);
                    }
                }
            } catch (Throwable t) {
                // ignore
            }

            // Clamp AmmoCount to discovered max or a conservative cap
            try {
                if (tag.contains("AmmoCount")) {
                    Integer max = JustEnoughGunsCompat.getJegGunMaxAmmo(stack);
                    int cur = tag.getInt("AmmoCount");
                    if (max != null && max >= 0) {
                        if (cur > max) {
                            tag.putInt("AmmoCount", max);
                            changed = true;
                            LOGGER.debug("Clamped AmmoCount {} -> {} on {}", cur, max, stack);
                        }
                    } else {
                        if (cur > 1000) {
                            tag.putInt("AmmoCount", 10);
                            changed = true;
                            LOGGER.debug("Clamped huge AmmoCount {} -> 10 on {}", cur, stack);
                        }
                    }
                }
            } catch (Throwable t) {
                // ignore
            }

            return changed;
        } catch (Throwable t) {
            LOGGER.debug("sanitizeStackAndReturnIfChanged threw", t);
            return false;
        }
    }

    /**
     * Attempt to sanitize items in a container and write back if we can detect setters.
     * itemsObj is expected to be a List/Iterable/array of ItemStack. inv is the inventory object (optional).
     */
    private static boolean sanitizeIterableItemsAndWriteBack(Object itemsObj, Object inv) {
        if (itemsObj == null) return false;
        boolean changed = false;
        try {
            if (itemsObj instanceof Iterable) {
                int idx = 0;
                for (Object o : (Iterable<?>) itemsObj) {
                    if (o instanceof ItemStack) {
                        ItemStack s = (ItemStack) o;
                        boolean c = sanitizeStackAndReturnIfChanged(s);
                        if (c) {
                            changed = true;
                            // try to write back if inv has a setItem method or setItem(index, stack)
                            tryWriteBackItem(inv, idx, s);
                        }
                    }
                    idx++;
                }
            } else if (itemsObj instanceof ItemStack[]) {
                ItemStack[] arr = (ItemStack[]) itemsObj;
                for (int i = 0; i < arr.length; i++) {
                    ItemStack s = arr[i];
                    boolean c = sanitizeStackAndReturnIfChanged(s);
                    if (c) {
                        changed = true;
                        tryWriteBackItem(inv, i, s);
                    }
                }
            } else if (itemsObj instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) itemsObj;
                for (int i = 0; i < list.size(); i++) {
                    Object o = list.get(i);
                    if (o instanceof ItemStack) {
                        ItemStack s = (ItemStack) o;
                        boolean c = sanitizeStackAndReturnIfChanged(s);
                        if (c) {
                            changed = true;
                            tryWriteBackItem(inv, i, s);
                        }
                    }
                }
            } else {
                // try reflection with size/get
                try {
                    Method size = itemsObj.getClass().getMethod("size");
                    Method get = itemsObj.getClass().getMethod("get", int.class);
                    int sizeV = (int) size.invoke(itemsObj);
                    for (int i = 0; i < sizeV; i++) {
                        Object o = get.invoke(itemsObj, i);
                        if (o instanceof ItemStack) {
                            ItemStack s = (ItemStack) o;
                            boolean c = sanitizeStackAndReturnIfChanged(s);
                            if (c) {
                                changed = true;
                                tryWriteBackItem(inv, i, s);
                            }
                        }
                    }
                } catch (NoSuchMethodException nsme) {
                    LOGGER.debug("Unknown container type for inventory items: {}", itemsObj.getClass().getName());
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("sanitizeIterableItemsAndWriteBack threw", t);
        }
        return changed;
    }

    private static void tryWriteBackItem(Object inv, int index, ItemStack stack) {
        if (inv == null) return;
        try {
            // try setItem(int, ItemStack)
            try {
                Method setItem = inv.getClass().getMethod("setItem", int.class, ItemStack.class);
                setItem.invoke(inv, index, stack);
                return;
            } catch (NoSuchMethodException ignore) {}

            // try setStack or set
            try {
                Method setStack = inv.getClass().getMethod("setStack", int.class, ItemStack.class);
                setStack.invoke(inv, index, stack);
                return;
            } catch (NoSuchMethodException ignore) {}

            // fallback: if no setter detected, do nothing (we already modified the ItemStack instance in-place)
        } catch (Throwable t) {
            LOGGER.debug("tryWriteBackItem failed", t);
        }
    }
}