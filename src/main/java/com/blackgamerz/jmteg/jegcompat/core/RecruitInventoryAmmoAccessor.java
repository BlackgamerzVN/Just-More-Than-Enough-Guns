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
 *
 * <p>All four public entry points ({@link #dumpInventoryContents}, {@link #countAmmoInInventory},
 * {@link #countAmmoInInventoryFuzzy}, {@link #removeAmmoFromInventory}) need the exact same
 * "find whatever inventory-like object this Recruits entity exposes" fallback chain — resolving
 * it is centralized once in {@link #resolveSlots(Entity)}, which returns a uniform
 * {@link SlotAccessor} view so each public method only has to implement its own per-slot logic.
 */
public final class RecruitInventoryAmmoAccessor {

    private static final Logger LOGGER = LogManager.getLogger("jmteg");

    /** Recruits' equipment slots occupy indices 0..5; ammo scans/removals skip them. */
    private static final int GENERAL_INVENTORY_START_SLOT = 6;

    private RecruitInventoryAmmoAccessor() {}

    /**
     * Uniform view over whatever container-like object backs a Recruits entity's inventory,
     * so callers don't need to know whether it came from a vanilla {@link Container}, a
     * {@code RecruitSimpleContainer}, a raw {@code List<ItemStack>} field, or the Forge
     * item-handler capability.
     */
    private interface SlotAccessor {
        int size();

        ItemStack get(int index);

        /**
         * Removes up to {@code amount} from the stack at {@code index} (caller is expected to
         * have already verified the stack matches the desired ammo id). Returns the amount
         * actually removed.
         */
        int removeUpTo(int index, int amount);
    }

    private record ContainerSlots(Container container) implements SlotAccessor {
        @Override public int size() { return container.getContainerSize(); }
        @Override public ItemStack get(int index) { return container.getItem(index); }
        @Override public int removeUpTo(int index, int amount) {
            return container.removeItem(index, amount).getCount();
        }
    }

    private record ListSlots(List<ItemStack> items, Runnable markDirty) implements SlotAccessor {
        @Override public int size() { return items.size(); }
        @Override public ItemStack get(int index) { return items.get(index); }
        @Override public int removeUpTo(int index, int amount) {
            ItemStack st = items.get(index);
            if (st == null || st.isEmpty()) return 0;
            int take = Math.min(amount, st.getCount());
            st.shrink(take);
            if (st.getCount() <= 0) items.set(index, ItemStack.EMPTY);
            if (take > 0) markDirty.run();
            return take;
        }
    }

    private record RecruitSimpleContainerSlots(Object inv, Method sizeMethod, Method getItemMethod,
                                                Method setItemMethod, Runnable markDirty) implements SlotAccessor {
        @Override public int size() {
            try {
                return (Integer) sizeMethod.invoke(inv);
            } catch (Throwable t) {
                return 0;
            }
        }

        @Override public ItemStack get(int index) {
            try {
                Object st = getItemMethod.invoke(inv, index);
                return st instanceof ItemStack stack ? stack : ItemStack.EMPTY;
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
        }

        @Override public int removeUpTo(int index, int amount) {
            try {
                ItemStack st = get(index);
                if (st.isEmpty()) return 0;
                int take = Math.min(amount, st.getCount());
                st.shrink(take);
                if (st.getCount() <= 0) setItemMethod.invoke(inv, index, ItemStack.EMPTY);
                if (take > 0) markDirty.run();
                return take;
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    private record ItemHandlerSlots(IItemHandler handler) implements SlotAccessor {
        @Override public int size() { return handler.getSlots(); }
        @Override public ItemStack get(int index) { return handler.getStackInSlot(index); }
        @Override public int removeUpTo(int index, int amount) {
            return handler.extractItem(index, amount, false).getCount();
        }
    }

    /**
     * Resolves a uniform {@link SlotAccessor} for {@code entity}'s inventory, trying (in order):
     * <ol>
     *   <li>{@code getInventory()} returning a vanilla {@link Container}</li>
     *   <li>{@code getInventory()} (or the {@code inventory} field) returning a
     *       {@code RecruitSimpleContainer}, accessed via {@code getContainerSize}/{@code getItem}/
     *       {@code setItem}/{@code setChanged}</li>
     *   <li>{@code getInventory()} (or the {@code inventory} field) exposing a raw
     *       {@code items} list field</li>
     *   <li>The Forge {@code ITEM_HANDLER} capability</li>
     * </ol>
     * Returns {@code null} when none of the above are accessible.
     */
    private static SlotAccessor resolveSlots(Entity entity) {
        Method getInventory = ReflectionCache.findMethod(entity.getClass(), ReflectionCache.METHOD_GET_INVENTORY);
        if (getInventory != null) {
            try {
                Object inv = getInventory.invoke(entity);
                SlotAccessor slots = slotsForInventoryLikeObject(inv);
                if (slots != null) return slots;
            } catch (Throwable ignored) {
                // fall through to the 'inventory' field / capability fallbacks below
            }
        }

        Field invField = ReflectionCache.findField(entity.getClass(), ReflectionCache.FIELD_INVENTORY);
        if (invField != null) {
            try {
                Object inv = invField.get(entity);
                SlotAccessor slots = slotsForInventoryLikeObject(inv);
                if (slots != null) return slots;
            } catch (Throwable ignored) {
                // fall through to the capability fallback below
            }
        }

        try {
            var handler = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (handler.isPresent()) {
                IItemHandler h = handler.orElse(null);
                if (h != null) return new ItemHandlerSlots(h);
            }
        } catch (Throwable ignored) {
            // no accessible inventory at all
        }
        return null;
    }

    /** Adapts a resolved {@code getInventory()}/{@code inventory}-field value into a {@link SlotAccessor}. */
    private static SlotAccessor slotsForInventoryLikeObject(Object inv) {
        if (inv == null) return null;
        if (inv instanceof Container container) {
            return new ContainerSlots(container);
        }
        if (ReflectionCache.RECRUIT_SIMPLE_CONTAINER_CLASS_NAME.equals(inv.getClass().getName())) {
            Method sizeM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_CONTAINER_SIZE);
            Method getItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_GET_ITEM, int.class);
            Method setItemM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_ITEM, int.class, ItemStack.class);
            Method setChangedM = ReflectionCache.findMethod(inv.getClass(), ReflectionCache.METHOD_SET_CHANGED);
            if (sizeM != null && getItemM != null && setItemM != null) {
                Runnable markDirty = setChangedM == null ? () -> {} : () -> {
                    try { setChangedM.invoke(inv); } catch (Throwable ignored) {}
                };
                return new RecruitSimpleContainerSlots(inv, sizeM, getItemM, setItemM, markDirty);
            }
        }
        Field itemsField = ReflectionCache.findField(inv.getClass(), ReflectionCache.FIELD_ITEMS);
        if (itemsField != null) {
            try {
                Object listObj = itemsField.get(inv);
                if (listObj instanceof List<?> rawList) {
                    @SuppressWarnings("unchecked")
                    List<ItemStack> list = (List<ItemStack>) rawList;
                    return new ListSlots(list, () -> {});
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Dump inventory contents (index -> item id -> count) for debugging. */
    public static void dumpInventoryContents(Entity entity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Inventory dump for ").append(entity.getEncodeId()).append(" / ").append(entity.getUUID()).append(":");
            SlotAccessor slots = resolveSlots(entity);
            if (slots == null) {
                LOGGER.info(sb.append("\n  (no accessible inventory found)").toString());
                return;
            }
            for (int i = 0; i < slots.size(); i++) {
                ItemStack st = slots.get(i);
                ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                sb.append("\n  slot ").append(i).append(": ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
            }
            LOGGER.info(sb.toString());
        } catch (Throwable t) {
            LOGGER.warn("Failed to dump inventory for entity {}", entity.getUUID(), t);
        }
    }

    /** Fuzzy count: count items whose id string or path contains the poolId path or namespace. */
    public static int countAmmoInInventoryFuzzy(Entity entity, ResourceLocation poolId) {
        int total = 0;
        try {
            SlotAccessor slots = resolveSlots(entity);
            if (slots == null) return 0;
            String poolPath = poolId == null ? "" : poolId.getPath();
            String poolNs = poolId == null ? "" : poolId.getNamespace();
            for (int i = GENERAL_INVENTORY_START_SLOT; i < slots.size(); i++) {
                ItemStack st = slots.get(i);
                if (st == null || st.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (key == null) continue;
                String path = key.getPath();
                String id = key.toString();
                if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                    total += st.getCount();
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventoryFuzzy reflection failed", t);
        }
        return total;
    }

    public static int countAmmoInInventory(Entity entity, ResourceLocation ammoId) {
        int total = 0;
        try {
            SlotAccessor slots = resolveSlots(entity);
            if (slots == null) return 0;
            for (int i = GENERAL_INVENTORY_START_SLOT; i < slots.size(); i++) {
                ItemStack st = slots.get(i);
                if (st == null || st.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (ammoId.equals(key)) total += st.getCount();
            }
        } catch (Throwable t) {
            LOGGER.debug("countAmmoInInventory reflection failed", t);
        }
        return total;
    }

    public static int removeAmmoFromInventory(Entity entity, ResourceLocation ammoId, int amount) {
        if (amount <= 0) return 0;
        int removedTotal = 0;
        try {
            SlotAccessor slots = resolveSlots(entity);
            if (slots == null) return 0;
            for (int i = GENERAL_INVENTORY_START_SLOT; i < slots.size() && amount > 0; i++) {
                ItemStack st = slots.get(i);
                if (st == null || st.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (!ammoId.equals(key)) continue;
                int take = slots.removeUpTo(i, amount);
                removedTotal += take;
                amount -= take;
            }
        } catch (Throwable t) {
            LOGGER.debug("removeAmmoFromInventory reflection failed", t);
        }
        return removedTotal;
    }
}
