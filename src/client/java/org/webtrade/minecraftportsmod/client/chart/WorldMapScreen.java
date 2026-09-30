package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The world map (the chart key, on land): the chart's land as far as it is known, the ports and their sea routes,
 * the villages and the trails between them (made stretches solid, the rest dotted), the merchants on their rounds,
 * the player's vessels, the player; and the players' marks with their screenshots. A right click puts a mark down;
 * a click on one opens it at the right: its name, where and when, its screenshots two to a row and a "+" for more.
 * The layers are shown or hidden with the buttons at the top.
 */
public class WorldMapScreen extends UiScreen {

    private static final Identifier ANCHOR = Minecraftportsmod.id("textures/gui/chart/anchor.png");
    private static final Identifier COMPASS = Minecraftportsmod.id("textures/gui/chart/compass.png");
    private static final Identifier PLAYER = Minecraftportsmod.id("textures/gui/chart/player.png");
    private static final Identifier VESSEL = Minecraftportsmod.id("textures/gui/chart/vessel.png");
    private static final int TILE = ChartTileCache.TILE;
    private static final float MIN_ZOOM = 0.0625F, MAX_ZOOM = 8F;
    private static final int TRAIL = 0xFF7A4A1E, TRAIL_PLANNED = 0xB07A4A1E;
    private static final int PANEL = 200;
    /** The land nobody has seen: as the chart's tiles have it (their hatching, from afar). */
    private static final int UNKNOWN = 0xFFE2D0A3;

    /** The layers: villages, trails, ports and sea routes, merchants and vessels, marks (kept while the game runs). */
    private static final boolean[] SHOWN = {true, true, true, true, true};
    private static final int VILLAGES = 0, TRAILS = 1, PORTS = 2, MOVERS = 3, MARKS = 4;

    private List<WorldMapPayloads.PortPin> ports = List.of();
    private List<WorldMapPayloads.Line> lines = List.of();
    private List<WorldMapPayloads.VillagePin> villages = List.of();
    private List<WorldMapPayloads.Mover> movers = List.of();
    private List<WorldMapPayloads.MarkPin> marks = List.of();
    private List<WorldMapPayloads.ShotPin> shots = List.of();

    private double centerX, centerZ;
    private float zoom = 0.5F;
    private boolean dragging;
    private double dragMoved;
    private int ticks;
    private int fx0, fy0, fx1, fy1, mx0, my0, mx1, my1, px0;

    /** The mark open at the right (-1: none); a new one put down, to open as soon as it is back from the server. */
    private int selected = -1;
    private int[] placed;
    private EditBox nameBox;
    private String sentName;
    private int nameChanged = -1;
    private boolean confirmDelete;
    private int panelScroll;
    /** The screenshot shown large (-1: none). */
    private int viewing = -1;

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
        marks = data.marks();
        shots = data.shots();
        int before = selected;
        if (placed != null) {
            for (WorldMapPayloads.MarkPin m : marks) {
                if (m.mine() && m.x() == placed[0] && m.z() == placed[1] && m.id() > selected) {
                    selected = m.id();
                }
            }
            if (selected != before) placed = null;
        }
        if (selected >= 0 && mark(selected) == null) selected = -1;
        if (selected != before && minecraft != null) {
            rebuildWidgets();
            if (nameBox != null) setFocused(nameBox);
        }
    }

    /** Zooms out by a number of steps (automated tests; players scroll). */
    public void zoomOut(int steps) {
        zoom = Math.max(MIN_ZOOM, zoom / (float) Math.pow(1.25, steps));
    }

    /** Opens a mark at the right (automated tests; players click it). */
    public void open(int id) {
        selected = id;
        rebuildWidgets();
    }

    /** Shows a screenshot large (automated tests; players click it). */
    public void view(int shot) {
        viewing = shot;
    }

    /** Shows or hides a layer (automated tests; players use the buttons). */
    public void toggle(int layer) {
        SHOWN[layer] = !SHOWN[layer];
    }

    public List<WorldMapPayloads.MarkPin> marks() {
        return marks;
    }

    private WorldMapPayloads.MarkPin mark(int id) {
        for (WorldMapPayloads.MarkPin m : marks) if (m.id() == id) return m;
        return null;
    }

    private WorldMapPayloads.ShotPin shot(int id) {
        for (WorldMapPayloads.ShotPin s : shots) if (s.id() == id) return s;
        return null;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void layout() {
        int m = width < 360 ? 4 : 10;
        fx0 = m;
        fy0 = m;
        fx1 = width - m;
        fy1 = height - m;
        mx0 = fx0 + 6;
        my0 = fy0 + 22;
        my1 = fy1 - 6;
        WorldMapPayloads.MarkPin open = mark(selected);
        mx1 = open != null ? fx1 - 6 - PANEL - 6 : fx1 - 6;
        px0 = mx1 + 6;
        nameBox = null;
        confirmDelete = false;
        if (open != null && open.mine()) {
            nameBox = addRenderableWidget(new EditBox(font, px0 + 8, my0 + 8, PANEL - 16, 12, Component.translatable("minecraftportsmod.worldmap.name")));
            nameBox.setBordered(false);
            nameBox.setTextColor(ChartStyle.TEXT);
            nameBox.setMaxLength(40);
            nameBox.setValue(open.name());
            nameBox.setHint(Component.translatable("minecraftportsmod.worldmap.name").withStyle(ChatFormatting.GRAY));
            sentName = open.name();
            nameBox.setResponder(s -> nameChanged = ticks);
            addRenderableWidget(UiButton.make(Component.translatable("minecraftportsmod.worldmap.delete"), b -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    b.setMessage(Component.translatable("minecraftportsmod.worldmap.delete_sure"));
                    return;
                }
                act(WorldMapPayloads.MarkAction.DELETE, selected, 0, 0, "", "");
                selected = -1;
                rebuildWidgets();
            }).bounds(px0 + 4, my1 - 20, PANEL - 8, 16).build());
        }
    }

    @Override
    public void removed() {
        saveName();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void act(int action, int id, int x, int z, String icon, String name) {
        if (ClientPlayNetworking.canSend(WorldMapPayloads.MarkAction.TYPE)) {
            ClientPlayNetworking.send(new WorldMapPayloads.MarkAction(action, id, x, z, icon, name));
        }
    }

    private void saveName() {
        WorldMapPayloads.MarkPin open = mark(selected);
        if (nameBox == null || open == null || nameBox.getValue().equals(sentName)) return;
        sentName = nameBox.getValue();
        act(WorldMapPayloads.MarkAction.EDIT, open.id(), 0, 0, open.icon(), sentName);
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
        // (a name typed in goes to the server when the typing stops)
        if (nameChanged >= 0 && ticks - nameChanged > 15) {
            nameChanged = -1;
            saveName();
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
        Component layerTip = layerButtons(g, mouseX, mouseY);
        g.fill(mx0 - 2, my0 - 2, mx1 + 2, my1 + 2, ChartStyle.PARCHMENT_SHADE);
        g.fill(mx0, my0, mx1, my1, UNKNOWN);

        g.enableScissor(mx0, my0, mx1, my1);
        drawTiles(g);
        drawGrid(g);
        if (SHOWN[PORTS]) for (WorldMapPayloads.Line l : lines) if (l.kind() == WorldMapPayloads.Line.SEA) polyline(g, l.path(), ChartStyle.ROUTE_FAINT, 1, 4, 3);
        if (SHOWN[TRAILS]) for (WorldMapPayloads.Line l : lines) if (l.kind() == WorldMapPayloads.Line.TRAIL) trail(g, l);
        Object hover = null;
        if (SHOWN[PORTS]) {
            for (WorldMapPayloads.PortPin p : ports) {
                int x = Math.round(sx(p.x() + 0.5)), y = Math.round(sy(p.z() + 0.5));
                if (!near(x, y)) continue;
                g.blit(RenderPipelines.GUI_TEXTURED, ANCHOR, x - 6, y - 6, 0, 0, 12, 12, 16, 16, 16, 16);
                if (zoom >= 0.25F) label(g, p.name(), x, y + 8, ChartStyle.INK_SOFT);
                if (Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7) hover = p;
            }
        }
        if (SHOWN[VILLAGES]) {
            for (WorldMapPayloads.VillagePin v : villages) {
                int x = Math.round(sx(v.x() + 0.5)), y = Math.round(sy(v.z() + 0.5));
                if (!near(x, y)) continue;
                int r = 5 + Math.min(4, v.level());
                g.fill(x - r, y - r, x + r + 1, y + r + 1, ChartStyle.INK);
                g.fill(x - r + 1, y - r + 1, x + r, y + r, 0xFFE3A12F);
                g.item(new ItemStack(v.level() == 0 ? Items.CAMPFIRE : Items.BELL), x - 8, y - 8);
                label(g, v.name(), x, y + r + 3, ChartStyle.BRASS_DARK);
                if (Math.abs(mouseX - x) <= r + 1 && Math.abs(mouseY - y) <= r + 1) hover = v;
            }
        }
        if (SHOWN[MOVERS]) {
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
        }
        if (SHOWN[MARKS]) {
            for (WorldMapPayloads.MarkPin m : marks) {
                int x = Math.round(sx(m.x() + 0.5)), y = Math.round(sy(m.z() + 0.5));
                if (!near(x, y)) continue;
                boolean hot = Math.abs(mouseX - x) <= 8 && Math.abs(mouseY - y) <= 8;
                if (m.id() == selected) {
                    float pulse = 0.75F + 0.25F * Mth.sin(ticks * 0.25F);
                    int s = Math.round(22 * pulse);
                    g.blit(RenderPipelines.GUI_TEXTURED, Minecraftportsmod.id("textures/gui/chart/ring.png"), x - s / 2, y - s / 2, 0, 0, s, s, 32, 32, 32, 32);
                }
                int s = hot ? 16 : 14;
                g.blit(RenderPipelines.GUI_TEXTURED, icon(m.icon()), x - s / 2, y - s / 2, 0, 0, s, s, 16, 16, 16, 16);
                if (!m.name().isEmpty() && (zoom >= 0.25F || hot || m.id() == selected)) label(g, m.name(), x, y + 9, ChartStyle.INK);
                if (hot) hover = m;
            }
        }
        drawPlayer(g);
        drawCompassAndScale(g);
        g.disableScissor();
        g.outline(mx0 - 1, my0 - 1, mx1 - mx0 + 2, my1 - my0 + 2, ChartStyle.INK);
        if (mark(selected) != null) drawPanel(g, mouseX, mouseY);
        widgets(g, mouseX, mouseY, partialTick);
        if (viewing >= 0) {
            drawViewer(g);
        } else if (layerTip != null) {
            g.setComponentTooltipForNextFrame(font, List.of(layerTip), mouseX, mouseY);
        } else if (inMap(mouseX, mouseY)) {
            tooltip(g, hover, mouseX, mouseY);
        }
    }

    private static Identifier icon(String name) {
        return Minecraftportsmod.id("textures/gui/chart/mark/" + name + ".png");
    }

    private boolean near(int x, int y) {
        return x > mx0 - 40 && x < mx1 + 40 && y > my0 - 40 && y < my1 + 40;
    }

    /** The layer buttons at the top right of the frame; returns the tooltip of the one pointed at. */
    private Component layerButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Component tip = null;
        for (int i = 0; i < SHOWN.length; i++) {
            int[] r = layerRect(i);
            boolean hot = mouseX >= r[0] && mouseX < r[0] + 16 && mouseY >= r[1] && mouseY < r[1] + 16;
            g.fill(r[0], r[1], r[0] + 16, r[1] + 16, SHOWN[i] ? (hot ? 0xFFF3E6C4 : 0xFFEADBB5) : (hot ? 0xFF9A8A6A : 0xFF7A6A4A));
            g.outline(r[0], r[1], 16, 16, ChartStyle.INK);
            switch (i) {
                case VILLAGES -> g.item(new ItemStack(Items.BELL), r[0], r[1]);
                case TRAILS -> g.item(new ItemStack(Items.DIRT_PATH), r[0], r[1]);
                case PORTS -> g.blit(RenderPipelines.GUI_TEXTURED, ANCHOR, r[0] + 2, r[1] + 2, 0, 0, 12, 12, 16, 16, 16, 16);
                case MOVERS -> g.item(new ItemStack(Items.EMERALD), r[0], r[1]);
                default -> g.blit(RenderPipelines.GUI_TEXTURED, icon("flag"), r[0], r[1], 0, 0, 16, 16, 16, 16, 16, 16);
            }
            if (!SHOWN[i]) g.fill(r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15, 0x90302010);
            if (hot) tip = Component.translatable("minecraftportsmod.worldmap.layer." + i);
        }
        return tip;
    }

    private int[] layerRect(int i) {
        return new int[]{fx1 - 10 - (SHOWN.length - i) * 18, fy0 + 3};
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

    private void polyline(GuiGraphicsExtractor g, int[] p, int color, int thick, int on, int off) {
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
        List<Component> tip = new ArrayList<>();
        if (hover instanceof WorldMapPayloads.VillagePin v) {
            Village.Level lv = Village.Level.values()[Math.max(0, Math.min(Village.Level.values().length - 1, v.level()))];
            tip.add(Component.literal(v.name()).withStyle(ChatFormatting.GOLD));
            tip.add(Component.translatable("minecraftportsmod.vboard.level", lv.number(), lv.displayName()).withStyle(ChatFormatting.GRAY));
            tip.add(Component.translatable("minecraftportsmod.worldmap.people", v.people()).withStyle(ChatFormatting.GRAY));
        } else if (hover instanceof WorldMapPayloads.PortPin p) {
            tip.add(Component.literal(p.name()).withStyle(ChatFormatting.AQUA));
        } else if (hover instanceof WorldMapPayloads.Mover m) {
            tip.add(Component.literal(m.name()).withStyle(ChatFormatting.GOLD));
            if (!m.home().isEmpty()) tip.add(Component.translatable("minecraftportsmod.worldmap.merchant_of", m.home()).withStyle(ChatFormatting.GRAY));
        } else if (hover instanceof WorldMapPayloads.MarkPin m) {
            if (!m.name().isEmpty()) tip.add(Component.literal(m.name()).withStyle(ChatFormatting.GOLD));
            tip.add(Component.literal(m.owner()).withStyle(ChatFormatting.GRAY));
        } else {
            return;
        }
        g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ the mark at the right

    private static final int COLS = 2, GAP = 4;

    private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        WorldMapPayloads.MarkPin m = mark(selected);
        int x0 = px0, x1 = fx1 - 6;
        g.fill(x0 - 2, my0 - 2, x1 + 2, my1 + 2, ChartStyle.PARCHMENT_SHADE);
        g.fill(x0, my0, x1, my1, ChartStyle.PARCHMENT);
        g.outline(x0 - 1, my0 - 1, x1 - x0 + 2, my1 - my0 + 2, ChartStyle.INK);
        int y = my0 + 4;
        if (nameBox == null) {
            g.text(font, m.name().isEmpty() ? "—" : m.name(), x0 + 5, y + 4, ChartStyle.TEXT, false);
        } else {
            g.fill(x0 + 4, y, x1 - 4, y + 16, nameBox.isFocused() ? 0xFFF3E6C4 : ChartStyle.PARCHMENT_DARK);
            g.outline(x0 + 4, y, x1 - x0 - 8, 16, nameBox.isFocused() ? ChartStyle.BRASS_DARK : ChartStyle.INK_SOFT);
        }
        y += 20;
        g.text(font, "X " + m.x() + "   Z " + m.z(), x0 + 5, y, ChartStyle.TEXT_MUTED, false);
        String when = new SimpleDateFormat("dd.MM HH:mm").format(new Date(m.time()));
        g.text(font, when, x1 - 5 - font.width(when), y, ChartStyle.TEXT_MUTED, false);
        y += 11;
        if (!m.mine()) {
            g.text(font, m.owner(), x0 + 5, y, ChartStyle.TEXT_MUTED, false);
            y += 11;
        }
        // its icon (the owner picks another here)
        if (m.mine()) {
            int per = (x1 - x0 - 8) / 18;
            List<String> icons = org.webtrade.minecraftportsmod.chart.MapMarks.ICONS;
            for (int i = 0; i < icons.size(); i++) {
                int ix = x0 + 4 + (i % per) * 18, iy = y + (i / per) * 18;
                boolean on = icons.get(i).equals(m.icon());
                boolean hot = mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16;
                if (on || hot) g.fill(ix - 1, iy - 1, ix + 17, iy + 17, on ? ChartStyle.BRASS : 0x40000000);
                g.blit(RenderPipelines.GUI_TEXTURED, icon(icons.get(i)), ix, iy, 0, 0, 16, 16, 16, 16, 16, 16);
            }
            y += ((icons.size() + per - 1) / per) * 18 + 4;
        }
        Ui.rule(g, x0 + 4, x1 - 4, y);
        y += 5;
        // the screenshots, two to a row, and a "+" at the end
        int bottom = m.mine() ? my1 - 24 : my1 - 4;
        g.enableScissor(x0, y, x1, bottom);
        int[] shotIds = m.shots();
        int tw = (x1 - x0 - 8 - GAP) / COLS, th = tw * 9 / 16;
        int n = shotIds.length + (m.mine() ? 1 : 0);
        for (int i = 0; i < n; i++) {
            int tx = x0 + 4 + (i % COLS) * (tw + GAP), ty = y + (i / COLS) * (th + GAP) - panelScroll;
            boolean hot = mouseX >= tx && mouseX < tx + tw && mouseY >= ty && mouseY < ty + th && mouseY >= y && mouseY < bottom;
            g.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, hot ? ChartStyle.BRASS : ChartStyle.INK_SOFT);
            if (i < shotIds.length) {
                Shots.Picture p = Shots.picture(shotIds[i]);
                if (p != null) g.blit(RenderPipelines.GUI_TEXTURED, p.id(), tx, ty, 0, 0, tw, th, p.width(), p.height(), p.width(), p.height());
                else g.fill(tx, ty, tx + tw, ty + th, ChartStyle.PARCHMENT_SHADE);
            } else {
                g.fill(tx, ty, tx + tw, ty + th, hot ? 0xFFF3E6C4 : ChartStyle.PARCHMENT_DARK);
                g.blit(RenderPipelines.GUI_TEXTURED, Minecraftportsmod.id("textures/gui/chart/icon_plus.png"), tx + tw / 2 - 6, ty + th / 2 - 6, 0, 0, 12, 12, 12, 12, 12, 12);
            }
        }
        g.disableScissor();
    }

    /** The first row of screenshots in the panel (below the name, the place and the icons). */
    private int shotsTop(WorldMapPayloads.MarkPin m) {
        int y = my0 + 4 + 20 + 11 + (m.mine() ? 0 : 11);
        if (m.mine()) {
            int per = (fx1 - 6 - px0 - 8) / 18;
            y += ((org.webtrade.minecraftportsmod.chart.MapMarks.ICONS.size() + per - 1) / per) * 18 + 4;
        }
        return y + 5;
    }

    private boolean panelClick(double mx, double my) {
        WorldMapPayloads.MarkPin m = mark(selected);
        if (m == null || mx < px0 || mx >= fx1 - 6 || my < my0 || my >= my1) return false;
        int x0 = px0, x1 = fx1 - 6;
        if (m.mine()) {
            int per = (x1 - x0 - 8) / 18;
            int iy0 = my0 + 4 + 20 + 11;
            List<String> icons = org.webtrade.minecraftportsmod.chart.MapMarks.ICONS;
            for (int i = 0; i < icons.size(); i++) {
                int ix = x0 + 4 + (i % per) * 18, iy = iy0 + (i / per) * 18;
                if (mx >= ix && mx < ix + 16 && my >= iy && my < iy + 16) {
                    act(WorldMapPayloads.MarkAction.EDIT, m.id(), 0, 0, icons.get(i), nameBox == null ? m.name() : nameBox.getValue());
                    return true;
                }
            }
        }
        int y = shotsTop(m), bottom = m.mine() ? my1 - 24 : my1 - 4;
        int tw = (x1 - x0 - 8 - GAP) / COLS, th = tw * 9 / 16;
        int[] shotIds = m.shots();
        int n = shotIds.length + (m.mine() ? 1 : 0);
        for (int i = 0; i < n; i++) {
            int tx = x0 + 4 + (i % COLS) * (tw + GAP), ty = y + (i / COLS) * (th + GAP) - panelScroll;
            if (mx >= tx && mx < tx + tw && my >= ty && my < ty + th && my >= y && my < bottom) {
                if (i < shotIds.length) viewing = shotIds[i];
                else Minecraft.getInstance().gui.setScreen(new ShotPickerScreen(this, m.id()));
                return true;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ a screenshot shown large

    private void drawViewer(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, height, 0xE0100A05);
        Shots.Picture p = Shots.picture(viewing);
        int maxW = width - 40, maxH = height - 50;
        if (p != null) {
            float s = Math.min(maxW / (float) p.width(), maxH / (float) p.height());
            int w = Math.round(p.width() * s), h = Math.round(p.height() * s);
            int x = (width - w) / 2, y = (height - h) / 2 - 6;
            g.fill(x - 2, y - 2, x + w + 2, y + h + 2, ChartStyle.PARCHMENT_SHADE);
            g.blit(RenderPipelines.GUI_TEXTURED, p.id(), x, y, 0, 0, w, h, p.width(), p.height(), p.width(), p.height());
        }
        WorldMapPayloads.ShotPin s = shot(viewing);
        if (s != null) {
            String line = s.owner() + "   " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(s.time())) + "   X " + s.x() + "  Z " + s.z();
            g.text(font, line, (width - font.width(line)) / 2, height - 18, ChartStyle.PARCHMENT_SHADE, false);
        }
    }

    private void step(int d) {
        WorldMapPayloads.MarkPin m = mark(selected);
        if (m == null) return;
        int[] ids = m.shots();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == viewing) {
                viewing = ids[Math.floorMod(i + d, ids.length)];
                return;
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (viewing >= 0) {
            // a click on the left half: the one before; on the right: the next; outside the picture: back to the map
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) step(event.x() < width / 2.0 ? -1 : 1);
            else viewing = -1;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (int i = 0; i < SHOWN.length; i++) {
                int[] r = layerRect(i);
                if (event.x() >= r[0] && event.x() < r[0] + 16 && event.y() >= r[1] && event.y() < r[1] + 16) {
                    SHOWN[i] = !SHOWN[i];
                    return true;
                }
            }
        }
        if (super.uiClick(event, doubleClick)) return true;
        if (panelClick(event.x(), event.y())) return true;
        if (!inMap(event.x(), event.y())) return false;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // a new mark here
            int x = (int) Math.floor(wx(event.x())), z = (int) Math.floor(wz(event.y()));
            placed = new int[]{x, z};
            SHOWN[MARKS] = true;
            act(WorldMapPayloads.MarkAction.CREATE, 0, x, z, "flag", "");
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            dragMoved = 0;
            return true;
        }
        return false;
    }

    @Override
    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            dragMoved += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.uiDrag(event, dx, dy);
    }

    @Override
    protected boolean uiRelease(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            if (dragMoved < 3) {
                // a click, not a drag: the mark there opens (or the open one closes)
                int hit = -1;
                if (SHOWN[MARKS]) {
                    for (WorldMapPayloads.MarkPin m : marks) {
                        if (Math.abs(event.x() - sx(m.x() + 0.5)) <= 8 && Math.abs(event.y() - sy(m.z() + 0.5)) <= 8) hit = m.id();
                    }
                }
                if (hit != selected) {
                    saveName();
                    selected = hit;
                    panelScroll = 0;
                    rebuildWidgets();
                }
            }
            return true;
        }
        return super.uiRelease(event);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (viewing >= 0) {
            step(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (mark(selected) != null && mouseX >= px0 && mouseX < fx1 - 6) {
            panelScroll = Math.max(0, panelScroll - (int) (scrollY * 24));
            return true;
        }
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
        if (viewing >= 0) {
            switch (key) {
                case GLFW.GLFW_KEY_LEFT -> step(-1);
                case GLFW.GLFW_KEY_RIGHT -> step(1);
                case GLFW.GLFW_KEY_DELETE -> {
                    WorldMapPayloads.MarkPin m = mark(selected);
                    if (m != null && m.mine()) {
                        act(WorldMapPayloads.MarkAction.DELETE_SHOT, viewing, 0, 0, "", "");
                        viewing = -1;
                    }
                }
                default -> viewing = -1;
            }
            return true;
        }
        if (nameBox != null && nameBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                saveName();
                setFocused(null);
                return true;
            }
            if (key != GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(event);
        }
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
