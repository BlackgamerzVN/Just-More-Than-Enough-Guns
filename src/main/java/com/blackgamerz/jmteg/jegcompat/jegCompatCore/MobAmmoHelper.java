package com.blackgamerz.jmteg.jegcompat.jegCompatCore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * Small helper that stores mob ammo pools in entity persistent NBT.
 * Uses the same key format as JEG: "JEG_Ammo_<namespace>_<path>" (':' -> '_').
 */
public final class MobAmmoHelper {
    private static final String PREFIX = "JEG_Ammo_";

    private MobAmmoHelper() {}

    private static String keyFor(ResourceLocation id) {
        if (id == null) return PREFIX + "null";
        return PREFIX + id.toString().replace(':', '_');
    }

    public static int getAmmoPool(LivingEntity mob, ResourceLocation ammoId) {
        if (mob == null || ammoId == null) return 0;
        CompoundTag nbt = mob.getPersistentData();
        String key = keyFor(ammoId);
        return nbt.contains(key) ? nbt.getInt(key) : 0;
    }

    public static int addAmmo(LivingEntity mob, ResourceLocation ammoId, int amount) {
        if (mob == null || ammoId == null) return 0;
        if (amount <= 0) return getAmmoPool(mob, ammoId);
        CompoundTag nbt = mob.getPersistentData();
        String key = keyFor(ammoId);
        int current = nbt.contains(key) ? nbt.getInt(key) : 0;
        int updated = Math.max(0, current + amount);
        nbt.putInt(key, updated);
        return updated;
    }

    /**
     * Attempts to consume up to requested ammo from the mob's pool.
     * @return the actual amount consumed (0..requested)
     */
    public static int consumeAmmo(LivingEntity mob, ResourceLocation ammoId, int requested) {
        if (mob == null || ammoId == null) return 0;
        if (requested <= 0) return 0;
        CompoundTag nbt = mob.getPersistentData();
        String key = keyFor(ammoId);
        int current = nbt.contains(key) ? nbt.getInt(key) : 0;
        int consumed = Math.min(current, requested);
        if (consumed > 0) {
            nbt.putInt(key, current - consumed);
        }
        return consumed;
    }

    public static boolean hasAmmo(LivingEntity mob, ResourceLocation ammoId) {
        return getAmmoPool(mob, ammoId) > 0;
    }
}