package com.blackgamerz.jmteg.jegcompat.jegCompatCore;

import com.blackgamerz.jmteg.jegcompat.jegCompatCore.MobAmmoHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Goal that keeps a mob's weapon AmmoCount in sync with a mob ammo pool (MobAmmoHelper).
 * - Detects increases in AmmoCount (someone else reloaded) and consumes from the pool and inventory.
 * - When AmmoCount is 0 (or low) attempts to reload using mob pool.
 */
public class GunSyncGoal extends Goal {
    private final PathfinderMob mob;
    private final GunConfig config;
    private int prevAmmoCount = -1;

    /**
     * Lazy-resolved reload delay in ticks.
     * {@code -2} = not yet determined, {@code 0} = instant reload (no delay),
     * {@code > 0} = reload duration from {@link GunConfig#reloadTimeTicks} or JEG reflection.
     */
    private int cachedReloadTime = -2;

    public GunSyncGoal(PathfinderMob mob, GunConfig config) {
        this.mob = mob;
        this.config = config;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() != null && isHoldingConfiguredGun();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        prevAmmoCount = getCurrentAmmoCount();
    }

    @Override
    public void tick() {
        ItemStack stack = mob.getMainHandItem();
        if (stack == null || stack.isEmpty()) {
            prevAmmoCount = 0;
            return;
        }

        CompoundTag tag = stack.getTag();
        if (tag == null) tag = stack.getOrCreateTag();

        int cur = tag.getInt("AmmoCount");
        if (prevAmmoCount < 0) prevAmmoCount = cur;

        // Case A: weapon was reloaded by other AI (e.g., Recruit). Consume inventory first, then pool for the added ammo.
        if (cur > prevAmmoCount) {
            int delta = cur - prevAmmoCount;

            // Try to consume from inventory first (inventory-first semantics).
            int consumedFromInv = 0;
            try {
                consumedFromInv = MobAiInjector.removeAmmoFromInventory(mob, config.poolId, delta);
            } catch (Throwable t) {
                // defensive: if reflection helper fails, fallback to pool-only below
            }

            int remaining = delta - consumedFromInv;
            int consumedFromPool = 0;
            if (remaining > 0) {
                consumedFromPool = MobAmmoHelper.consumeAmmo(mob, config.poolId, remaining);
            }

            int totalConsumed = consumedFromInv + consumedFromPool;
            if (totalConsumed < delta) {
                // Pool + inventory lacked enough ammo: lower the magazine to what we could consume
                tag.putInt("AmmoCount", prevAmmoCount + totalConsumed);
                cur = prevAmmoCount + totalConsumed;
            }
            // Clear any pending reload timer since ammo just increased externally.
            tag.remove("jmteg_reload_at");
        }

        // Case B: weapon is empty (or low) -> attempt to reload using mob pool.
        // A reload-delay timer ("jmteg_reload_at") prevents instant refill so the
        // NPC experiences the same reload duration as a player using the same gun.
        if (cur <= 0) {
            long gameTime = mob.level().getGameTime();

            if (!tag.contains("jmteg_reload_at")) {
                // Gun just ran dry — resolve the reload delay once and cache it.
                if (cachedReloadTime == -2) {
                    int delay = config.reloadTimeTicks;
                    if (delay <= 0) {
                        // Try JEG reflection once; store the result (even if -1) so we don't retry.
                        try {
                            int reflectedTime = com.blackgamerz.jmteg.recruitcompat.JustEnoughGunsCompat.getJegGunReloadTime(stack);
                            delay = Math.max(reflectedTime, 0);
                        } catch (Throwable ignored) {}
                    }
                    cachedReloadTime = delay; // 0 = instant, > 0 = delayed
                }
                if (cachedReloadTime > 0) {
                    tag.putLong("jmteg_reload_at", gameTime + cachedReloadTime);
                    prevAmmoCount = cur;
                    return; // wait — do not fill ammo yet
                }
                // cachedReloadTime == 0: instant reload (legacy behaviour)
            } else {
                long readyAt = tag.getLong("jmteg_reload_at");
                if (gameTime < readyAt) {
                    // Reload still in progress — don't fill yet.
                    prevAmmoCount = cur;
                    return;
                }
                // Timer has expired — clear it and proceed with the actual fill.
                tag.remove("jmteg_reload_at");
            }

            // ── Actual ammo fill ──
            if (config.reloadKind == GunConfig.ReloadKind.SINGLE_ITEM) {
                // consume 1 reload item to fill to max
                int consumed = MobAmmoHelper.consumeAmmo(mob, config.poolId, 1);
                if (consumed > 0) {
                    tag.putInt("AmmoCount", config.maxAmmo);
                    cur = config.maxAmmo;
                }
            } else {
                int needed = Math.max(0, config.maxAmmo - cur);
                int consumed = MobAmmoHelper.consumeAmmo(mob, config.poolId, needed);
                if (consumed > 0) {
                    tag.putInt("AmmoCount", cur + consumed);
                    cur = cur + consumed;
                }
            }
        } else {
            // Ammo is available — clear any stale reload timer.
            if (tag.contains("jmteg_reload_at")) {
                tag.remove("jmteg_reload_at");
            }
        }

        prevAmmoCount = cur;
    }

    private boolean isHoldingConfiguredGun() {
        ItemStack stack = mob.getMainHandItem();
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) return false;
        return key.equals(config.itemId);
    }

    private int getCurrentAmmoCount() {
        ItemStack stack = mob.getMainHandItem();
        if (stack == null || stack.isEmpty()) return 0;
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.getInt("AmmoCount") : 0;
    }
}