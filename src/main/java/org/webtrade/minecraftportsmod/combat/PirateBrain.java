package org.webtrade.minecraftportsmod.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * How a pirate sails: she looks for a ship worth taking (one with a captain aboard), closes in under full sail,
 * turns her side to it at gun range and fires as her guns bear, keeping her distance; with nobody about she cruises
 * slowly, and goes off over the horizon in the end.
 */
final class PirateBrain {

    /** The range she fights at, and beyond which she closes in under full sail (blocks). */
    static final double FIGHT_NEAR = 14, FIGHT_FAR = 30, SEEK = 140;

    private final WarshipEntity ship;
    private WarshipEntity prey;
    /** Her heading while cruising, and ticks until she picks another. */
    private float cruise;
    private int cruiseFor;
    /** Ticks with nobody to fight anywhere near: after a while she sails away (is gone). */
    private int idle;

    PirateBrain(WarshipEntity ship) {
        this.ship = ship;
        this.cruise = ship.getYRot();
    }

    void attackedBy(WarshipEntity enemy) {
        if (!enemy.isPirate()) prey = enemy;
    }

    void tick(ServerLevel level) {
        if (prey == null || !prey.isAlive() || prey.sinking() > 0 || prey.distanceTo(ship) > SEEK * 1.3) prey = null;
        if (prey == null && ship.tickCount % 20 == 0) {
            double best = SEEK * SEEK;
            for (WarshipEntity s : level.getEntitiesOfClass(WarshipEntity.class, ship.getBoundingBox().inflate(SEEK))) {
                if (s.isPirate() || s.sinking() > 0 || s.captain() == null) continue;
                double d = s.distanceToSqr(ship);
                if (d < best) {
                    best = d;
                    prey = s;
                }
            }
        }
        if (prey == null) {
            cruise(level);
            return;
        }
        idle = 0;
        Vec3 to = prey.position().subtract(ship.position());
        double d = Math.sqrt(to.x * to.x + to.z * to.z);
        float bearing = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
        float want;
        if (d > FIGHT_FAR) {
            // close in: straight at her, under full sail
            want = bearing;
            ship.setSails(3);
        } else if (d < FIGHT_NEAR) {
            // too near (her guns would rake us at point blank): bear away
            want = bearing + 180;
            ship.setSails(3);
        } else {
            // at range: her side to the prey, the nearer of the two ways round, under half sail
            float a = Mth.wrapDegrees(bearing + 90 - ship.getYRot()), b = Mth.wrapDegrees(bearing - 90 - ship.getYRot());
            want = Math.abs(a) < Math.abs(b) ? bearing + 90 : bearing - 90;
            // (edging in or out to stay in the middle of the range)
            double mid = (FIGHT_NEAR + FIGHT_FAR) / 2;
            float edge = (float) Mth.clamp((d - mid) * 2.5, -25, 25);
            want += Mth.wrapDegrees(bearing - want) > 0 ? edge : -edge;
            ship.setSails(2);
        }
        steer(want);
        // the guns: fire the side the prey is on, when it bears (within a few points of the beam) and is in range
        float rel = Mth.wrapDegrees(bearing - ship.getYRot());
        int side = rel > 0 ? 1 : -1;
        float offBeam = Math.abs(Math.abs(rel) - 90);
        if (d < FIGHT_FAR + 8 && offBeam < 18 && ship.reload(side) == 0 && ship.getRandom().nextInt(6) == 0) {
            float aim = WarshipEntity.elevationFor(d) + (ship.getRandom().nextFloat() - 0.5F) * 3F;
            ship.fire(side, Math.max(0, aim));
        }
    }

    private void steer(float want) {
        float delta = Mth.wrapDegrees(want - ship.getYRot());
        ship.setRudder(Math.abs(delta) < 4 ? 0 : delta > 0 ? 1 : -1);
    }

    private void cruise(ServerLevel level) {
        ship.setSails(1);
        if (--cruiseFor <= 0) {
            cruise = ship.getYRot() + (ship.getRandom().nextFloat() - 0.5F) * 120;
            cruiseFor = 200 + ship.getRandom().nextInt(400);
        }
        // (land ahead: come about)
        if (ship.horizontalCollision) cruise = ship.getYRot() + 150;
        steer(cruise);
        // nobody to fight and no player near: after a minute she is over the horizon
        if (++idle > 1200 && level.getNearestPlayer(ship, 96) == null) ship.discard();
    }
}
