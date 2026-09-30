package org.webtrade.minecraftportsmod.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * A port: the harbour-master's office block plus an anchorage point in open water nearby.
 * Routes between ports run anchorage to anchorage; docks belong to a port and lead to it.
 */
public final class Port {

    public static final Codec<Port> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(p -> p.id),
            Codec.STRING.fieldOf("name").forGetter(p -> p.name),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(p -> p.dimension),
            BlockPos.CODEC.fieldOf("office").forGetter(p -> p.office),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(p -> Optional.ofNullable(p.owner)),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(p -> p.ownerName),
            Codec.LONG.optionalFieldOf("created", 0L).forGetter(p -> p.createdAt),
            BlockPos.CODEC.optionalFieldOf("anchorage").forGetter(p -> Optional.ofNullable(p.anchorage))
    ).apply(i, (id, name, dim, office, owner, ownerName, created, anchorage) -> {
        Port p = new Port(id, name, dim, office, owner.orElse(null), ownerName, created);
        p.anchorage = anchorage.orElse(null);
        return p;
    }));

    private final int id;
    private String name;
    private final ResourceKey<Level> dimension;
    private final BlockPos office;
    private UUID owner;
    private String ownerName;
    private final long createdAt;
    private BlockPos anchorage;

    public Port(int id, String name, ResourceKey<Level> dimension, BlockPos office, UUID owner, String ownerName, long createdAt) {
        this.id = id;
        this.name = name;
        this.dimension = dimension;
        this.office = office.immutable();
        this.owner = owner;
        this.ownerName = ownerName;
        this.createdAt = createdAt;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos office() {
        return office;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Open-water point routes start and end at; null until water near the port is known. */
    public BlockPos anchorage() {
        return anchorage;
    }

    void setAnchorage(BlockPos anchorage) {
        this.anchorage = anchorage == null ? null : anchorage.immutable();
    }

    public double horizontalDistance(Port other) {
        double dx = office.getX() - other.office.getX();
        double dz = office.getZ() - other.office.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
