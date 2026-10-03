package org.webtrade.minecraftportsmod.combat;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.minecraftportsmod.registry.ModContent;

/**
 * Pirates at sea: now and then a pirate ship comes over the horizon towards a captain sailing his warship in open
 * water (never more than a few at a time), and fights him.
 */
public final class Pirates {

    /** How often a sail may be sighted (ticks), and the odds then. */
    static final int EVERY = 400;
    static final float CHANCE = 0.35F;
    /** Pirates about one captain at most. */
    static final int MAX = 2;
    /** How far out they appear (blocks). */
    static final int FROM = 70, TO = 100;

    private static final RandomSource RND = RandomSource.create();

    /** Off for tests that want the sea to themselves. */
    public static boolean enabled = true;

    private Pirates() {
    }

    public static void tick(MinecraftServer server) {
        if (!enabled || server.getTickCount() % EVERY != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p.getVehicle() instanceof WarshipEntity own) || own.isPirate() || own.captain() != p) continue;
            if (RND.nextFloat() > CHANCE) continue;
            ServerLevel level = p.level();
            int near = level.getEntitiesOfClass(WarshipEntity.class, own.getBoundingBox().inflate(200), WarshipEntity::isPirate).size();
            if (near >= MAX) continue;
            WarshipEntity pirate = spawn(level, own.getX(), own.getZ(), RND);
            if (pirate != null) p.sendOverlayMessage(Component.translatable("minecraftportsmod.pirates.sighted").withStyle(ChatFormatting.RED));
        }
    }

    /** A pirate ship put to sea some way off a point, on open water; null if there is no room for one there. */
    public static WarshipEntity spawn(ServerLevel level, double x, double z, RandomSource rnd) {
        for (int tries = 0; tries < 16; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = FROM + rnd.nextDouble() * (TO - FROM);
            int px = (int) Math.floor(x + Math.cos(a) * d), pz = (int) Math.floor(z + Math.sin(a) * d);
            if (!openWater(level, px, pz)) continue;
            WarshipEntity ship = ModContent.WARSHIP.create(level, EntitySpawnReason.EVENT);
            if (ship == null) return null;
            int y = level.getSeaLevel();
            // facing the point (where her prey is)
            float yaw = (float) Math.toDegrees(Math.atan2(-(x - px), z - pz));
            ship.snapTo(px + 0.5, y - 0.4, pz + 0.5, yaw, 0);
            ship.makePirate();
            level.addFreshEntity(ship);
            return ship;
        }
        return null;
    }

    /** Is a patch of sea round a column open water: loaded, water at sea level, three deep and more, no land in it? */
    static boolean openWater(ServerLevel level, int x, int z) {
        for (int dx = -9; dx <= 9; dx += 3) {
            for (int dz = -9; dz <= 9; dz += 3) {
                BlockPos c = new BlockPos(x + dx, 0, z + dz);
                if (!level.hasChunkAt(c)) return false;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ()) - 1;
                if (top != level.getSeaLevel() - 1) return false;
                for (int k = 0; k < 3; k++) {
                    if (level.getBlockState(new BlockPos(c.getX(), top - k, c.getZ())).getFluidState().isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** A ship went down: whoever sank her hears of it. */
    static void sunk(ServerLevel level, WarshipEntity ship, Entity by) {
        if (ship.isPirate() && by instanceof WarshipEntity w && w.captain() instanceof ServerPlayer p) {
            p.sendOverlayMessage(Component.translatable("minecraftportsmod.pirates.sunk").withStyle(ChatFormatting.GOLD));
        }
        if (!ship.isPirate() && ship.captain() instanceof ServerPlayer p) {
            p.sendOverlayMessage(Component.translatable("minecraftportsmod.pirates.lost").withStyle(ChatFormatting.RED));
        }
    }
}
