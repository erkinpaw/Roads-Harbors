package org.webtrade.minecraftportsmod.combat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A warship: a big brig with a row of cannons along each side. Its captain (the first aboard) sets the sails (W/S)
 * and the rudder (A/D) and fires a broadside to the side he looks at; the guns of a side reload after each
 * broadside. The server moves it (the client only sends the captain's orders), so every ship in a fight is where the
 * server says. A pirate ship is the same ship sailed by the server: it closes in, turns its side to its prey and fires.
 * <p>
 * Shot through, a ship sinks: it heels over and goes down, and what a pirate carried floats up.
 */
public class WarshipEntity extends Boat {

    /** A cannonball leaves the gun this fast (blocks per tick). */
    public static final double SHOT_SPEED = 2.0;

    /** How long a sinking takes (ticks). */
    private static final int SINK_TIME = 140;
    private static final EntityDataAccessor<Float> DATA_HULL = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_SAILS = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_PIRATE = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_SINK = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RELOAD_LEFT = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RELOAD_RIGHT = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.INT);
    /** How long the reloading under way takes in all (it is longer with gunners lost): for the bar of it. */
    /** How far her helm is laid over (-1 .. 1): she heels in her turn by it. */
    private static final EntityDataAccessor<Float> DATA_HELM = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_RELOAD_FULL = SynchedEntityData.defineId(WarshipEntity.class, EntityDataSerializers.INT);

    /** The captain's rudder: -1 to port (left), 1 to starboard, 0 amidships. */
    private int rudder;
    /** The speed she makes now (eases towards what the sails give). */
    private double speed;
    /** Broadsides being fired: the guns go off one after another. {side, gun, ticks to go, elevation*100}. */
    private final java.util.List<int[]> firing = new java.util.ArrayList<>();
    /** Ticks since she was last hit (she is patched up when left in peace). */
    private int sinceHit = 1000;
    /** A pirate's brain (null for a captain's ship). */
    private PirateBrain brain;

    private final ShipClass cls;

    /** Whose ship she is (a player's, bought at a village's pier): only the owner sails her and opens her hold. Null: anyone's. */
    private java.util.UUID owner;
    /** Her hold: what her owner carries to trade (six rows, as a big chest). */
    private final net.minecraft.world.SimpleContainer hold = new net.minecraft.world.SimpleContainer(HOLD);
    public static final int HOLD = 54;

    public WarshipEntity(EntityType<? extends Boat> type, Level level) {
        super(type, level, () -> Items.OAK_BOAT);
        cls = org.webtrade.minecraftportsmod.registry.ModContent.shipClass(type);
        entityData.set(DATA_HULL, cls.hull);
        // (on a client she follows the server's word closely: whoever stands on her deck is carried by her moves there,
        // and a ship drawn ticks behind the server's would leave them behind)
        if (getInterpolation() != null) getInterpolation().setInterpolationLength(3);
    }

    /** What kind of warship she is (by her entity type: a brig, a galleon, a ship of the line). */
    public ShipClass cls() {
        return cls;
    }



    // ------------------------------------------------------------------ state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HULL, 100F);
        builder.define(DATA_SAILS, 0);
        builder.define(DATA_PIRATE, false);
        builder.define(DATA_SINK, 0);
        builder.define(DATA_RELOAD_LEFT, 0);
        builder.define(DATA_RELOAD_RIGHT, 0);
        builder.define(DATA_RELOAD_FULL, 1);
        builder.define(DATA_HELM, 0F);
    }

    public float hull() {
        return entityData.get(DATA_HULL);
    }

    public int sails() {
        return entityData.get(DATA_SAILS);
    }

    public boolean isPirate() {
        return entityData.get(DATA_PIRATE);
    }

    /** A pirate's ship: sailed by the server; worse kept than a navy's (less hull to her). */
    public void makePirate() {
        if (!isPirate()) entityData.set(DATA_HULL, Math.min(hull(), cls.hull * PIRATE_HULL));
        entityData.set(DATA_PIRATE, true);
        brain = new PirateBrain(this);
    }

    /** A pirate's hull against a navy ship's of her class. */
    static final float PIRATE_HULL = 0.8F;

    public float maxHull() {
        return isPirate() ? cls.hull * PIRATE_HULL : cls.hull;
    }

    /** Ticks into her sinking (0: afloat). */
    public int sinking() {
        return entityData.get(DATA_SINK);
    }

    /** Ticks until a side's guns are loaded again (side -1 port, 1 starboard). */
    public int reload(int side) {
        return entityData.get(side < 0 ? DATA_RELOAD_LEFT : DATA_RELOAD_RIGHT);
    }

    /** How far a side's guns are loaded (0 just fired .. 1 loaded). */
    public float loaded(int side) {
        return Mth.clamp(1F - reload(side) / (float) Math.max(1, Math.max(entityData.get(DATA_RELOAD_FULL), reload(side))), 0F, 1F);
    }

    public double speed() {
        return speed;
    }

    /** Her heel (degrees, to starboard +): by her helm and her way, eased (a client keeps it). */
    private float heel;

    public float heel(float partial) {
        return heel;
    }

    /** On a client: her heel follows her helm and her speed slowly (a big hull leans over and rights herself slowly). */
    private void heelTick() {
        float way = (float) Math.min(1, Math.hypot(getX() - xo, getZ() - zo) / 0.15);
        float want = Mth.clamp(entityData.get(DATA_HELM) * way * 6F, -6F, 6F);
        heel += (want - heel) * 0.05F;
    }

    public void setSails(int s) {
        entityData.set(DATA_SAILS, Mth.clamp(s, 0, cls.speeds.length - 1));
    }

    /** Sailing on by herself with nobody at the helm (a crew's captain, tests): the rudder held as given. */
    private int cruise;

    public void cruise(int sails, int rudder) {
        cruise = 1;
        setSails(sails);
        this.rudder = Mth.clamp(rudder, -1, 1);
    }

    void setRudder(int r) {
        rudder = Mth.clamp(r, -1, 1);
    }

    /** The captain: the first one aboard. */
    public Player captain() {
        return getFirstPassenger() instanceof Player p ? p : null;
    }

    /** The captain's orders, as his client sent them (a player's ship: only its owner sails her). */
    public void order(ServerPlayer from, int sailsChange, int rudder, int fire, float elevation) {
        if (captain() != from || sinking() > 0) return;
        if (owner != null && !owner.equals(from.getUUID())) return;
        if (sailsChange != 0) setSails(sails() + Integer.signum(sailsChange));
        setRudder(rudder);
        if (fire != 0) fire(fire, elevation);
    }

    // ------------------------------------------------------------------ the hull and where things are on it

    /** The bow's direction (unit, horizontal). */
    public Vec3 forward() {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    /** Starboard (to the right of the bow). */
    public Vec3 starboard() {
        Vec3 f = forward();
        return new Vec3(-f.z, 0, f.x);
    }

    /** A point of the ship: {@code side} blocks to starboard, {@code along} to the bow, {@code up} over the origin. */
    public Vec3 at(double side, double along, double up) {
        return position().add(starboard().scale(side)).add(forward().scale(along)).add(0, up, 0);
    }

    /** Is a point inside her hull (her sides, her length, from the waterline to the top of her bulwarks)? */
    public boolean hits(Vec3 p) {
        Vec3 d = p.subtract(position());
        double side = d.dot(starboard()), along = d.dot(forward()) - cls.middle;
        return Math.abs(side) <= cls.halfBeam && Math.abs(along) <= cls.halfLength && d.y >= -1.2 && d.y <= cls.castle() + 1.2;
    }

    // ------------------------------------------------------------------ guns

    /** A broadside to one side ({@code -1} port, {@code 1} starboard), the guns raised by {@code elevation} degrees. */
    public void fire(int side, float elevation) {
        if (sinking() > 0 || reload(side) > 0) return;
        int time = reloadTime();
        entityData.set(DATA_RELOAD_FULL, time);
        entityData.set(side < 0 ? DATA_RELOAD_LEFT : DATA_RELOAD_RIGHT, time);
        // (the guns go off down the side one after another, the rows together)
        int n = cls.guns();
        for (int g = 0; g < n; g++) firing.add(new int[]{side, g, (g % 7) * 3 + random.nextInt(2) + 1, Math.round(elevation * 100)});
    }

    private void fireGun(ServerLevel level, int side, int gun, float elevation) {
        double[] spot = cls.gun(gun);
        Vec3 muzzle = at(side * cls.muzzle(), spot[0], spot[1]);
        Vec3 out = starboard().scale(side);
        double e = Math.toRadians(elevation + (random.nextFloat() - 0.5F) * 1.6F);
        double yaw = (random.nextFloat() - 0.5F) * 0.05;
        Vec3 dir = out.scale(Math.cos(e)).add(forward().scale(Math.sin(yaw))).add(0, Math.sin(e), 0).normalize();
        CannonballEntity ball = new CannonballEntity(org.webtrade.minecraftportsmod.registry.ModContent.CANNONBALL, level);
        ball.setPos(muzzle);
        ball.setDeltaMovement(dir.scale(SHOT_SPEED).add(getDeltaMovement()));
        ball.ship = getId();
        level.addFreshEntity(ball);
        Fx.gun(level, muzzle, out, random);
    }

    /** Elevation (degrees) that drops a ball {@code range} blocks out, at sea level. */
    public static float elevationFor(double range) {
        // flight of a ball with the drag and gravity of CannonballEntity, found by stepping it
        for (float e = 0; e <= 30; e += 0.5F) if (CannonballEntity.range(SHOT_SPEED, e) >= range) return e;
        return 30;
    }

    // ------------------------------------------------------------------ damage and sinking

    /** A ball hit her. */
    void struck(ServerLevel level, Vec3 where, float damage, Entity by) {
        if (sinking() > 0) return;
        sinceHit = 0;
        float hull = Math.max(0, hull() - damage);
        entityData.set(DATA_HULL, hull);
        Fx.hit(level, where, random);
        if (brain != null && by instanceof WarshipEntity enemy) brain.attackedBy(enemy);
        if (hull <= 0) startSinking(level, by);
    }

    private void startSinking(ServerLevel level, Entity by) {
        entityData.set(DATA_SINK, 1);
        firing.clear();
        Fx.sound(level, position(), SoundEvents.WOOD_BREAK, Fx.HIT, 0.5F);
        Fx.sound(level, position(), SoundEvents.GENERIC_EXPLODE, Fx.HIT, 0.5F);
        // what a pirate carried floats up for whoever sank her
        if (isPirate()) {
            int emeralds = 3 + random.nextInt(6), gold = 1 + random.nextInt(4);
            drop(level, new ItemStack(Items.EMERALD, emeralds));
            drop(level, new ItemStack(Items.GOLD_INGOT, gold));
            if (random.nextInt(3) == 0) drop(level, new ItemStack(Items.GUNPOWDER, 2 + random.nextInt(4)));
        }
        for (int i = 0; i < hold.getContainerSize(); i++) {
            ItemStack st = hold.removeItemNoUpdate(i);
            if (!st.isEmpty()) drop(level, st);
        }
        Pirates.sunk(level, this, by);
    }

    private void drop(ServerLevel level, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, getX() + (random.nextDouble() - 0.5) * 3, getY() + 1.2, getZ() + (random.nextDouble() - 0.5) * 3, stack);
        item.setDeltaMovement(0, 0.2, 0);
        level.addFreshEntity(item);
    }

    /** Ships aren't broken by hand (a creative player may take one away). */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.getEntity() instanceof Player p && p.isCreative() && p.isShiftKeyDown()) {
            discard();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ riding

    @Override
    protected int getMaxPassengers() {
        return 4;
    }

    /** Nobody steers her the vanilla way: the server sails her on the captain's orders. */
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (isPirate() || sinking() > 0) return InteractionResult.PASS;
        // her owner, crouching: her hold
        if (owner != null && player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND) {
            if (!owner.equals(player.getUUID())) return InteractionResult.PASS;
            if (player instanceof ServerPlayer sp) openHold(sp);
            return InteractionResult.SUCCESS;
        }
        if (hire(player, hand)) return InteractionResult.SUCCESS;
        return super.interact(player, hand, location);
    }

    // ------------------------------------------------------------------ a player's ship: the owner, the hold

    public java.util.UUID owner() {
        return owner;
    }

    public void setOwner(java.util.UUID owner) {
        this.owner = owner;
    }

    public net.minecraft.world.SimpleContainer hold() {
        return hold;
    }

    /** The hold opened for her owner: its slots, the player's inventory below. */
    public void openHold(ServerPlayer player) {
        player.openMenu(new net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<Integer>() {
            @Override
            public Integer getScreenOpeningData(ServerPlayer p) {
                return HOLD;
            }

            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return net.minecraft.network.chat.Component.translatable("minecraftportsmod.ship.hold");
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, Player p) {
                return new org.webtrade.minecraftportsmod.vessel.HoldMenu(id, inv, hold);
            }
        });
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        // the captain at the wheel on the quarterdeck, the others along the deck
        int i = Math.max(0, getPassengers().indexOf(passenger));
        double[][] seats = {{0, cls.wheel(), cls.castle()}, {1.1, 0.5, cls.deck()}, {-1.1, 0.5, cls.deck()}, {0, 3.5, cls.deck()}};
        double[] s = seats[Math.min(i, seats.length - 1)];
        return new Vec3(s[0], s[2], s[1]).yRot(-getYRot() * Mth.DEG_TO_RAD);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 seat = getPassengerRidingPosition(passenger);
        Vec3 attach = passenger.getVehicleAttachmentPoint(this);
        move.accept(passenger, seat.x - attach.x, seat.y - attach.y, seat.z - attach.z);
    }

    /** The captain looks all round him: no boat's limit on how far his head turns. */
    @Override
    public void onPassengerTurned(Entity passenger) {
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
    public boolean canCollideWith(Entity other) {
        return !(other instanceof WarshipEntity) && !(other instanceof org.webtrade.minecraftportsmod.vessel.VesselDeckEntity) && super.canCollideWith(other);
    }

    // ------------------------------------------------------------------ her deck, to walk on

    /** The slabs of her deck (solid, invisible: one walks on them), each {side, along, height} on her. */
    private final java.util.List<org.webtrade.minecraftportsmod.vessel.VesselDeckEntity> decks = new java.util.ArrayList<>();
    private double[][] deckLayout;
    /** Where she was and which way she looked a tick ago (whoever stands on her deck goes along with her). */
    private double lastX = Double.NaN, lastZ, lastY;
    private float lastYaw;

    static final float SLAB = 2.0F;

    /**
     * Her deck as slabs: rows along her, two blocks apart, as many across as her beam takes; aft of her wheel at the
     * height of her quarterdeck, then down to her main deck by half a block a row (a slope one walks up).
     */
    double[][] deckLayout() {
        if (deckLayout != null) return deckLayout;
        java.util.List<double[]> out = new java.util.ArrayList<>();
        double deck = cls.deck(), castle = Math.max(deck, cls.castle()), wheel = cls.wheel();
        int across = Math.max(1, (int) Math.round(cls.halfBeam * 2 / SLAB));
        double stern = cls.middle - cls.halfLength + SLAB / 2, bow = cls.middle + cls.halfLength - SLAB / 2;
        double edge = stern;
        for (double a = stern; a <= bow + 1e-6; a += SLAB) {
            // aft of the wheel: the quarterdeck; forward of it, the main deck
            boolean aft = a <= wheel + 1;
            if (aft) edge = a + SLAB / 2;
            // (each slab bigger than the step between them: turned at an angle, they still cover her deck without gaps)
            for (int k = 0; k < across; k++) out.add(new double[]{(k - (across - 1) / 2.0) * SLAB, a, aft ? castle : deck, SLAB * 1.45});
        }
        // a stair down from the quarterdeck's edge to the main deck, a step of half a block a block, up the middle
        int steps = (int) Math.ceil((castle - deck) / 0.5) - 1;
        for (int k = 0; k < steps; k++) out.add(new double[]{0, edge + 0.5 + k, castle - 0.5 * (k + 1), 1.3});
        deckLayout = out.toArray(new double[0][]);
        return deckLayout;
    }

    private void updateDecks(ServerLevel level) {
        if (sinking() > 0) {
            for (var d : decks) d.discard();
            decks.clear();
            return;
        }
        double[][] layout = deckLayout();
        decks.removeIf(Entity::isRemoved);
        while (decks.size() < layout.length) {
            var deck = org.webtrade.minecraftportsmod.registry.ModContent.VESSEL_DECK.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (deck == null) break;
            double[] l = layout[decks.size()];
            deck.attach(this, (float) l[3]);
            Vec3 p = at(l[0], l[1], l[2] - org.webtrade.minecraftportsmod.vessel.VesselDeckEntity.HEIGHT);
            deck.setPos(p.x, p.y, p.z);
            level.addFreshEntity(deck);
            decks.add(deck);
        }
        for (int i = 0; i < decks.size() && i < layout.length; i++) {
            double[] l = layout[i];
            Vec3 p = at(l[0], l[1], l[2] - org.webtrade.minecraftportsmod.vessel.VesselDeckEntity.HEIGHT);
            decks.get(i).setPos(p.x, p.y, p.z);
        }
    }

    /** The slabs of her deck now (tests). */
    public java.util.List<org.webtrade.minecraftportsmod.vessel.VesselDeckEntity> deckSlabs() {
        return decks;
    }

    /** Is a point over her deck (within her length and beam, from her main deck to a little over her highest)? */
    public boolean onDeck(Vec3 p) {
        Vec3 d = p.subtract(position());
        double side = d.dot(starboard()), along = d.dot(forward()) - cls.middle;
        return Math.abs(side) <= cls.halfBeam + 0.3 && Math.abs(along) <= cls.halfLength + 0.3 && d.y >= cls.deck() - 0.8 && d.y <= cls.castle() + 2.5;
    }

    /** Moves a point standing on her as she moved since her last tick (along, and round as she turned). */
    public Vec3 carry(Vec3 p, double fromX, double fromY, double fromZ, float fromYaw) {
        float turn = (getYRot() - fromYaw) * Mth.DEG_TO_RAD;
        double ox = p.x - fromX, oz = p.z - fromZ;
        // (turned the way she turned: her bow (-sin yaw, cos yaw) goes round with her)
        double c = Math.cos(turn), s = Math.sin(turn);
        double rx = ox * c - oz * s, rz = ox * s + oz * c;
        return new Vec3(getX() + rx, p.y + (getY() - fromY), getZ() + rz);
    }

    /** The people on her deck (not the players: each moves himself, see CombatClient) go along with her. */
    private void carryDeck(ServerLevel level) {
        if (!Double.isNaN(lastX) && (lastX != getX() || lastZ != getZ() || lastYaw != getYRot())) {
            var box = getBoundingBox().inflate(cls.halfLength + 2, cls.castle() + 3, cls.halfLength + 2);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof Player) && !(e instanceof SailorEntity) && e.getVehicle() == null)) {
                if (!onDeckFrom(e.position(), lastX, lastY, lastZ, lastYaw)) continue;
                Vec3 to = carry(e.position(), lastX, lastY, lastZ, lastYaw);
                e.setPos(to.x, to.y, to.z);
                e.setYRot(e.getYRot() + (getYRot() - lastYaw));
                e.setYBodyRot(e.getYRot());
            }
        }
        lastX = getX();
        lastY = getY();
        lastZ = getZ();
        lastYaw = getYRot();
    }

    /** Was a point over her deck where she stood a tick ago? */
    public boolean onDeckFrom(Vec3 p, double x, double y, double z, float yaw) {
        float r = yaw * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(r), 0, Mth.cos(r)), st = new Vec3(-f.z, 0, f.x);
        Vec3 d = p.subtract(x, y, z);
        double side = d.dot(st), along = d.dot(f) - cls.middle;
        return Math.abs(side) <= cls.halfBeam + 0.3 && Math.abs(along) <= cls.halfLength + 0.3 && d.y >= cls.deck() - 0.8 && d.y <= cls.castle() + 2.5;
    }

    /** Off the helm (or a seat): onto her deck, by the wheel (not into the sea). */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return at(0.9, cls.wheel() + 1.2, cls.castle() + 0.05);
    }

    /** The height of her deck at a place along her: her quarterdeck aft of the wheel, else her main deck. */
    public double deckAt(double along) {
        return along <= cls.wheel() + 1 + SLAB / 2 ? Math.max(cls.deck(), cls.castle()) : cls.deck();
    }

    // ------------------------------------------------------------------ her crew

    /** Her crew by role (captain, gunners, marines, hands): how many she has; and how many she takes at most. */
    private final int[] crew = new int[SailorEntity.Role.values().length];
    private boolean crewSet;
    private final java.util.List<SailorEntity> bodies = new java.util.ArrayList<>();

    public int[] crewMax() {
        return new int[]{1, Math.min(8, cls.guns()), cls == ShipClass.BRIG ? 2 : cls == ShipClass.GALLEON ? 3 : 4, 2};
    }

    public int crew(SailorEntity.Role r) {
        if (!crewSet) {
            System.arraycopy(crewMax(), 0, crew, 0, crew.length);
            crewSet = true;
        }
        return crew[r.ordinal()];
    }

    public int crewTotal() {
        int n = 0;
        for (SailorEntity.Role r : SailorEntity.Role.values()) n += crew(r);
        return n;
    }

    /** One of her crew of a role lost (tests). */
    public void crewLostForTestRole(SailorEntity.Role r) {
        crewLost(r);
    }

    /** One of her hands lost (tests). */
    public void crewLostForTest() {
        crewLost(SailorEntity.Role.HAND);
    }

    /** One of her crew was killed. */
    void crewLost(SailorEntity.Role r) {
        crew(r);
        crew[r.ordinal()] = Math.max(0, crew[r.ordinal()] - 1);
    }

    /** Her crew aboard in body: those missing come up on deck (amidships), each to his post. */
    private void crewBodies(ServerLevel level) {
        bodies.removeIf(b -> b.isRemoved() || b.ship() != this);
        int[] have = new int[crew.length];
        for (SailorEntity b : bodies) have[b.role().ordinal()]++;
        for (SailorEntity.Role r : SailorEntity.Role.values()) {
            for (int k = have[r.ordinal()]; k < crew(r); k++) {
                SailorEntity s = org.webtrade.minecraftportsmod.registry.ModContent.SAILOR.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
                if (s == null) return;
                int n = bodies.size();
                s.setup(this, r, (getId() * 7 + n * 3) % 9, 0, cls.middle);
                if (r == SailorEntity.Role.GUNNER) {
                    s.gunSide = n % 2 == 0 ? 1 : -1;
                    s.gun = k % Math.max(1, cls.guns());
                }
                level.addFreshEntity(s);
                bodies.add(s);
            }
        }
    }

    /** The nearest ship she is fighting (a pirate for a navy ship, a navy ship for a pirate) within reach, or null. */
    WarshipEntity enemy(ServerLevel level, double reach) {
        WarshipEntity best = null;
        double bd = reach * reach;
        for (WarshipEntity o : level.getEntitiesOfClass(WarshipEntity.class, getBoundingBox().inflate(reach))) {
            if (o == this || o.isPirate() == isPirate() || o.sinking() > 0) continue;
            double d = o.distanceToSqr(this);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    private WarshipEntity enemyNow;
    private int enemySeen = -100;

    /** What a sailor of hers does now (called from his tick). */
    void crewTick(ServerLevel level, SailorEntity s) {
        double rail = cls.halfBeam - 0.6;
        // (the enemy looked for once in a while for the whole crew, not by every man every tick)
        if (tickCount - enemySeen >= 10) {
            enemySeen = tickCount;
            enemyNow = enemy(level, 64);
        }
        WarshipEntity enemy = enemyNow != null && !enemyNow.isRemoved() ? enemyNow : null;
        switch (s.role()) {
            case CAPTAIN -> {
                // at the wheel; beside it while a player has the helm
                double side = captain() != null ? 1.3 : 0;
                s.runTo(side, cls.wheel() + 0.6, 0);
            }
            case GUNNER -> {
                double along = cls.gun(s.gun)[0];
                boolean action = reload(s.gunSide) > 0 || enemy != null || !firing.isEmpty();
                if (action || s.wander <= 0) s.runTo(s.gunSide * rail, along + (action ? 0.6 : 0), action ? s.gunSide : 0);
                if (!action && --s.wander <= 0) s.wander = 200 + random.nextInt(200);
                if (reload(s.gunSide) > 0 && s.there()) s.work();
            }
            case MARINE -> {
                if (enemy != null) {
                    Vec3 d = enemy.position().subtract(position());
                    int side = d.dot(starboard()) >= 0 ? 1 : -1;
                    if (s.faces != side) s.runTo(side * rail, cls.middle + (random.nextDouble() - 0.5) * cls.halfLength, side);
                    if (s.there() && s.musket == 0 && enemy.distanceTo(this) < 30) {
                        LivingEntity target = enemy.target(level, s);
                        if (target != null) s.shoot(level, target);
                    }
                } else if (--s.wander <= 0) {
                    s.wander = 120 + random.nextInt(160);
                    s.runTo((random.nextDouble() - 0.5) * 2 * rail, cls.middle + (random.nextDouble() - 0.5) * 1.4 * cls.halfLength, 0);
                }
            }
            case HAND -> {
                if (--s.wander <= 0) {
                    s.wander = 60 + random.nextInt(120);
                    s.runTo((random.nextDouble() - 0.5) * 2 * rail, cls.middle + (random.nextDouble() - 0.5) * 1.6 * cls.halfLength, 0);
                }
            }
        }
    }

    /** Someone on her deck to shoot at (one of her crew, a player aboard), the nearest to the marine. */
    LivingEntity target(ServerLevel level, SailorEntity from) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        var box = getBoundingBox().inflate(cls.halfLength + 2, cls.castle() + 3, cls.halfLength + 2);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !(e instanceof Player p && (p.isCreative() || p.isSpectator())))) {
            boolean ours = e instanceof SailorEntity s ? s.ship == getId() : e.getVehicle() == this || onDeck(e.position());
            if (!ours) continue;
            double d = e.distanceToSqr(from);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Gunners short: her guns take longer to load (half as long again for each third of them missing). */
    int reloadTime() {
        double missing = 1 - crew(SailorEntity.Role.GUNNER) / (double) Math.max(1, crewMax()[SailorEntity.Role.GUNNER.ordinal()]);
        return (int) Math.round(cls.reload * (1 + 1.5 * missing));
    }

    /**
     * A player takes on a hand for an emerald out of the purse (crouching, empty-handed, at her), one more of whom she is
     * shortest of, while she has room for him.
     */
    private boolean hire(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || !stack.isEmpty() || hand != InteractionHand.MAIN_HAND) return false;
        int[] max = crewMax();
        SailorEntity.Role want = null;
        int gap = 0;
        for (SailorEntity.Role r : SailorEntity.Role.values()) {
            int g = max[r.ordinal()] - crew(r);
            if (g > gap) {
                gap = g;
                want = r;
            }
        }
        if (want == null) return false;
        if (!level().isClientSide()) {
            if (!player.isCreative() && player instanceof net.minecraft.server.level.ServerPlayer sp
                    && !org.webtrade.minecraftportsmod.colony.Wallet.pay(sp, 1)) return false;
            crew[want.ordinal()]++;
            playSound(SoundEvents.VILLAGER_YES, 1F, 1F);
        }
        return true;
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        for (var b : bodies) if (!b.isRemoved() && reason.shouldDestroy()) b.discard();
        for (var d : decks) d.discard();
        decks.clear();
        super.remove(reason);
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return !(other instanceof WarshipEntity) && !(other instanceof CannonballEntity) && super.canBeCollidedWith(other);
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    // ------------------------------------------------------------------ sailing

    @Override
    public void tick() {
        if (level().isClientSide()) heelTick();
        if (level() instanceof ServerLevel level) {
            if (sinking() > 0) {
                sink(level);
                return;
            }
            if (brain != null) brain.tick(level);
            else if (captain() == null && cruise == 0) {
                // nobody at the helm: she lies to (her sails as they were, backed: no way on her)
                rudder = 0;
            }
            sail();
            guns(level);
            if (++sinceHit > 600 && hull() < maxHull() && tickCount % 20 == 0) entityData.set(DATA_HULL, Math.min(maxHull(), hull() + 1));
            if (tickCount % 3 == 0) Fx.damage(level, this, random);
        }
        super.tick();
        setPaddleState(false, false);
        if (level() instanceof ServerLevel level) {
            updateDecks(level);
            carryDeck(level);
            if (sinking() == 0 && tickCount % 20 == 5) crewBodies(level);
        }
    }

    /** Way on her and her turning, by the sails and the rudder: she turns only while she moves, the faster the more. */
    /** The rudder as it is laid over now (it goes over and comes back slowly: a big ship answers her helm late). */
    private float helm;

    private void sail() {
        boolean underWay = brain != null || captain() != null || cruise != 0;
        double want = underWay ? cls.speeds[sails()] : 0;
        // (a heavy ship gathers way slowly, and loses it slowly)
        speed += Mth.clamp(want - speed, -0.003, 0.0015);
        helm += Mth.clamp(rudder - helm, -0.04F, 0.04F);
        if (Math.abs(entityData.get(DATA_HELM) - helm) > 0.01F) entityData.set(DATA_HELM, helm);
        // she turns only with way on her: the more, the faster, but never fast
        float turn = (float) (helm * (0.12 + speed * 3.0) * cls.turn);
        setYRot(getYRot() + turn);
        Vec3 v = getDeltaMovement();
        Vec3 f = forward();
        // the current carries her along under way: with it she makes more, against it less, across it she is set aside
        double cx = 0, cz = 0;
        if (underWay && sinking() == 0) {
            double[] c = Currents.at(getX(), getZ());
            cx = c[0];
            cz = c[1];
        }
        // (the water's drag on a boat is 0.9 a tick: the velocity is set so that what is left is the speed)
        setDeltaMovement((f.x * speed + cx) / 0.9, v.y, (f.z * speed + cz) / 0.9);
        if (horizontalCollision) speed *= 0.5;
    }

    private void guns(ServerLevel level) {
        for (int side : new int[]{-1, 1}) {
            var data = side < 0 ? DATA_RELOAD_LEFT : DATA_RELOAD_RIGHT;
            int r = entityData.get(data);
            if (r > 0) entityData.set(data, r - 1);
        }
        for (var it = firing.iterator(); it.hasNext(); ) {
            int[] f = it.next();
            if (--f[2] > 0) continue;
            fireGun(level, f[0], f[1], f[3] / 100F);
            it.remove();
        }
    }

    private void sink(ServerLevel level) {
        int t = sinking() + 1;
        entityData.set(DATA_SINK, t);
        if (t == 2) ejectPassengers();
        speed = 0;
        setPos(getX(), getY() - 0.035, getZ());
        Fx.sinking(level, position(), random);
        if (t % 4 == 0) {
            Vec3 p = at((random.nextDouble() - 0.5) * 2 * cls.halfBeam, cls.middle + (random.nextDouble() - 0.5) * 2 * cls.halfLength, 0.4);
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y, p.z, 6, 0.6, 0.2, 0.6, 0.1);
            level.sendParticles(ParticleTypes.SPLASH, p.x, p.y + 0.6, p.z, 10, 0.8, 0.1, 0.8, 0.1);
        }
        if (t >= SINK_TIME) discard();
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("hull", hull());
        output.putBoolean("pirate", isPirate());
        crew(SailorEntity.Role.HAND);
        output.putIntArray("crew", crew.clone());
        if (owner != null) output.putString("owner", owner.toString());
        net.minecraft.world.ContainerHelper.saveAllItems(output, hold.getItems());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(DATA_HULL, input.getFloatOr("hull", cls.hull));
        if (input.getBooleanOr("pirate", false)) makePirate();
        input.getIntArray("crew").ifPresent(a -> {
            for (int i = 0; i < Math.min(a.length, crew.length); i++) crew[i] = a[i];
            crewSet = true;
        });
        String o = input.getStringOr("owner", "");
        try {
            owner = o.isEmpty() ? null : java.util.UUID.fromString(o);
        } catch (IllegalArgumentException e) {
            owner = null;
        }
        net.minecraft.world.ContainerHelper.loadAllItems(input, hold.getItems());
    }
}
