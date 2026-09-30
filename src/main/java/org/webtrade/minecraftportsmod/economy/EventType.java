package org.webtrade.minecraftportsmod.economy;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Things that happen to settlements and ripple through the trade network: a poor harvest drives the price of
 * grain up everywhere its merchants buy, a fire empties a lumber yard, a new vein makes a mining town rich.
 */
public enum EventType {
    /** Grain grows poorly: wheat output down to 40%. */
    POOR_HARVEST(6, 0.05),
    /** Bumper harvest: wheat output doubled. */
    GOOD_HARVEST(5, 0.04),
    /** The fish come close to the shore: catch doubled. */
    RICH_CATCH(4, 0.05),
    /** Sickness among the sheep: wool and mutton down to 30%. */
    SHEEP_PLAGUE(6, 0.03),
    /** Fire in the stores: a third of the wood burns (instant). */
    FIRE(0, 0.02),
    /** A new vein is found: ore deposits richer for good (instant). */
    NEW_VEIN(0, 0.02),
    /** Illness goes round: everyone works at 70%. */
    SICKNESS(4, 0.03),
    /** A fair: comforts wanted three times as much, people cheerful. */
    FAIR(3, 0.04),
    /** Storms: the settlement's ships stay in port. */
    STORM(2, 0.04);

    /** How many days it lasts (0 = happens at once). */
    public final int days;
    /** Daily chance per settlement. */
    public final double chance;

    EventType(int days, double chance) {
        this.days = days;
        this.chance = chance;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EventType byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.event." + id());
    }

    /** Does it make sense for this settlement? (no poor harvest where nobody farms...) */
    boolean fits(Settlement s) {
        return switch (this) {
            case POOR_HARVEST, GOOD_HARVEST -> s.workers(Profession.FARMER) > 0;
            case RICH_CATCH -> s.workers(Profession.FISHER) > 0;
            case SHEEP_PLAGUE -> s.workers(Profession.SHEPHERD) > 0;
            case FIRE -> s.stock(Good.OAK_LOG) + s.stock(Good.OAK_PLANKS) + s.stock(Good.SPRUCE_LOG) > 40
                    || s.spec() == Specialization.LUMBER;
            case NEW_VEIN -> s.workers(Profession.MINER) > 0;
            case SICKNESS, STORM -> true;
            case FAIR -> s.level().ordinal() >= Settlement.Level.VILLAGE.ordinal();
        };
    }

    /** Production multiplier this event puts on a good while it lasts. */
    double production(Good g) {
        return switch (this) {
            case POOR_HARVEST -> g == Good.WHEAT ? 0.4 : 1;
            case GOOD_HARVEST -> g == Good.WHEAT ? 2.0 : 1;
            case RICH_CATCH -> g == Good.FISH ? 2.0 : 1;
            case SHEEP_PLAGUE -> g == Good.WOOL || g == Good.MUTTON ? 0.3 : 1;
            case SICKNESS -> 0.7;
            default -> 1;
        };
    }
}
