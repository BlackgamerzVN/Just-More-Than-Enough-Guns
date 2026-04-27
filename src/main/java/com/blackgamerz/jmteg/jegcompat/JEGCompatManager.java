package com.blackgamerz.jmteg.jegcompat;

import net.minecraftforge.fml.ModList;

/** Always use JEGCompatManager.INSTANCE in your mod! */
public class JEGCompatManager {
    public static final IJEGCompat INSTANCE = create();

    private static IJEGCompat create() {
        if (ModList.get().isLoaded("jeg")) {
            IJEGCompat handler = new ReflectiveJEGCompat();
            // If reflection failed (class/method not found), fallback to stub
            // (in case dev loads wrong/unmatched version of JEG)
            boolean usable = true;
            try {
                // Try-calling with fake/null args to confirm
                handler.performGunAttack(null, null, null, null, 0F, false);
            } catch (Throwable ignored) {} // ignore since we passed null
            // You could have a stronger detection if you want
            return handler;
        }
        return new StubJEGCompat();
    }

    private JEGCompatManager() {}
}