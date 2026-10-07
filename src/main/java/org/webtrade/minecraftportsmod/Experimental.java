package org.webtrade.minecraftportsmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's experimental features, off unless turned on (in the title screen's "Experiments"); kept in
 * config/minecraftportsmod-experimental.json. Read by the game and its own server alike (a single player's world).
 */
public final class Experimental {

    private Experimental() {
    }

    private static Boolean duels;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("minecraftportsmod-experimental.json");
    }

    private static void load() {
        duels = false;
        try {
            if (Files.exists(file())) {
                JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                if (o.has("duels")) duels = o.get("duels").getAsBoolean();
            }
        } catch (Exception ignored) {
            duels = false;
        }
    }

    /** Duels with the villages' people. */
    public static boolean duels() {
        if (duels == null) load();
        return duels;
    }

    public static void setDuels(boolean on) {
        duels = on;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("duels", on);
            Files.writeString(file(), o.toString());
        } catch (Exception e) {
            Minecraftportsmod.LOGGER.warn("Could not save the experimental settings: {}", e.toString());
        }
    }
}
