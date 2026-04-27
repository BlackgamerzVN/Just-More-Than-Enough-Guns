package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;

public class AmmoConsumptionHandler {

    public static boolean tryReloadFromInventory(LivingEntity mob, ItemStack gunStack, Object gun) {
        // 1. Players: full inventory search
        if (mob instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) {
                if (isValidAmmo(stack, gun)) {
                    stack.shrink(1);
                    gunStack.getOrCreateTag().putInt("AmmoCount", getMaxAmmo(gun));
                    return true;
                }
            }
        }

        // 2. Recruits (using reflection for soft dependency)
        try {
            Class<?> recruitClass = Class.forName("com.talhanation.recruits.entities.RecruitEntity");
            if (recruitClass.isInstance(mob)) {
                Object inventory = recruitClass.getMethod("getInventory").invoke(mob);
                if (inventory != null) {
                    int size = (int) inventory.getClass().getMethod("getContainerSize").invoke(inventory);
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = (ItemStack) inventory.getClass().getMethod("getItem", int.class).invoke(inventory, i);
                        if (isValidAmmo(stack, gun)) {
                            stack.shrink(1);
                            gunStack.getOrCreateTag().putInt("AmmoCount", getMaxAmmo(gun));
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        // 3. Fallback: check mob's main/off hand
        ItemStack mainhand = mob.getMainHandItem();
        if (isValidAmmo(mainhand, gun)) {
            mainhand.shrink(1);
            gunStack.getOrCreateTag().putInt("AmmoCount", getMaxAmmo(gun));
            return true;
        }
        ItemStack offhand = mob.getOffhandItem();
        if (isValidAmmo(offhand, gun)) {
            offhand.shrink(1);
            gunStack.getOrCreateTag().putInt("AmmoCount", getMaxAmmo(gun));
            return true;
        }

        return false;
    }

    // Example: you must implement these according to your ammo/gun system!
    public static boolean isValidAmmo(ItemStack stack, Object gun) {
        // TODO: Replace with actual check (item type, tag, etc.)
        return !stack.isEmpty();
    }
    public static int getMaxAmmo(Object gun) {
        // TODO: Use reflection if necessary
        return 6; // Fallback
    }
}