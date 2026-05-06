package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.compat.ReflectionCache;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages fallback goal injection/restore for recruits holding JEG guns.
 * - On entity join: run an initial evaluation (delayed slightly to avoid races).
 * - Public API: forceReevaluate(mob) to be called when equipment changes (e.g. by EquipmentChangeHandler).
 *
 * NOTE: There is no mandatory per-tick re-evaluation here to reduce cost; if you want a robust fallback
 * for unusual sources of equipment change, re-enable the optional tick-based re-check in this file or
 * register a server tick handler that calls forceReevaluate on tracked mobs.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitGoalOverrideHandler {
    private static final Logger LOGGER = LogManager.getLogger("JMT-RecruitGoalOverride");

    private RecruitGoalOverrideHandler() {}

    // Store removed goals for later restoration; WeakHashMap to avoid leaks when entities are GC'd/unloaded
    private static final Map<PathfinderMob, List<RemovedGoal>> removedGoals = Collections.synchronizedMap(new WeakHashMap<>());

    private static class RemovedGoal {
        final Goal goal;
        final int priority;
        RemovedGoal(Goal goal, int priority) {
            this.goal = goal;
            this.priority = priority;
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof PathfinderMob mob)) return;
        if (mob.level().isClientSide) return;

        // Delay initial registration slightly to avoid race with entity constructors.
        // Ownership and equipment are also more reliably readable after the first couple of ticks.
        DeferredTaskScheduler.schedule(() -> {
            try {
                // Only sanitize (strip IgnoreAmmo / clamp AmmoCount) for recruits that are
                // owned by a player or faction. Unowned/NPC recruits keep the default JEG
                // mob behaviour where IgnoreAmmo=true and AmmoCount is unconstrained.
                if (RecruitOwnershipHelper.isAmmoAwareRecruit(mob)) {
                    try { EntityWeaponSanitizer.sanitize(mob); } catch (Throwable t) { LOGGER.debug("sanitize failed", t); }
                }
                evaluateAndApplyForMob(mob);
            } catch (Throwable t) { LOGGER.debug("deferred replace failed", t); }
        }, 2);
    }

    /**
     * Evaluate and apply appropriate goals for this mob:
     * - If holding JEG gun AND ammo-aware (player-owned / faction):
     *   ensure resupply goal (priority 0) and attack goal (priority 1) are present.
     * - If holding JEG gun but NOT ammo-aware (wild / NPC recruit):
     *   ensure only the attack goal (priority 0) is present; skip sanitizer and
     *   resupply so the recruit retains the original infinite-ammo reload mechanic.
     * - If not holding JEG gun: remove custom goals and restore stored originals.
     */
    public static void evaluateAndApplyForMob(PathfinderMob mob) {
        if (mob == null || mob.level().isClientSide) return;

        ItemStack main = mob.getMainHandItem();
        boolean hasJegGun = main != null && !main.isEmpty() && JustEnoughGunsCompat.isJegGun(main);

        if (hasJegGun) {
            // Proactively remove shield from two-handed (non-SIDEARM) recruits so it is
            // dropped even before the attack goal runs its first 20-tick role refresh.
            proactivelyUnequipShieldIfNeeded(mob, main);
            if (RecruitOwnershipHelper.isAmmoAwareRecruit(mob)) {
                addFallbackIfMissing(mob);       // resupply@0 + attack@1 + sanitizer
            } else {
                addAttackGoalOnly(mob);          // attack@0, no sanitizer, no resupply
            }
        } else {
            restoreOriginalGoalsIfAny(mob);
        }
    }

    private static void addFallbackIfMissing(PathfinderMob mob) {
        // Remove any previously registered custom goals to avoid stale priority mismatches.
        removeAllCustomGoals(mob);

        // Check what custom goals are already present (after removal, this detects externally added ones)
        boolean hasAttackGoal   = false;
        boolean hasResupplyGoal = false;
        Collection<?> entries = mob.goalSelector.getAvailableGoals();
        if (entries != null) {
            for (Object entry : entries) {
                try {
                    var getGoal = entry.getClass().getMethod("getGoal");
                    Object goal = getGoal.invoke(entry);
                    if (goal != null) {
                        String name = goal.getClass().getName();
                        if (name.equals(RecruitRangedGunnerAttackGoal.class.getName())) hasAttackGoal   = true;
                        if (name.equals(RecruitAmmoResupplyGoal.class.getName()))       hasResupplyGoal = true;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Remove competing vanilla attack goals and store them for restore
        removeAndStoreCompetingGoals(mob);

        // Resupply goal at priority 0: preempts attack only when gun is empty, then yields immediately.
        if (!hasResupplyGoal) {
            mob.goalSelector.addGoal(0, new RecruitAmmoResupplyGoal(mob));
            LOGGER.debug("Added RecruitAmmoResupplyGoal to {}", mob);
        }
        // Attack goal at priority 1: runs normally when ammo is available.
        if (!hasAttackGoal) {
            mob.goalSelector.addGoal(1, new RecruitRangedGunnerAttackGoal(mob));
            LOGGER.debug("Added RecruitRangedGunnerAttackGoal (ammo-aware) to {}", mob);
        }
    }

    /**
     * Adds only the attack goal (at priority 0) for unowned / NPC recruits that
     * should retain the original infinite-ammo JEG reload mechanic.
     * The {@link EntityWeaponSanitizer} is intentionally NOT called here so that
     * {@code IgnoreAmmo=true} and an unconstrained {@code AmmoCount} are preserved.
     */
    private static void addAttackGoalOnly(PathfinderMob mob) {
        removeAllCustomGoals(mob);

        boolean hasAttackGoal = false;
        Collection<?> entries = mob.goalSelector.getAvailableGoals();
        if (entries != null) {
            for (Object entry : entries) {
                try {
                    var getGoal = entry.getClass().getMethod("getGoal");
                    Object goal = getGoal.invoke(entry);
                    if (goal != null && goal.getClass().getName().equals(RecruitRangedGunnerAttackGoal.class.getName())) {
                        hasAttackGoal = true;
                    }
                } catch (Throwable ignored) {}
            }
        }

        removeAndStoreCompetingGoals(mob);

        if (!hasAttackGoal) {
            mob.goalSelector.addGoal(0, new RecruitRangedGunnerAttackGoal(mob));
            LOGGER.debug("Added RecruitRangedGunnerAttackGoal (no-ammo-constraint) to {}", mob);
        }
    }

    /** Removes all JMTEG-registered custom goals from the selector (attack + resupply). */
    private static void removeAllCustomGoals(PathfinderMob mob) {
        try {
            Collection<?> avail = mob.goalSelector.getAvailableGoals();
            if (avail == null) return;
            for (Object entry : avail) {
                try {
                    if (entry instanceof WrappedGoal wrap) {
                        Goal goal = wrap.getGoal();
                        if (goal == null) continue;
                        String name = goal.getClass().getName();
                        if (name.equals(RecruitRangedGunnerAttackGoal.class.getName())
                                || name.equals(RecruitAmmoResupplyGoal.class.getName())) {
                            mob.goalSelector.removeGoal(goal);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LOGGER.debug("removeAllCustomGoals failed", t);
        }
    }

    /** Removes and caches competing vanilla attack goals (Recruits mod musket/crossbow goals). */
    private static void removeAndStoreCompetingGoals(PathfinderMob mob) {
        List<RemovedGoal> stored = removedGoals.get(mob);
        if (stored == null) stored = Collections.synchronizedList(new ArrayList<>());

        try {
            Collection<?> avail = mob.goalSelector.getAvailableGoals();
            if (avail != null) {
                for (Object entry : avail) {
                    try {
                        if (entry instanceof WrappedGoal wrap) {
                            Goal goal = wrap.getGoal();
                            if (goal != null) {
                                String name = goal.getClass().getName();
                                if (name.equals("com.talhanation.recruits.entities.ai.compat.RecruitRangedMusketAttackGoal")
                                        || name.equals("com.talhanation.recruits.entities.ai.RecruitRangedCrossbowAttackGoal")
                                        ) {
                                    int prio = wrap.getPriority();
                                    stored.add(new RemovedGoal(goal, prio));
                                    mob.goalSelector.removeGoal(goal);
                                    LOGGER.info("Flagged & removed WrappedGoal {} from {}", name, mob);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("Error while scanning/removing a goal", t);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Error removing goals", t);
        }

        if (!stored.isEmpty()) removedGoals.put(mob, stored);
    }

    private static void restoreOriginalGoalsIfAny(PathfinderMob mob) {
        // Remove all JMTEG custom goals (both attack and resupply)
        removeAllCustomGoals(mob);

        // Restore stored original goals (if present)
        List<RemovedGoal> stored = removedGoals.remove(mob);
        if (stored != null && !stored.isEmpty()) {
            for (RemovedGoal rg : stored) {
                try {
                    mob.goalSelector.addGoal(rg.priority, rg.goal);
                    LOGGER.info("Restored original goal {} with priority {} to {}", rg.goal.getClass().getName(), rg.priority, mob);
                } catch (Throwable t) {
                    LOGGER.debug("Failed to restore original goal " + rg.goal, t);
                }
            }
        }
    }

    /**
     * Public helper for immediate re-evaluation (to be called from EquipmentChangeHandler).
     */
    public static void forceReevaluate(PathfinderMob mob) {
        try {
            evaluateAndApplyForMob(mob);
        } catch (Throwable t) {
            LOGGER.debug("forceReevaluate failed for " + mob, t);
        }
    }

    /**
     * Proactively removes the shield from a recruit's offhand when the held gun is
     * determined to be a two-handed (non-SIDEARM) weapon.
     *
     * <p>This is called from {@link #evaluateAndApplyForMob} on entity join and on
     * every equipment change so the shield is dropped even before the attack goal
     * runs its first role-refresh tick.
     */
    private static void proactivelyUnequipShieldIfNeeded(PathfinderMob mob, ItemStack heldGun) {
        try {
            ItemStack offhand = mob.getOffhandItem();
            if (offhand.isEmpty() || !(offhand.getItem() instanceof ShieldItem)) return;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(heldGun.getItem());
            if (id == null) return;
            RecruitLoadoutConfigManager.ensureLoaded();
            String classKey = RecruitGunSelector.detectRecruitClassKey(mob);
            RecruitLoadoutConfigManager.RecruitTierConfig tier = RecruitLoadoutConfigManager.getTierConfig(classKey);
            RecruitGunRole role = RecruitGunSelector.detectRole(id, tier);
            if (role != RecruitGunRole.SIDEARM) {
                ItemStack shield = offhand.copy();
                mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                // Also clear from the Recruits SimpleContainer so the mod doesn't re-sync it back.
                tryRemoveShieldFromRecruitInventory(mob);
                mob.spawnAtLocation(shield);
                LOGGER.info("Proactively removed shield from two-handed-gun recruit {}", mob);
            }
        } catch (Throwable t) {
            LOGGER.debug("proactivelyUnequipShieldIfNeeded failed", t);
        }
    }

    /**
     * Scans the Recruits mod SimpleContainer (via reflection) for a shield item and
     * removes the first one found.  This prevents the Recruits mod from re-syncing the
     * shield back into the offhand slot after the equipment slot has been cleared.
     * Safe to call when the Recruits mod is absent; all exceptions are swallowed.
     */
    static void tryRemoveShieldFromRecruitInventory(PathfinderMob mob) {
        try {
            Object inv = ReflectionCache.tryGetInventoryObject(mob);
            if (inv == null) return;
            Method getSize = inv.getClass().getMethod("getContainerSize");
            Method getItem = inv.getClass().getMethod("getItem", int.class);
            int size = (int) getSize.invoke(inv);
            for (int i = 0; i < size; i++) {
                ItemStack s = (ItemStack) getItem.invoke(inv, i);
                if (s != null && !s.isEmpty() && s.getItem() instanceof ShieldItem) {
                    ReflectionCache.tryWriteBackInventoryItem(inv, i, ItemStack.EMPTY);
                    break; // stop after removing the first shield found
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("tryRemoveShieldFromRecruitInventory failed", t);
        }
    }

    /*
     * OPTIONAL: If you want an extra robust fallback for unusual equipment changes (e.g. via NBT or direct field changes),
     * you can enable a server tick handler that periodically re-evaluates tracked mobs. This reduces the chance of
     * desynchronisation but has some cost. Implementing that was previously shown; leave disabled unless needed.
     */
}