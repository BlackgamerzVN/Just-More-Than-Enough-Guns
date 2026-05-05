package com.blackgamerz.jmteg.recruitcompat;

/**
 * Squad doctrine that modifies a recruit's tactical behaviour on top of the
 * role-profile that is derived from the gun it is carrying.
 *
 * <h3>Multiplier semantics</h3>
 * <ul>
 *   <li>{@link #rangeMultiplier}    – applied to {@code preferredRange} and
 *       {@code safeDistance} in the {@link RecruitRoleProfile}.  Values&nbsp;&gt;&nbsp;1
 *       push the recruit further from the target; values&nbsp;&lt;&nbsp;1 bring it closer.</li>
 *   <li>{@link #retreatMultiplier}  – applied to {@code retreatExtra} and
 *       {@code retreatSpeed}.  Values&nbsp;&gt;&nbsp;1 make the recruit retreat farther
 *       and faster when the target closes in.</li>
 *   <li>{@link #ammoConservation}   – scales aim-ticks and cooldown-ticks inside
 *       {@link RecruitRangedGunnerAttackGoal}.  Values&nbsp;&gt;&nbsp;1.0 produce
 *       longer pauses between shots (more careful, conserves ammo); values&nbsp;&lt;&nbsp;1.0
 *       shorten those pauses (fires more aggressively).</li>
 *   <li>{@link #approachSpeedMult}  – applied to {@code approachSpeed} in the role
 *       profile; affects how quickly the recruit moves toward its target.</li>
 * </ul>
 *
 * <h3>Setting a doctrine</h3>
 * <ul>
 *   <li><b>Per recruit:</b> the recruit's owner can <b>shift-right-click</b> it with
 *       an empty main hand to cycle through all five doctrines.  A chat message
 *       confirms the new selection.  The choice is stored in the mob's
 *       {@link net.minecraft.world.entity.Entity#getPersistentData() persistent data}
 *       and survives server reloads.</li>
 *   <li><b>Per squad (commander):</b> running {@code /jmteg doctrine <NAME>} stores the
 *       doctrine on the executing player (or entity).  Any recruit assigned to that
 *       commander that has no individual doctrine will inherit it automatically.</li>
 * </ul>
 *
 * @see RecruitDoctrineHolder
 * @see RecruitRoleProfile#applyDoctrine(RecruitDoctrine)
 */
public enum RecruitDoctrine {

    /**
     * Rush in and fire freely.
     * Engage at close range, barely retreat, and fire as fast as possible.
     * Best paired with SIDEARM or TACTICAL_RANGED guns.
     */
    AGGRESSIVE(
            /* displayName       */ "Aggressive",
            /* rangeMultiplier   */ 0.75,
            /* retreatMultiplier */ 0.50,
            /* ammoConservation  */ 0.75,
            /* approachSpeedMult */ 1.30
    ),

    /**
     * Hold the line.
     * Engage from a safe distance, retreat readily, and wait for clear shots
     * to conserve ammo.  Best paired with HEAVY or BASIC_RANGED guns.
     */
    DEFENSIVE(
            /* displayName       */ "Defensive",
            /* rangeMultiplier   */ 1.20,
            /* retreatMultiplier */ 1.50,
            /* ammoConservation  */ 1.30,
            /* approachSpeedMult */ 0.80
    ),

    /**
     * Mobile hit-and-run.
     * Normal engagement range but retreats more often; approaches quickly and
     * keeps repositioning.  Best paired with TACTICAL_RANGED guns.
     */
    SKIRMISHER(
            /* displayName       */ "Skirmisher",
            /* rangeMultiplier   */ 1.00,
            /* retreatMultiplier */ 1.20,
            /* ammoConservation  */ 1.00,
            /* approachSpeedMult */ 1.20
    ),

    /**
     * Long-range precision fire.
     * Hangs back at maximum range, retreats strongly if approached, and fires
     * slowly for maximum accuracy.  Best paired with HEAVY guns.
     */
    SIEGE(
            /* displayName       */ "Siege",
            /* rangeMultiplier   */ 1.50,
            /* retreatMultiplier */ 1.80,
            /* ammoConservation  */ 1.50,
            /* approachSpeedMult */ 0.70
    ),

    /**
     * Close-protection detail.
     * Stays within short range of the owner, does not retreat much, and fires
     * at a steady cadence.  Best paired with UTILITY or BASIC_RANGED guns.
     */
    ESCORT(
            /* displayName       */ "Escort",
            /* rangeMultiplier   */ 0.90,
            /* retreatMultiplier */ 0.80,
            /* ammoConservation  */ 1.10,
            /* approachSpeedMult */ 1.00
    );

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Human-readable name shown in chat messages and command suggestions. */
    public final String displayName;

    /** Multiplier applied to {@code preferredRange} and {@code safeDistance}. */
    public final double rangeMultiplier;

    /**
     * Multiplier applied to {@code retreatExtra} and {@code retreatSpeed}.
     * Values&nbsp;&gt;&nbsp;1 make the recruit retreat farther and faster when
     * the target closes inside the safe distance.
     */
    public final double retreatMultiplier;

    /**
     * Multiplier applied to aim-tick and cooldown-tick counts.
     * <ul>
     *   <li>&gt;&nbsp;1.0 → fires more carefully (longer pauses → conserves ammo).</li>
     *   <li>&lt;&nbsp;1.0 → fires more aggressively (shorter pauses → spends ammo faster).</li>
     * </ul>
     */
    public final double ammoConservation;

    /** Multiplier applied to {@code approachSpeed}. */
    public final double approachSpeedMult;

    /**
     * NBT key used to persist the doctrine name on mobs and on
     * CommanderEntities (players / squads).
     */
    public static final String NBT_KEY = "jmteg_doctrine";

    // ── Constructor ───────────────────────────────────────────────────────────

    RecruitDoctrine(
            String displayName,
            double rangeMultiplier,
            double retreatMultiplier,
            double ammoConservation,
            double approachSpeedMult) {
        this.displayName      = displayName;
        this.rangeMultiplier  = rangeMultiplier;
        this.retreatMultiplier = retreatMultiplier;
        this.ammoConservation = ammoConservation;
        this.approachSpeedMult = approachSpeedMult;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the next doctrine in cycle order, wrapping from the last back to the
     * first.  Used by the shift-right-click toggle in {@link RecruitDoctrineHolder}.
     */
    public RecruitDoctrine next() {
        RecruitDoctrine[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /**
     * Parses a doctrine by its enum constant name (case-insensitive).
     *
     * @param name the name string (e.g. {@code "AGGRESSIVE"} or {@code "aggressive"})
     * @return the matching doctrine, or {@code null} if the name is unknown
     */
    public static RecruitDoctrine fromName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (RecruitDoctrine d : values()) {
            if (d.name().equalsIgnoreCase(name)) return d;
        }
        return null;
    }
}
