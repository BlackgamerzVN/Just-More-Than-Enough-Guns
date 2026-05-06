package com.blackgamerz.jmteg.jegcompat;

import com.blackgamerz.jmteg.Main;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.*;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = Main.MOD_ID)
public final class MobAiInjectorReflection {

    private static ResourceLocation rl(String ns, String path) {
        ResourceLocation parsed = ResourceLocation.tryParse(ns + ":" + path);
        if (parsed == null) throw new IllegalStateException("Invalid ResourceLocation: " + ns + ":" + path);
        return parsed;
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof PathfinderMob mob)) return;

        // Recruit entities are fully handled by RecruitGoalOverrideHandler + RecruitRangedGunnerAttackGoal.
        // Injecting a separate GunAttackGoal here would create a competing priority-0 goal that
        // conflicts with JMTEG's recruit attack goal (priority 1) and with the resupply goal (priority 0).
        // Match both the canonical Talhanation package and any subclassed/relocated variants.
        String fqcn = mob.getClass().getName();
        if (fqcn.contains("talhanation.recruits") || fqcn.contains(".recruits.")) return;

        try {
            // Try to load JEG classes. If any are missing, Class.forName will throw and we abort.
            Class<?> jegGunItemClass = Class.forName("ttv.migami.jeg.item.GunItem");
            Class<?> jegGunClass = Class.forName("ttv.migami.jeg.common.Gun");
            Class<?> jegReloadsClass = Class.forName("ttv.migami.jeg.common.Reloads"); // adjust name if different; or get via method return
            Class<?> jegReloadTypeClass = Class.forName("ttv.migami.jeg.common.ReloadType");
            Class<?> jegMobAmmoHelperClass = Class.forName("ttv.migami.jeg.common.MobAmmoHelper");
            Class<?> jegGunAttackGoalClass = Class.forName("ttv.migami.jeg.entity.ai.GunAttackGoal");
            Class<?> jegAITypeClass = Class.forName("ttv.migami.jeg.entity.ai.AIType");

            // Check main hand item is instance of JEG's GunItem
            ItemStack main = mob.getMainHandItem();
            Item item = main.getItem();
            if (!jegGunItemClass.isInstance(item)) {
                // Not a JEG gun item; nothing to do. Optionally, detect by registry name instead.
                return;
            }

            // getModifiedGun(ItemStack) -> Gun
            Method getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class);
            Object gunObj = getModifiedGun.invoke(item, main);
            if (gunObj == null) return;

            // reloads = gun.getReloads()
            Method getReloads = jegGunClass.getMethod("getReloads");
            Object reloadsObj = getReloads.invoke(gunObj);

            // reloadType = reloads.getReloadType()
            Method getReloadType = reloadsObj.getClass().getMethod("getReloadType");
            Object reloadType = getReloadType.invoke(reloadsObj);

            // maxAmmo = reloads.getMaxAmmo()
            int maxAmmo = 0;
            try {
                Method getMaxAmmo = reloadsObj.getClass().getMethod("getMaxAmmo");
                Object maxAmmoObj = getMaxAmmo.invoke(reloadsObj);
                if (maxAmmoObj instanceof Number) maxAmmo = ((Number) maxAmmoObj).intValue();
            } catch (NoSuchMethodException ex) {
                // try alternative method name if needed or bail
            }

            // Determine poolId ResourceLocation:
            // reloadsObj may be a ResourceLocation, an Item, or a String — handle each case safely
            ResourceLocation poolId = null;
            if (reloadsObj instanceof ResourceLocation rl) {
                poolId = rl;
            } else if (reloadsObj instanceof String s) {
                poolId = ResourceLocation.tryParse(s);
            } else {
                // Last-resort: call toString() then tryParse
                String txt = reloadsObj != null ? reloadsObj.toString() : null;
                poolId = txt != null ? ResourceLocation.tryParse(txt) : null;
            }

// Fallback: if reloadItemObj was an Item instance, try to get its registry key
            if (poolId == null && reloadsObj != null) {
                try {
                    if (reloadsObj instanceof net.minecraft.world.item.Item itemObj) {
                        poolId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(itemObj);
                    }
                } catch (Throwable ignored) {}
            }

            if (poolId == null) {
                // couldn't determine pool id — choose a sensible fallback or skip seeding
                // e.g., log and continue
            }

            // Seed mob pool if empty
            if (poolId != null) {
                Method getAmmoPool = jegMobAmmoHelperClass.getMethod("getAmmoPool", LivingEntity.class, ResourceLocation.class);
                Object poolVal = getAmmoPool.invoke(null, mob, poolId);
                int pool = (poolVal instanceof Number) ? ((Number) poolVal).intValue() : 0;
                if (pool == 0) {
                    int seedPool = mob.getRandom().nextInt(Math.max(1, Math.max(1, maxAmmo * 2)));
                    Method addAmmo = jegMobAmmoHelperClass.getMethod("addAmmo", LivingEntity.class, ResourceLocation.class, int.class);
                    addAmmo.invoke(null, mob, poolId, seedPool);
                }
            }

            // Instantiate GunAttackGoal: constructor (PathfinderMob, double, float, AIType, int)
            Constructor<?> ctor = jegGunAttackGoalClass.getConstructor(PathfinderMob.class, double.class, float.class, jegAITypeClass, int.class);
            // get AIType.TACTICAL enum value
            Object tacticalValue = null;
            for (Field f : jegAITypeClass.getFields()) {
                if (f.getName().equals("TACTICAL")) {
                    tacticalValue = f.get(null);
                    break;
                }
            }
            if (tacticalValue == null) {
                // fallback: use first enum constant
                Object[] enums = jegAITypeClass.getEnumConstants();
                if (enums != null && enums.length > 0) tacticalValue = enums[0];
            }

            Object goalInstance = ctor.newInstance(mob, 12.0d /* stopRange */, 1.0f /* speedModifier */, tacticalValue, 1 /* difficulty */);
            if (goalInstance instanceof Goal) {
                // add with priority 0 (highest)
                mob.goalSelector.addGoal(0, (Goal) goalInstance);
            } else {
                // If the loaded class is not assignable to Goal (weird classloader mismatch), try to invoke addGoal reflectively
                Method addGoalMethod = mob.goalSelector.getClass().getMethod("addGoal", int.class, Goal.class);
                addGoalMethod.invoke(mob.goalSelector, 0, goalInstance);
            }
        } catch (ClassNotFoundException e) {
            // JEG not present -> nothing to do
        } catch (InvocationTargetException | NoSuchMethodException | InstantiationException | IllegalAccessException | LinkageError e) {
            // Reflection failed; log for debugging
            e.printStackTrace();
        }
    }
}