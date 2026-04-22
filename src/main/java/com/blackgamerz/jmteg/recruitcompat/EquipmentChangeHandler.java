package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.jegcompat.JustEnoughGunsCompat;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debounced equipment-change listener that triggers sanitization and fallback install.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EquipmentChangeHandler {
    private static final Logger LOGGER = LogManager.getLogger("JMT-EquipmentChangeHandler");
    private static final Map<UUID, Long> lastHandled = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 250L;

    private EquipmentChangeHandler() {}

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getEntity() == null || event.getEntity().level().isClientSide) return;
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof PathfinderMob mob)) return;

        ItemStack to = event.getTo();
        boolean isJeg = to != null && !to.isEmpty() && JustEnoughGunsCompat.isJegGun(to);
        if (!isJeg) return;

        UUID id = mob.getUUID();
        long now = System.currentTimeMillis();
        Long last = lastHandled.get(id);
        if (last != null && now - last < COOLDOWN_MS) return;
        lastHandled.put(id, now);

        try { EntityWeaponSanitizer.sanitize(mob); } catch (Throwable t) { LOGGER.debug("sanitize failed", t); }
        try { RecruitGoalOverrideHandler.replaceGoalsWithFallback(mob); } catch (Throwable t) { LOGGER.debug("replace failed", t); }

        DeferredTaskScheduler.schedule(() -> {
            try { EntityWeaponSanitizer.sanitize(mob); RecruitGoalOverrideHandler.replaceGoalsWithFallback(mob); } catch (Throwable t) { LOGGER.debug("deferred ops failed", t); }
        }, 4);
    }
}