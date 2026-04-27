package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitGoalOverrideHandler {
    private static final Logger LOGGER = LogManager.getLogger("JMT-RecruitGoalOverride");
    private static final String FALLBACK_TAG = "jmteg_fallback_installed";

    private RecruitGoalOverrideHandler() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof PathfinderMob mob)) return;
        if (mob.level().isClientSide) return;

        try {
            CompoundTag pdata = mob.getPersistentData();
            if (pdata.getBoolean(FALLBACK_TAG)) return;
        } catch (Throwable ignored) {}

        try { EntityWeaponSanitizer.sanitize(mob); } catch (Throwable t) { LOGGER.debug("sanitize failed", t); }

        // Delay registration to avoid race with vanilla/mod goal construction in entity constructors
        DeferredTaskScheduler.schedule(() -> {
            try { replaceGoalsWithFallback(mob); } catch (Throwable t) { LOGGER.debug("deferred replace failed", t); }
        }, 2);
    }

    public static void replaceGoalsWithFallback(PathfinderMob mob) {
        if (mob == null || mob.level().isClientSide) return;

        // Guard: only handle if mainhand is a JEG gun
        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) return;
        if (!JustEnoughGunsCompat.isJegGun(main)) return;

        try {
            CompoundTag pdata = mob.getPersistentData();
            if (pdata.getBoolean(FALLBACK_TAG)) return;
        } catch (Throwable ignored) {}

        // ---- 1. Remove existing attack goals that target recruit/JEG guns ----
        try {
            Collection<?> entries = mob.goalSelector.getAvailableGoals();
            List<WrappedGoal> toRemove = new ArrayList<>();

            if (entries != null) {
                for (Object entry : entries) {
                    if (entry instanceof WrappedGoal wrap) {
                        Goal goal = wrap.getGoal();
                        if (goal != null) {
                            String name = goal.getClass().getName();
                            if (name.equals("com.talhanation.recruits.entities.ai.compat.RecruitRangedMusketAttackGoal")
                                    || name.equals("com.talhanation.recruits.entities.ai.RecruitRangedCrossbowAttackGoal")
                                    || name.equals("ttv.migami.jeg.entity.ai.GunAttackGoal")) {
                                toRemove.add(wrap);
                                LOGGER.info("Flagged for removal WrappedGoal {} from {}", name, mob);
                            }
                        }
                    }
                }
            }

            // This removes the wrapped instance from the selector
            for (WrappedGoal wrap : toRemove) {
                mob.goalSelector.removeGoal(wrap.getGoal());
                LOGGER.info("Removed goal instance {}", wrap.getGoal());
            }

        } catch (Throwable t) {
            LOGGER.debug("Error removing goals", t);
        }

        // ---- 2. Avoid duplicate fallback goals; add only if missing ----
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

        // ---- 3. Add fallback goal, register at LOWEST available priority (e.g., 0 for first, or choose as appropriate for your AI stack) ----
        if (!hasFallback) {
            int priority = 0; // You can bump this if needed; 0 means "run first"
            mob.goalSelector.addGoal(priority, new RecruitRangedGunnerAttackGoal(mob));
            LOGGER.info("Added fallback RecruitRangedGunnerAttackGoal to {}", mob);
        }

        try {
            // Mark as processed, so handler only runs once per entity
            CompoundTag pdata = mob.getPersistentData();
            pdata.putBoolean(FALLBACK_TAG, true);
        } catch (Throwable ignored) {}

        // ---- 4. Log new goal stack for debugging ----
        entries = mob.goalSelector.getAvailableGoals();
        for (Object entry : entries) {
            try {
                if (entry instanceof WrappedGoal goalWrap) {
                    Goal goal = goalWrap.getGoal();
                    int prio = goalWrap.getPriority();
                    System.out.println("AFTER:GOAL" + goal.getClass().getName() + "PRIORITY" + prio);
                }
            } catch (Throwable ignored) {}
        }
    }
}