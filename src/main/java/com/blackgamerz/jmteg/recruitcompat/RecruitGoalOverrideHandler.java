package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.jegcompat.JustEnoughGunsCompat;
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

import java.util.Collection;
import java.util.Iterator;

/**
 * Orchestrates replacement of recruit/JEG goals with the deterministic fallback.
 * Marks mob persistent data when fallback is installed to avoid reprocessing.
 */
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
            // quick skip if already handled
            CompoundTag pdata = mob.getPersistentData();
            if (pdata.getBoolean(FALLBACK_TAG)) return;
        } catch (Throwable ignored) {}

        // sanitize and defer replacement slightly
        try { EntityWeaponSanitizer.sanitize(mob); } catch (Throwable t) { LOGGER.debug("sanitize failed", t); }

        DeferredTaskScheduler.schedule(() -> {
            try { replaceGoalsWithFallback(mob); } catch (Throwable t) { LOGGER.debug("deferred replace failed", t); }
        }, 2);
    }

    public static void replaceGoalsWithFallback(PathfinderMob mob) {
        if (mob == null || mob.level().isClientSide) return;

        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) return;
        if (!JustEnoughGunsCompat.isJegGun(main)) return;

        try {
            CompoundTag pdata = mob.getPersistentData();
            if (pdata.getBoolean(FALLBACK_TAG)) return;
        } catch (Throwable ignored) {}

        // remove recruit musket + any JEG GunAttackGoal
        try {
            Collection<?> entries = mob.goalSelector.getAvailableGoals();
            if (entries != null) {
                Iterator<?> it = entries.iterator();
                while (it.hasNext()) {
                    Object entry = it.next();
                    try {
                        var getGoal = entry.getClass().getMethod("getGoal");
                        Object goal = getGoal.invoke(entry);
                        if (goal != null) {
                            String name = goal.getClass().getName();
                            if (name.equals("com.talhanation.recruits.entities.ai.compat.RecruitRangedMusketAttackGoal")
                                    || name.equals("ttv.migami.jeg.entity.ai.GunAttackGoal")) {
                                it.remove();
                                LOGGER.info("Removed goal {} from {}", name, mob);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Error removing goals", t);
        }

        // add fallback (once)
        try {
            boolean has = false;
            var entries = mob.goalSelector.getAvailableGoals();
            if (entries != null) {
                for (Object entry : entries) {
                    try {
                        var getGoal = entry.getClass().getMethod("getGoal");
                        Object goal = getGoal.invoke(entry);
                        if (goal != null && goal.getClass().getName().equals(RecruitRangedGunnerAttackGoal.class.getName())) {
                            has = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (!has) {
                mob.goalSelector.addGoal(2, new RecruitRangedGunnerAttackGoal(mob));
                LOGGER.info("Added fallback RecruitRangedGunnerAttackGoal to {}", mob);
            }

            // mark persistent so we skip future heavy processing
            try {
                CompoundTag pdata = mob.getPersistentData();
                pdata.putBoolean(FALLBACK_TAG, true);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOGGER.debug("Failed to add fallback", t);
        }
    }
}