package org.webtrade.minecraftportsmod.client.chart;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The scale the mod's screens are drawn at, apart from the game's own GUI scale: finer (more fits, text and icons
 * sharper) unless the window is too small for it. 0 is "auto"; kept in config/minecraftportsmod-ui.json.
 */
final class UiConfig {

    /** The smallest room a screen is laid out in (in its own pixels). */
    static final int MIN_W = 520, MIN_H = 320;

    private static int scale = -1;

    private UiConfig() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("minecraftportsmod-ui.json");
    }

    static int scale() {
        if (scale < 0) {
            scale = 0;
            try {
                if (Files.exists(file())) {
                    JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                    if (o.has("scale")) scale = Math.max(0, Math.min(6, o.get("scale").getAsInt()));
                }
            } catch (Exception ignored) {
                scale = 0;
            }
        }
        return scale;
    }

    static void setScale(int s) {
        scale = Math.max(0, Math.min(6, s));
        try {
            JsonObject o = new JsonObject();
            o.addProperty("scale", scale);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {
        }
    }

    /** The largest scale the window has room for (a screen needs MIN_W × MIN_H of its own pixels). */
    static int maxScale(Window w) {
        return Math.max(1, Math.min(w.getGuiScale(), Math.min(w.getWidth() / MIN_W, w.getHeight() / MIN_H)));
    }

    /** The scale to draw at: the one chosen, or by the window's height (about 480 of its own pixels high). */
    static int effective(Window w) {
        int auto = Math.max(1, Math.round(w.getHeight() / 480f));
        int s = scale() == 0 ? auto : scale();
        return Math.max(1, Math.min(s, maxScale(w)));
    }
}
