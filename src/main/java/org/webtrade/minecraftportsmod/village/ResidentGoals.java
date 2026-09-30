package org.webtrade.minecraftportsmod.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.webtrade.minecraftportsmod.economy.EconomyManager;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.economy.TradeRun;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.vessel.VesselEntity;

import java.util.EnumSet;

/** The daily routine of a resident: sleep, work, home in the evening; and the skipper's trips ashore. */
final class ResidentGoals {

    private ResidentGoals() {
    }

    private static boolean free(ResidentEntity r) {
        return !r.isPassenger() && !r.isSleeping() && r.level() instanceof ServerLevel;
    }

    private static void walkTo(ResidentEntity r, BlockPos p, double speed) {
        if (r.getNavigation().isDone() || r.tickCount % 40 == 0) {
            r.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed);
        }
    }

    // ------------------------------------------------------------------ sleep

    /** At night: walk home and sleep in the bed (if someone else took it, just stay by it). */
    static final class Sleep extends Goal {
        private final ResidentEntity r;

        Sleep(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        private boolean bedThere() {
            return r.home != null && r.level().getBlockState(r.home).getBlock() instanceof BedBlock;
        }

        @Override
        public boolean canUse() {
            if (r.colony()) return false;
            return r.night() && !r.isPassenger() && !r.isSkipper() && bedThere() && r.level() instanceof ServerLevel;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() || r.isSleeping() && r.night();
        }

        @Override
        public void start() {
            r.hold(ItemStack.EMPTY);
        }

        @Override
        public void tick() {
            if (r.isSleeping()) return;
            if (r.distanceToSqr(r.home.getX() + 0.5, r.home.getY(), r.home.getZ() + 0.5) > 2.5 * 2.5) {
                walkTo(r, r.home, 0.55);
                return;
            }
            r.getNavigation().stop();
            BlockState bed = r.level().getBlockState(r.home);
            if (bed.getBlock() instanceof BedBlock && !bed.getValue(BedBlock.OCCUPIED)) r.startSleeping(r.home);
        }

        @Override
        public void stop() {
            if (r.isSleeping()) r.stopSleeping();
        }
    }

    // ------------------------------------------------------------------ evening

    /** Outside working hours: stay about the house (or the square) rather than wander off. */
    static final class StayNearHome extends Goal {
        private final ResidentEntity r;

        StayNearHome(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private BlockPos anchor() {
            return r.home != null ? r.home : r.job;
        }

        @Override
        public boolean canUse() {
            if (r.colony()) return false;
            BlockPos a = anchor();
            return !r.workHours() && free(r) && !r.isSkipper() && a != null && r.distanceToSqr(a.getX(), a.getY(), a.getZ()) > 10 * 10;
        }

        @Override
        public void start() {
            r.hold(ItemStack.EMPTY);
        }

        @Override
        public void tick() {
            walkTo(r, anchor(), 0.5);
        }
    }

    // ------------------------------------------------------------------ work

    /** What a trade takes from the stores and what it brings back. */
    private record Trade(ItemStack input, ItemStack product, java.util.List<org.webtrade.minecraftportsmod.economy.Good> needs) {
    }

    private static Trade trade(ResidentEntity r, VillageLayout l) {
        var G = org.webtrade.minecraftportsmod.economy.Good.class;
        String wood = l == null ? "oak" : l.wood();
        ItemStack log = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace(wood + "_log")));
        ItemStack planks = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace(wood + "_planks")));
        java.util.List<org.webtrade.minecraftportsmod.economy.Good> logs = new java.util.ArrayList<>();
        for (var g : org.webtrade.minecraftportsmod.economy.Good.values()) if (g.isLog()) logs.add(g);
        return switch (r.profession()) {
            case FARMER -> new Trade(ItemStack.EMPTY, new ItemStack(Items.WHEAT), java.util.List.of());
            case FISHER -> new Trade(ItemStack.EMPTY, new ItemStack(Items.COD), java.util.List.of());
            case WOODCUTTER -> new Trade(ItemStack.EMPTY, log, java.util.List.of());
            case MINER -> new Trade(ItemStack.EMPTY, new ItemStack(Items.RAW_IRON), java.util.List.of());
            case MASON -> new Trade(ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE), java.util.List.of());
            case SHEPHERD -> new Trade(ItemStack.EMPTY, new ItemStack(Items.WOOL.white()), java.util.List.of());
            case SAWYER -> new Trade(log, planks, logs);
            case SMELTER -> new Trade(new ItemStack(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), java.util.List.of(
                    org.webtrade.minecraftportsmod.economy.Good.IRON_ORE, org.webtrade.minecraftportsmod.economy.Good.COPPER_ORE,
                    org.webtrade.minecraftportsmod.economy.Good.GOLD_ORE));
            case SMITH -> new Trade(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_PICKAXE),
                    java.util.List.of(org.webtrade.minecraftportsmod.economy.Good.IRON));
            case POTTER -> new Trade(new ItemStack(Items.CLAY_BALL), new ItemStack(Items.BRICK),
                    java.util.List.of(org.webtrade.minecraftportsmod.economy.Good.CLAY, org.webtrade.minecraftportsmod.economy.Good.SAND));
            case BAKER -> new Trade(new ItemStack(Items.WHEAT), new ItemStack(Items.BREAD),
                    java.util.List.of(org.webtrade.minecraftportsmod.economy.Good.WHEAT));
            case MERCHANT -> new Trade(ItemStack.EMPTY, ItemStack.EMPTY, java.util.List.of());
        };
    }

    private static Settlement settlementOf(ResidentEntity r) {
        MinecraftServer srv = r.level().getServer();
        return srv == null ? null : SettlementData.get(srv).get(r.settlement);
    }

    /**
     * By day: the round of the trade. Crafters fetch their material from the stock yard, work it at their
     * block and carry the product back; gatherers work in the field, on the pier, at the trees and bring in what
     * they got. With nothing in the stores to work, a crafter sits idle and grumbles — the village's shortage is
     * there to see.
     */
    static final class Work extends Goal {
        private enum Step {FETCH, WORK, CARRY, IDLE}

        private final ResidentEntity r;
        private Step step;
        private BlockPos spot, target;
        private int cooldown, stay, swings;

        Work(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (r.colony()) return false;
            return r.workHours() && !r.lunchTime() && free(r) && !r.isSkipper() && r.job != null
                    && r.distanceToSqr(r.job.getX(), r.job.getY(), r.job.getZ()) < 96 * 96;
        }

        @Override
        public void start() {
            spot = null;
            step = null;
        }

        @Override
        public void stop() {
            r.hold(ItemStack.EMPTY);
            r.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        private VillageLayout layout() {
            Settlement s = settlementOf(r);
            return s == null ? null : s.layout();
        }

        private BlockPos yard() {
            VillageLayout l = layout();
            if (l == null || l.stockyard().isEmpty()) return null;
            BlockPos c = l.stockyard().get();
            return c.offset(r.getRandom().nextInt(3) - 1, 0, r.getRandom().nextInt(3) - 1);
        }

        /** Has the village anything this crafter could work? */
        private boolean materialInStore(Trade t) {
            if (t.needs().isEmpty()) return true;
            Settlement s = settlementOf(r);
            if (s == null) return true;
            for (var g : t.needs()) if (s.stock(g) >= 1) return true;
            return false;
        }

        private double speed() {
            Settlement s = settlementOf(r);
            return s != null && s.need("food") < 0.7 ? 0.35 : 0.5;   // hungry people drag their feet
        }

        @Override
        public void tick() {
            VillageLayout l = layout();
            Trade t = trade(r, l);
            if (step == null) step = !t.input().isEmpty() && yard() != null ? Step.FETCH : Step.WORK;
            switch (step) {
                case FETCH -> {
                    if (!materialInStore(t)) {
                        step = Step.IDLE;
                        spot = null;
                        return;
                    }
                    if (spot == null) spot = yard();
                    if (spot == null) {
                        step = Step.WORK;
                        return;
                    }
                    r.hold(ItemStack.EMPTY);
                    if (walk(spot)) {
                        r.swing(InteractionHand.MAIN_HAND);
                        r.hold(t.input().copy());
                        step = Step.WORK;
                        spot = null;
                    }
                }
                case IDLE -> {
                    // waiting for material at the workplace, no tool in hand
                    r.hold(ItemStack.EMPTY);
                    if (walk(r.job)) {
                        if (r.tickCount % 80 == 0) {
                            ((ServerLevel) r.level()).sendParticles(ParticleTypes.ANGRY_VILLAGER, r.getX(), r.getY() + 2.1, r.getZ(), 1, 0.2, 0.1, 0.2, 0);
                        }
                        if (r.tickCount % 200 == 0 && materialInStore(t)) step = Step.FETCH;
                    }
                }
                case WORK -> work(t);
                case CARRY -> {
                    if (spot == null) spot = yard();
                    if (spot == null) {
                        step = Step.WORK;
                        return;
                    }
                    r.hold(t.product().copy());
                    if (walk(spot)) {
                        r.swing(InteractionHand.MAIN_HAND);
                        ((ServerLevel) r.level()).sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM,
                                        net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(t.product())),
                                spot.getX() + 0.5, spot.getY() + 0.8, spot.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.05);
                        r.hold(ItemStack.EMPTY);
                        spot = null;
                        step = !t.input().isEmpty() ? Step.FETCH : Step.WORK;
                    }
                }
            }
        }

        /** Walks towards a point; true once there (and standing still). */
        private boolean walk(BlockPos p) {
            if (r.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) > 2.2 * 2.2) {
                walkTo(r, p, speed());
                return false;
            }
            r.getNavigation().stop();
            return true;
        }

        private void work(Trade t) {
            if (spot == null) {
                pick();
                swings = 5 + r.getRandom().nextInt(5);
            }
            if (spot == null) return;
            double d = r.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            if (d > 2.2 * 2.2) {
                walkTo(r, spot, speed());
                if (r.getNavigation().isDone() && r.tickCount % 100 == 0) spot = null; // can't get there: try another spot
                return;
            }
            r.getNavigation().stop();
            r.hold(r.tool());
            if (target != null) r.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            if (--cooldown <= 0) {
                r.swing(InteractionHand.MAIN_HAND);
                effect();
                cooldown = 20 + r.getRandom().nextInt(30);
                // a load done: take it to the stock yard
                if (--swings <= 0 && !t.product().isEmpty() && yard() != null) {
                    step = Step.CARRY;
                    spot = null;
                    return;
                }
            }
            if (--stay <= 0) spot = null;
        }

        /** Chooses where to stand and what to work at. */
        private void pick() {
            var rnd = r.getRandom();
            stay = 200 + rnd.nextInt(300);
            cooldown = 10;
            BlockPos job = r.job;
            switch (r.work) {
                case FIELD -> {
                    // a crop somewhere in the field (not the water channel)
                    int dx = rnd.nextInt(7) - 3, dz = rnd.nextInt(7) - 3;
                    if (dx == 0) dx = 1;
                    target = findGround(job.offset(dx, 0, dz));
                    spot = target;
                }
                case FISH -> {
                    spot = job.offset(rnd.nextInt(3) - 1, 0, rnd.nextInt(3) - 1);
                    target = r.workTarget != null ? r.workTarget : job;
                }
                case TREE -> {
                    target = nearestLog(job, 48);
                    spot = target == null ? job : findGround(target.offset(rnd.nextBoolean() ? 1 : -1, 0, rnd.nextBoolean() ? 1 : -1));
                }
                case WORKSHOP, OFFICE -> {
                    target = r.workTarget != null ? r.workTarget : job;
                    spot = job;
                }
                case PEN -> {
                    target = job;
                    spot = r.workTarget != null ? r.workTarget : job.offset(5, 0, 0);
                }
                case SQUARE -> {
                    target = null;
                    spot = job.offset(rnd.nextInt(7) - 3, 0, rnd.nextInt(7) - 3);
                }
            }
        }

        private BlockPos findGround(BlockPos p) {
            ServerLevel level = (ServerLevel) r.level();
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            return new BlockPos(p.getX(), y, p.getZ());
        }

        private BlockPos nearestLog(BlockPos around, int radius) {
            ServerLevel level = (ServerLevel) r.level();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    int x = around.getX() + dx, z = around.getZ() + dz;
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    for (int k = 0; k < 6; k++) {
                        BlockPos p = new BlockPos(x, y - k, z);
                        if (level.getBlockState(p).is(BlockTags.LOGS)) {
                            // the lowest log of this trunk: the one to chop at
                            while (level.getBlockState(p.below()).is(BlockTags.LOGS)) p = p.below();
                            double d = p.distSqr(around);
                            if (d < bestD && d > 20) {
                                bestD = d;
                                best = p;
                            }
                            break;
                        }
                    }
                }
            }
            return best;
        }

        private void effect() {
            ServerLevel level = (ServerLevel) r.level();
            if (target == null) return;
            BlockState st = level.getBlockState(target);
            switch (r.work) {
                case TREE -> {
                    if (st.is(BlockTags.LOGS)) level.levelEvent(2001, target, net.minecraft.world.level.block.Block.getId(st));
                }
                case FIELD -> {
                    if (st.getBlock() instanceof CropBlock && st.hasProperty(BlockStateProperties.AGE_7)) {
                        if (st.getValue(BlockStateProperties.AGE_7) >= 7) {
                            // harvest and sow again: the field keeps its cycle
                            level.levelEvent(2001, target, net.minecraft.world.level.block.Block.getId(st));
                            level.setBlock(target, st.setValue(BlockStateProperties.AGE_7, 0), 3);
                        } else if (r.getRandom().nextInt(4) == 0) {
                            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5, target.getY() + 0.4, target.getZ() + 0.5, 3, 0.3, 0.2, 0.3, 0);
                        }
                    }
                }
                case FISH -> {
                    level.sendParticles(ParticleTypes.FISHING, target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5, 4, 0.4, 0.05, 0.4, 0.02);
                    if (r.getRandom().nextInt(6) == 0) {
                        level.sendParticles(ParticleTypes.SPLASH, target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5, 12, 0.3, 0.1, 0.3, 0.1);
                    }
                }
                case WORKSHOP -> {
                    if (!st.isAir()) {
                        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), target.getX() + 0.5, target.getY() + 1.0,
                                target.getZ() + 0.5, 5, 0.2, 0.1, 0.2, 0.05);
                    }
                    if (st.is(net.minecraft.world.level.block.Blocks.FURNACE) || st.is(net.minecraft.world.level.block.Blocks.BLAST_FURNACE)
                            || st.is(net.minecraft.world.level.block.Blocks.SMOKER)) {
                        level.sendParticles(ParticleTypes.SMOKE, target.getX() + 0.5, target.getY() + 1.1, target.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
                    }
                }
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------ midday

    /** At midday the village gathers on the square: people stand together and talk. */
    static final class Lunch extends Goal {
        private final ResidentEntity r;
        private BlockPos spot;
        private int chat;

        Lunch(ResidentEntity r) {
            this.r = r;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (r.colony()) return false;
            if (!r.lunchTime() || !free(r) || r.isSkipper()) return false;
            Settlement s = settlementOf(r);
            return s != null && s.layout() != null;
        }

        @Override
        public void start() {
            Settlement s = settlementOf(r);
            BlockPos sq = s.layout().square();
            spot = sq.offset(r.getRandom().nextInt(7) - 3, 0, r.getRandom().nextInt(7) - 3);
            r.hold(ItemStack.EMPTY);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (r.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 2.5 * 2.5) {
                walkTo(r, spot, 0.5);
                return;
            }
            r.getNavigation().stop();
            // turn to the nearest neighbour and talk, with the odd gesture
            var other = r.level().getEntitiesOfClass(ResidentEntity.class, r.getBoundingBox().inflate(5), e -> e != r)
                    .stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(r))).orElse(null);
            if (other != null) {
                r.getLookControl().setLookAt(other, 30, 30);
                if (++chat % 60 == 0 && r.getRandom().nextBoolean()) r.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    // ------------------------------------------------------------------ skipper

    /**
     * A skipper sails with its vessel. In port — loading at home or trading abroad — it goes ashore with a chest of
     * goods to the market square, bargains with the local residents, and comes back aboard with the takings
     * before the vessel casts off. If it is ever left ashore while its vessel sails away, it rejoins it.
     */
    static final class Skipper extends Goal {
        private enum Step {NONE, TO_MARKET, BARGAIN, BACK}

        private final ResidentEntity r;
        private Step step = Step.NONE;
        private BlockPos market;
        private int timer;

        Skipper(ResidentEntity r) {
            this.r = r;
            // no JUMP flag: a mob sitting in a boat has jumping goals switched off, and this one starts aboard
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private MinecraftServer server() {
            return r.level().getServer();
        }

        @Override
        public boolean canUse() {
            if (r.colony()) return false;
            if (!r.isSkipper() || !(r.level() instanceof ServerLevel)) return false;
            return step != Step.NONE || !r.isPassenger() || shouldGoAshore();
        }

        @Override
        public boolean canContinueToUse() {
            return r.isSkipper() && (step != Step.NONE || !r.isPassenger());
        }

        private VesselEntity body() {
            VesselEntity b = FleetManager.live(r.vessel);
            return b != null && b.level() == r.level() ? b : null;
        }

        private boolean shouldGoAshore() {
            VesselEntity body = body();
            VesselRecord rec = FleetManager.record(server(), r.vessel);
            if (body == null || rec == null || r.getVehicle() != body) return false;
            if (rec.state() != VesselRecord.State.MOORED) return false;
            TradeRun run = SettlementData.get(server()).run(r.vessel);
            if (run == null || run.departAt() == r.visitedStop) return false;
            if (run.phase() != TradeRun.Phase.LOADING && run.phase() != TradeRun.Phase.TRADING) return false;
            // the departure waits for the skipper, so any time in port is enough for a walk to the market
            return run.departAt() - EconomyManager.now(server()) > 40;
        }

        /** Where a skipper steps ashore: the pier's end, else the square. */
        private BlockPos landingOf(int portId) {
            Settlement s = SettlementData.get(server()).get(portId);
            if (s == null || s.layout() == null) return null;
            return s.layout().pierTip().map(BlockPos::above).orElse(s.layout().square());
        }

        private BlockPos marketOf(int portId) {
            Settlement s = SettlementData.get(server()).get(portId);
            if (s != null && s.layout() != null) return s.layout().square();
            var port = org.webtrade.minecraftportsmod.port.PortData.get(server()).port(portId);
            return port == null ? r.blockPosition() : port.office();
        }

        @Override
        public void start() {
            VesselRecord rec = FleetManager.record(server(), r.vessel);
            if (r.isPassenger() && rec != null && shouldGoAshore()) {
                TradeRun run = SettlementData.get(server()).run(r.vessel);
                r.visitedStop = run.departAt();
                market = marketOf(rec.portId());
                r.stopRiding();
                // step off onto the pier, not into the water
                BlockPos landing = landingOf(rec.portId());
                if (landing != null) r.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                r.hold(new ItemStack(Items.CHEST));
                step = Step.TO_MARKET;
            } else if (!r.isPassenger() && step == Step.NONE) {
                step = Step.BACK;
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            VesselEntity body = body();
            VesselRecord rec = FleetManager.record(server(), r.vessel);
            if (rec == null) {
                // the vessel is gone: the skipper retires into the village
                r.vessel = null;
                step = Step.NONE;
                return;
            }
            // the vessel left (or went out of sight) without the skipper: rejoin it
            if (body == null || rec.state() == VesselRecord.State.SAILING) {
                step = Step.NONE;
                r.hold(ItemStack.EMPTY);
                FleetManager.board((ServerLevel) r.level(), rec, r);
                return;
            }
            TradeRun run = SettlementData.get(server()).run(r.vessel);
            long now = EconomyManager.now(server());
            r.debug = "step=" + step + " market=" + (market == null ? "-" : market.toShortString());
            boolean hurry = run == null || now - run.departAt() > 500;
            switch (step) {
                case TO_MARKET -> {
                    if (hurry) {
                        step = Step.BACK;
                        timer = 0;
                    } else if (r.distanceToSqr(market.getX() + 0.5, market.getY(), market.getZ() + 0.5) < 3.5 * 3.5) {
                        r.getNavigation().stop();
                        step = Step.BARGAIN;
                        timer = 140;
                    } else {
                        walkTo(r, market, 0.55);
                    }
                }
                case BARGAIN -> {
                    Entity partner = r.level().getEntitiesOfClass(ResidentEntity.class, r.getBoundingBox().inflate(10), e -> e != r)
                            .stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(r))).orElse(null);
                    if (partner != null) {
                        r.getLookControl().setLookAt(partner, 30, 30);
                        if (partner instanceof ResidentEntity p && !p.isSkipper() && timer % 40 == 0) {
                            p.getNavigation().stop();
                            p.getLookControl().setLookAt(r, 30, 30);
                            p.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                    if (timer % 25 == 0) {
                        r.swing(InteractionHand.MAIN_HAND);
                        ((ServerLevel) r.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, r.getX(), r.getY() + 2.1, r.getZ(), 3, 0.3, 0.2, 0.3, 0);
                    }
                    if (--timer <= 0 || hurry) {
                        r.hold(new ItemStack(Items.EMERALD));
                        step = Step.BACK;
                        timer = 0;
                    }
                }
                case BACK, NONE -> {
                    if (r.distanceToSqr(body) < 7.0 * 7.0) {
                        // at the berth: climb aboard
                        r.hold(ItemStack.EMPTY);
                        FleetManager.board((ServerLevel) r.level(), rec, r);
                        step = Step.NONE;
                    } else {
                        BlockPos landing = landingOf(rec.portId());
                        walkTo(r, landing != null ? landing : body.blockPosition(), 0.6);
                        // stuck on the way back for too long: jump aboard
                        if (++timer > 600 || hurry && run != null && now > run.departAt()) {
                            r.hold(ItemStack.EMPTY);
                            FleetManager.board((ServerLevel) r.level(), rec, r);
                            step = Step.NONE;
                            timer = 0;
                        }
                    }
                }
            }
        }
    }
}
