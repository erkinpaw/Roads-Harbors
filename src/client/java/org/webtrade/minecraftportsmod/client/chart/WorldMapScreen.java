package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.network.WorldMapPayloads;

import java.util.ArrayList;
import java.util.List;

/**
 * The world map (the chart key, on land): the chart's land as far as it is known, the ports and their sea routes,
 * the villages and the trails between them (made stretches solid, the rest dotted), the merchants on their rounds,
 * the player's vessels and the player. Drag to move, wheel to zoom; the chart key or Esc closes it.
 */
public class WorldMapScreen extends UiScreen {

    private static final Identifier ANCHOR = Minecraftportsmod.id("textures/gui/chart/anchor.png");
    private static final Identifier COMPASS = Minecraftportsmod.id("textures/gui/chart/compass.png");
    private static final Identifier PLAYER = Minecraftportsmod.id("textures/gui/chart/player.png");
    private static final Identifier VESSEL = Minecraftportsmod.id("textures/gui/chart/vessel.png");
    private static final int TILE = ChartTileCache.TILE;
    private static final float MIN_ZOOM = 0.0625F, MAX_ZOOM = 8F;
    private static final int TRAIL = 0xFF7A4A1E, TRAIL_PLANNED = 0xB07A4A1E;

    private List<WorldMapPayloads.PortPin> ports = List.of();
    private List<WorldMapPayloads.Line> lines = List.of();
    private List<WorldMapPayloads.VillagePin> villages = List.of();
    private List<WorldMapPayloads.Mover> movers = List.of();

    private double centerX, centerZ;
    private float zoom = 0.5F;
    private boolean dragging;
    private int ticks;
    private int fx0, fy0, fx1, fy1, mx0, my0, mx1, my1;

    public WorldMapScreen(WorldMapPayloads.View data) {
        super(Component.translatable("minecraftportsmod.worldmap.title"));
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            centerX = p.getX();
            centerZ = p.getZ();
        }
        ChartTileCache.beginSession();
        update(data);
    }

    public void update(WorldMapPayloads.View data) {
        ports = data.ports();
        lines = data.lines();
        villages = data.villages();
        movers = data.movers();
    }

    /** Zooms out by a number of steps (automated tests; players scroll). */
    public void zoomOut(int steps) {
        zoom = Math.max(MIN_ZOOM, zoom / (float) Math.pow(1.25, steps));
    }

    @Override
    protected void layout() {
        int m = width < 360 ? 4 : 10;
        fx0 = m;
        fy0 = m;
        fx1 = width - m;
        fy1 = height - m;
        mx0 = fx0 + 6;
        my0 = fy0 + 22;
        mx1 = fx1 - 6;
        my1 = fy1 - 6;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ coordinates

    private float sx(double wx) {
        return (float) ((mx0 + mx1) / 2.0 + (wx - centerX) * zoom);
    }

    private float sy(double wz) {
        return (float) ((my0 + my1) / 2.0 + (wz - centerZ) * zoom);
    }

    private double wx(double x) {
        return centerX + (x - (mx0 + mx1) / 2.0) / zoom;
    }

    private double wz(double y) {
        return centerZ + (y - (my0 + my1) / 2.0) / zoom;
    }

    private boolean inMap(double x, double y) {
        return x >= mx0 && x < mx1 && y >= my0 && y < my1;
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks % 4 == 0) {
            ChartTileCache.requestVisible(Math.floorDiv((int) Math.floor(wx(mx0)), TILE), Math.floorDiv((int) Math.floor(wz(my0)), TILE),
                    Math.floorDiv((int) Math.floor(wx(mx1)), TILE), Math.floorDiv((int) Math.floor(wz(my1)), TILE),
                    Math.floorDiv((int) centerX, TILE), Math.floorDiv((int) centerZ, TILE));
        }
        // the merchants and vessels move
        if (ticks % 40 == 0 && ClientPlayNetworking.canSend(WorldMapPayloads.Request.TYPE)) {
            ClientPlayNetworking.send(new WorldMapPayloads.Request());
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ChartTileCache.uploadReady();
        Ui.frame(g, fx0, fy0, fx1, fy1);
        g.text(font, title, fx0 + 12, fy0 + 8, ChartStyle.TEXT_LIGHT, true);
        g.fill(mx0 - 2, my0 - 2, mx1 + 2, my1 + 2, ChartStyle.PARCHMENT_SHADE);
        g.fill(mx0, my0, mx1, my1, ChartStyle.PARCHMENT_DARK);

        g.enableScissor(mx0, my0, mx1, my1);
        drawTiles(g);
        drawGrid(g);
        for (WorldMapPayloads.Line l : lines) if (l.kind() == WorldMapPayloads.Line.SEA) polyline(g, l.path(), null, ChartStyle.ROUTE_FAINT, 1, 4, 3);
        for (WorldMapPayloads.Line l : lines) if (l.kind() == WorldMapPayloads.Line.TRAIL) trail(g, l);
        Object hover = null;
        for (WorldMapPayloads.PortPin p : ports) {
            int x = Math.round(sx(p.x() + 0.5)), y = Math.round(sy(p.z() + 0.5));
            if (!near(x, y)) continue;
            g.blit(RenderPipelines.GUI_TEXTURED, ANCHOR, x - 6, y - 6, 0, 0, 12, 12, 16, 16, 16, 16);
            if (zoom >= 0.25F) label(g, p.name(), x, y + 8, ChartStyle.INK_SOFT);
            if (Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7) hover = p;
        }
        for (WorldMapPayloads.VillagePin v : villages) {
            int x = Math.round(sx(v.x() + 0.5)), y = Math.round(sy(v.z() + 0.5));
            if (!near(x, y)) continue;
            Village.Level lv = Village.Level.values()[Math.max(0, Math.min(Village.Level.values().length - 1, v.level()))];
            int r = 5 + Math.min(4, v.level());
            g.fill(x - r, y - r, x + r + 1, y + r + 1, ChartStyle.INK);
            g.fill(x - r + 1, y - r + 1, x + r, y + r, 0xFFE3A12F);
            g.item(new ItemStack(lv.ordinal() == 0 ? Items.CAMPFIRE : Items.BELL), x - 8, y - 8);
            label(g, v.name(), x, y + r + 3, ChartStyle.BRASS_DARK);
            if (Math.abs(mouseX - x) <= r + 1 && Math.abs(mouseY - y) <= r + 1) hover = v;
        }
        for (WorldMapPayloads.Mover m : movers) {
            int x = Math.round(sx(m.x())), y = Math.round(sy(m.z()));
            if (!near(x, y)) continue;
            if (m.kind() == WorldMapPayloads.Mover.VESSEL) {
                g.blit(RenderPipelines.GUI_TEXTURED, VESSEL, x - 6, y - 6, 0, 0, 12, 12, 16, 16, 16, 16);
            } else {
                g.pose().pushMatrix();
                g.pose().translate(x - 6, y - 6);
                g.pose().scale(0.75F, 0.75F);
                g.item(new ItemStack(Items.EMERALD), 0, 0);
                g.pose().popMatrix();
            }
            if (Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7) hover = m;
        }
        drawPlayer(g);
        drawCompassAndScale(g);
        g.disableScissor();
        g.outline(mx0 - 1, my0 - 1, mx1 - mx0 + 2, my1 - my0 + 2, ChartStyle.INK);
        if (inMap(mouseX, mouseY)) tooltip(g, hover, mouseX, mouseY);
        widgets(g, mouseX, mouseY, partialTick);
    }

    private boolean near(int x, int y) {
        return x > mx0 - 40 && x < mx1 + 40 && y > my0 - 40 && y < my1 + 40;
    }

    private void drawTiles(GuiGraphicsExtractor g) {
        int minTx = Math.floorDiv((int) Math.floor(wx(mx0)), TILE), maxTx = Math.floorDiv((int) Math.floor(wx(mx1)), TILE);
        int minTz = Math.floorDiv((int) Math.floor(wz(my0)), TILE), maxTz = Math.floorDiv((int) Math.floor(wz(my1)), TILE);
        for (int tx = minTx; tx <= maxTx; tx++) {
            for (int tz = minTz; tz <= maxTz; tz++) {
                Identifier tex = ChartTileCache.texture(tx, tz);
                if (tex == null) continue;
                g.pose().pushMatrix();
                g.pose().translate(sx(tx * TILE), sy(tz * TILE));
                g.pose().scale(zoom, zoom);
                g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, TILE, TILE, TILE, TILE);
                g.pose().popMatrix();
            }
        }
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        int step = 64;
        while (step * zoom < 70) step *= 2;
        int color = 0x2A3B2A1C;
        for (long x = (long) Math.floor(wx(mx0) / step) * step; x <= wx(mx1); x += step) {
            int s = Math.round(sx(x));
            g.fill(s, my0, s + 1, my1, color);
        }
        for (long z = (long) Math.floor(wz(my0) / step) * step; z <= wz(my1); z += step) {
            int s = Math.round(sy(z));
            g.fill(mx0, s, mx1, s + 1, color);
        }
    }

    /** A trail: the stretches made solid, the ones still to be made dotted. */
    private void trail(GuiGraphicsExtractor g, WorldMapPayloads.Line l) {
        int[] p = l.path();
        int thick = zoom >= 1 ? 3 : 2;
        for (int i = 2; i + 1 < p.length; i += 2) {
            int s = i / 2 - 1;
            boolean made = s >= l.made().length || l.made()[s] != 0;
            line(g, sx(p[i - 2] + 0.5), sy(p[i - 1] + 0.5), sx(p[i] + 0.5), sy(p[i + 1] + 0.5), made ? thick : 1,
                    made ? TRAIL : TRAIL_PLANNED, made ? 1 : 2, made ? 0 : 3, 0);
        }
    }

    private void polyline(GuiGraphicsExtractor g, int[] p, byte[] made, int color, int thick, int on, int off) {
        float dist = 0;
        for (int i = 2; i + 1 < p.length; i += 2) {
            dist = line(g, sx(p[i - 2] + 0.5), sy(p[i - 1] + 0.5), sx(p[i] + 0.5), sy(p[i + 1] + 0.5), thick, color, on, off, dist);
        }
    }

    /** A line clipped to the map, solid ({@code off} 0) or dashed. */
    private float line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int thick, int color, int on, int off, float dist) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01F) return dist;
        if (Math.max(x0, x1) < mx0 - 2 || Math.min(x0, x1) > mx1 + 2 || Math.max(y0, y1) < my0 - 2 || Math.min(y0, y1) > my1 + 2) return dist + len;
        float period = on + off, half = thick / 2F;
        for (float s = 0; s <= len; s += 1F) {
            if (off > 0 && ((dist + s) % period) >= on) continue;
            int ix = Math.round(x0 + dx * s / len - half), iy = Math.round(y0 + dy * s / len - half);
            g.fill(ix, iy, ix + thick, iy + thick, color);
        }
        return dist + len;
    }

    private void label(GuiGraphicsExtractor g, String text, int x, int y, int border) {
        int w = font.width(text), lx = x - w / 2;
        g.fill(lx - 3, y - 1, lx + w + 3, y + 9, 0xD8EADBB5);
        g.outline(lx - 3, y - 1, w + 6, 10, border);
        g.text(font, text, lx, y, ChartStyle.TEXT, false);
    }

    private void drawPlayer(GuiGraphicsExtractor g) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        float x = sx(player.getX()), y = sy(player.getZ());
        if (!inMap(x, y)) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate((float) Math.toRadians(player.getYRot() + 180F));
        g.blit(RenderPipelines.GUI_TEXTURED, PLAYER, -6, -6, 0, 0, 12, 12, 16, 16, 16, 16);
        g.pose().popMatrix();
    }

    private void drawCompassAndScale(GuiGraphicsExtractor g) {
        int size = Math.min(56, (my1 - my0) / 4);
        if (size >= 24) g.blit(RenderPipelines.GUI_TEXTURED, COMPASS, mx0 + 6, my1 - size - 6, 0, 0, size, size, 64, 64, 64, 64);
        int[] nice = {5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};
        int blocks = nice[nice.length - 1];
        for (int n : nice) {
            if (n * zoom >= 50) {
                blocks = n;
                break;
            }
        }
        int px = Math.round(blocks * zoom);
        int x1 = mx1 - 10, x0 = x1 - px, y = my1 - 12;
        g.fill(x0 - 4, y - 13, x1 + 4, y + 7, 0xC8EADBB5);
        g.outline(x0 - 4, y - 13, px + 8, 20, ChartStyle.INK_SOFT);
        for (int i = 0; i < 4; i++) g.fill(x0 + px * i / 4, y, x0 + px * (i + 1) / 4, y + 3, i % 2 == 0 ? ChartStyle.INK : 0xFFF3E6C4);
        g.outline(x0, y, px, 3, ChartStyle.INK);
        Component label = Component.translatable("minecraftportsmod.chart.blocks", blocks);
        g.text(font, label, x0 + (px - font.width(label)) / 2, y - 10, ChartStyle.TEXT, false);
    }

    private void tooltip(GuiGraphicsExtractor g, Object hover, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        if (hover instanceof WorldMapPayloads.VillagePin v) {
            Village.Level lv = Village.Level.values()[Math.max(0, Math.min(Village.Level.values().length - 1, v.level()))];
            lines.add(Component.literal(v.name()).withStyle(ChatFormatting.GOLD));
            lines.add(Component.translatable("minecraftportsmod.vboard.level", lv.number(), lv.displayName()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("minecraftportsmod.worldmap.people", v.people()).withStyle(ChatFormatting.GRAY));
        } else if (hover instanceof WorldMapPayloads.PortPin p) {
            lines.add(Component.literal(p.name()).withStyle(ChatFormatting.AQUA));
        } else if (hover instanceof WorldMapPayloads.Mover m) {
            lines.add(Component.literal(m.name()).withStyle(ChatFormatting.GOLD));
            if (!m.home().isEmpty()) lines.add(Component.translatable("minecraftportsmod.worldmap.merchant_of", m.home()).withStyle(ChatFormatting.GRAY));
        } else {
            return;
        }
        g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && inMap(event.x(), event.y())) {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            return true;
        }
        return super.uiDrag(event, dx, dy);
    }

    @Override
    protected boolean uiRelease(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.uiRelease(event);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!inMap(mouseX, mouseY)) return super.uiScroll(mouseX, mouseY, scrollX, scrollY);
        double x = wx(mouseX), z = wz(mouseY);
        zoom = Mth.clamp((float) (zoom * Math.pow(1.25, scrollY)), MIN_ZOOM, MAX_ZOOM);
        centerX = x - (mouseX - (mx0 + mx1) / 2.0) / zoom;
        centerZ = z - (mouseY - (my0 + my1) / 2.0) / zoom;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        double pan = 48 / zoom;
        switch (key) {
            case GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_HOME -> {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) {
                    centerX = p.getX();
                    centerZ = p.getZ();
                }
            }
            case GLFW.GLFW_KEY_LEFT -> centerX -= pan;
            case GLFW.GLFW_KEY_RIGHT -> centerX += pan;
            case GLFW.GLFW_KEY_UP -> centerZ -= pan;
            case GLFW.GLFW_KEY_DOWN -> centerZ += pan;
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoom = Math.min(MAX_ZOOM, zoom * 1.25F);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoom = Math.max(MIN_ZOOM, zoom / 1.25F);
            default -> {
                if (MinecraftportsmodKeys.isChartKey(event)) {
                    onClose();
                    return true;
                }
                return super.keyPressed(event);
            }
        }
        return true;
    }
}
