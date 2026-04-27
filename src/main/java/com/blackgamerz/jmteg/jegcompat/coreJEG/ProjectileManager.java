package com.blackgamerz.jmteg.jegcompat.coreJEG;

public class ProjectileManager {
    private static final ProjectileManager instance = new ProjectileManager();
    public static ProjectileManager getInstance() { return instance; }
    public IProjectileFactory getFactory(Object item) { return new IProjectileFactory(){}; }
}