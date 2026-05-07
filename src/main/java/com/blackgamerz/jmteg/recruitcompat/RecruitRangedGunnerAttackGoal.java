package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.jegcompat.JEGCompatManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumSet;
import java.util.List;

/**
 * Movement/aim/fire/reload goal for recruits holding JEG guns.
 * Shooting and reload are now self-contained: JEG's GunAttackGoal is NOT relied upon
 * for these recruits (see MobAiInjectorReflection which skips injection for recruits).
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
 * - Role-specific pathfinding: each {@link RecruitGunRole} has its own {@link RecruitRoleProfile}
 *   that drives preferred engagement range, safe distance, retreat speed, and whether the recruit
 *   strafes while aiming.  The profile is refreshed every 20 ticks so it adapts automatically
 *   when the recruit's held weapon changes.
 * - Role-aware target selection: every 20 ticks (same cadence as the profile refresh) the recruit
 *   re-scores nearby enemies and picks the tactically best one for its current role — SIDEARM
 *   closes on the nearest threat, TACTICAL_RANGED hunts exposed / low-health targets, HEAVY
 *   walks into the largest enemy cluster, and UTILITY recruits prioritise enemies that are
 *   actively attacking nearby allies.
 * - Self-contained firing: on AIM→COOLDOWN the goal calls JEGCompatManager.INSTANCE to spawn
 *   projectiles, consume ammo, eject the casing, and play the fire sound.
 * - Burst-fire support: when the aim timer expires the recruit fires a random burst of 1–
 *   {@value #MAX_BURST_COUNT} shots, spacing them by the gun's own fire rate between each shot.
 *   Only after the full burst is exhausted does the goal enter COOLDOWN for the inter-burst pause.
 * - RELOADING state: when AmmoCount reaches 0 after a shot the goal enters RELOADING and waits
 *   until GunSyncGoal / RecruitAmmoResupplyGoal replenishes the magazine, emitting the JEG
 *   bubble-ammo reload indicator particle in the meantime.
 *
 * This class is defensive: if JEG isn't present or reflection fails it falls back to safe defaults.
 */
public class RecruitRangedGunnerAttackGoal extends Goal {
    private static final Logger LOGGER = LogManager.getLogger("JMTEG-AttackGoal");

    private final PathfinderMob mob;

    private enum State { IDLE, SEEK, AIM, COOLDOWN, RELOADING }
    private State state = State.IDLE;

    // ── Role-profile cache ────────────────────────────────────────────────────
    // Refreshed every ROLE_CACHE_INTERVAL ticks (or immediately when the role changes).

    /** How often (ticks) to re-detect the held gun's role and rebuild the movement profile. */
    private static final int ROLE_CACHE_INTERVAL = 20;

    /** Last detected gun role (null = unknown / not in any pool). */
    private RecruitGunRole cachedRole = null;
    /** Movement/positioning profile derived from {@link #cachedRole} and {@link #currentDoctrine}. */
    private RecruitRoleProfile currentProfile = RecruitRoleProfile.forRole(null);
    /** Counts down from ROLE_CACHE_INTERVAL; profile is refreshed when it reaches 0. */
    private int roleCacheTick = 0;

    // ── Doctrine cache ────────────────────────────────────────────────────────
    // Refreshed on the same cadence as the role profile so doctrine changes are
    // picked up within ROLE_CACHE_INTERVAL ticks without per-tick NBT reads.

    /**
     * Active doctrine for this recruit, or {@code null} if none is set.
     * Refreshed via {@link RecruitDoctrineHolder#getDoctrine(PathfinderMob)} every
     * {@link #ROLE_CACHE_INTERVAL} ticks.
     */
    private RecruitDoctrine currentDoctrine = null;

    // ── Tuning: aim / cooldown timing ─────────────────────────────────────────
    private static final int MIN_AIM_TICKS = 5;
    private static final int MAX_AIM_TICKS = 40; // far targets get longer aim time
    private static final int MIN_COOLDOWN_TICKS = 8;
    private static final int MAX_COOLDOWN_TICKS = 40;

    // Reflection / physics fallbacks if JEG not available or we can't read values
    // (ballistic defaults are now provided by ReflectiveJEGCompat / StubJEGCompat via JEGCompatManager)

    // Downward bias (degrees) to reduce overshooting; increase to aim lower
    private static final float AIM_DOWN_BIAS_DEGREES = 200.0f;

    // ADS-like spread multiplier: while AIMing the gun's stored spread will be multiplied by this.
    // 1.0 = no change, 0.5 = half spread (more accurate). Tweak to your taste.
    // This is the best-case (weight=1.0) multiplier; inappropriate guns receive less benefit.
    private static final float ADS_SPREAD_MULTIPLIER = 0.025f;

    // Burst-fire tuning ───────────────────────────────────────────────────────
    /** Maximum shots that can be fired in one burst cycle (JEG uses 3 as its default). */
    private static final int MAX_BURST_COUNT = 3;

    // Role-weight stat modifiers ──────────────────────────────────────────────
    // All four affect behaviour when the held gun is "inappropriate" for this recruit's tier
    // (i.e. its role-weight < 1.0).  At weight=1.0 these have no effect.

    /** Aim time at weight=0.0 will be this many times longer than at weight=1.0. */
    private static final double MAX_AIM_PENALTY_FACTOR      = 2.5;
    /** Cooldown at weight=0.0 will be this many times longer than at weight=1.0. */
    private static final double MAX_COOLDOWN_PENALTY_FACTOR = 2.0;
    /** Effective attack range at weight=0.0 is this fraction of ATTACK_RANGE. */
    private static final double MIN_RANGE_FACTOR            = 0.625;

    // Role-aware target-selection tuning ──────────────────────────────────────
    // These constants control the scoring formulae for each role.  Adjust to taste.

    /** Minimum entity-scan radius (blocks) used by pickBestRoleAwareTarget(). */
    private static final double MIN_TARGET_SEARCH_RADIUS = 24.0;

    // SIDEARM: inverse-square distance scoring
    /** Numerator for the SIDEARM inverse-square distance score (higher = prefer closer more). */
    private static final double SIDEARM_SCORE_MULTIPLIER = 4.0;
    /** Offset added to distance before squaring, preventing division-by-zero at point-blank range. */
    private static final double SIDEARM_DISTANCE_OFFSET  = 0.5;

    // TACTICAL_RANGED: LOS + health + distance
    /** Score bonus awarded when the recruit has a clear line of sight to the target. */
    private static final double TACTICAL_LOS_BONUS       = 1.5;
    /** Distance scale divisor in the TACTICAL_RANGED distance component (higher = more forgiving). */
    private static final double TACTICAL_DISTANCE_SCALE  = 0.5;

    // HEAVY: cluster-of-enemies scoring
    /** Radius (blocks) around a candidate target used to count nearby enemies for HEAVY scoring. */
    private static final double HEAVY_CLUSTER_RADIUS     = 6.0;
    /** Score weight applied to each enemy found in the cluster radius. */
    private static final double HEAVY_CLUSTER_WEIGHT     = 1.2;
    /** Distance scale divisor in the HEAVY distance tiebreaker component. */
    private static final double HEAVY_DISTANCE_SCALE     = 0.1;

    // UTILITY: ally-threat scoring
    /** Score multiplier applied to the threat bonus when an enemy is targeting a friendly. */
    private static final double UTILITY_THREAT_WEIGHT    = 2.0;
    /** Distance offset preventing division-by-zero in the UTILITY distance component. */
    private static final double UTILITY_DISTANCE_OFFSET  = 0.1;

    // NBT keys used to stash original spread and mark applied state
    private static final String JMTEG_ADS_FLAG = "jmteg_ads";
    private static final String JMTEG_ORIG_SPREAD = "jmteg_original_spread";

    private int aimTimer = 0;
    private int cooldownTimer = 0;
    /** Absolute game tick when current reload is allowed to complete (-1 = not scheduled). */
    private long reloadReadyAtTick = -1L;

    // Burst-fire state ─────────────────────────────────────────────────────────
    /** Shots still to be fired in the current burst cycle (0 = no burst active). */
    private int remainingBurstsInCycle = 0;
    /** Ticks to wait before firing the next shot within an active burst. */
    private int burstIntervalTick = 0;

    // strafing state
    private int strafeTimer = 0;
    private int strafeDirection = 1; // +1 = right, -1 = left

    /** Default initial strafe half-period used in the constructor before the first role detection. */
    private static final int DEFAULT_STRAFE_INIT_TICKS = 20; // half of BASIC_RANGED strafeChangeTicks

    public RecruitRangedGunnerAttackGoal(PathfinderMob mob) {
        this.mob = mob;
        this.strafeTimer = DEFAULT_STRAFE_INIT_TICKS;
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
        reloadReadyAtTick = -1L;
        remainingBurstsInCycle = 0;
        burstIntervalTick = 0;
        strafeTimer = currentProfile.strafeChangeTicks / 2;
        strafeDirection = mob.getRandom().nextBoolean() ? 1 : -1;
        // Force profile and doctrine re-detection on next tick so the goal starts with current info
        roleCacheTick = 0;
        cachedRole = null;
        currentDoctrine = null;
        currentProfile = RecruitRoleProfile.forRole(null);
    }

    @Override
    public void stop() {
        // ensure we remove any temporary ADS modifier when goal stops
        disableAdsOnHeldGun();
        mob.getNavigation().stop();
        this.state = State.IDLE;
        reloadReadyAtTick = -1L;
        remainingBurstsInCycle = 0;
        burstIntervalTick = 0;
    }

    @Override
    public void tick() {
        // ── Profile refresh ───────────────────────────────────────────────────
        // Detect the held gun's role every ROLE_CACHE_INTERVAL ticks (or on first tick).
        // When the role changes the profile is swapped immediately so movement parameters
        // update without waiting for the full interval.
        roleCacheTick--;
        if (roleCacheTick <= 0) {
            roleCacheTick = ROLE_CACHE_INTERVAL;
            RecruitGunRole detectedRole = detectHeldGunRole();
            RecruitDoctrine detectedDoctrine = RecruitDoctrineHolder.getDoctrine(mob);
            if (detectedRole != cachedRole || detectedDoctrine != currentDoctrine) {
                cachedRole      = detectedRole;
                currentDoctrine = detectedDoctrine;
                currentProfile  = RecruitRoleProfile.forRole(cachedRole).applyDoctrine(currentDoctrine);
                LOGGER.debug("{} switched to role profile {} (role={}, doctrine={})",
                        mob, currentProfile, cachedRole,
                        currentDoctrine != null ? currentDoctrine.name() : "none");
            }
            // Re-score nearby enemies on the same cadence as the profile refresh so the
            // recruit shifts to a tactically appropriate target without per-tick scanning.
            pickBestRoleAwareTarget();
        }

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            // leaving aim — clean up any temporary ADS modifiers
            disableAdsOnHeldGun();
            state = State.IDLE;
            reloadReadyAtTick = -1L;
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
                    mob.getNavigation().moveTo(target, currentProfile.approachSpeed);
                } else {
                    mob.getNavigation().stop();
                    state = State.AIM;
                    aimTimer = computeAimTicks(dist);
                    enableAdsOnHeldGun(); // start ADS-like spread reduction
                }
            }
            case AIM -> {
                // Positioning while aiming — three exclusive branches:
                //   1. Retreat: target is inside safe distance → back away.
                //   2. Strafe:  safe and strafeEnabled → circle/strafe the target.
                //   3. Stand:   safe and !strafeEnabled (heavy role) → plant feet.
                double safeSq = currentProfile.safeDistance * currentProfile.safeDistance;

                if (distSq < safeSq) {
                    // retreat directly away from target to reach safeDistance + retreatExtra
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
                    double desiredDistance = currentProfile.safeDistance + currentProfile.retreatExtra;
                    double nx = target.getX() + (dx / horiz) * desiredDistance;
                    double nz = target.getZ() + (dz / horiz) * desiredDistance;
                    double ny = mob.getY();

                    mob.getNavigation().moveTo(nx, ny, nz, currentProfile.retreatSpeed);
                } else if (currentProfile.strafeEnabled) {
                    // Strafing behavior: pick lateral offsets around current position to circle/strafe target while aiming
                    // update timer and possibly flip direction
                    strafeTimer--;
                    if (strafeTimer <= 0) {
                        strafeDirection = mob.getRandom().nextBoolean() ? 1 : -1;
                        strafeTimer = currentProfile.strafeChangeTicks + mob.getRandom().nextInt(20);
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
                    double strafeX = mob.getX() + px * currentProfile.strafeDistance * strafeDirection;
                    double strafeZ = mob.getZ() + pz * currentProfile.strafeDistance * strafeDirection;
                    double strafeY = mob.getY();

                    // Use navigation so mob can path around obstacles
                    mob.getNavigation().moveTo(strafeX, strafeY, strafeZ, currentProfile.strafeSpeed);
                } else {
                    // Heavy role: plant feet, stop navigation, aim precisely
                    mob.getNavigation().stop();
                }

                // Closer targets can snap faster; farther targets get slower, steadier aim.
                float maxYawPerTick = (float) clamp(15.0 + (1.0 - (dist / currentProfile.preferredRange)) * 60.0, 10.0, 120.0);
                float maxPitchPerTick = (float) clamp(10.0 + (1.0 - (dist / currentProfile.preferredRange)) * 40.0, 8.0, 90.0);

                // Extract projectile properties through the established JEG compat boundary.
                float projectileSpeed = JEGCompatManager.INSTANCE.getProjectileSpeed(mob.getMainHandItem());
                float projectileGravity = JEGCompatManager.INSTANCE.getProjectileGravity(mob.getMainHandItem());

                // Aim accounting for target motion and gravity
                applyAdvancedAim(mob, target, projectileSpeed, projectileGravity, maxYawPerTick, maxPitchPerTick);

                aimTimer--;
                if (aimTimer <= 0) {
                    // Initialize burst cycle on first entry into the fire-ready phase.
                    if (remainingBurstsInCycle <= 0) {
                        remainingBurstsInCycle = 1 + mob.getRandom().nextInt(MAX_BURST_COUNT);
                        burstIntervalTick = 0;    // fire first shot immediately
                        disableAdsOnHeldGun();    // restore spread before firing
                    }

                    if (burstIntervalTick > 0) {
                        burstIntervalTick--;
                    } else {
                        if (isGunLoaded()) {
                            fireShot(target);
                            remainingBurstsInCycle--;
                            if (remainingBurstsInCycle <= 0) {
                                // Burst complete — enter inter-burst cooldown.
                                cooldownTimer = computeCooldownTicks(dist);
                                state = State.COOLDOWN;
                            } else {
                                // More shots remain in this burst — wait inter-shot interval.
                                burstIntervalTick = computeBurstIntervalTicks();
                            }
                        } else {
                            // Out of ammo mid-burst — abort burst and wait for reload.
                            remainingBurstsInCycle = 0;
                            state = State.RELOADING;
                            reloadReadyAtTick = -1L;
                        }
                    }
                }
            }
            case COOLDOWN -> {
                cooldownTimer--;
                if (cooldownTimer <= 0) {
                    if (isGunLoaded()) {
                        state = State.AIM;
                        aimTimer = computeAimTicks(dist);
                        enableAdsOnHeldGun(); // re-enable ADS-like spread reduction for the next aim cycle
                    } else {
                        // Ran out of ammo during cooldown — switch to reload wait.
                        state = State.RELOADING;
                        reloadReadyAtTick = -1L;
                    }
                }
            }
            case RELOADING -> {
                // Stand still and wait until GunSyncGoal / RecruitAmmoResupplyGoal reloads the gun.
                mob.getNavigation().stop();
                if (reloadReadyAtTick < 0L) {
                    reloadReadyAtTick = mob.level().getGameTime() + computeReloadTicks();
                }
                boolean timerElapsed = mob.level().getGameTime() >= reloadReadyAtTick;
                if (timerElapsed && isGunLoaded()) {
                    state = State.AIM;
                    aimTimer = computeAimTicks(dist);
                    enableAdsOnHeldGun();
                    reloadReadyAtTick = -1L;
                } else {
                    sendReloadBubble();
                }
            }
        }
    }

    // Compute aim ticks: closer => fewer ticks (faster firing), far => longer aim for accuracy.
    // Additionally, the result is scaled up when the held gun is inappropriate for this recruit's tier,
    // and further scaled by the doctrine's ammoConservation factor (>1 = more careful, conserves ammo).
    private int computeAimTicks(double distance) {
        double t = clamp(distance / currentProfile.preferredRange, 0.0, 1.0);
        int base = (int) Math.max(MIN_AIM_TICKS, Math.round(MIN_AIM_TICKS + (MAX_AIM_TICKS - MIN_AIM_TICKS) * t));
        double weight   = getHeldGunWeight();
        double penalty  = 1.0 + (MAX_AIM_PENALTY_FACTOR - 1.0) * (1.0 - weight);
        double conserve = currentDoctrine != null ? currentDoctrine.ammoConservation : 1.0;
        return (int) Math.round(base * penalty * conserve);
    }

    private int computeCooldownTicks(double distance) {
        double t = clamp(distance / currentProfile.preferredRange, 0.0, 1.0);
        int base = (int) Math.max(MIN_COOLDOWN_TICKS, Math.round(MIN_COOLDOWN_TICKS + (MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS) * t));
        double weight   = getHeldGunWeight();
        double penalty  = 1.0 + (MAX_COOLDOWN_PENALTY_FACTOR - 1.0) * (1.0 - weight);
        double conserve = currentDoctrine != null ? currentDoctrine.ammoConservation : 1.0;
        return (int) Math.round(base * penalty * conserve);
    }

    /**
     * Returns the inter-shot delay (ticks) between shots within a burst cycle.
     * Uses the held gun's fire rate ({@code Gun.General.getRate()}) as the interval so that
     * burst cadence matches the gun's intended rate of fire, mirroring JEG's own behaviour
     * where {@code attackTime} is reset to {@code getRate()} after each shot.
     * Falls back to 1 tick on any error so the burst is never blocked.
     */
    private int computeBurstIntervalTicks() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack.isEmpty()) return 1;
            Object gun = JEGCompatManager.INSTANCE.getModifiedGun(stack);
            return Math.max(1, JEGCompatManager.INSTANCE.getGunRate(gun));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /**
     * Resolve reload duration from the currently held weapon.
     * Uses the JEG compat boundary and falls back to 20 ticks when unavailable.
     */
    private int computeReloadTicks() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack == null || stack.isEmpty()) return 20;
            return Math.max(1, JEGCompatManager.INSTANCE.getReloadTicks(stack));
        } catch (Throwable ignored) {
            return 20;
        }
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }

    // ── Firing ────────────────────────────────────────────────────────────────

    /**
     * Fires one shot at {@code target} using the held JEG gun:
     * <ol>
     *   <li>Resolves the {@code Gun} object via {@link JEGCompatManager#INSTANCE}.</li>
     *   <li>Delegates projectile spawning to {@code AIGunEvent.performGunAttack}.</li>
     *   <li>Decrements {@code AmmoCount} in the stack NBT.</li>
     *   <li>Ejects a casing via {@code GunEventBus.ejectCasing}.</li>
     *   <li>Plays the gun's fire sound, if set.</li>
     * </ol>
     * All steps are individually guarded; a failure in one does not prevent the others.
     */
    private void fireShot(LivingEntity target) {
        ItemStack stack = mob.getMainHandItem();
        if (stack == null || stack.isEmpty()) return;

        Object gun = JEGCompatManager.INSTANCE.getModifiedGun(stack);
        if (gun == null) return;

        // Spawn projectile(s). Use a minimal spread so the ADS reduction is already in NBT;
        // passing 1.0f lets JEG's own difficulty/spread multipliers handle any remaining bloom.
        float spread = computeAdsSpreadMultiplier();
        JEGCompatManager.INSTANCE.performGunAttack(mob, target, stack, gun, spread, false);

        // Consume one ammo unit from the stack.
        JustEnoughGunsCompat.consumeAmmoOnGun(stack);

        // Eject brass / shell.
        JEGCompatManager.INSTANCE.ejectCasing(mob.level(), mob);

        // Play the configured fire sound, if any.
        ResourceLocation fireSound = JEGCompatManager.INSTANCE.getFireSound(gun);
        if (fireSound != null) {
            mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                    SoundEvent.createVariableRangeEvent(fireSound),
                    SoundSource.HOSTILE, 0.5f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
        }
    }

    /**
     * Returns {@code true} when the held gun has at least one ammo unit remaining.
     * Reads {@code AmmoCount} from the stack's NBT; returns {@code false} on any error
     * so the recruit enters the RELOADING state rather than firing with an empty gun.
     */
    private boolean isGunLoaded() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack == null || stack.isEmpty()) return false;
            CompoundTag tag = stack.getTag();
            return tag != null && tag.contains("AmmoCount") && tag.getInt("AmmoCount") > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Emits the JEG reload-indicator particle ({@code jeg:bubble_ammo}) above the mob's
     * head, mirroring the visual feedback JEG's own {@code GunAttackGoal} produces while
     * reloading.  Guarded by a registry-key check so it silently no-ops when JEG is absent.
     */
    private void sendReloadBubble() {
        try {
            if (mob.level().isClientSide()) return;
            ResourceLocation bubbleKey = ResourceLocation.fromNamespaceAndPath("jeg", "bubble_ammo");

            var particleType = BuiltInRegistries.PARTICLE_TYPE.get(bubbleKey);
            if (!(particleType instanceof net.minecraft.core.particles.SimpleParticleType simple)) return;

            net.minecraft.server.level.ServerLevel serverLevel =
                    (net.minecraft.server.level.ServerLevel) mob.level();

            serverLevel.sendParticles(simple,
                    mob.getX(), mob.getY() + mob.getEyeHeight() + 0.9, mob.getZ(),
                    1, 0.0, 0.0, 0.0, 0.0);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    // ── ADS (Aim-Down-Sights) spread helpers ──────────────────────────────────

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
     * Uses the role profile's preferred range as the base. At weight=1.0 this equals
     * {@code currentProfile.preferredRange}; at weight=0.0 it is
     * {@code currentProfile.preferredRange × MIN_RANGE_FACTOR}.
     */
    private double getEffectiveAttackRange() {
        double weight = getHeldGunWeight();
        return currentProfile.preferredRange * (MIN_RANGE_FACTOR + weight * (1.0 - MIN_RANGE_FACTOR));
    }

    /**
     * Detects the {@link RecruitGunRole} of the held gun within this recruit's tier config.
     * Returns {@code null} when the gun is not in any accessible role pool (the weight
     * system will already penalise that case; the profile falls back to BASIC_RANGED).
     */
    private RecruitGunRole detectHeldGunRole() {
        try {
            ItemStack stack = mob.getMainHandItem();
            if (stack == null || stack.isEmpty()) return null;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) return null;
            RecruitLoadoutConfigManager.ensureLoaded();
            String classKey = RecruitGunSelector.detectRecruitClassKey(mob);
            RecruitLoadoutConfigManager.RecruitTierConfig tier =
                    RecruitLoadoutConfigManager.getTierConfig(classKey);
            return RecruitGunSelector.detectRole(id, tier);
        } catch (Throwable t) {
            return null;
        }
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

    // ── Role-aware target selection ───────────────────────────────────────────

    /**
     * Scans nearby living entities, scores each one according to the current gun role,
     * and calls {@code mob.setTarget(best)} to override the standard nearest-enemy selection.
     *
     * <p>Scoring rules per role:
     * <ul>
     *   <li><b>SIDEARM</b>        – strongly prefers the closest threat (inverse-square of distance).</li>
     *   <li><b>BASIC_RANGED</b>   – prefers the nearest enemy (safe default; inverse of distance).</li>
     *   <li><b>TACTICAL_RANGED</b>– prefers exposed (clear line-of-sight) and weakened targets.</li>
     *   <li><b>HEAVY</b>          – prefers targets that are surrounded by the most enemies.</li>
     *   <li><b>UTILITY</b>        – prefers enemies that are currently targeting nearby allies.</li>
     * </ul>
     *
     * <p>Only called during the profile-refresh window (every {@link #ROLE_CACHE_INTERVAL} ticks)
     * so entity scanning does not occur every tick.
     */
    private void pickBestRoleAwareTarget() {
        double searchRadius = Math.max(currentProfile.preferredRange * 2.0, MIN_TARGET_SEARCH_RADIUS);
        List<LivingEntity> candidates = mob.level().getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(searchRadius),
                e -> e != mob && e.isAlive() && !mob.isAlliedTo(e) && mob.canAttack(e));

        if (candidates.isEmpty()) {
            return;
        }

        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            double score = scoreTargetForRole(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            mob.setTarget(best);
        }
    }

    /**
     * Computes a priority score for a target candidate based on {@link #cachedRole}.
     * Higher score = higher priority.  Scores from different roles are not directly
     * comparable, but within any one role they rank candidates correctly.
     */
    private double scoreTargetForRole(LivingEntity target) {
        double dist = mob.distanceTo(target);
        RecruitGunRole role = cachedRole != null ? cachedRole : RecruitGunRole.BASIC_RANGED;
        return switch (role) {
            case SIDEARM         -> scoreForSidearm(dist);
            case BASIC_RANGED    -> scoreForBasicRanged(dist);
            case TACTICAL_RANGED -> scoreForTacticalRanged(dist, target);
            case HEAVY           -> scoreForHeavy(dist, target);
            case UTILITY         -> scoreForUtility(dist, target);
        };
    }

    /**
     * SIDEARM: strongly prefers the closest threat.
     * Score decays as inverse-square of distance so even modest range differences
     * produce a large preference for the nearer target.
     */
    private static double scoreForSidearm(double dist) {
        double d = dist + SIDEARM_DISTANCE_OFFSET;
        return SIDEARM_SCORE_MULTIPLIER / (d * d);
    }

    /**
     * BASIC_RANGED: simple nearest-first — inverse of distance.
     * Reproduces the vanilla "attack nearest enemy" behaviour as a baseline.
     */
    private static double scoreForBasicRanged(double dist) {
        return 1.0 / (dist + 0.1);
    }

    /**
     * TACTICAL_RANGED: rifles prefer exposed and vulnerable targets.
     * <ul>
     *   <li>+1.5 bonus when the recruit has clear line of sight (no cover).</li>
     *   <li>+0–1.0 bonus proportional to how much health the target has already lost.</li>
     *   <li>Small distance component breaks ties in favour of closer targets.</li>
     * </ul>
     */
    private double scoreForTacticalRanged(double dist, LivingEntity target) {
        double losBonus     = mob.hasLineOfSight(target) ? TACTICAL_LOS_BONUS : 0.0;
        double healthRatio  = target.getMaxHealth() > 0
                              ? target.getHealth() / target.getMaxHealth() : 1.0;
        double exposedBonus = 1.0 - healthRatio; // 0 (full health) → 1 (nearly dead)
        double distScore    = 1.0 / (dist * TACTICAL_DISTANCE_SCALE + 1.0);
        return losBonus + exposedBonus + distScore;
    }

    /**
     * HEAVY (rocket launchers / miniguns): prefers clustered enemy groups.
     * A large cluster count dominates; distance acts as a tiebreaker.
     */
    private double scoreForHeavy(double dist, LivingEntity target) {
        int cluster = countNearbyEnemies(target, HEAVY_CLUSTER_RADIUS);
        return cluster * HEAVY_CLUSTER_WEIGHT + 1.0 / (dist * HEAVY_DISTANCE_SCALE + 1.0);
    }

    /**
     * UTILITY / support: prefers enemies that are actively threatening nearby allies.
     * Falls back to nearest when no immediate ally threat is detected.
     */
    private double scoreForUtility(double dist, LivingEntity target) {
        double threatBonus = computeAllyThreatBonus(target);
        return threatBonus * UTILITY_THREAT_WEIGHT + 1.0 / (dist + UTILITY_DISTANCE_OFFSET);
    }

    /**
     * Returns the count of valid enemy entities within {@link #HEAVY_CLUSTER_RADIUS} blocks of
     * {@code center}.  Used by the HEAVY role scorer to identify clustered target groups.
     */
    private int countNearbyEnemies(LivingEntity center, double radius) {
        return mob.level().getEntitiesOfClass(
                LivingEntity.class,
                center.getBoundingBox().inflate(radius),
                e -> e != mob && e != center && e.isAlive()
                        && !mob.isAlliedTo(e) && mob.canAttack(e)
        ).size();
    }

    /**
     * Returns 1.0 if {@code enemy} is currently targeting an allied entity, 0.0 otherwise.
     * Used by the UTILITY role scorer to prioritise threats to friendly units.
     */
    private double computeAllyThreatBonus(LivingEntity enemy) {
        if (!(enemy instanceof Mob enemyMob)) return 0.0;
        LivingEntity enemyTarget = enemyMob.getTarget();
        if (enemyTarget == null) return 0.0;
        return mob.isAlliedTo(enemyTarget) ? 1.0 : 0.0;
    }
}
