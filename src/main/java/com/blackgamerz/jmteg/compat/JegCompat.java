package com.blackgamerz.jmteg.compat;

import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lightweight compatibility initializer for Just Enough Guns (mod id "jeg").
 * This class attempts minimal reflective integration points. Expand as needed.
 *
 * Important: Do NOT reference JEG types directly in the top-level class fields or signatures.
 * All reflective access occurs inside methods called only when ModList reports the mod present.
 */
public final class JegCompat {
    private static final Logger LOGGER = LogManager.getLogger("JMTJEG-JEG-Compat");

    private JegCompat() {}

    public static void init() {
        LOGGER.info("Initializing JEG compatibility...");
        // Example: register event handlers that will call into JEG reflectively when needed.
        MinecraftForge.EVENT_BUS.register(new JegCompat());
        // Optionally, verify reflective classes are present
        try {
            Class.forName("ttv.migami.jeg.Reference");
            LOGGER.info("Detected JEG Reference class");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("JEG classes not found via reflection despite ModList reporting loaded. Compatibility may fail.");
        }
    }

    // Example helper that tries to call a JEG static boolean (like recruitsLoaded) if it exists.
    public static boolean trySetRecruitsLoadedFlag(boolean value) {
        try {
            Class<?> cls = Class.forName("ttv.migami.jeg.JustEnoughGuns");
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("recruitsLoaded");
                f.setAccessible(true);
                f.setBoolean(null, value);
                LOGGER.info("Set JustEnoughGuns.recruitsLoaded = {}", value);
                return true;
            } catch (NoSuchFieldException nsf) {
                LOGGER.debug("JustEnoughGuns.recruitsLoaded field not found");
            }
        } catch (ClassNotFoundException cnf) {
            LOGGER.debug("JEG main class not present");
        } catch (Throwable t) {
            LOGGER.error("Error setting recruitsLoaded flag reflectively", t);
        }
        return false;
    }

    // Add more reflection helpers here for the exact JEG integration points you need (gun registration, network hooks, etc.)
}