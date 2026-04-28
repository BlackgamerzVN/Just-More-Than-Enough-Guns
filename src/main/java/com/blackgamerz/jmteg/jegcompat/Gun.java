package com.blackgamerz.jmteg.jegcompat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

public class Gun {
    public final ItemStack stack;
    private final CompoundTag tag, gunTag, reloadTag;
    public General getGeneral() { return new General(); }
    public Projectile getProjectile() { return new Projectile(); }
    public static class General {
        public int getProjectileAmount() { return 1; }
        public float getSpread() { return 1.0F; }
        public boolean isAlwaysSpread() { return false; }
    }
    public static class Projectile {
        public float getSpeed() { return 1.0F; }
        public boolean isVisible() { return true; }
        public boolean hideTrail() { return false; }
        public Object getItem() { return null; }
    }
    public static double getAdditionalDamage(Object weapon) { return 0; }

    public Gun(ItemStack stack) {
        this.stack = stack == null ? ItemStack.EMPTY : stack;
        this.tag = stack != null && stack.hasTag() ? stack.getTag() : new CompoundTag();
        this.gunTag = tag.contains("Gun", 10) ? tag.getCompound("Gun") : new CompoundTag();
        this.reloadTag = gunTag.contains("Reloads", 10) ? gunTag.getCompound("Reloads") : new CompoundTag();
    }

    /** Makes a Gun object from any stack (returns safe dummy if invalid) */
    public static Gun from(ItemStack stack) { return new Gun(stack); }

    /** Returns the max ammo (magazine/tube) for this gun; defaults to 1 */
    public int getMaxAmmo() {
        return reloadTag.getInt("MaxAmmo") > 0
                ? reloadTag.getInt("MaxAmmo")
                : 1; // Assume single-shot if missing
    }

    /** How many units are loaded per reload cycle ("ReloadAmount"); defaults: MaxAmmo for mags, 1 for shells */
    public int getReloadAmount() {
        String type = getReloadType();
        if (type.equals("magazine")) return getMaxAmmo();
        return reloadTag.getInt("ReloadAmount") > 0 ? reloadTag.getInt("ReloadAmount") : 1;
    }

    /** Reload type: "magazine", "shell", "tube", etc. Defaults to "magazine" */
    public String getReloadType() {
        return reloadTag.contains("ReloadType", 8) ? reloadTag.getString("ReloadType") : "magazine";
    }

    /** Current ammo in magazine/tube (top-level "AmmoCount"); defaults to 0 if absent */
    public int getAmmoCount() {
        return tag.contains("AmmoCount", 99) ? tag.getInt("AmmoCount") : 0;
    }

    /** Writes AmmoCount into gun's ItemStack (no clamping, call with valid value!) */
    public void setAmmoCount(int count) {
        stack.getOrCreateTag().putInt("AmmoCount", count);
    }

    /** Some creative/test guns will ignore ammo checks (IgnoreAmmo=true) */
    public boolean getIgnoreAmmo() {
        return tag.contains("IgnoreAmmo", 1) && tag.getBoolean("IgnoreAmmo");
    }

    /** Heuristic: is this a JEG gun-like stack */
    public static boolean isJegGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && (tag.contains("GunId", 8) || tag.contains("Gun", 10));
    }


}