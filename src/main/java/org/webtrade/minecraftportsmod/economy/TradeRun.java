package org.webtrade.minecraftportsmod.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * One trading trip of a settlement's vessel: load at home, sail to the partner, sell and buy there, sail back.
 * The goods travel as an abstract manifest (not as items in the hold).
 */
public final class TradeRun {

    public enum Phase implements StringRepresentable {
        /** Loaded at home, waiting to cast off at {@link #departAt}. */
        LOADING("loading"),
        OUTBOUND("outbound"),
        /** At the partner's port, trading until {@link #departAt}. */
        TRADING("trading"),
        RETURN("return");

        public static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);
        private final String name;

        Phase(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final Codec<TradeRun> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("vessel").forGetter(r -> r.vessel),
            Codec.INT.fieldOf("home").forGetter(r -> r.home),
            Codec.INT.fieldOf("partner").forGetter(r -> r.partner),
            Phase.CODEC.fieldOf("phase").forGetter(r -> r.phase),
            Codec.LONG.optionalFieldOf("depart_at", 0L).forGetter(r -> r.departAt),
            Settlement.goodMap(Codec.DOUBLE).optionalFieldOf("cargo", Map.of()).forGetter(r -> r.cargo),
            Settlement.goodMap(Codec.DOUBLE).optionalFieldOf("home_prices", Map.of()).forGetter(r -> r.homePrices),
            Settlement.goodMap(Codec.DOUBLE).optionalFieldOf("partner_prices", Map.of()).forGetter(r -> r.partnerPrices),
            Codec.DOUBLE.optionalFieldOf("purse", 0.0).forGetter(r -> r.purse),
            Codec.DOUBLE.optionalFieldOf("sold_for", 0.0).forGetter(r -> r.soldFor),
            Codec.LONG.optionalFieldOf("retry_at", 0L).forGetter(r -> r.retryAt),
            Settlement.goodMap(Codec.DOUBLE).optionalFieldOf("partner_wants", Map.of()).forGetter(r -> r.partnerWants),
            Codec.BOOL.optionalFieldOf("aid", false).forGetter(r -> r.aid)
    ).apply(i, (vessel, home, partner, phase, departAt, cargo, homePrices, partnerPrices, purse, soldFor, retryAt, partnerWants, aid) -> {
        TradeRun r = new TradeRun(vessel, home, partner);
        r.phase = phase;
        r.departAt = departAt;
        r.cargo.putAll(cargo);
        r.homePrices.putAll(homePrices);
        r.partnerPrices.putAll(partnerPrices);
        r.purse = purse;
        r.soldFor = soldFor;
        r.retryAt = retryAt;
        r.partnerWants.putAll(partnerWants);
        r.exported.addAll(cargo.keySet());
        r.aid = aid;
        return r;
    }));

    final UUID vessel;
    final int home;
    final int partner;
    Phase phase = Phase.LOADING;
    /** Game time (ticks) when the vessel casts off. */
    long departAt;
    final Map<Good, Double> cargo = new EnumMap<>(Good.class);
    /** Home prices when the trip began: what the merchant refuses to sell below / is willing to pay up to. */
    final Map<Good, Double> homePrices = new EnumMap<>(Good.class);
    /** Prices seen at the partner; the home settlement learns them on return. */
    final Map<Good, Double> partnerPrices = new EnumMap<>(Good.class);
    /** What the partner was short of when we left it; the home settlement learns it on return. */
    final Map<Good, Double> partnerWants = new EnumMap<>(Good.class);
    /**
     * Goods loaded at home: never bought back on the same trip (home only looks short of them because they are
     * in our hold). Not saved as such — rebuilt from the cargo, which is close enough after a restart.
     */
    final java.util.Set<Good> exported = java.util.EnumSet.noneOf(Good.class);
    /** Emeralds carried: the takings of the outbound cargo minus what was spent on the return cargo. */
    double purse;
    double soldFor;
    /** A failed departure is retried after this game time. */
    long retryAt;
    /** Food sent to a starving neighbour: handed over for half price (or free if they can't pay). */
    boolean aid;
    /** Transient: a departure is being planned. */
    boolean dispatching;

    TradeRun(UUID vessel, int home, int partner) {
        this.vessel = vessel;
        this.home = home;
        this.partner = partner;
    }

    public UUID vessel() {
        return vessel;
    }

    public int home() {
        return home;
    }

    public int partner() {
        return partner;
    }

    public boolean aid() {
        return aid;
    }

    public Phase phase() {
        return phase;
    }

    /** Game time the vessel casts off (loading or trading in port). */
    public long departAt() {
        return departAt;
    }

    public Map<Good, Double> cargo() {
        return java.util.Collections.unmodifiableMap(cargo);
    }

    public double cargoUnits() {
        double n = 0;
        for (double v : cargo.values()) n += v;
        return n;
    }
}
