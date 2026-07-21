package com.blackgamerz.jmteg.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized reflection cache for JEG and Recruits helper lookups.
 * Other classes call these getters/helpers instead of repeatedly doing
 * {@code Class.forName}/{@code getMethod} with hardcoded, duplicated strings.
 *
 * <p>Phase-2 additions cache the Gun inner-class methods and GunEventBus so that
 * {@link com.blackgamerz.jmteg.jegcompat.ReflectiveJEGCompat} can implement all
 * {@link com.blackgamerz.jmteg.jegcompat.IJEGCompat} primitives without any hard
 * compile-time dependency on JEG classes.</p>
 *
 * <p>Reflection-consolidation additions centralize every hardcoded Recruits/JEG
 * fully-qualified class name and reflective method name that was previously
 * scattered (and duplicated) across {@code recruitcompat}/{@code jegcompat}
 * classes, and back {@link #findMethod(Class, String, Class[])} with a real
 * per-(class, method name, parameter types) memoized cache — including negative
 * (not-found) results — instead of the ad-hoc, per-class-only caches that used
 * to live in individual callers.</p>
 */
public final class ReflectionCache {
    private static final Logger LOGGER = LogManager.getLogger("JMT-ReflectionCache");

    private ReflectionCache() {}

    // ── Hardcoded FQCNs, centralized ───────────────────────────────────────────
    // Recruits (Talhanation) classes
    public static final String RECRUIT_ENTITY_CLASS_NAME = "com.talhanation.recruits.entities.RecruitEntity";
    public static final String RECRUIT_SIMPLE_CONTAINER_CLASS_NAME = "com.talhanation.recruits.inventory.RecruitSimpleContainer";
    public static final String RECRUIT_INVENTORY_CLASS_NAME = "com.talhanation.recruits.entities.inventory.RecruitInventory";
    public static final String RECRUIT_MUSKET_ATTACK_GOAL_CLASS_NAME = "com.talhanation.recruits.entities.ai.compat.RecruitRangedMusketAttackGoal";
    public static final String RECRUIT_CROSSBOW_ATTACK_GOAL_CLASS_NAME = "com.talhanation.recruits.entities.ai.RecruitRangedCrossbowAttackGoal";

    // Just Enough Guns classes
    public static final String JEG_GUN_ATTACK_GOAL_CLASS_NAME = "ttv.migami.jeg.entity.ai.GunAttackGoal";
    public static final String JEG_AI_GUN_EVENT_CLASS_NAME = "ttv.migami.jeg.entity.ai.AIGunEvent";
    public static final String JEG_MOB_AMMO_HELPER_CLASS_NAME = "ttv.migami.jeg.common.MobAmmoHelper";
    public static final String JEG_AI_TYPE_CLASS_NAME = "ttv.migami.jeg.entity.ai.AIType";

    // Reflective method names shared across compat classes
    public static final String RECRUIT_METHOD_IS_EFFECTED_BY_COMMAND = "isEffectedByCommand";
    public static final String RECRUIT_METHOD_GET_FOLLOW_STATE = "getFollowState";
    public static final String RECRUIT_METHOD_GET_IS_IN_ORDER = "getIsInOrder";
    public static final String METHOD_GET_INVENTORY = "getInventory";
    public static final String METHOD_GET_CONTAINER_SIZE = "getContainerSize";
    public static final String METHOD_GET_ITEM = "getItem";
    public static final String METHOD_SET_ITEM = "setItem";
    public static final String METHOD_SET_CHANGED = "setChanged";

    // ── Top-level JEG classes ─────────────────────────────────────────────────
    private static volatile Class<?> jegGunItemClass;
    private static volatile Class<?> jegCommonGunClass;

    // ── Gun inner/static-nested class handles ────────────────────────────────
    private static volatile Class<?> jegGunGeneralClass;
    private static volatile Class<?> jegGunReloadsClass;
    private static volatile Class<?> jegGunSoundsClass;
    private static volatile Class<?> jegGunProjectileClass;

    // ── GunEventBus class ─────────────────────────────────────────────────────
    private static volatile Class<?> jegGunEventBusClass;

    // ── Additional soft-dependency classes (lazily resolved, cached) ──────────
    private static volatile Class<?> recruitEntityClass;
    private static volatile boolean recruitEntityClassResolved;
    private static volatile Class<?> recruitSimpleContainerClass;
    private static volatile boolean recruitSimpleContainerClassResolved;
    private static volatile Class<?> recruitInventoryClass;
    private static volatile boolean recruitInventoryClassResolved;
    private static volatile Class<?> jegGunAttackGoalClass;
    private static volatile boolean jegGunAttackGoalClassResolved;
    private static volatile Class<?> jegAiGunEventClass;
    private static volatile boolean jegAiGunEventClassResolved;
    private static volatile Class<?> jegMobAmmoHelperClass;
    private static volatile boolean jegMobAmmoHelperClassResolved;
    private static volatile Class<?> jegAiTypeClass;
    private static volatile boolean jegAiTypeClassResolved;

    // ── GunItem instance methods ──────────────────────────────────────────────
    private static volatile Method jeg_getModifiedGun;

    // ── Gun ammo/pool helpers ─────────────────────────────────────────────────
    private static volatile Method jeg_findAmmoStack;
    private static volatile Method jeg_shrinkFromAmmoPool_1;
    private static volatile Method jeg_shrinkFromAmmoPool_2;

    // ── Gun → sub-object accessors ────────────────────────────────────────────
    private static volatile Method jeg_gun_getGeneral;
    private static volatile Method jeg_gun_getReloads;
    private static volatile Method jeg_gun_getSounds;
    private static volatile Method jeg_gun_getProjectile;

    // ── Gun.General methods ───────────────────────────────────────────────────
    /** @return int – fire rate (ticks between shots) */
    private static volatile Method jeg_general_getRate;

    // ── Gun.Reloads methods ───────────────────────────────────────────────────
    /** @return int – reload duration in ticks */
    private static volatile Method jeg_reloads_getReloadTimer;
    /** @return int – max magazine / tube capacity */
    private static volatile Method jeg_reloads_getMaxAmmo;

    // ── Gun.Sounds methods ────────────────────────────────────────────────────
    /** @return ResourceLocation (nullable) – sound event id for the fire sound */
    private static volatile Method jeg_sounds_getFire;

    // ── Gun.Projectile methods ────────────────────────────────────────────────
    /** @return double – base projectile speed (blocks per tick) */
    private static volatile Method jeg_projectile_getSpeed;
    /** @return boolean – whether gravity is applied to the projectile */
    private static volatile Method jeg_projectile_isGravity;

    // ── GunEventBus static methods ────────────────────────────────────────────
    /** static void ejectCasing(Level, LivingEntity) */
    private static volatile Method jeg_gunEventBus_ejectCasing;

    // ── AIGunEvent static methods ─────────────────────────────────────────────
    /** static void performGunAttack(Mob, LivingEntity, ItemStack, Gun, float, boolean) */
    private static volatile Method jeg_aiGunEvent_performGunAttack;

    static {
        // ── Load top-level classes ─────────────────────────────────────────
        try { jegGunItemClass    = Class.forName("ttv.migami.jeg.item.GunItem");   } catch (Throwable ignored) {}
        try { jegCommonGunClass  = Class.forName("ttv.migami.jeg.common.Gun");     } catch (Throwable ignored) {}

        // ── Load Gun inner classes (static-nested, so '$' separator) ───────
        try { jegGunGeneralClass   = Class.forName("ttv.migami.jeg.common.Gun$General");   } catch (Throwable ignored) {}
        try { jegGunReloadsClass   = Class.forName("ttv.migami.jeg.common.Gun$Reloads");   } catch (Throwable ignored) {}
        try { jegGunSoundsClass    = Class.forName("ttv.migami.jeg.common.Gun$Sounds");    } catch (Throwable ignored) {}
        try { jegGunProjectileClass = Class.forName("ttv.migami.jeg.common.Gun$Projectile"); } catch (Throwable ignored) {}

        try { jegGunEventBusClass  = Class.forName("ttv.migami.jeg.event.GunEventBus");   } catch (Throwable ignored) {}

        // ── GunItem methods ────────────────────────────────────────────────
        if (jegGunItemClass != null) {
            try { jeg_getModifiedGun = jegGunItemClass.getMethod("getModifiedGun", ItemStack.class); } catch (Throwable ignored) {}
        }

        // ── Gun ammo helpers ───────────────────────────────────────────────
        if (jegCommonGunClass != null) {
            try { jeg_findAmmoStack = jegCommonGunClass.getMethod("findAmmoStack", ItemStack.class); } catch (Throwable ignored) {}
            try { jeg_shrinkFromAmmoPool_1 = jegCommonGunClass.getMethod("shrinkFromAmmoPool", ItemStack.class, int.class); } catch (Throwable ignored) {}
            try { jeg_shrinkFromAmmoPool_2 = jegCommonGunClass.getMethod("shrinkFromAmmoPool", net.minecraft.world.entity.PathfinderMob.class, ItemStack.class, int.class); } catch (Throwable ignored) {}

            // Gun sub-object getters
            try { jeg_gun_getGeneral    = jegCommonGunClass.getMethod("getGeneral");    } catch (Throwable ignored) {}
            try { jeg_gun_getReloads    = jegCommonGunClass.getMethod("getReloads");    } catch (Throwable ignored) {}
            try { jeg_gun_getSounds     = jegCommonGunClass.getMethod("getSounds");     } catch (Throwable ignored) {}
            try { jeg_gun_getProjectile = jegCommonGunClass.getMethod("getProjectile"); } catch (Throwable ignored) {}
        }

        // ── Gun.General methods ────────────────────────────────────────────
        if (jegGunGeneralClass != null) {
            try { jeg_general_getRate = jegGunGeneralClass.getMethod("getRate"); } catch (Throwable ignored) {}
        }

        // ── Gun.Reloads methods ────────────────────────────────────────────
        if (jegGunReloadsClass != null) {
            try { jeg_reloads_getReloadTimer = jegGunReloadsClass.getMethod("getReloadTimer"); } catch (Throwable ignored) {}
            try { jeg_reloads_getMaxAmmo     = jegGunReloadsClass.getMethod("getMaxAmmo");     } catch (Throwable ignored) {}
        }

        // ── Gun.Sounds methods ─────────────────────────────────────────────
        if (jegGunSoundsClass != null) {
            try { jeg_sounds_getFire = jegGunSoundsClass.getMethod("getFire"); } catch (Throwable ignored) {}
        }

        // ── Gun.Projectile methods ─────────────────────────────────────────
        if (jegGunProjectileClass != null) {
            try { jeg_projectile_getSpeed   = jegGunProjectileClass.getMethod("getSpeed");   } catch (Throwable ignored) {}
            try { jeg_projectile_isGravity  = jegGunProjectileClass.getMethod("isGravity");  } catch (Throwable ignored) {}
        }

        // ── GunEventBus static methods ─────────────────────────────────────
        if (jegGunEventBusClass != null) {
            try { jeg_gunEventBus_ejectCasing = jegGunEventBusClass.getMethod("ejectCasing", Level.class, LivingEntity.class); } catch (Throwable ignored) {}
        }

        // ── AIGunEvent static methods ───────────────────────────────────────
        if (jegCommonGunClass != null) {
            try {
                Class<?> aiGunEventClass = Class.forName(JEG_AI_GUN_EVENT_CLASS_NAME);
                jegAiGunEventClass = aiGunEventClass;
                jegAiGunEventClassResolved = true;
                jeg_aiGunEvent_performGunAttack = aiGunEventClass.getDeclaredMethod("performGunAttack",
                        net.minecraft.world.entity.Mob.class, LivingEntity.class, ItemStack.class,
                        jegCommonGunClass, float.class, boolean.class);
            } catch (Throwable ignored) {}
        }
    }

    // ── Public getters ────────────────────────────────────────────────────────

    public static Class<?> getJegGunItemClass() { return jegGunItemClass; }
    public static Class<?> getJegCommonGunClass() { return jegCommonGunClass; }
    public static Method getJeg_aiGunEvent_performGunAttack() { return jeg_aiGunEvent_performGunAttack; }
    public static Method getJeg_getModifiedGun() { return jeg_getModifiedGun; }
    public static Method getJeg_findAmmoStack() { return jeg_findAmmoStack; }
    public static Method getJeg_shrinkFromAmmoPool_1() { return jeg_shrinkFromAmmoPool_1; }
    public static Method getJeg_shrinkFromAmmoPool_2() { return jeg_shrinkFromAmmoPool_2; }

    public static Method getJeg_gun_getGeneral()    { return jeg_gun_getGeneral; }
    public static Method getJeg_gun_getReloads()    { return jeg_gun_getReloads; }
    public static Method getJeg_gun_getSounds()     { return jeg_gun_getSounds; }
    public static Method getJeg_gun_getProjectile() { return jeg_gun_getProjectile; }

    public static Method getJeg_general_getRate()          { return jeg_general_getRate; }
    public static Method getJeg_reloads_getReloadTimer()   { return jeg_reloads_getReloadTimer; }
    public static Method getJeg_reloads_getMaxAmmo()       { return jeg_reloads_getMaxAmmo; }
    public static Method getJeg_sounds_getFire()           { return jeg_sounds_getFire; }
    public static Method getJeg_projectile_getSpeed()      { return jeg_projectile_getSpeed; }
    public static Method getJeg_projectile_isGravity()     { return jeg_projectile_isGravity; }
    public static Method getJeg_gunEventBus_ejectCasing()  { return jeg_gunEventBus_ejectCasing; }

    // ── Lazily-resolved soft-dependency classes (resolved once, then cached) ──

    public static Class<?> getRecruitEntityClass() {
        if (!recruitEntityClassResolved) {
            recruitEntityClass = tryForName(RECRUIT_ENTITY_CLASS_NAME);
            recruitEntityClassResolved = true;
        }
        return recruitEntityClass;
    }

    public static Class<?> getRecruitSimpleContainerClass() {
        if (!recruitSimpleContainerClassResolved) {
            recruitSimpleContainerClass = tryForName(RECRUIT_SIMPLE_CONTAINER_CLASS_NAME);
            recruitSimpleContainerClassResolved = true;
        }
        return recruitSimpleContainerClass;
    }

    public static Class<?> getRecruitInventoryClass() {
        if (!recruitInventoryClassResolved) {
            recruitInventoryClass = tryForName(RECRUIT_INVENTORY_CLASS_NAME);
            recruitInventoryClassResolved = true;
        }
        return recruitInventoryClass;
    }

    public static Class<?> getJegGunAttackGoalClass() {
        if (!jegGunAttackGoalClassResolved) {
            jegGunAttackGoalClass = tryForName(JEG_GUN_ATTACK_GOAL_CLASS_NAME);
            jegGunAttackGoalClassResolved = true;
        }
        return jegGunAttackGoalClass;
    }

    public static Class<?> getJegAiGunEventClass() {
        if (!jegAiGunEventClassResolved) {
            jegAiGunEventClass = tryForName(JEG_AI_GUN_EVENT_CLASS_NAME);
            jegAiGunEventClassResolved = true;
        }
        return jegAiGunEventClass;
    }

    public static Class<?> getJegMobAmmoHelperClass() {
        if (!jegMobAmmoHelperClassResolved) {
            jegMobAmmoHelperClass = tryForName(JEG_MOB_AMMO_HELPER_CLASS_NAME);
            jegMobAmmoHelperClassResolved = true;
        }
        return jegMobAmmoHelperClass;
    }

    public static Class<?> getJegAiTypeClass() {
        if (!jegAiTypeClassResolved) {
            jegAiTypeClass = tryForName(JEG_AI_TYPE_CLASS_NAME);
            jegAiTypeClassResolved = true;
        }
        return jegAiTypeClass;
    }

    /** Returns {@code true} when {@code className} matches one of the recruit ranged attack goal FQCNs. */
    public static boolean isRecruitRangedAttackGoalClassName(String className) {
        return RECRUIT_MUSKET_ATTACK_GOAL_CLASS_NAME.equals(className)
                || RECRUIT_CROSSBOW_ATTACK_GOAL_CLASS_NAME.equals(className);
    }

    private static Class<?> tryForName(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    // Generic helpers for inventory reflection used by compat classes
    public static Object tryGetInventoryObject(net.minecraft.world.entity.PathfinderMob mob) {
        if (mob == null) return null;
        try {
            Method m = findMethod(mob.getClass(), METHOD_GET_INVENTORY);
            if (m == null) return null;
            return m.invoke(mob);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object tryExtractItemsObjectFromInventory(Object inv) {
        if (inv == null) return null;
        try {
            Field f = inv.getClass().getField("items");
            return f.get(inv);
        } catch (Throwable t) {
            try {
                Method getItems = inv.getClass().getMethod("getItems");
                return getItems.invoke(inv);
            } catch (Throwable tt) {
                return null;
            }
        }
    }

    public static void tryWriteBackInventoryItem(Object inv, int index, net.minecraft.world.item.ItemStack stack) {
        if (inv == null) return;
        Method setItem = findMethod(inv.getClass(), METHOD_SET_ITEM, int.class, net.minecraft.world.item.ItemStack.class);
        if (setItem != null) {
            try { setItem.invoke(inv, index, stack); return; } catch (Throwable ignored) {}
        }
        try {
            Method setStack = inv.getClass().getMethod("setStack", int.class, net.minecraft.world.item.ItemStack.class);
            setStack.invoke(inv, index, stack);
        } catch (Throwable ignored) {}
    }

    // ── Memoized method lookup ─────────────────────────────────────────────────

    /**
     * Cache key uniquely identifying a (owning class, method name, parameter types)
     * lookup. Replaces the assorted per-class-only or unmemoized {@code findMethod}
     * helpers that used to be duplicated across {@code RecruitOwnershipHelper},
     * {@code RecruitDoctrineHolder}, and {@code RecruitMovementDoctrineIntegrator}
     * (the latter of which keyed its cache by {@code Class<?>} alone, so looking up
     * more than one method name per class silently thrashed the cache).
     */
    private static final class MethodKey {
        final Class<?> owner;
        final String name;
        final Class<?>[] params;
        private final int hash;

        MethodKey(Class<?> owner, String name, Class<?>[] params) {
            this.owner = owner;
            this.name = name;
            this.params = params;
            int h = owner.hashCode() * 31 + name.hashCode();
            for (Class<?> p : params) h = h * 31 + (p == null ? 0 : p.hashCode());
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodKey other)) return false;
            return owner.equals(other.owner) && name.equals(other.name) && java.util.Arrays.equals(params, other.params);
        }

        @Override
        public int hashCode() { return hash; }
    }

    // Optional.empty() memoizes a confirmed "not found" so repeated lookups for
    // methods that don't exist on a class (e.g. probing several candidate mod
    // APIs) don't re-walk the class hierarchy every call.
    private static final Map<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    /**
     * Finds a method by name/parameter-types on {@code clazz} or any of its
     * superclasses, trying public methods first and falling back to
     * {@code getDeclaredMethod} (with {@code setAccessible(true)}) to also reach
     * protected/package-private methods on soft-dependency mod classes.
     *
     * <p>Results (including "not found") are memoized per (class, name, parameter
     * types), so repeated calls — e.g. once per tick per entity — are O(1) map
     * lookups instead of repeated reflective class-hierarchy walks.</p>
     *
     * @return the resolved, accessible {@link Method}, or {@code null} if none was found
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        if (clazz == null || name == null) return null;
        MethodKey key = new MethodKey(clazz, name, params);
        Optional<Method> cached = METHOD_CACHE.get(key);
        if (cached != null) return cached.orElse(null);

        Method resolved = resolveMethod(clazz, name, params);
        METHOD_CACHE.put(key, Optional.ofNullable(resolved));
        return resolved;
    }

    private static Method resolveMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (Throwable ignored) {}

        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }
}