package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A day at one's trade, done for real in the world:
 * <ul>
 *   <li>the woodcutter fells a tree (the trunk from the top down, then the leaves), plants a sapling on the stump
 *   and carries the logs to the store;</li>
 *   <li>the miner breaks the bare stone around the village (never below the village's own ground) a block at a
 *   time and carries the stone to the store;</li>
 *   <li>the fisher casts, waits for a bite, pulls the fish out and brings the catch to the store;</li>
 *   <li>the farmer harvests ripe wheat and sows it again, tends the young crops, and brings the harvest in.</li>
 * </ul>
 * What they bring counts towards their day's work; once that is done, they go and help build, or rest.
 */
final class WorkGoal extends Goal {

    private enum Phase {FIND, GO, WORK, FELL, PLANT, GROVE, DELIVER, IDLE}

    /** Units carried before a trip to the store. */
    private static final int LOAD_STONE = 4, LOAD_FISH = 3, LOAD_WHEAT = 4;
    /** Food a fish or a sheaf of wheat is worth. */
    private static final int FISH_FOOD = 6, WHEAT_FOOD = 4;
    /** Give up on a target that can't be reached in this many ticks. */
    private static final int GIVE_UP = 300;

    private final ResidentEntity r;
    private final int[] repath = {0};
    private Phase phase = Phase.FIND;
    private BlockPos target, stand, water;
    private int ticks, timer, carried, progress;
    /** Blocks of a felled tree still to come down (logs first, top down, then leaves). */
    private final Deque<BlockPos> falling = new ArrayDeque<>();
    private BlockState stump;
    private final Set<BlockPos> bad = new HashSet<>();
    /** How long the way to the store has taken. */
    private int deliverTicks;
    /** Iron ore found on this trip (a miner's). */
    private int iron;
    /** What the field being worked is to be sown with. */
    private Crop sowing;
    /** The mine step being dug. */
    private Mine.Step step;
    /** The mines' plans (by miners' house), worked out once. */
    static final java.util.Map<Long, List<Mine.Step>> plans = new java.util.HashMap<>();
    /** The miner's block is part of the quarry pit (not bare rock). */
    private boolean quarry;
    /** The trade (and for a farmer, field or no field) the current phase belongs to: if it changes, start over. */
    private Job lastJob;
    private boolean lastField;

    WorkGoal(ResidentEntity r) {
        this.r = r;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Village village() {
        if (!r.colony() || !(r.level() instanceof ServerLevel level)) return null;
        return VillageData.get(level.getServer()).get(r.colonyVillage());
    }

    private Dweller dweller(Village v) {
        return v == null ? null : v.dweller(r.colonyDweller());
    }

    private static boolean workHours(ResidentEntity r) {
        return Routine.working(r);
    }

    @Override
    public boolean canUse() {
        Village v = village();
        Dweller d = dweller(v);
        if (d == null || d.job == null || d.job.makes == null || !workHours(r)) return false;
        if (carried > 0) return true;
        // trees standing on the village's own land are felled all the same (the land is cleared, not the wood wanted)
        if (d.job == Job.WOODCUTTER && clearing((ServerLevel) r.level(), v)) return true;
        // no point bringing what the store has no room for; and ahead of the day, a break
        return !ahead(v, d, r.dayTime()) && !VillageLife.plenty(v, d.job.makes);
    }

    /**
     * The day's work is spread over the whole working day: someone who has brought in more than the time of day
     * asks for takes a break (at home, or helping at a building site) and goes on later.
     */
    /** Is there a tree still standing on the village's own land? */
    static boolean clearing(ServerLevel level, Village v) {
        List<BlockPos> trees = DwellerGoals.trees(level, v);
        return !trees.isEmpty() && Tidy.territory(v, trees.getFirst().getX(), trees.getFirst().getZ());
    }

    static boolean ahead(Village v, Dweller d, int dayTime) {
        if (d.job == null || d.job.makes == null) return false;
        int quota = VillageLife.dailyOf(v, d);
        double part = Math.max(0, Math.min(1, (dayTime - 1000) / 10000.0 + 0.12));
        return d.earned >= quota * part;
    }

    @Override
    public boolean canContinueToUse() {
        // a tree half felled is finished first
        return canUse() || !falling.isEmpty();
    }

    @Override
    public void start() {
        phase = carried > 0 ? Phase.DELIVER : Phase.FIND;
        timer = 0;
    }

    @Override
    public void stop() {
        Village v = village();
        Dweller d = dweller(v);
        // whatever was carried reaches the store anyway (as what the trade it was carried in makes: a woodcutter
        // just made the stall's merchant still has his logs)
        if (v != null && d != null) {
            Job was = lastJob != null ? lastJob : d.job;
            if (carried > 0 && was != null && was.makes != null) VillageManager.deposit((ServerLevel) r.level(), v, d, was.makes, carried);
            if (iron > 0) VillageManager.depositIron((ServerLevel) r.level(), v, d, iron);
        }
        carried = 0;
        iron = 0;
        if (target != null) ((ServerLevel) r.level()).destroyBlockProgress(r.getId(), target, -1);
        falling.clear();
        r.hold(ItemStack.EMPTY);
    }

    private static Res resource(Job j) {
        return j.makes;
    }

    /** How much faster the trade's tools make the work. */
    private static double speed(Village v, Job j) {
        return Job.TOOL_SPEED[v.toolLevel(j)];
    }

    private void activity(String key, Object... args) {
        r.setActivity(net.minecraft.network.chat.Component.translatable("minecraftportsmod.act." + key, args));
    }

    private boolean walk(BlockPos to, double reach) {
        if (to == null) {
            // nowhere to go (the plan changed under us): look for work anew
            phase = Phase.FIND;
            return false;
        }
        double dx = r.getX() - (to.getX() + 0.5), dz = r.getZ() - (to.getZ() + 0.5);
        if (dx * dx + dz * dz <= reach * reach && Math.abs(r.getY() - to.getY()) < 4) {
            r.getNavigation().stop();
            return true;
        }
        r.walking = r.level().getGameTime();
        if (r.getNavigation().isDone() || --repath[0] <= 0) {
            repath[0] = 40;
            r.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, Routine.mode(r).speed);
        }
        return false;
    }

    private void lookAt(BlockPos p) {
        r.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    @Override
    public void tick() {
        try {
            work();
        } catch (RuntimeException e) {
            // a resident must never take the game down: log it, and start the work over
            org.webtrade.minecraftportsmod.Minecraftportsmod.LOGGER.error("Resident {} work failed in phase {}", r.getName().getString(), phase, e);
            phase = Phase.FIND;
            target = stand = water = null;
            falling.clear();
        }
    }

    private void work() {
        Village v = village();
        Dweller d = dweller(v);
        if (d == null || d.job == null) return;
        ServerLevel level = (ServerLevel) r.level();
        ticks++;
        boolean field = v.count(BuildingType.FIELD, true) > 0;
        if (d.job != lastJob || d.job == Job.FARMER && field != lastField) {
            // a new trade, or the field just got built: what was half done no longer applies
            if (lastJob != null && carried > 0) VillageManager.deposit(level, v, d, resource(lastJob), carried);
            if (target != null) level.destroyBlockProgress(r.getId(), target, -1);
            carried = 0;
            falling.clear();
            target = stand = water = null;
            phase = Phase.FIND;
            lastJob = d.job;
            lastField = field;
        }
        if (phase != Phase.DELIVER) deliverTicks = 0;
        if (phase == Phase.DELIVER) {
            deliver(level, v, d);
            return;
        }
        switch (d.job) {
            case WOODCUTTER -> woodcutter(level, v);
            case MINER -> miner(level, v);
            case FISHER -> fisher(level, v);
            case FARMER -> farmer(level, v);
            case GATHERER -> gatherer(level, v);
            case HERDER -> herder(level, v, d);
            case MERCHANT, SAWYER, SCOUT, SMITH, JOINER, LOCKSMITH, WEAVER, SMELTER, GLASSBLOWER, SAILOR -> {
            }
        }
    }

    // ------------------------------------------------------------------ to the store

    private void deliver(ServerLevel level, Village v, Dweller d) {
        Res res = resource(d.job);
        r.hold(new ItemStack(switch (d.job) {
            case WOODCUTTER -> Items.OAK_LOG;
            case MINER -> Items.COBBLESTONE;
            case FISHER -> Items.COD;
            case FARMER -> Items.WHEAT;
            case GATHERER -> Items.SWEET_BERRIES;
            case HERDER -> Items.EGG;
            case MERCHANT, SAWYER, SCOUT, SMITH, JOINER, LOCKSMITH, WEAVER, SMELTER, GLASSBLOWER, SAILOR -> Items.EMERALD;
        }));
        activity("delivering", res.displayName(), carried);
        // a store that can't be walked to (a cliff, water between): after a while the load goes by cart, as it were
        boolean far = ++deliverTicks > GIVE_UP;
        if (walk(v.storeSpot(), 2.5) || far) {
            VillageManager.deposit(level, v, d, res, carried);
            if (iron > 0) {
                VillageManager.depositIron(level, v, d, iron);
                iron = 0;
            }
            level.playSound(null, r.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5F, 0.8F);
            r.swing(InteractionHand.MAIN_HAND);
            carried = 0;
            phase = Phase.FIND;
            r.hold(ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ the woodcutter

    private void woodcutter(ServerLevel level, Village v) {
        r.hold(new ItemStack(Job.WOODCUTTER.tool(v.toolLevel(Job.WOODCUTTER))));
        switch (phase) {
            case FIND -> {
                target = null;
                // (wood not wanted just now: only the trees on the village's land)
                Dweller me = dweller(v);
                boolean onlyLand = v.full(Res.WOOD) || me != null && ahead(v, me, r.dayTime());
                BlockPos hut = DwellerGoals.woodHut(v);
                // the woodcutters fell by their hut, not all over the country; when the wood there is gone, farther out
                for (int pass = 0; pass < 2 && target == null; pass++)
                for (BlockPos base : DwellerGoals.trees(level, v)) {
                    if (bad.contains(base) || !level.getBlockState(base).is(BlockTags.LOGS)) continue;
                    boolean land = Tidy.territory(v, base.getX(), base.getZ());
                    if (onlyLand && !land) continue;
                    if (pass == 0 && !land && hut != null && base.distSqr(hut) > (DwellerGoals.GROVE + 8) * (DwellerGoals.GROVE + 8)) continue;
                    if (r.getRandom().nextInt(3) == 0) continue;   // not everyone at the same tree
                    // (none across the water or up a cliff: only what can be walked to)
                    if (!Reach.ok(level, v, base)) {
                        bad.add(base);
                        continue;
                    }
                    // a real tree, with a crown of its own (not the logs of a bench, a field's edge, a house)
                    if (tree(level, base, v) == null) {
                        bad.add(base);
                        continue;
                    }
                    target = base;
                    break;
                }
                if (target == null) {
                    // nothing to fell: saplings for the grove, or a wait by the hut
                    BlockPos spot = hut == null ? null : groveSpot(level, v, hut);
                    if (spot != null) {
                        stand = spot;
                        phase = Phase.GROVE;
                        timer = 0;
                        return;
                    }
                    activity("no_trees");
                    if (hut != null) waitBy(level, hut);
                    else wander(level, v);
                    return;
                }
                phase = Phase.GO;
                timer = 0;
            }
            case GROVE -> {
                activity("planting");
                if (walk(stand, 2.0)) {
                    lookAt(stand);
                    if (++timer >= 20) {
                        r.swing(InteractionHand.MAIN_HAND);
                        BlockState sapling = groveSapling(v);
                        if (level.getBlockState(stand).canBeReplaced() && level.getBlockState(stand).getFluidState().isEmpty()
                                && sapling.canSurvive(level, stand)) {
                            level.setBlock(stand, sapling, Block.UPDATE_ALL);
                            level.playSound(null, stand, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.7F, 1.2F);
                            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, stand.getX() + 0.5, stand.getY() + 0.5, stand.getZ() + 0.5,
                                    6, 0.3, 0.3, 0.3, 0);
                        }
                        phase = Phase.FIND;
                    }
                } else if (++timer > GIVE_UP / 2) {
                    phase = Phase.FIND;
                }
            }
            case GO -> {
                activity("to_tree");
                if (walk(target, 2.3)) {
                    phase = Phase.WORK;
                    progress = 0;
                } else if (++timer > GIVE_UP / 2) {
                    double dx = r.getX() - (target.getX() + 0.5), dz = r.getZ() - (target.getZ() + 0.5);
                    if (dx * dx + dz * dz <= 36 && Math.abs(r.getY() - target.getY()) < 6) {
                        r.getNavigation().stop();
                        phase = Phase.WORK;
                        progress = 0;
                    } else {
                        bad.add(target);
                        phase = Phase.FIND;
                    }
                }
            }
            case WORK -> {
                if (!level.getBlockState(target).is(BlockTags.LOGS)) {
                    phase = Phase.FIND;
                    return;
                }
                activity("chopping");
                lookAt(target);
                if (ticks % 8 == 0) {
                    r.swing(InteractionHand.MAIN_HAND);
                    BlockState st = level.getBlockState(target);
                    level.playSound(null, target, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.7F, 0.8F);
                }
                progress++;
                int chop = (int) (80 / speed(v, Job.WOODCUTTER));
                level.destroyBlockProgress(r.getId(), target, Math.min(9, progress * 10 / chop));
                if (progress >= chop) {
                    level.destroyBlockProgress(r.getId(), target, -1);
                    List<BlockPos> tree = tree(level, target, v);
                    if (tree == null) {
                        bad.add(target);
                        phase = Phase.FIND;
                        return;
                    }
                    stump = level.getBlockState(target);
                    falling.clear();
                    falling.addAll(tree);
                    phase = Phase.FELL;
                }
            }
            case FELL -> {
                activity("felling");
                lookAt(target);
                // the trunk comes down from the top, two blocks a tick, then the crown of leaves
                for (int i = 0; i < 3 && !falling.isEmpty(); i++) {
                    BlockPos p = falling.pollFirst();
                    BlockState st = level.getBlockState(p);
                    if (st.is(BlockTags.LOGS)) {
                        level.destroyBlock(p, false, r, 512);
                        carried++;
                    } else if (st.is(BlockTags.LEAVES) || hangs(st)) {
                        if (r.getRandom().nextInt(4) == 0) level.destroyBlock(p, false, r, 512);
                        else level.removeBlock(p, false);
                        i--;   // leaves go quicker
                        if (falling.size() % 6 == 0) break;
                    }
                }
                if (falling.isEmpty()) {
                    level.playSound(null, target, SoundEvents.GENERIC_BIG_FALL, SoundSource.BLOCKS, 0.8F, 0.7F);
                    phase = Phase.PLANT;
                    timer = 0;
                }
            }
            case PLANT -> {
                activity("planting");
                lookAt(target);
                if (++timer == 20) {
                    r.swing(InteractionHand.MAIN_HAND);
                    BlockState sapling = sapling(stump);
                    // (on the village's own land the tree is cleared away, not replanted)
                    if (!Tidy.territory(v, target.getX(), target.getZ()) && level.getBlockState(target).isAir() && sapling.canSurvive(level, target)) {
                        level.setBlock(target, sapling, Block.UPDATE_ALL);
                        level.playSound(null, target, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.7F, 1.2F);
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                                6, 0.3, 0.3, 0.3, 0);
                    }
                }
                if (timer >= 40) {
                    DwellerGoals.forgetTrees(v);
                    phase = carried > 0 ? Phase.DELIVER : Phase.FIND;
                }
            }
            default -> phase = Phase.FIND;
        }
    }

    /**
     * The logs of a natural tree standing on {@code base} (top ones first), then its leaves; null if it is not a
     * tree but, say, the post of a house (no leaves of its own) or a giant too big to fell (a jungle giant is not).
     */
    static List<BlockPos> tree(ServerLevel level, BlockPos base, Village v) {
        // (a building's own timber is no tree: the logs round a field's beds, a house's posts)
        if (Tidy.built(level, base)) return null;
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(base);
        seen.add(base);
        boolean leafy = false;
        while (!open.isEmpty()) {
            BlockPos p = open.poll();
            logs.add(p);
            if (logs.size() > 400) return null;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (Math.abs(n.getX() - base.getX()) > 7 || Math.abs(n.getZ() - base.getZ()) > 7 || !seen.add(n)) continue;
                        // down only beside the foot (the rest of a thick trunk on lower ground): no other tree's crown
                        if (n.getY() < base.getY() && (n.getY() < base.getY() - 3 || Math.abs(n.getX() - base.getX()) > 1 || Math.abs(n.getZ() - base.getZ()) > 1)) continue;
                        BlockState s = level.getBlockState(n);
                        // the posts of a house the tree leans on are not part of it
                        if (s.is(BlockTags.LOGS) && (v == null || !DwellerGoals.inside(v, n.getX(), n.getZ())) && !Tidy.built(level, n)) open.add(n);
                        else if (natural(s)) leafy = true;
                    }
                }
            }
        }
        if (!leafy) return null;
        // the leaves of the crown: natural leaves near the logs
        List<BlockPos> leaves = new ArrayList<>();
        Set<BlockPos> seenLeaves = new HashSet<>();
        Deque<BlockPos> front = new ArrayDeque<>();
        for (BlockPos l : logs) {
            for (Direction dir : Direction.values()) {
                BlockPos n = l.relative(dir);
                if (natural(level.getBlockState(n)) && seenLeaves.add(n)) front.add(n);
            }
        }
        while (!front.isEmpty() && leaves.size() < 1600) {
            BlockPos p = front.poll();
            leaves.add(p);
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (!seenLeaves.contains(n) && natural(level.getBlockState(n)) && near(n, logs, 3)) {
                    seenLeaves.add(n);
                    front.add(n);
                }
            }
        }
        logs.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
        leaves.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
        List<BlockPos> out = new ArrayList<>(logs);
        out.addAll(leaves);
        out.addAll(hanging(level, out));
        return out;
    }

    /** What hangs on a tree and would be left in the air without it: vines (down to their ends), cocoa pods, a bees' nest. */
    static List<BlockPos> hanging(ServerLevel level, List<BlockPos> tree) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(tree);
        Deque<BlockPos> open = new ArrayDeque<>();
        for (BlockPos t : tree) {
            for (Direction dir : Direction.values()) {
                BlockPos n = t.relative(dir);
                if (seen.add(n) && hangs(level.getBlockState(n))) open.add(n);
                // (the snow lying on the crown comes down with it)
                else if (dir == Direction.UP && level.getBlockState(n).is(Blocks.SNOW) && seen.add(n)) out.add(n);
            }
        }
        while (!open.isEmpty() && out.size() < 600) {
            BlockPos p = open.poll();
            out.add(p);
            // a vine hangs on down; and to the side along the leaves it covers
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) continue;
                BlockPos n = p.relative(dir);
                if (seen.add(n) && level.getBlockState(n).is(Blocks.VINE)) open.add(n);
            }
        }
        out.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
        return out;
    }

    /** A block of a huge mushroom (its stem, its cap): felled like a tree, nothing of it kept. */
    static boolean fungus(BlockState s) {
        return s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK);
    }

    /** The whole of a huge mushroom one of whose blocks is at {@code p}: stem and cap. */
    static List<BlockPos> mushroom(ServerLevel level, BlockPos p) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(List.of(p));
        Deque<BlockPos> open = new ArrayDeque<>(List.of(p));
        while (!open.isEmpty() && out.size() < 300) {
            BlockPos q = open.poll();
            if (!fungus(level.getBlockState(q))) continue;
            out.add(q);
            for (BlockPos n : BlockPos.betweenClosed(q.offset(-1, -1, -1), q.offset(1, 1, 1))) {
                BlockPos m = n.immutable();
                if (Math.abs(m.getX() - p.getX()) <= 5 && Math.abs(m.getZ() - p.getZ()) <= 5 && seen.add(m)) open.add(m);
            }
        }
        out.sort(Comparator.comparingInt((BlockPos q) -> -q.getY()));
        return out;
    }

    /** Snow lying on what is gone (a crown felled from under it): it can't stay up in the air. */
    static boolean loftySnow(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).is(Blocks.SNOW) && level.getBlockState(p.below()).isAir();
    }

    static boolean hangs(BlockState s) {
        return s.is(Blocks.VINE) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST) || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.MOSS_CARPET)
                || s.is(Blocks.PALE_HANGING_MOSS);
    }

    private static boolean natural(BlockState s) {
        return s.is(BlockTags.LEAVES) && (!s.hasProperty(BlockStateProperties.PERSISTENT) || !s.getValue(BlockStateProperties.PERSISTENT));
    }

    private static boolean near(BlockPos p, List<BlockPos> logs, int d) {
        for (BlockPos l : logs) if (Math.abs(p.getX() - l.getX()) <= d && Math.abs(p.getY() - l.getY()) <= d && Math.abs(p.getZ() - l.getZ()) <= d) return true;
        return false;
    }

    /** The sapling that grows the tree this log came from. */
    static BlockState sapling(BlockState log) {
        String id = log == null ? "oak_log" : BuiltInRegistries.BLOCK.getKey(log.getBlock()).getPath();
        String kind = id.replace("stripped_", "").replace("_log", "").replace("_wood", "");
        String sapling = kind.equals("mangrove") ? "mangrove_propagule" : kind + "_sapling";
        var b = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(sapling));
        return (b.isPresent() ? b.get() : Blocks.OAK_SAPLING).defaultBlockState();
    }

    // ------------------------------------------------------------------ the miner

    private void miner(ServerLevel level, Village v) {
        r.hold(new ItemStack(Job.MINER.tool(v.toolLevel(Job.MINER))));
        Building house = Mine.house(v);
        if (house != null) {
            mine(level, v, house);
            return;
        }
        // no house: chipping at the bare rock about (it stays: nobody wants the village's land full of holes)
        switch (phase) {
            case FIND -> {
                target = null;
                for (BlockPos p : DwellerGoals.stone(level, v)) {
                    if (bad.contains(p) || !DwellerGoals.mineable(level, v, p)) continue;
                    if (r.getRandom().nextInt(3) == 0) continue;
                    if (!Reach.ok(level, v, p.above())) {
                        bad.add(p);
                        continue;
                    }
                    target = p;
                    break;
                }
                if (target == null) {
                    activity("no_stone");
                    wander(level, v);
                    return;
                }
                stand = target.above();
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                activity("to_quarry");
                if (walk(stand, 2.6)) {
                    phase = Phase.WORK;
                    progress = 0;
                } else if (++timer > GIVE_UP) {
                    bad.add(target);
                    phase = Phase.FIND;
                }
            }
            case WORK -> {
                if (level.getBlockState(target).isAir()) {
                    phase = Phase.FIND;
                    return;
                }
                activity("mining");
                if (chip(level, v, target, 60)) phase = carried >= LOAD_STONE ? Phase.DELIVER : Phase.FIND;
            }
            default -> phase = Phase.FIND;
        }
    }

    /**
     * A miner at a block of rock: the pick goes at it, it cracks, the stone of it (and now and then ore) comes
     * away, but the block stays where it is. True once a load's worth is done.
     */
    private boolean chip(ServerLevel level, Village v, BlockPos at, int base) {
        lookAt(at);
        BlockState st = level.getBlockState(at);
        if (ticks % 7 == 0) {
            r.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, at, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.6F, 0.8F);
        }
        progress++;
        int breakTime = (int) (base / speed(v, Job.MINER));
        level.destroyBlockProgress(r.getId(), at, Math.min(9, progress * 10 / breakTime));
        if (progress < breakTime) return false;
        level.destroyBlockProgress(r.getId(), at, -1);
        level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, st), at.getX() + 0.5, at.getY() + 0.5,
                at.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, at, st.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.6F, 0.9F);
        carried++;
        findOre(level, v, at, st);
        // coal shows now and then (it comes in with the day's work)
        if (r.getRandom().nextFloat() < VillageLife.coalChance(v)) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.COAL))),
                    at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.1);
        }
        progress = 0;
        return true;
    }

    /** Every block of rock broken may hold iron ore (a real ore block always does). */
    private void findOre(ServerLevel level, Village v, BlockPos at, BlockState st) {
        boolean real = st.is(Blocks.IRON_ORE) || st.is(Blocks.DEEPSLATE_IRON_ORE);
        if (!real && r.getRandom().nextFloat() >= VillageLife.oreChance(v)) return;
        iron++;
        // raw iron flies out of the broken rock
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.RAW_IRON))),
                at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.1);
        level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6F, 1.4F);
    }

    /**
     * The village mine: first dug out (a short stair, a tunnel, a chamber, torches), the stone of it to the store;
     * then worked: at a wall of it, chipping away.
     */
    private void mine(ServerLevel level, Village v, Building house) {
        // (a plan for each level of the house: the pit is widened and deepened; what is dug already is skipped)
        List<Mine.Step> plan = plans.computeIfAbsent(v.id * 1000000L + house.id * 10L + house.level, k -> {
            VillageManager.mineReset(v);
            return Mine.plan(level, v, house);
        });
        switch (phase) {
            case FIND -> {
                // skip what is already open (caves, what others dug)
                int i = VillageManager.mineStepOf(v);
                while (i < plan.size() && !Mine.diggable(level, plan.get(i).dig())) {
                    VillageManager.mineStep(level, v);
                    i++;
                }
                if (i >= 3) Mine.entrance(level, v, house);
                if (i >= plan.size()) {
                    // dug out: to a wall of it
                    List<BlockPos[]> faces = Mine.faces(level, plan);
                    if (faces.isEmpty()) {
                        activity("mine_done");
                        wander(level, v);
                        return;
                    }
                    BlockPos[] f = faces.get(Math.floorMod(r.colonyDweller() * 7 + r.getRandom().nextInt(3), faces.size()));
                    stand = f[0];
                    target = f[1];
                    step = null;
                    phase = Phase.GO;
                    timer = 0;
                    return;
                }
                // (each miner at his own block of the layer, not all at one)
                int slot = 0;
                for (Dweller d : v.dwellers) {
                    if (d.id == r.colonyDweller()) break;
                    if (d.job == Job.MINER) slot++;
                }
                int j = Math.min(plan.size() - 1, i + slot * 2);
                step = Mine.diggable(level, plan.get(j).dig()) ? plan.get(j) : plan.get(i);
                target = step.dig();
                stand = step.stand();
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                activity("to_mine");
                if (walk(stand, 1.6)) {
                    phase = Phase.WORK;
                    progress = 0;
                } else if (++timer > GIVE_UP / 2) {
                    // down a narrow stair the path-finding gives up: climb down by hand
                    r.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                    phase = Phase.WORK;
                    progress = 0;
                }
            }
            case WORK -> {
                if (target == null) {
                    phase = Phase.FIND;
                    return;
                }
                if (step == null) {
                    // working the finished mine
                    activity("working_mine");
                    if (level.getBlockState(target).isAir()) {
                        phase = Phase.FIND;
                        return;
                    }
                    if (chip(level, v, target, 50)) phase = carried >= LOAD_STONE + 2 ? Phase.DELIVER : Phase.FIND;
                    return;
                }
                // (dug already, by another: the next; how far the pit is dug is counted by what is open, see FIND)
                if (!Mine.diggable(level, target)) {
                    phase = Phase.FIND;
                    return;
                }
                activity("mining_deep");
                lookAt(target);
                BlockState st = level.getBlockState(target);
                if (ticks % 7 == 0) {
                    r.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, target, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.6F, 0.8F);
                }
                progress++;
                int breakTime = (int) (50 / speed(v, Job.MINER));
                level.destroyBlockProgress(r.getId(), target, Math.min(9, progress * 10 / breakTime));
                if (progress < breakTime) return;
                level.destroyBlockProgress(r.getId(), target, -1);
                Mine.seal(level, target);
                level.destroyBlock(target, false, r, 512);
                if (DwellerGoals.rock(st) || st.is(Blocks.IRON_ORE) || st.is(Blocks.DEEPSLATE_IRON_ORE)) {
                    carried++;
                    findOre(level, v, target, st);
                }
                if (step.torchWall() != null) Mine.torch(level, target, step.torchWall());
                phase = carried >= LOAD_STONE + 2 ? Phase.DELIVER : Phase.FIND;
            }
            default -> phase = Phase.FIND;
        }
    }

    // ------------------------------------------------------------------ the fisher

    /** Food a gatherer finds at one place, and how many places' worth he carries home. */
    private static final int GATHER_FOOD = 3, LOAD_GATHER = 4;
    private static final Item[] FINDS = {Items.SWEET_BERRIES, Items.BROWN_MUSHROOM, Items.RED_MUSHROOM, Items.WHEAT_SEEDS, Items.APPLE};

    /** The gatherer: out to the growing land round the village, stooping for what grows wild, a basket home. */
    private void gatherer(ServerLevel level, Village v) {
        switch (phase) {
            case FIND -> {
                BlockPos spot = DwellerGoals.dryLand(level, v, r.getRandom(), 10 + r.getRandom().nextInt(22));
                if (spot == null || bad.contains(spot)) {
                    activity("gathering");
                    wander(level, v);
                    return;
                }
                stand = spot;
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                r.hold(ItemStack.EMPTY);
                activity("to_gather");
                if (walk(stand, 2.0)) {
                    phase = Phase.WORK;
                    timer = 0;
                    progress = 80 + r.getRandom().nextInt(120);
                } else if (++timer > GIVE_UP) {
                    bad.add(stand);
                    phase = Phase.FIND;
                }
            }
            case WORK -> {
                lookAt(stand.below());
                timer++;
                activity("gathering");
                if (timer % 16 == 0) {
                    r.swing(InteractionHand.MAIN_HAND);
                    ItemStack find = new ItemStack(FINDS[r.getRandom().nextInt(FINDS.length)]);
                    r.hold(find);
                    level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(find)),
                            stand.getX() + 0.5, stand.getY() + 0.2, stand.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.05);
                }
                if (timer >= progress) {
                    carried += (int) Math.round(GATHER_FOOD * speed(v, Job.GATHERER));
                    phase = carried >= LOAD_GATHER * GATHER_FOOD ? Phase.DELIVER : Phase.FIND;
                }
            }
            default -> phase = Phase.FIND;
        }
    }

    private void fisher(ServerLevel level, Village v) {
        r.hold(new ItemStack(Items.FISHING_ROD));
        switch (phase) {
            case FIND -> {
                BlockPos[] spot = DwellerGoals.fishingSpot(level, v, r.colonyDweller() + bad.size());
                if (spot == null) {
                    activity("no_water");
                    wander(level, v);
                    return;
                }
                stand = spot[0];
                water = spot[1];
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                activity("to_shore");
                if (walk(stand, 1.8)) {
                    phase = Phase.WORK;
                    timer = 0;
                    progress = 0;
                } else if (++timer > GIVE_UP) {
                    bad.add(stand);
                    phase = Phase.FIND;
                }
            }
            case WORK -> {
                lookAt(water.below());
                double wx = water.getX() + 0.5, wy = water.getY() + 0.1, wz = water.getZ() + 0.5;
                if (timer == 0) {
                    // cast
                    r.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, r.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F);
                    level.sendParticles(ParticleTypes.SPLASH, wx, wy, wz, 8, 0.1, 0, 0.1, 0.1);
                    progress = 120 + r.getRandom().nextInt(260);
                }
                timer++;
                activity("fishing");
                // the line and the float, drawn with dust: the float bobs, and dips when a fish bites
                if (timer % 3 == 0) line(level, wx, wy, wz, timer > progress - 40);
                if (timer % 20 == 0) level.sendParticles(ParticleTypes.BUBBLE_POP, wx, wy, wz, 2, 0.05, 0, 0.05, 0);
                if (timer > progress - 40 && timer % 4 == 0) {
                    // something is nibbling: a trail of bubbles coming to the float
                    level.sendParticles(ParticleTypes.FISHING, wx + (progress - timer) / 20.0, wy, wz, 1, 0, 0, 0, 0);
                }
                if (timer >= progress) {
                    r.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6F, 1.0F);
                    level.sendParticles(ParticleTypes.SPLASH, wx, wy, wz, 20, 0.2, 0.1, 0.2, 0.2);
                    ItemStack fish = new ItemStack(r.getRandom().nextInt(4) == 0 ? Items.SALMON : Items.COD);
                    level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(fish)),
                            wx, wy + 0.3, wz, 6, 0.1, 0.3, 0.1, 0.15);
                    level.playSound(null, r.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 0.5F, 1.0F);
                    carried += FISH_FOOD;
                    timer = 0;
                    if (carried >= LOAD_FISH * FISH_FOOD) phase = Phase.DELIVER;
                }
            }
            default -> phase = Phase.FIND;
        }
    }

    private static final net.minecraft.core.particles.DustParticleOptions LINE = new net.minecraft.core.particles.DustParticleOptions(0xF0F0F0, 0.45F);
    private static final net.minecraft.core.particles.DustParticleOptions FLOAT_RED = new net.minecraft.core.particles.DustParticleOptions(0xE02828, 1.0F);
    private static final net.minecraft.core.particles.DustParticleOptions FLOAT_WHITE = new net.minecraft.core.particles.DustParticleOptions(0xFFFFFF, 0.8F);

    /** A fishing line from the rod's tip to the float, which bobs (or dips, when something bites). */
    private void line(ServerLevel level, double wx, double wy, double wz, boolean biting) {
        double bob = biting ? -0.12 - 0.08 * Math.sin(timer * 1.3) : 0.04 * Math.sin(timer * 0.25);
        double fy = wy + 0.05 + bob;
        // the tip of the rod: in front of the face, a little to the right
        double yaw = Math.toRadians(r.getYRot());
        double tx = r.getX() - Math.sin(yaw) * 0.9 - Math.cos(yaw) * 0.3;
        double tz = r.getZ() + Math.cos(yaw) * 0.9 - Math.sin(yaw) * 0.3;
        double ty = r.getY() + 1.9;
        int n = 20;
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            // the line sags a little
            double sag = Math.sin(t * Math.PI) * 0.35;
            level.sendParticles(LINE, tx + (wx - tx) * t, ty + (fy + 0.1 - ty) * t - sag, tz + (wz - tz) * t, 1, 0, 0, 0, 0);
        }
        level.sendParticles(FLOAT_RED, wx, fy, wz, 1, 0, 0, 0, 0);
        level.sendParticles(FLOAT_WHITE, wx, fy + 0.12, wz, 1, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ the farmer

    private void farmer(ServerLevel level, Village v) {
        r.hold(new ItemStack(Job.FARMER.tool(v.toolLevel(Job.FARMER))));
        // the fields in turn: each farmer starts at a different one
        List<Building> fields = new ArrayList<>();
        for (Building b : v.buildings) if (b.type == BuildingType.FIELD && b.standing()) fields.add(b);
        Building field = fields.isEmpty() ? null : fields.get(Math.floorMod(r.colonyDweller() + ticks / 2400, fields.size()));
        if (field == null) {
            forage(level, v);
            return;
        }
        switch (phase) {
            case FIND -> {
                BlockPos ripe = null, young = null;
                sowing = field.crop();
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        BlockPos p = field.origin.offset(x, 0, z);
                        BlockState s = level.getBlockState(p);
                        if (!(s.getBlock() instanceof CropBlock crop)) continue;
                        if (crop.isMaxAge(s)) {
                            if (ripe == null || r.getRandom().nextInt(4) == 0) ripe = p;
                        } else if (young == null || r.getRandom().nextInt(6) == 0) {
                            young = p;
                        }
                    }
                }
                target = ripe != null ? ripe : young;
                if (target == null) {
                    activity("farming");
                    return;
                }
                progress = ripe != null ? 1 : 0;   // 1: harvest, 0: tend
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                activity(progress == 1 ? "harvesting" : "tending");
                if (walk(target, 1.8)) {
                    phase = Phase.WORK;
                    timer = 0;
                } else if (++timer > GIVE_UP) {
                    phase = Phase.FIND;
                }
            }
            case WORK -> {
                lookAt(target);
                if (++timer % 10 == 0) r.swing(InteractionHand.MAIN_HAND);
                if (timer < 30) return;
                BlockState s = level.getBlockState(target);
                if (s.getBlock() instanceof CropBlock crop) {
                    if (progress == 1 && crop.isMaxAge(s)) {
                        // harvest, and sow again at once (what the field is meant to have now)
                        int food = WHEAT_FOOD;
                        for (Crop c : Crop.values()) if (c.block == crop) food = c.food;
                        level.destroyBlock(target, false, r, 512);
                        CropBlock seed = sowing != null && sowing.block instanceof CropBlock cb ? cb : crop;
                        level.setBlock(target, seed.getStateForAge(0), Block.UPDATE_ALL);
                        carried += food;
                    } else if (!crop.isMaxAge(s)) {
                        // weeding and watering a patch around it: it all comes on a little
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                BlockPos p = target.offset(dx, 0, dz);
                                BlockState c = level.getBlockState(p);
                                if (c.getBlock() instanceof CropBlock cb && !cb.isMaxAge(c)) {
                                    level.setBlock(p, cb.getStateForAge(cb.getAge(c) + 1), Block.UPDATE_ALL);
                                }
                            }
                        }
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5, target.getY() + 0.4, target.getZ() + 0.5,
                                8, 0.8, 0.2, 0.8, 0);
                    }
                }
                phase = carried >= LOAD_WHEAT * WHEAT_FOOD ? Phase.DELIVER : Phase.FIND;
            }
            default -> phase = Phase.FIND;
        }
    }

    // ------------------------------------------------------------------ the herder

    /** The animal the herder is seeing to. */
    private net.minecraft.world.entity.animal.Animal beast;

    /**
     * The herder at his run: calls an animal over to the fence, stands outside it and feeds it, shears a sheep with its
     * wool grown, milks a cow, takes a hen's eggs; brings the food to the store (the wool goes in at once).
     */
    private void herder(ServerLevel level, Village v, Dweller d) {
        Building pen = VillageLife.pen(v, d);
        if (pen == null) {
            r.hold(ItemStack.EMPTY);
            forage(level, v);
            return;
        }
        Blueprint.Frame f = new Blueprint.Frame(pen.origin, pen.front);
        switch (phase) {
            case FIND -> {
                beast = null;
                List<net.minecraft.world.entity.animal.Animal> herd = Herds.animals(level, v, pen);
                if (herd.isEmpty()) {
                    activity("herding");
                    r.hold(new ItemStack(Items.WHEAT));
                    waitBy(level, f.at(2, 0, Herds.FRONT + 1));
                    return;
                }
                // a sheep with its wool grown first, else any (each in turn)
                for (var a : herd) if (a instanceof net.minecraft.world.entity.animal.sheep.Sheep s && s.readyForShearing() && !a.isBaby()) beast = a;
                if (beast == null) beast = herd.get(r.getRandom().nextInt(herd.size()));
                Herds.Run run = Herds.runOf(v, pen, beast);
                if (run == null) {
                    beast = null;
                    return;
                }
                // where to stand: outside the run's fence, on its open side nearest the animal
                double[] l = local(f, beast.getX(), beast.getZ());
                int[] spot = Herds.keeperSpot(pen, run, l[0], l[1]);
                BlockPos at = f.at(spot[0], 0, spot[1]);
                stand = PlotFinder.standAt(level, at.getX(), at.getZ(), pen.origin.getY());
                if (stand == null) stand = f.at(2, 0, Herds.FRONT + 1);
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                activity("herding");
                r.hold(new ItemStack(Items.WHEAT));
                if (beast == null || !beast.isAlive()) {
                    phase = Phase.FIND;
                    return;
                }
                if (walk(stand, 1.2)) {
                    phase = Phase.WORK;
                    timer = 0;
                } else if (++timer > GIVE_UP) {
                    phase = Phase.FIND;
                }
            }
            case WORK -> {
                if (beast == null || !beast.isAlive()) {
                    phase = Phase.FIND;
                    return;
                }
                // the animal is called over to the fence (it comes for the grain)
                r.getLookControl().setLookAt(beast, 30, 30);
                if (timer % 20 == 0) beast.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0);
                boolean sheep = beast instanceof net.minecraft.world.entity.animal.sheep.Sheep s0 && s0.readyForShearing() && !beast.isBaby();
                boolean cow = beast instanceof net.minecraft.world.entity.animal.cow.Cow && !beast.isBaby();
                boolean hen = beast instanceof net.minecraft.world.entity.animal.chicken.Chicken && !beast.isBaby();
                boolean pig = beast instanceof net.minecraft.world.entity.animal.pig.Pig && !beast.isBaby();
                double near = beast.distanceToSqr(r);
                // fed first (the grain in hand), then the work of it
                if (timer < 60) {
                    activity("feeding");
                    r.hold(new ItemStack(Items.WHEAT));
                } else {
                    activity(sheep ? "shearing" : cow ? "milking" : hen ? "eggs" : pig ? "pigs" : "feeding");
                    r.hold(new ItemStack(sheep ? Job.HERDER.tool(Math.max(1, v.toolLevel(Job.HERDER))) : cow ? Items.BUCKET : Items.WHEAT));
                }
                if (++timer % 15 == 0 && near < 16) r.swing(InteractionHand.MAIN_HAND);
                if (timer == 50 && near < 25) {
                    level.sendParticles(ParticleTypes.HEART, beast.getX(), beast.getY() + beast.getBbHeight() + 0.3, beast.getZ(), 2, 0.3, 0.2, 0.3, 0);
                }
                if (timer < 120) return;
                if (near < 25) {
                    if (sheep) {
                        ((net.minecraft.world.entity.animal.sheep.Sheep) beast).setSheared(true);
                        level.playSound(null, beast.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0F, 1.0F);
                        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.WOOL.white()))),
                                beast.getX(), beast.getY() + 0.7, beast.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
                        int wool = 1 + r.getRandom().nextInt(3);
                        if (!v.full(Res.WOOL)) v.add(Res.WOOL, wool);
                        Dweller me = dweller(v);
                        if (me != null) me.found += wool;
                        VillageData.get(level.getServer()).changed();
                    } else if (cow) {
                        level.playSound(null, beast.blockPosition(), SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0F, 1.0F);
                        carried += 6;
                    } else if (hen) {
                        level.playSound(null, beast.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1.0F, 1.0F);
                        carried += 4;
                    } else if (pig) {
                        level.playSound(null, beast.blockPosition(), SoundEvents.PIG_EAT_BABY.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
                        carried += 4;
                    } else {
                        carried += 2;
                    }
                }
                phase = carried >= 12 ? Phase.DELIVER : Phase.FIND;
            }
            default -> phase = Phase.FIND;
        }
    }

    /** World coordinates in a plot's own (x to its right, z to its front). */
    private static double[] local(Blueprint.Frame f, double x, double z) {
        double dx = x - (f.origin().getX() + 0.5), dz = z - (f.origin().getZ() + 0.5);
        Direction right = f.right(), front = f.front();
        return new double[]{dx * right.getStepX() + dz * right.getStepZ(), dx * front.getStepX() + dz * front.getStepZ()};
    }

    /** No field yet: berries, roots and mushrooms from around the village. */
    private void forage(ServerLevel level, Village v) {
        switch (phase) {
            case FIND -> {
                double a = r.getRandom().nextDouble() * Math.PI * 2;
                int dist = 10 + r.getRandom().nextInt(16);
                int x = v.center.getX() + (int) (Math.cos(a) * dist), z = v.center.getZ() + (int) (Math.sin(a) * dist);
                stand = new BlockPos(x, PlotFinder.floorAt(level, x, z), z);
                if (!level.getBlockState(stand.below()).getFluidState().isEmpty()) return;
                phase = Phase.GO;
                timer = 0;
            }
            case GO -> {
                activity("foraging");
                if (walk(stand, 2.0)) {
                    phase = Phase.WORK;
                    timer = 0;
                } else if (++timer > GIVE_UP) {
                    phase = Phase.FIND;
                }
            }
            case WORK -> {
                activity("foraging");
                lookAt(stand.below());
                if (++timer % 12 == 0) {
                    r.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, stand, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.5F, 1.1F);
                }
                if (timer >= 60) {
                    level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.SWEET_BERRIES))),
                            stand.getX() + 0.5, stand.getY() + 0.3, stand.getZ() + 0.5, 5, 0.2, 0.2, 0.2, 0.08);
                    carried += WHEAT_FOOD;
                    phase = carried >= LOAD_WHEAT * WHEAT_FOOD ? Phase.DELIVER : Phase.FIND;
                }
            }
            default -> phase = Phase.FIND;
        }
    }

    /** Nothing to work at: walk about the edge of the village looking. */
    /** Waits about the hut: a few steps round it now and then. */
    private void waitBy(ServerLevel level, BlockPos hut) {
        if (stand == null || stand.distSqr(hut) > 400 || walk(stand, 2.0) || ++timer > GIVE_UP) {
            // somewhere round the hut, each of them a spot of their own (not all in a heap at its door)
            stand = null;
            for (int k = 0; k < 8 && stand == null; k++) {
                double a = r.getRandom().nextDouble() * Math.PI * 2, d = 6 + r.getRandom().nextInt(9);
                int x = hut.getX() + (int) Math.round(Math.cos(a) * d), z = hut.getZ() + (int) Math.round(Math.sin(a) * d);
                BlockPos p = new BlockPos(x, PlotFinder.floorAt(level, x, z), z);
                if (DwellerGoals.inPlot(village(), x, z, 0) || !level.getBlockState(p.below()).getFluidState().isEmpty()) continue;
                stand = p;
            }
            if (stand == null) stand = hut;
            timer = 0;
            if (ticks % 400 == 0) {
                DwellerGoals.forgetTrees(village());
                bad.clear();
            }
        }
    }

    /** Saplings and trees a woodcutters' hut's grove holds at most. */
    static int groveSize(Village v) {
        int level = 1;
        for (Building b : v.buildings) if (b.type == BuildingType.WOOD_HUT && b.standing()) level = Math.max(level, b.level);
        return 4 + 3 * level;
    }

    /** The sapling the woodcutters plant: of the village's own wood (one that grows from a single sapling). */
    static BlockState groveSapling(Village v) {
        String kind = v.wood.equals("dark_oak") || v.wood.equals("pale_oak") ? "oak" : v.wood;
        String id = kind.equals("mangrove") ? "mangrove_propagule" : kind + "_sapling";
        return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(id)).orElse(Blocks.OAK_SAPLING).defaultBlockState();
    }

    /** The trees and saplings of the grove round a hut. */
    static int groveCount(ServerLevel level, BlockPos hut) {
        int n = 0;
        int r = DwellerGoals.GROVE - 4;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int px = hut.getX() + x, pz = hut.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) continue;
                BlockPos g = new BlockPos(px, PlotFinder.floorAt(level, px, pz), pz);
                BlockState s = level.getBlockState(g);
                if (s.getBlock() instanceof SaplingBlock || s.is(BlockTags.LOGS) && !level.getBlockState(g.below()).is(BlockTags.LOGS)) n++;
                // (a trunk: floorAt goes down through it to the ground it stands on)
                else if (level.getBlockState(g.above()).is(BlockTags.LOGS) && !level.getBlockState(g).is(BlockTags.LOGS)) n++;
            }
        }
        return n;
    }

    /** Where the grove wants another sapling (if it isn't full): grass by the hut, clear of plots, paths and trees. */
    static BlockPos groveSpot(ServerLevel level, Village v, BlockPos hut) {
        if (groveCount(level, hut) >= groveSize(v)) return null;
        BlockState sapling = groveSapling(v);
        var rnd = level.getRandom();
        for (int i = 0; i < 24; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int d = 5 + rnd.nextInt(DwellerGoals.GROVE - 8);
            int x = hut.getX() + (int) Math.round(Math.cos(a) * d), z = hut.getZ() + (int) Math.round(Math.sin(a) * d);
            if (!Construction.loaded(level, new BlockPos(x, 0, z)) || DwellerGoals.inPlot(v, x, z, 1) || Tidy.territory(v, x, z)) continue;
            BlockPos p = new BlockPos(x, PlotFinder.floorAt(level, x, z), z);
            BlockState ground = level.getBlockState(p.below());
            if (ground.is(Blocks.DIRT_PATH) || !ground.getFluidState().isEmpty() || !level.getBlockState(p).canBeReplaced()) continue;
            if (!sapling.canSurvive(level, p)) continue;
            // room to grow: nothing woody within three blocks
            boolean crowded = false;
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -1, -3), p.offset(3, 4, 3))) {
                BlockState s = level.getBlockState(q);
                if (s.is(BlockTags.LOGS) || s.getBlock() instanceof SaplingBlock) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) return p;
        }
        return null;
    }

    /** A day in the grove: the saplings the woodcutters planted grow (tended, they grow quicker than wild ones). */
    static void growGrove(ServerLevel level, Village v) {
        BlockPos hut = DwellerGoals.woodHut(v);
        if (hut == null || !Construction.loaded(level, hut)) return;
        int r = DwellerGoals.GROVE - 4;
        var rnd = level.getRandom();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int px = hut.getX() + x, pz = hut.getZ() + z;
                if (!Construction.loaded(level, new BlockPos(px, 0, pz))) continue;
                BlockPos p = new BlockPos(px, PlotFinder.floorAt(level, px, pz), pz);
                BlockState s = level.getBlockState(p);
                if (s.getBlock() instanceof SaplingBlock sb && rnd.nextInt(3) != 0) {
                    sb.advanceTree(level, p, s, rnd);
                    BlockState now = level.getBlockState(p);
                    if (now.getBlock() instanceof SaplingBlock) sb.advanceTree(level, p, now, rnd);
                }
            }
        }
        DwellerGoals.forgetTrees(v);
    }

    private void wander(ServerLevel level, Village v) {
        if (stand == null || walk(stand, 2.0) || ++timer > GIVE_UP) {
            // somewhere on dry land: never out into the water
            stand = DwellerGoals.dryLand(level, v, r.getRandom(), 8 + r.getRandom().nextInt(14));
            if (stand == null) stand = v.storeSpot();
            timer = 0;
            if (ticks % 400 == 0) {
                DwellerGoals.forgetTrees(v);
                bad.clear();
            }
        }
    }
}
