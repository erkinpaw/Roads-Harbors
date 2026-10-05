package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a player has seen at the villages' stalls: for each village, the day they were last at its stall and what it
 * asked and paid for each ware then. A trader's memory of the prices: what is cheap here is told against what was seen
 * elsewhere (and how long ago), not against prices nobody told them.
 */
public final class PriceBook extends SavedData {

    /** One village's prices as last seen: the day, and each ware's ask and bid (hundredths a piece; -1: none). */
    record Seen(long day, List<Integer> ask, List<Integer> bid) {
        static final Codec<Seen> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("day").forGetter(Seen::day),
                Codec.INT.listOf().fieldOf("ask").forGetter(Seen::ask),
                Codec.INT.listOf().fieldOf("bid").forGetter(Seen::bid)
        ).apply(i, Seen::new));
    }

    public static final Codec<PriceBook> CODEC = Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Seen.CODEC)).xmap(m -> {
        PriceBook b = new PriceBook();
        m.forEach((player, villages) -> {
            try {
                Map<Integer, Seen> book = new HashMap<>();
                villages.forEach((id, seen) -> book.put(Integer.parseInt(id), seen));
                b.books.put(UUID.fromString(player), book);
            } catch (IllegalArgumentException ignored) {
            }
        });
        return b;
    }, b -> {
        Map<String, Map<String, Seen>> m = new HashMap<>();
        b.books.forEach((player, book) -> {
            Map<String, Seen> villages = new HashMap<>();
            book.forEach((id, seen) -> villages.put(String.valueOf(id), seen));
            m.put(player.toString(), villages);
        });
        return m;
    });

    public static final SavedDataType<PriceBook> TYPE = new SavedDataType<>(Minecraftportsmod.id("price_book"), PriceBook::new, CODEC, null);

    /** Villages told of at a stall, the most recently seen first. */
    static final int SHOWN = 8;

    private final Map<UUID, Map<Integer, Seen>> books = new HashMap<>();

    public PriceBook() {
    }

    static PriceBook get(MinecraftServer srv) {
        return srv.getDataStorage().computeIfAbsent(TYPE);
    }

    /** A player at a village's stall: its prices now, written down. */
    static void see(ServerPlayer p, Village v, long day) {
        List<Integer> ask = new ArrayList<>(), bid = new ArrayList<>();
        for (Trade.Ware w : Trade.WARES) {
            ask.add(Trade.listed(w) ? Trade.sellCents(v, w) : -1);
            bid.add(Trade.listed(w) ? Trade.buyCents(v, w) : -1);
        }
        PriceBook b = get(p.level().getServer());
        b.books.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(v.id, new Seen(day, ask, bid));
        b.setDirty();
    }

    /** The other villages' prices the player has seen, the most recent first. */
    public static List<ColonyPayloads.KnownPrices> known(ServerPlayer p, VillageData data, Village here) {
        Map<Integer, Seen> book = get(p.level().getServer()).books.getOrDefault(p.getUUID(), Map.of());
        List<Map.Entry<Integer, Seen>> seen = new ArrayList<>(book.entrySet());
        seen.sort((a, b) -> Long.compare(b.getValue().day(), a.getValue().day()));
        List<ColonyPayloads.KnownPrices> out = new ArrayList<>();
        for (var e : seen) {
            Village v = data.get(e.getKey());
            if (v == null || v.id == here.id) continue;
            Seen s = e.getValue();
            out.add(new ColonyPayloads.KnownPrices(v.name, (int) Math.max(0, data.day - s.day()), toArray(s.ask()), toArray(s.bid())));
            if (out.size() >= SHOWN) break;
        }
        return out;
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
