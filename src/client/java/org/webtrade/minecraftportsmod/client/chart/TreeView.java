package org.webtrade.minecraftportsmod.client.chart;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.BuildingType;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Tree;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The development tree, growing up from the village's middle: the smithy over it (the wood and the ore grow from
 * there), the three specialities upwards (food on the left, from the middle itself; wood; ore), each its base first
 * and its two sub-branches above side by side, marked with what they give this village (+20% its own, as usual the
 * other of its branch, -30% another branch's: a click on one makes it the village's); what every village has alike
 * goes off to the sides (homes and trade to the left, stores and the shore to the right). The player can drag the
 * nodes about (where they put them is remembered, for every village alike); clicking one shows what it is, what
 * opening it costs, and what of it stands.
 */
final class TreeView {

    /** What every village has alike: to the left, and to the right, of the middle (top to bottom). */
    private static final BuildingType.Branch[] LEFT = {BuildingType.Branch.HOME, BuildingType.Branch.TRADE},
            RIGHT = {BuildingType.Branch.STORE, BuildingType.Branch.COAST};
    static final BuildingType.Branch[] SPECIAL = {BuildingType.Branch.FOOD, BuildingType.Branch.WOOD, BuildingType.Branch.MINE};
    /** A speciality's column's width, a row's height, a sub-branch's lane off the column's middle, a side step (tree units). */
    private static final int COL = 190, ROW = 46, LANE = 50, SIDE = 80, NODE_R = 11, CENTER_R = 16, PANEL_W = 164, STRIP_H = 18;
    /** The rows (upwards from the middle) of the smithy, of the specialities' bases, of their sub-branches' names. */
    private static final double SMITHY_ROW = 1.2, BASE_ROW = 2.4;
    // (the columns are laid out otherwise than the circle was: where nodes were put in it is not carried over)
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(Minecraftportsmod.MOD_ID + "-tree2.json");

    /** Where the player put the nodes (tree units from the middle), by node id. */
    private static final Map<String, double[]> custom = new HashMap<>();
    private static boolean loaded;

    private final Font font;
    private final int villageId;
    private float zoom = -1;
    private double panX, panY;
    /** The node shown in the panel: a BuildingType ordinal, CENTER for the middle, -1 for none. */
    private int selected = -1;
    static final int CENTER = 1000;
    // dragging: a node (its ordinal) or the whole tree (-2); where the press was
    private int dragNode = -1;
    private boolean moved;
    private double pressX, pressY;
    // the screen area and the panel's buttons of the last frame
    private int ax0, ay0, ax1, ay1;
    private final List<Object[]> buttons = new ArrayList<>();

    TreeView(Font font, int villageId) {
        this.font = font;
        this.villageId = villageId;
        load();
    }

    void select(int type) {
        selected = type;
    }

    /** Where a node is drawn on the screen (automated tests); null before the first frame. */
    double[] onScreen(BuildingType t) {
        if (zoom < 0) return null;
        double[] p = at(t);
        return new double[]{sx(p[0]), sy(p[1])};
    }

    /** Where the player has put this node, or null if nowhere (automated tests). */
    static double[] moved(BuildingType t) {
        return custom.get(t.id());
    }

    // ------------------------------------------------------------------ the layout

    private static int leaves(BuildingType t) {
        int n = 0;
        for (BuildingType k : t.children()) n += leaves(k);
        return Math.max(1, n);
    }

    /** A speciality's column's middle (tree units): food to the left, wood over the smithy, ore to the right. */
    static double colX(BuildingType.Branch b) {
        for (int i = 0; i < SPECIAL.length; i++) if (SPECIAL[i] == b) return (i - 1) * COL;
        return 0;
    }

    /** A sub-branch's lane: left or right of its column's middle. */
    static double laneX(BuildingType.Sub sub) {
        BuildingType.Sub[] mine = subs(sub.branch);
        return colX(sub.branch) + (mine[0] == sub ? -LANE : LANE);
    }

    static BuildingType.Sub[] subs(BuildingType.Branch b) {
        List<BuildingType.Sub> out = new ArrayList<>();
        for (BuildingType.Sub s : BuildingType.Sub.values()) if (s.branch == b) out.add(s);
        return out.toArray(new BuildingType.Sub[0]);
    }

    /** How deep a speciality's base goes in the tree (the smithy aside). */
    static int baseDepth(BuildingType.Branch b) {
        int d = 1;
        for (BuildingType t : BuildingType.values()) if (t.isNode() && t.branch == b && t.sub() == null && t != BuildingType.SMITHY) d = Math.max(d, t.depth());
        return d;
    }

    /** How many rows a speciality's base takes (its first base node on the base row). */
    static int baseRows(BuildingType.Branch b) {
        int lo = Integer.MAX_VALUE, hi = 0;
        for (BuildingType t : BuildingType.values()) {
            if (!t.isNode() || t.branch != b || t.sub() != null || t == BuildingType.SMITHY) continue;
            lo = Math.min(lo, t.depth());
            hi = Math.max(hi, t.depth());
        }
        return lo == Integer.MAX_VALUE ? 1 : hi - lo + 1;
    }

    /** The row (upwards) the sub-branches' names are written on, and the first of their nodes stands on. */
    static double subNameRow(BuildingType.Branch b) {
        return BASE_ROW + baseRows(b) - 1 + 1.0;
    }

    static double subRow(BuildingType.Branch b) {
        return subNameRow(b) + 1.15;
    }

    /** The row the branch's name is written on: over all of it. */
    static double titleRow(BuildingType.Branch b) {
        int deep = 0;
        for (BuildingType t : BuildingType.values()) if (t.isNode() && t.branch == b && t.sub() != null) deep = Math.max(deep, t.depth() - baseDepth(b));
        return subRow(b) + Math.max(1, deep) - 1 + 1.1;
    }

    /** Where every node sits when nobody has moved it (y grows downwards: the tree grows up, to negative y). */
    private static Map<BuildingType, double[]> homes() {
        Map<BuildingType, double[]> out = new EnumMap<>(BuildingType.class);
        // the smithy over the middle; the specialities upwards from there, their bases then their sub-branches
        out.put(BuildingType.SMITHY, new double[]{0, -SMITHY_ROW * ROW});
        for (BuildingType.Branch b : SPECIAL) {
            int first = Integer.MAX_VALUE;
            for (BuildingType t : BuildingType.values()) {
                if (t.isNode() && t.branch == b && t.sub() == null && t != BuildingType.SMITHY) first = Math.min(first, t.depth());
            }
            for (BuildingType t : BuildingType.values()) {
                if (!t.isNode() || t.branch != b || t == BuildingType.SMITHY) continue;
                if (t.sub() == null) {
                    out.put(t, new double[]{colX(b), -(BASE_ROW + t.depth() - first) * ROW});
                } else {
                    out.put(t, new double[]{laneX(t.sub()), -(subRow(b) + t.depth() - baseDepth(b) - 1) * ROW});
                }
            }
        }
        // what every village has: off to the sides of the middle, each group growing outwards
        for (int side = -1; side <= 1; side += 2) {
            BuildingType.Branch[] groups = side < 0 ? LEFT : RIGHT;
            double y = -0.6 * ROW;
            for (BuildingType.Branch b : groups) {
                List<BuildingType> rs = new ArrayList<>();
                int all = 0;
                for (BuildingType t : BuildingType.values()) {
                    if (t.isNode() && t.branch == b && t.parent == null) {
                        rs.add(t);
                        all += leaves(t);
                    }
                }
                double h = Math.max(1, all) * 30;
                double yy = y;
                for (BuildingType r : rs) {
                    double w = h * leaves(r) / Math.max(1, all);
                    place(out, r, side, yy, yy + w);
                    yy += w;
                }
                y += h + 26;
            }
        }
        return out;
    }

    /** A side group's node and those it opens: each a step further out, side by side (up and down) by how much grows from them. */
    private static void place(Map<BuildingType, double[]> out, BuildingType t, int side, double y0, double y1) {
        double x = side * (COL * 1.5 + (t.depth() - 1) * SIDE);
        out.put(t, new double[]{x, (y0 + y1) / 2});
        double s = y0;
        for (BuildingType k : t.children()) {
            double w = (y1 - y0) * leaves(k) / leaves(t);
            place(out, k, side, s, s + w);
            s += w;
        }
    }

    private static final Map<BuildingType, double[]> HOME = homes();

    private static double[] at(BuildingType t) {
        double[] c = custom.get(t.id());
        return c != null ? c : HOME.get(t);
    }

    // ------------------------------------------------------------------ remembering where things were put

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
            for (var e : o.entrySet()) {
                var arr = e.getValue().getAsJsonArray();
                custom.put(e.getKey(), new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble()});
            }
        } catch (Exception e) {
            Minecraftportsmod.LOGGER.warn("Could not read {}: {}", FILE, e.toString());
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            for (var e : custom.entrySet()) {
                var arr = new com.google.gson.JsonArray();
                arr.add(Math.round(e.getValue()[0] * 10) / 10.0);
                arr.add(Math.round(e.getValue()[1] * 10) / 10.0);
                o.add(e.getKey(), arr);
            }
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception e) {
            Minecraftportsmod.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
        }
    }

    // ------------------------------------------------------------------ drawing helpers (on the screen)

    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.round(Math.sqrt(r * r - dy * dy + 0.5));
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** A line of dots (or a solid one with {@code gap} 1). */
    private static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int w, int color, int gap) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        int n = (int) Math.max(1, len);
        for (int i = 0; i <= n; i++) {
            if (gap > 1 && (i / 2) % gap != 0) continue;
            double t = i / (double) n;
            int x = (int) Math.round(x0 + (x1 - x0) * t), y = (int) Math.round(y0 + (y1 - y0) * t);
            g.fill(x - w / 2, y - w / 2, x - w / 2 + w, y - w / 2 + w, color);
        }
    }

    private ColonyPayloads.NodeRow row(ColonyPayloads.VillageView view, BuildingType t) {
        for (ColonyPayloads.NodeRow n : view.tree()) if (n.type() == t.ordinal()) return n;
        return null;
    }

    private static Tree.Node state(ColonyPayloads.NodeRow n) {
        return n == null ? Tree.Node.HIDDEN : Tree.Node.values()[n.node()];
    }

    private static int count(ColonyPayloads.NodeRow n) {
        return n == null ? 0 : n.levels()[0] + n.levels()[1] + n.levels()[2];
    }

    private static int best(ColonyPayloads.NodeRow n) {
        return n == null ? 0 : n.levels()[2] > 0 ? 3 : n.levels()[1] > 0 ? 2 : n.levels()[0] > 0 ? 1 : 0;
    }

    /** The box the tree's nodes take when nobody moved them (tree units): min x, min y, max x, max y. */
    private static final double[] BOX = box();

    private static double[] box() {
        double[] b = {-CENTER_R, -CENTER_R, CENTER_R, CENTER_R};
        for (double[] p : HOME.values()) {
            b[0] = Math.min(b[0], p[0] - 50);
            b[1] = Math.min(b[1], p[1] - ROW / 2.0);
            b[2] = Math.max(b[2], p[0] + 50);
            b[3] = Math.max(b[3], p[1] + ROW / 2.0);
        }
        for (BuildingType.Branch sp : SPECIAL) {
            b[0] = Math.min(b[0], colX(sp) - COL / 2.0);
            b[2] = Math.max(b[2], colX(sp) + COL / 2.0);
            b[1] = Math.min(b[1], -(titleRow(sp) + 0.4) * ROW);
        }
        return b;
    }

    /** The middle of the tree on the screen: the box of its nodes centred in the space, then moved by the player. */
    private double originX() {
        return (ax0 + treeRight()) / 2.0 - (BOX[0] + BOX[2]) / 2 * zoom + panX;
    }

    private double originY() {
        return (ay0 + ay1 - STRIP_H) / 2.0 - (BOX[1] + BOX[3]) / 2 * zoom + panY;
    }

    private int treeRight() {
        return selected >= 0 ? ax1 - PANEL_W : ax1;
    }

    /** Tree units to the screen: the zoom spreads the nodes out, they keep their size. */
    private double sx(double x) {
        return originX() + x * zoom;
    }

    private double sy(double y) {
        return originY() + y * zoom;
    }

    private double tx(double mx) {
        return (mx - originX()) / zoom;
    }

    private double ty(double my) {
        return (my - originY()) / zoom;
    }

    /** The node under the mouse (an ordinal, CENTER, or -1). */
    private int nodeAt(double mx, double my) {
        if (zoom < 0 || mx < ax0 || mx >= treeRight() || my < ay0 || my >= ay1 - STRIP_H) return -1;
        double cx = sx(0) - mx, cy = sy(0) - my;
        if (cx * cx + cy * cy <= (CENTER_R + 2) * (CENTER_R + 2)) return CENTER;
        int best = -1;
        double bestD = (NODE_R + 3) * (NODE_R + 3);
        for (BuildingType t : BuildingType.values()) {
            if (!t.isNode()) continue;
            double[] p = at(t);
            double dx = sx(p[0]) - mx, dy = sy(p[1]) - my, d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = t.ordinal();
            }
        }
        return best;
    }

    /** The zoom at which the whole tree fits the space it has. */
    private float fit() {
        // room around the nodes for their names and the branches' names
        double w = treeRight() - ax0 - 80, h = ay1 - ay0 - STRIP_H - 30;
        return (float) Math.max(0.3, Math.min(1.6, Math.min(w / (BOX[2] - BOX[0]), h / (BOX[3] - BOX[1]))));
    }

    // ------------------------------------------------------------------ drawing

    void draw(GuiGraphicsExtractor g, ColonyPayloads.VillageView view, int x0, int y0, int x1, int y1, int mouseX, int mouseY, int ticks) {
        ax0 = x0;
        ay0 = y0;
        ax1 = x1;
        ay1 = y1;
        buttons.clear();
        int right = treeRight();
        // at first: the whole tree if it fits; if not, close enough that the nodes stand apart (the rest is a drag away)
        if (zoom < 0) zoom = Math.max(fit(), 0.72F);
        int pulse = (ticks / 8) % 2 == 0 ? 0xFFE3A12F : 0xFFFFC857;
        BuildingType.Branch focus = view.focus() < 0 ? null : BuildingType.Branch.values()[view.focus()];
        int hover = nodeAt(mouseX, mouseY);
        // names under the nodes when there is room for them; else only for the one pointed at
        // names under the nodes once zoomed in enough for them not to run into each other; else only the one pointed at
        boolean names = zoom >= 1.1F;
        int nameW = 96;

        g.enableScissor(x0, y0, right, y1 - STRIP_H);
        BuildingType.Sub mySub = view.sub() < 0 ? null : BuildingType.Sub.values()[view.sub()];
        // the links first (the names drawn over them): from the middle to each root (the smithy, the field, what every village has), from each node to the next
        for (BuildingType t : BuildingType.values()) {
            if (!t.isNode()) continue;
            Tree.Node st = state(row(view, t));
            double[] p = at(t);
            double[] q = t.parent == null ? new double[]{0, 0} : at(t.parent);
            boolean alive = st == Tree.Node.OPEN;
            line(g, sx(q[0]), sy(q[1]), sx(p[0]), sy(p[1]), alive ? 3 : 2, alive ? 0xFF6B4A2A : st == Tree.Node.HIDDEN ? 0x80B8AC94 : 0xFFB8AC94,
                    alive ? 1 : st == Tree.Node.HIDDEN ? 3 : 2);
        }
        // the specialities: their names at the top; the sub-branches' names (a click makes one the village's) with what
        // they give; a sub-branch with nothing in it yet, an empty place
        for (BuildingType.Branch b : SPECIAL) {
            boolean f = b == focus;
            MutableComponent name = Component.empty().append(b.displayName());
            if (f) name = Component.literal("★ ").append(name);
            int nx = (int) Math.round(sx(colX(b))), ny = (int) Math.round(sy(-titleRow(b) * ROW)) - 4;
            int w = font.width(name);
            g.fill(nx - w / 2 - 3, ny - 2, nx + w / 2 + 3, ny + 10, f ? 0xE0F3D38A : 0xA0EADBB5);
            Ui.centered(g,font, name, nx, ny, f ? 0xFFB07A10 : ChartStyle.TEXT);
            for (BuildingType.Sub sb : subs(b)) {
                int lx = (int) Math.round(sx(laneX(sb))), ly = (int) Math.round(sy(-subNameRow(b) * ROW)) - 10;
                boolean mine = sb == mySub;
                String pct = mine ? "+20%" : b == focus ? "0%" : "−30%";
                int color = mine ? 0xFFB07A10 : b == focus ? ChartStyle.TEXT_MUTED : ChartStyle.BAD;
                Component label = Component.empty().append(sb.displayName());
                int lw = Math.max(font.width(label), font.width(pct)) + 8;
                int[] rect = {lx - lw / 2, ly - 2, lw, 22};
                boolean hot = mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3];
                g.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], mine ? 0xF0F3D38A : hot ? 0xE0F3EAD5 : 0xB0EADBB5);
                g.outline(rect[0], rect[1], rect[2], rect[3], mine ? 0xFFC9922A : 0x60000000);
                Ui.centered(g,font, label, lx, ly, ChartStyle.TEXT);
                Ui.centered(g,font, pct, lx, ly + 10, color);
                if (!mine) buttons.add(new Object[]{rect, (Runnable) () -> send(ColonyPayloads.VillageAction.SUB, sb.ordinal())});
                boolean any = false;
                for (BuildingType t : BuildingType.values()) if (t.isNode() && t.sub() == sb) any = true;
                if (!any) {
                    int ey = (int) Math.round(sy(-subRow(b) * ROW));
                    disc(g, lx, ey, NODE_R - 3, 0x40000000);
                    Ui.centered(g,font, "?", lx + 1, ey - 3, 0x80000000);
                }
            }
        }
        // what every village has: its groups' names by their first node
        for (BuildingType.Branch b : new BuildingType.Branch[]{BuildingType.Branch.HOME, BuildingType.Branch.TRADE, BuildingType.Branch.STORE,
                BuildingType.Branch.COAST}) {
            double[] first = null;
            for (BuildingType t : BuildingType.values()) if (t.isNode() && t.branch == b && t.parent == null && first == null) first = at(t);
            if (first == null) continue;
            Component name = b.displayName();
            int nx = (int) Math.round(sx(first[0])), ny = (int) Math.round(sy(first[1])) - NODE_R - 12;
            Ui.centered(g,font, name, nx, ny, ChartStyle.TEXT_MUTED);
        }
        // the nodes
        for (BuildingType t : BuildingType.values()) {
            if (!t.isNode()) continue;
            ColonyPayloads.NodeRow n = row(view, t);
            Tree.Node st = state(n);
            double[] p = at(t);
            int x = (int) Math.round(sx(p[0])), y = (int) Math.round(sy(p[1]));
            boolean lit = t.ordinal() == selected || t.ordinal() == hover;
            if (lit) disc(g, x, y, NODE_R + 2, 0xFFFFFFFF);
            switch (st) {
                case OPEN -> {
                    disc(g, x, y, NODE_R, ChartStyle.INK);
                    disc(g, x, y, NODE_R - 2, 0xFF000000 | t.color);
                }
                case READY -> {
                    disc(g, x, y, NODE_R, pulse);
                    disc(g, x, y, NODE_R - 2, 0xFFF3EAD5);
                }
                case WAIT -> {
                    disc(g, x, y, NODE_R, 0xFF9A948A);
                    disc(g, x, y, NODE_R - 2, 0xFFD5CDBD);
                }
                case HIDDEN -> {
                    disc(g, x, y, NODE_R - 3, 0xFF9A948A);
                    disc(g, x, y, NODE_R - 5, 0xFFCFC4AD);
                }
            }
            if (st == Tree.Node.HIDDEN) {
                Ui.centered(g,font, "?", x + 1, y - 3, 0xFF6D6558);
                continue;
            }
            g.item(new ItemStack(t.icon), x - 8, y - 8);
            if (st == Tree.Node.WAIT) g.fill(x - NODE_R + 3, y - NODE_R + 3, x + NODE_R - 2, y + NODE_R - 2, 0x80D5CDBD);
            if (names || lit) {
                String full = t.displayName().getString();
                String name = lit ? full : font.plainSubstrByWidth(full, nameW);
                if (name.length() < full.length()) name = font.plainSubstrByWidth(full, nameW - 6) + "…";
                int w = font.width(name);
                // the one picked in the upper half: its name above it, off the node it grows from
                int ny = lit && !names && p[1] < -1 ? y - NODE_R - 11 : y + NODE_R + 1;
                g.fill(x - w / 2 - 2, ny, x + w / 2 + 2, ny + 10, 0xC8EADBB5);
                Ui.centered(g,font, name, x, ny + 1, st == Tree.Node.WAIT ? 0xFF8D8574 : ChartStyle.TEXT);
            }
            if (st == Tree.Node.OPEN && count(n) > 0) {
                // how many stand; gold once one of them has grown to the top (what opens the next node)
                String c = String.valueOf(count(n));
                int bw = font.width(c) + 4;
                int bx = x + NODE_R - 5, by = y - NODE_R - 3;
                boolean top = best(n) >= t.maxLevel && t.maxLevel > 1;
                g.fill(bx, by, bx + bw, by + 10, top ? 0xF0C9922A : 0xE02B2118);
                g.text(font, c, bx + 2, by + 1, top ? 0xFF2B1D08 : 0xFFEFE6D3, false);
            }
            if (view.priority() == t.ordinal()) g.text(font, "★", x - NODE_R - 5, y - NODE_R - 3, 0xFFFFD24A, false);
        }
        // the middle of the village
        ColonyPayloads.CenterRow c = view.center();
        BuildingType mid = c.type() < 0 ? BuildingType.CAMPFIRE : BuildingType.values()[c.type()];
        int cx = (int) Math.round(sx(0)), cy = (int) Math.round(sy(0));
        if (selected == CENTER || hover == CENTER) disc(g, cx, cy, CENTER_R + 2, 0xFFFFFFFF);
        disc(g, cx, cy, CENTER_R, ChartStyle.INK);
        disc(g, cx, cy, CENTER_R - 2, 0xFFD9822B);
        g.item(new ItemStack(mid.icon), cx - 8, cy - 8);
        g.disableScissor();
        // what the middle is, the village's title and speciality: in the corner, out of the way of the nodes
        MutableComponent head = Component.empty().append(mid.displayName()).append(" · ").append(Village.Level.values()[view.level()].displayName());
        if (focus != null) head.append(" · ★ ").append(focus.displayName());
        int hw = font.width(head);
        g.fill(x0 + 2, y0 + 2, x0 + hw + 8, y0 + 13, 0xC8EADBB5);
        g.text(font, head, x0 + 5, y0 + 4, 0xFFB07A10, false);

        drawStrip(g, view, mouseX, mouseY, right);
        if (selected >= 0) drawPanel(g, view, mouseX, mouseY);
        if (hover >= 0 && dragNode < 0) {
            if (hover == CENTER) {
                ColonyPayloads.CenterRow cr = view.center();
                g.setComponentTooltipForNextFrame(font, List.of((cr.type() < 0 ? BuildingType.CAMPFIRE : BuildingType.values()[cr.type()]).displayName()), mouseX, mouseY);
            }
            else g.setComponentTooltipForNextFrame(font, tip(view, BuildingType.values()[hover]), mouseX, mouseY);
        }
    }

    private List<Component> tip(ColonyPayloads.VillageView view, BuildingType t) {
        ColonyPayloads.NodeRow n = row(view, t);
        Tree.Node st = state(n);
        List<Component> lines = new ArrayList<>();
        if (st == Tree.Node.HIDDEN) {
            lines.add(Component.translatable("minecraftportsmod.tree.hidden").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(t.displayName().copy().withStyle(ChatFormatting.GOLD));
        lines.add(stateText(st).copy().withStyle(st == Tree.Node.OPEN ? ChatFormatting.GREEN : st == Tree.Node.READY ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return lines;
    }

    private static Component stateText(Tree.Node st) {
        return Component.translatable("minecraftportsmod.tree.node." + st.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** What the village's building sites still lack; and the button that puts every node back in its place. */
    private void drawStrip(GuiGraphicsExtractor g, ColonyPayloads.VillageView view, int mouseX, int mouseY, int right) {
        int y = ay1 - STRIP_H + 1;
        g.fill(ax0, y - 1, right, ay1, ChartStyle.PARCHMENT_DARK);
        g.fill(ax0, y - 1, right, y, ChartStyle.PARCHMENT_SHADE);
        int[] need = new int[Res.values().length];
        for (ColonyPayloads.BuildingRow b : view.buildings()) {
            for (Res r : Res.values()) need[r.ordinal()] += Math.max(0, b.missing()[r.ordinal()]);
        }
        int x = ax0 + 4;
        Component label = Component.translatable("minecraftportsmod.tree.demand");
        g.text(font, label, x, y + 4, ChartStyle.TEXT, false);
        x += font.width(label) + 4;
        boolean any = false;
        for (Res r : Res.values()) {
            int m = need[r.ordinal()] - view.stock()[r.ordinal()];
            if (m <= 0) continue;
            any = true;
            g.item(new ItemStack(r.icon), x, y);
            g.text(font, String.valueOf(m), x + 17, y + 4, ChartStyle.BAD, false);
            x += 20 + font.width(String.valueOf(m)) + 6;
        }
        if (!any) g.text(font, Component.translatable("minecraftportsmod.tree.demand_none"), x, y + 4, ChartStyle.GOOD, false);
        if (!custom.isEmpty()) {
            Component reset = Component.translatable("minecraftportsmod.tree.reset");
            int bw = font.width(reset) + 10;
            button(g, new int[]{right - bw - 3, y + 1, bw, 14}, reset, mouseX, mouseY, true, () -> {
                custom.clear();
                save();
                panX = panY = 0;
            });
        }
    }

    private void button(GuiGraphicsExtractor g, int[] b, Component label, int mouseX, int mouseY, boolean active, Runnable action) {
        boolean hover = active && mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], !active ? 0xFF8A7F6C : hover ? ChartStyle.BRASS : ChartStyle.WOOD);
        g.outline(b[0], b[1], b[2], b[3], ChartStyle.WOOD_DARK);
        Ui.centered(g,font, label, b[0] + b[2] / 2, b[1] + (b[3] - 8) / 2, !active ? 0xFFD0C6B0 : hover ? ChartStyle.TEXT : ChartStyle.TEXT_LIGHT);
        if (active) buttons.add(new Object[]{b, action});
    }

    private void send(int kind, int a) {
        ClientPlayNetworking.send(new ColonyPayloads.VillageAction(villageId, kind, a, 0));
    }

    private int costLine(GuiGraphicsExtractor g, int x, int y, int w, int[] cost, int[] stock) {
        int cx = x;
        for (Res r : Res.values()) {
            int c = cost[r.ordinal()];
            if (c <= 0) continue;
            String s = String.valueOf(c);
            if (cx + 18 + font.width(s) > x + w) {
                cx = x;
                y += 17;
            }
            g.item(new ItemStack(r.icon), cx, y);
            g.text(font, s, cx + 17, y + 5, stock != null && stock[r.ordinal()] < c ? ChartStyle.BAD : ChartStyle.TEXT, false);
            cx += 20 + font.width(s) + 4;
        }
        return y + 18;
    }

    private void drawPanel(GuiGraphicsExtractor g, ColonyPayloads.VillageView view, int mouseX, int mouseY) {
        int px0 = ax1 - PANEL_W + 2, px1 = ax1;
        g.fill(px0 - 2, ay0, px0 - 1, ay1, ChartStyle.PARCHMENT_SHADE);
        g.fill(px0, ay0, px1, ay1, ChartStyle.PARCHMENT_DARK);
        int x = px0 + 5, y = ay0 + 5, w = PANEL_W - 12;
        if (selected == CENTER) {
            drawCenterPanel(g, view, x, y, w, mouseX, mouseY);
            return;
        }
        BuildingType t = BuildingType.values()[selected];
        ColonyPayloads.NodeRow n = row(view, t);
        Tree.Node st = state(n);
        if (st == Tree.Node.HIDDEN) {
            g.text(font, "?", x + 5, y + 4, ChartStyle.TEXT, false);
            g.text(font, Component.translatable("minecraftportsmod.tree.unknown"), x + 20, y + 4, ChartStyle.TEXT, false);
            y += 20;
            Component why = Component.translatable("minecraftportsmod.tree.hidden_after", t.parent.displayName());
            g.textWithWordWrap(font, why, x, y, w, ChartStyle.INK_SOFT, false);
            return;
        }
        g.item(new ItemStack(t.icon), x, y);
        g.text(font, font.plainSubstrByWidth(t.displayName().getString(), w - 20), x + 20, y, ChartStyle.TEXT, false);
        int stColor = st == Tree.Node.OPEN ? ChartStyle.GOOD : st == Tree.Node.READY ? 0xFFB07A10 : ChartStyle.BAD;
        MutableComponent sub = Component.empty().append(t.branch.displayName());
        if (t.sub() != null) sub.append(" · ").append(t.sub().displayName());
        if (view.focus() == t.branch.ordinal()) sub.append(" ★");
        sub.append(" · ").append(stateText(st));
        g.text(font, font.plainSubstrByWidth(sub.getString(), w - 20), x + 20, y + 9, stColor, false);
        y += 22;
        g.text(font, Component.translatable("minecraftportsmod.tree.build_cost"), x, y, ChartStyle.TEXT_MUTED, false);
        y = costLine(g, x, y + 9, w, n.build(), null);
        if (st == Tree.Node.OPEN) {
            int total = count(n);
            g.text(font, Component.translatable("minecraftportsmod.tree.standing", total), x, y, ChartStyle.TEXT, false);
            y += 11;
            for (int l = 1; l <= t.maxLevel; l++) {
                if (t.maxLevel == 1) break;
                BuildingScreen.pips(g, x + 2, y + 1, l, t.maxLevel, 0);
                g.text(font, Component.translatable("minecraftportsmod.tree.at_level", l, n.levels()[l - 1]), x + 26, y, ChartStyle.INK_SOFT, false);
                y += 10;
            }
            y += 4;
            int by = Math.max(y + 4, ay1 - 40);
            boolean pinned = view.priority() == t.ordinal();
            button(g, new int[]{x, by, w, 16}, Component.translatable(pinned ? "minecraftportsmod.bmenu.unpin" : "minecraftportsmod.tree.build_first"),
                    mouseX, mouseY, true, () -> send(ColonyPayloads.VillageAction.PIN, pinned ? -1 : t.ordinal()));
            int first = firstOf(view, t);
            if (first >= 0) {
                button(g, new int[]{x, by + 19, w, 16}, Component.translatable("minecraftportsmod.tree.open_building"), mouseX, mouseY, true,
                        () -> send(ColonyPayloads.VillageAction.OPEN, first));
            }
            return;
        }
        // not open yet: what it needs, and what opening it costs and why
        if (t.parent != null) {
            boolean ok = st == Tree.Node.READY;
            Component need = Component.literal(ok ? "✔ " : "✘ ").append(Component.translatable("minecraftportsmod.tree.need_grown", t.parent.displayName()));
            for (var line : font.split(need, w)) {
                g.text(font, line, x, y, ok ? ChartStyle.GOOD : ChartStyle.BAD, false);
                y += 10;
            }
            y += 2;
        }
        g.text(font, Component.translatable("minecraftportsmod.tree.unlock_cost"), x, y, ChartStyle.TEXT, false);
        y = costLine(g, x, y + 9, w, n.unlock(), view.stock());
        for (ColonyPayloads.Req f : n.factors()) {
            int pct = f.have();
            String k = pct >= 100 ? "×" + String.format(java.util.Locale.ROOT, "%.2f", pct / 100.0).replaceAll("0+$", "").replaceAll("\\.$", "")
                    : "−" + (100 - pct) + "%";
            g.text(font, k, x, y, pct > 100 ? ChartStyle.BAD : ChartStyle.GOOD, false);
            for (var line : font.split(f.label(), w - 34)) {
                g.text(font, line, x + 32, y, ChartStyle.INK_SOFT, false);
                y += 10;
            }
        }
        boolean affordable = true;
        for (Res r : Res.values()) if (view.stock()[r.ordinal()] < n.unlock()[r.ordinal()]) affordable = false;
        boolean can = st == Tree.Node.READY && affordable;
        int by = Math.max(y + 4, ay1 - 22);
        button(g, new int[]{x, by, w, 16}, Component.translatable("minecraftportsmod.tree.unlock"), mouseX, mouseY, can,
                () -> send(ColonyPayloads.VillageAction.UNLOCK, t.ordinal()));
        if (st == Tree.Node.READY && !affordable) {
            g.text(font, Component.translatable("minecraftportsmod.tree.not_enough"), x, by - 10, ChartStyle.BAD, false);
        }
    }

    private void drawCenterPanel(GuiGraphicsExtractor g, ColonyPayloads.VillageView view, int x, int y, int w, int mouseX, int mouseY) {
        ColonyPayloads.CenterRow c = view.center();
        BuildingType mid = c.type() < 0 ? BuildingType.CAMPFIRE : BuildingType.values()[c.type()];
        g.item(new ItemStack(mid.icon), x, y);
        g.text(font, mid.displayName(), x + 20, y, ChartStyle.TEXT, false);
        Village.Level lvl = Village.Level.values()[view.level()];
        g.text(font, Component.empty().append(lvl.displayName()).append(" · ").append(Component.translatable("minecraftportsmod.tree.people", view.people().size())),
                x + 20, y + 9, 0xFFB07A10, false);
        y += 22;
        // the speciality
        g.text(font, Component.translatable("minecraftportsmod.tree.focus"), x, y, ChartStyle.TEXT, false);
        y += 10;
        y += 3;
        int bx = x;
        for (BuildingType.Branch b : SPECIAL) {
            Component name = b.displayName();
            int bw = font.width(name) + 8;
            if (bx + bw > x + w) {
                bx = x;
                y += 16;
            }
            boolean on = view.focus() == b.ordinal();
            int[] rect = {bx, y, bw, 14};
            if (on) {
                g.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], 0xFF7FC04A);
                g.outline(rect[0], rect[1], rect[2], rect[3], ChartStyle.WOOD_DARK);
                Ui.centered(g,font, name, rect[0] + rect[2] / 2, rect[1] + 3, ChartStyle.TEXT);
            } else {
                button(g, rect, name, mouseX, mouseY, true, () -> send(ColonyPayloads.VillageAction.FOCUS, b.ordinal()));
            }
            bx += bw + 3;
        }
        y += 20;
        // the middle's next step
        if (c.next() < 0) {
            g.textWithWordWrap(font, Component.translatable("minecraftportsmod.tree.center_top"), x, y, w, ChartStyle.TEXT_MUTED, false);
            return;
        }
        BuildingType next = BuildingType.values()[c.next()];
        g.text(font, Component.translatable("minecraftportsmod.tree.center_next"), x, y, ChartStyle.TEXT, false);
        y += 11;
        g.item(new ItemStack(next.icon), x, y - 2);
        g.text(font, next.displayName(), x + 20, y + 3, ChartStyle.ROUTE, false);
        y += 18;
        boolean people = view.people().size() >= c.people();
        g.text(font, Component.literal(people ? "✔ " : "✘ ").append(Component.translatable("minecraftportsmod.tree.center_people", view.people().size(), c.people())),
                x, y, people ? ChartStyle.GOOD : ChartStyle.BAD, false);
        y += 11;
        y = costLine(g, x, y, w, c.cost(), view.stock());
        int by = Math.max(y + 2, ay1 - 22);
        if (c.queued()) {
            g.textWithWordWrap(font, Component.translatable("minecraftportsmod.tree.center_queued"), x, by, w, ChartStyle.GOOD, false);
        } else {
            button(g, new int[]{x, by, w, 16}, Component.translatable("minecraftportsmod.tree.center_build"), mouseX, mouseY, people,
                    () -> send(ColonyPayloads.VillageAction.PIN, next.ordinal()));
        }
    }

    private static int firstOf(ColonyPayloads.VillageView view, BuildingType t) {
        for (ColonyPayloads.BuildingRow b : view.buildings()) if (b.type() == t.ordinal() && b.state() == Building.State.BUILT.ordinal()) return b.id();
        return -1;
    }

    // ------------------------------------------------------------------ input

    boolean click(double mx, double my) {
        for (Object[] b : buttons) {
            int[] r = (int[]) b[0];
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                ((Runnable) b[1]).run();
                return true;
            }
        }
        if (mx < ax0 || mx >= treeRight() || my < ay0 || my >= ay1 - STRIP_H) return false;
        int n = nodeAt(mx, my);
        dragNode = n >= 0 ? n : -2;
        moved = false;
        pressX = mx;
        pressY = my;
        return true;
    }

    boolean drag(double mx, double my, double dx, double dy) {
        if (dragNode == -1) return false;
        if (!moved && Math.hypot(mx - pressX, my - pressY) < 3) return true;
        moved = true;
        if (dragNode == -2 || dragNode == CENTER) {
            panX += dx;
            panY += dy;
        } else {
            BuildingType t = BuildingType.values()[dragNode];
            // kept within the columns
            double x = Math.max(BOX[0], Math.min(BOX[2], tx(mx))), y = Math.max(BOX[1], Math.min(BOX[3], ty(my)));
            custom.put(t.id(), new double[]{x, y});
        }
        return true;
    }

    void release() {
        if (dragNode == -1) return;
        if (!moved) {
            if (dragNode >= 0) selected = dragNode == selected ? -1 : dragNode;
        } else if (dragNode >= 0 && dragNode != CENTER) {
            save();
        }
        dragNode = -1;
    }

    boolean scroll(double mx, double my, double amount) {
        if (mx < ax0 || mx >= treeRight() || my < ay0 || my >= ay1) return false;
        float old = zoom < 0 ? 1 : zoom;
        zoom = Math.max(0.3F, Math.min(2.5F, old * (amount > 0 ? 1.15F : 0.87F)));
        double ox = (ax0 + treeRight()) / 2.0, oy = (ay0 + ay1 - STRIP_H) / 2.0;
        panX = (mx - ox) - (mx - ox - panX) * zoom / old;
        panY = (my - oy) - (my - oy - panY) * zoom / old;
        return true;
    }
}
