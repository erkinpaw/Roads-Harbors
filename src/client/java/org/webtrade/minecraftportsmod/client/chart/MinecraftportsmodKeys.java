package org.webtrade.minecraftportsmod.client.chart;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

/** Key bindings of the mod (rebindable in Options → Controls). */
public final class MinecraftportsmodKeys {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Minecraftportsmod.id("ports"));
    /** Opens the nautical chart; when sitting in your vessel, lets you choose where to sail. Default: M. */
    public static final KeyMapping CHART = new KeyMapping("key.minecraftportsmod.open_chart", GLFW.GLFW_KEY_M, CATEGORY);
    /** Takes a screenshot for the world map, where the player stands. Default: F4. */
    public static final KeyMapping MAP_SHOT = new KeyMapping("key.minecraftportsmod.map_shot", GLFW.GLFW_KEY_F4, CATEGORY);

    private MinecraftportsmodKeys() {
    }

    /** The same key closes the chart again. */
    static boolean isChartKey(KeyEvent event) {
        return CHART.matches(event);
    }
}
