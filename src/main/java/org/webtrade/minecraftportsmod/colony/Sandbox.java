package org.webtrade.minecraftportsmod.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.util.Map;

/**
 * A test village, set up at once where a player stands: every workshop at its top level (two joiner's, two
 * farmyards with herds of their own), homes, stores, the people of every trade and a store full of what they work
 * with; every node of the tree open. To see the queues, the bars and the tools at work without waiting for a village
 * to grow (the command "/village sandbox").
 */
public final class Sandbox {

    private Sandbox() {
    }

    /** What goes up, in this order (where there is room): the stores, the workshops, the farms, the homes. */
    private static final BuildingType[] BUILDINGS = {
            BuildingType.STOREHOUSE, BuildingType.STOREHOUSE_2, BuildingType.SMITHY, BuildingType.SAWMILL, BuildingType.CARPENTER,
            BuildingType.CARPENTER, BuildingType.LOCKSMITH, BuildingType.WEAVER, BuildingType.SMELTER, BuildingType.GLASSWORKS,
            BuildingType.MARKET, BuildingType.WOOD_HUT, BuildingType.MINE_HOUSE, BuildingType.FIELD, BuildingType.FARM, BuildingType.FARMYARD,
            BuildingType.FARMYARD, BuildingType.HOUSE, BuildingType.HOUSE, BuildingType.HOUSE_TALL, BuildingType.HOUSE_TALL, BuildingType.STONE_HOUSE,
            BuildingType.STONE_HOUSE};

    /** The people: so many of each trade. */
    private static final Map<Job, Integer> PEOPLE = Map.ofEntries(Map.entry(Job.SMITH, 1), Map.entry(Job.SAWYER, 1), Map.entry(Job.JOINER, 2),
            Map.entry(Job.LOCKSMITH, 1), Map.entry(Job.WEAVER, 1), Map.entry(Job.SMELTER, 1), Map.entry(Job.GLASSBLOWER, 1),
            Map.entry(Job.MERCHANT, 1), Map.entry(Job.WOODCUTTER, 2), Map.entry(Job.MINER, 2), Map.entry(Job.FARMER, 2), Map.entry(Job.HERDER, 2));

    public static Village build(ServerLevel level, BlockPos at, Direction facing, String name, boolean russian) {
        Village v = VillageManager.foundCamp(level, at.relative(facing, 10), facing, name, russian, "oak");
        VillageData data = VillageData.get(level.getServer());
        for (BuildingType t : BuildingType.values()) if (t.isNode()) v.unlocked.add(t);
        int herds = 0;
        for (BuildingType t : BUILDINGS) {
            Blueprint.Frame f = PlotFinder.find(level, v, t, -1);
            if (f == null) {
                Minecraftportsmod.LOGGER.info("[sandbox] no room for {}: {}", t.id(), PlotFinder.WHY);
                continue;
            }
            Building b = new Building(v.nextBuilding++, t, f.origin(), f.front(), level.getRandom().nextLong()).look(VillageLife.look(v));
            b.level = t.maxLevel;
            if (t == BuildingType.FARMYARD && herds++ == 1) b.setOption("herd:2;");
            b.state = Building.State.BUILT;
            b.work = b.blueprint(v.wood).pieces.size();
            b.created = data.day;
            v.buildings.add(b);
            Construction.advance(level, v, b, Integer.MAX_VALUE);
        }
        for (var e : PEOPLE.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                Dweller d = VillageLife.addPerson(v, data.day, false);
                d.job = e.getKey();
            }
        }
        VillageLife.rehouse(v, data.day);
        // a store full of what the trades work with
        for (Res r : Res.values()) {
            int n = switch (r) {
                case FOOD, WOOD, STONE, PLANKS -> 500;
                case STICKS, WHEAT -> 300;
                case IRON, COAL, WOOL -> 120;
                case LEATHER, GLASS -> 60;
                case TOOLS1, TOOLS2, TOOLS3 -> 20;
                default -> 0;
            };
            if (n > 0) v.add(r, Math.min(n, v.capacity(r)));
        }
        v.emeralds = 200;
        data.changed();
        Minecraftportsmod.LOGGER.info("[sandbox] {} (#{}): {} buildings, {} people", v.name, v.id, v.buildings.size(), v.population());
        return v;
    }
}
