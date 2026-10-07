package org.webtrade.minecraftportsmod.colony;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A player working in a village as one of its people: building by hand at a site (a bar shows how far along it is),
 * taking what they carry to the village's store, hiring on at a trade (with a day's work to bring, a little more than
 * one of the village's own people brings), living there (a home on their plot).
 */
public final class Helping {

    private Helping() {
    }

    // ------------------------------------------------------------------ building by hand

    /** Blocks of a building a player puts up with each stroke (a stroke every few ticks while the button is held). */
    static final int BLOCKS_A_STROKE = 1;

    private record Bar(ServerBossEvent bar, int[] idle, int village, int building) {
    }

    private static final Map<UUID, Bar> BARS = new HashMap<>();

    /** The building whose plot this is, being built or raised a level now (null: none). */
    static Building site(Village v, BlockPos pos) {
        for (Building b : v.buildings) {
            if (b.state != Building.State.BUILDING && !b.upgradeWork()) continue;
            int h = b.type.half + 1;
            if (Math.abs(pos.getX() - b.origin.getX()) <= h && Math.abs(pos.getZ() - b.origin.getZ()) <= h) return b;
            BlockPos post = b.blueprint(v.wood).post;
            if (Math.abs(pos.getX() - post.getX()) <= 1 && Math.abs(pos.getZ() - post.getZ()) <= 1) return b;
        }
        return null;
    }

    /**
     * A player, crouching, uses a block of a site being built: a stroke of work, as one of the builders. Returns
     * true if it was building work (the block's own use is then not done).
     */
    public static boolean build(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!p.isShiftKeyDown()) return false;
        VillageData data = VillageData.get(level.getServer());
        Village v = data.near(pos, 160);
        if (v == null) return false;
        Building b = site(v, pos);
        if (b == null) return false;
        int total = b.blueprint(v.wood).pieces.size();
        VillageManager.work(level, v, b, BLOCKS_A_STROKE);
        p.swing(InteractionHand.MAIN_HAND, true);
        BlockState st = level.getBlockState(pos);
        if (!st.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 4, 0.3, 0.1, 0.3, 0.02);
            level.playSound(null, pos, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        float done = Math.min(1F, b.work / (float) Math.max(1, total));
        if (b.upgradeWork()) {
            // (a level's additions: from what stood before to the whole)
            int from = b.blueprint(v.wood).upTo(b.level);
            done = Math.min(1F, (b.work - from) / (float) Math.max(1, total - from));
        }
        Bar bar = BARS.get(p.getUUID());
        if (bar == null || bar.village != v.id || bar.building != b.id) {
            if (bar != null) bar.bar.removeAllPlayers();
            ServerBossEvent e = new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);
            e.addPlayer(p);
            bar = new Bar(e, new int[]{0}, v.id, b.id);
            BARS.put(p.getUUID(), bar);
        }
        bar.idle[0] = 0;
        bar.bar.setName(Component.empty().append(b.type.displayName()).append(" " + Math.round(done * 100) + "%"));
        bar.bar.setProgress(done);
        if (b.state == Building.State.BUILT && !b.upgradeWork()) {
            bar.bar.setColor(BossEvent.BossBarColor.GREEN);
            bar.bar.setProgress(1F);
            bar.idle[0] = 60;
            v.tasks.helped.merge(p.getUUID(), 1, Integer::sum);
        }
        return true;
    }

    // ------------------------------------------------------------------ at a workshop's bench

    /** The tick each player last worked at a bench (the work counts the time between strokes, a stroke every few ticks). */
    private static final Map<UUID, Long> STROKE = new HashMap<>();

    /** The workshop standing here, on its plot or at its work spot (null: none). */
    static Building workshop(Village v, BlockPos pos) {
        for (Building b : v.buildings) {
            if (!Workshops.workshop(b.type) || !b.standing() || b.owner() != null) continue;
            int h = b.type.half + 1;
            if (Math.abs(pos.getX() - b.origin.getX()) <= h && Math.abs(pos.getZ() - b.origin.getZ()) <= h) return b;
            BlockPos spot = b.blueprint(v.wood).workSpot;
            if (Math.abs(pos.getX() - spot.getX()) <= 1 && Math.abs(pos.getZ() - spot.getZ()) <= 1) return b;
        }
        return null;
    }

    /**
     * A player, crouching, uses a block of a workshop that has an order in hand: they work at its bench as one of its
     * hands, for as long as they keep at it, with the tools in its slot. Returns true if it was bench work.
     */
    public static boolean bench(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!p.isShiftKeyDown()) return false;
        VillageData data = VillageData.get(level.getServer());
        Village v = data.near(pos, 160);
        if (v == null) return false;
        Building b = workshop(v, pos);
        if (b == null) return false;
        Orders.Order o = Workshops.current(v, b);
        // (nothing in hand: what the village wants of it taken up now, as its own hands would in the morning)
        if (o == null) {
            Workshops.plan(v, data.day);
            o = Workshops.current(v, b);
            if (o == null && Workshops.planAt(v, b, data.day)) o = Workshops.current(v, b);
        }
        Workshops.Recipe r = o == null ? null : Workshops.recipe(b.type, o.recipe);
        long now = level.getServer().getTickCount();
        if (r == null) {
            // (still nothing to make: the bench, not the block's own use; the bar says so)
            p.swing(InteractionHand.MAIN_HAND, true);
            Bar bar = bar(p, v, b, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
            bar.bar.setColor(BossEvent.BossBarColor.RED);
            bar.bar.setName(Component.translatable("minecraftportsmod.badge.idle", b.type.displayName()));
            bar.bar.setProgress(0F);
            return true;
        }
        Long last = STROKE.put(p.getUUID(), now);
        double seconds = last == null || now - last > 10 ? 0.2 : (now - last) / 20.0;
        boolean began = b.taken;
        int made = o.made;
        Workshops.work(v, b, 1, seconds, data.day);
        // (what one making takes not in the store: nothing to work on)
        boolean short_ = !began && !b.taken && o.made == made;
        // a making finished by a player hired at the trade: towards the day's work
        if (began && !b.taken && hired(v, p.getUUID()) == b.type.job) {
            v.tasks.brought.merge(p.getUUID(), 1, Integer::sum);
            int got = brought(v, p.getUUID()), need = quota(v, b.type.job);
            p.sendSystemMessage(Component.empty().append(b.type.job.displayName()).append("  " + Math.min(got, need) + " / " + need)
                    .withStyle(got >= need ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
        }
        p.swing(InteractionHand.MAIN_HAND, true);
        if (!short_ && now % 8 < 4) level.playSound(null, pos, net.minecraft.sounds.SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.BLOCKS, 0.4F, 1.1F);
        float done = (float) Workshops.progress(v, b)[1];
        Bar bar = bar(p, v, b, BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        Component what = r.out().displayName();
        bar.bar.setColor(short_ ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN);
        bar.bar.setName(short_ ? Component.translatable("minecraftportsmod.badge.short", what)
                : Component.empty().append(what).append(" " + o.made + "/" + o.count + " · " + Math.round(done * 100) + "%"));
        bar.bar.setProgress(short_ ? 0F : done);
        return true;
    }

    /** The player's bar for this building (a new one if it was for another), kept up. */
    private static Bar bar(ServerPlayer p, Village v, Building b, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        Bar bar = BARS.get(p.getUUID());
        if (bar == null || bar.village != v.id || bar.building != b.id) {
            if (bar != null) bar.bar.removeAllPlayers();
            ServerBossEvent e = new ServerBossEvent(UUID.randomUUID(), Component.empty(), color, overlay);
            e.addPlayer(p);
            bar = new Bar(e, new int[]{0}, v.id, b.id);
            BARS.put(p.getUUID(), bar);
        }
        bar.idle[0] = 0;
        return bar;
    }

    /** The bars of players who stopped building go away after a few seconds. */
    static void tick(MinecraftServer srv) {
        if (BARS.isEmpty() || srv.getTickCount() % 5 != 0) return;
        for (Iterator<Map.Entry<UUID, Bar>> it = BARS.entrySet().iterator(); it.hasNext(); ) {
            Bar b = it.next().getValue();
            b.idle[0] += 5;
            if (b.idle[0] > 80) {
                b.bar.removeAllPlayers();
                it.remove();
            }
        }
    }

    static void clear() {
        for (Bar b : BARS.values()) b.bar.removeAllPlayers();
        BARS.clear();
        STROKE.clear();
    }

    // ------------------------------------------------------------------ the store

    /** Units of a resource a player carries. */
    public static int carried(ServerPlayer p, Res r) {
        var inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EMERALD)) n += r.unitsOf(s) * s.getCount();
        }
        return n;
    }

    /** The building sites of a village that still lack something (first in its queue first). */
    public static java.util.List<Building> sites(Village v) {
        java.util.List<Building> out = new java.util.ArrayList<>();
        for (Building b : VillageLife.queue(v)) {
            if (b.state != Building.State.PLANNED && !b.upgrading()) continue;
            for (Res r : Res.values()) {
                if (b.missing(r) > 0) {
                    out.add(b);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * A player gives so many units of what they carry: to the store (as much as there is room for), or to a building
     * site (as much as it still lacks). Returns the units given.
     */
    public static int give(ServerPlayer p, VillageData data, Village v, Building site, Res r, int units) {
        int want = site == null ? v.free(r) : site.missing(r);
        want = Math.min(want, units);
        if (want <= 0) return 0;
        var inv = p.getInventory();
        int got = 0;
        for (int i = 0; i < inv.getContainerSize() && got < want; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.EMERALD)) continue;
            int per = r.unitsOf(s);
            if (per <= 0) continue;
            // (whole items only: one worth more than what is left of the want is not split)
            int take = Math.min(s.getCount(), (want - got) / per);
            if (take <= 0) continue;
            inv.removeItem(i, take);
            got += take * per;
        }
        inv.setChanged();
        if (got <= 0) return 0;
        if (site != null) {
            v.stock.merge(r, got, Integer::sum);
            VillageLife.deliver(v, site, r, got);
            v.built.merge(r, -got, Integer::sum);
        } else {
            v.add(r, got);
        }
        // (help: the worth of it, in tasks done)
        double worth = Trade.base(r) * got;
        int before = (int) v.tasks.given.getOrDefault(p.getUUID(), 0.0).doubleValue();
        v.tasks.given.merge(p.getUUID(), worth, Double::sum);
        int after = (int) v.tasks.given.get(p.getUUID()).doubleValue();
        // every 5 emeralds' worth brought counts as a task done for the village
        int thanks = after / 5 - before / 5;
        if (thanks > 0) {
            v.tasks.thanks.merge(p.getUUID(), thanks, Integer::sum);
            Plots.thank(p, v);
        }
        EnumMap<Res, Integer> one = new EnumMap<>(Res.class);
        one.put(r, got);
        if (site == null) v.log(data.day, Component.translatable("minecraftportsmod.vlog.deposit", p.getName(), VillageText.amounts(one)).withStyle(ChatFormatting.DARK_GREEN));
        else v.log(data.day, Component.translatable("minecraftportsmod.vlog.gave_site", p.getName(), VillageText.amounts(one), site.type.displayName())
                .withStyle(ChatFormatting.DARK_GREEN));
        data.changed();
        return got;
    }

    // ------------------------------------------------------------------ hired at a trade

    /** The trades a player can take up: those that bring in what the land gives (gathered), and the workshops' (made at the bench). */
    static final Job[] GATHER = {Job.WOODCUTTER, Job.MINER, Job.FISHER, Job.FARMER, Job.GATHERER, Job.HERDER};
    static final Job[] CRAFT = {Job.SAWYER, Job.JOINER, Job.SMITH, Job.LOCKSMITH, Job.WEAVER, Job.GLASSBLOWER};

    /** Makings at the bench a crafter brings in a day. */
    static final int CRAFT_QUOTA = 6;

    public static Job hired(Village v, UUID player) {
        return Job.byId(v.tasks.hired.getOrDefault(player, ""));
    }

    /** What a hired player must bring in a day: gathered, a fifth more than one of the village's own people (at least ten); made, so many makings. */
    public static int quota(Village v, Job job) {
        if (crafter(job)) return CRAFT_QUOTA;
        return Math.max(10, (int) Math.ceil(VillageLife.daily(v, job) * 1.2));
    }

    public static boolean crafter(Job j) {
        for (Job c : CRAFT) if (c == j) return true;
        return false;
    }

    public static int brought(Village v, UUID player) {
        return v.tasks.brought.getOrDefault(player, 0);
    }

    /** The workshop of a crafter's trade standing in the village (null: none). */
    static Building workshopOf(Village v, Job j) {
        for (Building b : v.buildings) if (b.type.job == j && b.standing() && Workshops.workshop(b.type) && b.owner() == null) return b;
        return null;
    }

    /** What a gathering trade brings in of a thing picked up (the village's resource it is), or null. */
    static Res gathered(Job j, ItemStack s) {
        if (s.isEmpty()) return null;
        return switch (j) {
            case WOODCUTTER -> s.is(net.minecraft.tags.ItemTags.LOGS) ? Res.WOOD : null;
            case MINER -> Res.STONE.unitsOf(s) > 0 ? Res.STONE : Res.COAL.unitsOf(s) > 0 ? Res.COAL
                    : s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE) ? Res.IRON : null;
            case FISHER -> s.is(net.minecraft.tags.ItemTags.FISHES) ? Res.FOOD : null;
            case FARMER -> s.is(Items.WHEAT) ? Res.WHEAT : s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT) ? Res.FOOD : null;
            case GATHERER -> s.is(Items.SWEET_BERRIES) || s.is(Items.GLOW_BERRIES) || s.is(Items.APPLE) || s.is(Items.BROWN_MUSHROOM)
                    || s.is(Items.RED_MUSHROOM) ? Res.FOOD : null;
            case HERDER -> s.is(net.minecraft.tags.ItemTags.WOOL) ? Res.WOOL : s.is(Items.LEATHER) ? Res.LEATHER
                    : s.is(Items.BEEF) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.PORKCHOP) || s.is(Items.EGG) ? Res.FOOD : null;
            default -> null;
        };
    }

    /** How much the village wants a trade's work now (0: not at all; above 1: badly); -1 if it cannot be taken up here. */
    public static double demand(Village v, Job j) {
        if (crafter(j)) {
            Building b = workshopOf(v, j);
            if (b == null) return -1;
            // what its queue has waiting, and what the village is short of that it makes
            int waiting = 0;
            for (Orders.Order o : v.orders) if (o.building == b.id && !o.done()) waiting += o.count - o.made;
            double w = 0;
            for (Workshops.Recipe r : Workshops.recipes(b.type)) if (Workshops.open(b, r)) w = Math.max(w, VillageLife.want(v, r.out()));
            return Math.max(0, w) + Math.min(1, waiting / 24.0);
        }
        double w = 0;
        switch (j) {
            case WOODCUTTER -> w = VillageLife.want(v, Res.WOOD);
            case MINER -> w = Math.max(VillageLife.want(v, Res.STONE), Math.max(VillageLife.want(v, Res.COAL), VillageLife.want(v, Res.IRON)));
            case FISHER, GATHERER -> w = VillageLife.want(v, Res.FOOD);
            case FARMER -> w = Math.max(VillageLife.want(v, Res.FOOD), VillageLife.want(v, Res.WHEAT));
            case HERDER -> {
                if (!v.has(BuildingType.FARMYARD)) return -1;
                w = Math.max(VillageLife.want(v, Res.WOOL), Math.max(VillageLife.want(v, Res.LEATHER), VillageLife.want(v, Res.FOOD)));
            }
            default -> {
                return -1;
            }
        }
        return Math.max(0, w);
    }

    /** The trades a player can take up here, the most wanted first. */
    public static java.util.List<Job> jobs(Village v) {
        java.util.List<Job> out = new java.util.ArrayList<>();
        for (Job j : GATHER) if (demand(v, j) >= 0) out.add(j);
        for (Job j : CRAFT) if (demand(v, j) >= 0) out.add(j);
        out.sort((a, b) -> Double.compare(demand(v, b), demand(v, a)));
        return out;
    }

    /** Hires the player at a trade (null: lets them go). One trade at a time, in one village: any other is left. Returns the trade. */
    static Job hire(ServerPlayer p, VillageData data, Village v, Job j) {
        for (Village o : data.all()) {
            if (o.tasks.hired.remove(p.getUUID()) != null) o.tasks.brought.remove(p.getUUID());
        }
        if (j != null) {
            v.tasks.hired.put(p.getUUID(), j.id());
            v.tasks.brought.put(p.getUUID(), 0);
            v.log(data.day, Component.translatable("minecraftportsmod.vlog.hired", p.getName(), j.displayName()));
        }
        data.changed();
        return j;
    }

    /**
     * A player hired at a gathering trade picks up something of it: while the day's work is not all brought, it goes
     * straight to the village's store (as much of it as the work still wants and the store has room for). Returns the
     * items that went.
     */
    public static int collect(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return 0;
        VillageData data = VillageData.get(p.level().getServer());
        for (Village v : data.all()) {
            Job j = hired(v, p.getUUID());
            if (j == null || crafter(j)) continue;
            Res r = gathered(j, s);
            if (r == null) return 0;
            int per = Math.max(1, r.unitsOf(s));
            int left = quota(v, j) - brought(v, p.getUUID());
            if (left <= 0) return 0;
            int n = Math.min(s.getCount(), Math.min((left + per - 1) / per, v.free(r) / per));
            if (n <= 0) return 0;
            v.add(r, n * per);
            v.tasks.brought.merge(p.getUUID(), n * per, Integer::sum);
            int now = brought(v, p.getUUID()), need = quota(v, j);
            p.sendSystemMessage(Component.empty().append(j.displayName()).append("  " + Math.min(now, need) + " / " + need)
                    .withStyle(now >= need ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
            data.changed();
            return n;
        }
        return 0;
    }

    /**
     * The end of a day for the players hired at the village: one who brought their day's work is paid a day's wage
     * (an emerald, if the purse has one) and thanked; the count starts again.
     */
    static void day(MinecraftServer srv, VillageData data, Village v) {
        for (var e : v.tasks.hired.entrySet()) {
            Job j = Job.byId(e.getValue());
            if (j == null) continue;
            int got = v.tasks.brought.getOrDefault(e.getKey(), 0), need = quota(v, j);
            ServerPlayer p = srv.getPlayerList().getPlayer(e.getKey());
            if (got >= need) {
                v.tasks.thanks.merge(e.getKey(), 1, Integer::sum);
                if (v.emeralds > 0 && p != null) {
                    v.emeralds--;
                    Quests.give(p, new ItemStack(Items.EMERALD));
                }
                if (p != null) {
                    p.sendSystemMessage(Component.translatable("minecraftportsmod.work.done", v.name, got, need).withStyle(ChatFormatting.GREEN));
                    Plots.thank(p, v);
                }
            } else if (p != null) {
                p.sendSystemMessage(Component.translatable("minecraftportsmod.work.short", v.name, got, need).withStyle(ChatFormatting.YELLOW));
            }
            v.tasks.brought.put(e.getKey(), 0);
        }
    }

    // ------------------------------------------------------------------ living there

    /** Players who live in the village: a home (a bed and a door) on their plot. */
    public static int residents(Village v) {
        int n = 0;
        for (Plots.Plot p : v.tasks.plots) if (p.home()) n++;
        return n;
    }
}
