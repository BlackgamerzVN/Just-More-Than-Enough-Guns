package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import com.blackgamerz.jmteg.jegcompat.Gun;
import com.blackgamerz.jmteg.jegcompat.GunAmmoResolver;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles ammo reload from the entity's own inventory when a JEG gun runs dry.
 *
 * <p>Search order:
 * <ol>
 *   <li>Recruits mod inventory (via soft-dependency reflection)</li>
 *   <li>Player inventory (if the holder is a player)</li>
 *   <li>Off-hand fallback</li>
 * </ol>
 *
 * <p>Ammo matching uses {@link GunAmmoResolver#isAmmoForGun} which reads the
 * required ammo item from the gun's NBT or the JEG JSON config, so no hard
 * dependency on JEG is introduced here.
 */
public class AmmoConsumptionHandler {

    private static final Logger LOGGER = LogManager.getLogger("JMTEG-AmmoConsumption");

    private AmmoConsumptionHandler() {}

    /**
     * Searches the entity's inventory for matching ammo and reloads {@code gunStack}
     * to its maximum capacity on success.
     *
     * @param mob      the entity holding the gun
     * @param gunStack the gun ItemStack whose {@code AmmoCount} NBT will be updated
     * @return {@code true} if ammo was found and the gun was reloaded
     */
    public static boolean tryReloadFromInventory(LivingEntity mob, ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty()) return false;

        int maxAmmo = getMaxAmmo(gunStack);

        // 1. Recruits mod inventory (soft dependency via reflection)
        if (mob instanceof PathfinderMob pathfinderMob) {
            try {
                Object inventory = ReflectionCache.tryGetInventoryObject(pathfinderMob);
                if (inventory != null) {
                    int size = (int) inventory.getClass().getMethod("getContainerSize").invoke(inventory);
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = (ItemStack) inventory.getClass()
                                .getMethod("getItem", int.class).invoke(inventory, i);
                        if (isValidAmmo(stack, gunStack)) {
                            stack.shrink(1);
                            ReflectionCache.tryWriteBackInventoryItem(inventory, i, stack);
                            setAmmoCount(gunStack, maxAmmo);
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Player inventory
        if (mob instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) {
                if (isValidAmmo(stack, gunStack)) {
                    stack.shrink(1);
                    setAmmoCount(gunStack, maxAmmo);
                    return true;
                }
            }
        }

        // 3. Off-hand fallback
        ItemStack offhand = mob.getOffhandItem();
        if (isValidAmmo(offhand, gunStack)) {
            offhand.shrink(1);
            setAmmoCount(gunStack, maxAmmo);
            return true;
        }

        return false;
    }

    /**
     * Returns {@code true} when {@code ammoCandidate} is the correct ammo type
     * for {@code gunStack}, as determined by {@link GunAmmoResolver}.
     */
    public static boolean isValidAmmo(ItemStack ammoCandidate, ItemStack gunStack) {
        if (ammoCandidate == null || ammoCandidate.isEmpty()) return false;
        if (gunStack == null || gunStack.isEmpty()) return false;
        try {
            return GunAmmoResolver.isAmmoForGun(ammoCandidate, gunStack);
        } catch (Throwable t) {
            LOGGER.debug("isValidAmmo check failed", t);
            return false;
        }
    }

    /**
     * Returns the maximum ammo capacity of {@code gunStack} by reading the
     * gun's NBT data via {@link Gun#getMaxAmmo()}.
     * Falls back to {@code 6} if the data is unavailable.
     */
    public static int getMaxAmmo(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty()) return 6;
        try {
            int max = Gun.from(gunStack).getMaxAmmo();
            return max > 0 ? max : 6;
        } catch (Throwable t) {
            LOGGER.debug("getMaxAmmo failed, using fallback", t);
            return 6;
        }
    }

    /**
     * Writes {@code count} (clamped to {@link #getMaxAmmo}) into the
     * {@code AmmoCount} NBT entry of {@code gunStack}.
     *
     * <p>Use this helper instead of accessing NBT directly so all ammo-count
     * mutations go through a single call site.
     *
     * @param gunStack the gun whose AmmoCount will be updated in-place
     * @param count    the new ammo count; values above {@code getMaxAmmo} are clamped
     */
    public static void setAmmoCount(ItemStack gunStack, int count) {
        if (gunStack == null || gunStack.isEmpty()) return;
        int clamped = Math.min(count, getMaxAmmo(gunStack));
        gunStack.getOrCreateTag().putInt("AmmoCount", Math.max(0, clamped));
    }
}