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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sanitizes ItemStacks on entities (clear IgnoreAmmo/clamp AmmoCount/remove Infinity enchants).
 * Uses a scheduled-reschedule guard to avoid queuing duplicates for the same mob.
 */
public final class EntityWeaponSanitizer {
    private static final Logger LOGGER = LogManager.getLogger("JMT-EntityWeaponSanitizer");
    private static final int RESCHEDULE_TICKS = 3;
    private static final Set<UUID> rescheduleSet = ConcurrentHashMap.newKeySet();

    private EntityWeaponSanitizer() {}

    public static void sanitize(PathfinderMob mob) {
        if (mob == null) return;
        try {
            // sanitize main and writeback
            ItemStack main = mob.getMainHandItem();
            boolean mainChanged = sanitizeStackAndReturnIfChanged(main);
            if (mainChanged) mob.setItemSlot(EquipmentSlot.MAINHAND, main);
        } catch (Throwable t) {
            LOGGER.debug("sanitize main error", t);
        }
        try {
            ItemStack off = mob.getOffhandItem();
            boolean offChanged = sanitizeStackAndReturnIfChanged(off);
            if (offChanged) mob.setItemSlot(EquipmentSlot.OFFHAND, off);
        } catch (Throwable t) {
            LOGGER.debug("sanitize offhand error", t);
        }

        boolean sanitizedInventory = false;
        try {
            Object inv = ReflectionCache.tryGetInventoryObject(mob);
            Object itemsObj = ReflectionCache.tryExtractItemsObjectFromInventory(inv);
            if (itemsObj != null) {
                sanitizedInventory = sanitizeIterableItemsAndWriteBack(itemsObj, inv);
            }
        } catch (Throwable t) {
            LOGGER.debug("inventory sanitize error", t);
        }

        if (!sanitizedInventory) {
            try {
                Iterable<ItemStack> armor = ((LivingEntity) mob).getArmorSlots();
                if (armor != null) {
                    for (ItemStack s : armor) sanitizeStackAndReturnIfChanged(s);
                }
            } catch (Throwable t) {
                LOGGER.debug("armor sanitize error", t);
            }
        }

        // schedule a single reschedule per mob
        try {
            UUID id = mob.getUUID();
            if (rescheduleSet.add(id)) {
                DeferredTaskScheduler.schedule(() -> {
                    try {
                        if (mob.isAlive()) sanitize(mob);
                    } catch (Throwable ex) {
                        LOGGER.debug("Rescheduled sanitize failed for {}: {}", id, ex.toString());
                    } finally {
                        rescheduleSet.remove(id);
                    }
                }, RESCHEDULE_TICKS);
            }
        } catch (Throwable t) {
            // ignore scheduling errors
        }
    }

    private static boolean sanitizeStackAndReturnIfChanged(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            CompoundTag tag = stack.getOrCreateTag();
            boolean changed = false;

            // Force IgnoreAmmo false
            try {
                if (tag.getBoolean("IgnoreAmmo")) {
                    tag.putBoolean("IgnoreAmmo", false);
                    changed = true;
                }
            } catch (Throwable ignored) {}

            // Remove infinity-like enchantments
            try {
                if (tag.contains("Enchantments", Tag.TAG_LIST)) {
                    ListTag ench = tag.getList("Enchantments", Tag.TAG_COMPOUND);
                    if (!ench.isEmpty()) {
                        ListTag filtered = new ListTag();
                        for (int i = 0; i < ench.size(); i++) {
                            CompoundTag e = ench.getCompound(i);
                            String id = e.contains("id") ? e.getString("id") : "";
                            if (id != null && id.toLowerCase().contains("infinity")) {
                                changed = true;
                                continue;
                            }
                            filtered.add(e.copy());
                        }
                        tag.put("Enchantments", filtered);
                    }
                }
            } catch (Throwable ignored) {}

            // Clamp AmmoCount
            try {
                if (tag.contains("AmmoCount")) {
                    Integer max = JustEnoughGunsCompat.getJegGunMaxAmmo(stack);
                    int cur = tag.getInt("AmmoCount");
                    if (max != null && max >= 0 && cur > max) {
                        tag.putInt("AmmoCount", max);
                        changed = true;
                    } else if (cur > 1000) {
                        tag.putInt("AmmoCount", 10);
                        changed = true;
                    }
                }
            } catch (Throwable ignored) {}

            return changed;
        } catch (Throwable t) {
            LOGGER.debug("sanitizeStackAndReturnIfChanged failed", t);
            return false;
        }
    }

    private static boolean sanitizeIterableItemsAndWriteBack(Object itemsObj, Object inv) {
        if (itemsObj == null) return false;
        boolean changed = false;
        try {
            if (itemsObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<net.minecraft.world.item.ItemStack> list = (java.util.List<net.minecraft.world.item.ItemStack>) itemsObj;
                for (int i = 0; i < list.size(); i++) {
                    net.minecraft.world.item.ItemStack s = list.get(i);
                    if (s == null || s.isEmpty()) continue;
                    boolean c = sanitizeStackAndReturnIfChanged(s);
                    if (c) {
                        changed = true;
                        ReflectionCache.tryWriteBackInventoryItem(inv, i, s);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("sanitizeIterableItemsAndWriteBack failed", t);
        }
        return changed;
    }
}