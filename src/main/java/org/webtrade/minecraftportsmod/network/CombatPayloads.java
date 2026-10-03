package org.webtrade.minecraftportsmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

/** A warship captain's orders, from his client. */
public final class CombatPayloads {

    private CombatPayloads() {
    }

    /**
     * @param sails     -1 take in a sail, 1 set one more, 0 as they are
     * @param rudder    -1 to port, 1 to starboard, 0 amidships
     * @param fire      -1 a broadside to port, 1 to starboard, 0 none
     * @param elevation the guns' elevation (degrees) for that broadside
     */
    public record ShipOrder(int sails, int rudder, int fire, float elevation) implements CustomPacketPayload {
        public static final Type<ShipOrder> TYPE = new Type<>(Minecraftportsmod.id("ship_order"));
        public static final StreamCodec<FriendlyByteBuf, ShipOrder> CODEC = StreamCodec.of(
                (buf, o) -> {
                    buf.writeByte(o.sails);
                    buf.writeByte(o.rudder);
                    buf.writeByte(o.fire);
                    buf.writeFloat(o.elevation);
                },
                buf -> new ShipOrder(buf.readByte(), buf.readByte(), buf.readByte(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
