package org.webtrade.minecraftportsmod.fleet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.webtrade.minecraftportsmod.vessel.Voyage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The permanent identity of a vessel: who owns it, where it is, what it is doing. This record — not the
 * in-world boat entity — is the source of truth. The entity only exists while the vessel's surroundings are
 * loaded; everything that must survive (upgrades, and later cargo) lives here.
 */
public final class VesselRecord {

    public enum State implements StringRepresentable {
        /** Tied up at a berth ({@link #dockId}). */
        MOORED("moored"),
        /** Waiting near a port because all its berths were taken. */
        ANCHORED("anchored"),
        /** Following a {@link Voyage}. */
        SAILING("sailing"),
        /** Somewhere at sea, rowed or left there by its owner. */
        ADRIFT("adrift");

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);
        private final String name;

        State(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** One non-empty slot of the cargo hold. */
    private record CargoSlot(int slot, ItemStack item) {
        static final Codec<CargoSlot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("slot").forGetter(CargoSlot::slot),
                ItemStack.CODEC.fieldOf("item").forGetter(CargoSlot::item)
        ).apply(i, CargoSlot::new));
    }

    private record Place(ResourceKey<Level> dimension, double x, double z, float yaw) {
        static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Place::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(Place::x),
                Codec.DOUBLE.fieldOf("z").forGetter(Place::z),
                Codec.FLOAT.optionalFieldOf("yaw", 0F).forGetter(Place::yaw)
        ).apply(i, Place::new));
    }

    public static final Codec<VesselRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(v -> v.id),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(v -> Optional.ofNullable(v.owner)),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(v -> v.ownerName),
            Codec.STRING.fieldOf("name").forGetter(v -> v.name),
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("hull").forGetter(v -> v.hull),
            Codec.DOUBLE.optionalFieldOf("speed_multiplier", 1.0).forGetter(v -> v.speedMultiplier),
            Codec.INT.optionalFieldOf("tier", 0).forGetter(v -> v.type.tier()),
            Place.CODEC.fieldOf("place").forGetter(v -> new Place(v.dimension, v.x, v.z, v.yaw)),
            State.CODEC.fieldOf("state").forGetter(v -> v.state),
            Codec.INT.optionalFieldOf("port", -1).forGetter(v -> v.portId),
            Codec.INT.optionalFieldOf("dock", -1).forGetter(v -> v.dockId),
            Voyage.CODEC.optionalFieldOf("voyage").forGetter(v -> Optional.ofNullable(v.voyage)),
            CargoSlot.CODEC.listOf().optionalFieldOf("cargo", List.of()).forGetter(VesselRecord::cargoSlots),
            net.minecraft.nbt.CompoundTag.CODEC.listOf().optionalFieldOf("stowed", List.of()).forGetter(v -> v.stowed),
            Codec.INT.optionalFieldOf("settlement", -1).forGetter(v -> v.settlement),
            UUIDUtil.CODEC.optionalFieldOf("crew").forGetter(v -> Optional.ofNullable(v.crew))
    ).apply(i, (id, owner, ownerName, name, hull, speed, tier, place, state, port, dock, voyage, cargo, stowed, settlement, crew) -> {
        VesselRecord r = new VesselRecord(id, owner.orElse(null), ownerName, name, hull, place.dimension);
        r.speedMultiplier = speed;
        r.type = VesselType.byTier(tier);
        r.x = place.x;
        r.z = place.z;
        r.yaw = place.yaw;
        r.state = state;
        r.portId = port;
        r.dockId = dock;
        r.voyage = voyage.orElse(null);
        if (r.state == State.SAILING && r.voyage == null) r.state = State.ADRIFT;
        r.resizeHold();
        r.stowed.addAll(stowed);
        r.settlement = settlement;
        r.crew = crew.orElse(null);
        for (CargoSlot c : cargo) {
            if (c.slot >= 0 && c.slot < r.cargo.size()) r.cargo.set(c.slot, c.item);
        }
        return r;
    }));

    /** Base cruising speed in blocks per tick (8 blocks/s, about a well-rowed boat). */
    public static final double BASE_SPEED = 0.4;

    private final UUID id;
    private UUID owner;
    private String ownerName;
    private String name;
    private final Item hull;
    /** Extra multiplier for future upgrades (engines, sails...). */
    private double speedMultiplier = 1.0;
    private VesselType type = VesselType.BOAT;

    private ResourceKey<Level> dimension;
    private double x, z;
    private float yaw;
    private State state = State.ADRIFT;
    private int portId = -1;
    private int dockId = -1;
    private Voyage voyage;

    /** The cargo hold; its size follows the hull class. */
    private NonNullList<ItemStack> cargo = NonNullList.withSize(VesselType.BOAT.holdSlots, ItemStack.EMPTY);

    /**
     * Passengers (animals etc.) saved while the vessel has no body in the world; they are seated again when it
     * reappears. Players are never stowed.
     */
    private final List<net.minecraft.nbt.CompoundTag> stowed = new java.util.ArrayList<>();

    /** The settlement this trade vessel belongs to (no player owns it), or -1. */
    private int settlement = -1;
    /** The skipper of a settlement's vessel (a resident entity), or null. */
    private UUID crew;

    /** Transient: an async voyage plan is being computed for this vessel. */
    boolean planning;

    public VesselRecord(UUID id, UUID owner, String ownerName, String name, Item hull, ResourceKey<Level> dimension) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = name;
        this.hull = hull;
        this.dimension = dimension;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public boolean isOwnedBy(UUID player) {
        if (settlement >= 0) return false;
        return owner == null || owner.equals(player);
    }

    public int settlement() {
        return settlement;
    }

    public UUID crew() {
        return crew;
    }

    public void setCrew(UUID crew) {
        this.crew = crew;
    }

    void setSettlement(int settlement) {
        this.settlement = settlement;
    }

    public String ownerName() {
        return ownerName;
    }

    void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    /** The boat item this vessel was built from; dropped when it is destroyed. */
    public Item hull() {
        return hull;
    }

    public double speed() {
        return BASE_SPEED * type.speedFactor * speedMultiplier;
    }

    public VesselType type() {
        return type;
    }

    void setType(VesselType type) {
        this.type = type;
        resizeHold();
    }

    public List<net.minecraft.nbt.CompoundTag> stowed() {
        return stowed;
    }

    public NonNullList<ItemStack> cargo() {
        return cargo;
    }

    /** Grows the hold to the hull's size, keeping everything already in it. */
    private void resizeHold() {
        if (cargo.size() >= type.holdSlots) return;
        NonNullList<ItemStack> bigger = NonNullList.withSize(type.holdSlots, ItemStack.EMPTY);
        for (int i = 0; i < cargo.size(); i++) bigger.set(i, cargo.get(i));
        cargo = bigger;
    }

    private List<CargoSlot> cargoSlots() {
        List<CargoSlot> list = new java.util.ArrayList<>();
        for (int i = 0; i < cargo.size(); i++) {
            if (!cargo.get(i).isEmpty()) list.add(new CargoSlot(i, cargo.get(i)));
        }
        return list;
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public void setPose(double x, double z, float yaw) {
        this.x = x;
        this.z = z;
        this.yaw = yaw;
    }

    public State state() {
        return state;
    }

    public int portId() {
        return portId;
    }

    public int dockId() {
        return dockId;
    }

    public Voyage voyage() {
        return voyage;
    }

    public boolean isPlanning() {
        return planning;
    }

    void moor(int portId, int dockId) {
        this.state = State.MOORED;
        this.portId = portId;
        this.dockId = dockId;
        this.voyage = null;
    }

    void anchor(int portId) {
        this.state = State.ANCHORED;
        this.portId = portId;
        this.dockId = -1;
        this.voyage = null;
    }

    void sail(Voyage voyage) {
        this.state = State.SAILING;
        this.voyage = voyage;
        this.portId = -1;
        this.dockId = -1;
    }

    void drift() {
        this.state = State.ADRIFT;
        this.portId = -1;
        this.dockId = -1;
        this.voyage = null;
    }
}
