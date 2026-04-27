package com.blackgamerz.jmteg.jegcompat.coreJEG;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class ProjectileEntity extends Projectile {
    public ProjectileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        // No synched data for stub
    }

    public void setWeapon(Object itemstack) {}
    public void setAdditionalDamage(double dmg) {}
    public Gun.Projectile getProjectile() { return new Gun.Projectile(); }
    public void updateHeading() {}

    public static ProjectileEntity createCustom(Level level, LivingEntity shooter, Object stack, Gun gun) {
        return new ProjectileEntity(EntityType.ARROW, level);
    }
}