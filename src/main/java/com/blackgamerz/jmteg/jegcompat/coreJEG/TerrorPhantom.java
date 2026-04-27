package com.blackgamerz.jmteg.jegcompat.coreJEG;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

public class TerrorPhantom extends Mob {
    protected TerrorPhantom(EntityType<? extends Mob> type, Level level) {
        super(type, level);
    }
    public LivingEntity getTarget() { return null; }
}