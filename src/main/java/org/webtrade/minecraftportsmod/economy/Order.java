package org.webtrade.minecraftportsmod.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A settlement's order for the player: bring {@link #amount} of a good it is short of, for {@link #reward}
 * emeralds. Deliveries may come in parts; each part is paid for right away.
 */
public final class Order {

    static final Codec<Order> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(o -> o.id),
            Codec.STRING.xmap(Good::byId, Good::id).fieldOf("good").forGetter(o -> o.good),
            Codec.INT.fieldOf("amount").forGetter(o -> o.amount),
            Codec.INT.optionalFieldOf("delivered", 0).forGetter(o -> o.delivered),
            Codec.INT.fieldOf("reward").forGetter(o -> o.reward),
            Codec.INT.optionalFieldOf("paid", 0).forGetter(o -> o.paid),
            Codec.LONG.fieldOf("expires").forGetter(o -> o.expires)
    ).apply(i, (id, good, amount, delivered, reward, paid, expires) -> {
        Order o = new Order(id, good, amount, reward, expires);
        o.delivered = delivered;
        o.paid = paid;
        return o;
    }));

    final int id;
    final Good good;
    final int amount;
    final int reward;
    final long expires;
    int delivered;
    int paid;

    Order(int id, Good good, int amount, int reward, long expires) {
        this.id = id;
        this.good = good;
        this.amount = amount;
        this.reward = reward;
        this.expires = expires;
    }

    public int id() {
        return id;
    }

    public Good good() {
        return good;
    }

    public int amount() {
        return amount;
    }

    public int delivered() {
        return delivered;
    }

    public int reward() {
        return reward;
    }

    public long expires() {
        return expires;
    }

    public int remaining() {
        return Math.max(0, amount - delivered);
    }
}
