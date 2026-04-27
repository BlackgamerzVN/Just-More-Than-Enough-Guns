package com.blackgamerz.jmteg.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal config: allow setting an ammo resource whitelist, and whether to use it.
 */
public final class JmtegConfig {
    private static volatile boolean useWhitelist = false;
    private static final Set<String> ammoWhitelist = Collections.synchronizedSet(new HashSet<>());

    private JmtegConfig() {}

    public static boolean useWhitelist() { return useWhitelist; }
    public static void setUseWhitelist(boolean v) { useWhitelist = v; }

    public static Set<String> getAmmoWhitelist() { return ammoWhitelist; }
    public static void setAmmoWhitelist(Set<String> set) { ammoWhitelist.clear(); if (set != null) ammoWhitelist.addAll(set); }
}