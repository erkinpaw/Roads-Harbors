package org.webtrade.minecraftportsmod.fleet;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Hull classes a vessel goes through as it is upgraded at a port office's shipyard.
 * Each step costs {@link #upgradeCost} of {@link #upgradeItem} (paid when upgrading <em>to</em> this type).
 */
public enum VesselType {
    /** A plain boat, looks like the boat it was built from. */
    BOAT(1.0, 2, 4, 0),
    /** Bigger hull, one mast with a fore-and-aft sail. */
    SLOOP(1.4, 4, 18, 20),
    /** Two masts with square sails, stern castle, bowsprit. */
    BRIG(1.75, 12, 54, 30);

    /** Multiplier on the base cruising speed. */
    public final double speedFactor;
    public final int passengers;
    /** Item slots in the cargo hold. */
    public final int holdSlots;
    public final int upgradeCost;
    public final Item upgradeItem = Items.DIAMOND;

    VesselType(double speedFactor, int passengers, int holdSlots, int upgradeCost) {
        this.speedFactor = speedFactor;
        this.passengers = passengers;
        this.holdSlots = holdSlots;
        this.upgradeCost = upgradeCost;
    }

    public static VesselType byTier(int tier) {
        VesselType[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, tier))];
    }

    public int tier() {
        return ordinal();
    }

    /** The type this one upgrades into, or null at the top. */
    public VesselType next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.vessel_type." + name().toLowerCase());
    }

    /** Cruising speed in blocks per second (for UI). */
    public double blocksPerSecond() {
        return VesselRecord.BASE_SPEED * speedFactor * 20;
    }
}
