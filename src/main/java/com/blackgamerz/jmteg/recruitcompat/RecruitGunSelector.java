package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects the best applicable JEG gun from a recruit's inventory based on the
 * Bowman / CrossBowman role-tier system.
 *
 * <h3>How balancing works</h3>
 * <ul>
 *   <li><b>Bowman</b> (cheaper) – has access to SIDEARM, BASIC_RANGED, and UTILITY roles at
 *       full preference, plus TACTICAL_RANGED at a reduced weight of 0.3.  HEAVY is not in
 *       the Bowman tier at all, so Bowman recruits cannot use rocket launchers, miniguns, etc.</li>
 *   <li><b>CrossBowman</b> (more expensive / specialised) – has access to all five roles.
 *       TACTICAL_RANGED and HEAVY carry weight 1.0, making it the natural choice for powerful
 *       weapons.  Lower-tier roles are accessible at reduced weights, keeping flexibility.</li>
 * </ul>
 *
 * <h3>Selection algorithm</h3>
 * <ol>
 *   <li>Detect recruit type (BOWMAN / CROSSBOWMAN) from class simple name.</li>
 *   <li>Load the tier config for that type from {@link RecruitLoadoutConfigManager}.</li>
 *   <li>Walk the recruit's inventory in a single pass; for each JEG gun compute its
 *       best matching role weight and track the single highest-weight candidate.</li>
 *   <li>Return the candidate with the highest weight.</li>
 *   <li>If no role-pool gun was found AND {@code fallback_to_any_gun} is enabled, return the
 *       first JEG gun found in inventory (so neither recruit becomes weaponless).</li>
 *   <li>If nothing at all, return {@link ItemStack#EMPTY}.</li>
 * </ol>
 *
 * <h3>Pack author tuning</h3>
 * Edit {@code config/jmteg/recruit_roles.json} to:
 * <ul>
 *   <li>Add/remove guns from role pools.</li>
 *   <li>Adjust weight values in recruit_tiers to shift power balance.</li>
 *   <li>Set {@code fallback_to_any_gun: false} to prevent improvised fallbacks.</li>
 * </ul>
 *
 * This class is reflection-safe: it never imports JEG or Recruits classes directly.
 */
public final class RecruitGunSelector {

    private static final Logger LOGGER = LogManager.getLogger("JMTEG-GunSelector");

    private RecruitGunSelector() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Selects the best gun {@link ItemStack} for the given recruit entity.
     *
     * @param recruit the recruit entity (may be any LivingEntity; returns EMPTY for non-recruits)
     * @return the best matching {@link ItemStack}, or {@link ItemStack#EMPTY} if none found
     */
    public static ItemStack selectBestGun(LivingEntity recruit) {
        if (recruit == null) return ItemStack.EMPTY;

        RecruitLoadoutConfigManager.ensureLoaded();

        String recruitClassKey = detectRecruitClassKey(recruit);
        RecruitLoadoutConfigManager.RecruitTierConfig tier =
                RecruitLoadoutConfigManager.getTierConfig(recruitClassKey);

        List<ItemStack> inventory = collectInventory(recruit);
        if (inventory.isEmpty()) return ItemStack.EMPTY;

        // --- Phase 1: find the best role-matched gun by weight ---
        ItemStack bestGun   = ItemStack.EMPTY;
        double    bestWeight = -1.0;

        for (ItemStack stack : inventory) {
            if (!isJegGun(stack)) continue;
            ResourceLocation itemId = getItemId(stack);
            if (itemId == null) continue;

            double weight = getRoleWeight(itemId, tier);
            if (weight > bestWeight) {
                bestWeight = weight;
                bestGun    = stack;
            }
        }

        if (!bestGun.isEmpty()) {
            LOGGER.debug("{} ({}) selected gun {} (role weight {})",
                    recruit.getClass().getSimpleName(), recruitClassKey, getItemId(bestGun), bestWeight);
            return bestGun;
        }

        // --- Phase 2: fallback to any JEG gun when enabled ---
        if (tier.fallbackToAnyGun) {
            for (ItemStack stack : inventory) {
                if (isJegGun(stack)) {
                    LOGGER.debug("{} ({}) fallback to first available JEG gun {}",
                            recruit.getClass().getSimpleName(), recruitClassKey, getItemId(stack));
                    return stack;
                }
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * Returns the highest role-preference weight for the given gun within the provided tier config.
     * Returns 0.0 if the gun does not match any role accessible to this recruit type.
     *
     * @param itemId gun item ResourceLocation
     * @param tier   the recruit class's tier config
     * @return best matching weight (0.0 if no match)
     */
    public static double getRoleWeight(ResourceLocation itemId,
                                       RecruitLoadoutConfigManager.RecruitTierConfig tier) {
        if (itemId == null || tier == null) return 0.0;
        double best = 0.0;
        for (RecruitLoadoutConfigManager.RoleWeight rw : tier.roles) {
            List<ResourceLocation> pool =
                    RecruitLoadoutConfigManager.ROLE_GUN_POOLS.get(rw.role);
            if (pool != null && pool.contains(itemId)) {
                if (rw.weight > best) best = rw.weight;
            }
        }
        return best;
    }

    /**
     * Returns the {@link RecruitGunRole} of a gun relative to this recruit type, or
     * {@code null} if the gun is not in any accessible role pool.
     */
    public static RecruitGunRole detectRole(ResourceLocation itemId,
                                            RecruitLoadoutConfigManager.RecruitTierConfig tier) {
        if (itemId == null || tier == null) return null;
        double bestWeight = -1.0;
        RecruitGunRole bestRole = null;
        for (RecruitLoadoutConfigManager.RoleWeight rw : tier.roles) {
            List<ResourceLocation> pool =
                    RecruitLoadoutConfigManager.ROLE_GUN_POOLS.get(rw.role);
            if (pool != null && pool.contains(itemId) && rw.weight > bestWeight) {
                bestWeight = rw.weight;
                bestRole   = rw.role;
            }
        }
        return bestRole;
    }

    // ── Recruit type detection ─────────────────────────────────────────────────

    /**
     * Returns "BOWMAN" or "CROSSBOWMAN" based on the entity's class name.
     * Defaults to "BOWMAN" for unknown/generic recruits.
     */
    public static String detectRecruitClassKey(LivingEntity entity) {
        if (entity == null) return "BOWMAN";
        String name = entity.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("crossbow")) return "CROSSBOWMAN";
        if (name.contains("bowman"))   return "BOWMAN";
        // Broader recruits fallback: any recruit gets BOWMAN tier unless it's a crossbowman
        return "BOWMAN";
    }

    // ── Inventory collection ───────────────────────────────────────────────────

    /**
     * Collects all item stacks from the recruit's inventory (via reflection) plus main/off hand.
     * Safe when the Recruits mod is absent.
     */
    static List<ItemStack> collectInventory(LivingEntity entity) {
        List<ItemStack> result = new ArrayList<>();

        // Try Recruits inventory via ReflectionCache helper
        try {
            if (entity instanceof net.minecraft.world.entity.PathfinderMob mob) {
                Object inv = ReflectionCache.tryGetInventoryObject(mob);
                if (inv != null) {
                    try {
                        Method getSize = inv.getClass().getMethod("getContainerSize");
                        Method getItem = inv.getClass().getMethod("getItem", int.class);
                        int size = (int) getSize.invoke(inv);
                        for (int i = 0; i < size; i++) {
                            ItemStack stack = (ItemStack) getItem.invoke(inv, i);
                            if (stack != null && !stack.isEmpty()) result.add(stack);
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("collectInventory: could not iterate inventory", t);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("collectInventory: reflection failed", t);
        }

        // Always include main and off-hand (in case they're not in the simpleInventory list)
        ItemStack main = entity.getMainHandItem();
        ItemStack off  = entity.getOffhandItem();
        if (!main.isEmpty() && !result.contains(main)) result.add(main);
        if (!off.isEmpty()  && !result.contains(off))  result.add(off);

        return result;
    }

    // ── JEG gun detection ──────────────────────────────────────────────────────

    private static boolean isJegGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Class<?> gunItemClass = ReflectionCache.getJegGunItemClass();
        return gunItemClass != null && gunItemClass.isInstance(stack.getItem());
    }

    private static ResourceLocation getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }
}
