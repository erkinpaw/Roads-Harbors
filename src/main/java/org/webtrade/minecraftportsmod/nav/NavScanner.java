package org.webtrade.minecraftportsmod.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

/**
 * Turns a loaded chunk into a {@link ChunkNav}. Must run on the server thread
 * (it reads block states straight from the live chunk).
 */
public final class NavScanner {

    private NavScanner() {
    }

    public static ChunkNav scan(ServerLevel level, LevelChunk chunk) {
        int waterY = level.getSeaLevel() - 1;
        int minBuildY = level.getMinY();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        byte[] flags = new byte[ChunkNav.COLUMNS];
        byte[] colors = new byte[ChunkNav.COLUMNS];
        int[] prevRowHeight = new int[16];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                int index = ChunkNav.index(lx, lz);
                int f = 0;

                // --- navigability: water surface exactly at sea level, nothing solid on top of it ---
                BlockState surface = chunk.getBlockState(pos.set(x, waterY, z));
                if (isWater(surface.getFluidState()) && surface.getCollisionShape(chunk, pos).isEmpty()) {
                    BlockState above = chunk.getBlockState(pos.set(x, waterY + 1, z));
                    if (above.getFluidState().isEmpty() && above.getCollisionShape(chunk, pos).isEmpty()) {
                        f |= ChunkNav.FLAG_NAVIGABLE;
                    }
                }

                // --- map colour, the same way vanilla maps pick it ---
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                BlockState top = chunk.getBlockState(pos.set(x, y, z));
                MapColor color = top.getMapColor(chunk, pos);
                while (color == MapColor.NONE && y > minBuildY) {
                    y--;
                    top = chunk.getBlockState(pos.set(x, y, z));
                    color = top.getMapColor(chunk, pos);
                }

                MapColor.Brightness brightness;
                if (!top.getFluidState().isEmpty() && isWater(top.getFluidState())) {
                    f |= ChunkNav.FLAG_SURFACE_WATER;
                    color = MapColor.WATER;
                    int depth = 0;
                    int dy = y;
                    while (depth < 12 && dy > minBuildY && !chunk.getBlockState(pos.set(x, dy, z)).getFluidState().isEmpty()) {
                        depth++;
                        dy--;
                    }
                    double shade = depth * 0.1 + ((lx + lz) & 1) * 0.2;
                    brightness = shade < 0.5 ? MapColor.Brightness.HIGH
                            : shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                } else {
                    int north = lz == 0 ? y : prevRowHeight[lx];
                    int diff = y - north;
                    brightness = diff > 0 ? MapColor.Brightness.HIGH
                            : diff < 0 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                prevRowHeight[lx] = y;

                flags[index] = (byte) f;
                colors[index] = (byte) ((color.id << 2) | (brightness.id & 3));
            }
        }
        return new ChunkNav(ChunkNav.SCAN_VERSION, flags, colors);
    }

    private static boolean isWater(FluidState state) {
        Fluid type = state.getType();
        return type == Fluids.WATER || type == Fluids.FLOWING_WATER;
    }
}
