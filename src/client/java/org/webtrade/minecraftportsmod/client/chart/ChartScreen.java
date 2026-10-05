package org.webtrade.minecraftportsmod.client.chart;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.network.ChartPayloads;
import org.webtrade.minecraftportsmod.network.ChartPayloads.FleetAction;
import org.webtrade.minecraftportsmod.network.ChartPayloads.PortInfo;
import org.webtrade.minecraftportsmod.network.ChartPayloads.RouteInfo;
import org.webtrade.minecraftportsmod.network.ChartPayloads.VesselInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The nautical chart and port office screen: a pannable, zoomable map drawn from the server's navigation cache
 * (ports, berths, sea routes, your vessels, you), plus a side panel with three tabs:
 * <ul>
 *     <li><b>Ports</b> — pick a destination; sail there when sitting in your vessel, or send the vessel chosen in the fleet tab;</li>
 *     <li><b>Fleet</b> — your vessels; at a port office build a new one, summon one here or send one anywhere;</li>
 *     <li><b>Berths</b> — (office only) the berths of this port; add or remove them.</li>
 * </ul>
 */
public class ChartScreen extends UiScreen {

    private static final Identifier ANCHOR = Minecraftportsmod.id("textures/gui/chart/anchor.png");
    private static final Identifier ANCHOR_HOME = Minecraftportsmod.id("textures/gui/chart/anchor_home.png");
    private static final Identifier ANCHOR_OFF = Minecraftportsmod.id("textures/gui/chart/anchor_off.png");
    private static final Identifier RING = Minecraftportsmod.id("textures/gui/chart/ring.png");
    private static final Identifier COMPASS = Minecraftportsmod.id("textures/gui/chart/compass.png");
    private static final Identifier PLAYER = Minecraftportsmod.id("textures/gui/chart/player.png");
    private static final Identifier VESSEL = Minecraftportsmod.id("textures/gui/chart/vessel.png");
    private static final Identifier VESSEL_SELECTED = Minecraftportsmod.id("textures/gui/chart/vessel_selected.png");
    private static final Identifier[] VESSEL_ICON = {VESSEL,
            Minecraftportsmod.id("textures/gui/chart/vessel_sloop.png"), Minecraftportsmod.id("textures/gui/chart/vessel_brig.png")};
    private static final Identifier[] VESSEL_ICON_SELECTED = {VESSEL_SELECTED,
            Minecraftportsmod.id("textures/gui/chart/vessel_sloop_selected.png"), Minecraftportsmod.id("textures/gui/chart/vessel_brig_selected.png")};

    private static final Identifier ICON_PLUS = Minecraftportsmod.id("textures/gui/chart/icon_plus.png");
    private static final Identifier ICON_GEAR = Minecraftportsmod.id("textures/gui/chart/icon_gear.png");
    private static final Identifier ICON_EYE = Minecraftportsmod.id("textures/gui/chart/icon_eye.png");
    private static final Identifier ICON_PENCIL = Minecraftportsmod.id("textures/gui/chart/icon_pencil.png");
    private static final Identifier ICON_HALL = Minecraftportsmod.id("textures/gui/chart/icon_hall.png");
    private static final Identifier ICON_NEWS = Minecraftportsmod.id("textures/gui/chart/icon_news.png");
    private static final int SUBHEADER = 13;
    private static final int GEAR_WIDTH = 16;

    private static final float MIN_ZOOM = 0.125F;
    private static final float MAX_ZOOM = 8F;
    private static final int PANEL_WIDTH = 176;
    private static final int ROW_HEIGHT = 22;
    private static final int TAB_HEIGHT = 14;
    private static final int BUTTON_HEIGHT = 18;
    private static final int TILE = ChartTileCache.TILE;
    private static final int STATE_MOORED = 0, STATE_ANCHORED = 1, STATE_SAILING = 2, STATE_ADRIFT = 3;

    private enum Tab {
        PORTS("minecraftportsmod.chart.tab.ports"),
        FLEET("minecraftportsmod.chart.tab.fleet"),
        BERTHS("minecraftportsmod.chart.tab.berths"),
        SHIPYARD("minecraftportsmod.chart.tab.shipyard");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    // ------------------------------------------------------------------ server data
    private int mode;
    private int originId;
    private boolean canManage;
    private UUID riding;
    private final Map<Integer, PortInfo> ports = new LinkedHashMap<>();
    private final List<RouteInfo> routes = new ArrayList<>();
    private final Map<Long, RouteInfo> routeByPair = new java.util.HashMap<>();
    private final Map<UUID, VesselInfo> fleet = new LinkedHashMap<>();
    private final List<ChartPayloads.TraderInfo> traders = new ArrayList<>();
    private List<PortInfo> sortedPorts = List.of();
    private int diamonds;
    private boolean creative;

    // ------------------------------------------------------------------ view state
    private Tab tab = Tab.PORTS;
    private double centerX, centerZ, targetCenterX, targetCenterZ;
    private boolean animating;
    private float zoom = 1F;
    private boolean dragging;
    private double dragMoved;
    private int selectedPort = -1;
    private UUID selectedVessel;
    private int selectedDock = -1;
    /** Office mode: the vessel being sent; picking a port in the ports tab sends it there. */
    private UUID sending;
    private int hoveredPort = -1;
    private UUID hoveredVessel;
    private int listScroll;
    private int tickCounter;
    /** Berths view: draw all berths prominently on the chart, at any zoom. */
    private boolean highlightBerths;

    // ------------------------------------------------------------------ layout
    private int fx0, fy0, fx1, fy1;   // outer frame
    private int mx0, my0, mx1, my1;   // map viewport
    private int px0, py0, px1, py1;   // side panel (px1 == px0 when hidden)
    private final Button[] buttons = new Button[4];
    private final Runnable[] buttonActions = new Runnable[4];
    /** Dismantling asks for a second click; this is the vessel armed for it and until when. */
    private UUID dismantleArmed;
    private int dismantleArmedUntil;

    public ChartScreen(ChartPayloads.OpenChart data) {
        super(Component.translatable("minecraftportsmod.chart.title"));
        applyData(data, true);
    }

    /** Refreshes the data of an open chart without resetting the view. */
    public void update(ChartPayloads.OpenChart data) {
        boolean modeChanged = data.mode() != mode;
        applyData(data, modeChanged);
        if (modeChanged) rebuildWidgets();
    }

    /** Selects a destination as if it was clicked in the list (also used by automated tests). */
    public void selectPort(int portId) {
        tab = Tab.PORTS;
        select(portId, true);
    }

    /** Zooms out by a number of steps (automated tests; players scroll). */
    public void zoomOut(int steps) {
        zoom = Math.max(MIN_ZOOM, zoom / (float) Math.pow(1.25, steps * 2));
    }

    /** Toggles the berth highlight (automated tests; players use the eye icon). */
    public void toggleBerthHighlight() {
        highlightBerths = !highlightBerths;
    }

    /** Switches tabs programmatically (automated tests). */
    public void showTab(String name) {
        for (Tab t : availableTabs()) {
            if (t.name().equalsIgnoreCase(name)) tab = t;
        }
    }

    private void applyData(ChartPayloads.OpenChart data, boolean resetView) {
        mode = data.mode();
        originId = data.originPortId();
        canManage = data.canManage();
        riding = data.riding();
        ports.clear();
        data.ports().forEach(p -> ports.put(p.id(), p));
        routes.clear();
        routes.addAll(data.routes());
        routeByPair.clear();
        for (RouteInfo r : routes) routeByPair.put(pairKey(r.portA(), r.portB()), r);
        fleet.clear();
        data.fleet().forEach(v -> fleet.put(v.id(), v));
        traders.clear();
        traders.addAll(data.traders());
        diamonds = data.diamonds();
        creative = data.creative();

        List<PortInfo> list = new ArrayList<>(ports.values());
        list.sort(Comparator.comparingInt((PortInfo p) -> p.id() == originId ? 0 : 1).thenComparingDouble(this::sortDistance));
        sortedPorts = list;

        if (resetView) {
            PortInfo origin = ports.get(originId);
            LocalPlayer player = Minecraft.getInstance().player;
            if (origin != null) {
                centerX = origin.x();
                centerZ = origin.z();
            } else if (player != null) {
                centerX = player.getX();
                centerZ = player.getZ();
            }
            targetCenterX = centerX;
            targetCenterZ = centerZ;
            zoom = 1F;
            selectedPort = -1;
            selectedDock = -1;
            sending = null;
            tab = Tab.PORTS;
            if (mode == ChartPayloads.MODE_OFFICE && selectedVessel == null) {
                // preselect a vessel of ours moored here, it is the one you most likely want to send
                fleet.values().stream().filter(v -> v.portId() == originId).findFirst().ifPresent(v -> selectedVessel = v.id());
            }
            ChartTileCache.beginSession();
        }
        if (!ports.containsKey(selectedPort)) selectedPort = -1;
        if (selectedVessel != null && !fleet.containsKey(selectedVessel)) selectedVessel = null;
        if (sending != null && !fleet.containsKey(sending)) sending = null;
        if (!availableTabs().contains(tab)) tab = Tab.PORTS;
    }

    private List<Tab> availableTabs() {
        return mode == ChartPayloads.MODE_OFFICE ? List.of(Tab.PORTS, Tab.FLEET, Tab.BERTHS, Tab.SHIPYARD) : List.of(Tab.PORTS, Tab.FLEET);
    }

    /** Tabs shown as labels; the berths view opens from the gear at the end of the bar. */
    private List<Tab> barTabs() {
        return mode == ChartPayloads.MODE_OFFICE ? List.of(Tab.PORTS, Tab.FLEET, Tab.SHIPYARD) : List.of(Tab.PORTS, Tab.FLEET);
    }

    private boolean hasGear() {
        return mode == ChartPayloads.MODE_OFFICE;
    }

    /** A small icon button in the panel's sub-header. */
    private record HeaderIcon(Identifier icon, Component tooltip, boolean active, boolean toggled, Runnable action) {
    }

    private List<HeaderIcon> headerIcons() {
        List<HeaderIcon> icons = new ArrayList<>();
        if (tab == Tab.PORTS) {
            icons.add(new HeaderIcon(ICON_NEWS, Component.translatable("minecraftportsmod.news.title"), true, false,
                    () -> Minecraft.getInstance().gui.setScreen(new NewsScreen(this))));
            PortInfo town = hallPort();
            if (town != null) {
                icons.add(new HeaderIcon(ICON_HALL, Component.translatable("minecraftportsmod.chart.town_hall", town.name()), true, false,
                        () -> openTownHall(town.id())));
            }
        }
        if (mode != ChartPayloads.MODE_OFFICE) return icons;
        if (tab == Tab.FLEET) {
            VesselInfo sel = selectedVessel == null ? null : fleet.get(selectedVessel);
            if (sel != null) {
                icons.add(new HeaderIcon(ICON_PENCIL, Component.translatable("minecraftportsmod.chart.rename_vessel", sel.name()), true, false,
                        () -> Minecraft.getInstance().gui.setScreen(new RenameScreen(this, Component.translatable("minecraftportsmod.chart.rename_vessel", sel.name()),
                                sel.name(), name -> ClientPlayNetworking.send(new ChartPayloads.Rename(-1, sel.id(), name))))));
            }
            icons.add(new HeaderIcon(ICON_PLUS, Component.translatable("minecraftportsmod.chart.build_tooltip"), true, false,
                    () -> action(FleetAction.of(FleetAction.Action.BUILD), false)));
        } else if (tab == Tab.BERTHS) {
            PortInfo office = ports.get(originId);
            if (office != null) {
                icons.add(new HeaderIcon(ICON_PENCIL, canManage ? Component.translatable("minecraftportsmod.chart.rename_port", office.name())
                        : Component.translatable("minecraftportsmod.chart.not_owner"), canManage, false,
                        () -> Minecraft.getInstance().gui.setScreen(new RenameScreen(this, Component.translatable("minecraftportsmod.chart.rename_port", office.name()),
                                office.name(), name -> ClientPlayNetworking.send(new ChartPayloads.Rename(office.id(), null, name))))));
            }
            icons.add(new HeaderIcon(ICON_EYE, Component.translatable("minecraftportsmod.chart.highlight_berths"), true, highlightBerths,
                    () -> highlightBerths = !highlightBerths));
            icons.add(new HeaderIcon(ICON_PLUS, canManage ? Component.translatable("minecraftportsmod.chart.add_berth")
                    : Component.translatable("minecraftportsmod.chart.not_owner"), canManage, false,
                    () -> action(FleetAction.of(FleetAction.Action.ADD_BERTH), false)));
        }
        return icons;
    }

    /** The settled port whose town hall the header icon opens: the selected one, else the office's own. */
    private PortInfo hallPort() {
        PortInfo p = ports.get(selectedPort);
        if (p == null) p = ports.get(originId);
        return p != null && p.settled() ? p : null;
    }

    /** Opens the town hall of a settled port (also used by automated tests). */
    public void openTownHall(int portId) {
        Minecraft.getInstance().gui.setScreen(new TownHallScreen(this, portId));
    }

    /** Right-to-left positions of the header icons: x of each 12x12 icon. */
    private int headerIconX(int index) {
        return px1 - 16 - index * 15;
    }

    private int headerY() {
        return py0 + TAB_HEIGHT + 1;
    }

    private Component headerTitle() {
        return switch (tab) {
            case PORTS -> Component.translatable("minecraftportsmod.chart.ports", ports.size());
            case FLEET -> Component.translatable("minecraftportsmod.chart.fleet_count", fleet.size(), 8);
            case BERTHS -> {
                PortInfo office = ports.get(originId);
                yield Component.translatable("minecraftportsmod.chart.berths_count", office == null ? 0 : office.docks());
            }
            case SHIPYARD -> Component.translatable("minecraftportsmod.chart.tab.shipyard");
        };
    }

    private double sortDistance(PortInfo p) {
        RouteInfo r = route(originId, p.id());
        if (r != null && r.status() == 1) return r.length();
        double ox, oz;
        PortInfo origin = ports.get(originId);
        LocalPlayer pl = Minecraft.getInstance().player;
        if (origin != null) {
            ox = origin.x();
            oz = origin.z();
        } else {
            ox = pl == null ? 0 : pl.getX();
            oz = pl == null ? 0 : pl.getZ();
        }
        return 100000 + Math.hypot(p.x() - ox, p.z() - oz);
    }

    private static long pairKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
    }

    private RouteInfo route(int a, int b) {
        return routeByPair.get(pairKey(a, b));
    }

    private VesselInfo ridingVessel() {
        return riding == null ? null : fleet.get(riding);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void layout() {
        int m = width < 360 ? 4 : 10;
        fx0 = m;
        fy0 = m;
        fx1 = width - m;
        fy1 = height - m;
        boolean panel = width >= 400;
        mx0 = fx0 + 6;
        my0 = fy0 + 22;
        my1 = fy1 - 6;
        mx1 = panel ? fx1 - 6 - PANEL_WIDTH - 6 : fx1 - 6;
        px0 = panel ? mx1 + 6 : mx1;
        px1 = panel ? fx1 - 6 : mx1;
        py0 = my0;
        py1 = my1;

        int bw = panel ? px1 - px0 - 10 : 130;
        int bx = panel ? px0 + 5 : mx1 - bw - 8;
        int bottom = panel ? py1 - 4 : my1 - 6;
        for (int i = 0; i < buttons.length; i++) {
            final int index = i;
            int by = bottom - (buttons.length - i) * (BUTTON_HEIGHT + 2);
            buttons[i] = addRenderableWidget(UiButton.make(Component.empty(), b -> {
                if (buttonActions[index] != null) buttonActions[index].run();
            }).bounds(bx, by, bw, BUTTON_HEIGHT).build());
        }
        updateButtons();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int visibleButtonCount() {
        int n = 0;
        for (Button b : buttons) if (b != null && b.visible) n++;
        return n;
    }

    // ------------------------------------------------------------------ buttons per tab

    private void setButton(int i, Component label, boolean active, Component tooltip, Runnable action) {
        Button b = buttons[i];
        if (b == null) return;
        b.visible = label != null;
        b.active = active;
        if (label != null) b.setMessage(label);
        b.setTooltip(tooltip == null ? null : Tooltip.create(tooltip));
        buttonActions[i] = action;
    }

    private void clearButtons() {
        for (int i = 0; i < buttons.length; i++) setButton(i, null, false, null, null);
    }

    private void updateButtons() {
        if (buttons[0] == null) return;
        clearButtons();
        switch (tab) {
            case PORTS -> portButtons();
            case FLEET -> fleetButtons();
            case BERTHS -> berthButtons();
            case SHIPYARD -> shipyardButtons();
        }
        // stack visible buttons at the bottom
        int vis = visibleButtonCount();
        int bottom = px1 > px0 ? py1 - 4 : my1 - 6;
        int k = 0;
        for (Button b : buttons) {
            if (!b.visible) continue;
            b.setY(bottom - (vis - k) * (BUTTON_HEIGHT + 2));
            k++;
        }
    }

    private void portButtons() {
        PortInfo dest = ports.get(selectedPort);
        if (mode == ChartPayloads.MODE_BOAT) {
            VesselInfo v = ridingVessel();
            Component why = dest == null ? Component.translatable("minecraftportsmod.chart.pick_port")
                    : dest.id() == originId ? Component.translatable("minecraftportsmod.chart.already_there")
                    : v != null && v.planning() ? Component.translatable("minecraftportsmod.chart.planning") : null;
            setButton(2, Component.translatable("minecraftportsmod.chart.sail_here"), why == null, why,
                    () -> action(new FleetAction(FleetAction.Action.SAIL, null, dest.id(), -1), true));
            if (v != null && v.state() == STATE_SAILING) {
                setButton(1, Component.translatable("minecraftportsmod.chart.stop"), true, null,
                        () -> action(FleetAction.of(FleetAction.Action.STOP), false));
            }
            setButton(0, Component.translatable("minecraftportsmod.chart.open_hold"), true, null,
                    () -> action(FleetAction.of(FleetAction.Action.OPEN_HOLD), false));
        } else if (mode == ChartPayloads.MODE_OFFICE && sending != null) {
            VesselInfo v = fleet.get(sending);
            Component why = dest == null ? Component.translatable("minecraftportsmod.chart.pick_port")
                    : v != null && v.portId() == dest.id() && v.state() != STATE_SAILING
                    ? Component.translatable("minecraftportsmod.chart.already_there") : null;
            setButton(1, Component.translatable("minecraftportsmod.chart.send_here", v == null ? "?" : v.name()), why == null, why, () -> {
                action(new FleetAction(FleetAction.Action.SEND, sending, dest.id(), -1), false);
                sending = null;
                tab = Tab.FLEET;
            });
            setButton(2, Component.translatable("gui.cancel"), true, null, () -> sending = null);
        }
    }

    private void fleetButtons() {
        if (mode != ChartPayloads.MODE_OFFICE) return;
        VesselInfo v = selectedVessel == null ? null : fleet.get(selectedVessel);
        if (v == null) return;
        boolean here = v.portId() == originId && v.state() != STATE_SAILING
                || v.state() == STATE_SAILING && v.destPortId() == originId;
        Component why = here ? Component.translatable("minecraftportsmod.chart.already_there")
                : v.planning() ? Component.translatable("minecraftportsmod.chart.planning") : null;
        setButton(1, Component.translatable("minecraftportsmod.chart.summon"), why == null, why,
                () -> action(new FleetAction(FleetAction.Action.SUMMON, v.id(), originId, -1), false));
        setButton(2, Component.translatable("minecraftportsmod.chart.send_to"), !v.planning(), null, () -> {
            sending = v.id();
            tab = Tab.PORTS;
            selectedPort = -1;
        });
        boolean armed = v.id().equals(dismantleArmed) && tickCounter < dismantleArmedUntil;
        setButton(3, Component.translatable(armed ? "minecraftportsmod.chart.dismantle_confirm" : "minecraftportsmod.chart.dismantle")
                        .withStyle(armed ? ChatFormatting.RED : ChatFormatting.RESET), true,
                Component.translatable("minecraftportsmod.chart.dismantle_hint"), () -> {
                    if (armed) {
                        action(new FleetAction(FleetAction.Action.DISMANTLE, v.id(), -1, -1), false);
                        dismantleArmed = null;
                        selectedVessel = null;
                    } else {
                        dismantleArmed = v.id();
                        dismantleArmedUntil = tickCounter + 60;
                    }
                });
    }

    private void berthButtons() {
        if (mode != ChartPayloads.MODE_OFFICE) return;
        Component noRights = canManage ? null : Component.translatable("minecraftportsmod.chart.not_owner");
        Component why = noRights != null ? noRights
                : selectedDock < 0 ? Component.translatable("minecraftportsmod.chart.pick_berth") : null;
        final int dock = selectedDock;
        boolean locked = berthLocked(dock);
        setButton(2, Component.translatable(locked ? "minecraftportsmod.chart.unlock_berth" : "minecraftportsmod.chart.lock_berth"),
                why == null, why != null ? why : Component.translatable(locked
                        ? "minecraftportsmod.chart.unlock_berth_hint" : "minecraftportsmod.chart.lock_berth_hint"),
                () -> action(new FleetAction(FleetAction.Action.LOCK_BERTH, null, originId, dock, locked ? 0 : 1, 0), false));
        setButton(3, Component.translatable("minecraftportsmod.chart.remove_berth"), why == null,
                why != null ? why : Component.translatable("minecraftportsmod.chart.remove_berth_hint"), () -> {
                    action(new FleetAction(FleetAction.Action.REMOVE_BERTH, null, originId, dock), false);
                    selectedDock = -1;
                });
    }

    private boolean berthLocked(int dockId) {
        PortInfo office = ports.get(originId);
        if (office == null) return false;
        int[] b = office.berths();
        for (int i = 0; i < b.length; i += PortInfo.BERTH_STRIDE) if (b[i] == dockId) return b[i + 4] != 0;
        return false;
    }

    /** Vessels that can be worked on here: ours, moored at this port. */
    private boolean inShipyard(VesselInfo v) {
        return v.portId() == originId && v.state() == STATE_MOORED;
    }

    private void shipyardButtons() {
        if (mode != ChartPayloads.MODE_OFFICE) return;
        VesselInfo v = selectedVessel == null ? null : fleet.get(selectedVessel);
        if (v == null || !inShipyard(v)) {
            setButton(3, Component.translatable("minecraftportsmod.shipyard.upgrade_generic"), false,
                    Component.translatable("minecraftportsmod.shipyard.pick"), null);
            return;
        }
        org.webtrade.minecraftportsmod.fleet.VesselType next = org.webtrade.minecraftportsmod.fleet.VesselType.byTier(v.tier()).next();
        if (next == null) {
            setButton(3, Component.translatable("minecraftportsmod.shipyard.top"), false, null, null);
            return;
        }
        boolean affordable = creative || diamonds >= next.upgradeCost;
        Component why = affordable ? null : Component.translatable("minecraftportsmod.shipyard.not_enough", next.upgradeCost, diamonds);
        setButton(3, Component.translatable("minecraftportsmod.shipyard.upgrade", next.displayName(), next.upgradeCost), affordable, why,
                () -> action(new FleetAction(FleetAction.Action.UPGRADE, v.id(), originId, -1), false));
    }

    private Identifier vesselIcon(VesselInfo v, boolean selected) {
        int t = Math.max(0, Math.min(2, v.tier()));
        return selected ? VESSEL_ICON_SELECTED[t] : VESSEL_ICON[t];
    }

    private void action(FleetAction action, boolean close) {
        ClientPlayNetworking.send(action);
        if (close) onClose();
    }

    // ------------------------------------------------------------------ selection

    private void select(int portId, boolean center) {
        selectedPort = portId;
        PortInfo p = ports.get(portId);
        if (p != null && center) centerOn(p.x(), p.z());
    }

    private void selectVessel(UUID id, boolean center) {
        selectedVessel = id;
        VesselInfo v = id == null ? null : fleet.get(id);
        if (v != null && center) centerOn(v.x(), v.z());
    }

    private void centerOn(double x, double z) {
        targetCenterX = x;
        targetCenterZ = z;
        animating = true;
    }

    // ------------------------------------------------------------------ coordinates

    private float toScreenX(double wx) {
        return (float) ((mx0 + mx1) / 2.0 + (wx - centerX) * zoom);
    }

    private float toScreenY(double wz) {
        return (float) ((my0 + my1) / 2.0 + (wz - centerZ) * zoom);
    }

    private double toWorldX(double sx) {
        return centerX + (sx - (mx0 + mx1) / 2.0) / zoom;
    }

    private double toWorldZ(double sy) {
        return centerZ + (sy - (my0 + my1) / 2.0) / zoom;
    }

    private boolean inMap(double x, double y) {
        return x >= mx0 && x < mx1 && y >= my0 && y < my1;
    }

    private boolean inPanel(double x, double y) {
        return px1 > px0 && x >= px0 && x < px1 && y >= py0 && y < py1;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        tickCounter++;
        if (tickCounter % 4 == 0) {
            int minTx = Math.floorDiv((int) Math.floor(toWorldX(mx0)), TILE);
            int maxTx = Math.floorDiv((int) Math.floor(toWorldX(mx1)), TILE);
            int minTz = Math.floorDiv((int) Math.floor(toWorldZ(my0)), TILE);
            int maxTz = Math.floorDiv((int) Math.floor(toWorldZ(my1)), TILE);
            ChartTileCache.requestVisible(minTx, minTz, maxTx, maxTz,
                    Math.floorDiv((int) centerX, TILE), Math.floorDiv((int) centerZ, TILE));
        }
        // live positions of the fleet, voyage progress, route status
        if (tickCounter % 40 == 0 && ClientPlayNetworking.canSend(ChartPayloads.RequestChart.TYPE)) {
            ClientPlayNetworking.send(new ChartPayloads.RequestChart(true));
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ChartTileCache.uploadReady();
        if (animating) {
            centerX += (targetCenterX - centerX) * 0.25;
            centerZ += (targetCenterZ - centerZ) * 0.25;
            if (Math.abs(targetCenterX - centerX) < 0.5 && Math.abs(targetCenterZ - centerZ) < 0.5) {
                centerX = targetCenterX;
                centerZ = targetCenterZ;
                animating = false;
            }
        }
        hoveredVessel = inMap(mouseX, mouseY) ? vesselAt(mouseX, mouseY) : null;
        hoveredPort = inMap(mouseX, mouseY) && hoveredVessel == null ? portAt(mouseX, mouseY) : -1;
        updateButtons();

        drawFrame(g);
        drawMap(g, mouseX, mouseY);
        if (px1 > px0) drawPanel(g, mouseX, mouseY);

        widgets(g, mouseX, mouseY, partialTick);

        ChartPayloads.TraderInfo trader = inMap(mouseX, mouseY) && hoveredVessel == null ? traderAt(mouseX, mouseY) : null;
        if (hoveredVessel != null) {
            g.setComponentTooltipForNextFrame(font, vesselTooltip(fleet.get(hoveredVessel)), mouseX, mouseY);
        } else if (trader != null && hoveredPort < 0) {
            g.setComponentTooltipForNextFrame(font, traderTooltip(trader), mouseX, mouseY);
        } else if (hoveredPort >= 0) {
            g.setComponentTooltipForNextFrame(font, portTooltip(ports.get(hoveredPort)), mouseX, mouseY);
        }
    }

    private void drawFrame(GuiGraphicsExtractor g) {
        Ui.frame(g, fx0, fy0, fx1, fy1);

        PortInfo origin = ports.get(originId);
        VesselInfo boat = ridingVessel();
        Component title = mode == ChartPayloads.MODE_OFFICE && origin != null
                ? Component.translatable("minecraftportsmod.chart.title_office", origin.name())
                : mode == ChartPayloads.MODE_BOAT && boat != null && origin != null
                ? Component.translatable("minecraftportsmod.chart.title_boat_at", boat.name(), origin.name())
                : mode == ChartPayloads.MODE_BOAT && boat != null
                ? Component.translatable("minecraftportsmod.chart.title_boat", boat.name())
                : Component.translatable("minecraftportsmod.chart.title");
        g.text(font, title, fx0 + 12, fy0 + 8, ChartStyle.TEXT_LIGHT, false);

        Component hints = Component.translatable("minecraftportsmod.chart.hints");
        int hw = font.width(hints);
        if (fx0 + 12 + font.width(title) + 20 + hw < fx1 - 12) {
            g.text(font, hints, fx1 - 12 - hw, fy0 + 8, ChartStyle.PARCHMENT_SHADE, false);
        }

        g.fill(mx0 - 2, my0 - 2, mx1 + 2, my1 + 2, ChartStyle.PARCHMENT_SHADE);
        g.fill(mx0, my0, mx1, my1, ChartStyle.PARCHMENT_DARK);
    }

    private void drawMap(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.enableScissor(mx0, my0, mx1, my1);

        int minTx = Math.floorDiv((int) Math.floor(toWorldX(mx0)), TILE);
        int maxTx = Math.floorDiv((int) Math.floor(toWorldX(mx1)), TILE);
        int minTz = Math.floorDiv((int) Math.floor(toWorldZ(my0)), TILE);
        int maxTz = Math.floorDiv((int) Math.floor(toWorldZ(my1)), TILE);
        for (int tx = minTx; tx <= maxTx; tx++) {
            for (int tz = minTz; tz <= maxTz; tz++) {
                Identifier tex = ChartTileCache.texture(tx, tz);
                if (tex == null) continue;
                g.pose().pushMatrix();
                g.pose().translate(toScreenX(tx * TILE), toScreenY(tz * TILE));
                g.pose().scale(zoom, zoom);
                g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, TILE, TILE, TILE, TILE);
                g.pose().popMatrix();
            }
        }

        drawGrid(g);
        drawRoutes(g);
        drawBerths(g);
        drawPorts(g);
        drawTraders(g);
        drawVessels(g);
        drawPlayer(g);
        drawCompassAndScale(g);

        if (sending != null) {
            VesselInfo v = fleet.get(sending);
            Component banner = Component.translatable("minecraftportsmod.chart.sending_banner", v == null ? "?" : v.name());
            int w = font.width(banner);
            int x = (mx0 + mx1 - w) / 2;
            g.fill(x - 6, my0 + 4, x + w + 6, my0 + 17, 0xE0A8322A);
            g.text(font, banner, x, my0 + 7, ChartStyle.TEXT_LIGHT, false);
        } else if (inMap(mouseX, mouseY)) {
            String coords = "X " + (int) Math.floor(toWorldX(mouseX)) + "   Z " + (int) Math.floor(toWorldZ(mouseY));
            int w = font.width(coords);
            g.fill(mx0 + 4, my0 + 4, mx0 + 10 + w, my0 + 16, 0xC8EADBB5);
            g.outline(mx0 + 4, my0 + 4, w + 6, 12, ChartStyle.INK_SOFT);
            g.text(font, coords, mx0 + 7, my0 + 6, ChartStyle.TEXT, false);
        }

        g.disableScissor();
        g.outline(mx0 - 1, my0 - 1, mx1 - mx0 + 2, my1 - my0 + 2, ChartStyle.INK);
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        int step = 64;
        while (step * zoom < 70) step *= 2;
        int color = 0x2A3B2A1C;
        for (long x = (long) Math.floor(toWorldX(mx0) / step) * step; x <= toWorldX(mx1); x += step) {
            int sx = Math.round(toScreenX(x));
            g.fill(sx, my0, sx + 1, my1, color);
        }
        for (long z = (long) Math.floor(toWorldZ(my0) / step) * step; z <= toWorldZ(my1); z += step) {
            int sy = Math.round(toScreenY(z));
            g.fill(mx0, sy, mx1, sy + 1, color);
        }
    }

    /** The port routes are drawn from: the office port, or the port of the vessel being sent. */
    private int routeFocusPort() {
        if (sending != null) {
            VesselInfo v = fleet.get(sending);
            if (v != null && v.state() != STATE_SAILING && v.portId() >= 0) return v.portId();
            return -1;
        }
        return originId;
    }

    private void drawRoutes(GuiGraphicsExtractor g) {
        int focus = routeFocusPort();
        int activeDest = selectedPort >= 0 ? selectedPort : hoveredPort;
        float march = (tickCounter % 20) / 20F * 7F;
        for (int pass = 0; pass < 3; pass++) {
            for (RouteInfo r : routes) {
                if (r.path().length < 4) continue;
                boolean fromFocus = focus >= 0 && (r.portA() == focus || r.portB() == focus);
                boolean highlight = fromFocus && (r.portA() == activeDest || r.portB() == activeDest);
                int routePass = highlight ? 2 : fromFocus ? 1 : 0;
                if (routePass != pass) continue;
                int color, thick;
                if (r.status() != 1) {
                    color = ChartStyle.ROUTE_BLOCKED;
                    thick = 1;
                } else if (highlight) {
                    color = ChartStyle.ROUTE_HIGHLIGHT;
                    thick = 2;
                } else if (fromFocus) {
                    color = ChartStyle.ROUTE;
                    thick = zoom >= 0.5F ? 2 : 1;
                } else {
                    color = ChartStyle.ROUTE_FAINT;
                    thick = 1;
                }
                drawPolyline(g, r.path(), color, thick, highlight ? march : 0);
            }
        }
    }

    private void drawPolyline(GuiGraphicsExtractor g, int[] path, int color, int thick, float phase) {
        float dist = phase;
        for (int i = 2; i < path.length; i += 2) {
            dist = dashedLine(g, toScreenX(path[i - 2] + 0.5), toScreenY(path[i - 1] + 0.5),
                    toScreenX(path[i] + 0.5), toScreenY(path[i + 1] + 0.5), thick, color, 4, 3, dist);
        }
    }

    /** Draws a dashed line clipped to the map viewport; returns the dash distance to continue from. */
    private float dashedLine(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int thick, int color,
                             int on, int off, float dist) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01F) return dist;
        float t0 = 0, t1 = 1;
        float[] p = {-dx, dx, -dy, dy};
        float[] q = {x0 - (mx0 - 2), (mx1 + 2) - x0, y0 - (my0 - 2), (my1 + 2) - y0};
        for (int k = 0; k < 4; k++) {
            if (p[k] == 0) {
                if (q[k] < 0) return dist + len;
            } else {
                float t = q[k] / p[k];
                if (p[k] < 0) t0 = Math.max(t0, t);
                else t1 = Math.min(t1, t);
            }
        }
        if (t0 > t1) return dist + len;
        float period = on + off;
        float half = thick / 2F;
        for (float s = t0 * len; s <= t1 * len; s += 1F) {
            if (off > 0 && ((dist + s) % period) >= on) continue;
            int ix = Math.round(x0 + dx * s / len - half), iy = Math.round(y0 + dy * s / len - half);
            g.fill(ix, iy, ix + thick, iy + thick, color);
        }
        return dist + len;
    }

    private void drawBerths(GuiGraphicsExtractor g) {
        boolean berthTab = tab == Tab.BERTHS;
        boolean highlight = berthTab && highlightBerths;
        if (zoom < 1F && !berthTab) return;
        float pulse = 0.5F + 0.5F * Mth.sin(tickCounter * 0.3F);
        for (PortInfo p : ports.values()) {
            int[] b = p.berths();
            for (int i = 0; i < b.length; i += PortInfo.BERTH_STRIDE) {
                int sx = Math.round(toScreenX(b[i + 1] + 0.5)), sy = Math.round(toScreenY(b[i + 2] + 0.5));
                if (!inMap(sx, sy)) continue;
                int s = Math.max(3, Math.min(7, Math.round(zoom * 1.5F)));
                if (highlight) {
                    int r = s + 3 + Math.round(pulse * 3);
                    g.fill(sx - r, sy - r, sx + r + 1, sy + r + 1, ChartStyle.withAlpha(ChartStyle.BRASS, 90));
                    g.outline(sx - r, sy - r, r * 2 + 1, r * 2 + 1, ChartStyle.ROUTE_HIGHLIGHT);
                }
                boolean selected = berthTab && p.id() == originId && b[i] == selectedDock;
                int color = selected ? ChartStyle.ROUTE_HIGHLIGHT : b[i + 3] != 0 ? ChartStyle.INK : ChartStyle.GOOD;
                if (b[i + 4] != 0) g.outline(sx - s - 2, sy - s - 2, s * 2 + 5, s * 2 + 5, ChartStyle.BRASS_DARK);
                g.outline(sx - s, sy - s, s * 2 + 1, s * 2 + 1, color);
                if (selected) g.outline(sx - s - 1, sy - s - 1, s * 2 + 3, s * 2 + 3, color);
                if (berthTab && p.id() == originId && (zoom >= 1.5F || highlight)) {
                    String n = "#" + b[i];
                    g.text(font, n, sx - font.width(n) / 2, sy - s - 10, ChartStyle.TEXT, false);
                }
            }
        }
    }

    private void drawPorts(GuiGraphicsExtractor g) {
        boolean labels = zoom >= 0.5F;
        int focus = routeFocusPort();
        for (PortInfo p : ports.values()) {
            int sx = Math.round(toScreenX(p.x() + 0.5));
            int sy = Math.round(toScreenY(p.z() + 0.5));
            if (sx < mx0 - 20 || sx > mx1 + 20 || sy < my0 - 20 || sy > my1 + 20) continue;

            if (p.id() == selectedPort) {
                float pulse = 0.75F + 0.25F * Mth.sin(tickCounter * 0.25F);
                int size = Math.round(22 * pulse);
                g.blit(RenderPipelines.GUI_TEXTURED, RING, sx - size / 2, sy - size / 2, 0, 0, size, size, 32, 32, 32, 32);
            }
            Identifier icon = p.id() == originId ? ANCHOR_HOME : (focus >= 0 && p.id() != focus && !reachable(focus, p.id())) ? ANCHOR_OFF : ANCHOR;
            int s = p.id() == hoveredPort ? 14 : 12;
            g.blit(RenderPipelines.GUI_TEXTURED, icon, sx - s / 2, sy - s / 2, 0, 0, s, s, 16, 16, 16, 16);

            if (labels || p.id() == originId || p.id() == selectedPort || p.id() == hoveredPort) {
                String name = p.name();
                int w = font.width(name);
                int lx = sx - w / 2;
                int ly = sy + 8;
                g.fill(lx - 3, ly - 1, lx + w + 3, ly + 9, 0xD8EADBB5);
                g.outline(lx - 3, ly - 1, w + 6, 10, p.id() == originId ? ChartStyle.BRASS_DARK : ChartStyle.INK_SOFT);
                g.text(font, name, lx, ly, ChartStyle.TEXT, false);
                if (p.settled()) {
                    // a settlement: a stripe in the colour of its trade under the name
                    int c = 0xFF000000 | SettlementStyle.specColor(org.webtrade.minecraftportsmod.economy.Specialization.values()[p.spec()]);
                    g.fill(lx - 3, ly + 9, lx + w + 3, ly + 11, c);
                }
            }
        }
    }

    private boolean reachable(int from, int to) {
        RouteInfo r = route(from, to);
        return r != null && r.status() == 1;
    }

    private void drawVessels(GuiGraphicsExtractor g) {
        for (VesselInfo v : fleet.values()) {
            float sx = toScreenX(v.x()), sy = toScreenY(v.z());
            if (!inMap(sx, sy)) continue;
            // a thin dotted course line to where it is heading
            if (v.state() == STATE_SAILING && ports.containsKey(v.destPortId())) {
                PortInfo d = ports.get(v.destPortId());
                dashedLine(g, sx, sy, toScreenX(d.x() + 0.5), toScreenY(d.z() + 0.5), 1, 0xAA23344F, 2, 3, 0);
            }
            boolean sel = v.id().equals(selectedVessel) || v.id().equals(sending);
            int s = v.id().equals(hoveredVessel) || sel ? 14 : 12;
            g.pose().pushMatrix();
            g.pose().translate(sx, sy);
            g.pose().rotate((float) Math.toRadians(v.yaw() + 180F));
            g.blit(RenderPipelines.GUI_TEXTURED, vesselIcon(v, sel), -s / 2, -s / 2, 0, 0, s, s, 16, 16, 16, 16);
            g.pose().popMatrix();
        }
    }

    /** Settlements' trade vessels: smaller, with a pennant in the colour of their home's trade. */
    private void drawTraders(GuiGraphicsExtractor g) {
        for (ChartPayloads.TraderInfo t : traders) {
            float sx = toScreenX(t.x()), sy = toScreenY(t.z());
            if (!inMap(sx, sy)) continue;
            PortInfo home = ports.get(t.home());
            int color = home != null && home.settled()
                    ? 0xFF000000 | SettlementStyle.specColor(org.webtrade.minecraftportsmod.economy.Specialization.values()[home.spec()])
                    : ChartStyle.INK_SOFT;
            g.pose().pushMatrix();
            g.pose().translate(sx, sy);
            g.pose().rotate((float) Math.toRadians(t.yaw() + 180F));
            g.blit(RenderPipelines.GUI_TEXTURED, VESSEL_ICON[Math.min(t.tier(), VESSEL_ICON.length - 1)], -5, -5, 0, 0, 10, 10, 16, 16, 16, 16);
            g.pose().popMatrix();
            g.fill(Math.round(sx) + 3, Math.round(sy) - 7, Math.round(sx) + 7, Math.round(sy) - 4, color);
            g.fill(Math.round(sx) + 3, Math.round(sy) - 7, Math.round(sx) + 4, Math.round(sy) - 1, ChartStyle.INK);
        }
    }

    private ChartPayloads.TraderInfo traderAt(double mx, double my) {
        for (ChartPayloads.TraderInfo t : traders) {
            float sx = toScreenX(t.x()), sy = toScreenY(t.z());
            if (Math.abs(mx - sx) <= 6 && Math.abs(my - sy) <= 6) return t;
        }
        return null;
    }

    private List<Component> traderTooltip(ChartPayloads.TraderInfo t) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(t.name()).withStyle(ChatFormatting.BOLD));
        PortInfo home = ports.get(t.home());
        lines.add(Component.translatable("minecraftportsmod.chart.trader_of", home == null ? "?" : home.name()).withStyle(ChatFormatting.GRAY));
        PortInfo dest = ports.get(t.dest());
        if (dest != null) lines.add(Component.translatable("minecraftportsmod.chart.trader_to", dest.name()).withStyle(ChatFormatting.BLUE));
        if (t.cargo().length == 0) {
            lines.add(Component.translatable("minecraftportsmod.chart.trader_empty").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("minecraftportsmod.chart.trader_cargo").withStyle(ChatFormatting.GOLD));
            for (int i = 0; i + 1 < t.cargo().length && i < 12; i += 2) {
                var g = org.webtrade.minecraftportsmod.economy.Good.values()[t.cargo()[i]];
                lines.add(Component.literal("  " + t.cargo()[i + 1] + " × ").append(g.displayName()).withStyle(ChatFormatting.GRAY));
            }
        }
        return lines;
    }

    private void drawPlayer(GuiGraphicsExtractor g) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        float sx = toScreenX(player.getX());
        float sy = toScreenY(player.getZ());
        if (!inMap(sx, sy)) return;
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().rotate((float) Math.toRadians(player.getYRot() + 180F));
        g.blit(RenderPipelines.GUI_TEXTURED, PLAYER, -6, -6, 0, 0, 12, 12, 16, 16, 16, 16);
        g.pose().popMatrix();
    }

    private void drawCompassAndScale(GuiGraphicsExtractor g) {
        int size = Math.min(56, (my1 - my0) / 4);
        if (size >= 24) {
            g.blit(RenderPipelines.GUI_TEXTURED, COMPASS, mx0 + 6, my1 - size - 6, 0, 0, size, size, 64, 64, 64, 64);
        }
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
        for (int i = 0; i < 4; i++) {
            int a = x0 + px * i / 4, b = x0 + px * (i + 1) / 4;
            g.fill(a, y, b, y + 3, i % 2 == 0 ? ChartStyle.INK : 0xFFF3E6C4);
        }
        g.outline(x0, y, px, 3, ChartStyle.INK);
        Component label = Component.translatable("minecraftportsmod.chart.blocks", blocks);
        g.text(font, label, x0 + (px - font.width(label)) / 2, y - 10, ChartStyle.TEXT, false);
    }

    // ------------------------------------------------------------------ side panel

    private int tabsBottom() {
        return py0 + TAB_HEIGHT + 2;
    }

    private int listTop() {
        return tabsBottom() + SUBHEADER + 1;
    }

    /** The list takes the room left above the info card and the buttons. */
    private int listBottom() {
        int buttonsTop = py1 - 4 - visibleButtonCount() * (BUTTON_HEIGHT + 2);
        int info = infoLines() * 10 + 8;
        return Math.max(listTop() + ROW_HEIGHT, buttonsTop - info);
    }

    private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(px0 - 2, py0 - 2, px1 + 2, py1 + 2, ChartStyle.PARCHMENT_SHADE);
        g.fill(px0, py0, px1, py1, ChartStyle.PARCHMENT);
        g.outline(px0 - 1, py0 - 1, px1 - px0 + 2, py1 - py0 + 2, ChartStyle.INK);

        // tabs
        List<Tab> tabs = barTabs();
        int[] edges = tabEdges(tabs);
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            int x0 = edges[i], x1 = edges[i + 1];
            boolean active = t == tab;
            boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= py0 && mouseY < py0 + TAB_HEIGHT;
            g.fill(x0, py0, x1, py0 + TAB_HEIGHT, active ? ChartStyle.PARCHMENT : hover ? ChartStyle.PARCHMENT_DARK : ChartStyle.PARCHMENT_SHADE);
            if (!active) g.fill(x0, py0 + TAB_HEIGHT - 1, x1, py0 + TAB_HEIGHT, ChartStyle.INK_SOFT);
            if (i > 0) g.fill(x0, py0, x0 + 1, py0 + TAB_HEIGHT, ChartStyle.INK_SOFT);
            Component label = Component.translatable(t.key);
            g.text(font, label, x0 + (x1 - x0 - font.width(label)) / 2, py0 + 3, active ? ChartStyle.TEXT : ChartStyle.TEXT_MUTED, false);
        }

        if (hasGear()) {
            int gx0 = px1 - GEAR_WIDTH;
            boolean active = tab == Tab.BERTHS;
            boolean hover = mouseX >= gx0 && mouseX < px1 && mouseY >= py0 && mouseY < py0 + TAB_HEIGHT;
            g.fill(gx0, py0, px1, py0 + TAB_HEIGHT, active ? ChartStyle.PARCHMENT : hover ? ChartStyle.PARCHMENT_DARK : ChartStyle.PARCHMENT_SHADE);
            g.fill(gx0, py0, gx0 + 1, py0 + TAB_HEIGHT, ChartStyle.INK_SOFT);
            if (!active) g.fill(gx0, py0 + TAB_HEIGHT - 1, px1, py0 + TAB_HEIGHT, ChartStyle.INK_SOFT);
            g.blit(RenderPipelines.GUI_TEXTURED, ICON_GEAR, gx0 + 2, py0 + 1, 0, 0, 12, 12, 12, 12, 12, 12);
            if (hover) g.setTooltipForNextFrame(font, Component.translatable("minecraftportsmod.chart.tab.berths"), mouseX, mouseY);
        }
        // sub-header: what this is + small actions
        int hy = headerY();
        g.text(font, headerTitle(), px0 + 5, hy + 2, ChartStyle.TEXT, false);
        List<HeaderIcon> icons = headerIcons();
        for (int i = 0; i < icons.size(); i++) {
            HeaderIcon icon = icons.get(i);
            int ix = headerIconX(i);
            boolean hover = mouseX >= ix - 1 && mouseX < ix + 13 && mouseY >= hy - 1 && mouseY < hy + 13;
            int bg = icon.toggled ? ChartStyle.BRASS : hover && icon.active ? ChartStyle.PARCHMENT_DARK : ChartStyle.PARCHMENT_SHADE;
            g.fill(ix - 1, hy - 1, ix + 13, hy + 13, bg);
            g.outline(ix - 1, hy - 1, 14, 14, icon.active ? ChartStyle.INK_SOFT : ChartStyle.PARCHMENT_SHADE);
            g.blit(RenderPipelines.GUI_TEXTURED, icon.icon, ix, hy, 0, 0, 12, 12, 12, 12, 12, 12, icon.active ? -1 : 0x80FFFFFF);
            if (hover) g.setTooltipForNextFrame(font, font.split(icon.tooltip, 180), mouseX, mouseY);
        }
        g.fill(px0 + 4, hy + SUBHEADER - 1, px1 - 4, hy + SUBHEADER, ChartStyle.PARCHMENT_SHADE);

        List<Row> rows = rows();
        int top = listTop(), bottom = listBottom();
        int visibleRows = Math.max(1, (bottom - top) / ROW_HEIGHT);
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, rows.size() - visibleRows));
        g.enableScissor(px0, top, px1, bottom);
        if (rows.isEmpty()) {
            g.textWithWordWrap(font, emptyListText(), px0 + 6, top + 4, px1 - px0 - 12, ChartStyle.TEXT_MUTED, false);
        }
        for (int i = 0; i <= visibleRows && i + listScroll < rows.size(); i++) {
            Row row = rows.get(i + listScroll);
            int y = top + i * ROW_HEIGHT;
            boolean hover = mouseX >= px0 && mouseX < px1 && mouseY >= y && mouseY < y + ROW_HEIGHT && mouseY < bottom;
            if (row.selected) g.fill(px0 + 2, y, px1 - 2, y + ROW_HEIGHT - 1, ChartStyle.PARCHMENT_SHADE);
            else if (hover || row.highlighted) g.fill(px0 + 2, y, px1 - 2, y + ROW_HEIGHT - 1, ChartStyle.PARCHMENT_DARK);
            if (row.icon != null) g.blit(RenderPipelines.GUI_TEXTURED, row.icon, px0 + 5, y + 5, 0, 0, 10, 10, 16, 16, 16, 16);
            g.text(font, ellipsize(row.title, px1 - px0 - 28), px0 + 19, y + 2, ChartStyle.TEXT, false);
            int subColor = row.subtitle.getStyle().getColor() == null ? ChartStyle.TEXT_MUTED
                    : 0xFF000000 | row.subtitle.getStyle().getColor().getValue();
            g.text(font, ellipsize(row.subtitle.getString(), px1 - px0 - 28), px0 + 19, y + 11, subColor, false);
        }
        g.disableScissor();
        if (rows.size() > visibleRows) {
            int trackH = bottom - top;
            int thumbH = Math.max(10, trackH * visibleRows / rows.size());
            int thumbY = top + (trackH - thumbH) * listScroll / Math.max(1, rows.size() - visibleRows);
            g.fill(px1 - 4, top, px1 - 2, bottom, ChartStyle.PARCHMENT_DARK);
            g.fill(px1 - 4, thumbY, px1 - 2, thumbY + thumbH, ChartStyle.INK_SOFT);
        }

        g.fill(px0 + 4, bottom + 2, px1 - 4, bottom + 3, ChartStyle.INK_SOFT);
        int y = bottom + 6;
        for (Component line : infoCard()) {
            g.text(font, line.getString().length() > 0 ? Component.literal(ellipsize(line.getString(), px1 - px0 - 12)).withStyle(line.getStyle()) : line,
                    px0 + 6, y, line.getStyle().getColor() == null ? ChartStyle.TEXT_MUTED : 0xFF000000 | line.getStyle().getColor().getValue(), false);
            y += 10;
        }
    }

    /** Tab boundaries: every tab gets its label width plus an equal share of the spare room. */
    private int[] tabEdges(List<Tab> tabs) {
        int[] widths = new int[tabs.size()];
        int total = 0;
        for (int i = 0; i < tabs.size(); i++) {
            widths[i] = font.width(Component.translatable(tabs.get(i).key)) + 4;
            total += widths[i];
        }
        int barRight = px1 - (hasGear() ? GEAR_WIDTH : 0);
        int spare = Math.max(0, (barRight - px0) - total);
        int[] edges = new int[tabs.size() + 1];
        edges[0] = px0;
        for (int i = 0; i < tabs.size(); i++) {
            edges[i + 1] = edges[i] + widths[i] + spare / tabs.size();
        }
        edges[tabs.size()] = barRight;
        return edges;
    }

    /** One entry of the side list. */
    private record Row(Identifier icon, String title, Component subtitle, boolean selected, boolean highlighted, Runnable onClick) {
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        switch (tab) {
            case PORTS -> {
                int focus = routeFocusPort();
                for (PortInfo p : sortedPorts) {
                    Identifier icon = p.id() == originId ? ANCHOR_HOME : (focus >= 0 && p.id() != focus && !reachable(focus, p.id())) ? ANCHOR_OFF : ANCHOR;
                    rows.add(new Row(icon, p.name(), portSubtitle(p), p.id() == selectedPort, p.id() == hoveredPort,
                            () -> select(p.id(), true)));
                }
            }
            case FLEET -> {
                for (VesselInfo v : fleet.values()) {
                    rows.add(new Row(vesselIcon(v, v.id().equals(selectedVessel)), v.name(), vesselStatus(v),
                            v.id().equals(selectedVessel), v.id().equals(hoveredVessel), () -> selectVessel(v.id(), true)));
                }
            }
            case SHIPYARD -> {
                for (VesselInfo v : fleet.values()) {
                    if (!inShipyard(v)) continue;
                    rows.add(new Row(vesselIcon(v, v.id().equals(selectedVessel)), v.name(),
                            org.webtrade.minecraftportsmod.fleet.VesselType.byTier(v.tier()).displayName().copy().withStyle(ChatFormatting.DARK_AQUA),
                            v.id().equals(selectedVessel), false, () -> selectVessel(v.id(), false)));
                }
            }
            case BERTHS -> {
                PortInfo office = ports.get(originId);
                if (office != null) {
                    int[] b = office.berths();
                    for (int i = 0; i < b.length; i += PortInfo.BERTH_STRIDE) {
                        int dock = b[i], bx = b[i + 1], bz = b[i + 2];
                        boolean busy = b[i + 3] != 0;
                        boolean lockedRow = b[i + 4] != 0;
                        String who = busy ? occupantName(dock) : null;
                        Component sub = busy
                                ? (who == null ? Component.translatable("minecraftportsmod.chart.berth_busy")
                                : Component.translatable("minecraftportsmod.chart.berth_busy_by", who)).withStyle(ChatFormatting.DARK_RED)
                                : Component.translatable("minecraftportsmod.chart.berth_free").withStyle(ChatFormatting.DARK_GREEN);
                        if (lockedRow) sub = Component.translatable("minecraftportsmod.chart.berth_locked", sub).withStyle(sub.getStyle());
                        rows.add(new Row(null, Component.translatable("minecraftportsmod.chart.berth", dock).getString()
                                + "  (" + bx + ", " + bz + ")", sub, dock == selectedDock, false, () -> {
                            selectedDock = dock;
                            centerOn(bx, bz);
                            if (zoom < 2F) zoom = 2F;
                        }));
                    }
                }
            }
        }
        return rows;
    }

    private String occupantName(int dockId) {
        for (VesselInfo v : fleet.values()) {
            if (v.dockId() == dockId && v.portId() == originId) return v.name();
        }
        return null;
    }

    private Component emptyListText() {
        return switch (tab) {
            case PORTS -> Component.translatable("minecraftportsmod.chart.no_ports");
            case FLEET -> Component.translatable(mode == ChartPayloads.MODE_OFFICE
                    ? "minecraftportsmod.chart.no_vessels_office" : "minecraftportsmod.chart.no_vessels");
            case BERTHS -> Component.translatable("minecraftportsmod.chart.no_berths");
            case SHIPYARD -> Component.translatable("minecraftportsmod.shipyard.empty");
        };
    }

    private Component portSubtitle(PortInfo p) {
        if (p.id() == originId) return Component.translatable("minecraftportsmod.chart.you_are_here").withStyle(ChatFormatting.DARK_GREEN);
        int focus = routeFocusPort();
        if (focus < 0 || focus == p.id()) {
            return Component.translatable("minecraftportsmod.chart.docks", p.freeDocks(), p.docks());
        }
        RouteInfo r = route(focus, p.id());
        if (r != null && r.status() == 1) {
            return Component.translatable("minecraftportsmod.chart.route_short", Math.round(r.length()), eta(r.length(), speed()));
        }
        return routeStatusText(r);
    }

    private float speed() {
        VesselInfo v = sending != null ? fleet.get(sending) : ridingVessel();
        return v != null ? v.speed() : 0.4F;
    }

    private Component vesselStatus(VesselInfo v) {
        String port = ports.containsKey(v.portId()) ? ports.get(v.portId()).name() : "?";
        String dest = ports.containsKey(v.destPortId()) ? ports.get(v.destPortId()).name() : "?";
        if (v.planning()) return Component.translatable("minecraftportsmod.chart.planning").withStyle(ChatFormatting.GOLD);
        return switch (v.state()) {
            case STATE_MOORED -> Component.translatable("minecraftportsmod.chart.vessel_moored", port).withStyle(ChatFormatting.DARK_GREEN);
            case STATE_ANCHORED -> Component.translatable("minecraftportsmod.chart.vessel_anchored", port).withStyle(ChatFormatting.DARK_AQUA);
            case STATE_SAILING -> Component.translatable("minecraftportsmod.chart.vessel_sailing", dest,
                    eta(v.remaining(), v.speed())).withStyle(ChatFormatting.BLUE);
            default -> Component.translatable("minecraftportsmod.chart.vessel_adrift").withStyle(ChatFormatting.GRAY);
        };
    }

    private int infoLines() {
        return Math.max(1, infoCard().size());
    }

    private List<Component> infoCard() {
        List<Component> lines = new ArrayList<>();
        switch (tab) {
            case PORTS -> {
                PortInfo p = ports.get(selectedPort);
                if (p == null) {
                    lines.add(Component.translatable(sending != null ? "minecraftportsmod.chart.pick_port_send"
                            : mode == ChartPayloads.MODE_BOAT ? "minecraftportsmod.chart.pick_port_sail" : "minecraftportsmod.chart.pick_port"));
                    break;
                }
                lines.add(Component.literal(p.name()).withStyle(ChatFormatting.BOLD));
                if (p.settled()) {
                    var spec = org.webtrade.minecraftportsmod.economy.Specialization.values()[p.spec()];
                    lines.add(Component.translatable("minecraftportsmod.chart.settlement", spec.displayName(), p.population())
                            .withColor(SettlementStyle.specColor(spec)));
                }
                if (!p.owner().isEmpty()) lines.add(Component.translatable("minecraftportsmod.chart.owner", p.owner()));
                lines.add(Component.translatable("minecraftportsmod.chart.docks", p.freeDocks(), p.docks()));
                int focus = routeFocusPort();
                if (focus >= 0 && focus != p.id()) {
                    RouteInfo r = route(focus, p.id());
                    if (r != null && r.status() == 1) {
                        lines.add(Component.translatable("minecraftportsmod.chart.route_len", Math.round(r.length())).withStyle(ChatFormatting.DARK_GREEN));
                        lines.add(Component.translatable("minecraftportsmod.chart.eta", eta(r.length(), speed())).withStyle(ChatFormatting.DARK_GREEN));
                    } else {
                        lines.add(routeStatusText(r));
                    }
                }
            }
            case FLEET -> {
                VesselInfo v = selectedVessel == null ? null : fleet.get(selectedVessel);
                if (v == null) {
                    lines.add(Component.translatable(fleet.isEmpty() ? "minecraftportsmod.chart.fleet_empty" : "minecraftportsmod.chart.pick_vessel"));
                    break;
                }
                lines.add(Component.literal(v.name()).withStyle(ChatFormatting.BOLD));
                lines.add(org.webtrade.minecraftportsmod.fleet.VesselType.byTier(v.tier()).displayName().copy().withStyle(ChatFormatting.DARK_AQUA));
                lines.add(vesselStatus(v));
                lines.add(Component.translatable("minecraftportsmod.chart.speed", String.format("%.1f", v.speed() * 20)));
            }
            case SHIPYARD -> {
                VesselInfo v = selectedVessel == null ? null : fleet.get(selectedVessel);
                if (v == null || !inShipyard(v)) {
                    lines.add(Component.translatable("minecraftportsmod.shipyard.pick"));
                    lines.add(Component.translatable("minecraftportsmod.shipyard.diamonds", creative ? "∞" : String.valueOf(diamonds)));
                    break;
                }
                org.webtrade.minecraftportsmod.fleet.VesselType type = org.webtrade.minecraftportsmod.fleet.VesselType.byTier(v.tier());
                org.webtrade.minecraftportsmod.fleet.VesselType next = type.next();
                lines.add(Component.literal(v.name()).withStyle(ChatFormatting.BOLD));
                if (next == null) {
                    lines.add(type.displayName().copy().withStyle(ChatFormatting.DARK_AQUA));
                    lines.add(Component.translatable("minecraftportsmod.shipyard.top").withStyle(ChatFormatting.DARK_GREEN));
                    break;
                }
                lines.add(Component.translatable("minecraftportsmod.shipyard.arrow", type.displayName(), next.displayName()).withStyle(ChatFormatting.DARK_AQUA));
                lines.add(Component.translatable("minecraftportsmod.shipyard.speed",
                        String.format("%.0f", type.blocksPerSecond()), String.format("%.0f", next.blocksPerSecond())));
                lines.add(Component.translatable("minecraftportsmod.shipyard.seats", type.passengers, next.passengers));
                lines.add(Component.translatable("minecraftportsmod.shipyard.cost", next.upgradeCost,
                        creative ? "∞" : String.valueOf(diamonds)).withStyle(creative || diamonds >= next.upgradeCost
                        ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
            }
            case BERTHS -> {
                PortInfo office = ports.get(originId);
                lines.add(Component.translatable("minecraftportsmod.chart.berths_info",
                        office == null ? 0 : office.freeDocks(), office == null ? 0 : office.docks()));
                if (canManage) lines.add(Component.translatable(selectedDock >= 0
                        ? "minecraftportsmod.chart.berth_move_hint" : "minecraftportsmod.chart.pick_berth"));
                if (!canManage) lines.add(Component.translatable("minecraftportsmod.chart.not_owner").withStyle(ChatFormatting.DARK_RED));
            }
        }
        return lines;
    }

    private Component routeStatusText(RouteInfo r) {
        if (r == null) return Component.translatable("minecraftportsmod.chart.route_none").withStyle(ChatFormatting.DARK_RED);
        if (r.calculating() || r.status() == 0) return Component.translatable("minecraftportsmod.chart.route_calculating").withStyle(ChatFormatting.GOLD);
        return switch (r.status()) {
            case 1 -> Component.translatable("minecraftportsmod.chart.route_len", Math.round(r.length()));
            case 2 -> Component.translatable("minecraftportsmod.chart.route_blocked").withStyle(ChatFormatting.GOLD);
            default -> Component.translatable("minecraftportsmod.chart.route_none").withStyle(ChatFormatting.DARK_RED);
        };
    }

    private static String eta(double length, float speed) {
        int seconds = (int) Math.round(length / (Math.max(0.05F, speed) * 20));
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private String ellipsize(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + dots) > maxWidth) end--;
        return s.substring(0, end) + dots;
    }

    private List<Component> portTooltip(PortInfo p) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(p.name()).withStyle(ChatFormatting.GOLD));
        if (!p.owner().isEmpty()) lines.add(Component.translatable("minecraftportsmod.chart.owner", p.owner()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("minecraftportsmod.chart.docks", p.freeDocks(), p.docks()).withStyle(ChatFormatting.GRAY));
        int focus = routeFocusPort();
        if (p.id() == originId) {
            lines.add(Component.translatable("minecraftportsmod.chart.you_are_here").withStyle(ChatFormatting.GREEN));
        } else if (focus >= 0 && focus != p.id()) {
            RouteInfo r = route(focus, p.id());
            lines.add(r != null && r.status() == 1
                    ? Component.translatable("minecraftportsmod.chart.route_short", Math.round(r.length()), eta(r.length(), speed())).withStyle(ChatFormatting.AQUA)
                    : routeStatusText(r));
        }
        if (!p.hasAnchorage()) lines.add(Component.translatable("minecraftportsmod.chart.no_anchorage").withStyle(ChatFormatting.RED));
        return lines;
    }

    private List<Component> vesselTooltip(VesselInfo v) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(v.name()).withStyle(ChatFormatting.GOLD));
        lines.add(vesselStatus(v));
        return lines;
    }

    private int portAt(double mouseX, double mouseY) {
        int best = -1;
        double bestSq = 9 * 9;
        for (PortInfo p : ports.values()) {
            double dx = toScreenX(p.x() + 0.5) - mouseX, dy = toScreenY(p.z() + 0.5) - mouseY;
            if (dx * dx + dy * dy < bestSq) {
                bestSq = dx * dx + dy * dy;
                best = p.id();
            }
        }
        return best;
    }

    private int berthAt(double mouseX, double mouseY) {
        PortInfo office = ports.get(originId);
        if (office == null) return -1;
        int best = -1;
        double bestSq = 8 * 8;
        int[] b = office.berths();
        for (int i = 0; i < b.length; i += PortInfo.BERTH_STRIDE) {
            double dx = toScreenX(b[i + 1] + 0.5) - mouseX, dy = toScreenY(b[i + 2] + 0.5) - mouseY;
            if (dx * dx + dy * dy < bestSq) {
                bestSq = dx * dx + dy * dy;
                best = b[i];
            }
        }
        return best;
    }

    private UUID vesselAt(double mouseX, double mouseY) {
        UUID best = null;
        double bestSq = 7 * 7;
        for (VesselInfo v : fleet.values()) {
            double dx = toScreenX(v.x()) - mouseX, dy = toScreenY(v.z()) - mouseY;
            if (dx * dx + dy * dy < bestSq) {
                bestSq = dx * dx + dy * dy;
                best = v.id();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        double x = event.x(), y = event.y();
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && tab == Tab.BERTHS && inMap(x, y)
                && selectedDock >= 0 && canManage && mode == ChartPayloads.MODE_OFFICE) {
            int bx = (int) Math.floor(toWorldX(x)), bz = (int) Math.floor(toWorldZ(y));
            action(new FleetAction(FleetAction.Action.MOVE_BERTH, null, originId, selectedDock, bx, bz), false);
            return true;
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (inMap(x, y)) {
            dragging = true;
            dragMoved = 0;
            if (tab == Tab.BERTHS) {
                int dock = berthAt(x, y);
                if (dock >= 0) {
                    selectedDock = dock;
                    return true;
                }
            }
            UUID v = vesselAt(x, y);
            if (v != null) {
                tab = Tab.FLEET;
                selectVessel(v, false);
                return true;
            }
            int id = portAt(x, y);
            if (id >= 0) {
                tab = Tab.PORTS;
                select(id, false);
                if (doubleClick && buttons[2] != null && buttons[2].visible && buttons[2].active && mode == ChartPayloads.MODE_BOAT) {
                    buttonActions[2].run();
                }
            }
            return true;
        }
        if (inPanel(x, y) && y < py0 + TAB_HEIGHT) {
            if (hasGear() && x >= px1 - GEAR_WIDTH) {
                tab = tab == Tab.BERTHS ? Tab.PORTS : Tab.BERTHS;
            } else {
                List<Tab> tabs = barTabs();
                int[] edges = tabEdges(tabs);
                for (int i = 0; i < tabs.size(); i++) {
                    if (x >= edges[i] && x < edges[i + 1]) tab = tabs.get(i);
                }
            }
            listScroll = 0;
            return true;
        }
        if (inPanel(x, y) && y >= headerY() - 1 && y < headerY() + 13) {
            List<HeaderIcon> icons = headerIcons();
            for (int i = 0; i < icons.size(); i++) {
                int ix = headerIconX(i);
                if (x >= ix - 1 && x < ix + 13 && icons.get(i).active) {
                    icons.get(i).action.run();
                    return true;
                }
            }
        }
        if (inPanel(x, y) && y >= listTop() && y < listBottom()) {
            int row = (int) ((y - listTop()) / ROW_HEIGHT) + listScroll;
            List<Row> rows = rows();
            if (row >= 0 && row < rows.size()) {
                rows.get(row).onClick.run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            targetCenterX = centerX;
            targetCenterZ = centerZ;
            animating = false;
            dragMoved += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.uiDrag(event, dx, dy);
    }

    @Override
    protected boolean uiRelease(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            if (dragMoved < 3 && inMap(event.x(), event.y()) && portAt(event.x(), event.y()) < 0 && vesselAt(event.x(), event.y()) == null) {
                selectedPort = -1;
            }
            return true;
        }
        return super.uiRelease(event);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inMap(mouseX, mouseY)) {
            double wx = toWorldX(mouseX), wz = toWorldZ(mouseY);
            float newZoom = Mth.clamp((float) (zoom * Math.pow(1.25, scrollY)), MIN_ZOOM, MAX_ZOOM);
            if (newZoom != zoom) {
                zoom = newZoom;
                centerX = wx - (mouseX - (mx0 + mx1) / 2.0) / zoom;
                centerZ = wz - (mouseY - (my0 + my1) / 2.0) / zoom;
                targetCenterX = centerX;
                targetCenterZ = centerZ;
                animating = false;
            }
            return true;
        }
        if (inPanel(mouseX, mouseY)) {
            listScroll -= (int) Math.signum(scrollY);
            return true;
        }
        return super.uiScroll(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_HOME) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) centerOn(player.getX(), player.getZ());
            return true;
        }
        double pan = 48 / zoom;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> targetCenterX -= pan;
            case GLFW.GLFW_KEY_RIGHT -> targetCenterX += pan;
            case GLFW.GLFW_KEY_UP -> targetCenterZ -= pan;
            case GLFW.GLFW_KEY_DOWN -> targetCenterZ += pan;
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
        animating = true;
        return true;
    }
}
