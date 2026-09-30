package org.webtrade.minecraftportsmod.economy;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * What a worker does all day. The actual recipes live in {@link Simulation#work}: extractors turn the
 * settlement's deposits into raw goods, crafters turn goods from the store into better goods.
 */
public enum Profession {
    WOODCUTTER, SAWYER, MINER, SMELTER, SMITH, POTTER, MASON, FARMER, BAKER, FISHER, SHEPHERD, MERCHANT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Profession byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Component displayName() {
        return Component.translatable("minecraftportsmod.profession." + id());
    }

    /** Extractors need a deposit; they don't consume goods. */
    public boolean isExtractor() {
        return switch (this) {
            case WOODCUTTER, MINER, MASON, FARMER, FISHER, SHEPHERD -> true;
            default -> false;
        };
    }

    public boolean producesFood() {
        return this == FARMER || this == BAKER || this == FISHER || this == SHEPHERD;
    }
}
