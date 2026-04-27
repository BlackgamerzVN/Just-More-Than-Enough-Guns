package com.blackgamerz.jmteg.jegcompat.coreJEG;

public class Config {
    public static final Common COMMON = new Common();

    public static class Common {
        public final Network network = new Network();
        public final Gameplay gameplay = new Gameplay();

        public static class Network {
            public double getProjectileTrackingRange() { return 64.0; }
        }
        public static class Gameplay {
            public boolean getMobDynamicLightsOnShooting() { return false; }
        }
    }
}