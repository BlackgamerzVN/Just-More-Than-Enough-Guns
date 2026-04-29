package com.blackgamerz.jmteg.recruitcompat;// In your EquipmentChangeHandler class (or new handler)
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.entity.PathfinderMob;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EquipmentChangeHandler {
    @SubscribeEvent
    public static void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        if (event.getEntity() instanceof PathfinderMob mob) {
            // Guarantee the RecruitGoalOverrideHandler re-evaluates this mob immediately
            try {
                RecruitGoalOverrideHandler.forceReevaluate(mob);
            } catch (Throwable t) {
                // swallow but log if you want
            }
        }
    }
}