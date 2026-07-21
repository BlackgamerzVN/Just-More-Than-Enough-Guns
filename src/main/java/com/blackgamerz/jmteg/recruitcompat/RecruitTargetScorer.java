package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;

import java.util.List;

/**
 * Role-aware target scoring/selection logic extracted from {@link RecruitRangedGunnerAttackGoal}.
 *
 * <p>Scans nearby living entities and scores each candidate according to the recruit's
 * current {@link RecruitGunRole} so the goal can override vanilla's nearest-enemy target
 * selection with a tactically appropriate choice:
 * <ul>
 *   <li><b>SIDEARM</b>        – strongly prefers the closest threat (inverse-square of distance).</li>
 *   <li><b>BASIC_RANGED</b>   – prefers the nearest enemy (safe default; inverse of distance).</li>
 *   <li><b>TACTICAL_RANGED</b>– prefers exposed (clear line-of-sight) and weakened targets.</li>
 *   <li><b>HEAVY</b>          – prefers targets that are surrounded by the most enemies.</li>
 *   <li><b>UTILITY</b>        – prefers enemies that are currently targeting nearby allies.</li>
 * </ul>
 */
final class RecruitTargetScorer {

    private RecruitTargetScorer() {
    }

    /** Minimum entity-scan radius (blocks) used by {@link #pickBestRoleAwareTarget}. */
    private static final double MIN_TARGET_SEARCH_RADIUS = 24.0;

    // SIDEARM: inverse-square distance scoring
    /** Numerator for the SIDEARM inverse-square distance score (higher = prefer closer more). */
    private static final double SIDEARM_SCORE_MULTIPLIER = 4.0;
    /** Offset added to distance before squaring, preventing division-by-zero at point-blank range. */
    private static final double SIDEARM_DISTANCE_OFFSET = 0.5;

    // TACTICAL_RANGED: LOS + health + distance
    /** Score bonus awarded when the recruit has a clear line of sight to the target. */
    private static final double TACTICAL_LOS_BONUS = 1.5;
    /** Distance scale divisor in the TACTICAL_RANGED distance component (higher = more forgiving). */
    private static final double TACTICAL_DISTANCE_SCALE = 0.5;

    // HEAVY: cluster-of-enemies scoring
    /** Radius (blocks) around a candidate target used to count nearby enemies for HEAVY scoring. */
    private static final double HEAVY_CLUSTER_RADIUS = 6.0;
    /** Score weight applied to each enemy found in the cluster radius. */
    private static final double HEAVY_CLUSTER_WEIGHT = 1.2;
    /** Distance scale divisor in the HEAVY distance tiebreaker component. */
    private static final double HEAVY_DISTANCE_SCALE = 0.1;

    // UTILITY: ally-threat scoring
    /** Score multiplier applied to the threat bonus when an enemy is targeting a friendly. */
    private static final double UTILITY_THREAT_WEIGHT = 2.0;
    /** Distance offset preventing division-by-zero in the UTILITY distance component. */
    private static final double UTILITY_DISTANCE_OFFSET = 0.1;

    /**
     * Scans nearby living entities, scores each one according to {@code cachedRole}, and
     * returns the tactically best one, or {@code null} when no valid candidate is found
     * (caller should leave the mob's existing target unchanged in that case).
     *
     * @param preferredRange the role profile's preferred engagement range, used to size the scan radius
     */
    static LivingEntity pickBestRoleAwareTarget(PathfinderMob mob, RecruitGunRole cachedRole, double preferredRange) {
        double searchRadius = Math.max(preferredRange * 2.0, MIN_TARGET_SEARCH_RADIUS);
        List<LivingEntity> candidates = mob.level().getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(searchRadius),
                e -> e != mob && e.isAlive() && !mob.isAlliedTo(e) && mob.canAttack(e));

        if (candidates.isEmpty()) {
            return null;
        }

        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            double score = scoreTargetForRole(mob, cachedRole, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Computes a priority score for a target candidate based on {@code cachedRole}.
     * Higher score = higher priority. Scores from different roles are not directly
     * comparable, but within any one role they rank candidates correctly.
     */
    private static double scoreTargetForRole(PathfinderMob mob, RecruitGunRole cachedRole, LivingEntity target) {
        double dist = mob.distanceTo(target);
        RecruitGunRole role = cachedRole != null ? cachedRole : RecruitGunRole.BASIC_RANGED;
        return switch (role) {
            case SIDEARM         -> scoreForSidearm(dist);
            case BASIC_RANGED    -> scoreForBasicRanged(dist);
            case TACTICAL_RANGED -> scoreForTacticalRanged(mob, dist, target);
            case HEAVY           -> scoreForHeavy(mob, dist, target);
            case UTILITY         -> scoreForUtility(mob, dist, target);
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
    private static double scoreForTacticalRanged(PathfinderMob mob, double dist, LivingEntity target) {
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
    private static double scoreForHeavy(PathfinderMob mob, double dist, LivingEntity target) {
        int cluster = countNearbyEnemies(mob, target, HEAVY_CLUSTER_RADIUS);
        return cluster * HEAVY_CLUSTER_WEIGHT + 1.0 / (dist * HEAVY_DISTANCE_SCALE + 1.0);
    }

    /**
     * UTILITY / support: prefers enemies that are actively threatening nearby allies.
     * Falls back to nearest when no immediate ally threat is detected.
     */
    private static double scoreForUtility(PathfinderMob mob, double dist, LivingEntity target) {
        double threatBonus = computeAllyThreatBonus(mob, target);
        return threatBonus * UTILITY_THREAT_WEIGHT + 1.0 / (dist + UTILITY_DISTANCE_OFFSET);
    }

    /**
     * Returns the count of valid enemy entities within {@code radius} blocks of {@code center}.
     * Used by the HEAVY role scorer to identify clustered target groups.
     */
    private static int countNearbyEnemies(PathfinderMob mob, LivingEntity center, double radius) {
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
    private static double computeAllyThreatBonus(PathfinderMob mob, LivingEntity enemy) {
        if (!(enemy instanceof Mob enemyMob)) return 0.0;
        LivingEntity enemyTarget = enemyMob.getTarget();
        if (enemyTarget == null) return 0.0;
        return mob.isAlliedTo(enemyTarget) ? 1.0 : 0.0;
    }
}
