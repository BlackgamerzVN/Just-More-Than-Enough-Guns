package com.blackgamerz.jmteg.recruitcompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config manager for the recruit role system.
 *
 * Reads (and writes on first run) {@code config/jmteg/recruit_roles.json}.
 *
 * The JSON encodes two things:
 *  1. {@code roles}  – mapping from RecruitGunRole name → list of applicable gun item IDs +
 *                     an optional config-level fallback override.
 *  2. {@code recruit_tiers} – per-recruit-class (BOWMAN / CROSSBOWMAN) ordered list of
 *                     accessible roles with relative preference weights and a
 *                     {@code fallback_to_any_gun} flag.
 *
 * All data is stored in {@link #ROLE_GUN_POOLS} and {@link #RECRUIT_TIER_CONFIGS} after loading.
 * Call {@link #ensureLoaded()} before reading those maps.
 */
public final class RecruitLoadoutConfigManager {

    private static final Logger LOGGER = LogManager.getLogger("JMTEG-RecruitLoadout");
    private static final String SUBPATH  = "jmteg";
    private static final String FILENAME = "recruit_roles.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RecruitLoadoutConfigManager() {}

    // ── Public data ──────────────────────────────────────────────────────────

    /**
     * Maps each role to an ordered list of gun item ResourceLocations.
     * Populated after {@link #ensureLoaded()}.
     */
    public static final Map<RecruitGunRole, List<ResourceLocation>> ROLE_GUN_POOLS =
            new ConcurrentHashMap<>();

    /**
     * Maps recruit class identifier ("BOWMAN" / "CROSSBOWMAN") to its tier config.
     * Populated after {@link #ensureLoaded()}.
     */
    public static final Map<String, RecruitTierConfig> RECRUIT_TIER_CONFIGS =
            new ConcurrentHashMap<>();

    private static volatile boolean loaded = false;

    // ── Public API ────────────────────────────────────────────────────────────

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File cfgDir  = FMLPaths.CONFIGDIR.get().toFile();
            File modDir  = new File(cfgDir, SUBPATH);
            if (!modDir.exists() && !modDir.mkdirs()) {
                LOGGER.warn("RecruitLoadoutConfigManager: could not create config dir {}", modDir.getAbsolutePath());
            }
            File cfgFile = new File(modDir, FILENAME);
            if (!cfgFile.exists()) {
                writeDefaults(cfgFile);
            }
            readFile(cfgFile);
        } catch (Throwable t) {
            LOGGER.error("RecruitLoadoutConfigManager: failed to load config, applying built-in defaults", t);
            applyHardcodedDefaults();
        }
    }

    // ── Public helper: tier config for a recruit class ───────────────────────

    /**
     * Returns the {@link RecruitTierConfig} for the given recruit class key.
     * Falls back to the BOWMAN config if the class key is unrecognised.
     */
    public static RecruitTierConfig getTierConfig(String recruitClassKey) {
        RecruitTierConfig cfg = RECRUIT_TIER_CONFIGS.get(recruitClassKey.toUpperCase(Locale.ROOT));
        if (cfg == null) cfg = RECRUIT_TIER_CONFIGS.get("BOWMAN");
        if (cfg == null) cfg = RecruitTierConfig.BOWMAN_DEFAULT;
        return cfg;
    }

    // ── Immutable tier config ─────────────────────────────────────────────────

    /** Immutable description of a recruit class's role capabilities. */
    public static final class RecruitTierConfig {
        /** Ordered list of roles this recruit class may use, from most preferred to least. */
        public final List<RoleWeight> roles;
        /** When true: if no role-pool gun is found, fall back to any JEG gun in inventory. */
        public final boolean fallbackToAnyGun;

        public RecruitTierConfig(List<RoleWeight> roles, boolean fallbackToAnyGun) {
            this.roles = Collections.unmodifiableList(new ArrayList<>(roles));
            this.fallbackToAnyGun = fallbackToAnyGun;
        }

        /** Hard-coded Bowman default used when config fails to load. */
        static final RecruitTierConfig BOWMAN_DEFAULT = new RecruitTierConfig(
                List.of(
                        new RoleWeight(RecruitGunRole.SIDEARM,        1.0),
                        new RoleWeight(RecruitGunRole.BASIC_RANGED,   1.0),
                        new RoleWeight(RecruitGunRole.UTILITY,        0.6),
                        new RoleWeight(RecruitGunRole.TACTICAL_RANGED,0.3)
                ), true);

        /** Hard-coded CrossBowman default used when config fails to load. */
        static final RecruitTierConfig CROSSBOWMAN_DEFAULT = new RecruitTierConfig(
                List.of(
                        new RoleWeight(RecruitGunRole.TACTICAL_RANGED,1.0),
                        new RoleWeight(RecruitGunRole.HEAVY,          1.0),
                        new RoleWeight(RecruitGunRole.BASIC_RANGED,   0.8),
                        new RoleWeight(RecruitGunRole.SIDEARM,        0.5),
                        new RoleWeight(RecruitGunRole.UTILITY,        0.5)
                ), true);
    }

    /** A role paired with a relative preference weight (0.0 – 1.0). */
    public static final class RoleWeight {
        public final RecruitGunRole role;
        /** Higher weight = stronger preference when multiple roles have matching guns. */
        public final double weight;

        public RoleWeight(RecruitGunRole role, double weight) {
            this.role   = role;
            this.weight = weight;
        }
    }

    // ── Internal JSON POJOs ────────────────────────────────────────────────────

    private static class JsonRoot {
        Map<String, JsonRoleEntry>     roles          = new LinkedHashMap<>();
        Map<String, JsonTierEntry>     recruit_tiers  = new LinkedHashMap<>();
    }

    private static class JsonRoleEntry {
        List<String> guns     = new ArrayList<>();
    }

    private static class JsonTierEntry {
        List<JsonRoleWeight> roles               = new ArrayList<>();
        boolean              fallback_to_any_gun = true;
    }

    private static class JsonRoleWeight {
        String role   = "";
        double weight = 1.0;
    }

    // ── Read / write ──────────────────────────────────────────────────────────

    private static void readFile(File file) {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonRoot root = GSON.fromJson(r, JsonRoot.class);
            if (root == null) {
                LOGGER.warn("RecruitLoadoutConfigManager: {} is empty – applying built-in defaults", file.getName());
                applyHardcodedDefaults();
                return;
            }

            // Parse role pools
            if (root.roles != null) {
                for (Map.Entry<String, JsonRoleEntry> e : root.roles.entrySet()) {
                    RecruitGunRole role;
                    try { role = RecruitGunRole.valueOf(e.getKey().toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ex) {
                        LOGGER.warn("RecruitLoadoutConfigManager: unknown role '{}', skipping", e.getKey());
                        continue;
                    }
                    List<ResourceLocation> guns = new ArrayList<>();
                    for (String id : e.getValue().guns) {
                        ResourceLocation rl = ResourceLocation.tryParse(id);
                        if (rl != null) guns.add(rl);
                        else LOGGER.warn("RecruitLoadoutConfigManager: invalid gun id '{}' in role {}", id, role);
                    }
                    ROLE_GUN_POOLS.put(role, Collections.unmodifiableList(guns));
                }
            }

            // Parse recruit tier configs
            if (root.recruit_tiers != null) {
                for (Map.Entry<String, JsonTierEntry> e : root.recruit_tiers.entrySet()) {
                    String key = e.getKey().toUpperCase(Locale.ROOT);
                    JsonTierEntry jt = e.getValue();
                    List<RoleWeight> rws = new ArrayList<>();
                    for (JsonRoleWeight jrw : jt.roles) {
                        try {
                            RecruitGunRole gwRole = RecruitGunRole.valueOf(jrw.role.toUpperCase(Locale.ROOT));
                            rws.add(new RoleWeight(gwRole, jrw.weight));
                        } catch (IllegalArgumentException ex) {
                            LOGGER.warn("RecruitLoadoutConfigManager: unknown role '{}' in tier {}", jrw.role, key);
                        }
                    }
                    RECRUIT_TIER_CONFIGS.put(key, new RecruitTierConfig(rws, jt.fallback_to_any_gun));
                }
            }

            LOGGER.info("RecruitLoadoutConfigManager: loaded {} role pools and {} recruit tier configs",
                    ROLE_GUN_POOLS.size(), RECRUIT_TIER_CONFIGS.size());
        } catch (IOException ex) {
            LOGGER.error("RecruitLoadoutConfigManager: IO error reading {}", file.getAbsolutePath(), ex);
            applyHardcodedDefaults();
        }
    }

    private static void writeDefaults(File file) {
        JsonRoot root = buildDefaultRoot();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
            LOGGER.info("RecruitLoadoutConfigManager: wrote default config to {}", file.getAbsolutePath());
        } catch (IOException ex) {
            LOGGER.error("RecruitLoadoutConfigManager: could not write default config", ex);
        }
    }

    private static JsonRoot buildDefaultRoot() {
        JsonRoot root = new JsonRoot();

        // ── Role pools ────────────────────────────────────────────────────────
        root.roles.put("SIDEARM", roleEntry(
                "jeg:revolver", "jeg:semi_auto_pistol", "jeg:combat_pistol", "jeg:finger_gun"));

        root.roles.put("BASIC_RANGED", roleEntry(
                "jeg:infantry_rifle", "jeg:semi_auto_rifle", "jeg:bolt_action_rifle",
                "jeg:double_barrel_shotgun", "jeg:waterpipe_shotgun", "jeg:compound_bow",
                "jeg:primitive_bow"));

        root.roles.put("TACTICAL_RANGED", roleEntry(
                "jeg:assault_rifle", "jeg:combat_rifle", "jeg:service_rifle",
                "jeg:burst_rifle", "jeg:custom_smg",
                "jeg:pump_shotgun", "jeg:repeating_shotgun",
                "jeg:blossom_rifle", "jeg:holy_shotgun",
                "jeg:subsonic_rifle", "jeg:supersonic_shotgun",
                "jeg:hollenfire_mk2", "jeg:soulhunter_mk2"));

        root.roles.put("HEAVY", roleEntry(
                "jeg:rocket_launcher", "jeg:grenade_launcher",
                "jeg:light_machine_gun", "jeg:minigun",
                "jeg:hypersonic_cannon", "jeg:flamethrower",
                "jeg:atlantean_spear", "jeg:typhoonee"));

        root.roles.put("UTILITY", roleEntry(
                "jeg:flare_gun", "jeg:abstract_gun"));

        // ── Recruit tiers ──────────────────────────────────────────────────────
        JsonTierEntry bowman = new JsonTierEntry();
        bowman.fallback_to_any_gun = true;
        bowman.roles = List.of(
                rw("SIDEARM",         1.0),
                rw("BASIC_RANGED",    1.0),
                rw("UTILITY",         0.6),
                rw("TACTICAL_RANGED", 0.3)
        );
        root.recruit_tiers.put("BOWMAN", bowman);

        JsonTierEntry crossbowman = new JsonTierEntry();
        crossbowman.fallback_to_any_gun = true;
        crossbowman.roles = List.of(
                rw("TACTICAL_RANGED", 1.0),
                rw("HEAVY",           1.0),
                rw("BASIC_RANGED",    0.8),
                rw("SIDEARM",         0.5),
                rw("UTILITY",         0.5)
        );
        root.recruit_tiers.put("CROSSBOWMAN", crossbowman);

        return root;
    }

    private static JsonRoleEntry roleEntry(String... guns) {
        JsonRoleEntry e = new JsonRoleEntry();
        e.guns = Arrays.asList(guns);
        return e;
    }

    private static JsonRoleWeight rw(String role, double weight) {
        JsonRoleWeight jrw = new JsonRoleWeight();
        jrw.role   = role;
        jrw.weight = weight;
        return jrw;
    }

    private static void applyHardcodedDefaults() {
        ROLE_GUN_POOLS.put(RecruitGunRole.SIDEARM, List.of(
                rl("jeg:revolver"), rl("jeg:semi_auto_pistol"), rl("jeg:combat_pistol")));
        ROLE_GUN_POOLS.put(RecruitGunRole.BASIC_RANGED, List.of(
                rl("jeg:infantry_rifle"), rl("jeg:semi_auto_rifle"), rl("jeg:bolt_action_rifle")));
        ROLE_GUN_POOLS.put(RecruitGunRole.TACTICAL_RANGED, List.of(
                rl("jeg:assault_rifle"), rl("jeg:combat_rifle"), rl("jeg:pump_shotgun")));
        ROLE_GUN_POOLS.put(RecruitGunRole.HEAVY, List.of(
                rl("jeg:rocket_launcher"), rl("jeg:grenade_launcher"), rl("jeg:light_machine_gun")));
        ROLE_GUN_POOLS.put(RecruitGunRole.UTILITY, List.of(
                rl("jeg:flare_gun")));

        RECRUIT_TIER_CONFIGS.put("BOWMAN",     RecruitTierConfig.BOWMAN_DEFAULT);
        RECRUIT_TIER_CONFIGS.put("CROSSBOWMAN",RecruitTierConfig.CROSSBOWMAN_DEFAULT);
    }

    private static ResourceLocation rl(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        return loc != null ? loc : ResourceLocation.fromNamespaceAndPath("minecraft", "air");
    }
}
