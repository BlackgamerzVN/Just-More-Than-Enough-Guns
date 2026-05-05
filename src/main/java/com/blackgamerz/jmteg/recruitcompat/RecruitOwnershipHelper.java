package com.blackgamerz.jmteg.recruitcompat;

import net.minecraft.world.entity.PathfinderMob;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Determines whether a recruit should use ammo-aware behaviour (ammo consumption,
 * resupply goal, weapon sanitization) or fall back to the original JEG/Recruits
 * reload mechanic that does not require or consume ammo.
 *
 * <h3>Rule</h3>
 * <p>A recruit is "ammo-aware" (i.e. subject to ammo constraints) if and only if
 * it satisfies <b>at least one</b> of:
 * <ol>
 *   <li>It is owned by a <b>player</b> (the entity reports a non-null/non-zero
 *       owner UUID via reflection on the Recruits mod API).</li>
 *   <li>It is affiliated with a <b>faction or village</b> (detected via a set
 *       of known Recruits mod method names: {@code isVillagerRecruit},
 *       {@code hasFaction}, {@code isPartOfVillage}, {@code isVillageGuard},
 *       or a class name that contains "villager" or "village").</li>
 * </ol>
 *
 * <p>Any recruit that does not belong to either category (e.g., a wild or
 * purely NPC recruit spawned without player interaction) retains the original
 * weapon-reload behaviour: {@code IgnoreAmmo} is never stripped, {@code AmmoCount}
 * is never clamped, and {@link RecruitAmmoResupplyGoal} is never registered.</p>
 *
 * <p>All detection is done via soft-dependency reflection so this class compiles
 * and runs even when the Recruits mod is absent.</p>
 */
public final class RecruitOwnershipHelper {

    private static final Logger LOGGER = LogManager.getLogger("JMTEG-OwnershipHelper");

    /** Method names tried (in order) when detecting a player owner. */
    private static final String[] OWNER_UUID_METHODS = {
            "getOwnerUUID",   // most common pattern in Recruits 1.x
            "getOwnerId",
            "getOwnerUniqueId",
    };

    /** Method names tried (in order) when detecting a live owner entity reference. */
    private static final String[] OWNER_ENTITY_METHODS = {
            "getOwner",
            "getMaster",
    };

    /** Method names tried when checking tamed / hired status. */
    private static final String[] IS_TAMED_METHODS = {
            "isTamed",
            "isHired",
            "hasOwner",
    };

    /** Method names tried for faction / village affiliation. */
    private static final String[] FACTION_METHODS = {
            "isVillagerRecruit",
            "hasFaction",
            "isPartOfVillage",
            "isVillageGuard",
            "hasVillage",
            "isForHire",   // some mods mark unowned-but-village-affiliated recruits this way
    };

    private RecruitOwnershipHelper() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code mob} is a Recruits-mod entity that is
     * either player-owned or faction/village-affiliated, and should therefore
     * operate under ammo-consumption constraints.
     *
     * <p>Returns {@code false} for non-Recruits entities and for Recruits
     * entities that are neither owned nor faction-affiliated (wild/NPC
     * recruits), restoring original infinite-ammo reload behaviour for those.</p>
     *
     * @param mob the entity to test; {@code null} is safe and returns {@code false}
     * @return {@code true} iff ammo-aware behaviour should apply
     */
    public static boolean isAmmoAwareRecruit(PathfinderMob mob) {
        if (mob == null) return false;
        if (!isRecruitEntity(mob)) return false;
        return hasPlayerOwner(mob) || hasFactionAffiliation(mob);
    }

    // ── Recruit entity detection ──────────────────────────────────────────────

    /**
     * Returns {@code true} when the entity's fully-qualified class name
     * indicates it originates from the Recruits mod.
     */
    private static boolean isRecruitEntity(PathfinderMob mob) {
        String fqcn = mob.getClass().getName().toLowerCase();
        // The Recruits mod by Talhanation uses "com.talhanation.recruits" package
        // Fall back to a simple name check for derivative/fork mods
        return fqcn.contains("talhanation.recruits") || fqcn.contains(".recruits.");
    }

    // ── Player ownership detection ────────────────────────────────────────────

    /**
     * Returns {@code true} when the entity carries a non-zero player owner UUID
     * or a non-null owner entity reference.
     */
    private static boolean hasPlayerOwner(PathfinderMob mob) {
        // 1. Try getOwnerUUID() / getOwnerId() / getOwnerUniqueId() -> UUID or Optional<UUID>
        for (String name : OWNER_UUID_METHODS) {
            try {
                Method m = findMethod(mob, name);
                if (m == null) continue;
                Object result = m.invoke(mob);
                if (result instanceof UUID uuid) {
                    // A non-null UUID with at least one non-zero bit means "owned"
                    if (uuid.getMostSignificantBits() != 0 || uuid.getLeastSignificantBits() != 0) {
                        return true;
                    }
                }
                if (result instanceof Optional<?> opt && opt.isPresent()) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        // 2. Try getOwner() / getMaster() -> LivingEntity or Optional<LivingEntity>
        for (String name : OWNER_ENTITY_METHODS) {
            try {
                Method m = findMethod(mob, name);
                if (m == null) continue;
                Object result = m.invoke(mob);
                if (result instanceof net.minecraft.world.entity.LivingEntity) return true;
                if (result instanceof Optional<?> opt && opt.isPresent()) return true;
            } catch (Throwable ignored) {}
        }

        // 3. Try isTamed() / isHired() / hasOwner() -> boolean
        for (String name : IS_TAMED_METHODS) {
            try {
                Method m = findMethod(mob, name);
                if (m == null) continue;
                Object result = m.invoke(mob);
                if (result instanceof Boolean b && b) return true;
            } catch (Throwable ignored) {}
        }

        return false;
    }

    // ── Faction / village affiliation detection ───────────────────────────────

    /**
     * Returns {@code true} when the entity is affiliated with a Recruits-mod
     * faction or village, even if it has no direct player owner.
     */
    private static boolean hasFactionAffiliation(PathfinderMob mob) {
        // 1. Try known boolean method names
        for (String name : FACTION_METHODS) {
            try {
                Method m = findMethod(mob, name);
                if (m == null) continue;
                Object result = m.invoke(mob);
                if (result instanceof Boolean b && b) return true;
            } catch (Throwable ignored) {}
        }

        // 2. Class-name heuristic: VillagerRecruit sub-classes
        String simpleName = mob.getClass().getSimpleName().toLowerCase();
        if (simpleName.contains("villager") || simpleName.contains("village")) {
            LOGGER.debug("Treating {} as faction-affiliated by class name", mob.getClass().getSimpleName());
            return true;
        }

        return false;
    }

    // ── Reflection helper ─────────────────────────────────────────────────────

    /**
     * Attempts to find a no-arg method by name in the entity's class hierarchy.
     * Returns {@code null} (never throws) if not found.
     */
    private static Method findMethod(Object obj, String name) {
        try {
            return obj.getClass().getMethod(name);
        } catch (NoSuchMethodException ignored) {}
        // Walk declared methods as fallback (handles package-private / protected)
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Method m = clazz.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
