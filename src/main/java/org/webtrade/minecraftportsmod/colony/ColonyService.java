package org.webtrade.minecraftportsmod.colony;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.network.ColonyPayloads;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Serves the village screens: the board, the building sites, the residents. */
public final class ColonyService {

    /** The village map shows this many blocks around the middle (each way). */
    public static final int MAP_RADIUS = 80;

    private ColonyService() {
    }

    private static VillageData data(ServerPlayer p) {
        return VillageData.get(p.level().getServer());
    }

    // ------------------------------------------------------------------ opening

    public static void openBoard(ServerPlayer player, BlockPos pos) {
        Village v = data(player).near(pos, 96);
        if (v == null) {
            player.sendSystemMessage(Component.translatable("minecraftportsmod.colony.no_village").withStyle(ChatFormatting.GRAY));
            return;
        }
        sendVillage(player, v.id, true);
    }

    public static void openSite(ServerPlayer player, BlockPos pos) {
        VillageData data = data(player);
        for (Village v : data.all()) {
            for (Building b : v.buildings) {
                BlockPos post = b.blueprint(v.wood).post;
                if (post.getX() != pos.getX() || post.getZ() != pos.getZ() || Math.abs(post.getY() - pos.getY()) > 5) continue;
                // a site under way (or a building still waiting for what its next level takes) shows its materials; a
                // building that stands, its own menu
                if (b.state == Building.State.BUILT && !(b.upgrading() && !b.supplied())) sendBuilding(player, v, b);
                else sendSite(player, v, b);
                return;
            }
        }
        player.sendSystemMessage(Component.translatable("minecraftportsmod.colony.no_site").withStyle(ChatFormatting.GRAY));
    }

    /** The cartographer's table: the map of what the village's scouts found. */
    public static void openMap(ServerPlayer player, BlockPos pos) {
        Village v = data(player).near(pos, 120);
        if (v == null) return;
        Scouting.send(player, v);
    }

    public static void openDweller(ServerPlayer player, ResidentEntity e) {
        VillageData data = data(player);
        Village v = data.get(e.colonyVillage());
        Dweller d = v == null ? null : v.dweller(e.colonyDweller());
        if (d == null) return;
        Building home = v.building(d.home);
        Component homeText = home == null ? Component.translatable("minecraftportsmod.colony.homeless")
                : Component.empty().append(home.type.displayName()).append(" #" + home.id);
        // the merchant: straight to the stall
        if (d.job == Job.MERCHANT) {
            sendTrade(player, v, null);
            return;
        }
        // the ones who make things to order: their building (the "Trade" button opens its orders)
        Building work = workplace(v, d.job);
        int orders = work != null && !Orders.recipes(work).isEmpty() ? work.id : -1;
        ServerPlayNetworking.send(player, new ColonyPayloads.PersonView(v.id, d.id, v.name, v.level.ordinal(), d.name,
                d.job == null ? -1 : d.job.ordinal(), d.child(data.day), d.elder, e.activity(), homeText, data.day - d.joined, orders));
    }

    // ------------------------------------------------------------------ the village screen

    public static void sendVillage(ServerPlayer player, int id, boolean withMap) {
        VillageData data = data(player);
        Village v = data.get(id);
        if (v == null) return;
        ServerLevel level = (ServerLevel) player.level();
        long today = data.day;
        int[] stock = new int[Res.values().length], made = new int[Res.values().length], cap = new int[Res.values().length];
        for (Res r : Res.values()) {
            stock[r.ordinal()] = v.stock(r);
            made[r.ordinal()] = VillageLife.production(v, r);
            cap[r.ordinal()] = v.capacity(r);
        }
        // yesterday, really: what was made, and what was eaten, worn out, sawn up and taken to the building sites
        int[] got = new int[Res.values().length], spent = new int[Res.values().length];
        for (Res r : Res.values()) {
            got[r.ordinal()] = v.made.getOrDefault(r, 0);
            spent[r.ordinal()] = v.used.getOrDefault(r, 0) + v.built.getOrDefault(r, 0) + (r == Res.FOOD ? v.eaten : 0);
        }
        List<ColonyPayloads.Req> reqs = new ArrayList<>();
        for (VillageLife.Requirement r : VillageLife.requirements(v, v.level.next())) {
            reqs.add(new ColonyPayloads.Req(r.label(), r.have(), r.need()));
        }
        // what people are doing, read off their bodies
        Map<Integer, Component> doing = new HashMap<>();
        for (ResidentEntity e : level.getEntitiesOfClass(ResidentEntity.class, new AABB(v.center).inflate(128, 64, 128),
                e -> e.colonyVillage() == v.id)) {
            doing.put(e.colonyDweller(), e.activity());
        }
        List<ColonyPayloads.PersonRow> people = new ArrayList<>();
        for (Dweller d : v.dwellers) {
            people.add(new ColonyPayloads.PersonRow(d.id, d.name, d.job == null ? -1 : d.job.ordinal(), d.child(today), d.elder,
                    d.home, doing.getOrDefault(d.id, Component.translatable("minecraftportsmod.act.away")), d.joined));
        }
        List<ColonyPayloads.BuildingRow> buildings = new ArrayList<>();
        for (Building b : v.buildings) {
            List<String> lives = new ArrayList<>();
            for (Dweller d : v.dwellers) if (d.home == b.id) lives.add(d.name);
            int total = b.blueprint(v.wood).pieces.size();
            float progress = switch (b.state) {
                case PLANNED -> 0;
                case BUILDING -> Math.min(1F, (float) b.work / Math.max(1, total));
                case BUILT -> b.upgrading() ? raiseProgress(v, b) : 1;
                case DEMOLISHING -> Math.min(1F, (float) b.work / Math.max(1, total));
            };
            int[] missing = new int[Res.values().length];
            for (Res r : Res.values()) missing[r.ordinal()] = b.missing(r);
            buildings.add(new ColonyPayloads.BuildingRow(b.id, b.type.ordinal(), b.state.ordinal(), b.origin.getX() - v.center.getX(),
                    b.origin.getZ() - v.center.getZ(), b.front.get2DDataValue(), b.type.beds, lives, progress, missing,
                    VillageLife.eta(v, b)));
        }
        List<ColonyPayloads.LogRow> log = new ArrayList<>();
        for (Village.LogLine l : v.log()) log.add(new ColonyPayloads.LogRow(l.day(), l.text()));
        int size = 0;
        int[] map = new int[0];
        if (withMap) {
            size = MAP_RADIUS * 2 + 1;
            map = map(level, v.center, MAP_RADIUS);
        }
        List<ColonyPayloads.NodeRow> tree = new ArrayList<>();
        for (BuildingType t : BuildingType.values()) {
            if (!t.isNode()) continue;
            int[] levels = new int[3];
            for (Building b : v.buildings) if (b.type == t && b.standing()) levels[Math.max(1, Math.min(3, b.level)) - 1]++;
            List<ColonyPayloads.Req> factors = new ArrayList<>();
            for (Tree.Factor f : Tree.factors(v, t)) factors.add(new ColonyPayloads.Req(f.label(), f.percent(), 0));
            tree.add(new ColonyPayloads.NodeRow(t.ordinal(), Tree.node(v, t).ordinal(), levels, amounts(Tree.unlockCost(v, t)),
                    amounts(Tree.price(v, t)), factors));
        }
        Building mid = Tree.center(v);
        BuildingType next = Tree.centerNext(v);
        boolean queued = next != null && (v.priority == next || v.count(next, false) > 0);
        ColonyPayloads.CenterRow center = new ColonyPayloads.CenterRow(mid == null ? -1 : mid.type.ordinal(), next == null ? -1 : next.ordinal(),
                next == null ? new int[Res.values().length] : amounts(next.cost()), next == null ? 0 : Tree.centerPeople(next), queued);
        ServerPlayNetworking.send(player, new ColonyPayloads.VillageView(v.id, v.name, v.level.ordinal(), v.mood, today,
                (float) data.dayTicks / data.dayLength, v.beds(), stock, made, cap, v.eaten, VillageLife.foodNeed(v, today), reqs, people,
                buildings, log, size, map, player.getBlockX() - v.center.getX(), player.getBlockZ() - v.center.getZ(),
                v.board.getX() - v.center.getX(), v.board.getZ() - v.center.getZ(), tree, v.priority == null ? -1 : v.priority.ordinal(),
                v.focus == null ? -1 : v.focus.ordinal(), center, queueRows(data, v), v.sub == null ? -1 : v.sub.ordinal(),
                (float) v.ready, (float) VillageLife.readyGain(data, v, today), v.stored(), v.capacity(), got, spent));
    }

    /** The village's queue as the board shows it: the research in hand, the building sites in order, the trails being made. */
    static List<ColonyPayloads.QueueRow> queueRows(VillageData data, Village v) {
        List<ColonyPayloads.QueueRow> out = new ArrayList<>();
        int n = Res.values().length;
        if (v.research != null) {
            Map<Res, Integer> cost = Tree.unlockCost(v, v.research);
            int[] missing = new int[n];
            int need = 0, got = 0;
            for (var e : cost.entrySet()) {
                missing[e.getKey().ordinal()] = Math.max(0, e.getValue() - v.stock(e.getKey()));
                need += e.getValue();
                got += Math.min(e.getValue(), v.stock(e.getKey()));
            }
            out.add(new ColonyPayloads.QueueRow(0, v.research.ordinal(), -1, 0, missing, need == 0 ? 0 : (float) got / need, -1,
                    Component.translatable("minecraftportsmod.queue.saving")));
        }
        for (Building b : VillageLife.queue(v)) {
            int[] missing = new int[n];
            for (Res r : Res.values()) missing[r.ordinal()] = Math.max(0, b.missing(r) - v.stock(r));
            int total = b.blueprint(v.wood).pieces.size();
            int kind;
            float progress;
            Component status;
            if (b.state == Building.State.DEMOLISHING) {
                kind = 3;
                progress = Math.min(1F, (float) b.work / Math.max(1, total));
                status = Component.translatable("minecraftportsmod.queue.demolishing");
            } else if (b.upgrading()) {
                kind = 2;
                progress = raiseProgress(v, b);
                status = Component.translatable(b.supplied() ? "minecraftportsmod.queue.raising" : "minecraftportsmod.queue.materials");
            } else if (b.state == Building.State.BUILDING) {
                kind = 1;
                progress = Math.min(1F, (float) b.work / Math.max(1, total));
                status = Component.translatable("minecraftportsmod.queue.building");
            } else {
                kind = 1;
                int need = 0, got = 0;
                for (Res r : Res.values()) {
                    need += b.cost(r);
                    got += Math.min(b.cost(r), b.delivered(r));
                }
                progress = need == 0 ? 0 : 0.5F * got / need;
                status = Component.translatable("minecraftportsmod.queue.materials");
            }
            out.add(new ColonyPayloads.QueueRow(kind, b.type.ordinal(), b.id, b.upgrading() ? b.goal : b.level, missing, progress,
                    VillageLife.eta(v, b), status));
        }
        for (Roadworks.Work w : data.works()) {
            Roadworks.Side s = w.side(v.id);
            if (s == null || s.state() == Roadworks.HOME) continue;
            Village o = data.get(w.a == v.id ? w.b : w.a);
            String other = o == null ? "?" : o.name;
            String key = switch (s.state()) {
                case Roadworks.WAITING -> "minecraftportsmod.queue.road_waiting";
                case Roadworks.GOING -> "minecraftportsmod.queue.road_going";
                case Roadworks.WORKING -> "minecraftportsmod.queue.road_working";
                default -> "minecraftportsmod.queue.road_home";
            };
            float progress = (float) ((w.sideA().done() + w.sideB().done()) / Math.max(1, w.length()));
            out.add(new ColonyPayloads.QueueRow(4, -1, -2, 0, new int[n], Math.min(1F, progress), -1,
                    Component.translatable(key, other, (int) (w.sideA().done() + w.sideB().done()), (int) w.length())));
        }
        return out;
    }

    static int[] amounts(Map<Res, Integer> m) {
        int[] out = new int[Res.values().length];
        m.forEach((r, n) -> out[r.ordinal()] = n);
        return out;
    }

    /** How far a building's rise to its next level has come: the materials count for half, the work for the rest. */
    static float raiseProgress(Village v, Building b) {
        Blueprint bp = b.blueprint(v.wood);
        int from = bp.upTo(b.level), to = bp.pieces.size();
        if (!b.supplied()) {
            int need = 0, got = 0;
            for (Res r : Res.values()) {
                need += b.cost(r);
                got += Math.min(b.cost(r), b.delivered(r));
            }
            return need == 0 ? 0 : 0.5F * got / need;
        }
        return 0.5F + 0.5F * Math.min(1F, (float) (b.work - from) / Math.max(1, to - from));
    }

    /** The land around a point as a map would draw it: one colour per column, shaded by the slope. */
    static int[] map(ServerLevel level, BlockPos c, int r) {
        int n = 2 * r + 1;
        int[] out = new int[n * n];
        int[] heights = new int[n + 1];
        for (int x = 0; x < n; x++) {
            int wx = c.getX() - r + x, wz = c.getZ() - r - 1;
            heights[x] = Construction.loaded(level, new BlockPos(wx, 0, wz)) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) : 0;
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int wx = c.getX() - r + x, wz = c.getZ() - r + z;
                if (!Construction.loaded(level, new BlockPos(wx, 0, wz))) {
                    out[z * n + x] = 0;
                    continue;
                }
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                BlockPos p = new BlockPos(wx, h - 1, wz);
                BlockState st = level.getBlockState(p);
                MapColor col = st.getMapColor(level, p);
                MapColor.Brightness b;
                if (col == MapColor.WATER) {
                    int depth = 0;
                    while (depth < 10 && !level.getBlockState(p.below(depth + 1)).getFluidState().isEmpty()) depth++;
                    b = depth > 6 ? MapColor.Brightness.LOW : depth > 2 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
                } else {
                    int north = heights[x];
                    b = h > north ? MapColor.Brightness.HIGH : h < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                heights[x] = h;
                out[z * n + x] = col == MapColor.NONE ? 0 : col.calculateARGBColor(b) | 0xFF000000;
            }
        }
        return out;
    }

    public static void sendSite(ServerPlayer player, Village v, Building b) {
        int n = Res.values().length;
        int[] cost = new int[n], delivered = new int[n], stock = new int[n], carried = new int[n];
        var inv = player.getInventory();
        for (Res r : Res.values()) {
            cost[r.ordinal()] = b.cost(r);
            delivered[r.ordinal()] = b.delivered(r);
            stock[r.ordinal()] = v.stock(r);
            int units = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var s = inv.getItem(i);
                units += r.unitsOf(s) * s.getCount();
            }
            carried[r.ordinal()] = units;
        }
        int total = b.blueprint(v.wood).pieces.size();
        float progress = b.state == Building.State.PLANNED ? 0 : Math.min(1F, (float) b.work / Math.max(1, total));
        // a building waiting for what its next level takes is, for the players, a site to bring things to
        int state = b.upgrading() && !b.supplied() ? Building.State.PLANNED.ordinal() : b.state.ordinal();
        if (b.upgrading()) progress = raiseProgress(v, b);
        ServerPlayNetworking.send(player, new ColonyPayloads.SiteView(v.id, b.id, v.name, b.type.ordinal(), state,
                cost, delivered, stock, carried, progress, VillageLife.eta(v, b)));
    }

    // ------------------------------------------------------------------ a building's menu

    public static void sendBuilding(ServerPlayer player, Village v, Building b) {
        List<String> people = new ArrayList<>();
        for (Dweller d : v.dwellers) if (d.home == b.id) people.add(d.name);
        List<Component> notes = new ArrayList<>();
        int tools = b.type.job == null || !b.type.job.usesTools() ? -1 : v.toolLevel(b.type.job);
        if (tools >= 0) {
            notes.add(Component.translatable("minecraftportsmod.bmenu.tools", Job.toolName(tools), String.valueOf(Job.TOOL_SPEED[tools])));
            notes.add(Component.translatable("minecraftportsmod.bmenu.workers", v.workers(b.type.job)));
            if (b.type.job == Job.MINER && VillageLife.ironDaily(v) > 0) {
                notes.add(Component.translatable("minecraftportsmod.bmenu.iron", VillageLife.ironDaily(v)));
            }
        }
        if (b.type.job != null && b.type.job.usesTools() && v.toolsShort(b.type.job)) {
            notes.add(Component.translatable("minecraftportsmod.bmenu.tools_short").withStyle(ChatFormatting.RED));
        }
        if (b.type == BuildingType.SAWMILL) {
            int[] saw = VillageLife.sawing(v);
            notes.add(Component.translatable("minecraftportsmod.bmenu.sawing", saw[0], saw[1], saw[2]));
        }
        if (b.type == BuildingType.CARTOGRAPHER) {
            notes.add(Component.translatable("minecraftportsmod.bmenu.scouting", Scouting.range(b.level), Scouting.scouts(v), v.known.size()));
        }
        if (b.type.beds > 0) notes.add(Component.translatable("minecraftportsmod.vboard.lives", people.size(), b.type.beds));
        if (b.type.branch == BuildingType.Branch.STORE) {
            notes.add(Component.translatable("minecraftportsmod.bmenu.capacity", v.capacity()));
        }
        if (b.type == BuildingType.FIELD) notes.add(Component.translatable("minecraftportsmod.bmenu.sown", b.crop().displayName()));
        if (b.type == BuildingType.FARM) {
            notes.add(Component.translatable("minecraftportsmod.bmenu.fields", v.count(BuildingType.FIELD, true), Tree.fieldLimit(v)));
        }
        if (b.upgrading()) {
            notes.add(Component.translatable(b.supplied() ? "minecraftportsmod.bmenu.raising" : "minecraftportsmod.bmenu.raise_waiting", b.goal,
                    Math.round(raiseProgress(v, b) * 100)));
        }
        int[] raiseCost = b.level < b.type.maxLevel && !b.upgrading() ? amounts(Tree.levelCost(b.type, b.level + 1)) : new int[0];
        int[] crops = new int[0];
        int crop = -1;
        if (b.type == BuildingType.FIELD) {
            List<Integer> open = new ArrayList<>();
            for (Crop c : Crop.values()) if (c.unlocked(v)) open.add(c.ordinal());
            crops = open.stream().mapToInt(Integer::intValue).toArray();
            crop = b.crop().ordinal();
        }
        float progress = b.upgrading() ? raiseProgress(v, b) : 1;
        int[][] flows = VillageLife.flows(v, b);
        ServerPlayNetworking.send(player, new ColonyPayloads.BuildingView(v.id, b.id, v.name, b.type.ordinal(), b.state.ordinal(),
                progress, people, tools, notes, b.level, b.goal, raiseCost, v.raiseFirst == b.id, b.keep, crops, crop, flows[0], flows[1],
                amounts(v.stock)));
    }

    public static void handleAction(ServerPlayer player, ColonyPayloads.VillageAction a) {
        VillageData data = data(player);
        Village v = data.get(a.village());
        if (v == null || player.blockPosition().distSqr(v.center) > 200 * 200) return;
        switch (a.kind()) {
            case ColonyPayloads.VillageAction.PIN -> {
                BuildingType t = a.a() < 0 || a.a() >= BuildingType.values().length ? null : BuildingType.values()[a.a()];
                v.priority = t == v.priority ? null : t;
                if (v.priority != null) {
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.pinned", player.getName(), v.priority.displayName()));
                }
                data.changed();
                sendVillage(player, v.id, false);
            }
            case ColonyPayloads.VillageAction.UNLOCK -> {
                BuildingType t = a.a() < 0 || a.a() >= BuildingType.values().length ? null : BuildingType.values()[a.a()];
                if (t != null && Tree.unlock(v, t)) {
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.unlocked_by", player.getName(), t.displayName())
                            .withStyle(ChatFormatting.GOLD));
                    player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.5F, 1.2F);
                    data.changed();
                } else {
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.6F, 1.0F);
                }
                sendVillage(player, v.id, false);
            }
            case ColonyPayloads.VillageAction.FOCUS -> {
                BuildingType.Branch br = a.a() < 0 || a.a() >= BuildingType.Branch.values().length ? null : BuildingType.Branch.values()[a.a()];
                if (br != null && br.trade() && br != v.focus) {
                    v.focus = br;
                    v.sub = BuildingType.Sub.first(br);
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.focus", player.getName(), br.displayName()));
                    data.changed();
                }
                sendVillage(player, v.id, false);
            }
            case ColonyPayloads.VillageAction.RAISE -> {
                Building b = v.building(a.a());
                if (b == null) return;
                if (Tree.canRaise(v, b)) {
                    v.raiseFirst = v.raiseFirst == b.id ? -1 : b.id;
                    if (v.raiseFirst == b.id) v.log(data.day, Component.translatable("minecraftportsmod.vlog.raise_first", player.getName(), b.type.displayName()));
                    data.changed();
                }
                sendBuilding(player, v, b);
            }
            case ColonyPayloads.VillageAction.SUB -> {
                BuildingType.Sub s = a.a() < 0 || a.a() >= BuildingType.Sub.values().length ? null : BuildingType.Sub.values()[a.a()];
                if (s != null && s != v.sub) {
                    // (a sub-branch of another speciality: the village takes up that speciality too)
                    v.focus = s.branch;
                    v.sub = s;
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.sub", player.getName(), s.branch.displayName(), s.displayName()));
                    data.changed();
                }
                sendVillage(player, v.id, false);
            }
            case ColonyPayloads.VillageAction.QUEUE_UP, ColonyPayloads.VillageAction.QUEUE_DOWN -> {
                VillageLife.queue(v);
                int i = v.order.indexOf(a.a());
                int j = i + (a.kind() == ColonyPayloads.VillageAction.QUEUE_UP ? -1 : 1);
                if (i >= 0 && j >= 0 && j < v.order.size()) {
                    java.util.Collections.swap(v.order, i, j);
                    data.changed();
                }
                sendVillage(player, v.id, false);
            }
            case ColonyPayloads.VillageAction.QUEUE_CANCEL -> {
                if (a.a() < 0) {
                    if (v.research != null) {
                        v.declined.put("research:" + v.research.id(), data.day);
                        v.log(data.day, Component.translatable("minecraftportsmod.vlog.queue_cancelled", player.getName(), v.research.displayName()));
                        v.research = null;
                        data.changed();
                    }
                } else if (VillageLife.cancel(v, v.building(a.a()), data.day)) {
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.queue_cancelled_site", player.getName()));
                    data.changed();
                }
                sendVillage(player, v.id, false);
            }
            case ColonyPayloads.VillageAction.SCOUT -> {
                // where the next expedition goes (from the map table); -1 leaves it to the scout
                v.scoutAim = a.a() < 0 ? -1 : Math.floorMod(a.a(), 360);
                if (v.scoutAim >= 0) {
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.scout_aim", player.getName(), Scouting.wind(v.scoutAim)));
                }
                data.changed();
                Scouting.send(player, v);
            }
            case ColonyPayloads.VillageAction.KEEP -> {
                Building b = v.building(a.a());
                if (b == null) return;
                b.keep = !b.keep;
                data.changed();
                sendBuilding(player, v, b);
            }
            case ColonyPayloads.VillageAction.DEMOLISH -> {
                Building b = v.building(a.a());
                if (b == null || !b.standing() || b.type.isCenter() || b.keep) return;
                b.state = Building.State.DEMOLISHING;
                b.goal = 0;
                b.work = 0;
                if (v.raiseFirst == b.id) v.raiseFirst = -1;
                v.log(data.day, Component.translatable("minecraftportsmod.vlog.demolish_by", player.getName(), b.type.displayName()));
                VillageLife.rehouse(v, data.day);
                data.changed();
                player.closeContainer();
            }
            case ColonyPayloads.VillageAction.CROP -> {
                Building b = v.building(a.a());
                if (b == null || b.type != BuildingType.FIELD || a.b() < 0 || a.b() >= Crop.values().length) return;
                Crop c = Crop.values()[a.b()];
                if (!c.unlocked(v) || c == b.crop()) return;
                b.setOption(c.id());
                v.log(data.day, Component.translatable("minecraftportsmod.vlog.sown", player.getName(), c.displayName()));
                data.changed();
                sendBuilding(player, v, b);
            }
            case ColonyPayloads.VillageAction.OPEN -> {
                Building b = v.building(a.a());
                if (b != null) sendBuilding(player, v, b);
            }
            case ColonyPayloads.VillageAction.TRADE -> sendTrade(player, v, null);
            case ColonyPayloads.VillageAction.ORDERS -> {
                Building b = v.building(a.a());
                if (b != null) sendOrders(player, v, b, null);
            }
            case ColonyPayloads.VillageAction.ORDER -> {
                Building b = v.building(a.a());
                if (b == null || a.b() < 0) return;
                int recipe = a.b() / 1000, pieces = a.b() % 1000;
                TreeData.Recipe r = Orders.recipe(b, recipe);
                int paid = Orders.place(player, v, b, recipe, pieces, data.day);
                Component note;
                if (paid > 0 && r != null) {
                    Component what = TreeData.stack(v, r, 1).getHoverName();
                    note = Component.translatable("minecraftportsmod.order.placed", pieces, what, paid).withStyle(ChatFormatting.DARK_GREEN);
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.ordered", player.getName(), pieces, what, b.type.displayName(), paid));
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
                    data.changed();
                } else {
                    note = Component.translatable("minecraftportsmod.order.refused").withStyle(ChatFormatting.RED);
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.6F, 1.0F);
                }
                sendOrders(player, v, b, note);
            }
            case ColonyPayloads.VillageAction.BUY_NOW -> {
                Building b = v.building(a.a());
                if (b == null || a.b() < 0) return;
                int recipe = a.b() / 1000, pieces = a.b() % 1000;
                TreeData.Recipe r = Orders.recipe(b, recipe);
                int paid = Orders.buyNow(player, v, b, recipe, pieces);
                Component note;
                if (paid > 0 && r != null) {
                    Component what = TreeData.stack(v, r, 1).getHoverName();
                    note = Component.translatable("minecraftportsmod.trade.bought", pieces, what, paid).withStyle(ChatFormatting.DARK_GREEN);
                    v.log(data.day, Component.translatable("minecraftportsmod.vlog.sold_to", player.getName(), pieces, what, paid));
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
                    data.changed();
                } else {
                    note = Component.translatable("minecraftportsmod.trade.no_deal").withStyle(ChatFormatting.RED);
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.6F, 1.0F);
                }
                sendOrders(player, v, b, note);
            }
            case ColonyPayloads.VillageAction.COLLECT -> {
                Building b = v.building(a.a());
                if (b == null) return;
                int n = Orders.collect(player, v, b);
                Component note = n > 0 ? Component.translatable("minecraftportsmod.order.collected", n).withStyle(ChatFormatting.DARK_GREEN)
                        : Component.translatable("minecraftportsmod.order.nothing_ready").withStyle(ChatFormatting.GRAY);
                if (n > 0) data.changed();
                sendOrders(player, v, b, note);
            }
            case ColonyPayloads.VillageAction.SELL, ColonyPayloads.VillageAction.BUY -> {
                // a = the ware (Trade.WARES), b = how many pieces
                if (a.a() < 0 || a.a() >= Trade.WARES.size() || a.b() <= 0 || v.workers(Job.MERCHANT) == 0) return;
                Trade.Ware w = Trade.WARES.get(a.a());
                boolean selling = a.kind() == ColonyPayloads.VillageAction.SELL;
                int n = Math.min(a.b(), selling ? Trade.maxSell(player, v, w) : Trade.maxBuy(player, v, w));
                int paid = selling ? Trade.sell(player, v, w, n) : Trade.buy(player, v, w, n);
                Component note;
                if (paid > 0) {
                    note = Component.translatable(selling ? "minecraftportsmod.trade.sold" : "minecraftportsmod.trade.bought",
                            n, Trade.name(v, w), paid).withStyle(ChatFormatting.DARK_GREEN);
                    v.log(data.day, Component.translatable(selling ? "minecraftportsmod.vlog.bought_from" : "minecraftportsmod.vlog.sold_to",
                            player.getName(), n, Trade.name(v, w), paid));
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
                    data.changed();
                } else {
                    note = Component.translatable("minecraftportsmod.trade.no_deal").withStyle(ChatFormatting.RED);
                    player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.6F, 1.0F);
                }
                sendTrade(player, v, note);
            }
            default -> {
            }
        }
    }

    /** The building a trade works in (the one standing with the highest level), or null. */
    static Building workplace(Village v, Job job) {
        if (job == null) return null;
        Building best = null;
        for (Building b : v.buildings) if (b.type.job == job && b.standing() && (best == null || b.level > best.level)) best = b;
        return best;
    }

    /** A building's people taking orders: what they make by their recipes, and the player's orders with them. */
    public static void sendOrders(ServerPlayer player, Village v, Building b, Component note) {
        Job job = b.type.job;
        String worker = "";
        for (Dweller d : v.dwellers) if (job != null && d.job == job) worker = d.name;
        List<ColonyPayloads.OrderRow> rows = new ArrayList<>();
        for (TreeData.Recipe r : Orders.recipes(b)) {
            Map<Res, Integer> takes = new java.util.LinkedHashMap<>();
            for (TreeData.Input in : r.use()) takes.merge(TreeData.res(in.cat()), in.n(), Integer::sum);
            rows.add(new ColonyPayloads.OrderRow(r.index(), TreeData.stack(v, r, 1), TreeData.stack(v, r, 1).getHoverName(), r.lvl(),
                    Orders.open(b, r), r.n(), Orders.perDay(v, r), Orders.cents(r), Orders.supplied(v, r),
                    takes.isEmpty() ? Component.translatable("minecraftportsmod.order.takes_nothing") : VillageText.amounts(takes), takes.isEmpty(),
                    Orders.inStock(v, r), Orders.nowCents(v, r)));
        }
        List<ColonyPayloads.MineRow> mine = new ArrayList<>();
        for (Orders.Order o : Orders.of(v, player.getUUID(), b.id)) {
            TreeData.Recipe r = Orders.recipe(b, o.recipe);
            if (r == null) continue;
            mine.add(new ColonyPayloads.MineRow(o.id, TreeData.stack(v, r, 1), TreeData.stack(v, r, 1).getHoverName(), o.count, o.made(),
                    Orders.eta(v, o)));
        }
        ServerPlayNetworking.send(player, new ColonyPayloads.OrderView(v.id, b.id, v.name, worker, b.type.displayName(), b.level,
                Trade.emeralds(player), rows, mine, note == null ? Component.empty() : note));
    }

    /** The merchant's stall for a player. */
    public static void sendTrade(ServerPlayer player, Village v, Component note) {
        String merchant = "";
        for (Dweller d : v.dwellers) if (d.job == Job.MERCHANT) merchant = d.name;
        List<ColonyPayloads.TradeRow> rows = new ArrayList<>();
        for (int i = 0; i < Trade.WARES.size(); i++) {
            Trade.Ware w = Trade.WARES.get(i);
            if (!Trade.listed(w)) continue;
            Res r = w.res();
            rows.add(new ColonyPayloads.TradeRow(i, w.kind().ordinal(), Trade.piece(v, w), Trade.name(v, w),
                    r == null ? 0 : v.stock(r), r == null ? 0 : VillageLife.target(v, r), Trade.available(v, w), Trade.sellCents(v, w),
                    Trade.buyCents(v, w), Trade.maxBuy(player, v, w), Trade.maxSell(player, v, w), Trade.carried(player, w)));
        }
        ServerPlayNetworking.send(player, new ColonyPayloads.TradeView(v.id, v.name, merchant, v.emeralds(), Trade.emeralds(player), rows,
                note == null ? Component.empty() : note));
    }

    public static void handleSite(ServerPlayer player, ColonyPayloads.SiteAction a) {
        Village v = data(player).get(a.village());
        Building b = v == null ? null : v.building(a.building());
        if (b == null) return;
        if (a.donate()) {
            if (player.distanceToSqr(b.origin.getX(), b.origin.getY(), b.origin.getZ()) > 24 * 24) return;
            var given = VillageManager.donate(player, v, b);
            if (given.isEmpty()) {
                player.sendSystemMessage(Component.translatable("minecraftportsmod.colony.nothing_to_give").withStyle(ChatFormatting.GRAY));
            } else {
                player.sendSystemMessage(Component.translatable("minecraftportsmod.colony.thanks", VillageText.amounts(given))
                        .withStyle(ChatFormatting.GREEN));
                player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
            }
        }
        sendSite(player, v, b);
    }
}
