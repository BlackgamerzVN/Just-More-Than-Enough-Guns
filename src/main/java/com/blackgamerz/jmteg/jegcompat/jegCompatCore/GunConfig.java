package com.blackgamerz.jmteg.jegcompat.jegCompatCore;

import net.minecraft.resources.ResourceLocation;

/**
 * Minimal config describing how a gun's reload should interact with a mob ammo pool.
 * Extend this or load instances from JSON to support many guns.
 */
public final class GunConfig {
    public enum ReloadKind { SINGLE_ITEM, PROJECTILE_OR_MAG }

    public final ResourceLocation itemId; // gun item registry name, e.g. "jeg:bolt_action_rifle"
    public final int maxAmmo;             // magazine size
    public final ReloadKind reloadKind;   // SINGLE_ITEM means uses a reload item, else consumes projectiles from pool
    public final ResourceLocation poolId; // ResourceLocation used for mob ammo pool (reload item id or projectile id)

    public GunConfig(ResourceLocation itemId, int maxAmmo, ReloadKind reloadKind, ResourceLocation poolId) {
        this.itemId = itemId;
        this.maxAmmo = maxAmmo;
        this.reloadKind = reloadKind;
        this.poolId = poolId;
    }
}