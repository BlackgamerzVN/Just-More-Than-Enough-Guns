package com.blackgamerz.jmteg.util;

import com.blackgamerz.jmteg.config.JmtegConfig;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Heuristics and helpers for ammo detection (bow/musket support).
 */
public final class AmmoUtils {
    private static final Logger LOGGER = LogManager.getLogger("JMTEG-AmmoUtils");

    // Basic: is this a Recruits, Skeleton, or crossbow user (extendable)?
    public static boolean isBowUser(LivingEntity ent) {
        String cname = ent.getClass().getSimpleName().toLowerCase();
        return (cname.contains("bowman") || cname.contains("archer") || cname.contains("skeleton"));
        // Extend for other archers as needed
    }
    public static boolean isMusketUser(LivingEntity ent) {
        String cname = ent.getClass().getSimpleName().toLowerCase();
        return cname.contains("musket") || cname.contains("crossbow"); // add modded Turkish musket here, etc.
    }

    // Count all arrow stacks on the entity (main, offhand, inventory, if any).
    public static int getRangedAmmoCount(Mob mob, ItemStack main, ItemStack offhand) {
        int count = 0;
        if (main != null && main.getItem() == Items.ARROW) count += main.getCount();
        if (offhand != null && offhand.getItem() == Items.ARROW) count += offhand.getCount();
        // If has inventory (e.g. Recruits): use reflection to look for items/arrow stacks
        try {
            Object inv = Class.forName("com.talhanation.recruits.entities.inventory.RecruitInventory")
                    .cast(mob.getClass().getMethod("getInventory").invoke(mob));
            if (inv != null) {
                java.lang.reflect.Field f = inv.getClass().getField("items");
                Object itemsObj = f.get(inv);
                if (itemsObj instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) itemsObj) {
                        if (o instanceof ItemStack && !((ItemStack) o).isEmpty()
                                && ((ItemStack) o).getItem() == Items.ARROW)
                            count += ((ItemStack) o).getCount();
                    }
                }
            }
        } catch (Throwable t) {
            // Not Recruits or no inventory
        }
        return count;
    }

    // Is this a vanilla recruit/skeleton projectile entity (used for broad projectile gate)?
    public static boolean isStandardProjectile(Entity e) {
        String name = e.getClass().getSimpleName().toLowerCase();
        return (name.contains("arrow") || name.contains("musket") || name.contains("cartridge") || name.contains("bullet"));
    }

    // JEG/projectile matching — call .contains(…) on standard names
    public static boolean matchesAmmo(ItemStack gun, ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        // If config enabled, use whitelist
        if (JmtegConfig.useWhitelist() && JmtegConfig.getAmmoWhitelist() != null) {
            String key = candidate.getItem().toString().toLowerCase();
            return JmtegConfig.getAmmoWhitelist().contains(key);
        }
        // Heuristic match
        try {
            String s = candidate.getItem().toString().toLowerCase();
            if (s.contains("arrow") || s.contains("ammo") || s.contains("bullet") ||
                    s.contains("cartridge") || s.contains("musket") || s.contains("shot"))
                return true;
            String cls = candidate.getItem().getClass().getName().toLowerCase();
            if (cls.contains("arrow") || cls.contains("ammo") || cls.contains("bullet") ||
                    cls.contains("cartridge") || cls.contains("musket") || cls.contains("shot"))
                return true;
        } catch (Throwable ignored) {}
        return false;
    }
}