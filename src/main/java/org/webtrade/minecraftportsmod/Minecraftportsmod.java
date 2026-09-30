package org.webtrade.minecraftportsmod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webtrade.minecraftportsmod.command.PortsCommand;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.network.ModNetworking;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.route.RouteManager;

public class Minecraftportsmod implements ModInitializer {

    public static final String MOD_ID = "minecraftportsmod";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ports&Routes");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModContent.register();
        // a little wheat falls from leaves, now and then: a camp's first thatch can come from the woods
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY.register((key, table, source, registries) -> {
            var id = key.identifier();
            if (!source.isBuiltin() || !id.getNamespace().equals("minecraft") || !id.getPath().startsWith("blocks/")
                    || !id.getPath().endsWith("_leaves")) return;
            table.withPool(net.minecraft.world.level.storage.loot.LootPool.lootPool()
                    .add(net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(net.minecraft.world.item.Items.WHEAT))
                    .when(net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition.randomChance(0.05F)));
        });
        ModNetworking.registerCommon();
        NavCacheManager.init();
        RouteManager.init();
        FleetManager.init();
        org.webtrade.minecraftportsmod.economy.EconomyManager.init();
        org.webtrade.minecraftportsmod.worldgen.WorldPlanner.init();
        org.webtrade.minecraftportsmod.colony.VillageManager.init();
        PortsCommand.register();
        org.webtrade.minecraftportsmod.command.VillageCommand.register();
        org.webtrade.minecraftportsmod.command.ColonyCommand.register();
        LOGGER.info("Ports & Routes initialized");
    }
}
