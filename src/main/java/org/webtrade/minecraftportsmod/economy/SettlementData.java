package org.webtrade.minecraftportsmod.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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

/** All settlements, their trade trips and the economy clock, stored with the world save. Server thread only. */
public final class SettlementData extends SavedData {

    /** Default length of an economy day in ticks (a quarter of a Minecraft day). */
    public static final int DEFAULT_DAY_LENGTH = 6000;

    public static final Codec<SettlementData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Settlement.CODEC.listOf().optionalFieldOf("settlements", List.of()).forGetter(d -> new ArrayList<>(d.settlements.values())),
            TradeRun.CODEC.listOf().optionalFieldOf("runs", List.of()).forGetter(d -> new ArrayList<>(d.runs.values())),
            Codec.LONG.optionalFieldOf("day", 0L).forGetter(d -> d.day),
            Codec.INT.optionalFieldOf("day_ticks", 0).forGetter(d -> d.dayTicks),
            Codec.INT.optionalFieldOf("day_length", DEFAULT_DAY_LENGTH).forGetter(d -> d.dayLength),
            News.CODEC.listOf().optionalFieldOf("news", List.of()).forGetter(d -> new ArrayList<>(d.news))
    ).apply(i, (list, runs, day, dayTicks, dayLength, news) -> {
        SettlementData d = new SettlementData();
        list.forEach(s -> d.settlements.put(s.id(), s));
        runs.forEach(r -> d.runs.put(r.vessel, r));
        d.day = day;
        d.dayTicks = dayTicks;
        d.dayLength = Math.max(200, dayLength);
        d.news.addAll(news);
        return d;
    }));

    public static final SavedDataType<SettlementData> TYPE = new SavedDataType<>(
            Minecraftportsmod.id("settlements"), SettlementData::new, CODEC, null);

    /** One piece of news from the world: an event, a famine, a village rising, help between neighbours. */
    public record News(long day, int portId, net.minecraft.network.chat.Component text) {
        static final Codec<News> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("day").forGetter(News::day),
                Codec.INT.fieldOf("port").forGetter(News::portId),
                net.minecraft.network.chat.ComponentSerialization.CODEC.fieldOf("text").forGetter(News::text)
        ).apply(i, News::new));
    }

    public static final int NEWS_SIZE = 100;
    /** Newest first. */
    final java.util.Deque<News> news = new java.util.ArrayDeque<>();

    public List<News> news() {
        return new ArrayList<>(news);
    }

    private final Map<Integer, Settlement> settlements = new LinkedHashMap<>();
    private final Map<UUID, TradeRun> runs = new LinkedHashMap<>();
    /** Economy days since the first settlement appeared. */
    long day;
    /** Ticks into the current day. */
    int dayTicks;
    int dayLength = DEFAULT_DAY_LENGTH;

    public SettlementData() {
    }

    public static SettlementData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Collection<Settlement> all() {
        return Collections.unmodifiableCollection(settlements.values());
    }

    public Settlement get(int id) {
        return settlements.get(id);
    }

    public long day() {
        return day;
    }

    public int dayLength() {
        return dayLength;
    }

    public int dayTicks() {
        return dayTicks;
    }

    void add(Settlement s) {
        settlements.put(s.id(), s);
        setDirty();
    }

    void remove(int id) {
        if (settlements.remove(id) != null) setDirty();
    }

    public Collection<TradeRun> runs() {
        return Collections.unmodifiableCollection(runs.values());
    }

    public TradeRun run(UUID vessel) {
        return runs.get(vessel);
    }

    void putRun(TradeRun run) {
        runs.put(run.vessel, run);
        setDirty();
    }

    void removeRun(UUID vessel) {
        if (runs.remove(vessel) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }
}
