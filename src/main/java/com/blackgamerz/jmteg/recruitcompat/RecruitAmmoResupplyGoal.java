package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import com.blackgamerz.jmteg.jegcompat.Gun;
import com.blackgamerz.jmteg.jegcompat.GunAmmoResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;

/**
 * AI Goal that automates ammo resupply for recruits carrying JEG guns.
 *
 * <p>Activates whenever the recruit's held gun reports {@code AmmoCount == 0}.
 * Four ordered strategies are attempted each resupply cycle:
 * <ol>
 *   <li><b>Own inventory</b>  – pull matching ammo from the recruit's own
 *       inventory slots (delegates to {@link AmmoConsumptionHandler}).</li>
 *   <li><b>Ally sharing</b>   – request surplus ammo from a nearby allied
 *       recruit; the donor keeps at least half of their stack.</li>
 *   <li><b>Chest / crate</b>  – navigate to the nearest {@link Container}
 *       block (chest, barrel, etc.) within range that holds matching ammo,
 *       then pull from it on arrival.</li>
 *   <li><b>Weapon switch</b>  – equip any other loaded JEG gun found in the
 *       recruit's own inventory as a backup weapon.</li>
 * </ol>
 *
 * <p>This goal is registered at <b>priority 0</b> so it preempts the attack
 * goal (priority 1) only when the gun is empty, and yields immediately once
 * a loaded weapon is available.  It holds only the {@link Flag#MOVE} flag,
 * leaving TARGET and LOOK free so the recruit keeps facing enemies while
 * searching for ammo.
 *
 * <p>All inventory access is reflection-safe; the goal degrades silently if
 * the Recruits mod or JEG is absent.
 */
public class RecruitAmmoResupplyGoal extends Goal {

    private static final Logger LOGGER = LogManager.getLogger("JMTEG-AmmoResupply");

    // ── Tuning constants ──────────────────────────────────────────────────────

    /** Ticks between full resupply scan attempts while idle (no chest navigation). */
    private static final int RESUPPLY_CHECK_INTERVAL = 40;

    /** Maximum horizontal/vertical search radius (blocks) when looking for containers. */
    private static final double CHEST_SEARCH_RADIUS = 16.0;
    /** Vertical half-height of the container search box (blocks above/below recruit). */
    private static final int    CHEST_SEARCH_HALF_HEIGHT = 3;

    /** Search radius (blocks) for nearby allied recruits to request ammo from. */
    private static final double ALLY_SEARCH_RADIUS = 12.0;

    /** Squared block distance threshold at which the recruit is considered "at" the chest. */
    private static final double ARRIVE_THRESHOLD_SQ = 9.0; // ≈ 3 blocks

    /** Navigation speed when walking to a supply chest. */
    private static final double CHEST_NAVIGATE_SPEED = 1.0;

    // ── State ─────────────────────────────────────────────────────────────────

    private final PathfinderMob mob;

    private enum Phase { IDLE, MOVING_TO_CHEST }

    private Phase    phase       = Phase.IDLE;
    private BlockPos targetChest = null;
    private int      idleTimer   = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RecruitAmmoResupplyGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return isHeldGunOutOfAmmo();
    }

    @Override
    public boolean canContinueToUse() {
        // Keep navigating even if the gun was somehow topped up mid-path; stop cleanly
        if (phase == Phase.MOVING_TO_CHEST) return true;
        return isHeldGunOutOfAmmo();
    }

    @Override
    public void start() {
        phase       = Phase.IDLE;
        targetChest = null;
        idleTimer   = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        phase       = Phase.IDLE;
        targetChest = null;
    }

    @Override
    public void tick() {
        if (phase == Phase.MOVING_TO_CHEST) {
            tickMoveToChest();
            return;
        }

        // Throttle idle checks to avoid per-tick overhead
        if (--idleTimer > 0) return;
        idleTimer = RESUPPLY_CHECK_INTERVAL;

        ItemStack gunStack = mob.getMainHandItem();
        if (!isOutOfAmmo(gunStack)) return;

        // Strategy 1 – pull from own inventory
        if (AmmoConsumptionHandler.tryReloadFromInventory(mob, gunStack)) {
            LOGGER.debug("{} reloaded from own inventory", mob);
            return;
        }

        // Strategy 2 – receive ammo from a nearby allied recruit
        if (tryReceiveFromAlly(gunStack)) {
            LOGGER.debug("{} received ammo from allied recruit", mob);
            return;
        }

        // Strategy 3 – navigate to the nearest supply chest
        BlockPos chest = findNearbyChestWithAmmo(gunStack);
        if (chest != null) {
            LOGGER.debug("{} heading to supply chest at {}", mob, chest);
            targetChest = chest;
            phase       = Phase.MOVING_TO_CHEST;
            return;
        }

        // Strategy 4 – switch to a loaded backup weapon
        if (trySwitchBackupWeapon()) {
            LOGGER.debug("{} switched to backup weapon", mob);
        }
    }

    // ── Phase: navigate to chest ──────────────────────────────────────────────

    private void tickMoveToChest() {
        if (targetChest == null) {
            phase = Phase.IDLE;
            return;
        }

        double distSq = mob.blockPosition().distSqr(targetChest);
        if (distSq <= ARRIVE_THRESHOLD_SQ) {
            // Arrived — pull ammo then return to idle
            tryPullFromChest(targetChest, mob.getMainHandItem());
            mob.getNavigation().stop();
            phase       = Phase.IDLE;
            targetChest = null;
        } else {
            mob.getNavigation().moveTo(
                    targetChest.getX() + 0.5,
                    targetChest.getY(),
                    targetChest.getZ() + 0.5,
                    CHEST_NAVIGATE_SPEED);
        }
    }

    private void tryPullFromChest(BlockPos pos, ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty()) return;
        try {
            BlockEntity be = mob.level().getBlockEntity(pos);
            if (!(be instanceof Container container)) return;

            int maxAmmo = AmmoConsumptionHandler.getMaxAmmo(gunStack);
            int curAmmo = Gun.from(gunStack).getAmmoCount();
            int needed  = Math.max(0, maxAmmo - curAmmo);
            if (needed <= 0) return;

            int taken = 0;
            for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
                ItemStack slot = container.getItem(i);
                if (slot.isEmpty() || !GunAmmoResolver.isAmmoForGun(slot, gunStack)) continue;
                int take = Math.min(needed, slot.getCount());
                slot.shrink(take);
                container.setItem(i, slot);
                taken  += take;
                needed -= take;
            }
            if (taken > 0) {
                gunStack.getOrCreateTag().putInt("AmmoCount", Math.min(curAmmo + taken, maxAmmo));
                LOGGER.debug("{} pulled {} ammo from chest at {}, AmmoCount now {}",
                        mob, taken, pos, curAmmo + taken);
            }
        } catch (Throwable t) {
            LOGGER.debug("tryPullFromChest failed at {}: {}", pos, t.toString());
        }
    }

    // ── Ammo state helpers ────────────────────────────────────────────────────

    private boolean isHeldGunOutOfAmmo() {
        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) return false;
        if (!JustEnoughGunsCompat.isJegGun(main)) return false;
        return isOutOfAmmo(main);
    }

    private static boolean isOutOfAmmo(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty()) return false;
        Gun gun = Gun.from(gunStack);
        if (gun.getIgnoreAmmo()) return false;
        return gun.getAmmoCount() <= 0;
    }

    // ── Strategy 2: ally sharing ──────────────────────────────────────────────

    private boolean tryReceiveFromAlly(ItemStack gunStack) {
        List<PathfinderMob> allies = mob.level().getEntitiesOfClass(
                PathfinderMob.class,
                mob.getBoundingBox().inflate(ALLY_SEARCH_RADIUS),
                e -> e != mob && e.isAlive() && mob.isAlliedTo(e));

        for (PathfinderMob ally : allies) {
            if (transferAmmoFromAlly(ally, gunStack)) return true;
        }
        return false;
    }

    private boolean transferAmmoFromAlly(PathfinderMob ally, ItemStack gunStack) {
        try {
            Object allyInv = ReflectionCache.tryGetInventoryObject(ally);
            if (allyInv == null) return false;

            Method getSize = allyInv.getClass().getMethod("getContainerSize");
            Method getItem = allyInv.getClass().getMethod("getItem", int.class);

            int size    = (int) getSize.invoke(allyInv);
            int maxAmmo = AmmoConsumptionHandler.getMaxAmmo(gunStack);
            int curAmmo = Gun.from(gunStack).getAmmoCount();
            int needed  = Math.max(0, maxAmmo - curAmmo);
            if (needed <= 0) return false;

            for (int i = 0; i < size; i++) {
                ItemStack candidate = (ItemStack) getItem.invoke(allyInv, i);
                if (candidate.isEmpty()) continue;
                if (!GunAmmoResolver.isAmmoForGun(candidate, gunStack)) continue;

                // Never drain an ally completely — take at most half their stack
                int available = candidate.getCount() / 2;
                if (available <= 0) continue;

                int take = Math.min(needed, available);
                candidate.shrink(take);
                ReflectionCache.tryWriteBackInventoryItem(allyInv, i, candidate);
                gunStack.getOrCreateTag().putInt("AmmoCount", Math.min(curAmmo + take, maxAmmo));
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ── Strategy 3: chest search ──────────────────────────────────────────────

    private BlockPos findNearbyChestWithAmmo(ItemStack gunStack) {
        BlockPos center = mob.blockPosition();
        int r = (int) CHEST_SEARCH_RADIUS;
        BlockPos nearest     = null;
        double   nearestDist = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -CHEST_SEARCH_HALF_HEIGHT; dy <= CHEST_SEARCH_HALF_HEIGHT; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    try {
                        BlockEntity be = mob.level().getBlockEntity(pos);
                        if (!(be instanceof Container container)) continue;
                        if (!containerHasAmmoForGun(container, gunStack)) continue;
                        double distSq = center.distSqr(pos);
                        if (distSq < nearestDist) {
                            nearestDist = distSq;
                            nearest     = pos;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return nearest;
    }

    private static boolean containerHasAmmoForGun(Container container, ItemStack gunStack) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && GunAmmoResolver.isAmmoForGun(slot, gunStack)) return true;
        }
        return false;
    }

    // ── Strategy 4: backup weapon switch ─────────────────────────────────────

    /**
     * Scans the recruit's inventory for any loaded JEG gun and swaps it into
     * the main hand, storing the empty gun back in the vacated inventory slot.
     *
     * <p>Triggers {@link EquipmentChangeHandler} automatically via
     * {@code mob.setItemSlot}, which in turn calls
     * {@link RecruitGoalOverrideHandler#forceReevaluate} to update goal state.
     *
     * @return {@code true} if a loaded backup gun was equipped
     */
    private boolean trySwitchBackupWeapon() {
        try {
            Object inv = ReflectionCache.tryGetInventoryObject(mob);
            if (inv == null) return false;

            Method getSize = inv.getClass().getMethod("getContainerSize");
            Method getItem = inv.getClass().getMethod("getItem", int.class);

            int size = (int) getSize.invoke(inv);
            for (int i = 0; i < size; i++) {
                ItemStack candidate = (ItemStack) getItem.invoke(inv, i);
                if (candidate.isEmpty()) continue;
                if (!JustEnoughGunsCompat.isJegGun(candidate)) continue;

                Gun candidateGun = Gun.from(candidate);
                // Accept guns with ammo or with IgnoreAmmo flag (e.g. creative test guns)
                if (!candidateGun.getIgnoreAmmo() && candidateGun.getAmmoCount() <= 0) continue;

                // Swap: stash empty gun in the inventory slot, equip loaded gun
                ItemStack emptyGun = mob.getMainHandItem().copy();
                mob.setItemSlot(EquipmentSlot.MAINHAND, candidate.copy());
                ReflectionCache.tryWriteBackInventoryItem(inv, i, emptyGun);
                LOGGER.debug("{} switched to backup weapon from inventory slot {}", mob, i);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
