package com.blackgamerz.jmteg.jegcompat.core;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection-based reading/writing of ammo items in a Recruits-compatible entity's
 * inventory (via {@code getInventory()}, the {@code inventory} field, a
 * {@code RecruitSimpleContainer}, or the Forge item-handler capability as fallbacks).
 * Extracted from {@link MobAiInjector} so inventory access has a single, focused home.
 */
public final class RecruitInventoryAmmoAccessor {

    private static final Logger LOGGER = LogManager.getLogger("jmteg");

    private RecruitInventoryAmmoAccessor() {}

    /** Dump inventory contents (index -> item id -> count) for debugging. */
    public static void dumpInventoryContents(Entity entity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Inventory dump for ").append(entity.getEncodeId()).append(" / ").append(entity.getUUID()).append(":");
            Method getInventory = ReflectionCache.findMethod(entity.getClass(), ReflectionCache.METHOD_GET_INVENTORY);
            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                        sb.append("\n  slot ").append(i).append(": ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                    }
                    LOGGER.info(sb.toString());
                    return;
                } else {
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 0; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                                sb.append("\n  inv[").append(i).append("]: ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                            }
                            LOGGER.info(sb.toString());
                            return;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            }
            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 0; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                                sb.append("\n  inventory[").append(i).append("]: ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                            }
                            LOGGER.info(sb.toString());
                            return;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            // Capability fallback
            try {
                entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
                    StringBuilder sbb = new StringBuilder(sb);
                    IItemHandler h = handler;
                    for (int i = 0; i < h.getSlots(); i++) {
                        ItemStack st = h.getStackInSlot(i);
                        ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                        sbb.append("\n  capability.slot[").append(i).append("]: ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                    }
                    LOGGER.info(sbb.toString());
                });
            } catch (Throwable ignored) {}
            LOGGER.info(sb.append("\n  (no accessible inventory found)").toString());
        } catch (Throwable t) {
            LOGGER.warn("Failed to dump inventory for entity {}", entity.getUUID(), t);
        }
    }

    /** Fuzzy count: count items whose id string or path contains the poolId path or namespace. */
    public static int countAmmoInInventoryFuzzy(Entity entity, ResourceLocation poolId) {
        int total = 0;
        try {
            Method getInventory = ReflectionCache.findMethod(entity.getClass(), ReflectionCache.METHOD_GET_INVENTORY);
            String poolPath = poolId == null ? "" : poolId.getPath();
            String poolNs = poolId == null ? "" : poolId.getNamespace();
            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    int start = Math.min(6, container.getContainerSize());
                    for (int i = start; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                            if (key != null) {
                                String path = key.getPath();
                                String id = key.toString();
                                if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                    total += st.getCount();
                                }
                            }
                        }
                    }
                    return total;
                } else {
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 6; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (key != null) {
                                        String path = key.getPath();
                                        String id = key.toString();
                                        if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                            total += st.getCount();
                                        }
                                    }
                                }
                            }
                            return total;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            }

            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    if (ReflectionCache.RECRUIT_SIMPLE_CONTAINER_CLASS_NAME.equals(inv.getClass().getName())) {
                        try {
                            Method sizeM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_CONTAINER_SIZE);
                            Method getItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_ITEM, int.class);
                            int size = (Integer) sizeM.invoke(inv);
                            for (int i = 6; i < size; i++) {
                                Object stObj = getItemM.invoke(inv, i);
                                if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (key != null) {
                                        String path = key.getPath();
                                        String id = key.toString();
                                        if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                            total += st.getCount();
                                        }
                                    }
                                }
                            }
                            return total;
                        } catch (Throwable ignored) {}
                    }
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 6; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (key != null) {
                                        String path = key.getPath();
                                        String id = key.toString();
                                        if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                            total += st.getCount();
                                        }
                                    }
                                }
                            }
                            return total;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // capability fallback: skip first 6 slots
            try {
                var optional = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
                if (optional.isPresent()) {
                    IItemHandler h = optional.orElse(null);
                    if (h != null) {
                        int start = Math.min(6, h.getSlots());
                        for (int i = start; i < h.getSlots(); i++) {
                            ItemStack st = h.getStackInSlot(i);
                            if (!st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (key != null) {
                                    String path = key.getPath();
                                    String id = key.toString();
                                    if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                        total += st.getCount();
                                    }
                                }
                            }
                        }
                        return total;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventoryFuzzy reflection failed", t);
        }
        return total;
    }

    public static int countAmmoInInventory(Entity entity, ResourceLocation ammoId) {
        try {
            // 1) try getInventory()
            Method getInventory = ReflectionCache.findMethod(entity.getClass(), ReflectionCache.METHOD_GET_INVENTORY);
            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    int total = 0;
                    int start = Math.min(6, container.getContainerSize());
                    for (int i = start; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                            if (ammoId.equals(key)) total += st.getCount();
                        }
                    }
                    return total;
                }
                // handle RecruitSimpleContainer specifically if it isn't a Container (defensive)
                if (inv != null && ReflectionCache.RECRUIT_SIMPLE_CONTAINER_CLASS_NAME.equals(inv.getClass().getName())) {
                    try {
                        Method sizeM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_CONTAINER_SIZE);
                        Method getItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_ITEM, int.class);
                        int size = (Integer) sizeM.invoke(inv);
                        int total = 0;
                        for (int i = 6; i < size; i++) { // only general inventory
                            Object stObj = getItemM.invoke(inv, i);
                            if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) total += st.getCount();
                            }
                        }
                        return total;
                    } catch (Throwable ignored) {}
                }
                try {
                    Field itemsField = inv.getClass().getDeclaredField("items");
                    itemsField.setAccessible(true);
                    Object listObj = itemsField.get(inv);
                    if (listObj instanceof List<?> rawList) {
                        @SuppressWarnings("unchecked")
                        List<ItemStack> list = (List<ItemStack>) rawList;
                        int total = 0;
                        for (int i = 6; i < list.size(); i++) { // skip 0..5
                            ItemStack st = list.get(i);
                            if (st != null && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) total += st.getCount();
                            }
                        }
                        return total;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }

            // 2) reflect 'inventory' field
            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    if (ReflectionCache.RECRUIT_SIMPLE_CONTAINER_CLASS_NAME.equals(inv.getClass().getName())) {
                        try {
                            Method sizeM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_CONTAINER_SIZE);
                            Method getItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_ITEM, int.class);
                            int size = (Integer) sizeM.invoke(inv);
                            int total = 0;
                            for (int i = 6; i < size; i++) {
                                Object stObj = getItemM.invoke(inv, i);
                                if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) total += st.getCount();
                                }
                            }
                            return total;
                        } catch (Throwable ignored) {}
                    }
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            int total = 0;
                            for (int i = 6; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) total += st.getCount();
                                }
                            }
                            return total;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // 3) capability fallback: IItemHandler
            try {
                var optional = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
                if (optional.isPresent()) {
                    IItemHandler h = optional.orElse(null);
                    if (h != null) {
                        int total = 0;
                        int start = Math.min(6, h.getSlots()); // skip 0..5 if present
                        for (int i = start; i < h.getSlots(); i++) {
                            ItemStack st = h.getStackInSlot(i);
                            if (!st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) total += st.getCount();
                            }
                        }
                        return total;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventory reflection failed", t);
        }
        return 0;
    }

    public static int removeAmmoFromInventory(Entity entity, ResourceLocation ammoId, int amount) {
        if (amount <= 0) return 0;
        int removedTotal = 0;
        try {
            // 1) try getInventory()
            Method getInventory = ReflectionCache.findMethod(entity.getClass(), ReflectionCache.METHOD_GET_INVENTORY);

            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    int start = Math.min(6, container.getContainerSize());
                    for (int i = start; i < container.getContainerSize() && amount > 0; i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                            if (ammoId.equals(key)) {
                                int take = Math.min(amount, st.getCount());
                                container.removeItem(i, take);
                                removedTotal += take;
                                amount -= take;
                            }
                        }
                    }
                    return removedTotal;
                }
                // RecruitSimpleContainer specific
                if (inv != null && ReflectionCache.RECRUIT_SIMPLE_CONTAINER_CLASS_NAME.equals(inv.getClass().getName())) {
                    try {
                        Method sizeM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_CONTAINER_SIZE);
                        Method getItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_ITEM, int.class);
                        Method setItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_ITEM, int.class, ItemStack.class);
                        int size = (Integer) sizeM.invoke(inv);
                        for (int i = 6; i < size && amount > 0; i++) {
                            Object stObj = getItemM.invoke(inv, i);
                            if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) {
                                    int take = Math.min(amount, st.getCount());
                                    st.shrink(take);
                                    removedTotal += take;
                                    amount -= take;
                                    if (st.getCount() <= 0) setItemM.invoke(inv, i, ItemStack.EMPTY);
                                }
                            }
                        }
                        try { Method setChanged = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_CHANGED); if (setChanged != null) setChanged.invoke(inv); } catch (Throwable ignored) {}
                        return removedTotal;
                    } catch (Throwable ignored) {}
                }

                try {
                    Field itemsField = inv.getClass().getDeclaredField("items");
                    itemsField.setAccessible(true);
                    Object listObj = itemsField.get(inv);
                    if (listObj instanceof List<?> rawList) {
                        @SuppressWarnings("unchecked")
                        List<ItemStack> list = (List<ItemStack>) rawList;
                        for (int i = 6; i < list.size() && amount > 0; i++) {
                            ItemStack st = list.get(i);
                            if (st != null && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) {
                                    int take = Math.min(amount, st.getCount());
                                    st.shrink(take);
                                    removedTotal += take;
                                    amount -= take;
                                    if (st.getCount() <= 0) list.set(i, ItemStack.EMPTY);
                                }
                            }
                        }
                        try {
                            Method setChanged = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_CHANGED);
                            if (setChanged != null) setChanged.invoke(inv);
                        } catch (Throwable ignored) {}
                        return removedTotal;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }

            // 2) reflect 'inventory' field
            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    if (ReflectionCache.RECRUIT_SIMPLE_CONTAINER_CLASS_NAME.equals(inv.getClass().getName())) {
                        try {
                            Method sizeM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_CONTAINER_SIZE);
                            Method getItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_ITEM, int.class);
                            Method setItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_ITEM, int.class, ItemStack.class);
                            int size = (Integer) sizeM.invoke(inv);
                            for (int i = 6; i < size && amount > 0; i++) {
                                Object stObj = getItemM.invoke(inv, i);
                                if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) {
                                        int take = Math.min(amount, st.getCount());
                                        st.shrink(take);
                                        removedTotal += take;
                                        amount -= take;
                                        if (st.getCount() <= 0) setItemM.invoke(inv, i, ItemStack.EMPTY);
                                    }
                                }
                            }
                            try {
                                Method setChanged = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_CHANGED);
                                if (setChanged != null) setChanged.invoke(inv);
                            } catch (Throwable ignored) {}
                            return removedTotal;
                        } catch (Throwable ignored) {}
                    }

                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList2) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList2;
                            for (int i = 6; i < list.size() && amount > 0; i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) {
                                        int take = Math.min(amount, st.getCount());
                                        st.shrink(take);
                                        removedTotal += take;
                                        amount -= take;
                                        if (st.getCount() <= 0) list.set(i, ItemStack.EMPTY);
                                    }
                                }
                            }
                            try {
                                Method setChanged = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_CHANGED);
                                if (setChanged != null) setChanged.invoke(inv);
                            } catch (Throwable ignored) {}
                            return removedTotal;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // 3) capability fallback: IItemHandler (skip first 6 slots)
            try {
                var optional = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
                if (optional.isPresent()) {
                    IItemHandler h = optional.orElse(null);
                    if (h != null) {
                        int start = Math.min(6, h.getSlots());
                        for (int i = start; i < h.getSlots() && amount > 0; i++) {
                            ItemStack st = h.getStackInSlot(i);
                            if (!st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) {
                                    int toTake = Math.min(amount, st.getCount());
                                    ItemStack extracted = h.extractItem(i, toTake, false);
                                    if (!extracted.isEmpty()) {
                                        removedTotal += extracted.getCount();
                                        amount -= extracted.getCount();
                                    }
                                }
                            }
                        }
                        return removedTotal;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOGGER.debug("removeAmmoFromInventory reflection failed", t);
        }

        return removedTotal;
    }
}
