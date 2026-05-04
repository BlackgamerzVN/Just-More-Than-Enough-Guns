package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.EnumSet;

/**
 * Movement/aim-only fallback for recruits holding JEG guns.
 * Shooting and reload are handled by the injected JEG GunAttackGoal (MobAiInjector).
 *
 * Enhancements included:
 * - Precise yaw/pitch application (instead of mob.lookAt)
 * - Leading moving targets (intercept time solver)
 * - Gravity compensation via projectile motion formula (stable ballistic formula)
 * - Dynamic AIM / COOLDOWN durations so recruits fire faster & more accurately when closer
 * - Strafing + retreat while aiming: the recruit will strafe left/right and retreat when target is too close
 * - Temporary ADS-like spread reduction: while the recruit is in AIM state we temporarily lower the held
 *   gun's spread value in NBT so JEG treats the weapon as if the shooter were aiming (player ADS).
 * - Role-weight stat modifiers: aim time, cooldown, ADS spread benefit, and attack range are all
 *   scaled by how appropriate the held gun is for this recruit's tier.  A Bowman forced to use a
 *   rocket launcher (weight≈0) will aim slower, fire less accurately, cool down slower, and engage
 *   at shorter range than a CrossBowman holding the same weapon (weight=1.0).
 *
 * This class is defensive: if JEG isn't present or reflection fails it falls back to safe defaults.
 */
public class RecruitRangedGunnerAttackGoal extends Goal {
    private final PathfinderMob mob;

    private enum State { IDLE, SEEK, AIM, COOLDOWN }
    private State state = State.IDLE;

    private static final double ATTACK_RANGE = 16.0;

    // Safety / avoidance tuning
    private static final double SAFE_DISTANCE = 4.0; // recruit will try to stay at least this far (blocks)
    private static final double SAFE_EXIT_BUFFER = 2.0; // add this to SAFE_DISTANCE to exit avoiding

    // Strafing tuning
    private static final double STRAFE_DISTANCE = 1.5; // lateral offset in blocks when strafing
    private static final int STRAFE_CHANGE_TICKS = 40; // how often to choose a new strafe direction
    private static final double STRAFE_SPEED = 1.0D; // navigation speed while strafing
    private static final double RETREAT_SPEED = 1.25D; // speed used when retreating
    private static final double RETREAT_EXTRA = 1.5; // extra distance when retreating beyond SAFE_DISTANCE

    // Tuning: adjust these for server/mod config
    private static final int MIN_AIM_TICKS = 5;
    private static final int MAX_AIM_TICKS = 40; // far targets get longer aim time
    private static final int MIN_COOLDOWN_TICKS = 8;
    private static final int MAX_COOLDOWN_TICKS = 40;

    // Reflection / physics fallbacks if JEG not available or we can't read values
    private static final float DEFAULT_PROJECTILE_SPEED = 3.0f; // blocks per tick (fallback)
    private static final float DEFAULT_PROJECTILE_GRAVITY = 0.04f; // positive magnitude (fallback)

    // Downward bias (degrees) to reduce overshooting; increase to aim lower
    private static final float AIM_DOWN_BIAS_DEGREES = 200.0f;

    // ADS-like spread multiplier: while AIMing the gun's stored spread will be multiplied by this.
    // 1.0 = no change, 0.5 = half spread (more accurate). Tweak to your taste.
    // This is the best-case (weight=1.0) multiplier; inappropriate guns receive less benefit.
    private static final float ADS_SPREAD_MULTIPLIER = 0.025f;

    // Role-weight stat modifiers ──────────────────────────────────────────────
    // All three affect behaviour when the held gun is "inappropriate" for this recruit's tier
    // (i.e. its role-weight < 1.0).  At weight=1.0 these have no effect.

    /** Aim time at weight=0.0 will be this many times longer than at weight=1.0. */
    private static final double MAX_AIM_PENALTY_FACTOR      = 2.5;
    /** Cooldown at weight=0.0 will be this many times longer than at weight=1.0. */
    private static final double MAX_COOLDOWN_PENALTY_FACTOR = 2.0;
    /** Effective attack range at weight=0.0 is this fraction of ATTACK_RANGE. */
    private static final double MIN_RANGE_FACTOR            = 0.625;

    // NBT keys used to stash original spread and mark applied state
    private static final String JMTEG_ADS_FLAG = "jmteg_ads";
    private static final String JMTEG_ORIG_SPREAD = "jmteg_original_spread";

    private int aimTimer = 0;
    private int cooldownTimer = 0;

    // strafing state
    private int strafeTimer = 0;
    private int strafeDirection = 1; // +1 = right, -1 = left

    public RecruitRangedGunnerAttackGoal(PathfinderMob mob) {
        this.mob = mob;
        this.strafeTimer = STRAFE_CHANGE_TICKS / 2;
        this.strafeDirection = mob.getRandom().nextBoolean() ? 1 : -1;
    }

    @Override
    public EnumSet<Flag> getFlags() {
        return EnumSet.of(Flag.TARGET, Flag.MOVE, Flag.LOOK);
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public void start() {
        this.state = State.IDLE;
        aimTimer = 0;
        cooldownTimer = 0;
        strafeTimer = STRAFE_CHANGE_TICKS / 2;
        strafeDirection = mob.getRandom().nextBoolean() ? 1 : -1;
    }

    @Override
    public void stop() {
        // ensure we remove any temporary ADS modifier when goal stops
        disableAdsOnHeldGun();
        mob.getNavigation().stop();
        this.state = State.IDLE;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            // leaving aim — clean up any temporary ADS modifiers
            disableAdsOnHeldGun();
            state = State.IDLE;
            return;
        }

        double distSq = mob.distanceToSqr(target);
        double dist = Math.sqrt(distSq);

        double effectiveRange   = getEffectiveAttackRange();
        double effectiveRangeSq = effectiveRange * effectiveRange;

        switch (state) {
            case IDLE -> {
                if (distSq > effectiveRangeSq) {
                    state = State.SEEK;
                } else {
                    state = State.AIM;
                    aimTimer = computeAimTicks(dist);
                    enableAdsOnHeldGun(); // start ADS-like spread reduction
                }
            }
            case SEEK -> {
                if (distSq > effectiveRangeSq) {
                    mob.getNavigation().moveTo(target, 1.1D);
                } else {
                    mob.getNavigation().stop();
                    state = State.AIM;
                    aimTimer = computeAimTicks(dist);
                    enableAdsOnHeldGun(); // start ADS-like spread reduction
                }
            }
            case AIM -> {
                // If target too close, retreat a bit while still aiming
                double safeSq = SAFE_DISTANCE * SAFE_DISTANCE;
                double exitSq = (SAFE_DISTANCE + SAFE_EXIT_BUFFER) * (SAFE_DISTANCE + SAFE_EXIT_BUFFER);

                if (distSq < safeSq) {
                    // retreat directly away from target to reach SAFE_DISTANCE + RETREAT_EXTRA
                    double dx = mob.getX() - target.getX();
                    double dz = mob.getZ() - target.getZ();
                    double horiz = Math.sqrt(dx * dx + dz * dz);
                    if (horiz < 1e-6) {
                        // choose random direction if overlapping
                        double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
                        dx = Math.cos(angle);
                        dz = Math.sin(angle);
                        horiz = 1.0;
                    }
                    double desiredDistance = SAFE_DISTANCE + RETREAT_EXTRA;
                    double nx = target.getX() + (dx / horiz) * desiredDistance;
                    double nz = target.getZ() + (dz / horiz) * desiredDistance;
                    double ny = mob.getY();

                    mob.getNavigation().moveTo(nx, ny, nz, RETREAT_SPEED);
                } else {
                    // Strafing behavior: pick lateral offsets around current position to circle/strafe target while aiming
                    // update timer and possibly flip direction
                    strafeTimer--;
                    if (strafeTimer <= 0) {
                        strafeDirection = mob.getRandom().nextBoolean() ? 1 : -1;
                        strafeTimer = STRAFE_CHANGE_TICKS + mob.getRandom().nextInt(20);
                    }

                    // Compute perpendicular (left/right) to vector from mob to target
                    double dx = target.getX() - mob.getX();
                    double dz = target.getZ() - mob.getZ();
                    double horiz = Math.sqrt(dx * dx + dz * dz);
                    if (horiz < 1e-6) horiz = 1e-6;
                    // normalized perpendicular: (-dz, dx)
                    double px = -dz / horiz;
                    double pz = dx / horiz;

                    // target strafe point around current mob position
                    double strafeX = mob.getX() + px * STRAFE_DISTANCE * strafeDirection;
                    double strafeZ = mob.getZ() + pz * STRAFE_DISTANCE * strafeDirection;
                    double strafeY = mob.getY();

                    // Use navigation so mob can path around obstacles
                    mob.getNavigation().moveTo(strafeX, strafeY, strafeZ, STRAFE_SPEED);
                }

                // Closer targets can snap faster; farther targets get slower, steadier aim.
                float maxYawPerTick = (float) clamp(15.0 + (1.0 - (dist / ATTACK_RANGE)) * 60.0, 10.0, 120.0);
                float maxPitchPerTick = (float) clamp(10.0 + (1.0 - (dist / ATTACK_RANGE)) * 40.0, 8.0, 90.0);

                // Extract projectile properties (try JEG via reflection with multiple fallbacks)
                float projectileSpeed = getHeldProjectileSpeed(mob);
                float projectileGravity = getHeldProjectileGravity(mob);

                // Aim accounting for target motion and gravity
                applyAdvancedAim(mob, target, projectileSpeed, projectileGravity, maxYawPerTick, maxPitchPerTick);

                aimTimer--;
                if (aimTimer <= 0) {
                    // Exiting AIM — restore spread before letting the GunAttackGoal run
                    disableAdsOnHeldGun();
                    // The injected JEG GunAttackGoal will perform the actual firing.
                    cooldownTimer = computeCooldownTicks(dist);
                    state = State.COOLDOWN;
                }
            }
            case COOLDOWN -> {
                cooldownTimer--;
                if (cooldownTimer <= 0) {
                    state = State.AIM;
                    aimTimer = computeAimTicks(dist);
                    enableAdsOnHeldGun(); // re-enable ADS-like spread reduction for the next aim cycle
                }
            }
        }
    }

    // Compute aim ticks: closer => fewer ticks (faster firing), far => longer aim for accuracy.
    // Additionally, the result is scaled up when the held gun is inappropriate for this recruit's tier.
    private int computeAimTicks(double distance) {
        double t = clamp(distance / ATTACK_RANGE, 0.0, 1.0);
        int base = (int) Math.max(MIN_AIM_TICKS, Math.round(MIN_AIM_TICKS + (MAX_AIM_TICKS - MIN_AIM_TICKS) * t));
        double weight  = getHeldGunWeight();
        double penalty = 1.0 + (MAX_AIM_PENALTY_FACTOR - 1.0) * (1.0 - weight);
        return (int) Math.round(base * penalty);
    }

    private int computeCooldownTicks(double distance) {
        double t = clamp(distance / ATTACK_RANGE, 0.0, 1.0);
        int base = (int) Math.max(MIN_COOLDOWN_TICKS, Math.round(MIN_COOLDOWN_TICKS + (MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS) * t));
        double weight  = getHeldGunWeight();
        double penalty = 1.0 + (MAX_COOLDOWN_PENALTY_FACTOR - 1.0) * (1.0 - weight);
        return (int) Math.round(base * penalty);
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }

    /**
     * Temporarily reduce the held gun's stored spread in NBT so JEG will treat it as "aiming".
     * This writes the original spread into jmteg_original_spread and sets jmteg_ads=true.
     *
     * This approach avoids modifying JEG classes and works by altering the Gun compound that
     * JEG's GunItem.getModifiedGun(...) deserializes.
     */
    private void enableAdsOnHeldGun() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack == null || stack.isEmpty()) return;
            CompoundTag tag = stack.getTag();
            if (tag == null) tag = stack.getOrCreateTag();
            if (tag.contains(JMTEG_ADS_FLAG) && tag.getBoolean(JMTEG_ADS_FLAG)) {
                // already applied
                return;
            }

            // Work with the "Gun" compound (JEG stores its gun data here when customized)
            CompoundTag gunTag = tag.contains("Gun", 10) ? tag.getCompound("Gun") : new CompoundTag();
            CompoundTag generalTag = gunTag.contains("General", 10) ? gunTag.getCompound("General") : new CompoundTag();

            // If "Spread" exists, stash it, otherwise mark as missing (NaN)
            if (generalTag.contains("Spread")) {
                float orig = generalTag.getFloat("Spread");
                tag.putFloat(JMTEG_ORIG_SPREAD, orig);
                generalTag.putFloat("Spread", orig * computeAdsSpreadMultiplier());
            } else {
                // no explicit Spread stored in NBT; we still mark that we applied ADS but stash a sentinel
                tag.putFloat(JMTEG_ORIG_SPREAD, Float.NaN);
                // proactively write a reduced spread so JEG will pick it up when it deserializes
                generalTag.putFloat("Spread", computeAdsSpreadMultiplier() * 1.0F); // 1.0F is a safe default baseline
            }

            gunTag.put("General", generalTag);
            tag.put("Gun", gunTag);
            tag.putBoolean(JMTEG_ADS_FLAG, true);
            stack.setTag(tag);
        } catch (Throwable ignored) {
            // Be silent — we don't want to crash if the structure isn't what we expected
        }
    }

    /**
     * Restore original spread (if any) and remove our temporary NBT markers.
     */
    private void disableAdsOnHeldGun() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack == null || stack.isEmpty()) return;
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(JMTEG_ADS_FLAG) || !tag.getBoolean(JMTEG_ADS_FLAG)) {
                return;
            }

            float orig = tag.contains(JMTEG_ORIG_SPREAD) ? tag.getFloat(JMTEG_ORIG_SPREAD) : Float.NaN;

            CompoundTag gunTag = tag.contains("Gun", 10) ? tag.getCompound("Gun") : null;
            if (gunTag != null) {
                CompoundTag generalTag = gunTag.contains("General", 10) ? gunTag.getCompound("General") : null;
                if (generalTag != null) {
                    if (!Float.isNaN(orig)) {
                        generalTag.putFloat("Spread", orig);
                    } else {
                        // no original stored — remove the explicit Spread so JEG falls back to its defaults
                        generalTag.remove("Spread");
                    }
                    gunTag.put("General", generalTag);
                    tag.put("Gun", gunTag);
                }
            }

            tag.remove(JMTEG_ORIG_SPREAD);
            tag.remove(JMTEG_ADS_FLAG);
            stack.setTag(tag);
        } catch (Throwable ignored) {
            // ignore failures — best-effort cleanup only
        }
    }

    /**
     * Aim helper that:
     * - predicts a lead point using an intercept solver (ignores gravity for time estimate)
     * - computes elevation needed to hit that point given projectile speed and gravity
     * - applies smooth rotation (yaw & pitch interpolation limited by maxDelta)
     *
     * Uses a stable ballistic formula and picks the lower-angle trajectory.
     */
    private static void applyAdvancedAim(PathfinderMob shooter, LivingEntity target, float projectileSpeed, float gravity, float maxYawChange, float maxPitchChange) {
        Vec3 shooterEye = new Vec3(shooter.getX(), shooter.getEyeY(), shooter.getZ());
        Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        Vec3 targetVel = target.getDeltaMovement(); // blocks per tick

        // Solve intercept time ignoring gravity to get rough time-of-flight
        double t = solveInterceptTime(shooterEye, targetEye, targetVel, projectileSpeed);

        Vec3 aimPoint;
        if (t > 0) {
            aimPoint = targetEye.add(targetVel.scale(t));
        } else {
            aimPoint = targetEye;
        }

        double dx = aimPoint.x - shooterEye.x;
        double dz = aimPoint.z - shooterEye.z;
        double dy = aimPoint.y - shooterEye.y;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1e-6) horiz = 1e-6;

        // Compute yaw (horizontal)
        double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;

        // Compute pitch using standard ballistic formula, picking the lower arc (more direct shot)
        double x = horiz;
        double v = Math.max(1e-4, projectileSpeed);
        double g = Math.abs(gravity); // use positive magnitude for formulas

        double pitchDeg;
        if (g < 1e-9) {
            // no gravity -> aim directly
            pitchDeg = -Math.toDegrees(Math.atan2(dy, x));
        } else {
            double v2 = v * v;
            double inside = v2 * v2 - g * (g * x * x + 2.0 * dy * v2);
            if (inside >= 0.0) {
                double root = Math.sqrt(inside);
                // two candidate angles:
                double theta1 = Math.atan2(v2 - root, g * x);
                double theta2 = Math.atan2(v2 + root, g * x);
                // choose the smaller (lower) angle in absolute value -> lower trajectory
                double theta = Math.min(theta1, theta2);
                pitchDeg = -Math.toDegrees(theta); // negative = look up in Minecraft
            } else {
                // no ballistic solution (projectile too slow), fall back to direct aim
                pitchDeg = -Math.toDegrees(Math.atan2(dy, x));
            }
        }

        // Apply a small downward bias to counter systematic overshoot and clamp
        pitchDeg += AIM_DOWN_BIAS_DEGREES;
        if (pitchDeg > 90.0) pitchDeg = 90.0;
        if (pitchDeg < -90.0) pitchDeg = -90.0;

        float newYaw = rotLerp(shooter.getYRot(), (float) targetYaw, maxYawChange);
        float newPitch = rotLerp(shooter.getXRot(), (float) pitchDeg, maxPitchChange);

        shooter.setYRot(newYaw);
        shooter.setXRot(newPitch);
        // align body/head to avoid mismatch between head yaw and body yaw
        shooter.yBodyRot = newYaw;
        shooter.yHeadRot = newYaw;
    }

    /**
     * Solve interception time (ignoring gravity) for projectile speed s:
     * (v·v - s^2) t^2 + 2 r·v t + r·r = 0
     * returns smallest positive t or -1 if no solution.
     */
    private static double solveInterceptTime(Vec3 shooter, Vec3 target, Vec3 targetVel, double s) {
        Vec3 rVec = target.subtract(shooter);
        double rx = rVec.x, ry = rVec.y, rz = rVec.z;
        double vx = targetVel.x, vy = targetVel.y, vz = targetVel.z;

        double a = vx * vx + vy * vy + vz * vz - s * s;
        double b = 2.0 * (rx * vx + ry * vy + rz * vz);
        double c = rx * rx + ry * ry + rz * rz;

        if (Math.abs(a) < 1e-6) {
            if (Math.abs(b) < 1e-6) {
                return c <= 0.0 ? 0.0 : -1.0;
            }
            double t = -c / b;
            return t > 0 ? t : -1.0;
        }

        double disc = b * b - 4.0 * a * c;
        if (disc < 0.0) return -1.0;
        double sqrtD = Math.sqrt(disc);
        double t1 = (-b - sqrtD) / (2.0 * a);
        double t2 = (-b + sqrtD) / (2.0 * a);

        double t = Double.POSITIVE_INFINITY;
        if (t1 > 0 && t1 < t) t = t1;
        if (t2 > 0 && t2 < t) t = t2;
        return t == Double.POSITIVE_INFINITY ? -1.0 : t;
    }

    // Interpolate angle 'from' towards 'to' with max delta (degrees), handles wrap-around
    private static float rotLerp(float from, float to, float maxDelta) {
        float delta = wrapDegrees(to - from);
        if (delta > maxDelta) delta = maxDelta;
        if (delta < -maxDelta) delta = -maxDelta;
        return from + delta;
    }

    // Normalize to [-180,180)
    private static float wrapDegrees(float angle) {
        angle = (angle % 360.0f);
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    // ── Role-weight stat-modifier helpers ─────────────────────────────────────

    /**
     * Returns the role-preference weight (0.0 – 1.0) of the mob's currently held gun
     * within this recruit's tier config.
     * <ul>
     *   <li>1.0 – perfectly appropriate gun (full performance)</li>
     *   <li>0.0 – gun not in any accessible role pool (maximum penalty)</li>
     * </ul>
     * Returns 1.0 (no penalty) on any error or when no gun is held.
     */
    private double getHeldGunWeight() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack == null || stack.isEmpty()) return 1.0;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) return 1.0;
            RecruitLoadoutConfigManager.ensureLoaded();
            String classKey = RecruitGunSelector.detectRecruitClassKey(mob);
            RecruitLoadoutConfigManager.RecruitTierConfig tier =
                    RecruitLoadoutConfigManager.getTierConfig(classKey);
            return RecruitGunSelector.getRoleWeight(id, tier);
        } catch (Throwable t) {
            return 1.0; // safe: no penalty when information is unavailable
        }
    }

    /**
     * Returns the effective attack range (blocks) for the currently held gun.
     * At weight=1.0 this equals {@link #ATTACK_RANGE}; at weight=0.0 it is
     * {@link #ATTACK_RANGE} × {@link #MIN_RANGE_FACTOR}.
     */
    private double getEffectiveAttackRange() {
        double weight = getHeldGunWeight();
        return ATTACK_RANGE * (MIN_RANGE_FACTOR + weight * (1.0 - MIN_RANGE_FACTOR));
    }

    /**
     * Returns the ADS spread multiplier to apply while aiming.
     * At weight=1.0 this is {@link #ADS_SPREAD_MULTIPLIER} (maximum accuracy benefit).
     * At weight=0.0 this is 1.0 (no accuracy benefit — the recruit cannot benefit from aiming
     * with a weapon they are not trained to use).
     */
    private float computeAdsSpreadMultiplier() {
        double weight = getHeldGunWeight();
        return (float) (ADS_SPREAD_MULTIPLIER + (1.0 - ADS_SPREAD_MULTIPLIER) * (1.0 - weight));
    }

    /**
     * Robust extraction of projectile base speed from held JEG gun using reflection.
     * Returns DEFAULT_PROJECTILE_SPEED on any failure.
     */
    private static float getHeldProjectileSpeed(PathfinderMob mob) {
        try {
            ItemStack main = mob.getMainHandItem();
            if (main == null || main.isEmpty()) return DEFAULT_PROJECTILE_SPEED;
            Item item = main.getItem();

            Class<?> jegGunItemClass = Class.forName("ttv.migami.jeg.item.GunItem");
            if (!jegGunItemClass.isInstance(item)) return DEFAULT_PROJECTILE_SPEED;

            Method getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class);
            Object gunObj = getModifiedGun.invoke(item, main);
            if (gunObj == null) return DEFAULT_PROJECTILE_SPEED;

            // Try gun.getProjectile().getSpeed()
            try {
                Method getProjectile = gunObj.getClass().getMethod("getProjectile");
                Object projObj = getProjectile.invoke(gunObj);
                if (projObj != null) {
                    try {
                        Method getSpeed = projObj.getClass().getMethod("getSpeed");
                        Object val = getSpeed.invoke(projObj);
                        if (val instanceof Number) return ((Number) val).floatValue();
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }

            // Fallback: gun.getProjectileSpeed()
            try {
                Method mg = gunObj.getClass().getMethod("getProjectileSpeed");
                Object val = mg.invoke(gunObj);
                if (val instanceof Number) return ((Number) val).floatValue();
            } catch (Throwable ignored) {
            }

        } catch (Throwable ignored) {
        }
        return DEFAULT_PROJECTILE_SPEED;
    }

    /**
     * Robust extraction of projectile gravity from held JEG gun using reflection.
     * Returns a positive gravity magnitude appropriate for use in projectile formulas.
     * If projectile has gravity disabled returns 0.0f.
     * On failures returns DEFAULT_PROJECTILE_GRAVITY.
     */
    private static float getHeldProjectileGravity(PathfinderMob mob) {
        try {
            ItemStack main = mob.getMainHandItem();
            if (main == null || main.isEmpty()) return DEFAULT_PROJECTILE_GRAVITY;
            Item item = main.getItem();

            Class<?> jegGunItemClass = Class.forName("ttv.migami.jeg.item.GunItem");
            if (!jegGunItemClass.isInstance(item)) return DEFAULT_PROJECTILE_GRAVITY;

            Method getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class);
            Object gunObj = getModifiedGun.invoke(item, main);
            if (gunObj == null) return DEFAULT_PROJECTILE_GRAVITY;

            // Try projectile.getGravity()
            try {
                Method getProjectile = gunObj.getClass().getMethod("getProjectile");
                Object projObj = getProjectile.invoke(gunObj);
                if (projObj != null) {
                    try {
                        Method mg = projObj.getClass().getMethod("getGravity");
                        Object val = mg.invoke(projObj);
                        if (val instanceof Number) {
                            double g = ((Number) val).doubleValue();
                            return (float) Math.abs(g);
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }

            // Fallback: gunObj.getProjectileGravity() or similar method/field
            try {
                Method mg = gunObj.getClass().getMethod("getProjectileGravity");
                Object val = mg.invoke(gunObj);
                if (val instanceof Number) {
                    double g = ((Number) val).doubleValue();
                    return (float) Math.abs(g);
                }
            } catch (Throwable ignored) {
            }

        } catch (Throwable ignored) {
        }
        return DEFAULT_PROJECTILE_GRAVITY;
    }
}