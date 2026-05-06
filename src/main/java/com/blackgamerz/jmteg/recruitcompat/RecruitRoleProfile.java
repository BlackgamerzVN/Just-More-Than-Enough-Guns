package com.blackgamerz.jmteg.recruitcompat;

/**
 * Immutable movement / positioning profile for a {@link RecruitGunRole}.
 *
 * <p>Each role has one canonical profile that drives all pathfinding behaviour inside
 * {@link RecruitRangedGunnerAttackGoal}:
 * <ul>
 *   <li>{@link #preferredRange}   – ideal engagement distance (blocks); used as ATTACK_RANGE.</li>
 *   <li>{@link #safeDistance}     – distance at which the recruit starts retreating.</li>
 *   <li>{@link #safeExitBuffer}   – extra distance beyond {@code safeDistance} used as the
 *                                   retreat hysteresis threshold and retreat-to overshoot.</li>
 *   <li>{@link #retreatExtra}     – additional blocks beyond {@code safeDistance} to navigate
 *                                   to when retreating (retreat target = safeDistance + retreatExtra).</li>
 *   <li>{@link #approachSpeed}    – navigation speed multiplier during SEEK state.</li>
 *   <li>{@link #retreatSpeed}     – navigation speed multiplier during retreat.</li>
 *   <li>{@link #strafeSpeed}      – navigation speed multiplier while strafing.</li>
 *   <li>{@link #strafeDistance}   – lateral offset in blocks when strafing.</li>
 *   <li>{@link #strafeChangeTicks}– ticks between strafe direction changes.</li>
 *   <li>{@link #strafeEnabled}    – when {@code false} the recruit stands still while aiming
 *                                   (appropriate for heavy weapons: rocket launchers, miniguns).</li>
 * </ul>
 *
 * <p>Use {@link #forRole(RecruitGunRole)} to obtain the profile for a given role.
 * {@code null} input returns the {@link RecruitGunRole#BASIC_RANGED} profile as a safe default.
 */
public final class RecruitRoleProfile {

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Ideal engagement distance (blocks). Functions as ATTACK_RANGE for this role. */
    public final double preferredRange;

    /** Distance at which the recruit begins retreating from the target. */
    public final double safeDistance;

    /**
     * Hysteresis buffer added to {@link #safeDistance} to determine the exit condition for
     * the retreat phase and the patrol "bubble" around the safe zone.
     */
    public final double safeExitBuffer;

    /**
     * Extra distance beyond {@link #safeDistance} to navigate to when retreating.
     * Retreat destination = target position + (safeDistance + retreatExtra) in the away direction.
     */
    public final double retreatExtra;

    /** Navigation speed multiplier used when seeking the target (SEEK state). */
    public final double approachSpeed;

    /** Navigation speed multiplier used when retreating from the target. */
    public final double retreatSpeed;

    /** Navigation speed multiplier used when strafing during the AIM state. */
    public final double strafeSpeed;

    /** Lateral strafe offset in blocks. */
    public final double strafeDistance;

    /** Ticks between random strafe-direction changes. */
    public final int strafeChangeTicks;

    /**
     * Whether strafing is enabled for this role.
     * Heavy-weapon roles set this to {@code false} so the recruit plants their feet while aiming.
     */
    public final boolean strafeEnabled;

    // ── Constructor ───────────────────────────────────────────────────────────

    private RecruitRoleProfile(
            double preferredRange,
            double safeDistance,
            double safeExitBuffer,
            double retreatExtra,
            double approachSpeed,
            double retreatSpeed,
            double strafeSpeed,
            double strafeDistance,
            int    strafeChangeTicks,
            boolean strafeEnabled) {
        this.preferredRange    = preferredRange;
        this.safeDistance      = safeDistance;
        this.safeExitBuffer    = safeExitBuffer;
        this.retreatExtra      = retreatExtra;
        this.approachSpeed     = approachSpeed;
        this.retreatSpeed      = retreatSpeed;
        this.strafeSpeed       = strafeSpeed;
        this.strafeDistance    = strafeDistance;
        this.strafeChangeTicks = strafeChangeTicks;
        this.strafeEnabled     = strafeEnabled;
    }

    // ── Role profiles ─────────────────────────────────────────────────────────

    /**
     * Pistols / revolvers — mobile, aggressive, short-to-medium range.
     * Recruit closes in quickly, keeps moving, and fires at close range.
     */
    private static final RecruitRoleProfile SIDEARM = new RecruitRoleProfile(
            /* preferredRange    */ 7.0,
            /* safeDistance      */ 2.5,
            /* safeExitBuffer    */ 1.5,
            /* retreatExtra      */ 1.0,
            /* approachSpeed     */ 1.3,
            /* retreatSpeed      */ 1.4,
            /* strafeSpeed       */ 1.2,
            /* strafeDistance    */ 2.0,
            /* strafeChangeTicks */ 30,
            /* strafeEnabled     */ true
    );

    /**
     * Standard rifles / light shotguns — balanced infantry engagement profile.
     * Recruit keeps medium range, strafes steadily, and approaches at a measured pace.
     */
    private static final RecruitRoleProfile BASIC_RANGED = new RecruitRoleProfile(
            /* preferredRange    */ 12.0,
            /* safeDistance      */ 4.0,
            /* safeExitBuffer    */ 2.0,
            /* retreatExtra      */ 1.5,
            /* approachSpeed     */ 1.1,
            /* retreatSpeed      */ 1.25,
            /* strafeSpeed       */ 1.0,
            /* strafeDistance    */ 1.5,
            /* strafeChangeTicks */ 40,
            /* strafeEnabled     */ true
    );

    /**
     * Assault rifles / SMGs / combat shotguns — intermediate-range specialist.
     * Recruit maintains a comfortable fighting distance and strafes more aggressively.
     */
    private static final RecruitRoleProfile TACTICAL_RANGED = new RecruitRoleProfile(
            /* preferredRange    */ 15.0,
            /* safeDistance      */ 5.0,
            /* safeExitBuffer    */ 2.5,
            /* retreatExtra      */ 2.0,
            /* approachSpeed     */ 1.0,
            /* retreatSpeed      */ 1.2,
            /* strafeSpeed       */ 1.1,
            /* strafeDistance    */ 2.0,
            /* strafeChangeTicks */ 35,
            /* strafeEnabled     */ true
    );

    /**
     * Rocket/grenade launchers, miniguns — elite long-range profile.
     * Recruit hangs back at maximum range, does NOT strafe (plants feet for accurate heavy shots),
     * and retreats quickly if the target gets too close.
     */
    private static final RecruitRoleProfile HEAVY = new RecruitRoleProfile(
            /* preferredRange    */ 20.0,
            /* safeDistance      */ 8.0,
            /* safeExitBuffer    */ 3.0,
            /* retreatExtra      */ 3.0,
            /* approachSpeed     */ 0.9,
            /* retreatSpeed      */ 1.35,
            /* strafeSpeed       */ 1.0,   // unused when strafeEnabled=false
            /* strafeDistance    */ 2.0,   // unused when strafeEnabled=false
            /* strafeChangeTicks */ 40,    // unused when strafeEnabled=false
            /* strafeEnabled     */ false
    );

    /**
     * Support / utility weapons — conservative engagement profile.
     * Recruit keeps a moderate distance and strafes lightly.
     */
    private static final RecruitRoleProfile UTILITY = new RecruitRoleProfile(
            /* preferredRange    */ 10.0,
            /* safeDistance      */ 3.5,
            /* safeExitBuffer    */ 2.0,
            /* retreatExtra      */ 1.5,
            /* approachSpeed     */ 1.0,
            /* retreatSpeed      */ 1.2,
            /* strafeSpeed       */ 0.9,
            /* strafeDistance    */ 1.2,
            /* strafeChangeTicks */ 45,
            /* strafeEnabled     */ true
    );

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns the {@link RecruitRoleProfile} for the given role.
     * Falls back to {@link #BASIC_RANGED} when {@code role} is {@code null} (e.g. the gun
     * is not in any tier pool — the weight system will already apply a penalty for that case).
     *
     * @param role the gun's detected role, or {@code null}
     * @return the matching profile; never {@code null}
     */
    public static RecruitRoleProfile forRole(RecruitGunRole role) {
        if (role == null) return BASIC_RANGED;
        return switch (role) {
            case SIDEARM         -> SIDEARM;
            case BASIC_RANGED    -> BASIC_RANGED;
            case TACTICAL_RANGED -> TACTICAL_RANGED;
            case HEAVY           -> HEAVY;
            case UTILITY         -> UTILITY;
        };
    }

    // ── Doctrine modifier ─────────────────────────────────────────────────────

    /**
     * Returns a new {@link RecruitRoleProfile} with all multipliers from the given
     * {@link RecruitDoctrine} applied on top of this profile's values.
     *
     * <p>The following fields are scaled:
     * <ul>
     *   <li>{@link #preferredRange}  × {@code doctrine.rangeMultiplier}</li>
     *   <li>{@link #safeDistance}    × {@code doctrine.rangeMultiplier}</li>
     *   <li>{@link #retreatExtra}    × {@code doctrine.retreatMultiplier}</li>
     *   <li>{@link #approachSpeed}   × {@code doctrine.approachSpeedMult}</li>
     *   <li>{@link #retreatSpeed}    × {@code doctrine.retreatMultiplier}</li>
     * </ul>
     *
     * <p>The following fields are intentionally left unchanged because they
     * are controlled by the gun role and should not be overridden by doctrine:
     * {@link #safeExitBuffer}, {@link #strafeSpeed}, {@link #strafeDistance},
     * {@link #strafeChangeTicks}, {@link #strafeEnabled}.
     *
     * <p>Aim-ticks and cooldown-ticks are scaled separately inside
     * {@link RecruitRangedGunnerAttackGoal} using
     * {@code doctrine.ammoConservation} so that the timing logic stays in one
     * place.
     *
     * @param doctrine the doctrine to apply; returns {@code this} unchanged when
     *                 {@code null}
     * @return a new profile with doctrine modifiers applied, or {@code this} when
     *         {@code doctrine} is {@code null}
     */
    public RecruitRoleProfile applyDoctrine(RecruitDoctrine doctrine) {
        if (doctrine == null) return this;
        return new RecruitRoleProfile(
                preferredRange    * doctrine.rangeMultiplier,
                safeDistance      * doctrine.rangeMultiplier,
                safeExitBuffer,                              // hysteresis: not scaled by doctrine
                retreatExtra      * doctrine.retreatMultiplier,
                approachSpeed     * doctrine.approachSpeedMult,
                retreatSpeed      * doctrine.retreatMultiplier,
                strafeSpeed,                                 // strafe speed: not scaled by doctrine
                strafeDistance,                              // strafe distance: not scaled by doctrine
                strafeChangeTicks,                           // strafe timing: not scaled by doctrine
                strafeEnabled
        );
    }
}
