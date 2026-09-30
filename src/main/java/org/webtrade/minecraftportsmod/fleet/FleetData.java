package org.webtrade.minecraftportsmod.fleet;

import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every vessel in the world, stored with the world save. Server thread only. */
public final class FleetData extends SavedData {

    public static final Codec<FleetData> CODEC = VesselRecord.CODEC.listOf()
            .xmap(FleetData::new, d -> new ArrayList<>(d.vessels.values()))
            .fieldOf("vessels").codec();

    public static final SavedDataType<FleetData> TYPE = new SavedDataType<>(
            Minecraftportsmod.id("fleet"), FleetData::new, CODEC, null);

    private final Map<UUID, VesselRecord> vessels = new LinkedHashMap<>();

    public FleetData() {
    }

    private FleetData(List<VesselRecord> list) {
        list.forEach(v -> vessels.put(v.id(), v));
    }

    public static FleetData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Collection<VesselRecord> all() {
        return Collections.unmodifiableCollection(vessels.values());
    }

    public VesselRecord get(UUID id) {
        return id == null ? null : vessels.get(id);
    }

    public List<VesselRecord> ownedBy(UUID owner) {
        List<VesselRecord> list = new ArrayList<>();
        for (VesselRecord v : vessels.values()) {
            if (owner.equals(v.owner())) list.add(v);
        }
        return list;
    }

    void add(VesselRecord record) {
        vessels.put(record.id(), record);
        setDirty();
    }

    void remove(UUID id) {
        if (vessels.remove(id) != null) setDirty();
    }

    /** Records are mutable; call after changing one. */
    void changed() {
        setDirty();
    }
}
