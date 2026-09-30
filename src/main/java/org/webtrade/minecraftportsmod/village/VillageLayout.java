package org.webtrade.minecraftportsmod.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where things are in a built village: the square, the office, the pier, the workshop and its work blocks, fields,
 * the pen, the beds, the stock yard's piles and the price board's signs, and the plots already built on. Residents
 * get their homes and workplaces from it; the village grows new houses on free plots around the square.
 *
 * @param origin the shore point the village was laid out from; {@code front} points from it to the water
 * @param plots  centres of every building plot taken (a new plot keeps its distance from them)
 */
public record VillageLayout(BlockPos square, BlockPos office, Optional<BlockPos> pierTip, Optional<BlockPos> workshop,
                            List<BlockPos> workBlocks, List<BlockPos> fields, Optional<BlockPos> pen, List<BlockPos> beds,
                            Optional<BlockPos> stockyard, List<BlockPos> piles, List<BlockPos> board, List<BlockPos> plots,
                            String wood, BlockPos origin, Direction front) {

    public static final Codec<VillageLayout> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("square").forGetter(VillageLayout::square),
            BlockPos.CODEC.fieldOf("office").forGetter(VillageLayout::office),
            BlockPos.CODEC.optionalFieldOf("pier").forGetter(VillageLayout::pierTip),
            BlockPos.CODEC.optionalFieldOf("workshop").forGetter(VillageLayout::workshop),
            BlockPos.CODEC.listOf().optionalFieldOf("work_blocks", List.of()).forGetter(VillageLayout::workBlocks),
            BlockPos.CODEC.listOf().optionalFieldOf("fields", List.of()).forGetter(VillageLayout::fields),
            BlockPos.CODEC.optionalFieldOf("pen").forGetter(VillageLayout::pen),
            BlockPos.CODEC.listOf().optionalFieldOf("beds", List.of()).forGetter(VillageLayout::beds),
            BlockPos.CODEC.optionalFieldOf("stockyard").forGetter(VillageLayout::stockyard),
            BlockPos.CODEC.listOf().optionalFieldOf("piles", List.of()).forGetter(VillageLayout::piles),
            BlockPos.CODEC.listOf().optionalFieldOf("board", List.of()).forGetter(VillageLayout::board),
            BlockPos.CODEC.listOf().optionalFieldOf("plots", List.of()).forGetter(VillageLayout::plots),
            Codec.STRING.optionalFieldOf("wood", "oak").forGetter(VillageLayout::wood),
            BlockPos.CODEC.optionalFieldOf("origin", BlockPos.ZERO).forGetter(VillageLayout::origin),
            Direction.CODEC.optionalFieldOf("front", Direction.NORTH).forGetter(VillageLayout::front)
    ).apply(i, VillageLayout::new));

    /** A village with nothing but its office (founded by command). */
    public static VillageLayout ofOffice(BlockPos office) {
        return new VillageLayout(office.above(), office, Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), "oak", office, Direction.NORTH);
    }

    /** Has the village a real layout (built by the world plan) that it can grow in? */
    public boolean canGrow() {
        return !plots.isEmpty();
    }

    /** The same village with one more house on {@code plot}, with these beds in it. */
    public VillageLayout withHouse(BlockPos plot, List<BlockPos> newBeds, List<BlockPos> newWorkBlocks) {
        List<BlockPos> p = new ArrayList<>(plots);
        p.add(plot);
        List<BlockPos> b = new ArrayList<>(beds);
        b.addAll(newBeds);
        List<BlockPos> w = new ArrayList<>(workBlocks);
        w.addAll(newWorkBlocks);
        return new VillageLayout(square, office, pierTip, workshop, w, fields, pen, b, stockyard, piles, board, p, wood, origin, front);
    }
}
