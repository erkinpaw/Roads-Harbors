package org.webtrade.minecraftportsmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.ArrayList;
import java.util.List;

/** The screens of the villages: the village board, a building site, a resident. */
public final class ColonyPayloads {

    private ColonyPayloads() {
    }

    private static void comp(RegistryFriendlyByteBuf buf, Component c) {
        ComponentSerialization.STREAM_CODEC.encode(buf, c);
    }

    private static Component comp(RegistryFriendlyByteBuf buf) {
        return ComponentSerialization.STREAM_CODEC.decode(buf);
    }

    private static int[] ints(FriendlyByteBuf buf) {
        return buf.readVarIntArray(1 << 16);
    }

    // ------------------------------------------------------------------ the village

    /** One thing the next level asks for. */
    public record Req(Component label, int have, int need) {
    }

    /** @param job Job ordinal, -1 for a child */
    public record PersonRow(int id, String name, int job, boolean child, boolean elder, int home, Component activity, long joined) {
    }

    /**
     * @param state    Building.State ordinal
     * @param dx       plot centre relative to the village centre
     * @param people   who lives there
     * @param missing  per Res ordinal
     */
    public record BuildingRow(int id, int type, int state, int dx, int dz, int front, int beds, List<String> people,
                              float progress, int[] missing, int eta) {
    }

    public record LogRow(long day, Component text) {
    }

    /**
     * A step of the village's queue: {@code kind} 0 research, 1 a building to put up, 2 one to raise a level, 3 one
     * coming down, 4 a trail being made; {@code id} the building (-1: the research, a trail: -2); what is still missing
     * (what the store cannot give it yet); how far it has come; days to go (-1: unknown); what it waits for.
     */
    public record QueueRow(int kind, int type, int id, int level, int[] missing, float progress, int eta, Component status) {
    }

    /**
     * A node of the development tree.
     *
     * @param type    BuildingType ordinal
     * @param node    Tree.Node ordinal
     * @param levels  how many buildings of it stand at level 1, 2, 3
     * @param unlock  the price of opening it, per Res ordinal
     * @param build   the price of one building, per Res ordinal
     * @param factors why the opening costs what it does (label, percent in {@code have})
     */
    public record NodeRow(int type, int node, int[] levels, int[] unlock, int[] build, List<Req> factors) {
    }

    /**
     * The middle of the village.
     *
     * @param type   BuildingType ordinal of what stands
     * @param next   its next step (-1 if none)
     * @param people people needed for the next step
     * @param queued the next step is under way or asked for
     */
    public record CenterRow(int type, int next, int[] cost, int people, boolean queued) {
    }

    private static void writeReqs(RegistryFriendlyByteBuf buf, List<Req> reqs) {
        buf.writeVarInt(reqs.size());
        for (Req r : reqs) {
            comp(buf, r.label);
            buf.writeVarInt(r.have);
            buf.writeVarInt(r.need);
        }
    }

    private static List<Req> readReqs(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Req> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Req(comp(buf), buf.readVarInt(), buf.readVarInt()));
        return out;
    }

    /**
     * @param map     colours (ARGB) of the land around the village, {@code mapSize}² of them, row by row from the
     *                north-west; empty if not asked for
     * @param level   Village.Level ordinal
     */
    public record VillageView(int id, String name, int level, int mood, long day, float progress, int beds,
                              int[] stock, int[] made, int[] capacity, int eaten, int foodNeed, List<Req> reqs, List<PersonRow> people,
                              List<BuildingRow> buildings, List<LogRow> log, int mapSize, int[] map, int viewerDx, int viewerDz,
                              int boardDx, int boardDz, List<NodeRow> tree, int priority, int focus, CenterRow center,
                              List<QueueRow> queue, int sub, float ready, float gain, int stored, int room, int[] got, int[] spent,
                              Component merchant, int[] shortOf, int[] spare, List<LogRow> deals, int slots, long purse) implements CustomPacketPayload {
        public static final Type<VillageView> TYPE = new Type<>(Minecraftportsmod.id("village_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VillageView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.id);
            buf.writeUtf(v.name, 64);
            buf.writeVarInt(v.level);
            buf.writeVarInt(v.mood);
            buf.writeVarLong(v.day);
            buf.writeFloat(v.progress);
            buf.writeVarInt(v.beds);
            buf.writeVarIntArray(v.stock);
            buf.writeVarIntArray(v.made);
            buf.writeVarIntArray(v.capacity);
            buf.writeVarInt(v.eaten);
            buf.writeVarInt(v.foodNeed);
            buf.writeVarInt(v.reqs.size());
            for (Req r : v.reqs) {
                comp(buf, r.label);
                buf.writeVarInt(r.have);
                buf.writeVarInt(r.need);
            }
            buf.writeVarInt(v.people.size());
            for (PersonRow p : v.people) {
                buf.writeVarInt(p.id);
                buf.writeUtf(p.name, 64);
                buf.writeVarInt(p.job + 1);
                buf.writeBoolean(p.child);
                buf.writeBoolean(p.elder);
                buf.writeVarInt(p.home + 1);
                comp(buf, p.activity);
                buf.writeVarLong(p.joined);
            }
            buf.writeVarInt(v.buildings.size());
            for (BuildingRow b : v.buildings) {
                buf.writeVarInt(b.id);
                buf.writeVarInt(b.type);
                buf.writeVarInt(b.state);
                buf.writeInt(b.dx);
                buf.writeInt(b.dz);
                buf.writeVarInt(b.front);
                buf.writeVarInt(b.beds);
                buf.writeVarInt(b.people.size());
                for (String s : b.people) buf.writeUtf(s, 64);
                buf.writeFloat(b.progress);
                buf.writeVarIntArray(b.missing);
                buf.writeVarInt(b.eta + 1);
            }
            buf.writeVarInt(v.log.size());
            for (LogRow l : v.log) {
                buf.writeVarLong(l.day);
                comp(buf, l.text);
            }
            buf.writeVarInt(v.mapSize);
            buf.writeVarInt(v.map.length);
            for (int c : v.map) buf.writeInt(c);
            buf.writeInt(v.viewerDx);
            buf.writeInt(v.viewerDz);
            buf.writeInt(v.boardDx);
            buf.writeInt(v.boardDz);
            buf.writeVarInt(v.tree.size());
            for (NodeRow n : v.tree) {
                buf.writeVarInt(n.type);
                buf.writeVarInt(n.node);
                buf.writeVarIntArray(n.levels);
                buf.writeVarIntArray(n.unlock);
                buf.writeVarIntArray(n.build);
                writeReqs(buf, n.factors);
            }
            buf.writeVarInt(v.priority + 1);
            buf.writeVarInt(v.focus + 1);
            buf.writeVarInt(v.center.type + 1);
            buf.writeVarInt(v.center.next + 1);
            buf.writeVarIntArray(v.center.cost);
            buf.writeVarInt(v.center.people);
            buf.writeBoolean(v.center.queued);
            buf.writeVarInt(v.queue.size());
            for (QueueRow q : v.queue) {
                buf.writeVarInt(q.kind);
                buf.writeVarInt(q.type + 1);
                buf.writeVarInt(q.id + 2);
                buf.writeVarInt(q.level);
                buf.writeVarIntArray(q.missing);
                buf.writeFloat(q.progress);
                buf.writeVarInt(q.eta + 1);
                comp(buf, q.status);
            }
            buf.writeVarInt(v.sub + 1);
            buf.writeFloat(v.ready);
            buf.writeFloat(v.gain);
            buf.writeVarInt(v.stored);
            buf.writeVarInt(v.room);
            buf.writeVarIntArray(v.got);
            buf.writeVarIntArray(v.spent);
            comp(buf, v.merchant);
            buf.writeVarIntArray(v.shortOf);
            buf.writeVarIntArray(v.spare);
            buf.writeVarInt(v.deals.size());
            for (LogRow l : v.deals) {
                buf.writeVarLong(l.day());
                comp(buf, l.text());
            }
            buf.writeVarInt(v.slots);
            buf.writeVarLong(v.purse);
        }, buf -> {
            int id = buf.readVarInt();
            String name = buf.readUtf(64);
            int level = buf.readVarInt(), mood = buf.readVarInt();
            long day = buf.readVarLong();
            float progress = buf.readFloat();
            int beds = buf.readVarInt();
            int[] stock = ints(buf), made = ints(buf), capacity = ints(buf);
            int eaten = buf.readVarInt(), foodNeed = buf.readVarInt();
            int n = buf.readVarInt();
            List<Req> reqs = new ArrayList<>();
            for (int i = 0; i < n; i++) reqs.add(new Req(comp(buf), buf.readVarInt(), buf.readVarInt()));
            n = buf.readVarInt();
            List<PersonRow> people = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                people.add(new PersonRow(buf.readVarInt(), buf.readUtf(64), buf.readVarInt() - 1, buf.readBoolean(), buf.readBoolean(),
                        buf.readVarInt() - 1, comp(buf), buf.readVarLong()));
            }
            n = buf.readVarInt();
            List<BuildingRow> buildings = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int bid = buf.readVarInt(), type = buf.readVarInt(), state = buf.readVarInt(), dx = buf.readInt(), dz = buf.readInt();
                int front = buf.readVarInt(), bb = buf.readVarInt();
                int pn = buf.readVarInt();
                List<String> ps = new ArrayList<>();
                for (int k = 0; k < pn; k++) ps.add(buf.readUtf(64));
                buildings.add(new BuildingRow(bid, type, state, dx, dz, front, bb, ps, buf.readFloat(), ints(buf), buf.readVarInt() - 1));
            }
            n = buf.readVarInt();
            List<LogRow> log = new ArrayList<>();
            for (int i = 0; i < n; i++) log.add(new LogRow(buf.readVarLong(), comp(buf)));
            int mapSize = buf.readVarInt();
            int[] map = new int[Math.min(buf.readVarInt(), 256 * 256)];
            for (int i = 0; i < map.length; i++) map[i] = buf.readInt();
            int vdx = buf.readInt(), vdz = buf.readInt(), bdx = buf.readInt(), bdz = buf.readInt();
            n = buf.readVarInt();
            List<NodeRow> tree = new ArrayList<>();
            for (int i = 0; i < n; i++) tree.add(new NodeRow(buf.readVarInt(), buf.readVarInt(), ints(buf), ints(buf), ints(buf), readReqs(buf)));
            int priority = buf.readVarInt() - 1, focus = buf.readVarInt() - 1;
            CenterRow center = new CenterRow(buf.readVarInt() - 1, buf.readVarInt() - 1, ints(buf), buf.readVarInt(), buf.readBoolean());
            int qn = buf.readVarInt();
            List<QueueRow> queue = new ArrayList<>();
            for (int q = 0; q < qn; q++) {
                queue.add(new QueueRow(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt() - 2, buf.readVarInt(), ints(buf), buf.readFloat(),
                        buf.readVarInt() - 1, comp(buf)));
            }
            int sub = buf.readVarInt() - 1;
            float ready = buf.readFloat(), gain = buf.readFloat();
            int stored = buf.readVarInt(), room = buf.readVarInt();
            int[] got = ints(buf), spent = ints(buf);
            Component merchant = comp(buf);
            int[] shortOf = ints(buf), spare = ints(buf);
            List<LogRow> deals = new ArrayList<>();
            for (int i = buf.readVarInt(); i > 0; i--) deals.add(new LogRow(buf.readVarLong(), comp(buf)));
            int slots = buf.readVarInt();
            long purse = buf.readVarLong();
            return new VillageView(id, name, level, mood, day, progress, beds, stock, made, capacity, eaten, foodNeed, reqs, people, buildings,
                    log, mapSize, map, vdx, vdz, bdx, bdz, tree, priority, focus, center, queue, sub, ready, gain, stored, room, got, spent,
                    merchant, shortOf, spare, deals, slots, purse);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ a building site

    /**
     * @param cost      per Res ordinal
     * @param carried   what the player carries that the site could use, per Res ordinal
     */
    public record SiteView(int village, int building, String villageName, int kind, int state, int[] cost, int[] delivered,
                           int[] stock, int[] carried, float progress, int eta) implements CustomPacketPayload {
        public static final Type<SiteView> TYPE = new Type<>(Minecraftportsmod.id("site_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SiteView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeVarInt(v.building);
            buf.writeUtf(v.villageName, 64);
            buf.writeVarInt(v.kind);
            buf.writeVarInt(v.state);
            buf.writeVarIntArray(v.cost);
            buf.writeVarIntArray(v.delivered);
            buf.writeVarIntArray(v.stock);
            buf.writeVarIntArray(v.carried);
            buf.writeFloat(v.progress);
            buf.writeVarInt(v.eta + 1);
        }, buf -> new SiteView(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(),
                ints(buf), ints(buf), ints(buf), ints(buf), buf.readFloat(), buf.readVarInt() - 1));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ a resident

    /**
     * A task the player has taken, as the inventory's panel shows it: what, whose, how far along, days left; and, for
     * the panel's tip, the task in full, the giver's trade, what is handed in, carried, the reward, where the letter went.
     */
    public record PanelQuest(net.minecraft.world.item.ItemStack icon, Component what, String giver, String village, int have, int need, int days,
                             int kind, Component full, Component trade, int done, int carried, int reward, String to) {
    }

    /** The inventory's panel: the purse (hundredths), the tasks taken, the plot, the trade hired at. */
    public record PanelView(long purse, List<PanelQuest> quests, Component plot, Component hired) implements CustomPacketPayload {
        public static final Type<PanelView> TYPE = new Type<>(Minecraftportsmod.id("panel_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarLong(v.purse);
            buf.writeVarInt(v.quests.size());
            for (PanelQuest q : v.quests) {
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, q.icon);
                comp(buf, q.what);
                buf.writeUtf(q.giver, 64);
                buf.writeUtf(q.village, 64);
                buf.writeVarInt(q.have);
                buf.writeVarInt(q.need);
                buf.writeVarInt(q.days);
                buf.writeVarInt(q.kind);
                comp(buf, q.full);
                comp(buf, q.trade);
                buf.writeVarInt(q.done);
                buf.writeVarInt(q.carried);
                buf.writeVarInt(q.reward);
                buf.writeUtf(q.to, 64);
            }
            comp(buf, v.plot);
            comp(buf, v.hired);
        }, buf -> {
            long purse = buf.readVarLong();
            int n = buf.readVarInt();
            List<PanelQuest> qs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                var icon = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                qs.add(new PanelQuest(icon, comp(buf), buf.readUtf(64), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), comp(buf), comp(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64)));
            }
            return new PanelView(purse, qs, comp(buf), comp(buf));
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A building's badge: where it hangs, the building's box (x0 y0 z0 x1 y1 z1), its icon, how far its work is (below 0: none), what it is doing, its name. */
    public record Badge(float x, float y, float z, int[] box, net.minecraft.world.item.ItemStack icon, float progress, Component doing, Component title) {
    }

    /** The badges over the buildings near a player (every second). */
    public record BadgeView(List<Badge> badges) implements CustomPacketPayload {
        public static final Type<BadgeView> TYPE = new Type<>(Minecraftportsmod.id("badge_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BadgeView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.badges.size());
            for (Badge b : v.badges) {
                buf.writeFloat(b.x);
                buf.writeFloat(b.y);
                buf.writeFloat(b.z);
                for (int k = 0; k < 6; k++) buf.writeVarInt(b.box[k]);
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, b.icon);
                buf.writeFloat(b.progress);
                comp(buf, b.doing);
                comp(buf, b.title);
            }
        }, buf -> {
            int n = buf.readVarInt();
            List<Badge> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
                int[] box = new int[6];
                for (int k = 0; k < 6; k++) box[k] = buf.readVarInt();
                var icon = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                float p = buf.readFloat();
                out.add(new Badge(x, y, z, box, icon, p, comp(buf), comp(buf)));
            }
            return new BadgeView(out);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A house the village will build on a player's plot: its kind, beds, what it takes, what the player pays. */
    public record PlotHouse(int kind, int beds, int[] cost, int price) {
    }

    /** A player's plot, as its stone shows it to its owner: the houses to choose from, the purse. */
    public record PlotView(int village, String villageName, List<PlotHouse> houses, long purse) implements CustomPacketPayload {
        public static final Type<PlotView> TYPE = new Type<>(Minecraftportsmod.id("plot_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlotView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeUtf(v.villageName, 64);
            buf.writeVarInt(v.houses.size());
            for (PlotHouse h : v.houses) {
                buf.writeVarInt(h.kind);
                buf.writeVarInt(h.beds);
                buf.writeVarIntArray(h.cost);
                buf.writeVarInt(h.price);
            }
            buf.writeVarLong(v.purse);
        }, buf -> {
            int village = buf.readVarInt();
            String name = buf.readUtf(64);
            int n = buf.readVarInt();
            List<PlotHouse> hs = new ArrayList<>();
            for (int i = 0; i < n; i++) hs.add(new PlotHouse(buf.readVarInt(), buf.readVarInt(), ints(buf), buf.readVarInt()));
            return new PlotView(village, name, hs, buf.readVarLong());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The client asks for the inventory's panel. */
    public record RequestPanel() implements CustomPacketPayload {
        public static final Type<RequestPanel> TYPE = new Type<>(Minecraftportsmod.id("request_panel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPanel> CODEC = StreamCodec.unit(new RequestPanel());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A harbour a skipper will sail a player to, and what it costs (emeralds). */
    public record Passage(int village, String name, int price) {
    }

    /** @param quest the person's task: 0 none, 1 one to take, 2 taken by this player, 3 taken by someone else */
    public record PersonView(int village, int person, String villageName, int level, String name, int job, boolean child, boolean elder,
                             Component activity, Component home, long days, int orders, int quest, int plotPrice,
                             int hired, int brought, int quota, List<Passage> passages) implements CustomPacketPayload {
        public static final Type<PersonView> TYPE = new Type<>(Minecraftportsmod.id("person_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PersonView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeVarInt(v.person);
            buf.writeUtf(v.villageName, 64);
            buf.writeVarInt(v.level);
            buf.writeUtf(v.name, 64);
            buf.writeVarInt(v.job + 1);
            buf.writeBoolean(v.child);
            buf.writeBoolean(v.elder);
            comp(buf, v.activity);
            comp(buf, v.home);
            buf.writeVarLong(v.days);
            buf.writeVarInt(v.orders + 1);
            buf.writeVarInt(v.quest);
            buf.writeVarInt(v.plotPrice + 1);
            buf.writeVarInt(v.hired + 1);
            buf.writeVarInt(v.brought);
            buf.writeVarInt(v.quota);
            buf.writeVarInt(v.passages.size());
            for (Passage p : v.passages) {
                buf.writeVarInt(p.village());
                buf.writeUtf(p.name(), 64);
                buf.writeVarInt(p.price());
            }
        }, buf -> new PersonView(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readUtf(64),
                buf.readVarInt() - 1, buf.readBoolean(), buf.readBoolean(), comp(buf), comp(buf), buf.readVarLong(), buf.readVarInt() - 1,
                buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), passages(buf)));

        private static List<Passage> passages(RegistryFriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<Passage> out = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) out.add(new Passage(buf.readVarInt(), buf.readUtf(64), buf.readVarInt()));
            return out;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ a person's task

    /**
     * A person's task, as a player sees it.
     *
     * @param kind     Quests.Kind ordinal
     * @param state    0 to take, 1 taken by this player, 2 taken by someone else
     * @param carried  how much of what is asked the player has on them
     * @param left     days left (to take it, or to do it)
     * @param to       LETTER: the village it reached ("" not yet)
     */
    public record QuestView(int village, int person, String name, int job, boolean elder, int quest, int kind, ItemStack icon, Component what,
                            int count, int done, int carried, int reward, int left, int state, String to, int taken, int thanks)
            implements CustomPacketPayload {
        public static final Type<QuestView> TYPE = new Type<>(Minecraftportsmod.id("quest_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuestView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeVarInt(v.person);
            buf.writeUtf(v.name, 64);
            buf.writeVarInt(v.job + 1);
            buf.writeBoolean(v.elder);
            buf.writeVarInt(v.quest + 1);
            buf.writeVarInt(v.kind);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, v.icon);
            comp(buf, v.what);
            buf.writeVarInt(v.count);
            buf.writeVarInt(v.done);
            buf.writeVarInt(v.carried);
            buf.writeVarInt(v.reward);
            buf.writeVarInt(v.left + 1);
            buf.writeVarInt(v.state);
            buf.writeUtf(v.to, 64);
            buf.writeVarInt(v.taken);
            buf.writeVarInt(v.thanks);
        }, buf -> new QuestView(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readVarInt() - 1, buf.readBoolean(), buf.readVarInt() - 1,
                buf.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), comp(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ a building's own menu

    /**
     * @param kind       BuildingType ordinal
     * @param tools      the tool level of the trade working here (-1 if none)
     * @param level      its level now
     * @param goal       the level it is being raised to (0 if none)
     * @param raiseCost  what the next level takes, per Res ordinal (empty at the top)
     * @param raiseFirst the players asked for it to be raised first
     * @param keep       never to be pulled down
     * @param crops      what can be sown here (Crop ordinals), empty if not a field
     * @param crop       what is sown now (Crop ordinal, -1 if not a field)
     * @param makes      what it makes in a day, per Res ordinal, in tenths
     * @param uses       what it uses up in a day, per Res ordinal, in tenths
     */
    public record BuildingView(int village, int building, String villageName, int kind, int state, float progress,
                               List<String> people, int tools, List<Component> notes, int level, int goal, int[] raiseCost,
                               boolean raiseFirst, boolean keep, int[] crops, int crop, int[] makes, int[] uses, int[] stock) implements CustomPacketPayload {
        public static final Type<BuildingView> TYPE = new Type<>(Minecraftportsmod.id("building_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BuildingView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeVarInt(v.building);
            buf.writeUtf(v.villageName, 64);
            buf.writeVarInt(v.kind);
            buf.writeVarInt(v.state);
            buf.writeFloat(v.progress);
            buf.writeVarInt(v.people.size());
            for (String s : v.people) buf.writeUtf(s, 64);
            buf.writeVarInt(v.tools + 1);
            buf.writeVarInt(v.notes.size());
            for (Component c : v.notes) comp(buf, c);
            buf.writeVarInt(v.level);
            buf.writeVarInt(v.goal);
            buf.writeVarIntArray(v.raiseCost);
            buf.writeBoolean(v.raiseFirst);
            buf.writeBoolean(v.keep);
            buf.writeVarIntArray(v.crops);
            buf.writeVarInt(v.crop + 1);
            buf.writeVarIntArray(v.makes);
            buf.writeVarIntArray(v.uses);
            buf.writeVarIntArray(v.stock);
        }, buf -> {
            int village = buf.readVarInt(), building = buf.readVarInt();
            String name = buf.readUtf(64);
            int kind = buf.readVarInt(), state = buf.readVarInt();
            float progress = buf.readFloat();
            int n = buf.readVarInt();
            List<String> people = new ArrayList<>();
            for (int i = 0; i < n; i++) people.add(buf.readUtf(64));
            int tools = buf.readVarInt() - 1;
            n = buf.readVarInt();
            List<Component> notes = new ArrayList<>();
            for (int i = 0; i < n; i++) notes.add(comp(buf));
            int level = buf.readVarInt(), goal = buf.readVarInt();
            int[] raiseCost = ints(buf);
            boolean raiseFirst = buf.readBoolean(), keep = buf.readBoolean();
            int[] crops = ints(buf);
            int crop = buf.readVarInt() - 1;
            int[] makes = ints(buf), uses = ints(buf), stock = ints(buf);
            return new BuildingView(village, building, name, kind, state, progress, people, tools, notes, level, goal, raiseCost,
                    raiseFirst, keep, crops, crop, makes, uses, stock);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ the cartographer's map

    /** A village the scouts found: where it is, its title and speciality (ordinals, -1 unknown), the day it was found. */
    public record PlaceRow(String name, int x, int z, int level, int focus, long found) {
    }

    /** A scout: away (until {@code back}) or home (since {@code back}), and the last expedition's heading and reach. */
    public record ScoutRow(String name, boolean away, long back, int heading, int range) {
    }

    /**
     * The map on the cartographer's table: the cells mapped ({@code cell} blocks each, keys as Scouting.key), their
     * colours, the villages found and the scouts.
     */
    public record MapView(int village, String villageName, int x, int z, int cell, long[] keys, int[] colors, List<PlaceRow> places,
                          List<ScoutRow> scouts, long day, int range, int aim) implements CustomPacketPayload {
        public static final Type<MapView> TYPE = new Type<>(Minecraftportsmod.id("map_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeUtf(v.villageName, 64);
            buf.writeInt(v.x);
            buf.writeInt(v.z);
            buf.writeVarInt(v.cell);
            buf.writeVarInt(v.keys.length);
            for (long k : v.keys) buf.writeLong(k);
            for (int c : v.colors) buf.writeInt(c);
            buf.writeVarInt(v.places.size());
            for (PlaceRow p : v.places) {
                buf.writeUtf(p.name, 64);
                buf.writeInt(p.x);
                buf.writeInt(p.z);
                buf.writeVarInt(p.level + 1);
                buf.writeVarInt(p.focus + 1);
                buf.writeVarLong(p.found);
            }
            buf.writeVarInt(v.scouts.size());
            for (ScoutRow s : v.scouts) {
                buf.writeUtf(s.name, 64);
                buf.writeBoolean(s.away);
                buf.writeVarLong(s.back + 1);
                buf.writeInt(s.heading);
                buf.writeVarInt(s.range);
            }
            buf.writeVarLong(v.day);
            buf.writeVarInt(v.range);
            buf.writeVarInt(v.aim + 1);
        }, buf -> {
            int village = buf.readVarInt();
            String name = buf.readUtf(64);
            int x = buf.readInt(), z = buf.readInt(), cell = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), 400_000);
            long[] keys = new long[n];
            int[] colors = new int[n];
            for (int i = 0; i < n; i++) keys[i] = buf.readLong();
            for (int i = 0; i < n; i++) colors[i] = buf.readInt();
            int pn = buf.readVarInt();
            List<PlaceRow> places = new ArrayList<>();
            for (int i = 0; i < pn; i++) {
                places.add(new PlaceRow(buf.readUtf(64), buf.readInt(), buf.readInt(), buf.readVarInt() - 1, buf.readVarInt() - 1, buf.readVarLong()));
            }
            int sn = buf.readVarInt();
            List<ScoutRow> scouts = new ArrayList<>();
            for (int i = 0; i < sn; i++) scouts.add(new ScoutRow(buf.readUtf(64), buf.readBoolean(), buf.readVarLong() - 1, buf.readInt(), buf.readVarInt()));
            return new MapView(village, name, x, z, cell, keys, colors, places, scouts, buf.readVarLong(), buf.readVarInt(), buf.readVarInt() - 1);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One of the merchant's wares (Trade.WARES index): its kind (Res.Kind ordinal), a piece of it and its name; the
     * village's stock and target (resources; 0 for tools), the pieces it has for sale; its prices a piece in
     * hundredths of an emerald (-1: not selling / not buying); the most the player can buy and sell in one deal, and
     * the pieces the player carries.
     */
    public record TradeRow(int ware, int kind, net.minecraft.world.item.ItemStack icon, Component name, int stock, int target,
                           int available, int sellCents, int buyCents, int maxBuy, int maxSell, int carried) {
    }

    public record TradeView(int village, String villageName, String merchant, long purse, long playerEmeralds, List<TradeRow> rows,
                            Component note) implements CustomPacketPayload {
        public static final Type<TradeView> TYPE = new Type<>(Minecraftportsmod.id("trade_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TradeView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeUtf(v.villageName, 64);
            buf.writeUtf(v.merchant, 64);
            buf.writeVarLong(v.purse);
            buf.writeVarLong(v.playerEmeralds);
            buf.writeVarInt(v.rows.size());
            for (TradeRow r : v.rows) {
                buf.writeVarInt(r.ware);
                buf.writeVarInt(r.kind);
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, r.icon);
                comp(buf, r.name);
                buf.writeVarInt(r.stock);
                buf.writeVarInt(r.target);
                buf.writeVarInt(r.available);
                buf.writeVarInt(r.sellCents + 1);
                buf.writeVarInt(r.buyCents + 1);
                buf.writeVarInt(r.maxBuy);
                buf.writeVarInt(r.maxSell);
                buf.writeVarInt(r.carried);
            }
            comp(buf, v.note);
        }, buf -> {
            int village = buf.readVarInt();
            String name = buf.readUtf(64), merchant = buf.readUtf(64);
            long purse = buf.readVarLong(), em = buf.readVarLong();
            int n = buf.readVarInt();
            List<TradeRow> rows = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int ware = buf.readVarInt(), kind = buf.readVarInt();
                var icon = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                Component wname = comp(buf);
                rows.add(new TradeRow(ware, kind, icon, wname, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1,
                        buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            return new TradeView(village, name, merchant, purse, em, rows, comp(buf));
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A recipe of a building that can be ordered: its place in the building's list, a piece of it, its name; the level
     * it opens at (and if it is open), pieces a making gives, pieces a day the building's people make, the price of a
     * piece in hundredths of an emerald, and whether the store has what a making takes right now.
     */
    public record OrderRow(int recipe, net.minecraft.world.item.ItemStack icon, Component name, int lvl, boolean open, int perMaking,
                           int perDay, int cents, boolean supplied, Component takes, boolean gathered, int stock, int nowCents,
                           List<net.minecraft.world.item.ItemStack> ins, int[] inStock, int seconds) {
    }

    /** One of the player's orders at the building: pieces ordered and made, days left (0 ready, -1 nobody to make it). */
    public record MineRow(int id, net.minecraft.world.item.ItemStack icon, Component name, int count, int made, int eta) {
    }

    /** An order in a workshop's queue, whoever's: what, how many of it made, whose (the village, a player). */
    public record WorkRow(net.minecraft.world.item.ItemStack icon, int count, int made, Component who) {
    }

    /**
     * A building's people, taking orders: what they make, the player's orders with them, the workshop's whole queue (the
     * first being made: {@code progress} 0..1, seconds left), and the uses left of its tools (wooden, stone, iron).
     */
    public record OrderView(int village, int building, String villageName, String worker, Component buildingName, int level,
                            long playerEmeralds, List<OrderRow> rows, List<MineRow> mine, Component note, List<WorkRow> queue, float progress,
                            int secondsLeft, int[] tools, int workers) implements CustomPacketPayload {
        public static final Type<OrderView> TYPE = new Type<>(Minecraftportsmod.id("order_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OrderView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.village);
            buf.writeVarInt(v.building);
            buf.writeUtf(v.villageName, 64);
            buf.writeUtf(v.worker, 64);
            comp(buf, v.buildingName);
            buf.writeVarInt(v.level);
            buf.writeVarLong(v.playerEmeralds);
            buf.writeVarInt(v.rows.size());
            for (OrderRow r : v.rows) {
                buf.writeVarInt(r.recipe);
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, r.icon);
                comp(buf, r.name);
                buf.writeVarInt(r.lvl);
                buf.writeBoolean(r.open);
                buf.writeVarInt(r.perMaking);
                buf.writeVarInt(r.perDay);
                buf.writeVarInt(r.cents);
                buf.writeBoolean(r.supplied);
                comp(buf, r.takes);
                buf.writeBoolean(r.gathered);
                buf.writeVarInt(r.stock);
                buf.writeVarInt(r.nowCents + 1);
                buf.writeVarInt(r.ins.size());
                for (int k = 0; k < r.ins.size(); k++) {
                    net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, r.ins.get(k));
                    buf.writeVarInt(r.inStock[k]);
                }
                buf.writeVarInt(r.seconds);
            }
            buf.writeVarInt(v.mine.size());
            for (MineRow m : v.mine) {
                buf.writeVarInt(m.id);
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, m.icon);
                comp(buf, m.name);
                buf.writeVarInt(m.count);
                buf.writeVarInt(m.made);
                buf.writeVarInt(m.eta + 1);
            }
            comp(buf, v.note);
            buf.writeVarInt(v.queue.size());
            for (WorkRow q : v.queue) {
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, q.icon);
                buf.writeVarInt(q.count);
                buf.writeVarInt(q.made);
                comp(buf, q.who);
            }
            buf.writeFloat(v.progress);
            buf.writeVarInt(v.secondsLeft + 1);
            for (int k = 0; k < 3; k++) buf.writeVarInt(v.tools[k]);
            buf.writeVarInt(v.workers);
        }, buf -> {
            int village = buf.readVarInt(), building = buf.readVarInt();
            String vname = buf.readUtf(64), worker = buf.readUtf(64);
            Component bname = comp(buf);
            int level = buf.readVarInt();
            long em = buf.readVarLong();
            int n = buf.readVarInt();
            List<OrderRow> rows = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int recipe = buf.readVarInt();
                var icon = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                Component name = comp(buf);
                int lvl = buf.readVarInt();
                boolean open = buf.readBoolean();
                int perMaking = buf.readVarInt(), perDay = buf.readVarInt(), cents = buf.readVarInt();
                boolean supplied = buf.readBoolean();
                Component takes = comp(buf);
                boolean gathered = buf.readBoolean();
                int stock = buf.readVarInt(), now = buf.readVarInt() - 1, k = buf.readVarInt();
                List<net.minecraft.world.item.ItemStack> ins = new ArrayList<>();
                int[] inStock = new int[k];
                for (int j = 0; j < k; j++) {
                    ins.add(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                    inStock[j] = buf.readVarInt();
                }
                rows.add(new OrderRow(recipe, icon, name, lvl, open, perMaking, perDay, cents, supplied, takes, gathered, stock, now, ins, inStock,
                        buf.readVarInt()));
            }
            int m = buf.readVarInt();
            List<MineRow> mine = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                int id = buf.readVarInt();
                var icon = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                Component name = comp(buf);
                mine.add(new MineRow(id, icon, name, buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1));
            }
            Component note = comp(buf);
            int qn = buf.readVarInt();
            List<WorkRow> queue = new ArrayList<>();
            for (int i = 0; i < qn; i++) {
                var icon = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                queue.add(new WorkRow(icon, buf.readVarInt(), buf.readVarInt(), comp(buf)));
            }
            float progress = buf.readFloat();
            int left = buf.readVarInt() - 1;
            int[] tools = {buf.readVarInt(), buf.readVarInt(), buf.readVarInt()};
            return new OrderView(village, building, vname, worker, bname, level, em, rows, mine, note, queue, progress, left, tools, buf.readVarInt());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Something a player does from a village screen.
     *
     * @param kind {@link #PIN} (a = BuildingType ordinal, -1 to unpin), {@link #CROP} (a = building, b = Crop ordinal),
     *             {@link #OPEN} (a = building)
     */
    public record VillageAction(int village, int kind, int a, int b) implements CustomPacketPayload {
        public static final int PIN = 0, CROP = 1, OPEN = 2, TRADE = 3, SELL = 4, BUY = 5, UNLOCK = 6, FOCUS = 7, RAISE = 8,
                DEMOLISH = 9, KEEP = 10, SCOUT = 11,
                /** a = building: its people's orders */ ORDERS = 12,
                /** a = building, b = recipe index * 1000 + pieces */ ORDER = 13,
                /** a = building: take what is ready */ COLLECT = 14,
                /** a = building, b = recipe index * 1000 + pieces: bought now from the village's stock */ BUY_NOW = 15,
                /** a = building id (-1: the research): a step of the queue up, down, out of it */ QUEUE_UP = 16, QUEUE_DOWN = 17, QUEUE_CANCEL = 18,
                /** a = sub-branch ordinal: what the village is known for, within its speciality */ SUB = 19,
                /** a = person: their task; a = quest id: take it, hand in what is asked, give it up */ QUEST = 20, QUEST_TAKE = 21, QUEST_HAND = 22, QUEST_DROP = 23,
                /** a = person (the head of the village): buy a boundary stone */ PLOT_BUY = 24,
                /** a = person: take what one carries to the store; hire on at a trade (or leave it) */ DEPOSIT = 25, HIRE = 26,
                /** a = person (a skipper), b = village: a passage there on his ship */ PASSAGE = 27,
                /** a = workshop: the player's tools put in its slot */ TOOLS = 28,
                /** a = BuildingType ordinal: the village builds that house on the player's plot */ PLOT_HOUSE = 29;
        public static final Type<VillageAction> TYPE = new Type<>(Minecraftportsmod.id("village_action"));
        public static final StreamCodec<FriendlyByteBuf, VillageAction> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.village);
            buf.writeVarInt(p.kind);
            buf.writeVarInt(p.a + 1);
            buf.writeVarInt(p.b + 1);
        }, buf -> new VillageAction(buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt() - 1));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ asking

    /** Ask for a village's screen; {@code map}: include the land around it. */
    public record RequestVillage(int village, boolean map) implements CustomPacketPayload {
        public static final Type<RequestVillage> TYPE = new Type<>(Minecraftportsmod.id("request_village"));
        public static final StreamCodec<FriendlyByteBuf, RequestVillage> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.village);
                    buf.writeBoolean(p.map);
                }, buf -> new RequestVillage(buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ask for a building site's screen, or ({@code donate}) hand over materials for it. */
    public record SiteAction(int village, int building, boolean donate) implements CustomPacketPayload {
        public static final Type<SiteAction> TYPE = new Type<>(Minecraftportsmod.id("site_action"));
        public static final StreamCodec<FriendlyByteBuf, SiteAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.village);
                    buf.writeVarInt(p.building);
                    buf.writeBoolean(p.donate);
                }, buf -> new SiteAction(buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
