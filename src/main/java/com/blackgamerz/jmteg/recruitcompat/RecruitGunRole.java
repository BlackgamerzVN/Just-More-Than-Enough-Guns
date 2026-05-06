package com.blackgamerz.jmteg.recruitcompat;

/**
 * Recruit gun loadout roles used by the Bowman/CrossBowman balancing system.
 *
 * Role tiers (ascending):
 *   SIDEARM < BASIC_RANGED < TACTICAL_RANGED < HEAVY
 *   UTILITY  is a sideways / support role usable by either recruit class.
 *
 * Bowman can use SIDEARM, BASIC_RANGED, UTILITY (and optionally TACTICAL_RANGED at reduced weight).
 * CrossBowman can use all roles, with full preference for TACTICAL_RANGED and HEAVY.
 *
 * The gun pools assigned to each role are driven by config/jmteg/recruit_roles.json, so
 * pack authors can re-assign any JEG (or modded) gun to any role without touching Java.
 */
public enum RecruitGunRole {

    /** Sidearms: pistols, revolvers. */
    SIDEARM,

    /** Standard rifles, light shotguns — bread-and-butter infantry weapons. */
    BASIC_RANGED,

    /** Assault rifles, SMGs, combat shotguns — specialist / intermediate tier. */
    TACTICAL_RANGED,

    /** Rocket/grenade launchers, miniguns, cannons — elite / expensive weapons. */
    HEAVY,

    /** Support / utility weapons: bows, flares, flamethrowers, special-purpose guns. */
    UTILITY;
}
