package org.webtrade.minecraftportsmod.colony;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

/**
 * The mod's achievements: advancements of their own tab (data/minecraftportsmod/advancement/roads), each with one
 * criterion that nothing in the game sets by itself; they are given here, when the thing is done.
 */
public final class Achievements {

    private Achievements() {
    }

    /** Gives an achievement (once: the game remembers it). */
    public static void award(ServerPlayer p, String id) {
        MinecraftServer srv = p.level().getServer();
        if (srv == null) return;
        AdvancementHolder h = srv.getAdvancements().get(Minecraftportsmod.id("roads/" + id));
        if (h != null) p.getAdvancements().award(h, "done");
    }

    /** Talked to someone of a village. */
    public static void talked(ServerPlayer p) {
        award(p, "root");
    }

    public static void traded(ServerPlayer p) {
        award(p, "trade");
    }

    public static void ordered(ServerPlayer p) {
        award(p, "order");
    }

    public static void researched(ServerPlayer p) {
        award(p, "research");
    }

    public static void letter(ServerPlayer p) {
        award(p, "letter");
    }

    public static void plot(ServerPlayer p) {
        award(p, "plot");
    }

    public static void home(ServerPlayer p) {
        award(p, "home");
    }

    /** A task done for a village: by its kind, and by how many the player has done there and everywhere. */
    static void questDone(ServerPlayer p, Quests.Quest q, int forThisVillage) {
        award(p, "quest");
        switch (q.kind) {
            case HUNT -> award(p, "hunt");
            case SITE -> award(p, "site");
            case ITEM -> award(p, Quests.treat(q) ? "flower" : "boost");
            default -> {
            }
        }
        if (forThisVillage >= 10) award(p, "friend");
        int all = 0;
        for (Village v : VillageData.get(p.level().getServer()).all()) all += v.tasks.thanks(p.getUUID());
        if (all >= 10) award(p, "quests10");
        if (all >= 50) award(p, "quests50");
    }
}
