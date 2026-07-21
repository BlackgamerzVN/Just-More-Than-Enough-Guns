package com.blackgamerz.jmteg.jegcompat.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

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

        File cfgDir = FMLPaths.CONFIGDIR.get().toFile();
        File modDir = new File(cfgDir, SUBPATH);
        if (!modDir.exists() && !modDir.mkdirs()) {
            LOGGER.warn("MobAiScannerConfig: failed to create config dir {}", modDir.getAbsolutePath());
        }
        File cfgFile = new File(modDir, FILE_NAME);
        if (!cfgFile.exists()) {
            this.config = new Values();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(cfgFile), StandardCharsets.UTF_8)) {
                GSON.toJson(this.config, w);
                LOGGER.info("MobAiScannerConfig: wrote default scanner config to {}", cfgFile.getAbsolutePath());
            } catch (IOException ex) {
                LOGGER.error("MobAiScannerConfig: failed to write default config", ex);
            }
            return;
        }

        try (Reader r = new InputStreamReader(new FileInputStream(cfgFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Values>() {}.getType();
            Values read = GSON.fromJson(r, type);
            this.config = read == null ? new Values() : read;
            LOGGER.info("MobAiScannerConfig: loaded scanner config (intervalTicks={}, radius={})", this.config.intervalTicks, this.config.radius);
        } catch (IOException ex) {
            LOGGER.error("MobAiScannerConfig: failed to read config, using defaults", ex);
            this.config = new Values();
        }
    }

    public Values get() {
        if (!loaded) ensureLoaded();
        return config != null ? config : new Values();
    }
}
