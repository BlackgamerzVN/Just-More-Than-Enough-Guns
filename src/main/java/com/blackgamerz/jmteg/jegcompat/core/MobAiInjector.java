package com.blackgamerz.jmteg.jegcompat.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MobAiInjector (reflection-only JEG detection + inventory-aware, deterministic seeding, seed-once).
 *
 * <p>This class is the event-driven orchestrator only: it owns the per-mob watch state
 * (which mobs are being tracked, their last-seen ammo count, pending reapply ticks) and
 * reacts to entity join / server tick events. The actual reflection-based work is
 * delegated to focused helper classes in this package:</p>
 * <ul>
 *   <li>{@link JegGunDetector} — detects JEG Gun properties (pool id, max ammo, reload kind) from an ItemStack.</li>
 *   <li>{@link RecruitInventoryAmmoAccessor} — counts/removes ammo items in a Recruits-compatible entity inventory.</li>
 *   <li>{@link MobAiScannerConfig} — loads the periodic mob-scanner's interval/radius settings.</li>
 * </ul>
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>Seeds pool from mob inventory only (deterministic) once per entity (marking mob as seeded).</li>
 *   <li>If no inventory ammo found at seed time, pool is cleared to 0 and magazine is set to 0.</li>
 *   <li>Detects JEG Gun properties via reflection at runtime (no compile-time dependency).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "jmteg")
public final class MobAiInjector {
    private static final Logger LOGGER = LogManager.getLogger("jmteg");

    private static final Map<UUID, ServerLevel> watchedLevels = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastAmmoCounts = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> pendingReapply = new ConcurrentHashMap<>();

    private static final MobAiScannerConfig scannerConfig = new MobAiScannerConfig();
    private static long tickCounter = 0L;

    private static final String SEEDED_TAG = "jmteg_ammo_seeded";

    private MobAiInjector() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof PathfinderMob mob)) return;

        // Recruit entities have their own ammo goal (RecruitAmmoResupplyGoal) injected by
        // RecruitGoalOverrideHandler. GunSyncGoal must not be added for recruits or it would
        // compete with that goal and double-consume ammo on reload.
        String fqcn = mob.getClass().getName();
        if (fqcn.contains("talhanation.recruits") || fqcn.contains(".recruits.")) return;

        GunConfigManager.ensureLoaded();
        scannerConfig.ensureLoaded();

        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) return;

        Item item = main.getItem();
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(item);
        if (itemKey == null) return;

        // Determine canonical poolId and maxAmmo (config or dynamic JEG via reflection)
        Optional<JegGunDetector.DetectedGun> detected = JegGunDetector.detect(main);
        ResourceLocation poolId = null;
        int maxAmmo = 0;
        GunConfig.ReloadKind reloadKind = null;

        GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
        if (cfg != null) {
            poolId = cfg.poolId;
            maxAmmo = cfg.maxAmmo;
            reloadKind = cfg.reloadKind;
        } else if (detected.isPresent()) {
            JegGunDetector.DetectedGun d = detected.get();
            poolId = d.poolId;
            maxAmmo = d.maxAmmo;
            reloadKind = d.kind;
            LOGGER.debug("Dynamic JEG detection (join): {} => pool {}, maxAmmo {}", itemKey, poolId, maxAmmo);
        }

        if (poolId == null) return;

        // Seed once: skip if already seeded
        if (!mob.getTags().contains(SEEDED_TAG)) {
            // Deterministic seeding: set pool to inventory count, or clear to 0 if none.
            int inventoryAmmo = RecruitInventoryAmmoAccessor.countAmmoInInventory(mob, poolId);
            int currentPool = MobAmmoHelper.getAmmoPool(mob, poolId);
            if (inventoryAmmo > 0) {
                if (currentPool != inventoryAmmo) {
                    if (currentPool > 0) MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                    MobAmmoHelper.addAmmo(mob, poolId, inventoryAmmo);
                }
                mob.addTag(SEEDED_TAG);
                LOGGER.info("Join: seeded mob {} pool {} from inventory ({} items) -> pool={}", mob.getUUID(), poolId, inventoryAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
            } else {
                // No inventory ammo -> clear pool and set magazine to 0 to avoid free reloads
                if (currentPool > 0) {
                    MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                    LOGGER.info("Join: cleared pool {} for mob {} (no inventory ammo)", poolId, mob.getUUID());
                } else {
                    LOGGER.debug("Join: no inventory ammo and pool already empty for mob {}", mob.getUUID());
                }
                mob.addTag(SEEDED_TAG);
            }
        } else {
            LOGGER.debug("Join: mob {} already seeded, skipping seeding", mob.getUUID());
        }

        // attach GunSyncGoal (pass a minimal GunConfig if needed)
        mob.goalSelector.addGoal(0, new GunSyncGoal(mob, JegGunDetector.makeGunConfig(itemKey, maxAmmo, reloadKind, poolId)));

        if (event.getLevel() instanceof ServerLevel serverLevel) {
            UUID id = mob.getUUID();
            watchedLevels.put(id, serverLevel);
            lastAmmoCounts.put(id, JegGunDetector.getAmmoCountFromStack(main));
            pendingReapply.put(id, serverLevel.getGameTime() + 1L);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        scannerConfig.ensureLoaded();
        tickCounter++;

        // pending reapply
        if (!pendingReapply.isEmpty()) {
            Set<UUID> keys = Set.copyOf(pendingReapply.keySet());
            for (UUID id : keys) {
                Long target = pendingReapply.get(id);
                ServerLevel level = watchedLevels.get(id);
                if (target == null || level == null) {
                    pendingReapply.remove(id);
                    continue;
                }
                try {
                    if (level.getGameTime() >= target) {
                        Entity ent = level.getEntity(id);
                        if (ent instanceof PathfinderMob mob) {
                            ItemStack main = mob.getMainHandItem();
                            if (main != null && !main.isEmpty()) {
                                Item item = main.getItem();
                                ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(item);
                                Optional<JegGunDetector.DetectedGun> detected = JegGunDetector.detect(main);

                                ResourceLocation poolId = null;
                                int maxAmmo = 0;
                                GunConfig.ReloadKind reloadKind = null;

                                GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
                                if (cfg != null) {
                                    poolId = cfg.poolId;
                                    maxAmmo = cfg.maxAmmo;
                                    reloadKind = cfg.reloadKind;
                                } else if (detected.isPresent()) {
                                    JegGunDetector.DetectedGun d = detected.get();
                                    poolId = d.poolId;
                                    maxAmmo = d.maxAmmo;
                                    reloadKind = d.kind;
                                }

                                if (poolId != null) {
                                    // Seed once during reapply if not already seeded
                                    if (!mob.getTags().contains(SEEDED_TAG)) {
                                        int inventoryAmmo = RecruitInventoryAmmoAccessor.countAmmoInInventory(mob, poolId);
                                        int currentPool2 = MobAmmoHelper.getAmmoPool(mob, poolId);
                                        if (inventoryAmmo > 0) {
                                            if (currentPool2 != inventoryAmmo) {
                                                if (currentPool2 > 0) MobAmmoHelper.consumeAmmo(mob, poolId, currentPool2);
                                                MobAmmoHelper.addAmmo(mob, poolId, inventoryAmmo);
                                            }
                                            mob.addTag(SEEDED_TAG);
                                            LOGGER.info("Reapply: seeded mob {} pool {} from inventory ({} items) -> pool={}", id, poolId, inventoryAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
                                        } else {
                                            if (currentPool2 > 0) {
                                                MobAmmoHelper.consumeAmmo(mob, poolId, currentPool2);
                                                LOGGER.info("Reapply: cleared pool {} for mob {} (no inventory ammo)", poolId, id);
                                            } else {
                                                LOGGER.debug("Reapply: no inventory ammo and pool already empty for mob {}", id);
                                            }
                                            mob.addTag(SEEDED_TAG);
                                        }
                                    }
                                    mob.goalSelector.addGoal(0, new GunSyncGoal(mob, JegGunDetector.makeGunConfig(itemKey, maxAmmo, reloadKind, poolId)));
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("Error during pending reapply for {}", id, t);
                } finally {
                    pendingReapply.remove(id);
                }
            }
        }

        // watched enforcement (inventory-first consumption)
        if (!watchedLevels.isEmpty()) {
            Set<UUID> uuids = Set.copyOf(watchedLevels.keySet());
            for (UUID id : uuids) {
                ServerLevel level = watchedLevels.get(id);
                if (level == null) {
                    watchedLevels.remove(id);
                    lastAmmoCounts.remove(id);
                    continue;
                }
                Entity ent = level.getEntity(id);
                if (!(ent instanceof PathfinderMob mob)) {
                    watchedLevels.remove(id);
                    lastAmmoCounts.remove(id);
                    continue;
                }

                ItemStack main = mob.getMainHandItem();
                if (main == null || main.isEmpty()) {
                    watchedLevels.remove(id);
                    lastAmmoCounts.remove(id);
                    continue;
                }

                Item item = main.getItem();
                ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(item);
                Optional<JegGunDetector.DetectedGun> detected = JegGunDetector.detect(main);

                ResourceLocation poolId = null;
                int maxAmmo = 0;
                GunConfig.ReloadKind reloadKind = null;

                GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
                if (cfg != null) {
                    poolId = cfg.poolId;
                    maxAmmo = cfg.maxAmmo;
                    reloadKind = cfg.reloadKind;
                } else if (detected.isPresent()) {
                    JegGunDetector.DetectedGun d = detected.get();
                    poolId = d.poolId;
                    maxAmmo = d.maxAmmo;
                    reloadKind = d.kind;
                }

                if (poolId == null) {
                    watchedLevels.remove(id);
                    lastAmmoCounts.remove(id);
                    continue;
                }

                int curAmmo = JegGunDetector.getAmmoCountFromStack(main);
                int prevAmmo = lastAmmoCounts.getOrDefault(id, -1);

                // If ammo increased externally (another AI reloaded), consume delta from inventory then pool
                if (prevAmmo >= 0 && curAmmo > prevAmmo) {
                    int delta = curAmmo - prevAmmo;
                    int consumedFromInv = RecruitInventoryAmmoAccessor.removeAmmoFromInventory(mob, poolId, delta);
                    int remaining = delta - consumedFromInv;
                    int consumedFromPool = 0;
                    if (remaining > 0) {
                        consumedFromPool = MobAmmoHelper.consumeAmmo(mob, poolId, remaining);
                    }
                    int totalConsumed = consumedFromInv + consumedFromPool;
                    if (totalConsumed < delta) {
                        CompoundTag tag = main.getOrCreateTag();
                        tag.putInt("AmmoCount", prevAmmo + totalConsumed);
                        curAmmo = prevAmmo + totalConsumed;
                        LOGGER.info("Watcher: mob {} attempted to increase mag by {} but only consumed {} (inv {} + pool {}), mag now {}",
                                id, delta, totalConsumed, consumedFromInv, consumedFromPool, curAmmo);
                    } else {
                        LOGGER.info("Watcher: mob {} external reload consumed {} (inv {} + pool {}), pool left {}",
                                id, totalConsumed, consumedFromInv, consumedFromPool, MobAmmoHelper.getAmmoPool(mob, poolId));
                    }
                }

                // If empty, attempt to reload by consuming inventory first, then pool
                if (curAmmo <= 0) {
                    if (reloadKind == GunConfig.ReloadKind.SINGLE_ITEM) {
                        int consumedInv = RecruitInventoryAmmoAccessor.removeAmmoFromInventory(mob, poolId, 1);
                        if (consumedInv > 0) {
                            main.getOrCreateTag().putInt("AmmoCount", maxAmmo);
                            curAmmo = maxAmmo;
                            LOGGER.info("Watcher: mob {} SINGLE_ITEM reload consumed 1 from inventory -> mag {}", id, curAmmo);
                        } else {
                            int consumedPool = MobAmmoHelper.consumeAmmo(mob, poolId, 1);
                            if (consumedPool > 0) {
                                main.getOrCreateTag().putInt("AmmoCount", maxAmmo);
                                curAmmo = maxAmmo;
                                LOGGER.info("Watcher: mob {} SINGLE_ITEM reload consumed 1 from pool -> mag {}, pool left {}",
                                        id, curAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
                            }
                        }
                    } else {
                        int needed = Math.max(0, maxAmmo - curAmmo);
                        int consumedInv = RecruitInventoryAmmoAccessor.removeAmmoFromInventory(mob, poolId, needed);
                        int afterInv = curAmmo + consumedInv;
                        if (consumedInv < needed) {
                            int consumedPool = MobAmmoHelper.consumeAmmo(mob, poolId, needed - consumedInv);
                            afterInv += consumedPool;
                            if (consumedPool > 0)
                                LOGGER.info("Watcher: mob {} MAG reload consumed pool {} (pool left {})", id, consumedPool, MobAmmoHelper.getAmmoPool(mob, poolId));
                        }
                        if (afterInv > curAmmo) {
                            main.getOrCreateTag().putInt("AmmoCount", afterInv);
                            curAmmo = afterInv;
                            LOGGER.info("Watcher: mob {} MAG reload consumed {} from inventory -> mag now {}", id, consumedInv, curAmmo);
                        }
                    }
                }

                lastAmmoCounts.put(id, curAmmo);
            }
        }

        // scanner: discover mobs later acquiring JEG guns (seed-once from inventory when possible)
        long interval = scannerConfig.get().intervalTicks;
        if (interval > 0 && tickCounter % interval == 0L) {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        for (ServerPlayer player : level.players()) {
                            double radius = scannerConfig.get().radius;
                            List<PathfinderMob> nearby = level.getEntitiesOfClass(
                                    PathfinderMob.class,
                                    player.getBoundingBox().inflate(radius),
                                    mob -> true
                            );

                            for (PathfinderMob mob : nearby) {
                                UUID id = mob.getUUID();
                                if (watchedLevels.containsKey(id)) continue;

                                ItemStack main = mob.getMainHandItem();
                                if (main == null || main.isEmpty()) continue;

                                Item item = main.getItem();
                                ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(item);
                                if (itemKey == null) continue;

                                GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
                                Optional<JegGunDetector.DetectedGun> detected2 = JegGunDetector.detect(main);

                                ResourceLocation poolId = null;
                                int maxAmmo = 0;
                                GunConfig.ReloadKind reloadKind = null;

                                if (cfg != null) {
                                    poolId = cfg.poolId;
                                    maxAmmo = cfg.maxAmmo;
                                    reloadKind = cfg.reloadKind;
                                } else if (detected2.isPresent()) {
                                    JegGunDetector.DetectedGun d = detected2.get();
                                    poolId = d.poolId;
                                    maxAmmo = d.maxAmmo;
                                    reloadKind = d.kind;
                                }

                                if (poolId == null) continue;

                                // Seed once only
                                if (!mob.getTags().contains(SEEDED_TAG)) {
                                    int inventoryAmmo = RecruitInventoryAmmoAccessor.countAmmoInInventory(mob, poolId);
                                    int currentPool = MobAmmoHelper.getAmmoPool(mob, poolId);
                                    if (inventoryAmmo > 0) {
                                        if (currentPool != inventoryAmmo) {
                                            if (currentPool > 0) MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                                            MobAmmoHelper.addAmmo(mob, poolId, inventoryAmmo);
                                        }
                                        mob.addTag(SEEDED_TAG);
                                        LOGGER.info("Scanner: seeded mob {} pool {} from inventory ({} items) -> pool={}", id, poolId, inventoryAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
                                    } else {
                                        LOGGER.info("Scanner: no exact-match ammo '{}' in inventory for mob {} — dumping contents", poolId, id);
                                        RecruitInventoryAmmoAccessor.dumpInventoryContents(mob);

                                        int fuzzy = RecruitInventoryAmmoAccessor.countAmmoInInventoryFuzzy(mob, poolId);
                                        if (fuzzy > 0) {
                                            LOGGER.info("Scanner: fuzzy-match would have found {} items for pool {} on mob {} but deterministic seeding does not use fuzzy", fuzzy, poolId, id);
                                        }

                                        if (currentPool > 0) {
                                            MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                                            LOGGER.info("Scanner: cleared pool {} for mob {} (no inventory ammo)", poolId, id);
                                        } else {
                                            LOGGER.debug("Scanner: no inventory ammo and pool already empty for mob {}", id);
                                        }
                                        mob.addTag(SEEDED_TAG);
                                    }
                                } else {
                                    LOGGER.debug("Scanner: mob {} already seeded, skipping", id);
                                }

                                mob.goalSelector.addGoal(0, new GunSyncGoal(mob, JegGunDetector.makeGunConfig(itemKey, maxAmmo, reloadKind, poolId)));
                                watchedLevels.put(id, level);
                                lastAmmoCounts.put(id, JegGunDetector.getAmmoCountFromStack(main));
                                LOGGER.debug("Scanner: started watching mob {} holding {}", id, itemKey);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("Error during mob scanner", t);
            }
        }
    }
}
