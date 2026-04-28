package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.goal.Goal;

import com.blackgamerz.jmteg.jegcompat.Gun;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;

import static com.blackgamerz.jmteg.jegcompat.GunAmmoResolver.resolveRequiredAmmoId;

public class RecruitRangedGunnerAttackGoal extends Goal {
    private final PathfinderMob mob;

    private enum State { IDLE, SEEK, AIM, SHOOT, COOLDOWN, RELOAD }
    private State state = State.IDLE;

    private static final double ATTACK_RANGE_SQ = 16.0 * 16.0;
    private static final int AIM_TIME = 20;
    private static final int COOLDOWN_TIME = 40;
    private static final int RELOAD_INTERVAL = 40;

    private int aimTimer = 0;
    private int cooldownTimer = 0;
    private int reloadTimer = 0;

    public RecruitRangedGunnerAttackGoal(PathfinderMob mob) {
        this.mob = mob;
    }

    @Override
    public EnumSet<Flag> getFlags() {
        return EnumSet.of(Flag.TARGET, Flag.MOVE, Flag.LOOK);
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && Gun.isJegGun(mob.getMainHandItem());
    }

    @Override
    public void start() {
        this.state = State.IDLE;
        aimTimer = 0;
        cooldownTimer = 0;
        reloadTimer = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        Gun gun = Gun.from(mob.getMainHandItem());
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
                } else if (hasAmmo(gun) && canSee(target)) {
                    state = State.AIM;
                    aimTimer = AIM_TIME;
                } else if (!hasAmmo(gun)) {
                    if (canReload(gun)) {
                        state = State.RELOAD;
                        reloadTimer = RELOAD_INTERVAL;
                    }
                }
            }
            case SEEK -> {
                if (dist > ATTACK_RANGE_SQ) {
                    mob.getNavigation().moveTo(target, 1.1D);
                } else {
                    mob.getNavigation().stop();
                    if (hasAmmo(gun)) {
                        state = State.AIM;
                        aimTimer = AIM_TIME;
                    } else if (canReload(gun)) {
                        state = State.RELOAD;
                        reloadTimer = RELOAD_INTERVAL;
                    } else {
                        state = State.IDLE;
                    }
                }
            }
            case AIM -> {
                mob.lookAt(target, 30.0f, 30.0f);
                aimTimer--;
                if (aimTimer <= 0) {
                    state = State.SHOOT;
                }
            }
            case SHOOT -> {
                handleShoot(gun, mob.getMainHandItem(), target);
                cooldownTimer = COOLDOWN_TIME;
                if (hasAmmo(gun)) {
                    state = State.COOLDOWN;
                } else if (canReload(gun)) {
                    state = State.RELOAD;
                    reloadTimer = RELOAD_INTERVAL;
                } else {
                    state = State.IDLE;
                }
            }
            case COOLDOWN -> {
                cooldownTimer--;
                if (cooldownTimer <= 0) {
                    if (hasAmmo(gun)) {
                        state = State.AIM;
                        aimTimer = AIM_TIME;
                    } else if (canReload(gun)) {
                        state = State.RELOAD;
                        reloadTimer = RELOAD_INTERVAL;
                    } else {
                        state = State.IDLE;
                    }
                }
            }
            case RELOAD -> {
                int required = Math.min(gun.getMaxAmmo() - gun.getAmmoCount(), gun.getReloadAmount());
                if (required <= 0) {
                    state = State.IDLE;
                    return;
                }
                if (getAvailableAmmo(gun) < required) {
                    // Not enough ammo for next reload step: WAIT in RELOAD state
                    return;
                }
                reloadTimer--;
                if (reloadTimer <= 0) {
                    doReload(gun);
                    if (hasAmmo(gun)) {
                        state = State.AIM;
                        aimTimer = AIM_TIME;
                    } else if (!canReload(gun)) {
                        state = State.IDLE;
                    } else {
                        reloadTimer = RELOAD_INTERVAL;
                    }
                }
            }
        }
    }

    // --- Helper methods ---

    private boolean hasAmmo(Gun gun) {
        if (gun.getIgnoreAmmo()) return true;
        return gun.getAmmoCount() > 0;
    }
    private boolean canSee(LivingEntity target) {
        return mob.getSensing().hasLineOfSight(target);
    }
    private boolean canReload(Gun gun) {
        if (gun.getIgnoreAmmo()) return false;
        int max = gun.getMaxAmmo();
        int cur = gun.getAmmoCount();
        int has = getAvailableAmmo(gun);
        int required = Math.min(max - cur, gun.getReloadAmount());
        return cur < max && has >= required && required > 0;
    }

    /**
     * Counts ammo in both hands and recruit inventory, for the gun's data-driven ammo type.
     */
    private int getAvailableAmmo(Gun gun) {
        ResourceLocation reloadId = resolveRequiredAmmoId(gun.stack);
        System.out.println("[RecruitAI] getAvailableAmmo for gun=" + gun + ": reloadId=" + reloadId);
        if (reloadId == null) return 0;
        int count = 0;

        for (ItemStack stack : new ItemStack[]{mob.getMainHandItem(), mob.getOffhandItem()}) {
            if (!stack.isEmpty() && stackMatchesAmmo(stack, reloadId)) {
                count += stack.getCount();
                System.out.println(" > Found stack: " + stack + ", registry: " + ForgeRegistries.ITEMS.getKey(stack.getItem()));
            }
        }

        // Reflection: check for recruit inventory (if present)
        try {
            Class<?> searchClass = mob.getClass();
            for (int i = 0; i < 2; i++) {
                try {
                    var method = searchClass.getMethod("getInventory");
                    Object container = method.invoke(mob);
                    if (container instanceof Container inv) {
                        for (int j = 0; j < inv.getContainerSize(); j++) {
                            ItemStack stack = inv.getItem(j);
                            if (!stack.isEmpty() && stackMatchesAmmo(stack, reloadId)) {
                                count += stack.getCount();
                            }
                        }
                    }
                    break; // Found, done!
                } catch (NoSuchMethodException e) {
                    searchClass = searchClass.getSuperclass();
                }
            }
        } catch (Throwable t) {
            // Silently ignore
        }
        return count;
    }

    /**
     * Uses registry id matching, works for all JEG guns/ammo.
     */
    private boolean stackMatchesAmmo(ItemStack stack, ResourceLocation reloadId) {
        ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        boolean matches = reloadId != null && reloadId.equals(rl);
        // Only match actual ammo, NOT the gun itself!
        return matches;
    }

    private void doReload(Gun gun) {
        int max = gun.getMaxAmmo();
        int cur = gun.getAmmoCount();
        int amt = Math.min(max - cur, getAvailableAmmo(gun));
        if (amt > 0 && consumeAmmo(gun, amt)) {
            gun.setAmmoCount(cur + amt);
        }
        System.out.println("doReload: max=" + max + ", cur=" + cur + ", available=" + getAvailableAmmo(gun));
    }
    private boolean consumeAmmo(Gun gun, int amount) {
        ResourceLocation reloadId = resolveRequiredAmmoId(gun.stack);
        if (reloadId == null) return false;
        int needed = amount;

        for (ItemStack stack : new ItemStack[]{mob.getMainHandItem(), mob.getOffhandItem()}) {
            if (!stack.isEmpty() && stackMatchesAmmo(stack, reloadId)) {
                int take = Math.min(stack.getCount(), needed);
                stack.shrink(take);
                needed -= take;
                if (needed <= 0) return true;
            }
        }

        try {
            Class<?> searchClass = mob.getClass();
            outer:
            for (int i = 0; i < 2; i++) {
                try {
                    var method = searchClass.getMethod("getInventory");
                    Object container = method.invoke(mob);
                    if (container instanceof Container inv) {
                        for (int j = 0; j < inv.getContainerSize(); j++) {
                            ItemStack stack = inv.getItem(j);
                            if (!stack.isEmpty() && stackMatchesAmmo(stack, reloadId)) {
                                int take = Math.min(stack.getCount(), needed);
                                stack.shrink(take);
                                needed -= take;
                                if (needed <= 0) break outer;
                            }
                        }
                    }
                    break;
                } catch (NoSuchMethodException e) {
                    searchClass = searchClass.getSuperclass();
                }
            }
        } catch (Throwable t) {}
        return needed <= 0;
    }

    /**
     * Calls the real gun fire logic. Modify this as needed for your mod.
     */
    private void handleShoot(Gun gun, ItemStack stack, LivingEntity target) {
        com.blackgamerz.jmteg.jegcompat.JEGCompatManager.INSTANCE
                .performGunAttack(mob, target, stack, gun, 1.0F, false);
        // (Optional) Play sounds/animation here if needed.
    }
}