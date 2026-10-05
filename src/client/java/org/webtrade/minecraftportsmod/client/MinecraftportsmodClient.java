package org.webtrade.minecraftportsmod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.webtrade.minecraftportsmod.client.chart.ChartScreen;
import org.webtrade.minecraftportsmod.client.chart.ChartTileCache;
import org.webtrade.minecraftportsmod.client.chart.MinecraftportsmodKeys;
import org.webtrade.minecraftportsmod.client.render.VesselRenderer;
import org.webtrade.minecraftportsmod.network.ChartPayloads;
import org.webtrade.minecraftportsmod.registry.ModContent;

public class MinecraftportsmodClient implements ClientModInitializer {

    /** Ticks until a screenshot for the map is taken (-1: none coming), and whether the interface was hidden before. */
    private static int shotIn = -1;
    private static boolean shotHud;

    @Override
    public void onInitializeClient() {
        // boats look like the boat they were built from; upgraded hulls get their own ship models
        VesselRenderer.registerLayers();
        org.webtrade.minecraftportsmod.client.render.WarshipRenderer.registerLayers();
        EntityRendererRegistry.register(ModContent.VESSEL, VesselRenderer::new);
        EntityRendererRegistry.register(ModContent.VESSEL_DECK, net.minecraft.client.renderer.entity.NoopRenderer::new);
        EntityRendererRegistry.register(ModContent.RESIDENT, org.webtrade.minecraftportsmod.client.render.ResidentRenderer::new);
        EntityRendererRegistry.register(ModContent.WARSHIP, org.webtrade.minecraftportsmod.client.render.WarshipRenderer::new);
        EntityRendererRegistry.register(ModContent.GALLEON, org.webtrade.minecraftportsmod.client.render.WarshipRenderer::new);
        EntityRendererRegistry.register(ModContent.SHIP_OF_THE_LINE, org.webtrade.minecraftportsmod.client.render.WarshipRenderer::new);
        EntityRendererRegistry.register(ModContent.CANNONBALL, org.webtrade.minecraftportsmod.client.render.CannonballRenderer::new);
        EntityRendererRegistry.register(ModContent.SAILOR, org.webtrade.minecraftportsmod.client.render.SailorRenderer::new);
        EntityRendererRegistry.register(ModContent.MUSKET_BALL, net.minecraft.client.renderer.entity.NoopRenderer::new);
        EntityRendererRegistry.register(ModContent.TRADE_SHIP, org.webtrade.minecraftportsmod.client.render.TradeShipRenderer::new);
        CombatClient.init();

        KeyMappingHelper.registerKeyMapping(MinecraftportsmodKeys.CHART);
        KeyMappingHelper.registerKeyMapping(MinecraftportsmodKeys.MAP_SHOT);
        net.minecraft.client.gui.screens.MenuScreens.register(ModContent.HOLD_MENU,
                org.webtrade.minecraftportsmod.client.chart.HoldScreen::new);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (MinecraftportsmodKeys.MAP_SHOT.consumeClick()) {
                // (the game's interface hidden for the frame the picture is taken from, as with F1)
                if (client.player != null && client.gui.screen() == null && shotIn < 0) {
                    shotHud = client.gui.hud.isHidden();
                    if (!shotHud) client.gui.hud.toggle();
                    shotIn = 2;
                }
            }
            if (shotIn > 0 && --shotIn == 0) {
                shotIn = -1;
                net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), img -> client.execute(() -> {
                    if (!shotHud && client.gui.hud.isHidden()) client.gui.hud.toggle();
                    org.webtrade.minecraftportsmod.client.chart.Shots.send(img, -1);
                }));
            }
            while (MinecraftportsmodKeys.CHART.consumeClick()) {
                if (client.player != null && client.gui.screen() == null
                        && ClientPlayNetworking.canSend(ChartPayloads.RequestChart.TYPE)) {
                    ClientPlayNetworking.send(new ChartPayloads.RequestChart(false));
                }
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.WorldMapPayloads.View.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.WorldMapScreen map) {
                map.update(payload);
            } else if (!payload.refresh() && ctx.client().gui.screen() == null) {
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.WorldMapScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(ChartPayloads.OpenChart.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof ChartScreen open) {
                open.update(payload);
            } else if (!payload.refresh()) {
                ctx.client().gui.setScreen(new ChartScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.SettlementPayloads.View.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.TownHallScreen hall) hall.update(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.SettlementPayloads.News.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.NewsScreen news) news.update(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.MarketPayloads.View.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.MarketScreen market) {
                market.update(payload);
            } else if (!payload.refresh()) {
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.MarketScreen(payload));
            }
        });
        // the villages: the board, a building site, a resident
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.VillageView.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.VillageScreen vs) {
                vs.update(payload);
            } else if (ctx.client().gui.screen() == null) {
                var vs = new org.webtrade.minecraftportsmod.client.chart.VillageScreen(null, payload.id());
                ctx.client().gui.setScreen(vs);
                vs.update(payload);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.SiteView.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof org.webtrade.minecraftportsmod.client.chart.SiteScreen ss) {
                if (ss.shows(payload)) ss.update(payload);
            } else if (ctx.client().gui.screen() == null) {
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.SiteScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.BuildingView.TYPE, (payload, ctx) -> {
            var screen = ctx.client().gui.screen();
            if (screen instanceof org.webtrade.minecraftportsmod.client.chart.BuildingScreen bs && bs.shows(payload)) {
                bs.update(payload);
            } else if (screen == null || screen instanceof org.webtrade.minecraftportsmod.client.chart.VillageScreen) {
                // from the name plate by the door, or from the development tree (back leads there)
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.BuildingScreen(screen, payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.TradeView.TYPE, (payload, ctx) -> {
            var screen = ctx.client().gui.screen();
            if (screen instanceof org.webtrade.minecraftportsmod.client.chart.TradeScreen ts && ts.shows(payload)) ts.update(payload);
            else if (screen == null) ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.TradeScreen(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.OrderView.TYPE, (payload, ctx) -> {
            var screen = ctx.client().gui.screen();
            if (screen instanceof org.webtrade.minecraftportsmod.client.chart.OrderScreen os && os.shows(payload)) os.update(payload);
            // (another building's orders open: this one's instead)
            else if ((screen == null || screen instanceof org.webtrade.minecraftportsmod.client.chart.OrderScreen)
                    && !org.webtrade.minecraftportsmod.client.chart.OrderScreen.justClosed(payload))
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.OrderScreen(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.MapView.TYPE, (payload, ctx) -> {
            var screen = ctx.client().gui.screen();
            if (screen instanceof org.webtrade.minecraftportsmod.client.chart.ScoutMapScreen ms && ms.shows(payload)) ms.update(payload);
            else if (screen == null) ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.ScoutMapScreen(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.QuestView.TYPE, (payload, ctx) -> {
            var screen = ctx.client().gui.screen();
            if (screen instanceof org.webtrade.minecraftportsmod.client.chart.QuestScreen qs && qs.shows(payload)) qs.update(payload);
            else if (screen == null || screen instanceof org.webtrade.minecraftportsmod.client.chart.PersonScreen
                    || screen instanceof org.webtrade.minecraftportsmod.client.chart.QuestScreen)
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.QuestScreen(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.ColonyPayloads.PersonView.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() == null) {
                ctx.client().gui.setScreen(new org.webtrade.minecraftportsmod.client.chart.PersonScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(ChartPayloads.ChartTile.TYPE, (payload, ctx) -> ChartTileCache.accept(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ChartTileCache.clear();
            org.webtrade.minecraftportsmod.client.chart.Shots.clear();
        }));
        ClientPlayNetworking.registerGlobalReceiver(org.webtrade.minecraftportsmod.network.WorldMapPayloads.ShotData.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.client.chart.Shots.received(payload));
    }
}
