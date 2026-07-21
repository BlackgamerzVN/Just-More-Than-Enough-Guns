package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

/**
 * Pure ballistic/aiming math extracted from {@link RecruitRangedGunnerAttackGoal}.
 *
 * <p>Holds the intercept-time solver, the ballistic-pitch formula, and the small angle
 * helpers ({@code rotLerp}/{@code wrapDegrees}/{@code clamp}) used to smoothly rotate a
 * shooter towards a predicted lead point. Every method here is a pure function of its
 * arguments (aside from mutating the shooter's yaw/pitch in {@link #applyAdvancedAim}) —
 * no goal-instance state is read or written.
 */
final class RecruitAimSolver {

    private RecruitAimSolver() {
    }

    // Downward bias (degrees) to reduce overshooting; increase to aim lower.
    private static final float AIM_DOWN_BIAS_DEGREES = 200.0f;
    private static final float AIM_DOWN_BIAS_DEGREES_SQR = AIM_DOWN_BIAS_DEGREES * AIM_DOWN_BIAS_DEGREES;

    static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }

    /**
     * Aim helper that:
     * - predicts a lead point using an intercept solver (ignores gravity for time estimate)
     * - computes elevation needed to hit that point given projectile speed and gravity
     * - applies smooth rotation (yaw & pitch interpolation limited by maxDelta)
     *
     * Uses a stable ballistic formula and picks the lower-angle trajectory.
     */
    static void applyAdvancedAim(PathfinderMob shooter, LivingEntity target, float projectileSpeed, float gravity, float maxYawChange, float maxPitchChange) {
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
        pitchDeg += AIM_DOWN_BIAS_DEGREES_SQR;
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
}
