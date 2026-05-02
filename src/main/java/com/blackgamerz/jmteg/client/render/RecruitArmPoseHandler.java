package com.blackgamerz.jmteg.client.render;

import com.blackgamerz.jmteg.jegcompat.jegCompatCore.GunConfigManager;
import com.blackgamerz.jmteg.jegcompat.jegCompatCore.GunConfig;
import com.blackgamerz.jmteg.recruitcompat.JustEnoughGunsCompat;
import com.blackgamerz.jmteg.jegcompat.GunDataManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Client-side renderer helper for Recruits holding Just Enough Guns weapons.
 *
 * Behavior:
 * - Forces BLOCK pose on BOTH arms for pistol-like JEG guns (pistol ammo AND maxAmmo <= SMG_THRESHOLD)
 * - Forces CROSSBOW_CHARGE pose on BOTH arms for non-pistol / large-mag JEG guns (charging animation)
 * - Forces CROSSBOW_HOLD pose on BOTH arms for shotguns
 * - Forces BOW_AND_ARROW pose on MAIN arm for rocket-type JEG weapons (other arm ITEM/EMPTY)
 *
 * Robustness improvements:
 * - Save original arm poses before overriding and restore them after render (RenderLivingEvent.Post).
 * - Clear per-model cached entries when entity equipment changes or when an entity joins the world (client-side)
 *   so the next render tick recomputes and applies poses immediately.
 * - Client tick scanner watches nearby mobs' main-hand item for changes (covers cases where equip packets arrive
 *   but no other event triggers).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "jmteg")
public final class RecruitArmPoseHandler {
    private static final Logger LOGGER = LogManager.getLogger("JMTEG-RecruitArmPoseHandler");

    // threshold (inclusive): max ammo <= this => treat as pistol (eligible for pistol/blocking behavior).
    private static final int SMG_THRESHOLD = 20;

    // Save original poses per-model instance during the frame so we can restore post-render.
    private static final Map<HumanoidModel<?>, ArmPosePair> originalPoses = new WeakHashMap<>();

    // Track last-seen main-hand for nearby mobs on client so we can detect equips.
    private static final Map<LivingEntity, ItemStack> lastSeenMainHand = new WeakHashMap<>();

    private RecruitArmPoseHandler() {}

    private static final class ArmPosePair {
        final HumanoidModel.ArmPose right;
        final HumanoidModel.ArmPose left;
        ArmPosePair(HumanoidModel.ArmPose r, HumanoidModel.ArmPose l) { this.right = r; this.left = l; }
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<LivingEntity, ?> event) {
        LivingEntity ent = event.getEntity();

        // Only target Recruits (talhanation's mod) / humanoid mobs that use a HumanoidModel in MobRenderer
        if (!(event.getRenderer() instanceof MobRenderer<?, ?> mobRenderer)) return;
        if (!(mobRenderer.getModel() instanceof HumanoidModel<?> humanoidModel)) return;

        // Quick filter: must be holding something in main hand (JEG guns are items)
        ItemStack main = ent.getMainHandItem();
        if (main == null || main.isEmpty()) return;

        // Use the helper in our mod to detect JEG gun stacks (soft-dependency)
        if (!JustEnoughGunsCompat.isJegGun(main)) return;

        // Save originals (if not already saved for this model instance)
        synchronized (originalPoses) {
            if (!originalPoses.containsKey(humanoidModel)) {
                originalPoses.put(humanoidModel,
                        new ArmPosePair(humanoidModel.rightArmPose, humanoidModel.leftArmPose));
            }
        }

        // --- Determine gun characteristics (same heuristics as before) ---
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(main.getItem());
        GunConfig cfg = itemId != null ? GunConfigManager.GUN_CONFIGS.get(itemId) : null;
        ResourceLocation poolId = cfg != null ? cfg.poolId : null;

        if (poolId == null) {
            // Try JSON/NBT-based resolution if GunConfig not present
            try {
                String ammoJson = GunDataManager.getAmmoTypeFromJson(main);
                if (ammoJson != null) {
                    String cleaned = ammoJson.replace("\"", "");
                    poolId = ResourceLocation.tryParse(cleaned);
                }
            } catch (Throwable t) {
                LOGGER.debug("Ammo JSON parse failed", t);
            }
        }

        Integer dynamicMax = JustEnoughGunsCompat.getJegGunMaxAmmo(main);
        int maxAmmo = (cfg != null && cfg.maxAmmo > 0) ? cfg.maxAmmo : (dynamicMax != null ? dynamicMax : 1);

        boolean isPistolAmmo = poolId != null && poolId.getPath().contains("pistol");
        boolean isPistol = isPistolAmmo && maxAmmo <= SMG_THRESHOLD;
        boolean isRocket = (poolId != null && poolId.getPath().contains("rocket"))
                || (itemId != null && (itemId.getPath().contains("bazooka") || itemId.getPath().contains("rocket")));
        boolean isShotgun = poolId != null && (poolId.getPath().contains("shotgun") || poolId.getPath().contains("shell") || poolId.getPath().contains("handmade_shell"));

        // find main arm
        HumanoidArm mainArm = ent.getMainArm();

        // --- Apply poses using conservative assignments (both arms) ---
        // Default: leave untouched unless one of our cases match
        try {
            if (isRocket) {
                // Rocket: main-hand shows BOW_AND_ARROW. Other arm remains ITEM or EMPTY.
                if (mainArm == HumanoidArm.RIGHT) {
                    humanoidModel.rightArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
                    humanoidModel.leftArmPose = ent.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
                } else {
                    humanoidModel.leftArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
                    humanoidModel.rightArmPose = ent.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
                }
                return;
            }

            if (isPistol) {
                // Pistol: both arms BLOCK
                humanoidModel.rightArmPose = HumanoidModel.ArmPose.BLOCK;
                humanoidModel.leftArmPose = HumanoidModel.ArmPose.BLOCK;
                return;
            }

            if (isShotgun) {
                // Shotgun: both arms CROSSBOW_HOLD
                humanoidModel.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
                humanoidModel.leftArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
                return;
            }

            // Non-pistol or large-mag pistol => CROSSBOW_CHARGE on BOTH arms (charging animation)
            humanoidModel.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            humanoidModel.leftArmPose = HumanoidModel.ArmPose.CROSSBOW_CHARGE;
        } catch (Throwable t) {
            LOGGER.debug("Failed to apply recruit arm poses", t);
        }
    }

    /**
     * Restore original arm poses after render so other renders / frames are not impacted.
     */
    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<LivingEntity, ?> event) {
        if (!(event.getRenderer() instanceof MobRenderer<?, ?> mobRenderer)) return;
        if (!(mobRenderer.getModel() instanceof HumanoidModel<?> humanoidModel)) return;

        ArmPosePair orig;
        synchronized (originalPoses) {
            orig = originalPoses.remove(humanoidModel);
        }
        if (orig != null) {
            try {
                humanoidModel.rightArmPose = orig.right;
                humanoidModel.leftArmPose = orig.left;
            } catch (Throwable t) {
                LOGGER.debug("Failed to restore original recruit arm poses", t);
            }
        }
    }

    /**
     * Clear any cached model mapping for this entity when equipment changes so the next render tick
     * re-evaluates and applies correct poses immediately.
     */
    @SubscribeEvent
    public static void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        var ent = event.getEntity();
        if (!(ent instanceof LivingEntity)) return;

        clearModelMappingForEntity((LivingEntity) ent);
    }

    /**
     * When entity joins client world, clear mapping so first-render uses fresh evaluation.
     */
    @SubscribeEvent
    public static void onEntityJoinClient(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            if (event.getEntity() instanceof LivingEntity le) {
                clearModelMappingForEntity(le);
            }
        }
    }

    /**
     * Client tick: lightweight scan of nearby mobs to detect main-hand changes and trigger model-mapping clear.
     * This covers cases where the client receives an equipment packet or NBT change but no higher-level event
     * we can catch for that entity.
     */
    // Add a simple throttle counter near top of class
    private static int clientTickCounter = 0;
    private static final int CLIENT_TICK_SCAN_INTERVAL = 4; // scan every 4 client ticks (~every 0.2s at 20tps)
    private static final double SCAN_RADIUS = 96.0D; // increase radius a bit

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        clientTickCounter++;
        if ((clientTickCounter % CLIENT_TICK_SCAN_INTERVAL) != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        try {
            AABB box = mc.player.getBoundingBox().inflate(SCAN_RADIUS);
            List<Mob> nearby = mc.level.getEntitiesOfClass(Mob.class, box, e -> true);

            // Drop stale entries
            lastSeenMainHand.entrySet().removeIf(en -> {
                LivingEntity key = en.getKey();
                return key == null || key.level() == null || !key.isAlive() || !key.level().isClientSide();
            });

            for (Mob mob : nearby) {
                // Optional small optimization: only inspect likely recruits
                String cname = mob.getClass().getSimpleName().toLowerCase();
                boolean maybeRecruit = cname.contains("recruit") || cname.contains("talhanation") || cname.contains("villager");
                // If you prefer broader coverage, skip this filter.
                if (!maybeRecruit) {
                    // still allow if lastSeenMainHand contains this mob (we are tracking it)
                    if (!lastSeenMainHand.containsKey(mob)) continue;
                }

                ItemStack cur = mob.getMainHandItem();
                ItemStack cached = lastSeenMainHand.get(mob);
                boolean changed = false;

                if (cached == null || cached.isEmpty()) {
                    if (cur != null && !cur.isEmpty()) changed = true;
                } else {
                    if (cur == null || cur.isEmpty()) {
                        changed = true;
                    } else if (cur.getItem() != cached.getItem()) {
                        changed = true;
                    } else {
                        // same item type; compare NBT only when necessary
                        if (!Objects.equals(cur.getTag(), cached.getTag())) changed = true;
                    }
                }

                if (changed) {
                    lastSeenMainHand.put(mob, (cur == null || cur.isEmpty()) ? ItemStack.EMPTY : cur.copy());
                    clearModelMappingForEntity(mob);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Client tick scan failed", t);
        }
    }

    // Replace existing clearModelMappingForEntity with this implementation
    private static void clearModelMappingForEntity(LivingEntity ent) {
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderDispatcher disp = mc.getEntityRenderDispatcher();
            if (disp == null) return;
            EntityRenderer<?> renderer = disp.getRenderer(ent);
            if (renderer == null) return;

            // If MobRenderer, prefer getModel()
            if (renderer instanceof MobRenderer<?, ?> mobRenderer) {
                try {
                    Object model = mobRenderer.getModel();
                    if (model instanceof HumanoidModel<?> hm) {
                        synchronized (originalPoses) { originalPoses.remove(hm); }
                        return;
                    }
                } catch (Throwable ignored) {
                    // continue to reflective search
                }
            }

            // Reflectively search any field on the renderer that *contains* a HumanoidModel instance.
            // This is robust across different renderers / mappings / obfuscations.
            Class<?> cls = renderer.getClass();
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(renderer);
                        if (val instanceof HumanoidModel<?> hm) {
                            synchronized (originalPoses) { originalPoses.remove(hm); }
                            // do NOT return immediately; it may have multiple model fields (remove all)
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) {
            LOGGER.debug("clearModelMappingForEntity failed", t);
        }
    }
}