package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.jegcompat.JustEnoughGunsCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumSet;

/**
 * Deterministic fallback ranged goal that enforces internal ammo stored in ItemStack NBT
 * and consumes real ammo from mob inventory at reload time.
 */
public class RecruitRangedGunnerAttackGoal extends Goal {
    private static final Logger LOGGER = LogManager.getLogger("JMT-RecruitGunnerGoal");

    private final PathfinderMob mob;
    private LivingEntity target;
    private int seeTime;
    private int reloadTicksRemaining;
    private State state = State.IDLE;

    private static final String INTERNAL_AMMO_KEY = "jmteg_internal_ammo";
    private static final int DEFAULT_INTERNAL_AMMO = 6;
    private static final int RELOAD_TICKS = 40; // tune as needed
    private static final int AIM_TICKS = 10;
    private static final double ARROW_SPEED = 1.6F;
    private static final float ARROW_INACCURACY = 14.0F;

    public RecruitRangedGunnerAttackGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) return false;
        return JustEnoughGunsCompat.isJegGun(mob.getMainHandItem());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.seeTime = 0;
        this.state = State.IDLE;
        this.reloadTicksRemaining = 0;
    }

    @Override
    public void stop() {
        this.state = State.IDLE;
        this.reloadTicksRemaining = 0;
        try {
            mob.stopUsingItem();
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        this.target = mob.getTarget();
        if (this.target == null || !this.target.isAlive()) return;

        boolean canSee = mob.getSensing().hasLineOfSight(target);
        if (canSee) this.seeTime++;
        else this.seeTime = 0;

        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) {
            this.state = State.IDLE;
            return;
        }

        switch (state) {
            case IDLE -> {
                int ammo = getOrInitInternalAmmo(main);
                if (ammo <= 0) {
                    // Only start reload if there is actual ammo in inventory (best-effort)
                    int available = JustEnoughGunsCompat.countAmmoInInventory(mob, main);
                    if (available <= 0) {
                        // no ammo available -> can't reload
                        LOGGER.debug("No ammo found in inventory for {} — skipping reload", mob);
                        state = State.IDLE;
                    } else {
                        state = State.RELOAD;
                        reloadTicksRemaining = RELOAD_TICKS;
                        try { mob.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
                    }
                } else {
                    state = State.AIM;
                }
            }

            case RELOAD -> {
                reloadTicksRemaining--;
                if (reloadTicksRemaining <= 0) {
                    try { mob.stopUsingItem(); } catch (Throwable ignored) {}
                    // Attempt to consume real ammo from inventory. Try to consume up to gun max.
                    Integer max = JustEnoughGunsCompat.getJegGunMaxAmmo(main);
                    int desired = (max != null && max > 0) ? Math.min(max, 30) : DEFAULT_INTERNAL_AMMO;
                    int consumed = JustEnoughGunsCompat.consumeAmmoFromInventory(mob, main, desired);
                    if (consumed > 0) {
                        // refill internal ammo to the amount consumed (or desired)
                        int refill = Math.min(desired, consumed);
                        setInternalAmmo(main, refill);
                        try { mob.setItemSlot(EquipmentSlot.MAINHAND, main); } catch (Throwable ignored) {}
                        LOGGER.debug("Reloaded {} ammo for {} (consumed {} items)", refill, mob, consumed);
                        state = State.AIM;
                    } else {
                        // nothing consumed: cannot reload — remain empty
                        setInternalAmmo(main, 0);
                        try { mob.setItemSlot(EquipmentSlot.MAINHAND, main); } catch (Throwable ignored) {}
                        LOGGER.debug("Reload attempted but no ammo consumed for {}", mob);
                        state = State.IDLE;
                    }
                }
            }

            case AIM -> {
                if (canSee && ++seeTime >= AIM_TICKS) {
                    seeTime = 0;
                    state = State.SHOOT;
                } else if (!canSee) {
                    if (seeTime <= -10) state = State.IDLE;
                }
                try { mob.getLookControl().setLookAt(target); } catch (Throwable ignored) {}
            }

            case SHOOT -> {
                int ammo = getOrInitInternalAmmo(main);
                if (ammo > 0) {
                    // decrement internal ammo and persist
                    setInternalAmmo(main, ammo - 1);
                    try { mob.setItemSlot(EquipmentSlot.MAINHAND, main); } catch (Throwable ignored) {}

                    // optionally call JEG consume for compatibility (best-effort)
                    try { JustEnoughGunsCompat.consumeAmmoOnGun(main); } catch (Throwable ignored) {}

                    // spawn a vanilla arrow as a simple visual projectile
                    try {
                        Arrow arrow = new Arrow(mob.level(), mob);
                        Vec3 eye = mob.getEyePosition(1.0F);
                        arrow.setPos(eye.x, eye.y, eye.z);
                        double dx = target.getX() - mob.getX();
                        double dy = target.getY(0.3333333333333333D) - arrow.getY();
                        double dz = target.getZ() - mob.getZ();
                        double dist = Math.sqrt(dx * dx + dz * dz);
                        arrow.shoot(dx, dy + dist * 0.20000000298023224D, dz, (float) ARROW_SPEED, ARROW_INACCURACY);
                        mob.level().addFreshEntity(arrow);
                        mob.playSound(SoundEvents.TRIDENT_THROW, 1.0F, 1.0F);
                    } catch (Throwable t) {
                        LOGGER.debug("Failed to spawn fallback projectile: {}", t.toString());
                    }
                }

                // after shooting decide next state
                int remaining = getOrInitInternalAmmo(main);
                if (remaining > 0) {
                    state = State.RELOAD; // short reload between shots
                    reloadTicksRemaining = RELOAD_TICKS;
                    try { mob.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
                } else {
                    state = State.IDLE;
                }
            }
        }
    }

    private int getOrInitInternalAmmo(ItemStack gun) {
        if (gun == null || gun.isEmpty()) return 0;
        try {
            CompoundTag tag = gun.getOrCreateTag();
            if (tag.contains(INTERNAL_AMMO_KEY)) {
                return tag.getInt(INTERNAL_AMMO_KEY);
            } else {
                Integer max = JustEnoughGunsCompat.getJegGunMaxAmmo(gun);
                int initial = (max != null && max > 0) ? Math.min(max, 30) : DEFAULT_INTERNAL_AMMO;
                tag.putInt(INTERNAL_AMMO_KEY, initial);
                return initial;
            }
        } catch (Throwable t) {
            return DEFAULT_INTERNAL_AMMO;
        }
    }

    private void setInternalAmmo(ItemStack gun, int val) {
        if (gun == null || gun.isEmpty()) return;
        try {
            CompoundTag tag = gun.getOrCreateTag();
            tag.putInt(INTERNAL_AMMO_KEY, Math.max(0, val));
        } catch (Throwable t) {
            // swallow
        }
    }

    private enum State {
        IDLE,
        RELOAD,
        AIM,
        SHOOT
    }
}