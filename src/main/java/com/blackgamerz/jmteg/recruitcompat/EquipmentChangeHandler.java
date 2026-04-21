package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.compat.EntityWeaponSanitizer;
import com.blackgamerz.jmteg.jegcompat.JustEnoughGunsCompat;
import com.blackgamerz.jmteg.util.DeferredTaskScheduler;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for equipment changes and entities joining the world.
 * Debounces rapid events and schedules deferred re-sanitization and goal replacement attempts.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EquipmentChangeHandler {
    private static final Logger LOGGER = LogManager.getLogger("JMT-EquipmentChangeHandler");

    private static final Map<UUID, Long> lastHandled = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 250L; // don't handle same entity more often than this

    private EquipmentChangeHandler() {}

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getEntity() == null || event.getEntity().level().isClientSide) return;
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof PathfinderMob mob)) return;

        ItemStack to = event.getTo();
        // Optionally only act if new item is a JEG gun; or act unconditionally to sanitize
        boolean isJeg = to != null && !to.isEmpty() && JustEnoughGunsCompat.isJegGun(to);

        if (!isJeg) {
            // Not a JEG gun equip -- but still may want to sanitize if item was modified; we'll skip most non-JEG changes
            return;
        }

        // debounce
        UUID id = mob.getUUID();
        long now = System.currentTimeMillis();
        Long last = lastHandled.get(id);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastHandled.put(id, now);

        // immediate sanitize and attempt replacement, then schedule a deferred retry
        try {
            EntityWeaponSanitizer.sanitize(mob);
        } catch (Throwable t) {
            LOGGER.debug("Immediate sanitize failed for {}: {}", mob, t.toString());
        }

        try {
            RecruitGoalOverrideHandler.replaceGoalsIfJegGun(mob);
        } catch (Throwable t) {
            LOGGER.debug("Immediate replaceGoalsIfJegGun failed for {}: {}", mob, t.toString());
        }

        // schedule deferred re-sanitize + reattempt after a couple ticks to catch mods that mutates later
        DeferredTaskScheduler.schedule(() -> {
            try {
                EntityWeaponSanitizer.sanitize(mob);
                RecruitGoalOverrideHandler.replaceGoalsIfJegGun(mob);
            } catch (Throwable t) {
                LOGGER.debug("Deferred sanitize/replace failed for {}: {}", mob, t.toString());
            }
        }, 4);
    }

    // optional join handler if you prefer listening here as well
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PathfinderMob mob) {
            // schedule a slightly-delayed sanitize/replace to allow other mods to finish setup
            DeferredTaskScheduler.schedule(() -> {
                try {
                    EntityWeaponSanitizer.sanitize(mob);
                    RecruitGoalOverrideHandler.replaceGoalsIfJegGun(mob);
                } catch (Throwable t) {
                    LOGGER.debug("Join schedule sanitize/replace failed for {}: {}", mob, t.toString());
                }
            }, 2);
        }
    }
}