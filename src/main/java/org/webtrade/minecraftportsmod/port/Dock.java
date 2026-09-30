package org.webtrade.minecraftportsmod.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * A berth: a spot in the water that belongs to a port, where one vessel ties up.
 * Berths are placed and moved by the port itself (see {@link BerthPlanner}), never by hand.
 * The occupant is either the vessel moored there or the vessel that has reserved it on its way in.
 */
public final class Dock {

    public static final Codec<Dock> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(d -> d.id),
            Codec.INT.fieldOf("port").forGetter(d -> d.portId),
            BlockPos.CODEC.fieldOf("berth").forGetter(d -> d.berth),
            UUIDUtil.CODEC.optionalFieldOf("occupant").forGetter(d -> Optional.ofNullable(d.occupant)),
            Codec.BOOL.optionalFieldOf("locked", false).forGetter(d -> d.locked)
    ).apply(i, (id, port, berth, occupant, locked) -> {
        Dock d = new Dock(id, port, berth);
        d.occupant = occupant.orElse(null);
        d.locked = locked;
        return d;
    }));

    private final int id;
    private final int portId;
    private BlockPos berth;
    private UUID occupant;
    /** Locked berths stay exactly where they are; unlocked ones move to the best spot the port can find. */
    private boolean locked;

    public Dock(int id, int portId, BlockPos berth) {
        this.id = id;
        this.portId = portId;
        this.berth = berth.immutable();
    }

    public int id() {
        return id;
    }

    public int portId() {
        return portId;
    }

    /** The water block (at sea level) where the vessel waits. */
    public BlockPos berth() {
        return berth;
    }

    void setBerth(BlockPos berth) {
        this.berth = berth.immutable();
    }

    public UUID occupant() {
        return occupant;
    }

    void setOccupant(UUID occupant) {
        this.occupant = occupant;
    }

    public boolean isLocked() {
        return locked;
    }

    void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isFree() {
        return occupant == null;
    }
}
