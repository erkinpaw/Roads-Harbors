package org.webtrade.minecraftportsmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.webtrade.minecraftportsmod.chart.ChartService;

public final class ModNetworking {

    private ModNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(ChartPayloads.OpenChart.TYPE, ChartPayloads.OpenChart.CODEC, 8 * 1024 * 1024);
        PayloadTypeRegistry.clientboundPlay().registerLarge(ChartPayloads.ChartTile.TYPE, ChartPayloads.ChartTile.CODEC, 256 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(ChartPayloads.RequestChart.TYPE, ChartPayloads.RequestChart.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChartPayloads.RequestTiles.TYPE, ChartPayloads.RequestTiles.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChartPayloads.FleetAction.TYPE, ChartPayloads.FleetAction.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChartPayloads.Rename.TYPE, ChartPayloads.Rename.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(SettlementPayloads.View.TYPE, SettlementPayloads.View.CODEC, 1024 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(SettlementPayloads.Request.TYPE, SettlementPayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(SettlementPayloads.News.TYPE, SettlementPayloads.News.CODEC, 512 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(SettlementPayloads.RequestNews.TYPE, SettlementPayloads.RequestNews.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(MarketPayloads.View.TYPE, MarketPayloads.View.CODEC, 256 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(MarketPayloads.Action.TYPE, MarketPayloads.Action.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(ColonyPayloads.VillageView.TYPE, ColonyPayloads.VillageView.CODEC, 2 * 1024 * 1024);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.SiteView.TYPE, ColonyPayloads.SiteView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.PersonView.TYPE, ColonyPayloads.PersonView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.QuestView.TYPE, ColonyPayloads.QuestView.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ColonyPayloads.RequestVillage.TYPE, ColonyPayloads.RequestVillage.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ColonyPayloads.SiteAction.TYPE, ColonyPayloads.SiteAction.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.BuildingView.TYPE, ColonyPayloads.BuildingView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.TradeView.TYPE, ColonyPayloads.TradeView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.OrderView.TYPE, ColonyPayloads.OrderView.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(ColonyPayloads.MapView.TYPE, ColonyPayloads.MapView.CODEC, 8 * 1024 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(ColonyPayloads.VillageAction.TYPE, ColonyPayloads.VillageAction.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(WorldMapPayloads.View.TYPE, WorldMapPayloads.View.CODEC, 8 * 1024 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(WorldMapPayloads.Request.TYPE, WorldMapPayloads.Request.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WorldMapPayloads.MarkAction.TYPE, WorldMapPayloads.MarkAction.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WorldMapPayloads.ShotPart.TYPE, WorldMapPayloads.ShotPart.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WorldMapPayloads.ShotRequest.TYPE, WorldMapPayloads.ShotRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(WorldMapPayloads.ShotData.TYPE, WorldMapPayloads.ShotData.CODEC, 2 * 1024 * 1024);

        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.PanelView.TYPE, ColonyPayloads.PanelView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ColonyPayloads.BadgeView.TYPE, ColonyPayloads.BadgeView.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ColonyPayloads.RequestPanel.TYPE, ColonyPayloads.RequestPanel.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ColonyPayloads.RequestPanel.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.colony.Wallet.panel(ctx.player()));
        PayloadTypeRegistry.serverboundPlay().register(CombatPayloads.ShipOrder.TYPE, CombatPayloads.ShipOrder.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CombatPayloads.ShipOrder.TYPE, (payload, ctx) -> {
            if (ctx.player().getVehicle() instanceof org.webtrade.minecraftportsmod.combat.WarshipEntity ship) {
                ship.order(ctx.player(), payload.sails(), payload.rudder(), payload.fire(), payload.elevation());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.RequestChart.TYPE, (payload, ctx) -> {
            if (payload.refresh()) ChartService.refresh(ctx.player());
            else ChartService.openFromKey(ctx.player());
        });
        ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.RequestTiles.TYPE,
                (payload, ctx) -> ChartService.sendTiles(ctx.player(), payload.tiles()));
        ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.Rename.TYPE,
                (payload, ctx) -> ChartService.handleRename(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(MarketPayloads.Action.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.village.MarketService.handle(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SettlementPayloads.RequestNews.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.economy.TownHallService.sendNews(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(SettlementPayloads.Request.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.economy.TownHallService.send(ctx.player(), payload.portId()));
        ServerPlayNetworking.registerGlobalReceiver(ColonyPayloads.RequestVillage.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.colony.ColonyService.sendVillage(ctx.player(), payload.village(), payload.map()));
        ServerPlayNetworking.registerGlobalReceiver(ColonyPayloads.VillageAction.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.colony.ColonyService.handleAction(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ColonyPayloads.SiteAction.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.colony.ColonyService.handleSite(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(WorldMapPayloads.Request.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.chart.WorldMapService.send(ctx.player(), true));
        ServerPlayNetworking.registerGlobalReceiver(WorldMapPayloads.MarkAction.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.chart.WorldMapService.handle(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(WorldMapPayloads.ShotPart.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.chart.WorldMapService.part(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(WorldMapPayloads.ShotRequest.TYPE,
                (payload, ctx) -> org.webtrade.minecraftportsmod.chart.WorldMapService.sendShot(ctx.player(), payload.id()));
        ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.FleetAction.TYPE,
                (payload, ctx) -> ChartService.handleAction(ctx.player(), payload));
    }
}
