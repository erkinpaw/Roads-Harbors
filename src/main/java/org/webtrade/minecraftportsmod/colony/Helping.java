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
    }

    // ------------------------------------------------------------------ the store

    /** What a player may leave in the store: everything the village keeps, but their tools (and food, unless that is their work). */
    static boolean depositable(Res r, Job hired) {
        if (r.toolLevel() > 0) return false;
        if (r == Res.FOOD) return hired != null && hired.makes == Res.FOOD;
        return true;
    }

    /**
     * A player takes what they carry of what the village keeps to its store (as much as there is room for). Their
     * day's work, if hired, counts what they bring of their trade's goods. Returns what was taken, by resource.
     */
    static EnumMap<Res, Integer> deposit(ServerPlayer p, VillageData data, Village v) {
        EnumMap<Res, Integer> got = new EnumMap<>(Res.class);
        Job hired = hired(v, p.getUUID());
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.is(Items.EMERALD)) continue;
            for (Res r : Res.values()) {
                int per = r.unitsOf(s);
                if (per <= 0 || !depositable(r, hired)) continue;
                int room = v.free(r);
                int n = Math.min(s.getCount(), room / per);
                if (n <= 0) break;
                inv.removeItem(i, n);
                v.add(r, n * per);
                got.merge(r, n * per, Integer::sum);
                break;
            }
        }
        inv.setChanged();
        if (got.isEmpty()) return got;
        // (help: the worth of it, in tasks done; a trade's goods towards the day's work)
        double worth = 0;
        for (var e : got.entrySet()) worth += Trade.base(e.getKey()) * e.getValue();
        int before = (int) v.tasks.given.getOrDefault(p.getUUID(), 0.0).doubleValue();
        v.tasks.given.merge(p.getUUID(), worth, Double::sum);
        int after = (int) v.tasks.given.get(p.getUUID()).doubleValue();
        // every 5 emeralds' worth brought counts as a task done for the village
        int thanks = after / 5 - before / 5;
        if (thanks > 0) {
            v.tasks.thanks.merge(p.getUUID(), thanks, Integer::sum);
            Plots.thank(p, v);
        }
        if (hired != null && hired.makes != null) v.tasks.brought.merge(p.getUUID(), got.getOrDefault(hired.makes, 0), Integer::sum);
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.deposit", p.getName(), VillageText.amounts(got)).withStyle(ChatFormatting.DARK_GREEN));
        data.changed();
        return got;
    }

    // ------------------------------------------------------------------ hired at a trade

    /** The trades a player can hire on at: those that bring in the village's plain goods. */
    static final Job[] TRADES = {Job.WOODCUTTER, Job.MINER, Job.FISHER};

    public static Job hired(Village v, UUID player) {
        return Job.byId(v.tasks.hired.getOrDefault(player, ""));
    }

    /** What a hired player must bring in a day: a fifth more than one of the village's own people at it, at least ten. */
    public static int quota(Village v, Job job) {
        return Math.max(10, (int) Math.ceil(VillageLife.daily(v, job) * 1.2));
    }

    public static int brought(Village v, UUID player) {
        return v.tasks.brought.getOrDefault(player, 0);
    }

    /** The trade the village wants hands at most now (of those a player can take up). */
    static Job wanted(Village v) {
        Job best = TRADES[0];
        double most = -1e9;
        for (Job j : TRADES) {
            double w = VillageLife.want(v, j.makes);
            if (w > most) {
                most = w;
                best = j;
            }
        }
        return best;
    }

    /** Hires the player at the trade the village wants most, or lets them go if hired already. Returns the trade, or null if let go. */
    static Job hire(ServerPlayer p, VillageData data, Village v) {
        if (hired(v, p.getUUID()) != null) {
            v.tasks.hired.remove(p.getUUID());
            v.tasks.brought.remove(p.getUUID());
            data.changed();
            return null;
        }
        Job j = wanted(v);
        v.tasks.hired.put(p.getUUID(), j.id());
        v.tasks.brought.put(p.getUUID(), 0);
        v.log(data.day, Component.translatable("minecraftportsmod.vlog.hired", p.getName(), j.displayName()));
        data.changed();
        return j;
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
