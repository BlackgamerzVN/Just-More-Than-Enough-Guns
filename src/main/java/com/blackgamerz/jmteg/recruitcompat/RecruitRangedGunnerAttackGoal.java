package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Movement/aim-only fallback for recruits holding JEG guns.
 * Shooting and reload are handled by the injected JEG GunAttackGoal (MobAiInjector).
 */
public class RecruitRangedGunnerAttackGoal extends Goal {
    private final PathfinderMob mob;

    private enum State { IDLE, SEEK, AIM, COOLDOWN }
    private State state = State.IDLE;

    private static final double ATTACK_RANGE_SQ = 16.0 * 16.0;
    private static final int AIM_TIME = 20;
    private static final int COOLDOWN_TIME = 40;

    private int aimTimer = 0;
    private int cooldownTimer = 0;

    public RecruitRangedGunnerAttackGoal(PathfinderMob mob) {
        this.mob = mob;
    }

    @Override
    public EnumSet<Flag> getFlags() {
        return EnumSet.of(Flag.TARGET, Flag.MOVE, Flag.LOOK);
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public void start() {
        this.state = State.IDLE;
        aimTimer = 0;
        cooldownTimer = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        this.state = State.IDLE;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            state = State.IDLE;
            return;
        }

        double dist = mob.distanceToSqr(target);

        switch (state) {
            case IDLE -> {
                if (dist > ATTACK_RANGE_SQ) {
                    state = State.SEEK;
                } else {
                    // In range — start aiming so the injected GunAttackGoal can take the shot
                    state = State.AIM;
                    aimTimer = AIM_TIME;
                }
            }
            case SEEK -> {
                if (dist > ATTACK_RANGE_SQ) {
                    mob.getNavigation().moveTo(target, 1.1D);
                } else {
                    mob.getNavigation().stop();
                    state = State.AIM;
                    aimTimer = AIM_TIME;
                }
            }
            case AIM -> {
                mob.lookAt(target, 30.0f, 30.0f);
                aimTimer--;
                if (aimTimer <= 0) {
                    // We do not perform shooting here — the injected JEG GunAttackGoal should handle firing.
                    cooldownTimer = COOLDOWN_TIME;
                    state = State.COOLDOWN;
                }
            }
            case COOLDOWN -> {
                cooldownTimer--;
                if (cooldownTimer <= 0) {
                    // Ready to aim again
                    state = State.AIM;
                    aimTimer = AIM_TIME;
                }
            }
        }
    }
}