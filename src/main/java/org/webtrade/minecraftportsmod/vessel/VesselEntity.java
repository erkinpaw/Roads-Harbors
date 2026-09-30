package org.webtrade.minecraftportsmod.vessel;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.fleet.VesselType;
import org.webtrade.minecraftportsmod.port.Dock;
import org.webtrade.minecraftportsmod.port.PortData;

import java.util.function.Supplier;

/**
 * The in-world body of a fleet vessel ({@link VesselRecord}). It is never saved with the chunk: the fleet
 * manager spawns it when the vessel's surroundings are simulated and removes it when they are not.
 * <p>
 * A vessel is never steered by hand: it reports no controlling passenger, so the server is always authoritative
 * over its movement (clients only interpolate) and it only ever moves along voyages. It can't be pushed, dragged on a
 * lead or broken by hitting it either — vessels are removed at a port office ("dismantle"), which returns the boat.
 */
public class VesselEntity extends Boat {

    private static final float MAX_TURN_PER_TICK = 7.0F;
    private static final double WAYPOINT_RADIUS = 2.2;
    private static final double BERTH_RADIUS = 0.6;
    /** Water friction boats get every tick; velocity is pre-scaled so the effective speed is exact. */
    private static final double WATER_FRICTION = 0.9;

    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT =
            SynchedEntityData.defineId(VesselEntity.class, EntityDataSerializers.BOOLEAN);
    /** Hull class (see {@link VesselType}), for the client to pick the model. */
    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(VesselEntity.class, EntityDataSerializers.INT);
    /** A settlement's trade vessel carrying goods: barrels and crates are drawn on deck. */
    private static final EntityDataAccessor<Boolean> DATA_CARGO =
            SynchedEntityData.defineId(VesselEntity.class, EntityDataSerializers.BOOLEAN);
    /** Id of the boat item the vessel was built from, for the client to pick the wood of small boats. */
    private static final EntityDataAccessor<String> DATA_HULL =
            SynchedEntityData.defineId(VesselEntity.class, EntityDataSerializers.STRING);

    /**
     * Seats on deck per hull class as {sideways, along} pairs in blocks (+along is the bow). They sit to either side
     * of the centre line so nobody ends up inside a sail, and clear of the square sails' planes on the brig.
     */
    private static final double[][][] SEATS = {
            {},
            {{0.55, 1.1}, {-0.55, 1.1}, {0.55, -0.9}, {-0.55, -0.9}},
            // six rows of two, clear of the two square-sail planes (z = -0.5 and 1.6)
            {{0.65, 0.9}, {-0.65, 0.9}, {0.65, 2.5}, {-0.65, 2.5}, {0.65, 0.05}, {-0.65, 0.05},
                    {0.65, -1.1}, {-0.65, -1.1}, {0.65, 3.3}, {-0.65, 3.3}, {0.65, -1.9}, {-0.65, -1.9}}
    };
    /** Walkable deck slabs per hull class: {size, offsets along the hull...} (blocks, + is the bow). */
    private static final double[][] DECKS = {
            {},
            {2.0, -1.35, 0.45, 2.1},
            {2.6, -1.95, 0.55, 2.8}
    };
    /** Height of the deck surface above the entity's origin (matches the renderer's model lift). */
    private static final double DECK_TOP = 0.6125;
    private final java.util.List<VesselDeckEntity> decks = new java.util.ArrayList<>();

    /** How far above a plain boat's seat the deck of each hull class is (matches the renderer's model lift). */
    private static final double[] DECK_LIFT = {0, 0.44, 0.44};

    private final DropItem dropItem;
    private VesselRecord record;
    private boolean released;

    // stuck detection
    private double lastProgress;
    private int stuckChecks;

    public VesselEntity(EntityType<? extends Boat> type, Level level) {
        this(type, level, new DropItem());
    }

    private VesselEntity(EntityType<? extends Boat> type, Level level, DropItem dropItem) {
        super(type, level, dropItem);
        this.dropItem = dropItem;
    }

    /** Supplies the boat item dropped when the vessel is broken (the one it was built from). */
    private static final class DropItem implements Supplier<Item> {
        Item item = Items.OAK_BOAT;

        @Override
        public Item get() {
            return item;
        }
    }

    // ------------------------------------------------------------------ binding

    public void bind(VesselRecord record) {
        this.record = record;
        this.dropItem.item = record.hull();
        entityData.set(DATA_TIER, record.type().tier());
        entityData.set(DATA_HULL, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(record.hull()).toString());
        setCustomName(Component.literal(record.name()));
        setCustomNameVisible(false);
        onStateChanged();
    }

    public VesselRecord record() {
        return record;
    }

    /** Re-reads the record after the fleet manager changed its state (voyage started, stopped, arrived...). */
    public void onStateChanged() {
        if (record == null) return;
        boolean sailing = record.state() == VesselRecord.State.SAILING;
        setAutopilot(sailing);
        if (sailing) {
            Voyage v = record.voyage();
            v.setProgress(v.progress()); // align the steering index with the progress
            lastProgress = v.progress();
            stuckChecks = 0;
        } else {
            setPaddleState(false, false);
        }
    }

    /** Removes the body without destroying the vessel (it lives on in the fleet registry). */
    public void releaseBody() {
        released = true;
        ejectPassengers();
        discard();
    }

    public boolean hasPlayerAboard() {
        for (Entity p : getPassengers()) {
            if (p instanceof Player) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ vanilla hooks

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_AUTOPILOT, false);
        builder.define(DATA_TIER, 0);
        builder.define(DATA_HULL, "minecraft:oak_boat");
        builder.define(DATA_CARGO, false);
    }

    public boolean hasCargo() {
        return entityData.get(DATA_CARGO);
    }

    public VesselType vesselType() {
        return VesselType.byTier(entityData.get(DATA_TIER));
    }

    public String hullId() {
        return entityData.get(DATA_HULL);
    }

    /** Called after an upgrade at the shipyard. */
    public void refreshType() {
        if (record != null) entityData.set(DATA_TIER, record.type().tier());
    }

    @Override
    protected int getMaxPassengers() {
        return vesselType().passengers;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        int tier = vesselType().tier();
        if (tier == 0) return super.getPassengerAttachmentPoint(passenger, dimensions, scale);
        double[][] seats = SEATS[tier];
        int index = Math.max(0, getPassengers().indexOf(passenger));
        double[] seat = seats[Math.min(index, seats.length - 1)];
        return new Vec3(seat[0], rideHeight(dimensions) + DECK_LIFT[tier], seat[1]).yRot(-getYRot() * Mth.DEG_TO_RAD);
    }

    public boolean isAutopilot() {
        return entityData.get(DATA_AUTOPILOT);
    }

    private void setAutopilot(boolean on) {
        entityData.set(DATA_AUTOPILOT, on);
    }

    /** Nobody steers a vessel by hand; it only moves along voyages. */
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * Vessels pass through each other (and each other's decks): two ships meeting head-on in a narrow channel
     * would otherwise block each other forever, and crewless ones can't give way.
     */
    @Override
    public boolean canCollideWith(Entity other) {
        if (other instanceof VesselEntity || other instanceof VesselDeckEntity) return false;
        return super.canCollideWith(other);
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        if (other instanceof VesselEntity || other instanceof VesselDeckEntity) return false;
        return super.canBeCollidedWith(other);
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
    public boolean canBeLeashed() {
        return false;
    }

    // Never written to chunks or player data: the fleet registry is the source of truth and respawns the body.
    // (The entity type itself must stay serializable, otherwise vanilla refuses to let anyone ride it.)
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
    protected void addAdditionalSaveData(ValueOutput output) {
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        // sneak + use: open the cargo hold
        if (record != null && player.isSecondaryUseActive() && record.isOwnedBy(player.getUUID())) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) FleetManager.openHold(sp, record);
            return InteractionResult.SUCCESS;
        }
        if (record != null && !record.isOwnedBy(player.getUUID())) {
            if (!level().isClientSide()) {
                player.sendOverlayMessage(Component.translatable("minecraftportsmod.vessel.not_yours", record.ownerName())
                        .withStyle(ChatFormatting.RED));
            }
            return InteractionResult.FAIL;
        }
        return super.interact(player, hand, location);
    }

    /** Vessels can't be broken in the world; dismantle them at a port office. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (record != null && source.getEntity() instanceof Player p && !p.isCreative()) {
            p.sendOverlayMessage(Component.translatable("minecraftportsmod.vessel.unbreakable").withStyle(ChatFormatting.GRAY));
        }
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        for (VesselDeckEntity deck : decks) deck.discard();
        decks.clear();
        // unloading with its chunk: keep the animals aboard in the fleet record (this body is never saved)
        if (!level().isClientSide() && !released && record != null
                && (reason == RemovalReason.UNLOADED_TO_CHUNK || reason == RemovalReason.UNLOADED_WITH_PLAYER)) {
            FleetManager.stowPassengers(this, record, false);
        }
        if (!level().isClientSide()) {
            FleetManager.onBodyRemoved(this, reason, released);
        }
        super.remove(reason);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        if (level() instanceof ServerLevel serverLevel) {
            if (record == null) {
                // a stray body (e.g. from /summon) that no fleet record owns
                discard();
                return;
            }
            switch (record.state()) {
                case SAILING -> steer(serverLevel);
                case MOORED -> holdBerth(serverLevel);
                case ANCHORED -> holdStill();
                case ADRIFT -> {
                    holdStill();
                    drift(serverLevel);
                }
            }
            record.setPose(getX(), getZ(), getYRot());
            updateDecks(serverLevel);
            if (tickCount % 10 == 0 && record.state() != VesselRecord.State.SAILING) seatAnimalsOnDeck(serverLevel);
            if (tickCount % 20 == 0 && record.settlement() >= 0) {
                entityData.set(DATA_CARGO, org.webtrade.minecraftportsmod.economy.EconomyManager.hasCargo(serverLevel.getServer(), record.id()));
            }
        }
        super.tick();
        if (!level().isClientSide() && isAutopilot()) {
            setPaddleState(true, true);
        }
    }

    private void steer(ServerLevel level) {
        Voyage voyage = record.voyage();
        if (voyage == null || voyage.finished()) {
            FleetManager.arrive(level, record, this);
            return;
        }
        double dx = voyage.targetX() - getX();
        double dz = voyage.targetZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // faster hulls need a wider capture radius or they overshoot and have to come about
        double capture = Math.max(WAYPOINT_RADIUS, record.speed() * 5);
        if (voyage.isLast() ? dist < BERTH_RADIUS : dist < capture) {
            if (voyage.isLast()) {
                voyage.setProgress(voyage.totalLength());
                FleetManager.arrive(level, record, this);
                return;
            }
            voyage.advance();
            dx = voyage.targetX() - getX();
            dz = voyage.targetZ() - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        voyage.trackPhysical(getX(), getZ());

        double speed = record.speed();
        double turn = voyage.turnAngleAtTarget();
        // ease off before sharp turns: full speed at 50 degrees, 45% at 140 and beyond
        if (dist < 8 && turn > 50) speed *= 1.0 - 0.55 * Math.min(1.0, (turn - 50) / 90.0);
        if (voyage.isLast()) speed = Math.min(speed, Math.max(0.04, dist * 0.09));

        float desiredYaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
        float delta = Mth.wrapDegrees(desiredYaw - getYRot());
        // Slow right down while the bow is not pointing at the target and turn harder when slow: a hull that
        // keeps full speed while turning traces a circle and can orbit a nearby waypoint forever.
        double alignment = Mth.clamp((Math.cos(Math.toRadians(delta)) + 0.3) / 1.3, 0.12, 1.0);
        speed *= alignment;
        float maxTurn = MAX_TURN_PER_TICK + (float) (6 * (1 - alignment));
        setYRot(getYRot() + Mth.clamp(delta, -maxTurn, maxTurn));

        // steer mostly along the path, blending in the hull's heading so turns look natural
        double nx = dx / dist, nz = dz / dist;
        double hx = -Mth.sin(getYRot() * Mth.DEG_TO_RAD), hz = Mth.cos(getYRot() * Mth.DEG_TO_RAD);
        double mix = 0.85;
        double vx = nx * mix + hx * (1 - mix);
        double vz = nz * mix + hz * (1 - mix);
        double len = Math.sqrt(vx * vx + vz * vz);
        if (len > 1e-6) {
            vx /= len;
            vz /= len;
        }
        Vec3 v = getDeltaMovement();
        setDeltaMovement(vx * speed / WATER_FRICTION, v.y, vz * speed / WATER_FRICTION);

        checkStuck(level);
    }

    /** Measured along the voyage, not by movement, so circling in place also counts as stuck. */
    private void checkStuck(ServerLevel level) {
        if (tickCount % 40 != 0) return;
        double progress = record.voyage().progress();
        // near the end there is less than a block left to gain, so expect proportionally less
        double expected = Math.min(1.0, record.voyage().remaining() * 0.3);
        if (progress - lastProgress < expected) {
            if (++stuckChecks == 3) {
                Voyage v = record.voyage();
                org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.info(
                        "Vessel {} stuck at {} yaw {} -> target {},{} (waypoint {}/{}), velocity {}, collided h={} v={}",
                        record.name(), String.format("%.2f %.2f %.2f", getX(), getY(), getZ()), Math.round(getYRot()),
                        v.targetX(), v.targetZ(), v.index(), v.count(), getDeltaMovement(), horizontalCollision, verticalCollision);
                FleetManager.replan(level, record);
            }
        } else {
            stuckChecks = 0;
        }
        lastProgress = progress;
    }

    private void holdBerth(ServerLevel level) {
        Dock dock = PortData.get(level.getServer()).dock(record.dockId());
        if (dock == null) {
            holdStill();
            return;
        }
        double dx = dock.berth().getX() + 0.5 - getX();
        double dz = dock.berth().getZ() + 0.5 - getZ();
        Vec3 v = getDeltaMovement();
        if (dx * dx + dz * dz > 0.3 * 0.3) {
            setDeltaMovement(Mth.clamp(dx * 0.15, -0.2, 0.2), v.y, Mth.clamp(dz * 0.15, -0.2, 0.2));
        } else {
            setDeltaMovement(0, v.y, 0);
        }
    }

    /** Anchored or stopped: stay exactly where we are. */
    private void holdStill() {
        Vec3 v = getDeltaMovement();
        setDeltaMovement(0, v.y, 0);
    }

    private void drift(ServerLevel level) {
        if (tickCount % 20 == 0 && getPassengers().isEmpty()) {
            FleetManager.tryAutoMoor(level, record, this);
        }
    }

    /** Lays walkable slabs along a moored/anchored ship's deck; takes them away while it sails. */
    private void updateDecks(ServerLevel level) {
        int tier = vesselType().tier();
        boolean want = tier > 0 && record.state() != VesselRecord.State.SAILING;
        double[] layout = DECKS[tier];
        int count = want ? layout.length - 1 : 0;
        if (count < decks.size()) clearDeckBeforeDeparture(level);
        while (decks.size() > count) decks.removeLast().discard();
        decks.removeIf(Entity::isRemoved);
        while (decks.size() < count) {
            VesselDeckEntity deck = org.webtrade.minecraftportsmod.registry.ModContent.VESSEL_DECK.create(level,
                    net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (deck == null) break;
            deck.attach(this, (float) layout[0]);
            placeDeck(deck, layout[decks.size() + 1]);
            level.addFreshEntity(deck);
            decks.add(deck);
        }
        for (int i = 0; i < decks.size(); i++) placeDeck(decks.get(i), layout[i + 1]);
    }

    private void placeDeck(VesselDeckEntity deck, double along) {
        double fx = -Mth.sin(getYRot() * Mth.DEG_TO_RAD), fz = Mth.cos(getYRot() * Mth.DEG_TO_RAD);
        deck.setPos(getX() + fx * along, getY() + DECK_TOP - VesselDeckEntity.HEIGHT, getZ() + fz * along);
    }

    /**
     * The deck slabs are about to go (the ship casts off): whoever stands on them takes a free seat,
     * or is put ashore next to the port office if the ship is full, instead of dropping into the sea.
     */
    private void clearDeckBeforeDeparture(ServerLevel level) {
        java.util.Set<Entity> standing = new java.util.HashSet<>();
        for (VesselDeckEntity deck : decks) {
            standing.addAll(level.getEntitiesOfClass(LivingEntity.class, deck.getBoundingBox().expandTowards(0, 1.0, 0),
                    e -> e.isAlive() && !e.isPassenger()));
        }
        for (Entity e : standing) {
            if (getPassengers().size() < getMaxPassengers() && e.startRiding(this)) continue;
            BlockPos shore = null;
            if (record.portId() >= 0) {
                var port = PortData.get(level.getServer()).port(record.portId());
                if (port != null) shore = port.office().above();
            }
            if (shore == null) continue; // adrift at open sea: nowhere better to go
            e.teleportTo(shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5);
        }
    }

    /** Animals led or pushed onto the deck take a free seat, so they travel along. */
    private void seatAnimalsOnDeck(ServerLevel level) {
        if (decks.isEmpty() || getPassengers().size() >= getMaxPassengers()) return;
        for (VesselDeckEntity deck : decks) {
            var box = deck.getBoundingBox().expandTowards(0, 1.5, 0);
            for (net.minecraft.world.entity.animal.Animal animal : level.getEntitiesOfClass(
                    net.minecraft.world.entity.animal.Animal.class, box, a -> !a.isPassenger() && a.isAlive())) {
                if (getPassengers().size() >= getMaxPassengers()) return;
                animal.startRiding(this);
            }
        }
    }

    /** Passengers sit facing the bow (vanilla boats turn animals sideways once full). */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 seat = getPassengerRidingPosition(passenger);
        Vec3 attach = passenger.getVehicleAttachmentPoint(this);
        move.accept(passenger, seat.x - attach.x, seat.y - attach.y, seat.z - attach.z);
        if (passenger instanceof Player) {
            clampRotation(passenger);
        } else {
            passenger.setYRot(getYRot());
            if (passenger instanceof LivingEntity living) {
                living.setYBodyRot(getYRot());
                living.setYHeadRot(getYRot());
            }
        }
    }

    public void snapToBerth(BlockPos berth) {
        snapTo(berth.getX() + 0.5, getY(), berth.getZ() + 0.5, getYRot(), 0);
        setDeltaMovement(Vec3.ZERO);
    }
}
