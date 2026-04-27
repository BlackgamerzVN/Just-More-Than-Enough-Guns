package com.blackgamerz.jmteg.jegcompat.coreJEG;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

public interface IProjectileFactory {
    default ProjectileEntity create(Level level, LivingEntity shooter, Object stack, GunItem item, Gun gun) {
        return new ProjectileEntity(net.minecraft.world.entity.EntityType.ARROW, level);
    }
}