package com.blackgamerz.jmteg.jegcompat.core;

import com.blackgamerz.jmteg.util.JsonConfigIO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.reflect.Type;

/**
 * Loads/writes config/just_more_than_enough_guns/scanner.json, which controls how
 * often (and how far) {@link MobAiInjector}'s periodic scanner looks for mobs that
 * later acquired a JEG gun. Extracted from {@code MobAiInjector.ScannerConfigManager}
 * so config I/O has a single, focused home.
 */
public final class MobAiScannerConfig {

    private static final Logger LOGGER = LogManager.getLogger("jmteg");

    private static final String SUBPATH = "just_more_than_enough_guns";
    private static final String FILE_NAME = "scanner.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private volatile boolean loaded = false;
    private Values config;

    /** Plain settings holder, deserialized directly from/to scanner.json. */
    public static final class Values {
        public int intervalTicks = 100;
        public int radius = 64;
    }

    public synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        File modDir = JsonConfigIO.resolveConfigDir(SUBPATH, LOGGER);
        File cfgFile = new File(modDir, FILE_NAME);
        if (!cfgFile.exists()) {
            this.config = new Values();
            JsonConfigIO.writeJson(GSON, cfgFile, this.config, LOGGER);
            LOGGER.info("MobAiScannerConfig: wrote default scanner config to {}", cfgFile.getAbsolutePath());
            return;
        }

        Type type = new TypeToken<Values>() {}.getType();
        Values read = JsonConfigIO.readJson(GSON, cfgFile, type, LOGGER);
        this.config = read == null ? new Values() : read;
        LOGGER.info("MobAiScannerConfig: loaded scanner config (intervalTicks={}, radius={})", this.config.intervalTicks, this.config.radius);
    }

    public Values get() {
        if (!loaded) ensureLoaded();
        return config != null ? config : new Values();
    }
}
