package com.blackgamerz.jmteg.client.render;

import com.blackgamerz.jmteg.jegcompat.jegCompatCore.GunConfigManager;
import com.blackgamerz.jmteg.jegcompat.jegCompatCore.GunConfig;
import com.blackgamerz.jmteg.recruitcompat.JustEnoughGunsCompat;
import com.blackgamerz.jmteg.jegcompat.GunDataManager;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Client-side renderer helper:
 * - Forces BLOCK pose on BOTH arms for recruits holding pistol-like JEG guns (pistol ammo AND maxAmmo <= SMG_THRESHOLD)
 * - Forces CROSSBOW_CHARGE pose on BOTH arms for non-pistol / large-mag JEG guns (charging animation)
 * - Forces CROSSBOW_HOLD pose on BOTH arms for shotguns (give shotguns the crossbow holding animation)
 * - Forces BOW_AND_ARROW pose on MAIN arm for JEG rocket launchers (ammo pool contains 'rocket' or item id contains bazooka/rocket)
 *
 * This class does not modify Recruits or JEG classes. It performs a render-time override of the HumanoidModel arm poses.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "jmteg")
public final class RecruitArmPoseHandler {

    // threshold (inclusive): max ammo <= this => treat as pistol (eligible for pistol/blocking behavior).
    private static final int SMG_THRESHOLD = 20;

    private RecruitArmPoseHandler() {}

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<LivingEntity, ?> event) {
        LivingEntity ent = event.getEntity();

        // Only target Recruits (talhanation's mod) - we don't import a Recruit class symbol here,
        // we just check that it's a mob with humanoid model in MobRenderer
        if (!(event.getRenderer() instanceof MobRenderer<?, ?> mobRenderer)) return;
        if (!(mobRenderer.getModel() instanceof HumanoidModel<?> humanoidModel)) return;

        // Fast filter: entity must be holding something
        ItemStack main = ent.getMainHandItem();
        if (main == null || main.isEmpty()) return;

        // Use the helper in our mod to detect JEG gun stacks
        if (!JustEnoughGunsCompat.isJegGun(main)) {
            // not a JEG gun: nothing to change (Recruits' existing logic will handle bows/crossbows/etc).
            return;
        }

        // Get canonical item id
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(main.getItem());
        GunConfig cfg = itemId != null ? GunConfigManager.GUN_CONFIGS.get(itemId) : null;

        // Determine the ammo/pool id if present
        ResourceLocation poolId = cfg != null ? cfg.poolId : null;

        // Fallback: try JSON/NBT-based resolution if GunConfig not present
        if (poolId == null) {
            String ammoJson = GunDataManager.getAmmoTypeFromJson(main);
            if (ammoJson != null) {
                String cleaned = ammoJson.replace("\"", ""); // GunDataManager returns raw json strings sometimes
                poolId = ResourceLocation.tryParse(cleaned);
            }
        }

        // Determine maxAmmo (try configured, then reflection helper)
        Integer dynamicMax = JustEnoughGunsCompat.getJegGunMaxAmmo(main);
        int maxAmmo = (cfg != null && cfg.maxAmmo > 0) ? cfg.maxAmmo : (dynamicMax != null ? dynamicMax : 1);

        // Determine type flags
        boolean isPistolAmmo = poolId != null && poolId.getPath().contains("pistol");
        boolean isPistol = isPistolAmmo && maxAmmo <= SMG_THRESHOLD;
        boolean isRocket = (poolId != null && poolId.getPath().contains("rocket"))
                || (itemId != null && (itemId.getPath().contains("bazooka") || itemId.getPath().contains("rocket")));
        boolean isShotgun = poolId != null && (poolId.getPath().contains("shotgun") || poolId.getPath().contains("shell") || poolId.getPath().contains("handmade_shell"));

        // find main arm
        HumanoidArm mainArm = ent.getMainArm();

        // Apply poses according to rules:
        // - pistol JEG weapon => BOTH arms BLOCK (blocking animation)
        // - shotgun => BOTH arms CROSSBOW_HOLD (give shotguns the crossbow holding animation)
        // - non-pistol / large-mag JEG weapon => BOTH arms CROSSBOW_CHARGE (crossbow charging animation)
        // - rocket JEG weapon => MAIN arm BOW_AND_ARROW, other arm ITEM/EMPTY as appropriate
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
            // Pistol: block on BOTH arms
            humanoidModel.rightArmPose = HumanoidModel.ArmPose.BLOCK;
            humanoidModel.leftArmPose = HumanoidModel.ArmPose.BLOCK;
            return;
        }

        if (isShotgun) {
            // Shotgun: both arms use CROSSBOW_HOLD (per your request)
            humanoidModel.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
            humanoidModel.leftArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
            return;
        }

        // Non-pistol or large-mag pistol => CROSSBOW_CHARGE on BOTH arms (charging animation)
        humanoidModel.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_CHARGE;
        humanoidModel.leftArmPose = HumanoidModel.ArmPose.CROSSBOW_CHARGE;

        // Note: this override runs immediately before render and is client-side only.
    }
}