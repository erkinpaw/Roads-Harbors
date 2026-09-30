package org.webtrade.minecraftportsmod.colony;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;

/** Bits of text about villages. */
public final class VillageText {

    private VillageText() {
    }

    /** "12 wood, 4 stone". */
    public static Component amounts(Map<Res, Integer> m) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (var e : m.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!first) out.append(", ");
            out.append(e.getKey().displayName()).append(" " + e.getValue());
            first = false;
        }
        return out;
    }
}
