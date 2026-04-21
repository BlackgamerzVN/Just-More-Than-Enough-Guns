package com.blackgamerz.jmteg.compat;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Call CompatInitializer.init() from your mod constructor (main class) after configs are loaded.
 * This class checks for optional mods and conditionally initializes compatibility code.
 */
public final class CompatInitializer {
    private static final Logger LOGGER = LogManager.getLogger("JMTJEG-Compat");

    private CompatInitializer() {}

    public static void init() {
        boolean jeg = ModList.get().isLoaded("jeg");
        boolean recruits = ModList.get().isLoaded("recruits");

        LOGGER.info("CompatInitializer: JEG loaded = {}, Recruits loaded = {}", jeg, recruits);

        if (jeg) {
            try {
                JegCompat.init();
                LOGGER.info("JegCompat initialized");
            } catch (Throwable t) {
                LOGGER.error("Failed to initialize JegCompat", t);
            }
        }

        if (recruits) {
            try {
                RecruitsCompat.init();
                LOGGER.info("RecruitsCompat initialized");
            } catch (Throwable t) {
                LOGGER.error("Failed to initialize RecruitsCompat", t);
            }
        }
    }
}