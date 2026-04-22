package com.blackgamerz.jmteg.util;

import com.blackgamerz.jmteg.config.JmtegConfig;
import net.minecraft.world.item.ItemStack;

/**
 * Ammo heuristics and whitelist checking. Uses JmtegConfig.ammoWhitelist if configured.
 */
public final class AmmoUtils {
    private AmmoUtils() {}

    public static boolean matchesAmmo(ItemStack gun, ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) return false;

        // if config whitelist set, prefer exact match on resource location strings
        if (JmtegConfig.useWhitelist() && JmtegConfig.getAmmoWhitelist() != null) {
            String key = candidate.getItem().toString().toLowerCase();
            return JmtegConfig.getAmmoWhitelist().contains(key);
        }

        // fallback heuristics on item class/name
        try {
            String s = candidate.getItem().toString().toLowerCase();
            if (s.contains("arrow") || s.contains("ammo") || s.contains("bullet") || s.contains("cartridge") || s.contains("shot")) return true;
            String cls = candidate.getItem().getClass().getName().toLowerCase();
            if (cls.contains("arrow") || cls.contains("ammo") || cls.contains("bullet") || cls.contains("cartridge") || cls.contains("shot")) return true;
        } catch (Throwable ignored) {}
        return false;
    }
}