package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * A player's purse: every emerald that comes into the inventory goes into it (an emerald block, nine), and everything
 * the villages are paid with is paid out of it. It is kept in hundredths of an emerald: the stall's prices are, and a
 * few logs cost less than an emerald. Kept with the world; what the player has is shown in the inventory's panel (see
 * {@link #panel}).
 */
public final class Wallet extends SavedData {

    /** The purses in hundredths; (the old form, before hundredths: whole emeralds). */
    public static final Codec<Wallet> CODEC = Codec.withAlternative(
            Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("cents").codec().xmap(m -> read(m, 1), w -> {
                Map<String, Long> m = new HashMap<>();
                w.purses.forEach((k, v) -> m.put(k.toString(), v));
                return m;
            }),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(m -> read(m, 100), w -> Map.of()));

    private static Wallet read(Map<String, Long> m, long times) {
        Wallet w = new Wallet();
        m.forEach((k, v) -> {
            try {
                w.purses.put(UUID.fromString(k), v * times);
            } catch (IllegalArgumentException ignored) {
            }
        });
        return w;
    }

    public static final SavedDataType<Wallet> TYPE = new SavedDataType<>(Minecraftportsmod.id("wallets"), Wallet::new, CODEC, null);

    private final Map<UUID, Long> purses = new HashMap<>();

    public Wallet() {
    }

    static Wallet get(MinecraftServer srv) {
        return srv.getDataStorage().computeIfAbsent(TYPE);
    }

    /** What a player has in the purse, in hundredths of an emerald. */
    public static long cents(ServerPlayer p) {
        return get(p.level().getServer()).purses.getOrDefault(p.getUUID(), 0L);
    }

    /** The whole emeralds a player has (in the purse). */
    public static int balance(ServerPlayer p) {
        return (int) Math.min(Integer.MAX_VALUE, cents(p) / 100);
    }

    public static void add(ServerPlayer p, int n) {
        addCents(p, n * 100L);
    }

    public static void addCents(ServerPlayer p, long cents) {
        if (cents <= 0) return;
        Wallet w = get(p.level().getServer());
        w.purses.merge(p.getUUID(), cents, Long::sum);
        w.setDirty();
    }

    /** Pays {@code n} emeralds out of the purse; false (and nothing paid) if there is not so much in it. */
    public static boolean pay(ServerPlayer p, int n) {
        return payCents(p, n * 100L);
    }

    /** Pays hundredths out of the purse; false (and nothing paid) if there is not so much in it. */
    public static boolean payCents(ServerPlayer p, long cents) {
        if (cents <= 0) return true;
        // (what was just picked up counts too)
        absorb(p);
        Wallet w = get(p.level().getServer());
        long have = w.purses.getOrDefault(p.getUUID(), 0L);
        if (have < cents) return false;
        w.purses.put(p.getUUID(), have - cents);
        w.setDirty();
        return true;
    }

    /** The emeralds in a player's inventory into the purse. */
    public static void absorb(ServerPlayer p) {
        var inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.EMERALD)) n += s.getCount();
            else if (s.is(Items.EMERALD_BLOCK)) n += 9 * s.getCount();
            else continue;
            inv.setItem(i, ItemStack.EMPTY);
        }
        if (n > 0) {
            inv.setChanged();
            add(p, n);
        }
    }

    /** Every half second: each player's emeralds into the purse. */
    static void tick(MinecraftServer srv) {
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) absorb(p);
    }

    // ------------------------------------------------------------------ the inventory's panel

    /** What the inventory's panel shows a player: the purse, the tasks taken (how far along), the plot, the trade hired at. */
    public static void panel(ServerPlayer p) {
        absorb(p);
        VillageData data = VillageData.get(p.level().getServer());
        List<ColonyPayloads.PanelQuest> quests = new ArrayList<>();
        Component plot = Component.empty(), hired = Component.empty();
        for (Village v : data.all()) {
            for (Quests.Quest q : v.tasks.quests()) {
                if (!p.getUUID().equals(q.taker())) continue;
                Dweller giver = v.dweller(q.giver);
                int carried = Math.max(0, Quests.carried(p, q));
                int have = Math.min(q.count, q.done() + carried);
                Village to = data.get(q.to());
                quests.add(new ColonyPayloads.PanelQuest(Quests.icon(q), Quests.thing(q, to), giver == null ? "?" : giver.name, v.name, have, q.count,
                        (int) Math.max(0, q.until() - data.day), q.kind.ordinal(), ColonyService.questText(data, v, q),
                        giver == null || giver.job == null ? Component.empty() : giver.job.displayName(), q.done(), carried, q.reward(),
                        to == null ? "" : to.name));
            }
            Plots.Plot mine = Plots.of(v, p.getUUID());
            if (mine != null) plot = Component.literal(v.name + " " + mine.marker().toShortString());
            Job j = Helping.hired(v, p.getUUID());
            if (j != null) hired = Component.empty().append(j.displayName()).append(" · " + v.name + " · " + Helping.brought(v, p.getUUID()) + "/"
                    + Helping.quota(v, j));
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new ColonyPayloads.PanelView(cents(p), quests, plot, hired));
    }
}
