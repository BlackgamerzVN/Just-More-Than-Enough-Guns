package com.blackgamerz.jmteg.compat;

import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lightweight compatibility initializer for Recruits (mod id "recruits").
 * Use reflection to call Recruits APIs or register events only when Recruits is present.
 */
public final class RecruitsCompat {
    private static final Logger LOGGER = LogManager.getLogger("JMTJEG-Recruits-Compat");

    private RecruitsCompat() {}

    public static void init() {
        LOGGER.info("Initializing Recruits compatibility...");
        MinecraftForge.EVENT_BUS.register(new RecruitsCompat());
        // Example verification: check for a known Recruits class
        try {
            Class.forName("com.talhanation.recruits.Recruits"); // example — adjust if main class name differs
            LOGGER.info("Detected Recruits classes via reflection.");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Could not find expected Recruits classes reflectively.");
        }
    }

    // Example reflective helper: register a gun resource or mapping to Recruits if Recruits exposes a registrar.
    public static boolean registerGunMapping(String gunResource, String recruitsEntryKey) {
        try {
            // Example: look for a hypothetical Recruits registrar class and call a register method reflectively.
            Class<?> registrar = Class.forName("com.talhanation.recruits.api.RecruitsAPI");
            java.lang.reflect.Method m = registrar.getMethod("registerExternalGun", String.class, String.class);
            m.invoke(null, gunResource, recruitsEntryKey);
            LOGGER.info("Registered external gun {} -> {}", gunResource, recruitsEntryKey);
            return true;
        } catch (ClassNotFoundException cnf) {
            LOGGER.debug("No Recruits API class found");
        } catch (NoSuchMethodException nsme) {
            LOGGER.debug("Recruits API method not present");
        } catch (Throwable t) {
            LOGGER.error("Failed to register gun mapping reflectively", t);
        }
        return false;
    }
}
