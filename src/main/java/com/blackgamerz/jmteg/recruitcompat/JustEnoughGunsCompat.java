package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;


import net.minecraft.world.entity.LivingEntity;

/**
 * JEG compatibility helpers (cached reflection via ReflectionCache).
 * Best-effort, safe if JEG absent.
 *
 * <p>In addition to the original ammo-check helpers, this class now exposes
 * {@link #selectBestGunForRecruit(LivingEntity)} which applies the role-based
 * Bowman / CrossBowman balancing system to pick the most appropriate JEG gun
 * from a recruit's inventory.  See {@link RecruitGunSelector} for the full
 * algorithm description and {@link RecruitLoadoutConfigManager} for the
 * config format.</p>
 */
public final class JustEnoughGunsCompat {
    private static final Logger LOGGER = LogManager.getLogger("JMT-JEG-Compat");

    private JustEnoughGunsCompat() {}

    /**
     * Upper bound on a valid JEG reload time (ticks).  72000 = 1 hour at 20 TPS — any
     * value at or above this comes from the Minecraft default {@code Item.getUseDuration}
     * implementation and does not represent a real gun reload duration.
     */
    private static final int MAX_VALID_RELOAD_TICKS = 72000;

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

    // ── Role-based gun selection (Bowman vs CrossBowman balancing) ─────────────

    /**
     * Selects the best applicable JEG gun for this recruit entity using the
     * role-tier system (Bowman / CrossBowman balancing).
     *
     * <p>Balance summary:</p>
     * <ul>
     *   <li><b>Bowman</b> (cheaper) – prefers SIDEARM and BASIC_RANGED roles;
     *       can use UTILITY at reduced preference and TACTICAL_RANGED at low
     *       preference; cannot access HEAVY weapons at all.</li>
     *   <li><b>CrossBowman</b> (expensive / elite) – full access including
     *       TACTICAL_RANGED and HEAVY at maximum preference.</li>
     * </ul>
     *
     * <p>If no role-pool gun is found, falls back to any JEG gun in inventory
     * (configurable via {@code fallback_to_any_gun} in recruit_roles.json).</p>
     *
     * @param entity the recruit entity
     * @return best matching gun stack, or {@link ItemStack#EMPTY} if none found
     */
    public static ItemStack selectBestGunForRecruit(LivingEntity entity) {
        if (entity == null) return ItemStack.EMPTY;
        try {
            return RecruitGunSelector.selectBestGun(entity);
        } catch (Throwable t) {
            LOGGER.debug("selectBestGunForRecruit failed for {}", entity, t);
            return ItemStack.EMPTY;
        }
    }

    /**
     * Returns the {@link RecruitGunRole} of the given gun stack relative to
     * the recruit entity's accessible role tier, or {@code null} if the gun
     * is not matched to any role for this recruit type.
     *
     * @param entity   the recruit entity (used to detect Bowman vs CrossBowman tier)
     * @param gunStack the gun item stack
     * @return matched role, or {@code null}
     */
    public static RecruitGunRole getGunRoleForRecruit(LivingEntity entity, ItemStack gunStack) {
        if (entity == null || gunStack == null || gunStack.isEmpty()) return null;
        try {
            RecruitLoadoutConfigManager.ensureLoaded();
            String classKey = RecruitGunSelector.detectRecruitClassKey(entity);
            RecruitLoadoutConfigManager.RecruitTierConfig tier =
                    RecruitLoadoutConfigManager.getTierConfig(classKey);
            ResourceLocation itemId =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(gunStack.getItem());
            return RecruitGunSelector.detectRole(itemId, tier);
        } catch (Throwable t) {
            LOGGER.debug("getGunRoleForRecruit failed", t);
            return null;
        }
    }

    // ── Original ammo-check helpers ────────────────────────────────────────────

    /**
     * Returns the reload duration (ticks) configured in JEG for the given gun stack,
     * matching exactly the time a player waits when reloading the same weapon.
     *
     * <p>Uses three reflection strategies in order of reliability:
     * <ol>
     *   <li>{@code GunItem.getUseDuration(ItemStack, LivingEntity)} — standard Forge item
     *       method overridden by JEG to return its reload time.</li>
     *   <li>{@code GunItem.getUseDuration(ItemStack)} — older signature.</li>
     *   <li>Direct {@code Reloads} field access via
     *       {@code getModifiedGun → getReloads → getReloadTime/getTime}.</li>
     * </ol>
     *
     * @param stack the gun ItemStack
     * @return reload time in ticks, or {@code -1} if the value could not be determined
     */
    public static int getJegGunReloadTime(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        try {
            Class<?> gunItemClass = ReflectionCache.getJegGunItemClass();
            if (gunItemClass == null) return -1;
            Object itemObj = stack.getItem();
            if (!gunItemClass.isInstance(itemObj)) return -1;

            // Strategy 1: getUseDuration(ItemStack, LivingEntity) – Forge 1.20+ signature
            try {
                Class<?> livingClass = Class.forName("net.minecraft.world.entity.LivingEntity");
                Method m = itemObj.getClass().getMethod("getUseDuration", ItemStack.class, livingClass);
                Object result = m.invoke(itemObj, stack, (Object) null);
                if (result instanceof Number n) {
                    int v = n.intValue();
                    if (v > 0 && v < MAX_VALID_RELOAD_TICKS) return v;
                }
            } catch (NoSuchMethodException | ClassNotFoundException ignored) {}

            // Strategy 2: getUseDuration(ItemStack) – older / simplified signature
            try {
                Method m = itemObj.getClass().getMethod("getUseDuration", ItemStack.class);
                Object result = m.invoke(itemObj, stack);
                if (result instanceof Number n) {
                    int v = n.intValue();
                    if (v > 0 && v < MAX_VALID_RELOAD_TICKS) return v;
                }
            } catch (NoSuchMethodException ignored) {}

            // Strategy 3: getModifiedGun → getReloads → getReloadTime / getTime
            Method getModifiedGun = ReflectionCache.getJeg_getModifiedGun();
            if (getModifiedGun == null) return -1;
            Object modifiedGun = getModifiedGun.invoke(itemObj, stack);
            if (modifiedGun == null) return -1;

            Method getReloads = ReflectionCache.findMethod(modifiedGun.getClass(), "getReloads");
            if (getReloads == null) return -1;
            Object reloads = getReloads.invoke(modifiedGun);
            if (reloads == null) return -1;

            for (String name : new String[]{"getReloadTime", "getTime", "getReloadDuration"}) {
                try {
                    Method m = reloads.getClass().getMethod(name);
                    Object val = m.invoke(reloads);
                    if (val instanceof Number n) {
                        int v = n.intValue();
                        if (v > 0) return v;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable t) {
            LOGGER.debug("getJegGunReloadTime failed", t);
        }
        return -1;
    }

    public static boolean hasJegGunAmmo(LivingEntity entity, ItemStack gunStack) {
        try {
            // Get the modified gun object via reflection
            Object gun = gunStack.getItem().getClass()
                    .getMethod("getModifiedGun", ItemStack.class)
                    .invoke(gunStack.getItem(), gunStack);

            if (gun != null) {
                // Get projectile
                Object projectile = gun.getClass().getMethod("getProjectile").invoke(gun);
                // Get ammo item
                Object ammoItem = projectile.getClass().getMethod("getItem").invoke(projectile);

                // Try Recruit inventory (reflection for cross-mod support)
                try {
                    Class<?> recruitClass = Class.forName("com.talhanation.recruits.entities.RecruitEntity");
                    if (recruitClass.isInstance(entity)) {
                        Object inventory = recruitClass.getMethod("getInventory").invoke(entity);
                        int size = (int) inventory.getClass().getMethod("getContainerSize").invoke(inventory);
                        for (int i = 0; i < size; i++) {
                            ItemStack stack = (ItemStack) inventory.getClass()
                                    .getMethod("getItem", int.class).invoke(inventory, i);
                            if (!stack.isEmpty() && stack.getItem() == ammoItem) {
                                return true;
                            }
                        }
                        return false;
                    }
                } catch (ClassNotFoundException ignored) {
                }
                // Fallback to hands
                ItemStack mainhand = entity.getMainHandItem();
                if (!mainhand.isEmpty() && mainhand.getItem() == ammoItem) return true;
                ItemStack offhand = entity.getOffhandItem();
                if (!offhand.isEmpty() && offhand.getItem() == ammoItem) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

}