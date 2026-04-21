package com.blackgamerz.jmteg.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Compatibility initializer for Recruits (mod id "recruits").
 * Adds behaviour: equip recruited villagers with JEG guns (if available)
 * and ask JEG to enable the villager as a gun user via reflection.
 */
public final class RecruitsDetection {
    private static final Logger LOGGER = LogManager.getLogger("JMTJEG-Recruits-Compat");

    private RecruitsDetection() {}

    public static void init() {
        LOGGER.info("Initializing Recruits compatibility...");
        MinecraftForge.EVENT_BUS.register(new RecruitsDetection());
        // Example verification: check for a known Recruits class
        try {
            Class.forName("com.talhanation.recruits.Recruits"); // adjust if main class name differs
            LOGGER.info("Detected Recruits classes via reflection.");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Could not find expected Recruits classes reflectively.");
        }
    }

    // Listen for any entity joining the level; when a villager spawns we check if it's recruited and equip it.
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;

        // Small delay check: EntityJoinLevelEvent fires during load; some recruit state may be set later.
        // We attempt detection immediately; if false-positive/negative possibilities exist the logic can be extended.
        if (!isRecruited(villager)) return;

        LOGGER.info("Detected recruited villager at {} - equipping if a compatible gun is available", villager.blockPosition());

        // Find candidate JEG gun Item (heuristic)
        Optional<Item> candidateGun = findCandidateJegGun();

        if (candidateGun.isPresent()) {
            ItemStack gunStack = new ItemStack(candidateGun.get());
            // equip the villager's main hand
            villager.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, gunStack);
            LOGGER.info("Equipped recruited villager with {}", ForgeRegistries.ITEMS.getKey(candidateGun.get()));
        } else {
            LOGGER.info("No candidate JEG gun item found to equip recruited villager.");
        }

        // Try to enable entity to use guns through JEG's API (reflective)
        try {
            boolean enabled = JegDetection.tryEnableEntityToUseGuns(villager);
            if (enabled) {
                LOGGER.info("Requested JEG to enable villager to use guns.");
            } else {
                LOGGER.debug("JEG did not report enabling entity to use guns (API not present or failed).");
            }
        } catch (Throwable t) {
            LOGGER.error("Error while invoking JEG API to enable entity gun use", t);
        }
    }

    // Heuristics to detect if a villager is 'recruited' by the Recruits mod.
    private boolean isRecruited(Villager v) {
        // Strategy 1: try Recruits API via reflection: com.talhanation.recruits.api.RecruitsAPI.isRecruited(Entity)
        try {
            Class<?> api = Class.forName("com.talhanation.recruits.api.RecruitsAPI");
            Method m = api.getMethod("isRecruited", net.minecraft.world.entity.Entity.class);
            Object result = m.invoke(null, v);
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {
            // API not present or method missing - try next detection
        }

        // Strategy 2: check persistent data - some mods write a boolean or tag like "recruited"
        try {
            if (v.getPersistentData().getBoolean("recruited")) return true;
        } catch (Throwable ignored) {}

        // Strategy 3: check entity tags for common names
        try {
            if (v.getTags().contains("recruited") || v.getTags().contains("recruit") || v.getTags().contains("recruits:recruited")) return true;
        } catch (Throwable ignored) {}

        // Strategy 4: Recruits might store owner/command data (this is a fallback and might need refinement)
        return false;
    }

    // Heuristic scan for a JEG gun item at runtime.
    // replace the previous findCandidateJegGun() implementation with this:
    private Optional<Item> findCandidateJegGun() {
        List<Item> items = ForgeRegistries.ITEMS.getValues().stream().collect(Collectors.toList());

        // First preference: items in the 'jeg' mod namespace
        Optional<Item> jegItem = items.stream()
                .filter(i -> {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(i);
                    return id != null && "jeg".equals(id.getNamespace());
                })
                .max(Comparator.comparing(i -> ForgeRegistries.ITEMS.getKey(i).toString()));
        if (jegItem.isPresent()) return jegItem;

        // Next preference: heuristic class-name check for ranged/projectile weapons (reflection-safe)
        Optional<Item> ranged = items.stream()
                .filter(i -> {
                    try {
                        Class<?> c = i.getClass();
                        while (c != null && c != Object.class) {
                            String simple = c.getSimpleName().toLowerCase();
                            if (simple.contains("ranged") || simple.contains("projectile") || simple.contains("gun") || simple.contains("rifle") || simple.contains("pistol")) {
                                return true;
                            }
                            c = c.getSuperclass();
                        }
                    } catch (Throwable ignored) {}
                    return false;
                })
                .findFirst();
        if (ranged.isPresent()) return ranged;

        // Fallback: name heuristics on the registry id
        Optional<Item> named = items.stream()
                .filter(i -> {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(i);
                    String path = id == null ? i.getDescriptionId() : id.getPath();
                    return path.contains("gun") || path.contains("rifle") || path.contains("pistol");
                })
                .findFirst();

        return named;
    }
}