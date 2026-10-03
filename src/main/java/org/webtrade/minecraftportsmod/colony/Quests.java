package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the village's people ask of the players. Every task comes out of the village's own life: a store it is short
 * of, a building site waiting for its materials, a trade that would work faster with the right things to hand,
 * monsters about at night, a village nobody has heard of. Done, it changes the village (the store filled, the site
 * supplied, the trade working faster for some days, two villages knowing of each other) and the one who asked pays
 * for it out of the village's purse (or, with no emeralds to spare, in goods).
 */
public final class Quests {

    private Quests() {
    }

    public enum Kind {
        /** Bring so many units of a resource the village is short of: into its store. */
        BRING,
        /** Bring so many of an item a trade works faster with: the trade's work goes faster for some days. */
        ITEM,
        /** Bring what a building site still lacks: it goes straight to the site. */
        SITE,
        /** Kill so many monsters round the village. */
        HUNT,
        /** Carry a letter to a village this one has never heard of, and its answer back. */
        LETTER;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** How long a task offered waits for someone to take it, and how long one taken may take, by kind (days). */
    static final int OFFER_DAYS = 3;
    /** Tasks a player can have taken at once, all villages together. */
    public static final int MAX_TAKEN = 5;
    /** How far from the village's middle a monster killed counts for a hunt. */
    static final int HUNT_RANGE = 96;
    /** How much faster a trade works with what it asked for, and for how many days. */
    static final double BOOST = 1.3;
    static final int BOOST_DAYS = 5;

    public static final class Quest {
        static final Codec<Quest> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(q -> q.id),
                Codec.INT.fieldOf("giver").forGetter(q -> q.giver),
                Codec.STRING.fieldOf("kind").forGetter(q -> q.kind.id()),
                Codec.STRING.optionalFieldOf("what", "").forGetter(q -> q.what),
                Codec.INT.fieldOf("count").forGetter(q -> q.count),
                Codec.INT.optionalFieldOf("done", 0).forGetter(q -> q.done),
                Codec.INT.optionalFieldOf("reward", 0).forGetter(q -> q.reward),
                Codec.INT.optionalFieldOf("building", -1).forGetter(q -> q.building),
                Codec.LONG.fieldOf("until").forGetter(q -> q.until),
                UUIDUtil.CODEC.optionalFieldOf("taker").forGetter(q -> Optional.ofNullable(q.taker)),
                Codec.STRING.optionalFieldOf("taker_name", "").forGetter(q -> q.takerName),
                Codec.INT.optionalFieldOf("to", -1).forGetter(q -> q.to)
        ).apply(i, (id, giver, kind, what, count, done, reward, building, until, taker, takerName, to) -> {
            Quest q = new Quest(id, giver, Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)), what, count);
            q.done = done;
            q.reward = reward;
            q.building = building;
            q.until = until;
            q.taker = taker.orElse(null);
            q.takerName = takerName;
            q.to = to;
            return q;
        }));

        public final int id;
        /** The person who asks. */
        public final int giver;
        public final Kind kind;
        /** A resource id (BRING, SITE), an item id or #tag (ITEM), else "". */
        public final String what;
        public final int count;
        int done;
        /** Emeralds promised (worked out when offered; paid in goods if the purse is short when it is done). */
        int reward;
        /** The building site (SITE). */
        int building = -1;
        /** The day the offer, or the task taken, runs out. */
        long until;
        UUID taker;
        String takerName = "";
        /** LETTER: the village the letter reached (-1: not yet). */
        int to = -1;

        Quest(int id, int giver, Kind kind, String what, int count) {
            this.id = id;
            this.giver = giver;
            this.kind = kind;
            this.what = what;
            this.count = count;
        }

        public int done() {
            return done;
        }

        public int reward() {
            return reward;
        }

        public int building() {
            return building;
        }

        public long until() {
            return until;
        }

        public UUID taker() {
            return taker;
        }

        public int to() {
            return to;
        }

        public boolean taken() {
            return taker != null;
        }

        /** Everything asked for is done (a hunt's monsters killed, a letter's answer in hand): only the reward is left. */
        public boolean complete() {
            return done >= count;
        }

        public Res res() {
            return kind == Kind.BRING || kind == Kind.SITE ? Res.byId(what) : null;
        }
    }

    /** What a village's people have asked for, and what the players did for it. */
    public static final class Board {
        static final Codec<Board> CODEC = RecordCodecBuilder.create(i -> i.group(
                Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(b -> b.quests),
                Codec.INT.optionalFieldOf("next", 1).forGetter(b -> b.next),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("thanks", Map.of()).forGetter(b -> {
                    Map<String, Integer> m = new HashMap<>();
                    b.thanks.forEach((u, n) -> m.put(u.toString(), n));
                    return m;
                }),
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("boosts", Map.of()).forGetter(b -> {
                    Map<String, Long> m = new HashMap<>();
                    b.boosts.forEach((j, d) -> m.put(j.id(), d));
                    return m;
                }),
                Plots.Plot.CODEC.listOf().optionalFieldOf("plots", List.of()).forGetter(b -> b.plots),
                UUIDUtil.CODEC.listOf().optionalFieldOf("gifted", List.of()).forGetter(b -> new ArrayList<>(b.gifted))
        ).apply(i, (quests, next, thanks, boosts, plots, gifted) -> {
            Board b = new Board();
            b.quests.addAll(quests);
            b.next = next;
            thanks.forEach((u, n) -> {
                try {
                    b.thanks.put(UUID.fromString(u), n);
                } catch (IllegalArgumentException ignored) {
                }
            });
            boosts.forEach((j, d) -> {
                Job job = Job.byId(j);
                if (job != null) b.boosts.put(job, d);
            });
            b.plots.addAll(plots);
            b.gifted.addAll(gifted);
            return b;
        }));

        final List<Quest> quests = new ArrayList<>();
        int next = 1;
        /** Tasks each player has done for the village. */
        final Map<UUID, Integer> thanks = new HashMap<>();
        /** The trades working faster thanks to the players, until the day given. */
        final EnumMap<Job, Long> boosts = new EnumMap<>(Job.class);
        /** The players' plots in the village (see {@link Plots}), and those it gave a boundary stone to. */
        final List<Plots.Plot> plots = new ArrayList<>();
        final java.util.Set<UUID> gifted = new java.util.HashSet<>();

        public List<Plots.Plot> plots() {
            return plots;
        }

        public List<Quest> quests() {
            return quests;
        }

        public int thanks(UUID player) {
            return thanks.getOrDefault(player, 0);
        }
    }

    // ------------------------------------------------------------------ what the trades ask for

    /** The item a trade works faster with, and how many of it: bone meal for the fields, torches for the pit... */
    record Want(Job job, String item, int count) {
    }

    static final List<Want> WANTS = List.of(
            new Want(Job.FARMER, "minecraft:bone_meal", 24),
            new Want(Job.FARMER, "minecraft:composter", 2),
            new Want(Job.FARMER, "minecraft:iron_hoe", 1),
            new Want(Job.WOODCUTTER, "#minecraft:saplings", 16),
            new Want(Job.WOODCUTTER, "minecraft:iron_axe", 1),
            new Want(Job.WOODCUTTER, "minecraft:grindstone", 1),
            new Want(Job.MINER, "minecraft:torch", 32),
            new Want(Job.MINER, "minecraft:ladder", 16),
            new Want(Job.MINER, "minecraft:iron_pickaxe", 1),
            new Want(Job.FISHER, "minecraft:string", 12),
            new Want(Job.FISHER, "minecraft:fishing_rod", 2),
            new Want(Job.FISHER, "#minecraft:boats", 1),
            new Want(Job.GATHERER, "minecraft:bowl", 8),
            new Want(Job.GATHERER, "minecraft:glass_bottle", 8),
            new Want(Job.GATHERER, "minecraft:shears", 1),
            new Want(Job.HERDER, "minecraft:lead", 3),
            new Want(Job.HERDER, "minecraft:hay_block", 6),
            new Want(Job.HERDER, "minecraft:bucket", 2),
            new Want(Job.SMITH, "minecraft:lava_bucket", 1),
            new Want(Job.SMITH, "minecraft:anvil", 1),
            new Want(Job.SMITH, "minecraft:coal_block", 3),
            new Want(Job.SCOUT, "minecraft:map", 2),
            new Want(Job.SCOUT, "minecraft:compass", 1),
            new Want(Job.SCOUT, "minecraft:spyglass", 1),
            new Want(Job.GLASSBLOWER, "minecraft:sand", 32),
            new Want(Job.GLASSBLOWER, "minecraft:red_sand", 32),
            new Want(Job.WEAVER, "#dyes", 8),
            new Want(Job.WEAVER, "minecraft:loom", 1),
            new Want(Job.SAWYER, "minecraft:iron_axe", 1),
            new Want(Job.SAWYER, "minecraft:stonecutter", 1),
            new Want(Job.JOINER, "minecraft:iron_axe", 1),
            new Want(Job.JOINER, "minecraft:crafting_table", 2),
            new Want(Job.LOCKSMITH, "minecraft:anvil", 1),
            new Want(Job.LOCKSMITH, "minecraft:grindstone", 1),
            new Want(Job.SMELTER, "minecraft:blast_furnace", 1),
            new Want(Job.SMELTER, "minecraft:lava_bucket", 1),
            new Want(Job.MERCHANT, "minecraft:chest", 4),
            new Want(Job.MERCHANT, "minecraft:lantern", 2),
            new Want(Job.MERCHANT, "minecraft:writable_book", 1));

    /** What a child asks for: a flower, a cookie, an apple. */
    static final List<String> TREATS = List.of("#flowers", "minecraft:cookie", "minecraft:apple", "minecraft:sweet_berries");

    /** The resources each trade minds most (asked for when the village is short of them). */
    static List<Res> minds(Job job) {
        return switch (job) {
            case FARMER, GATHERER, FISHER -> List.of(Res.FOOD, Res.WHEAT);
            case HERDER -> List.of(Res.WHEAT, Res.FOOD);
            case WOODCUTTER, SAWYER -> List.of(Res.WOOD, Res.PLANKS);
            case MINER -> List.of(Res.STONE, Res.COAL);
            case SMITH -> List.of(Res.IRON, Res.COAL, Res.STICKS);
            case JOINER -> List.of(Res.PLANKS, Res.STICKS, Res.WOOL, Res.LEATHER);
            case LOCKSMITH, SMELTER -> List.of(Res.IRON, Res.COAL);
            case WEAVER -> List.of(Res.WOOL);
            case GLASSBLOWER -> List.of(Res.COAL);
            case MERCHANT, SCOUT -> List.of(Res.FOOD);
        };
    }

    // ------------------------------------------------------------------ the day

    /** Tasks the village has out at once (offered and taken), by its size. */
    static int room(Village v) {
        return Math.max(2, Math.min(8, 1 + v.population() / 3));
    }

    /** The day's tasks: old offers and tasks run out, new ones are asked for. */
    static void day(VillageData data, Village v) {
        long today = data.day;
        Board b = v.tasks;
        b.boosts.values().removeIf(d -> d < today);
        for (Quest q : new ArrayList<>(b.quests)) {
            Dweller giver = v.dweller(q.giver);
            boolean gone = giver == null || q.until < today
                    || q.kind == Kind.SITE && (v.building(q.building) == null || v.building(q.building).missing(q.res()) <= 0 && !q.taken());
            if (!gone) continue;
            b.quests.remove(q);
            if (q.taken() && !q.complete()) v.log(today, Component.translatable("minecraftportsmod.vlog.quest_failed", q.takerName,
                    giver == null ? "?" : giver.name).withStyle(ChatFormatting.GRAY));
        }
        int tries = 0;
        while (b.quests.size() < room(v) && tries++ < 6) {
            Quest q = offer(data, v, today);
            if (q == null) continue;
            b.quests.add(q);
        }
    }

    /** One new task: someone without one asks for what the village (or their trade) needs most. */
    static Quest offer(VillageData data, Village v, long today) {
        List<Dweller> free = new ArrayList<>();
        for (Dweller d : v.dwellers) if (!d.away && !d.arriving && of(v, d.id) == null) free.add(d);
        if (free.isEmpty()) return null;
        Dweller d = free.get(VillageLife.RND.nextInt(free.size()));
        Board b = v.tasks;
        Quest q = null;
        int roll = VillageLife.RND.nextInt(100);
        if (d.elder) {
            // the head of the village minds the building sites, the village's safety and its neighbours
            if (roll < 50) q = site(v, d);
            if (q == null && roll < 75) q = letter(data, v, d);
            if (q == null && roll < 90) q = hunt(v, d);
        } else if (d.job == null) {
            // a child asks for a flower, a cookie...
            q = treat(d);
        } else if (d.job == Job.MERCHANT || d.job == Job.SCOUT) {
            if (roll < 50) q = letter(data, v, d);
        } else if (roll < 12) {
            q = hunt(v, d);
        }
        if (q == null && d.job != null && roll < 60) q = bring(v, d);
        if (q == null && d.job != null) q = item(v, d);
        if (q == null) return null;
        Quest out = new Quest(b.next++, q.giver, q.kind, q.what, q.count);
        out.building = q.building;
        out.until = today + OFFER_DAYS;
        out.reward = Math.min(price(v, out), Math.max(0, v.emeralds - 2));
        // (a purse too thin for it: the village pays in goods when it is done)
        if (out.reward <= 0) out.reward = price(v, out);
        return out;
    }

    /**
     * A task of a kind asked by a person now, whatever the village's day would have chosen (commands, tests). Returns
     * it, or null if there is nothing of the kind to ask for (no site waiting, no village unheard of...).
     */
    public static Quest ask(VillageData data, Village v, int dweller, Kind kind) {
        Dweller d = v.dweller(dweller);
        if (d == null) return null;
        Quest old = of(v, d.id);
        if (old != null) v.tasks.quests.remove(old);
        Quest q = switch (kind) {
            case BRING -> {
                Quest b = d.job == null ? null : bring(v, d);
                yield b != null ? b : new Quest(0, d.id, Kind.BRING, (d.job == null ? Res.FOOD : minds(d.job).getFirst()).id(), 16);
            }
            case ITEM -> d.job == null ? treat(d) : item(v, d);
            case SITE -> site(v, d);
            case HUNT -> hunt(v, d);
            case LETTER -> letter(data, v, d);
        };
        if (q == null) return null;
        Quest out = new Quest(v.tasks.next++, q.giver, q.kind, q.what, q.count);
        out.building = q.building;
        out.until = data.day + OFFER_DAYS;
        out.reward = price(v, out);
        v.tasks.quests.add(out);
        data.changed();
        return out;
    }

    /** A store the village is short of, of the ones this trade minds. */
    static Quest bring(Village v, Dweller d) {
        Res best = null;
        double most = 0.25;
        for (Res r : minds(d.job)) {
            if (r.optional() && VillageLife.target(v, r) <= 0) continue;
            double w = VillageLife.want(v, r);
            if (w > most) {
                most = w;
                best = r;
            }
        }
        if (best == null) return null;
        int short_ = VillageLife.target(v, best) - v.stock(best);
        int n = Math.max(8, Math.min(64, (short_ + 7) / 8 * 8));
        return new Quest(0, d.id, Kind.BRING, best.id(), n);
    }

    /** What a trade works faster with (if the village has the trade at work, and it is not working faster already). */
    static Quest item(Village v, Dweller d) {
        if (v.tasks.boosts.containsKey(d.job)) return null;
        List<Want> mine = new ArrayList<>();
        for (Want w : WANTS) if (w.job == d.job) mine.add(w);
        if (mine.isEmpty()) return null;
        Want w = mine.get(VillageLife.RND.nextInt(mine.size()));
        return new Quest(0, d.id, Kind.ITEM, w.item, w.count);
    }

    /** The first building site in the queue that still lacks something. */
    static Quest site(Village v, Dweller d) {
        for (Building b : VillageLife.queue(v)) {
            if (b.state != Building.State.PLANNED && !b.upgrading()) continue;
            boolean asked = false;
            for (Quest q : v.tasks.quests) asked |= q.kind == Kind.SITE && q.building == b.id;
            if (asked) continue;
            Res most = null;
            for (Res r : Res.values()) if (b.missing(r) > 0 && (most == null || b.missing(r) * Trade.base(r) > b.missing(most) * Trade.base(most))) most = r;
            if (most == null) continue;
            Quest q = new Quest(0, d.id, Kind.SITE, most.id(), Math.min(128, b.missing(most)));
            q.building = b.id;
            return q;
        }
        return null;
    }

    static Quest treat(Dweller d) {
        String t = TREATS.get(VillageLife.RND.nextInt(TREATS.size()));
        return new Quest(0, d.id, Kind.ITEM, t, t.equals("minecraft:sweet_berries") ? 8 : 1);
    }

    /** A child's wish (a flower, a cookie): it only makes the village a little happier. */
    static boolean treat(Quest q) {
        return TREATS.contains(q.what);
    }

    static Quest hunt(Village v, Dweller d) {
        return new Quest(0, d.id, Kind.HUNT, "", 4 + VillageLife.RND.nextInt(5));
    }

    /** A letter to a village this one does not know of (if there is one in the world). */
    static Quest letter(VillageData data, Village v, Dweller d) {
        for (Quest q : v.tasks.quests) if (q.kind == Kind.LETTER) return null;
        for (Village o : data.all()) if (o != v && !v.known.containsKey(o.id)) return new Quest(0, d.id, Kind.LETTER, "", 1);
        return null;
    }

    /** What a task is worth to the village, in emeralds. */
    static int price(Village v, Quest q) {
        double worth = switch (q.kind) {
            case BRING, SITE -> 1.5 * Trade.base(q.res()) * q.count;
            case ITEM -> treat(q) ? 0.5 : 2.5;
            case HUNT -> 0.4 * q.count;
            case LETTER -> 6;
        };
        return Math.max(1, (int) Math.round(worth));
    }

    // ------------------------------------------------------------------ the work of a trade

    /** How much faster a trade works today thanks to the players (1: as usual). */
    static double boost(Village v, Job job) {
        return job != null && v.tasks.boosts.containsKey(job) ? BOOST : 1.0;
    }

    // ------------------------------------------------------------------ reading

    /** The task a person has out (offered or taken), or null. */
    public static Quest of(Village v, int dweller) {
        for (Quest q : v.tasks.quests) if (q.giver == dweller) return q;
        return null;
    }

    /** The marker over a person's head: 0 none, 1 a task to take, 2 a task taken (by anyone). */
    public static int mark(Village v, Dweller d) {
        Quest q = of(v, d.id);
        return q == null ? 0 : q.taken() ? 2 : 1;
    }

    public static int taken(VillageData data, UUID player) {
        int n = 0;
        for (Village v : data.all()) for (Quest q : v.tasks.quests) if (player.equals(q.taker)) n++;
        return n;
    }

    /** Does an item answer what a task asks for. */
    public static boolean matches(Quest q, ItemStack s) {
        if (s.isEmpty()) return false;
        if (q.kind == Kind.BRING || q.kind == Kind.SITE) return q.res() != null && q.res().unitsOf(s) > 0;
        if (q.kind == Kind.LETTER) return letterOf(s) == q.id && reply(s);
        if (q.kind != Kind.ITEM) return false;
        return switch (q.what) {
            case "#flowers" -> s.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().is(BlockTags.SMALL_FLOWERS);
            case "#minecraft:saplings" -> s.is(ItemTags.SAPLINGS);
            case "#minecraft:boats" -> s.is(ItemTags.BOATS);
            case "#dyes" -> s.is(ItemTags.DYES);
            default -> s.is(item(q.what));
        };
    }

    /** The units an item stack is worth to a task. */
    static int unitsOf(Quest q, ItemStack s) {
        if (!matches(q, s)) return 0;
        return q.kind == Kind.BRING || q.kind == Kind.SITE ? q.res().unitsOf(s) : 1;
    }

    /** How much of what a task asks for the player carries (units). */
    public static int carried(ServerPlayer p, Quest q) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) n += unitsOf(q, inv.getItem(i)) * inv.getItem(i).getCount();
        return n;
    }

    public static Item item(String id) {
        Identifier rl = Identifier.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(rl);
    }

    /** An item to show for a task. */
    public static ItemStack icon(Quest q) {
        return switch (q.kind) {
            case BRING, SITE -> new ItemStack(q.res() == null ? Items.CHEST : q.res().icon);
            case HUNT -> new ItemStack(Items.IRON_SWORD);
            case LETTER -> new ItemStack(Items.PAPER);
            case ITEM -> switch (q.what) {
                case "#flowers" -> new ItemStack(Items.POPPY);
                case "#minecraft:saplings" -> new ItemStack(Items.OAK_SAPLING);
                case "#minecraft:boats" -> new ItemStack(Items.OAK_BOAT);
                case "#dyes" -> new ItemStack(Items.DYE.red());
                default -> new ItemStack(item(q.what));
            };
        };
    }

    // ------------------------------------------------------------------ the player's side

    public enum Result {OK, FULL, NOTHING, GONE}

    /** A player takes a task. */
    static Result take(ServerPlayer p, VillageData data, Village v, Quest q) {
        if (q.taken()) return Result.GONE;
        if (taken(data, p.getUUID()) >= MAX_TAKEN) return Result.FULL;
        q.taker = p.getUUID();
        q.takerName = p.getName().getString();
        q.until = data.day + switch (q.kind) {
            case BRING, ITEM -> 5;
            case SITE -> 4;
            case HUNT -> 2;
            case LETTER -> 20;
        };
        if (q.kind == Kind.LETTER) give(p, letter(v, q, false));
        data.changed();
        return Result.OK;
    }

    /** A player gives a task up. */
    static void drop(ServerPlayer p, VillageData data, Village v, Quest q) {
        if (!p.getUUID().equals(q.taker)) return;
        v.tasks.quests.remove(q);
        if (q.kind == Kind.LETTER) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) if (letterOf(inv.getItem(i)) == q.id && letterFrom(inv.getItem(i)) == v.id) inv.setItem(i, ItemStack.EMPTY);
        }
        data.changed();
    }

    /**
     * A player hands over what they carry of what a task asks for (all of it, up to what is still wanted); a task
     * finished is paid for. Returns how much was handed over (OK with nothing handed: a hunt or letter done, paid).
     */
    static Result handIn(ServerPlayer p, MinecraftServer srv, VillageData data, Village v, Quest q) {
        if (!p.getUUID().equals(q.taker)) return Result.GONE;
        if (q.kind == Kind.LETTER) {
            // the answer is given over
            if (!q.complete() || carried(p, q) == 0) return Result.NOTHING;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) if (matches(q, inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
        } else if (q.kind != Kind.HUNT) {
            int left = q.count - q.done;
            var inv = p.getInventory();
            int got = 0;
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                int per = unitsOf(q, s);
                if (per <= 0) continue;
                int take = Math.min(s.getCount(), (left + per - 1) / per);
                inv.removeItem(i, take);
                left -= take * per;
                got += take * per;
            }
            inv.setChanged();
            if (got == 0 && !q.complete()) return Result.NOTHING;
            got = Math.min(got, q.count - q.done);
            q.done += got;
            // what was brought goes where it was wanted
            Res r = q.res();
            if (q.kind == Kind.BRING && r != null) v.add(r, got);
            if (q.kind == Kind.SITE && r != null) {
                Building b = v.building(q.building);
                if (b != null && (b.state == Building.State.PLANNED || b.upgrading())) {
                    int site = Math.min(got, b.missing(r));
                    v.stock.merge(r, site, Integer::sum);
                    VillageLife.deliver(v, b, r, site);
                    v.built.merge(r, -site, Integer::sum);
                    if (got > site) v.add(r, got - site);
                } else {
                    v.add(r, got);
                }
            }
        }
        if (!q.complete()) {
            data.changed();
            return q.kind == Kind.HUNT ? Result.NOTHING : Result.OK;
        }
        finish(p, data, v, q);
        data.changed();
        return Result.OK;
    }

    /** A task done: the village changes by it, and the one who asked pays. */
    static void finish(ServerPlayer p, VillageData data, Village v, Quest q) {
        long today = data.day;
        Dweller giver = v.dweller(q.giver);
        v.tasks.quests.remove(q);
        if (q.kind == Kind.ITEM) {
            if (treat(q)) v.mood = Math.min(100, v.mood + 3);
            else if (giver != null && giver.job != null) {
                v.tasks.boosts.put(giver.job, today + BOOST_DAYS);
                // (sand: the glassworks melts it straight into glass)
                if (giver.job == Job.GLASSBLOWER) v.add(Res.GLASS, q.count / 2);
                // (hay: the herd's grain for days)
                if (q.what.equals("minecraft:hay_block")) v.add(Res.WHEAT, 9 * q.count);
            }
        }
        if (q.kind == Kind.HUNT) v.mood = Math.min(100, v.mood + 5);
        // the reward: emeralds from the purse, or goods it has to spare
        int pay = Math.min(q.reward, v.emeralds);
        if (pay > 0) {
            v.emeralds -= pay;
            give(p, new ItemStack(Items.EMERALD, pay));
        }
        int owed = q.reward - pay;
        if (owed > 0) goods(p, v, owed);
        v.tasks.thanks.merge(p.getUUID(), 1, Integer::sum);
        v.log(today, Component.translatable("minecraftportsmod.vlog.quest_done", p.getName(), giver == null ? "?" : giver.name)
                .withStyle(ChatFormatting.DARK_GREEN));
        Achievements.questDone(p, q, v.tasks.thanks(p.getUUID()));
        Plots.thank(p, v);
    }

    /** Emeralds' worth of what the village has most to spare. */
    static void goods(ServerPlayer p, Village v, int emeralds) {
        Res best = null;
        double most = -1e9;
        for (Res r : Res.values()) {
            if (r.toolLevel() > 0 || v.stock(r) <= 0) continue;
            double over = -VillageLife.want(v, r) * Trade.base(r);
            if (over > most) {
                most = over;
                best = r;
            }
        }
        if (best == null) return;
        int n = Math.min(v.stock(best), (int) Math.ceil(emeralds / Trade.base(best)));
        n = Math.min(n, 64 * 9);
        if (n <= 0) return;
        Trade.Ware w = Trade.WARES.get(best.ordinal());
        int units = Trade.units(w);
        int pieces = Math.max(1, n / units);
        v.add(best, -pieces * units);
        ItemStack one = Trade.piece(v, w);
        for (int left = pieces; left > 0; ) {
            int k = Math.min(left, one.getMaxStackSize());
            give(p, one.copyWithCount(k));
            left -= k;
        }
    }

    static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    // ------------------------------------------------------------------ hunts

    /** A monster was killed by a player: the hunts they took round there count it. */
    public static void killed(ServerPlayer p, BlockPos at) {
        VillageData data = VillageData.get(p.level().getServer());
        for (Village v : data.all()) {
            if (v.center.distSqr(at) > (double) HUNT_RANGE * HUNT_RANGE) continue;
            for (Quest q : v.tasks.quests) {
                if (q.kind != Kind.HUNT || !p.getUUID().equals(q.taker) || q.complete()) continue;
                q.done++;
                if (q.complete()) {
                    Dweller g = v.dweller(q.giver);
                    p.sendSystemMessage(Component.translatable("minecraftportsmod.quest.hunt_done", g == null ? "?" : g.name, v.name)
                            .withStyle(ChatFormatting.GREEN), true);
                }
                data.changed();
            }
        }
    }

    // ------------------------------------------------------------------ letters

    /** A letter (or its answer) of a village's task. */
    static ItemStack letter(Village from, Quest q, boolean reply) {
        ItemStack s = new ItemStack(Items.PAPER);
        CompoundTag t = new CompoundTag();
        t.putInt("mpm_letter", q.id);
        t.putInt("mpm_from", from.id);
        t.putInt("mpm_reply", reply ? 1 : 0);
        CustomData.set(DataComponents.CUSTOM_DATA, s, t);
        s.set(DataComponents.CUSTOM_NAME, Component.translatable(reply ? "minecraftportsmod.quest.reply_item" : "minecraftportsmod.quest.letter_item",
                from.name).withStyle(ChatFormatting.GOLD));
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    static CompoundTag tag(ItemStack s) {
        CustomData c = s.get(DataComponents.CUSTOM_DATA);
        return c == null ? null : c.copyTag();
    }

    /** The task a letter belongs to (-1: not a letter). */
    static int letterOf(ItemStack s) {
        CompoundTag t = tag(s);
        return t == null ? -1 : t.getIntOr("mpm_letter", -1);
    }

    static int letterFrom(ItemStack s) {
        CompoundTag t = tag(s);
        return t == null ? -1 : t.getIntOr("mpm_from", -1);
    }

    static boolean reply(ItemStack s) {
        CompoundTag t = tag(s);
        return t != null && t.getIntOr("mpm_reply", 0) == 1;
    }

    /**
     * A player talks to someone of a village with a letter in their bag from a village that does not know this one:
     * the letter is read, the two villages know of each other now, and an answer is written to carry back.
     * Returns true if a letter was delivered.
     */
    public static boolean deliver(ServerPlayer p, MinecraftServer srv, VillageData data, Village here) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            int id = letterOf(s);
            if (id < 0 || reply(s)) continue;
            Village from = data.get(letterFrom(s));
            if (from == null || from == here) continue;
            Quest q = null;
            for (Quest x : from.tasks.quests) if (x.id == id && x.kind == Kind.LETTER) q = x;
            if (q == null || !p.getUUID().equals(q.taker)) continue;
            if (from.known.containsKey(here.id)) continue;
            inv.setItem(i, letter(from, q, true));
            q.to = here.id;
            q.done = q.count;
            VillageManager.meet(srv, from, here);
            here.log(data.day, Component.translatable("minecraftportsmod.vlog.letter_came", p.getName(), from.name).withStyle(ChatFormatting.GOLD));
            from.log(data.day, Component.translatable("minecraftportsmod.vlog.letter_reached", p.getName(), here.name).withStyle(ChatFormatting.GOLD));
            p.sendSystemMessage(Component.translatable("minecraftportsmod.quest.letter_given", here.name, from.name).withStyle(ChatFormatting.GOLD), true);
            Achievements.letter(p);
            data.changed();
            return true;
        }
        return false;
    }
}
