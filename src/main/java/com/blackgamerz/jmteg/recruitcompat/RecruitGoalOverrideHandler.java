package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

        try { EntityWeaponSanitizer.sanitize(mob); } catch (Throwable t) { LOGGER.debug("sanitize failed", t); }

        // Delay initial registration slightly to avoid race with entity constructors
        DeferredTaskScheduler.schedule(() -> {
            try { evaluateAndApplyForMob(mob); } catch (Throwable t) { LOGGER.debug("deferred replace failed", t); }
        }, 2);
    }

    /**
     * Evaluate and apply appropriate goals for this mob:
     * - If holding JEG gun: ensure fallback movement/aim goal present and remove/remember conflicting goals.
     * - If not holding JEG gun: remove fallback and restore any stored original goals.
     */
    public static void evaluateAndApplyForMob(PathfinderMob mob) {
        if (mob == null || mob.level().isClientSide) return;

        ItemStack main = mob.getMainHandItem();
        boolean hasJegGun = main != null && !main.isEmpty() && JustEnoughGunsCompat.isJegGun(main);

        if (hasJegGun) {
            addFallbackIfMissing(mob);
        } else {
            restoreOriginalGoalsIfAny(mob);
        }
    }

    private static void addFallbackIfMissing(PathfinderMob mob) {
        // Avoid duplicate fallback
        boolean hasFallback = false;
        Collection<?> entries = mob.goalSelector.getAvailableGoals();
        if (entries != null) {
            for (Object entry : entries) {
                try {
                    var getGoal = entry.getClass().getMethod("getGoal");
                    Object goal = getGoal.invoke(entry);
                    if (goal != null && goal.getClass().getName().equals(RecruitRangedGunnerAttackGoal.class.getName())) {
                        hasFallback = true;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Remove competing attack goals and store them for restore
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
                                        || name.equals("ttv.migami.jeg.entity.ai.GunAttackGoal")) {
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

        // Add fallback goal if missing
        if (!hasFallback) {
            int priority = 0; // adjust priority as needed for your AI stack
            mob.goalSelector.addGoal(priority, new RecruitRangedGunnerAttackGoal(mob));
            LOGGER.info("Added fallback RecruitRangedGunnerAttackGoal to {}", mob);
        }
    }

    private static void restoreOriginalGoalsIfAny(PathfinderMob mob) {
        // Remove fallback(s)
        try {
            Collection<?> avail = mob.goalSelector.getAvailableGoals();
            if (avail != null) {
                for (Object entry : avail) {
                    try {
                        if (entry instanceof WrappedGoal wrap) {
                            Goal goal = wrap.getGoal();
                            if (goal != null && goal.getClass().getName().equals(RecruitRangedGunnerAttackGoal.class.getName())) {
                                mob.goalSelector.removeGoal(goal);
                                LOGGER.info("Removed fallback RecruitRangedGunnerAttackGoal from {}", mob);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Error removing fallback", t);
        }

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

    /*
     * OPTIONAL: If you want an extra robust fallback for unusual equipment changes (e.g. via NBT or direct field changes),
     * you can enable a server tick handler that periodically re-evaluates tracked mobs. This reduces the chance of
     * desynchronisation but has some cost. Implementing that was previously shown; leave disabled unless needed.
     */
}