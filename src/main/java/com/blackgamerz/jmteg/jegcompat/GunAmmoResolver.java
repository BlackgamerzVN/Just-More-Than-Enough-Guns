package com.blackgamerz.jmteg.jegcompat;

import com.blackgamerz.jmteg.jegcompat.coreJEG.Gun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

import static com.blackgamerz.jmteg.jegcompat.GunDataManager.getAmmoTypeFromJson;

/**
 * Utility for resolving which ammo a JEG gun requires for reloads.
 */
public class GunAmmoResolver {

    /**
     * Given a Gun object, returns the ResourceLocation of the required ammo.
     * Returns null if not found/invalid.
     */
    public static ResourceLocation resolveRequiredAmmoId(ItemStack stack) {
        // (1) Try reading from NBT first
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("Gun", 10)) {
            CompoundTag gunTag = tag.getCompound("Gun");
            if (gunTag.contains("Projectile", 10)) {
                CompoundTag projTag = gunTag.getCompound("Projectile");
                if (projTag.contains("item", 8)) {
                    String projectileItem = projTag.getString("item");
                    ResourceLocation rl = ResourceLocation.tryParse(projectileItem);
                    if (rl != null) return rl;
                }
            }
            if (gunTag.contains("Reloads", 10)) {
                CompoundTag reloadTag = gunTag.getCompound("Reloads");
                if (reloadTag.contains("ReloadItem", 8)) {
                    String reloadItem = reloadTag.getString("ReloadItem");
                    ResourceLocation rl = ResourceLocation.tryParse(reloadItem);
                    if (rl != null) return rl;
                }
            }
        }
        // (2) Fallback: consult JSON/config by item id
        String ammoString = getAmmoTypeFromJson(stack); // Write this to parse JSON
        return ammoString != null ? ResourceLocation.tryParse(ammoString) : null;
    }

    /**
     * Given an ItemStack (possibly a gun), returns the required ammo's ResourceLocation.
     * (Safe for non-guns—will return null.)
     */

    /**
     * Matches if the given ItemStack is the correct ammo for the provided gun.
     */
    public static boolean isAmmoForGun(ItemStack ammoCandidate, Gun gun) {
        ResourceLocation required = resolveRequiredAmmoId(gun.stack);
        if (required == null || ammoCandidate == null) return false;
        ResourceLocation actualAmmoId =
                ForgeRegistries.ITEMS.getKey(ammoCandidate.getItem());
        return required.equals(actualAmmoId);
    }

    public static boolean isAmmoForGun(ItemStack ammoCandidate, ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty() || !Gun.isJegGun(gunStack)) return false;
        Gun gun = Gun.from(gunStack);
        return isAmmoForGun(ammoCandidate, gun);
    }
}