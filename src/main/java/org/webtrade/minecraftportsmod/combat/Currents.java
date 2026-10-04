package org.webtrade.minecraftportsmod.combat;

import net.minecraft.util.Mth;

/**
 * The sea's currents: a slow, smooth flow over the whole sea, its way and strength changing from one stretch of water
 * to the next (the same in every world, so the server and every client know it alike). A ship under way is carried
 * by it: faster with it, slower against it, set sideways across it.
 */
public final class Currents {

    private Currents() {
    }

    /** The weakest and the strongest current (blocks a tick). */
    public static final double MIN = 0.02, MAX = 0.07;

    /** The current at a place: {x, z} (blocks a tick). */
    public static double[] at(double x, double z) {
        // the way of it: a few broad waves of direction laid over each other (gyres some hundreds of blocks across)
        double a = Math.sin(x * 0.0041 + 1.3) * 1.7 + Math.cos(z * 0.0033 - 0.4) * 1.9 + Math.sin((x + z) * 0.0019) * 1.2
                + Math.cos((x - z) * 0.0027 + 2.1) * 0.8;
        // the strength: stronger in bands, slack between them
        double s = 0.5 + 0.5 * Math.sin(x * 0.0023 - z * 0.0017 + Math.cos(z * 0.0011) * 2.0);
        double v = MIN + (MAX - MIN) * s;
        return new double[]{Math.cos(a) * v, Math.sin(a) * v};
    }

    /** Its strength (0 slack .. 1 the strongest). */
    public static double strength(double x, double z) {
        double[] c = at(x, z);
        return (Mth.length(c[0], c[1]) - MIN) / (MAX - MIN);
    }
}
