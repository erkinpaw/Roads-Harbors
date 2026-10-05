package org.webtrade.minecraftportsmod.client.chart;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

/**
 * The map on the cartographer's table: the land the village's scouts have walked (drawn from what they saw), the
 * villages they found, and where the scouts are now.
 */
public class ScoutMapScreen extends UiScreen {

    private static final Identifier TEXTURE = Minecraftportsmod.id("scout_map");
    private static final int PANEL_W = 150;
    private static final String[] WINDS = {"east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east"};

    private ColonyPayloads.MapView view;
    private DynamicTexture texture;
    /** The mapped cells' box (cell coordinates) and the texture's size. */
    private int minCx, minCz, w, h;
    private int x0, y0, x1, y1, mx0, my0, mx1, my1;
    /** Screen pixels per cell, and the map's middle (in blocks). */
    private double zoom = -1, lookX, lookZ;
    private boolean dragging;

    public ScoutMapScreen(ColonyPayloads.MapView view) {
        super(Component.translatable("minecraftportsmod.map.title"));
        update(view);
    }

    public boolean shows(ColonyPayloads.MapView v) {
        return view != null && v.village() == view.village();
    }

    public void update(ColonyPayloads.MapView v) {
        this.view = v;
        if (lookX == 0 && lookZ == 0) {
            lookX = v.x();
            lookZ = v.z();
        }
        upload();
    }

    private void upload() {
        int n = view.keys().length;
        if (n == 0) {
            w = h = 0;
            return;
        }
        int maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        minCx = Integer.MAX_VALUE;
        minCz = Integer.MAX_VALUE;
        for (long k : view.keys()) {
            int cx = (int) (k >> 32), cz = (int) k;
            minCx = Math.min(minCx, cx);
            minCz = Math.min(minCz, cz);
            maxCx = Math.max(maxCx, cx);
            maxCz = Math.max(maxCz, cz);
        }
        int nw = maxCx - minCx + 1, nh = maxCz - minCz + 1;
        if (texture == null || nw != w || nh != h) {
            if (texture != null) texture.close();
            texture = new DynamicTexture(TEXTURE::toString, nw, nh, false);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            w = nw;
            h = nh;
        }
        NativeImage img = texture.getPixels();
        for (int x = 0; x < w; x++) for (int z = 0; z < h; z++) img.setPixel(x, z, 0);
        for (int i = 0; i < n; i++) {
            long k = view.keys()[i];
            img.setPixel((int) (k >> 32) - minCx, (int) k - minCz, 0xFF000000 | view.colors()[i]);
        }
        texture.upload();
    }

    @Override
    protected void layout() {
        int ww = Math.min(width - 24, 780), hh = Math.min(height - 24, 495);
        x0 = (width - ww) / 2;
        y0 = (height - hh) / 2;
        x1 = x0 + ww;
        y1 = y0 + hh;
        mx0 = x0 + 8;
        my0 = y0 + 20;
        mx1 = x1 - PANEL_W - 10;
        my1 = y1 - 8;
        if (zoom < 0) {
            // at first: the scouts' reach all in sight
            int reach = Math.max(300, view.range());
            zoom = Math.max(0.5, Math.min(8, Math.min(mx1 - mx0, my1 - my0) / (2.2 * reach / view.cell())));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
    }

    /** Blocks → screen. */
    private double sx(double x) {
        return (mx0 + mx1) / 2.0 + (x - lookX) / view.cell() * zoom;
    }

    private double sy(double z) {
        return (my0 + my1) / 2.0 + (z - lookZ) / view.cell() * zoom;
    }

    static Component wind(int heading) {
        return Component.translatable("minecraftportsmod.wind." + WINDS[Math.floorMod(Math.round(heading / 45F), 8)]);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.frame(g, x0, y0, x1, y1);
        g.text(font, Component.translatable("minecraftportsmod.map.header", view.villageName()), x0 + 10, y0 + 7, ChartStyle.TEXT_LIGHT, false);
        Ui.blit(g, Ui.PARCHMENT, mx0 - 1, my0 - 1, mx1 + 1, my1 + 1);
        g.enableScissor(mx0, my0, mx1, my1);
        if (w > 0) {
            int left = (int) Math.round(sx((double) minCx * view.cell())), top = (int) Math.round(sy((double) minCz * view.cell()));
            int sw = (int) Math.round(w * zoom), sh = (int) Math.round(h * zoom);
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, left, top, 0, 0, sw, sh, w, h, w, h);
        } else {
            Ui.centered(g,font, Component.translatable("minecraftportsmod.map.empty"), (mx0 + mx1) / 2, (my0 + my1) / 2 - 4, ChartStyle.TEXT_MUTED);
        }
        // the scouts' reach round the village
        if (view.range() > 0) {
            for (int a = 0; a < 360; a += 3) {
                double r = Math.toRadians(a);
                int px = (int) Math.round(sx(view.x() + Math.cos(r) * view.range())), pz = (int) Math.round(sy(view.z() + Math.sin(r) * view.range()));
                g.fill(px, pz, px + 1, pz + 1, 0x80604020);
            }
        }
        // the scouts on the road: a line out their way
        for (ColonyPayloads.ScoutRow s : view.scouts()) {
            if (!s.away()) continue;
            double a = Math.toRadians(s.heading());
            for (int t = 20; t < s.range(); t += 24) {
                int px = (int) Math.round(sx(view.x() + Math.cos(a) * t)), pz = (int) Math.round(sy(view.z() + Math.sin(a) * t));
                g.fill(px - 1, pz - 1, px + 1, pz + 1, 0xC0A8322A);
            }
        }
        // the villages found, and this one
        ColonyPayloads.PlaceRow hover = null;
        for (ColonyPayloads.PlaceRow p : view.places()) {
            int px = (int) Math.round(sx(p.x())), pz = (int) Math.round(sy(p.z()));
            g.fill(px - 5, pz - 5, px + 5, pz + 5, 0xE0EADBB5);
            g.outline(px - 5, pz - 5, 10, 10, ChartStyle.ROUTE);
            g.item(new ItemStack(Items.BELL), px - 8, pz - 8);
            Ui.centered(g,font, p.name(), px, pz + 9, ChartStyle.TEXT);
            if (Math.abs(mouseX - px) < 8 && Math.abs(mouseY - pz) < 8) hover = p;
        }
        int hx = (int) Math.round(sx(view.x())), hz = (int) Math.round(sy(view.z()));
        g.fill(hx - 6, hz - 6, hx + 6, hz + 6, 0xF0FFE08A);
        g.outline(hx - 6, hz - 6, 12, 12, ChartStyle.INK);
        g.item(new ItemStack(Items.CAMPFIRE), hx - 8, hz - 8);
        Ui.centered(g,font, view.villageName(), hx, hz + 10, ChartStyle.INK);
        // the way the next expedition is to go
        if (view.aim() >= 0) {
            double a = Math.toRadians(view.aim());
            for (int t = 20; t < Math.max(300, view.range()); t += 12) {
                int px = (int) Math.round(sx(view.x() + Math.cos(a) * t)), pz = (int) Math.round(sy(view.z() + Math.sin(a) * t));
                g.fill(px, pz, px + 2, pz + 2, 0xE02A6A3A);
            }
        }
        g.disableScissor();
        if (view.aim() >= 0) {
            Component hint = Component.translatable("minecraftportsmod.map.aimed", wind(view.aim()));
            g.fill(mx0 + 2, my1 - 13, mx0 + 8 + font.width(hint), my1 - 2, 0xC0EADBB5);
            g.text(font, hint, mx0 + 5, my1 - 11, ChartStyle.TEXT_MUTED, false);
        }
        if (hover != null) g.setComponentTooltipForNextFrame(font, placeTip(hover), mouseX, mouseY);
        drawPanel(g);
        widgets(g, mouseX, mouseY, partialTick);
    }

    private java.util.List<Component> placeTip(ColonyPayloads.PlaceRow p) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.literal(p.name()).withStyle(net.minecraft.ChatFormatting.GOLD));
        lines.add(where(p));
        if (p.level() >= 0) lines.add(Village.Level.values()[p.level()].displayName().copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        if (p.focus() >= 0) {
            lines.add(Component.translatable("minecraftportsmod.map.focus", BuildingType.Branch.values()[p.focus()].displayName())
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
        lines.add(Component.translatable("minecraftportsmod.map.found_day", p.found()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return lines;
    }

    private Component where(ColonyPayloads.PlaceRow p) {
        int dx = p.x() - view.x(), dz = p.z() - view.z();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        return Component.translatable("minecraftportsmod.map.where", dist, wind((int) Math.round(Math.toDegrees(Math.atan2(dz, dx)))));
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        int px0 = x1 - PANEL_W - 4, py0 = my0, px1 = x1 - 8;
        g.fill(px0 - 1, py0 - 1, px1 + 1, my1 + 1, ChartStyle.PARCHMENT_SHADE);
        g.fill(px0, py0, px1, my1, ChartStyle.PARCHMENT_DARK);
        int x = px0 + 5, y = py0 + 5, wd = px1 - px0 - 10;
        g.text(font, Component.translatable("minecraftportsmod.map.places"), x, y, ChartStyle.TEXT, false);
        y += 12;
        if (view.places().isEmpty()) {
            Component none = Component.translatable("minecraftportsmod.map.no_places");
            g.textWithWordWrap(font, none, x, y, wd, ChartStyle.TEXT_MUTED, false);
            y += font.wordWrapHeight(none, wd) + 4;
        }
        for (ColonyPayloads.PlaceRow p : view.places()) {
            g.text(font, font.plainSubstrByWidth(p.name(), wd), x, y, ChartStyle.ROUTE, false);
            y += 10;
            MutableComponent sub = where(p).copy();
            if (p.focus() >= 0) sub.append(" · ").append(BuildingType.Branch.values()[p.focus()].displayName());
            for (var line : font.split(sub, wd)) {
                g.text(font, line, x + 4, y, ChartStyle.INK_SOFT, false);
                y += 10;
            }
            y += 3;
        }
        y += 6;
        g.text(font, Component.translatable("minecraftportsmod.map.scouts"), x, y, ChartStyle.TEXT, false);
        y += 12;
        if (view.scouts().isEmpty()) {
            g.textWithWordWrap(font, Component.translatable("minecraftportsmod.map.no_scouts"), x, y, wd, ChartStyle.TEXT_MUTED, false);
        }
        for (ColonyPayloads.ScoutRow s : view.scouts()) {
            g.text(font, font.plainSubstrByWidth(s.name(), wd), x, y, ChartStyle.TEXT, false);
            y += 10;
            Component st = s.away() ? Component.translatable("minecraftportsmod.map.away", wind(s.heading()), Math.max(0, s.back() - view.day()))
                    : Component.translatable("minecraftportsmod.map.home");
            for (var line : font.split(st, wd)) {
                g.text(font, line, x + 4, y, s.away() ? 0xFFB07A10 : ChartStyle.GOOD, false);
                y += 10;
            }
            y += 3;
        }
    }

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        if (event.x() >= mx0 && event.x() < mx1 && event.y() >= my0 && event.y() < my1) {
            if (event.button() == 1) {
                // right click: the next expedition goes that way
                double bx = lookX + (event.x() - (mx0 + mx1) / 2.0) / zoom * view.cell(), bz = lookZ + (event.y() - (my0 + my1) / 2.0) / zoom * view.cell();
                int heading = (int) Math.round(Math.toDegrees(Math.atan2(bz - view.z(), bx - view.x())));
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new ColonyPayloads.VillageAction(view.village(),
                        ColonyPayloads.VillageAction.SCOUT, Math.floorMod(heading, 360), 0));
                return true;
            }
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean uiRelease(MouseButtonEvent event) {
        dragging = false;
        return super.uiRelease(event);
    }

    @Override
    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            lookX -= dx / zoom * view.cell();
            lookZ -= dy / zoom * view.cell();
            return true;
        }
        return super.uiDrag(event, dx, dy);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        zoom = Math.max(0.3, Math.min(12, zoom * (scrollY > 0 ? 1.25 : 0.8)));
        return true;
    }
}
