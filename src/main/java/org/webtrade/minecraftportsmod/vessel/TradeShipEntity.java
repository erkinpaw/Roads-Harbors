package org.webtrade.minecraftportsmod.vessel;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A village's trade ship as it is seen: a sloop or a brig under sail along her way over the sea, or lying moored at
 * her pier. Only her body: the voyage itself (where she is going, what she carries, how far she has got) is the
 * village's ({@link org.webtrade.minecraftportsmod.colony.Voyages}), which puts her into the world where a player is
 * near and takes her out again; under way she goes at the pace the voyage goes unseen, the current helping or
 * holding her. Never saved.
 */
public class TradeShipEntity extends Entity {

    private static final EntityDataAccessor<Integer> DATA_TIER = SynchedEntityData.defineId(TradeShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_SAILING = SynchedEntityData.defineId(TradeShipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_CARGO = SynchedEntityData.defineId(TradeShipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_STOCKS = SynchedEntityData.defineId(TradeShipEntity.class, EntityDataSerializers.BOOLEAN);
    /** The village whose ship she is (server side). */
    private int village = -1;

    private final InterpolationHandler interpolation = new InterpolationHandler(this, 3);

    /** Her way (x, z points) and how far along it she is, in blocks; null when moored. */
    private int[] path;
    private double at;
    private double sea;
    /** Ticks since the voyage last looked after her: a body nobody looks after any more is gone. */
    private int untended;
    /** Blocks a second she makes, by where she is and which way she goes (the voyage's pace). */
    private java.util.function.ToDoubleFunction<double[]> pace;

    public TradeShipEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TIER, 1);
        builder.define(DATA_SAILING, false);
        builder.define(DATA_CARGO, false);
        builder.define(DATA_STOCKS, false);
    }

    /** Still being built: a bare hull on the stocks, no masts rigged. */
    public boolean onStocks() {
        return entityData.get(DATA_STOCKS);
    }

    /** A ship being built at the pier of a village (shown as a bare hull; a player at her sees what she still needs). */
    public void stocks(int village, int tier, Vec3 pos, float yaw) {
        moor(tier, pos, yaw, false);
        this.village = village;
        entityData.set(DATA_STOCKS, true);
    }

    @Override
    public net.minecraft.world.InteractionResult interact(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
                                                         Vec3 location) {
        if (!onStocks()) return super.interact(player, hand, location);
        if (player instanceof net.minecraft.server.level.ServerPlayer sp && village >= 0) {
            org.webtrade.minecraftportsmod.colony.Harbour.openSite(sp, village);
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    /** 1: a sloop, 2: a brig. */
    public int tier() {
        return entityData.get(DATA_TIER);
    }

    public boolean sailing() {
        return entityData.get(DATA_SAILING);
    }

    public boolean laden() {
        return entityData.get(DATA_CARGO);
    }

    /** Under way along {@code path}, {@code at} blocks along it. */
    public void sail(int tier, int[] path, double at, double sea, boolean cargo, java.util.function.ToDoubleFunction<double[]> pace) {
        entityData.set(DATA_TIER, tier);
        entityData.set(DATA_SAILING, true);
        entityData.set(DATA_CARGO, cargo);
        this.path = path;
        this.at = at;
        this.sea = sea;
        this.pace = pace;
        untended = 0;
        double[] p = point(at), d = heading(at);
        setPos(p[0], sea - 0.45, p[1]);
        setYRot((float) (Mth.atan2(d[1], d[0]) * Mth.RAD_TO_DEG) - 90.0F);
    }

    /** Lying at her berth. */
    public void moor(int tier, Vec3 pos, float yaw, boolean cargo) {
        entityData.set(DATA_TIER, tier);
        entityData.set(DATA_SAILING, false);
        entityData.set(DATA_CARGO, cargo);
        path = null;
        untended = 0;
        if (position().distanceToSqr(pos) > 0.01) setPos(pos.x, pos.y, pos.z);
        setYRot(yaw);
    }

    /** The voyage still has her (else she is taken out of the world). */
    public void tend() {
        untended = 0;
    }

    /** How far along her way she is. */
    public double at() {
        return at;
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return interpolation;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            if (interpolation.hasActiveInterpolation()) interpolation.interpolate();
            return;
        }
        if (++untended > 60) {
            discard();
            return;
        }
        if (path == null) return;
        double[] here = point(at), d = heading(at);
        double perTick = (pace == null ? 4.0 : pace.applyAsDouble(new double[]{here[0], here[1], d[0], d[1]})) / 20.0;
        at = Math.min(length(path), at + perTick);
        double[] p = point(at), h = heading(at + 4);
        setPos(p[0], sea - 0.45, p[1]);
        // the bow comes round slowly to the way ahead
        float want = (float) (Mth.atan2(h[1], h[0]) * Mth.RAD_TO_DEG) - 90.0F;
        setYRot(getYRot() + Mth.clamp(Mth.wrapDegrees(want - getYRot()), -2.5F, 2.5F));
    }

    // ------------------------------------------------------------------ the way

    public static double length(int[] p) {
        double len = 0;
        for (int i = 2; i + 1 < p.length; i += 2) len += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
        return len;
    }

    /** The point {@code d} blocks along her way. */
    private double[] point(double d) {
        return point(path, d);
    }

    public static double[] point(int[] p, double d) {
        for (int i = 2; i + 1 < p.length; i += 2) {
            double s = Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
            if (d <= s) {
                double f = s == 0 ? 0 : d / s;
                return new double[]{p[i - 2] + 0.5 + (p[i] - p[i - 2]) * f, p[i - 1] + 0.5 + (p[i + 1] - p[i - 1]) * f};
            }
            d -= s;
        }
        return new double[]{p[p.length - 2] + 0.5, p[p.length - 1] + 0.5};
    }

    /** Which way the stretch of her way {@code d} blocks along runs (a unit vector, x and z). */
    private double[] heading(double d) {
        return heading(path, d);
    }

    public static double[] heading(int[] p, double d) {
        for (int i = 2; i + 1 < p.length; i += 2) {
            double s = Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
            if (d <= s || i + 3 >= p.length) {
                if (s == 0) continue;
                return new double[]{(p[i] - p[i - 2]) / s, (p[i + 1] - p[i - 1]) / s};
            }
            d -= s;
        }
        return new double[]{1, 0};
    }

    // ------------------------------------------------------------------ not to be pushed, broken or saved

    @Override
    public boolean isPickable() {
        return onStocks();
    }

    /** A passenger or two on deck (players who paid for the passage). */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 2;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        // on deck, abaft the mast
        int i = Math.max(0, getPassengers().indexOf(passenger));
        return new Vec3(i == 0 ? 0.4 : -0.4, 1.15, -1.2).yRot(-getYRot() * Mth.DEG_TO_RAD);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void push(Vec3 impulse) {
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean save(ValueOutput output) {
        return false;
    }

    @Override
    public boolean saveAsPassenger(ValueOutput output) {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256 * 256;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
