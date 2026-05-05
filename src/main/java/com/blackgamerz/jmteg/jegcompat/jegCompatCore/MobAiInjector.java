package com.blackgamerz.jmteg.jegcompat.jegCompatCore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MobAiInjector (reflection-only JEG detection + inventory-aware, deterministic seeding, seed-once).
 *
 * - Seeds pool from mob inventory only (deterministic) once per entity (marking mob as seeded).
 * - If no inventory ammo found at seed time, pool is cleared to 0 and magazine is set to 0.
 * - Detects JEG Gun properties via reflection at runtime (no compile-time dependency).
 *
 * Package/modid kept as in your project.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "jmteg")
public final class MobAiInjector {
    private static final Logger LOG = LogManager.getLogger("jmteg");

    private static final Map<UUID, ServerLevel> watchedLevels = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastAmmoCounts = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> pendingReapply = new ConcurrentHashMap<>();

    private static final ScannerConfigManager scannerConfigManager = new ScannerConfigManager();
    private static long tickCounter = 0L;

    private static final String SEEDED_TAG = "jmteg_ammo_seeded";

    private MobAiInjector() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof PathfinderMob mob)) return;

        GunConfigManager.ensureLoaded();
        scannerConfigManager.ensureLoaded();

        ItemStack main = mob.getMainHandItem();
        if (main == null || main.isEmpty()) return;

        Item item = main.getItem();
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(item);
        if (itemKey == null) return;

        // Determine canonical poolId and maxAmmo (config or dynamic JEG via reflection)
        Optional<DetectedGun> detected = detectJegGunData(main);
        ResourceLocation poolId = null;
        int maxAmmo = 0;
        GunConfig.ReloadKind reloadKind = null;
        int reloadTimeTicks = 0;

        GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
        if (cfg != null) {
            poolId = cfg.poolId;
            maxAmmo = cfg.maxAmmo;
            reloadKind = cfg.reloadKind;
            reloadTimeTicks = cfg.reloadTimeTicks;
        } else if (detected.isPresent()) {
            DetectedGun d = detected.get();
            poolId = d.poolId;
            maxAmmo = d.maxAmmo;
            reloadKind = d.kind;
            reloadTimeTicks = d.reloadTimeTicks;
            LOG.debug("Dynamic JEG detection (join): {} => pool {}, maxAmmo {}", itemKey, poolId, maxAmmo);
        }

        if (poolId == null) return;

        // Seed once: skip if already seeded
        if (!mob.getTags().contains(SEEDED_TAG)) {
            // Deterministic seeding: set pool to inventory count, or clear to 0 if none.
            int inventoryAmmo = countAmmoInInventory(mob, poolId);
            int currentPool = MobAmmoHelper.getAmmoPool(mob, poolId);
            if (inventoryAmmo > 0) {
                if (currentPool != inventoryAmmo) {
                    if (currentPool > 0) MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                    MobAmmoHelper.addAmmo(mob, poolId, inventoryAmmo);
                }
                mob.addTag(SEEDED_TAG);
                LOG.info("Join: seeded mob {} pool {} from inventory ({} items) -> pool={}", mob.getUUID(), poolId, inventoryAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
            } else {
                // No inventory ammo -> clear pool and set magazine to 0 to avoid free reloads
                if (currentPool > 0) {
                    MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                    LOG.info("Join: cleared pool {} for mob {} (no inventory ammo)", poolId, mob.getUUID());
                } else {
                    LOG.debug("Join: no inventory ammo and pool already empty for mob {}", mob.getUUID());
                }
                mob.addTag(SEEDED_TAG);
            }
        } else {
            LOG.debug("Join: mob {} already seeded, skipping seeding", mob.getUUID());
        }

        // attach GunSyncGoal (pass a minimal GunConfig if needed)
        mob.goalSelector.addGoal(0, new GunSyncGoal(mob, makeGunConfig(itemKey, maxAmmo, reloadKind, poolId, reloadTimeTicks)));

        if (event.getLevel() instanceof ServerLevel serverLevel) {
            UUID id = mob.getUUID();
            watchedLevels.put(id, serverLevel);
            lastAmmoCounts.put(id, getAmmoCountFromStack(main));
            pendingReapply.put(id, serverLevel.getGameTime() + 1L);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        scannerConfigManager.ensureLoaded();
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
                                Optional<DetectedGun> detected = detectJegGunData(main);

                                ResourceLocation poolId = null;
                                int maxAmmo = 0;
                                GunConfig.ReloadKind reloadKind = null;
                                int reloadTimeTicks = 0;

                                GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
                                if (cfg != null) {
                                    poolId = cfg.poolId;
                                    maxAmmo = cfg.maxAmmo;
                                    reloadKind = cfg.reloadKind;
                                    reloadTimeTicks = cfg.reloadTimeTicks;
                                } else if (detected.isPresent()) {
                                    DetectedGun d = detected.get();
                                    poolId = d.poolId;
                                    maxAmmo = d.maxAmmo;
                                    reloadKind = d.kind;
                                    reloadTimeTicks = d.reloadTimeTicks;
                                }

                                if (poolId != null) {
                                    // Seed once during reapply if not already seeded
                                    if (!mob.getTags().contains(SEEDED_TAG)) {
                                        int inventoryAmmo = countAmmoInInventory(mob, poolId);
                                        int currentPool2 = MobAmmoHelper.getAmmoPool(mob, poolId);
                                        if (inventoryAmmo > 0) {
                                            if (currentPool2 != inventoryAmmo) {
                                                if (currentPool2 > 0) MobAmmoHelper.consumeAmmo(mob, poolId, currentPool2);
                                                MobAmmoHelper.addAmmo(mob, poolId, inventoryAmmo);
                                            }
                                            mob.addTag(SEEDED_TAG);
                                            LOG.info("Reapply: seeded mob {} pool {} from inventory ({} items) -> pool={}", id, poolId, inventoryAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
                                        } else {
                                            if (currentPool2 > 0) {
                                                MobAmmoHelper.consumeAmmo(mob, poolId, currentPool2);
                                                LOG.info("Reapply: cleared pool {} for mob {} (no inventory ammo)", poolId, id);
                                            } else {
                                                LOG.debug("Reapply: no inventory ammo and pool already empty for mob {}", id);
                                            }
                                            mob.addTag(SEEDED_TAG);
                                        }
                                    }
                                    mob.goalSelector.addGoal(0, new GunSyncGoal(mob, makeGunConfig(itemKey, maxAmmo, reloadKind, poolId, reloadTimeTicks)));
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("Error during pending reapply for {}", id, t);
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
                Optional<DetectedGun> detected = detectJegGunData(main);

                ResourceLocation poolId = null;
                int maxAmmo = 0;
                GunConfig.ReloadKind reloadKind = null;
                int reloadTimeTicks = 0;

                GunConfig cfg = itemKey == null ? null : GunConfigManager.GUN_CONFIGS.get(itemKey);
                if (cfg != null) {
                    poolId = cfg.poolId;
                    maxAmmo = cfg.maxAmmo;
                    reloadKind = cfg.reloadKind;
                    reloadTimeTicks = cfg.reloadTimeTicks;
                } else if (detected.isPresent()) {
                    DetectedGun d = detected.get();
                    poolId = d.poolId;
                    maxAmmo = d.maxAmmo;
                    reloadKind = d.kind;
                    reloadTimeTicks = d.reloadTimeTicks;
                }

                if (poolId == null) {
                    watchedLevels.remove(id);
                    lastAmmoCounts.remove(id);
                    continue;
                }

                int curAmmo = getAmmoCountFromStack(main);
                int prevAmmo = lastAmmoCounts.getOrDefault(id, -1);

                // If ammo increased externally (another AI reloaded), consume delta from inventory then pool
                if (prevAmmo >= 0 && curAmmo > prevAmmo) {
                    int delta = curAmmo - prevAmmo;
                    int consumedFromInv = removeAmmoFromInventory(mob, poolId, delta);
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
                        LOG.info("Watcher: mob {} attempted to increase mag by {} but only consumed {} (inv {} + pool {}), mag now {}",
                                id, delta, totalConsumed, consumedFromInv, consumedFromPool, curAmmo);
                    } else {
                        LOG.info("Watcher: mob {} external reload consumed {} (inv {} + pool {}), pool left {}",
                                id, totalConsumed, consumedFromInv, consumedFromPool, MobAmmoHelper.getAmmoPool(mob, poolId));
                    }
                    // Clear any pending reload timer since ammo was just refilled externally
                    main.getOrCreateTag().remove("jmteg_reload_at");
                }

                // If empty, attempt to reload by consuming inventory first, then pool.
                // A reload-delay timer ("jmteg_reload_at") prevents instant refill so the
                // NPC experiences the same reload duration as a player using the same gun.
                if (curAmmo <= 0) {
                    CompoundTag stackTag = main.getOrCreateTag();
                    long gameTime = level.getGameTime();

                    if (!stackTag.contains("jmteg_reload_at")) {
                        // Gun just ran dry — start the reload timer.
                        // Priority: JEG reflection value > GunConfig fallback > instant (0).
                        int delay = reloadTimeTicks;
                        if (delay <= 0) {
                            // Try one more time with JEG reflection directly on the stack.
                            try {
                                int reflectedTime = com.blackgamerz.jmteg.recruitcompat.JustEnoughGunsCompat.getJegGunReloadTime(main);
                                if (reflectedTime > 0) delay = reflectedTime;
                            } catch (Throwable ignored) {}
                        }
                        if (delay > 0) {
                            stackTag.putLong("jmteg_reload_at", gameTime + delay);
                            LOG.debug("Watcher: mob {} started reload timer ({} ticks)", id, delay);
                            lastAmmoCounts.put(id, curAmmo);
                            continue; // wait — do not fill ammo yet
                        }
                        // delay == 0: instant reload (legacy behaviour / IgnoreAmmo guns)
                    } else {
                        long readyAt = stackTag.getLong("jmteg_reload_at");
                        if (gameTime < readyAt) {
                            // Reload still in progress — don't fill yet.
                            lastAmmoCounts.put(id, curAmmo);
                            continue;
                        }
                        // Timer has expired — proceed with the actual ammo fill below.
                        stackTag.remove("jmteg_reload_at");
                        LOG.debug("Watcher: mob {} reload timer expired, filling ammo", id);
                    }

                    // ── Actual ammo fill (reached when timer is disabled or has expired) ──
                    if (reloadKind == GunConfig.ReloadKind.SINGLE_ITEM) {
                        int consumedInv = removeAmmoFromInventory(mob, poolId, 1);
                        if (consumedInv > 0) {
                            main.getOrCreateTag().putInt("AmmoCount", maxAmmo);
                            curAmmo = maxAmmo;
                            LOG.info("Watcher: mob {} SINGLE_ITEM reload consumed 1 from inventory -> mag {}", id, curAmmo);
                        } else {
                            int consumedPool = MobAmmoHelper.consumeAmmo(mob, poolId, 1);
                            if (consumedPool > 0) {
                                main.getOrCreateTag().putInt("AmmoCount", maxAmmo);
                                curAmmo = maxAmmo;
                                LOG.info("Watcher: mob {} SINGLE_ITEM reload consumed 1 from pool -> mag {}, pool left {}",
                                        id, curAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
                            }
                        }
                    } else {
                        int needed = Math.max(0, maxAmmo - curAmmo);
                        int consumedInv = removeAmmoFromInventory(mob, poolId, needed);
                        int afterInv = curAmmo + consumedInv;
                        if (consumedInv < needed) {
                            int consumedPool = MobAmmoHelper.consumeAmmo(mob, poolId, needed - consumedInv);
                            afterInv += consumedPool;
                            if (consumedPool > 0)
                                LOG.info("Watcher: mob {} MAG reload consumed pool {} (pool left {})", id, consumedPool, MobAmmoHelper.getAmmoPool(mob, poolId));
                        }
                        if (afterInv > curAmmo) {
                            main.getOrCreateTag().putInt("AmmoCount", afterInv);
                            curAmmo = afterInv;
                            LOG.info("Watcher: mob {} MAG reload consumed {} from inventory -> mag now {}", id, consumedInv, curAmmo);
                        }
                    }
                }

                lastAmmoCounts.put(id, curAmmo);
            }
        }

        // scanner: discover mobs later acquiring JEG guns (seed-once from inventory when possible)
        long interval = scannerConfigManager.get().intervalTicks;
        if (interval > 0 && tickCounter % interval == 0L) {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        for (ServerPlayer player : level.players()) {
                            double radius = scannerConfigManager.get().radius;
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
                                Optional<DetectedGun> detected2 = detectJegGunData(main);

                                ResourceLocation poolId = null;
                                int maxAmmo = 0;
                                GunConfig.ReloadKind reloadKind = null;
                                int reloadTimeTicks = 0;

                                if (cfg != null) {
                                    poolId = cfg.poolId;
                                    maxAmmo = cfg.maxAmmo;
                                    reloadKind = cfg.reloadKind;
                                    reloadTimeTicks = cfg.reloadTimeTicks;
                                } else if (detected2.isPresent()) {
                                    DetectedGun d = detected2.get();
                                    poolId = d.poolId;
                                    maxAmmo = d.maxAmmo;
                                    reloadKind = d.kind;
                                    reloadTimeTicks = d.reloadTimeTicks;
                                }

                                if (poolId == null) continue;

                                // Seed once only
                                if (!mob.getTags().contains(SEEDED_TAG)) {
                                    int inventoryAmmo = countAmmoInInventory(mob, poolId);
                                    int currentPool = MobAmmoHelper.getAmmoPool(mob, poolId);
                                    if (inventoryAmmo > 0) {
                                        if (currentPool != inventoryAmmo) {
                                            if (currentPool > 0) MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                                            MobAmmoHelper.addAmmo(mob, poolId, inventoryAmmo);
                                        }
                                        mob.addTag(SEEDED_TAG);
                                        LOG.info("Scanner: seeded mob {} pool {} from inventory ({} items) -> pool={}", id, poolId, inventoryAmmo, MobAmmoHelper.getAmmoPool(mob, poolId));
                                    } else {
                                        LOG.info("Scanner: no exact-match ammo '{}' in inventory for mob {} — dumping contents", poolId, id);
                                        dumpInventoryContents(mob);

                                        int fuzzy = countAmmoInInventoryFuzzy(mob, poolId);
                                        if (fuzzy > 0) {
                                            LOG.info("Scanner: fuzzy-match would have found {} items for pool {} on mob {} but deterministic seeding does not use fuzzy", fuzzy, poolId, id);
                                        }

                                        if (currentPool > 0) {
                                            MobAmmoHelper.consumeAmmo(mob, poolId, currentPool);
                                            LOG.info("Scanner: cleared pool {} for mob {} (no inventory ammo)", poolId, id);
                                        } else {
                                            LOG.debug("Scanner: no inventory ammo and pool already empty for mob {}", id);
                                        }
                                        mob.addTag(SEEDED_TAG);
                                    }
                                } else {
                                    LOG.debug("Scanner: mob {} already seeded, skipping", id);
                                }

                                mob.goalSelector.addGoal(0, new GunSyncGoal(mob, makeGunConfig(itemKey, maxAmmo, reloadKind, poolId, reloadTimeTicks)));
                                watchedLevels.put(id, level);
                                lastAmmoCounts.put(id, getAmmoCountFromStack(main));
                                LOG.debug("Scanner: started watching mob {} holding {}", id, itemKey);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("Error during mob scanner", t);
            }
        }
    }

    // Minimal holder used when JEG data is available via reflection
    private static final class DetectedGun {
        final ResourceLocation poolId;
        final int maxAmmo;
        final GunConfig.ReloadKind kind;
        /** JEG-reported reload duration in ticks; 0 = could not detect (use config fallback). */
        final int reloadTimeTicks;

        DetectedGun(ResourceLocation poolId, int maxAmmo, GunConfig.ReloadKind kind, int reloadTimeTicks) {
            this.poolId = poolId;
            this.maxAmmo = maxAmmo;
            this.kind = kind;
            this.reloadTimeTicks = reloadTimeTicks;
        }
    }

    // Reflection-based detection of JEG Gun properties (no compile-time dependency).
    private static Optional<DetectedGun> detectJegGunData(ItemStack stack) {
        try {
            Class<?> gunItemClass = Class.forName("ttv.migami.jeg.item.GunItem");
            Object itemObj = stack.getItem();
            if (!gunItemClass.isInstance(itemObj)) return Optional.empty();

            Method getModifiedGun = gunItemClass.getMethod("getModifiedGun", ItemStack.class);
            Object gunObj = getModifiedGun.invoke(itemObj, stack);
            if (gunObj == null) return Optional.empty();

            Method getReloads = gunObj.getClass().getMethod("getReloads");
            Object reloadsObj = getReloads.invoke(gunObj);
            if (reloadsObj == null) return Optional.empty();

            Method getReloadType = reloadsObj.getClass().getMethod("getReloadType");
            Object reloadTypeObj = getReloadType.invoke(reloadsObj);
            boolean isSingleItem = false;
            if (reloadTypeObj != null) {
                try {
                    Method nameMethod = reloadTypeObj.getClass().getMethod("name");
                    String name = (String) nameMethod.invoke(reloadTypeObj);
                    isSingleItem = "SINGLE_ITEM".equals(name);
                } catch (NoSuchMethodException ignored) {
                    isSingleItem = "SINGLE_ITEM".equals(reloadTypeObj.toString());
                }
            }

            ResourceLocation poolId = null;
            int maxAmmo = 0;
            GunConfig.ReloadKind kind;

            if (isSingleItem) {
                Method getReloadItem = reloadsObj.getClass().getMethod("getReloadItem");
                Object reloadItemObj = getReloadItem.invoke(reloadsObj);
                if (reloadItemObj instanceof ResourceLocation rl) {
                    poolId = rl;
                } else if (reloadItemObj != null) {
                    poolId = ResourceLocation.tryParse(reloadItemObj.toString());
                }
                kind = GunConfig.ReloadKind.SINGLE_ITEM;
            } else {
                Method getProjectile = gunObj.getClass().getMethod("getProjectile");
                Object projectileObj = getProjectile.invoke(gunObj);
                if (projectileObj != null) {
                    Method getItem = projectileObj.getClass().getMethod("getItem");
                    Object itemIdObj = getItem.invoke(projectileObj);
                    if (itemIdObj instanceof ResourceLocation rl) {
                        poolId = rl;
                    } else if (itemIdObj != null) {
                        poolId = ResourceLocation.tryParse(itemIdObj.toString());
                    }
                }
                kind = GunConfig.ReloadKind.PROJECTILE_OR_MAG;
            }

            try {
                Method getMaxAmmo = reloadsObj.getClass().getMethod("getMaxAmmo");
                Object maxAmmoObj = getMaxAmmo.invoke(reloadsObj);
                if (maxAmmoObj instanceof Number n) {
                    maxAmmo = n.intValue();
                } else if (maxAmmoObj != null) {
                    maxAmmo = Integer.parseInt(maxAmmoObj.toString());
                }
            } catch (NoSuchMethodException ex) {
                try {
                    Method gm = reloadsObj.getClass().getMethod("getMaxAmmo");
                    Object v = gm.invoke(reloadsObj);
                    if (v instanceof Number n) maxAmmo = n.intValue();
                } catch (Throwable ignored) {}
            }

            // Extract reload time so the watcher can delay ammo refill to match player reload.
            int reloadTimeTicks = 0;
            for (String methodName : new String[]{"getReloadTime", "getTime", "getReloadDuration"}) {
                try {
                    Method m = reloadsObj.getClass().getMethod(methodName);
                    Object val = m.invoke(reloadsObj);
                    if (val instanceof Number n && n.intValue() > 0) {
                        reloadTimeTicks = n.intValue();
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            // Fallback: getUseDuration(ItemStack) / getUseDuration(ItemStack, LivingEntity)
            if (reloadTimeTicks <= 0) {
                try {
                    Method m = itemObj.getClass().getMethod("getUseDuration", ItemStack.class);
                    Object val = m.invoke(itemObj, stack);
                    if (val instanceof Number n && n.intValue() > 0 && n.intValue() < 72000)
                        reloadTimeTicks = n.intValue();
                } catch (NoSuchMethodException ignored) {}
            }
            if (reloadTimeTicks <= 0) {
                try {
                    Class<?> livingClass = Class.forName("net.minecraft.world.entity.LivingEntity");
                    Method m = itemObj.getClass().getMethod("getUseDuration", ItemStack.class, livingClass);
                    Object val = m.invoke(itemObj, stack, (Object) null);
                    if (val instanceof Number n && n.intValue() > 0 && n.intValue() < 72000)
                        reloadTimeTicks = n.intValue();
                } catch (Throwable ignored) {}
            }

            if (poolId == null) return Optional.empty();
            return Optional.of(new DetectedGun(poolId, Math.max(0, maxAmmo), kind, reloadTimeTicks));
        } catch (ClassNotFoundException cnf) {
            return Optional.empty();
        } catch (Throwable t) {
            LOG.debug("detectJegGunData failed", t);
            return Optional.empty();
        }
    }

    // Dump inventory contents (index -> item id -> count)
    private static void dumpInventoryContents(Entity entity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Inventory dump for ").append(entity.getEncodeId()).append(" / ").append(entity.getUUID()).append(":");
            Method getInventory = null;
            try { getInventory = entity.getClass().getMethod("getInventory"); } catch (NoSuchMethodException ignored) {}
            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                        sb.append("\n  slot ").append(i).append(": ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                    }
                    LOG.info(sb.toString());
                    return;
                } else {
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 0; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                                sb.append("\n  inv[").append(i).append("]: ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                            }
                            LOG.info(sb.toString());
                            return;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            }
            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 0; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                                sb.append("\n  inventory[").append(i).append("]: ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                            }
                            LOG.info(sb.toString());
                            return;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            // Capability fallback
            try {
                entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
                    StringBuilder sbb = new StringBuilder(sb);
                    IItemHandler h = handler;
                    for (int i = 0; i < h.getSlots(); i++) {
                        ItemStack st = h.getStackInSlot(i);
                        ResourceLocation key = st == null || st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem());
                        sbb.append("\n  capability.slot[").append(i).append("]: ").append(key == null ? "<empty>" : key.toString()).append(" x").append(st == null ? 0 : st.getCount());
                    }
                    LOG.info(sbb.toString());
                });
            } catch (Throwable ignored) {}
            LOG.info(sb.append("\n  (no accessible inventory found)").toString());
        } catch (Throwable t) {
            LOG.warn("Failed to dump inventory for entity {}", entity.getUUID(), t);
        }
    }

    // Fuzzy count: count items whose id string or path contains the poolId path or namespace
    private static int countAmmoInInventoryFuzzy(Entity entity, ResourceLocation poolId) {
        int total = 0;
        try {
            Method getInventory = null;
            try { getInventory = entity.getClass().getMethod("getInventory"); } catch (NoSuchMethodException ignored) {}
            String poolPath = poolId == null ? "" : poolId.getPath();
            String poolNs = poolId == null ? "" : poolId.getNamespace();
            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    int start = Math.min(6, container.getContainerSize());
                    for (int i = start; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                            if (key != null) {
                                String path = key.getPath();
                                String id = key.toString();
                                if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                    total += st.getCount();
                                }
                            }
                        }
                    }
                    return total;
                } else {
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 6; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (key != null) {
                                        String path = key.getPath();
                                        String id = key.toString();
                                        if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                            total += st.getCount();
                                        }
                                    }
                                }
                            }
                            return total;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            }

            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    if ("com.talhanation.recruits.inventory.RecruitSimpleContainer".equals(inv.getClass().getName())) {
                        try {
                            Method sizeM = inv.getClass().getMethod("getContainerSize");
                            Method getItemM = inv.getClass().getMethod("getItem", int.class);
                            int size = (Integer) sizeM.invoke(inv);
                            for (int i = 6; i < size; i++) {
                                Object stObj = getItemM.invoke(inv, i);
                                if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (key != null) {
                                        String path = key.getPath();
                                        String id = key.toString();
                                        if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                            total += st.getCount();
                                        }
                                    }
                                }
                            }
                            return total;
                        } catch (Throwable ignored) {}
                    }
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            for (int i = 6; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (key != null) {
                                        String path = key.getPath();
                                        String id = key.toString();
                                        if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                            total += st.getCount();
                                        }
                                    }
                                }
                            }
                            return total;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // capability fallback: skip first 6 slots
            try {
                var optional = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
                if (optional.isPresent()) {
                    IItemHandler h = optional.orElse(null);
                    if (h != null) {
                        int start = Math.min(6, h.getSlots());
                        for (int i = start; i < h.getSlots(); i++) {
                            ItemStack st = h.getStackInSlot(i);
                            if (!st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (key != null) {
                                    String path = key.getPath();
                                    String id = key.toString();
                                    if (path.contains(poolPath) || id.contains(poolPath) || key.getNamespace().equals(poolNs) || id.contains(poolNs)) {
                                        total += st.getCount();
                                    }
                                }
                            }
                        }
                        return total;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.debug("countAmmoInInventoryFuzzy reflection failed", t);
        }
        return total;
    }

    public static int countAmmoInInventory(Entity entity, ResourceLocation ammoId) {
        try {
            // 1) try getInventory()
            Method getInventory = null;
            try { getInventory = entity.getClass().getMethod("getInventory"); } catch (NoSuchMethodException ignored) {}
            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    int total = 0;
                    int start = Math.min(6, container.getContainerSize());
                    for (int i = start; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                            if (ammoId.equals(key)) total += st.getCount();
                        }
                    }
                    return total;
                }
                // handle RecruitSimpleContainer specifically if it isn't a Container (defensive)
                if (inv != null && "com.talhanation.recruits.inventory.RecruitSimpleContainer".equals(inv.getClass().getName())) {
                    try {
                        Method sizeM = inv.getClass().getMethod("getContainerSize");
                        Method getItemM = inv.getClass().getMethod("getItem", int.class);
                        int size = (Integer) sizeM.invoke(inv);
                        int total = 0;
                        for (int i = 6; i < size; i++) { // only general inventory
                            Object stObj = getItemM.invoke(inv, i);
                            if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) total += st.getCount();
                            }
                        }
                        return total;
                    } catch (Throwable ignored) {}
                }
                try {
                    Field itemsField = inv.getClass().getDeclaredField("items");
                    itemsField.setAccessible(true);
                    Object listObj = itemsField.get(inv);
                    if (listObj instanceof List<?> rawList) {
                        @SuppressWarnings("unchecked")
                        List<ItemStack> list = (List<ItemStack>) rawList;
                        int total = 0;
                        for (int i = 6; i < list.size(); i++) { // skip 0..5
                            ItemStack st = list.get(i);
                            if (st != null && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) total += st.getCount();
                            }
                        }
                        return total;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }

            // 2) reflect 'inventory' field
            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    if ("com.talhanation.recruits.inventory.RecruitSimpleContainer".equals(inv.getClass().getName())) {
                        try {
                            Method sizeM = inv.getClass().getMethod("getContainerSize");
                            Method getItemM = inv.getClass().getMethod("getItem", int.class);
                            int size = (Integer) sizeM.invoke(inv);
                            int total = 0;
                            for (int i = 6; i < size; i++) {
                                Object stObj = getItemM.invoke(inv, i);
                                if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) total += st.getCount();
                                }
                            }
                            return total;
                        } catch (Throwable ignored) {}
                    }
                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList;
                            int total = 0;
                            for (int i = 6; i < list.size(); i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) total += st.getCount();
                                }
                            }
                            return total;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // 3) capability fallback: IItemHandler
            try {
                var optional = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
                if (optional.isPresent()) {
                    IItemHandler h = optional.orElse(null);
                    if (h != null) {
                        int total = 0;
                        int start = Math.min(6, h.getSlots()); // skip 0..5 if present
                        for (int i = start; i < h.getSlots(); i++) {
                            ItemStack st = h.getStackInSlot(i);
                            if (!st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) total += st.getCount();
                            }
                        }
                        return total;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.debug("countAmmoInInventory reflection failed", t);
        }
        return 0;
    }

    public static int removeAmmoFromInventory(Entity entity, ResourceLocation ammoId, int amount) {
        if (amount <= 0) return 0;
        int removedTotal = 0;
        try {
            // 1) try getInventory()
            Method getInventory = null;
            try {
                getInventory = entity.getClass().getMethod("getInventory");
            } catch (NoSuchMethodException ignored) {}

            if (getInventory != null) {
                Object inv = getInventory.invoke(entity);
                if (inv instanceof Container container) {
                    int start = Math.min(6, container.getContainerSize());
                    for (int i = start; i < container.getContainerSize() && amount > 0; i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                            if (ammoId.equals(key)) {
                                int take = Math.min(amount, st.getCount());
                                container.removeItem(i, take);
                                removedTotal += take;
                                amount -= take;
                            }
                        }
                    }
                    return removedTotal;
                }
                // RecruitSimpleContainer specific
                if (inv != null && "com.talhanation.recruits.inventory.RecruitSimpleContainer".equals(inv.getClass().getName())) {
                    try {
                        Method sizeM = inv.getClass().getMethod("getContainerSize");
                        Method getItemM = inv.getClass().getMethod("getItem", int.class);
                        Method setItemM = inv.getClass().getMethod("setItem", int.class, ItemStack.class);
                        int size = (Integer) sizeM.invoke(inv);
                        for (int i = 6; i < size && amount > 0; i++) {
                            Object stObj = getItemM.invoke(inv, i);
                            if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) {
                                    int take = Math.min(amount, st.getCount());
                                    st.shrink(take);
                                    removedTotal += take;
                                    amount -= take;
                                    if (st.getCount() <= 0) setItemM.invoke(inv, i, ItemStack.EMPTY);
                                }
                            }
                        }
                        try { Method setChanged = inv.getClass().getMethod("setChanged"); setChanged.invoke(inv); } catch (NoSuchMethodException ignored) {}
                        return removedTotal;
                    } catch (Throwable ignored) {}
                }

                try {
                    Field itemsField = inv.getClass().getDeclaredField("items");
                    itemsField.setAccessible(true);
                    Object listObj = itemsField.get(inv);
                    if (listObj instanceof List<?> rawList) {
                        @SuppressWarnings("unchecked")
                        List<ItemStack> list = (List<ItemStack>) rawList;
                        for (int i = 6; i < list.size() && amount > 0; i++) {
                            ItemStack st = list.get(i);
                            if (st != null && !st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) {
                                    int take = Math.min(amount, st.getCount());
                                    st.shrink(take);
                                    removedTotal += take;
                                    amount -= take;
                                    if (st.getCount() <= 0) list.set(i, ItemStack.EMPTY);
                                }
                            }
                        }
                        try {
                            Method setChanged = inv.getClass().getMethod("setChanged");
                            setChanged.invoke(inv);
                        } catch (NoSuchMethodException ignored) {}
                        return removedTotal;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }

            // 2) reflect 'inventory' field
            try {
                Field invField = entity.getClass().getDeclaredField("inventory");
                invField.setAccessible(true);
                Object inv = invField.get(entity);
                if (inv != null) {
                    if ("com.talhanation.recruits.inventory.RecruitSimpleContainer".equals(inv.getClass().getName())) {
                        try {
                            Method sizeM = inv.getClass().getMethod("getContainerSize");
                            Method getItemM = inv.getClass().getMethod("getItem", int.class);
                            Method setItemM = inv.getClass().getMethod("setItem", int.class, ItemStack.class);
                            int size = (Integer) sizeM.invoke(inv);
                            for (int i = 6; i < size && amount > 0; i++) {
                                Object stObj = getItemM.invoke(inv, i);
                                if (stObj instanceof ItemStack st && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) {
                                        int take = Math.min(amount, st.getCount());
                                        st.shrink(take);
                                        removedTotal += take;
                                        amount -= take;
                                        if (st.getCount() <= 0) setItemM.invoke(inv, i, ItemStack.EMPTY);
                                    }
                                }
                            }
                            try { Method setChanged = inv.getClass().getMethod("setChanged"); setChanged.invoke(inv); } catch (NoSuchMethodException ignored) {}
                            return removedTotal;
                        } catch (Throwable ignored) {}
                    }

                    try {
                        Field itemsField = inv.getClass().getDeclaredField("items");
                        itemsField.setAccessible(true);
                        Object listObj = itemsField.get(inv);
                        if (listObj instanceof List<?> rawList2) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> list = (List<ItemStack>) rawList2;
                            for (int i = 6; i < list.size() && amount > 0; i++) {
                                ItemStack st = list.get(i);
                                if (st != null && !st.isEmpty()) {
                                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                    if (ammoId.equals(key)) {
                                        int take = Math.min(amount, st.getCount());
                                        st.shrink(take);
                                        removedTotal += take;
                                        amount -= take;
                                        if (st.getCount() <= 0) list.set(i, ItemStack.EMPTY);
                                    }
                                }
                            }
                            try {
                                Method setChanged = inv.getClass().getMethod("setChanged");
                                setChanged.invoke(inv);
                            } catch (NoSuchMethodException ignored) {}
                            return removedTotal;
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // 3) capability fallback: IItemHandler (skip first 6 slots)
            try {
                var optional = entity.getCapability(ForgeCapabilities.ITEM_HANDLER);
                if (optional.isPresent()) {
                    IItemHandler h = optional.orElse(null);
                    if (h != null) {
                        int start = Math.min(6, h.getSlots());
                        for (int i = start; i < h.getSlots() && amount > 0; i++) {
                            ItemStack st = h.getStackInSlot(i);
                            if (!st.isEmpty()) {
                                ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
                                if (ammoId.equals(key)) {
                                    int toTake = Math.min(amount, st.getCount());
                                    ItemStack extracted = h.extractItem(i, toTake, false);
                                    if (!extracted.isEmpty()) {
                                        removedTotal += extracted.getCount();
                                        amount -= extracted.getCount();
                                    }
                                }
                            }
                        }
                        return removedTotal;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.debug("removeAmmoFromInventory reflection failed", t);
        }

        return removedTotal;
    }

    private static int getAmmoCountFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.getInt("AmmoCount") : 0;
    }

    // Minimal helper to create GunConfig object used by your existing code
    private static GunConfig makeGunConfig(ResourceLocation itemKey, int maxAmmo, GunConfig.ReloadKind kind,
                                           ResourceLocation poolId, int reloadTimeTicks) {
        if (itemKey == null) itemKey = ResourceLocation.tryParse("unknown:unknown");
        return new GunConfig(itemKey, maxAmmo, kind, poolId, reloadTimeTicks);
    }

    // Scanner config manager (same as before)
    private static final class ScannerConfigManager {
        private static final String SUBPATH = "just_more_than_enough_guns";
        private static final String FILE_NAME = "scanner.json";
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private volatile boolean loaded = false;
        private ScannerConfig config;

        private static final class ScannerConfig {
            int intervalTicks = 100;
            int radius = 64;
        }

        synchronized void ensureLoaded() {
            if (loaded) return;
            loaded = true;

            File cfgDir = FMLPaths.CONFIGDIR.get().toFile();
            File modDir = new File(cfgDir, SUBPATH);
            if (!modDir.exists() && !modDir.mkdirs()) {
                LOG.warn("ScannerConfigManager: failed to create config dir " + modDir.getAbsolutePath());
            }
            File cfgFile = new File(modDir, FILE_NAME);
            if (!cfgFile.exists()) {
                this.config = new ScannerConfig();
                try (Writer w = new OutputStreamWriter(new FileOutputStream(cfgFile), StandardCharsets.UTF_8)) {
                    GSON.toJson(this.config, w);
                    LOG.info("ScannerConfigManager: wrote default scanner config to {}", cfgFile.getAbsolutePath());
                } catch (IOException ex) {
                    LOG.error("ScannerConfigManager: failed to write default config", ex);
                }
                return;
            }

            try (Reader r = new InputStreamReader(new FileInputStream(cfgFile), StandardCharsets.UTF_8)) {
                Type type = new TypeToken<ScannerConfig>() {}.getType();
                ScannerConfig read = GSON.fromJson(r, type);
                if (read == null) {
                    this.config = new ScannerConfig();
                } else {
                    this.config = read;
                }
                LOG.info("ScannerConfigManager: loaded scanner config (intervalTicks={}, radius={})", this.config.intervalTicks, this.config.radius);
            } catch (IOException ex) {
                LOG.error("ScannerConfigManager: failed to read config, using defaults", ex);
                this.config = new ScannerConfig();
            }
        }

        ScannerConfig get() {
            if (!loaded) ensureLoaded();
            return config != null ? config : new ScannerConfig();
        }
    }
}