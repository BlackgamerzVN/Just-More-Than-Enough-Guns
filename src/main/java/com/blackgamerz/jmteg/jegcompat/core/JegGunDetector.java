package com.blackgamerz.jmteg.jegcompat.core;

import com.blackgamerz.jmteg.compat.ReflectionCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reflection-based detection of JEG {@code Gun} properties from an {@link ItemStack},
 * with no compile-time dependency on the JEG mod. Extracted from {@link MobAiInjector}
 * so gun-data detection has a single, focused home.
 */
public final class JegGunDetector {

    private static final Logger LOGGER = LogManager.getLogger("jmteg");

    private JegGunDetector() {}

    /** Minimal holder describing a JEG gun's ammo pool, capacity, and reload kind. */
    public static final class DetectedGun {
        public final ResourceLocation poolId;
        public final int maxAmmo;
        public final GunConfig.ReloadKind kind;

        DetectedGun(ResourceLocation poolId, int maxAmmo, GunConfig.ReloadKind kind) {
            this.poolId = poolId;
            this.maxAmmo = maxAmmo;
            this.kind = kind;
        }
    }

    /** Reflection-based detection of JEG Gun properties (no compile-time dependency). */
    public static Optional<DetectedGun> detect(ItemStack stack) {
        try {
            Class<?> gunItemClass = ReflectionCache.getJegGunItemClass();
            if (gunItemClass == null) return Optional.empty();
            Object itemObj = stack.getItem();
            if (!gunItemClass.isInstance(itemObj)) return Optional.empty();

            Method getModifiedGun = ReflectionCache.findMethod(gunItemClass, "getModifiedGun", ItemStack.class);
            if (getModifiedGun == null) return Optional.empty();
            Object gunObj = getModifiedGun.invoke(itemObj, stack);
            if (gunObj == null) return Optional.empty();

            Method getReloads = ReflectionCache.findMethod(gunObj.getClass(), "getReloads");
            if (getReloads == null) return Optional.empty();
            Object reloadsObj = getReloads.invoke(gunObj);
            if (reloadsObj == null) return Optional.empty();

            Method getReloadType = ReflectionCache.findMethod(reloadsObj.getClass(), "getReloadType");
            Object reloadTypeObj = getReloadType != null ? getReloadType.invoke(reloadsObj) : null;
            boolean isSingleItem = false;
            if (reloadTypeObj != null) {
                Method nameMethod = ReflectionCache.findMethod(reloadTypeObj.getClass(), "name");
                if (nameMethod != null) {
                    String name = (String) nameMethod.invoke(reloadTypeObj);
                    isSingleItem = "SINGLE_ITEM".equals(name);
                } else {
                    isSingleItem = "SINGLE_ITEM".equals(reloadTypeObj.toString());
                }
            }

            ResourceLocation poolId = null;
            int maxAmmo = 0;
            GunConfig.ReloadKind kind;

            if (isSingleItem) {
                Method getReloadItem = ReflectionCache.findMethod(reloadsObj.getClass(), "getReloadItem");
                Object reloadItemObj = getReloadItem != null ? getReloadItem.invoke(reloadsObj) : null;
                if (reloadItemObj instanceof ResourceLocation rl) {
                    poolId = rl;
                } else if (reloadItemObj != null) {
                    poolId = ResourceLocation.tryParse(reloadItemObj.toString());
                }
                kind = GunConfig.ReloadKind.SINGLE_ITEM;
            } else {
                Method getProjectile = ReflectionCache.findMethod(gunObj.getClass(), "getProjectile");
                Object projectileObj = getProjectile != null ? getProjectile.invoke(gunObj) : null;
                if (projectileObj != null) {
                    Method getItem = ReflectionCache.findMethod(projectileObj.getClass(), "getItem");
                    Object itemIdObj = getItem != null ? getItem.invoke(projectileObj) : null;
                    if (itemIdObj instanceof ResourceLocation rl) {
                        poolId = rl;
                    } else if (itemIdObj != null) {
                        poolId = ResourceLocation.tryParse(itemIdObj.toString());
                    }
                }
                kind = GunConfig.ReloadKind.PROJECTILE_OR_MAG;
            }

            Method getMaxAmmo = ReflectionCache.findMethod(reloadsObj.getClass(), "getMaxAmmo");
            if (getMaxAmmo != null) {
                Object maxAmmoObj = getMaxAmmo.invoke(reloadsObj);
                if (maxAmmoObj instanceof Number n) {
                    maxAmmo = n.intValue();
                } else if (maxAmmoObj != null) {
                    maxAmmo = Integer.parseInt(maxAmmoObj.toString());
                }
            }

            if (poolId == null) return Optional.empty();
            return Optional.of(new DetectedGun(poolId, Math.max(0, maxAmmo), kind));
        } catch (Throwable t) {
            LOGGER.debug("detectJegGunData failed", t);
            return Optional.empty();
        }
    }

    public static int getAmmoCountFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.getInt("AmmoCount") : 0;
    }

    /** Minimal helper to create a {@link GunConfig} for goals that need one. */
    public static GunConfig makeGunConfig(ResourceLocation itemKey, int maxAmmo, GunConfig.ReloadKind kind, ResourceLocation poolId) {
        if (itemKey == null) itemKey = ResourceLocation.tryParse("unknown:unknown");
        return new GunConfig(itemKey, maxAmmo, kind, poolId);
    }
}
