package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.jegcompat.JustEnoughGunsCompat;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Iterator;

/**
 * Replace recruit musket/JEG goals with a deterministic fallback goal for recruits holding a JEG gun.
 * This ensures controlled ammo, reload timing, and prevents frantic firing from duplicate goals.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitGoalOverrideHandler {
    private static final Logger LOGGER = LogManager.getLogger("JMT-RecruitGoalOverride");

    private RecruitGoalOverrideHandler() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof PathfinderMob mob)) return;

        // immediate sanitize (and sanitizer schedules a reschedule internally)
        try {
            EntityWeaponSanitizer.sanitize(mob);
        } catch (Throwable t) {
            LOGGER.debug("Sanitize failed on join for {}: {}", mob, t.toString());
        }

        // schedule deferred attempt to replace goals a couple ticks later
        try {
            DeferredTaskScheduler.schedule(() -> {
                try {
                    replaceGoalsIfJegGun(mob);
                } catch (Throwable ex) {
                    LOGGER.debug("Deferred replaceGoalsIfJegGun failed for {}: {}", mob, ex.toString());
                }
            }, 2);
        } catch (Throwable t) {
            // fallback immediate
            try {
                replaceGoalsIfJegGun(mob);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * If mob holds a JEG gun, remove recruits' musket goal and any JEG GunAttackGoal,
     * then ensure our deterministic fallback goal is installed exactly once.
     */
    public static void replaceGoalsIfJegGun(PathfinderMob mob) {
        if (mob == null) return;

        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) return;
        if (!JustEnoughGunsCompat.isJegGun(main)) return;

        LOGGER.info("Mob {} holds a JEG gun — ensuring deterministic fallback goal", mob);

        // Remove Recruit musket and any existing JEG GunAttackGoal entries
        try {
            GoalSelector selector = mob.goalSelector;
            Collection<?> entries = selector.getAvailableGoals();
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
                    } catch (NoSuchMethodException nsme) {
                        // ignore entries that don't expose getGoal
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Error while removing existing goals for {}: {}", mob, t.toString());
        }

        // Ensure fallback goal present exactly once
        boolean hasFallback = false;
        try {
            net.minecraft.nbt.CompoundTag pdata = mob.getPersistentData();
            if (pdata.getBoolean("jmteg_fallback_installed")) {
                LOGGER.debug("Skipping mob {}; fallback already installed", mob);
                return;
            }
        } catch (Throwable ignored) {}
        try {
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
        } catch (Throwable ignored) {}

        // after adding fallback:
        try {
            net.minecraft.nbt.CompoundTag pdata = mob.getPersistentData();
            pdata.putBoolean("jmteg_fallback_installed", true);
        } catch (Throwable t) {
            LOGGER.debug("Failed to mark fallback installed on {}", mob, t);
        }

        if (!hasFallback) {
            try {
                mob.goalSelector.addGoal(2, new RecruitRangedGunnerAttackGoal(mob));
                LOGGER.info("Added fallback RecruitRangedGunnerAttackGoal to {}", mob);
            } catch (Throwable t) {
                LOGGER.debug("Failed to add fallback goal to {}: {}", mob, t.toString());
            }
        } else {
            LOGGER.debug("Fallback goal already present for {}", mob);
        }
    }
}