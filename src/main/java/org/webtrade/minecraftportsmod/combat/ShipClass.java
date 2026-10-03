package org.webtrade.minecraftportsmod.combat;

/**
 * The warships there are: a brig, a galleon, a ship of the line. Each is drawn from its own model (made by
 * {@code tools/ships/gen_ships.py}, in units of 1/32 block, drawn {@link #SCALE} times as big), and everything about
 * where things stand on her here is measured off that model: her guns' ports, her decks, her length and beam.
 */
public enum ShipClass {
    //    model               hull reload  speeds (furled, reefed, half, full)     turn  lift   beam  middle half  deck castle  guns' rows (y, units)  ports along (z, units)
    BRIG("brig", 100, 70, new double[]{0, 0.1, 0.2, 0.32}, 1.0, 0.9, 2.25, 1.1, 6.8, 2, -28, new int[]{3},
            new int[]{-50, -18, 14, 46}, -76),
    GALLEON("galleon", 160, 90, new double[]{0, 0.09, 0.17, 0.27}, 0.8, 1.0, 2.6, 0.82, 7.7, 2, -50, new int[]{3},
            new int[]{-64, -32, 0, 32, 64}, -86),
    LINE("ship_of_the_line", 260, 110, new double[]{0, 0.08, 0.15, 0.24}, 0.6, 1.9, 2.95, 0.82, 9.6, 2, -26, new int[]{3, 19},
            new int[]{-112, -80, -48, -16, 16, 48, 80}, -104);

    /** How much bigger than the fleet's ships the warships are drawn; one model unit in blocks then. */
    public static final float SCALE = 1.8F;
    public static final double UNIT = 0.5 / 16 * SCALE;
    /** The model's origin over the entity's: a boat's, and the ship's own lift (her deck clear of the water). */
    static final double BOAT = 0.375;

    public final String model;
    public final float hull;
    public final int reload;
    public final double[] speeds;
    /** How readily she answers the helm (1: a brig). */
    public final double turn;
    /** How far her model is drawn over a boat's (blocks): her deck clear of the water, her lowest ports too. */
    public final float lift;
    /** Half her beam, where the middle of her hull is (forward of the origin), half her length: blocks. */
    public final double halfBeam, middle, halfLength;
    /** Her main deck's and her highest deck's (the captain's) y in model units (down is +). */
    private final int deckY, castleY;
    /** Her rows of guns (the ports' middle y, model units) and the ports along her side (z, model units). */
    private final int[] rows, ports;
    /** Where her wheel is (z, model units): the captain stands there. */
    private final int wheelZ;

    ShipClass(String model, float hull, int reload, double[] speeds, double turn, double lift, double halfBeam, double middle,
              double halfLength, int deckY, int castleY, int[] rows, int[] ports, int wheelZ) {
        this.model = model;
        this.hull = hull;
        this.reload = reload;
        this.speeds = speeds;
        this.turn = turn;
        this.lift = (float) lift;
        this.halfBeam = halfBeam;
        this.middle = middle;
        this.halfLength = halfLength;
        this.deckY = deckY;
        this.castleY = castleY;
        this.rows = rows;
        this.ports = ports;
        this.wheelZ = wheelZ;
    }

    /** A height on her (model y) over the entity's origin, in blocks. */
    public double height(int modelY) {
        return BOAT + lift - modelY * UNIT;
    }

    public double deck() {
        return height(deckY);
    }

    public double castle() {
        return height(castleY);
    }

    /** The captain's place along her (blocks forward of the origin): by the wheel. */
    public double wheel() {
        return wheelZ * UNIT + 0.6;
    }

    /** Guns along one side (in all her rows). */
    public int guns() {
        return rows.length * ports.length;
    }

    /** Where gun {@code g} of a side stands: {along, height} in blocks over the origin. */
    public double[] gun(int g) {
        int row = g / ports.length, i = g % ports.length;
        return new double[]{ports[i] * UNIT, height(rows[row])};
    }

    /** How far out of the hull her guns' muzzles are (the barrels stick out of the ports). */
    public double muzzle() {
        return halfBeam + 0.6;
    }
}
