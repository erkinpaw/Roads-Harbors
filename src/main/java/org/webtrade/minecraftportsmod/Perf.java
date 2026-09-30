package org.webtrade.minecraftportsmod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

/** Logs any piece of our server-thread work that takes long enough to be felt as a stutter. */
public final class Perf {

    /** Work slower than this (ms) in one go is logged. */
    public static final long WARN_MS = 20;

    private Perf() {
    }

    /** Wraps a tick handler so that slow ticks are logged under {@code name}. */
    public static ServerTickEvents.EndTick timed(String name, Consumer<MinecraftServer> handler) {
        return srv -> {
            long t0 = System.nanoTime();
            handler.accept(srv);
            report(name, t0);
        };
    }

    /** Logs {@code name} if more than {@link #WARN_MS} passed since {@code t0} (from {@link System#nanoTime()}). */
    public static void report(String name, long t0) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= WARN_MS) Minecraftportsmod.LOGGER.warn("[perf] {} took {} ms", name, ms);
    }
}
