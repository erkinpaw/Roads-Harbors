package org.webtrade.minecraftportsmod.colony;

import net.minecraft.world.level.pathfinder.PathType;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * A resident's day: what part of it they are in (at work, at rest, asleep, away on the road) decides what they may
 * do and how they get about. Every goal asks this, not the clock: one place sets the hours (and, later, the shifts
 * of the trades that keep other hours, an alarm, a hired hand's orders).
 */
public final class Routine {

    public enum Mode {
        /** At their trade, on the building sites, tending the land. */
        WORK(0.6, 40F),
        /** By the door, at the fire, children at play: unhurried. */
        REST(0.45, 40F),
        /** To bed, and in it. */
        SLEEP(0.55, 40F),
        /** Out of the village: a merchant on the trail, a road crew, a scout leaving. */
        ROAD(0.6, 40F);

        /** Walking speed; how much a step through water costs the path-finding (never across a lake, unless no other way). */
        public final double speed;
        final float water;

        Mode(double speed, float water) {
            this.speed = speed;
            this.water = water;
        }
    }

    /** The hours: work from morning to late afternoon, sleep from dusk to dawn. */
    static final int WORK_FROM = 1000, WORK_TO = 11500, SLEEP_FROM = 12500, SLEEP_TO = 23450;
    /** The afternoon: the building sites' time. */
    static final int AFTERNOON = 6000;

    private Routine() {
    }

    /** The part of the day a resident of a village is in now. */
    public static Mode mode(ResidentEntity r) {
        if (r.colony() && r.level() instanceof net.minecraft.server.level.ServerLevel level) {
            Village v = VillageData.get(level.getServer()).get(r.colonyVillage());
            Dweller d = v == null ? null : v.dweller(r.colonyDweller());
            if (d != null && d.away) return Mode.ROAD;
        }
        return mode(r.dayTime(), r.colonyJob());
    }

    /** By the clock alone (and the trade: room for trades keeping other hours). */
    static Mode mode(int dayTime, Job job) {
        if (dayTime >= SLEEP_FROM && dayTime < SLEEP_TO) return Mode.SLEEP;
        if (job != null && dayTime >= WORK_FROM && dayTime < WORK_TO) return Mode.WORK;
        return Mode.REST;
    }

    static boolean working(ResidentEntity r) {
        return mode(r) == Mode.WORK;
    }

    static boolean asleep(ResidentEntity r) {
        return mode(r) == Mode.SLEEP;
    }

    static boolean afternoon(ResidentEntity r) {
        return working(r) && r.dayTime() >= AFTERNOON;
    }

    /** Sets how the resident finds its way for the part of the day it is in (called each second or so). */
    public static void apply(ResidentEntity r) {
        Mode m = mode(r);
        if (r.routine == m) return;
        r.routine = m;
        r.setPathfindingMalus(PathType.WATER, m.water);
        r.setPathfindingMalus(PathType.WATER_BORDER, 4F);
    }
}
