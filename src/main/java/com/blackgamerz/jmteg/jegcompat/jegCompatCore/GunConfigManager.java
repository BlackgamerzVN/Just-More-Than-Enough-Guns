package com.blackgamerz.jmteg.jegcompat.jegCompatCore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads/writes config/just_more_than_enough_guns/guns.json which maps JEG gun item IDs
 * to mob-pool behavior used by the bridge mod.
 *
 * Usage: call GunConfigManager.ensureLoaded() during initialization or before accessing GUN_CONFIGS.
 *
 * NOTE: Replace package name (your.package) with your actual package and adapt file layout if needed.
 */
public final class GunConfigManager {
    private static final String SUBPATH = "jmteg";
    private static final String FILE_NAME = "guns.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Public map used by MobAiInjector (key = gun item ResourceLocation)
    public static final Map<ResourceLocation, GunConfig> GUN_CONFIGS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private GunConfigManager() {}

    // Small POJO representing a JSON entry
    private static class RawEntry {
        String item;
        int maxAmmo;
        String reloadKind;
        String pool;
    }

    /**
     * Ensure the JSON config is loaded into GUN_CONFIGS. If the file doesn't exist it
     * will be written with a reasonable default derived from JEG's GunGen.
     */
    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        try {
            File cfgDir = FMLPaths.CONFIGDIR.get().toFile();
            File modDir = new File(cfgDir, SUBPATH);
            if (!modDir.exists() && !modDir.mkdirs()) {
                System.err.println("GunConfigManager: failed to create config directory " + modDir.getAbsolutePath());
            }
            File cfgFile = new File(modDir, FILE_NAME);
            if (!cfgFile.exists()) {
                // write defaults
                try (Writer w = new OutputStreamWriter(new FileOutputStream(cfgFile), StandardCharsets.UTF_8)) {
                    GSON.toJson(defaultList(), w);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

            // read file
            try (Reader r = new InputStreamReader(new FileInputStream(cfgFile), StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<RawEntry>>(){}.getType();
                List<RawEntry> entries = GSON.fromJson(r, listType);
                if (entries != null) {
                    for (RawEntry e : entries) {
                        ResourceLocation itemId = ResourceLocation.tryParse(e.item);
                        ResourceLocation poolId = ResourceLocation.tryParse(e.pool);
                        if (itemId == null || poolId == null) {
                            System.err.println("GunConfigManager: invalid resource location in JSON entry, skipping: " + e);
                            continue;
                        }
                        GunConfig.ReloadKind kind;
                        try {
                            kind = GunConfig.ReloadKind.valueOf(e.reloadKind.toUpperCase(Locale.ROOT));
                        } catch (Exception ex) {
                            kind = GunConfig.ReloadKind.PROJECTILE_OR_MAG;
                        }
                        GUN_CONFIGS.put(itemId, new GunConfig(itemId, e.maxAmmo, kind, poolId));
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } catch (Throwable t) {
            // defensive fallback: populate a couple of defaults in-memory
            t.printStackTrace();
            for (GunConfig c : fallbackDefaults()) {
                if (c.itemId != null) GUN_CONFIGS.put(c.itemId, c);
            }
        }
    }

    // Returns the default JSON list (this will be written on first run if no file exists).
    // This list is derived from JEG's src/main/java/.../datagen/GunGen.java and ModItems registrations.
    private static List<RawEntry> defaultList() {
        List<RawEntry> list = new ArrayList<>();
        add(list, "jeg:abstract_gun", 30, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");
        add(list, "jeg:finger_gun", 1, "PROJECTILE_OR_MAG", "minecraft:air");

        add(list, "jeg:revolver", 8, "PROJECTILE_OR_MAG", "jeg:pistol_ammo");
        add(list, "jeg:waterpipe_shotgun", 1, "PROJECTILE_OR_MAG", "jeg:handmade_shell");
        add(list, "jeg:custom_smg", 24, "PROJECTILE_OR_MAG", "jeg:pistol_ammo");
        add(list, "jeg:double_barrel_shotgun", 2, "PROJECTILE_OR_MAG", "jeg:handmade_shell");

        add(list, "jeg:semi_auto_pistol", 10, "PROJECTILE_OR_MAG", "jeg:pistol_ammo");
        add(list, "jeg:semi_auto_rifle", 16, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");

        add(list, "jeg:assault_rifle", 30, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");
        add(list, "jeg:pump_shotgun", 6, "PROJECTILE_OR_MAG", "jeg:shotgun_shell");

        add(list, "jeg:combat_pistol", 15, "PROJECTILE_OR_MAG", "jeg:pistol_ammo");
        add(list, "jeg:burst_rifle", 30, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");
        add(list, "jeg:combat_rifle", 30, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");

        add(list, "jeg:bolt_action_rifle", 4, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");
        add(list, "jeg:flare_gun", 1, "PROJECTILE_OR_MAG", "jeg:flare");

        add(list, "jeg:blossom_rifle", 30, "PROJECTILE_OR_MAG", "jeg:spectre_round");
        add(list, "jeg:holy_shotgun", 8, "PROJECTILE_OR_MAG", "jeg:spectre_round");

        add(list, "jeg:atlantean_spear", 6, "PROJECTILE_OR_MAG", "jeg:water_bomb");
        add(list, "jeg:typhoonee", 8, "SINGLE_ITEM", "minecraft:water_bucket");

        add(list, "jeg:repeating_shotgun", 8, "PROJECTILE_OR_MAG", "jeg:shotgun_shell");
        add(list, "jeg:infantry_rifle", 8, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");
        add(list, "jeg:service_rifle", 30, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");

        add(list, "jeg:hollenfire_mk2", 40, "PROJECTILE_OR_MAG", "jeg:blaze_round");
        add(list, "jeg:soulhunter_mk2", 30, "PROJECTILE_OR_MAG", "jeg:blaze_round");

        add(list, "jeg:subsonic_rifle", 20, "PROJECTILE_OR_MAG", "minecraft:echo_shard");
        add(list, "jeg:supersonic_shotgun", 6, "PROJECTILE_OR_MAG", "minecraft:echo_shard");

        add(list, "jeg:hypersonic_cannon", 15, "SINGLE_ITEM", "minecraft:sculk_catalyst");
        add(list, "jeg:rocket_launcher", 1, "PROJECTILE_OR_MAG", "jeg:rocket");

        add(list, "jeg:compound_bow", 1, "PROJECTILE_OR_MAG", "minecraft:arrow");
        add(list, "jeg:primitive_bow", 1, "PROJECTILE_OR_MAG", "minecraft:arrow");

        add(list, "jeg:grenade_launcher", 1, "PROJECTILE_OR_MAG", "jeg:grenade");
        add(list, "jeg:light_machine_gun", 100, "PROJECTILE_OR_MAG", "jeg:rifle_ammo");

        add(list, "jeg:flamethrower", 200, "SINGLE_ITEM", "minecraft:lava_bucket");
        add(list, "jeg:minigun", 1, "PROJECTILE_OR_MAG", "jeg:pistol_ammo");

        // Additional entries present in GunGen that are occasionally registered:
        add(list, "jeg:repeating_shotgun", 8, "PROJECTILE_OR_MAG", "jeg:shotgun_shell"); // duplicate tolerated
        add(list, "jeg:burst_rifle", 30, "PROJECTILE_OR_MAG", "jeg:rifle_ammo"); // duplicate tolerated

        return list;
    }

    private static void add(List<RawEntry> list, String item, int maxAmmo, String kind, String pool) {
        RawEntry e = new RawEntry();
        e.item = item;
        e.maxAmmo = maxAmmo;
        e.reloadKind = kind;
        e.pool = pool;
        list.add(e);
    }

    private static List<GunConfig> fallbackDefaults() {
        List<GunConfig> list = new ArrayList<>();
        ResourceLocation bolt = ResourceLocation.tryParse("jeg:bolt_action_rifle");
        ResourceLocation rifleAmmo = ResourceLocation.tryParse("jeg:rifle_ammo");
        if (bolt != null && rifleAmmo != null) list.add(new GunConfig(bolt, 4, GunConfig.ReloadKind.PROJECTILE_OR_MAG, rifleAmmo));
        ResourceLocation semi = ResourceLocation.tryParse("jeg:semi_auto_pistol");
        ResourceLocation pistolAmmo = ResourceLocation.tryParse("jeg:pistol_ammo");
        if (semi != null && pistolAmmo != null) list.add(new GunConfig(semi, 10, GunConfig.ReloadKind.PROJECTILE_OR_MAG, pistolAmmo));
        return list;
    }
}