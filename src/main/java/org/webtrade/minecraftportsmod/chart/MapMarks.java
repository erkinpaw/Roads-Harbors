package org.webtrade.minecraftportsmod.chart;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The players' marks on the world map, and the screenshots taken on it: kept with the world, seen by everyone on
 * the server. A screenshot's picture is a file of its own (see {@link #shotFile}); here only where and when it was.
 */
public final class MapMarks extends SavedData {

    /** The icons a mark can have (the textures are gui/chart/mark/&lt;icon&gt;.png). */
    public static final List<String> ICONS = List.of("anchor", "lighthouse", "tower", "castle", "house", "tent", "mine", "tree", "cave",
            "flag", "star", "skull", "chest", "camera");

    /** How many screenshots one player may keep on the server. */
    public static final int SHOTS_PER_PLAYER = 200;

    public static final class Mark {
        static final Codec<Mark> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(m -> m.id),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(m -> m.owner),
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter(m -> m.ownerName),
                Codec.STRING.optionalFieldOf("name", "").forGetter(m -> m.name),
                Codec.STRING.optionalFieldOf("icon", "flag").forGetter(m -> m.icon),
                Codec.STRING.optionalFieldOf("dim", "minecraft:overworld").forGetter(m -> m.dim),
                Codec.INT.fieldOf("x").forGetter(m -> m.x),
                Codec.INT.fieldOf("z").forGetter(m -> m.z),
                Codec.LONG.optionalFieldOf("time", 0L).forGetter(m -> m.time),
                Codec.STRING.optionalFieldOf("text", "").forGetter(m -> m.text),
                Codec.INT.listOf().optionalFieldOf("shots", List.of()).forGetter(m -> m.shots)
        ).apply(i, (id, owner, ownerName, name, icon, dim, x, z, time, text, shots) -> {
            Mark m = new Mark(id, owner, ownerName, dim, x, z, time);
            m.name = name;
            m.icon = icon;
            m.text = text;
            m.shots.addAll(shots);
            return m;
        }));

        public final int id;
        public final UUID owner;
        public final String ownerName, dim;
        public final int x, z;
        public final long time;
        public String name = "", icon = "flag", text = "";
        /** Its screenshots, in the order they were added. */
        public final List<Integer> shots = new ArrayList<>();

        Mark(int id, UUID owner, String ownerName, String dim, int x, int z, long time) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.dim = dim;
            this.x = x;
            this.z = z;
            this.time = time;
        }
    }

    public record Shot(int id, UUID owner, String ownerName, String dim, int x, int z, long time, int mark) {
        static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(Shot::id),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Shot::owner),
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Shot::ownerName),
                Codec.STRING.optionalFieldOf("dim", "minecraft:overworld").forGetter(Shot::dim),
                Codec.INT.fieldOf("x").forGetter(Shot::x),
                Codec.INT.fieldOf("z").forGetter(Shot::z),
                Codec.LONG.optionalFieldOf("time", 0L).forGetter(Shot::time),
                Codec.INT.optionalFieldOf("mark", -1).forGetter(Shot::mark)
        ).apply(i, Shot::new));
    }

    public static final Codec<MapMarks> CODEC = RecordCodecBuilder.create(i -> i.group(
            Mark.CODEC.listOf().optionalFieldOf("marks", List.of()).forGetter(d -> new ArrayList<>(d.marks.values())),
            Shot.CODEC.listOf().optionalFieldOf("shots", List.of()).forGetter(d -> new ArrayList<>(d.shots.values())),
            Codec.INT.optionalFieldOf("next", 1).forGetter(d -> d.next)
    ).apply(i, (marks, shots, next) -> {
        MapMarks d = new MapMarks();
        for (Mark m : marks) d.marks.put(m.id, m);
        for (Shot s : shots) d.shots.put(s.id(), s);
        d.next = next;
        return d;
    }));

    public static final SavedDataType<MapMarks> TYPE = new SavedDataType<>(Minecraftportsmod.id("map_marks"), MapMarks::new, CODEC, null);

    final Map<Integer, Mark> marks = new LinkedHashMap<>();
    final Map<Integer, Shot> shots = new LinkedHashMap<>();
    int next = 1;

    public MapMarks() {
    }

    public static MapMarks get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Where a screenshot's picture is kept (a JPEG). */
    public static Path shotFile(MinecraftServer server, int id) {
        return server.getWorldPath(LevelResource.ROOT).resolve("minecraftportsmod").resolve("shots").resolve(id + ".jpg");
    }

    public List<Mark> marks() {
        return List.copyOf(marks.values());
    }

    public List<Shot> shots() {
        return List.copyOf(shots.values());
    }

    public Mark mark(int id) {
        return marks.get(id);
    }

    public Shot shot(int id) {
        return shots.get(id);
    }

    Mark addMark(UUID owner, String ownerName, String dim, int x, int z, String icon, String name) {
        Mark m = new Mark(next++, owner, ownerName, dim, x, z, System.currentTimeMillis());
        m.icon = ICONS.contains(icon) ? icon : "flag";
        m.name = name == null ? "" : name;
        marks.put(m.id, m);
        setDirty();
        return m;
    }

    Shot addShot(UUID owner, String ownerName, String dim, int x, int z, int mark) {
        Shot s = new Shot(next++, owner, ownerName, dim, x, z, System.currentTimeMillis(), mark);
        shots.put(s.id(), s);
        Mark m = marks.get(mark);
        if (m != null) m.shots.add(s.id());
        setDirty();
        return s;
    }

    int shotsOf(UUID owner) {
        int n = 0;
        for (Shot s : shots.values()) if (s.owner().equals(owner)) n++;
        return n;
    }

    void removeMark(int id) {
        marks.remove(id);
        setDirty();
    }

    void removeShot(int id) {
        Shot s = shots.remove(id);
        if (s != null) for (Mark m : marks.values()) m.shots.remove((Integer) id);
        setDirty();
    }

    void changed() {
        setDirty();
    }
}
