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
