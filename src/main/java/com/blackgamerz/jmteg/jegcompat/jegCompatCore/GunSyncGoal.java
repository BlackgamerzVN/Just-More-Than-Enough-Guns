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
 * - Detects increases in AmmoCount (someone else reloaded) and consumes from the pool.
 * - When AmmoCount is 0 (or low) attempts to reload using mob pool.
 */
public class GunSyncGoal extends Goal {
    private final PathfinderMob mob;
    private final GunConfig config;
    private int prevAmmoCount = -1;

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

        // Case A: weapon was reloaded by other AI (e.g., Recruit). Consume pool for the added ammo.
        if (cur > prevAmmoCount) {
            int delta = cur - prevAmmoCount;
            int consumed = MobAmmoHelper.consumeAmmo(mob, config.poolId, delta);
            if (consumed < delta) {
                // Pool lacked enough ammo: lower the magazine to what we could consume
                tag.putInt("AmmoCount", prevAmmoCount + consumed);
                cur = prevAmmoCount + consumed;
            }
        }

        // Case B: weapon is empty (or low) -> attempt to reload using mob pool
        if (cur <= 0) {
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