package org.webtrade.minecraftportsmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.minecraftportsmod.nav.DimensionNavCache;
import org.webtrade.minecraftportsmod.nav.NavCacheManager;
import org.webtrade.minecraftportsmod.port.Dock;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;
import org.webtrade.minecraftportsmod.port.PortService;
import org.webtrade.minecraftportsmod.port.Route;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.route.RouteManager;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;

/**
 * /ports list | info &lt;id&gt; | rename &lt;id&gt; &lt;name&gt; | reroute [id] | register [radius] | dispatch &lt;from&gt; &lt;to&gt; | cache | rescan [radius]
 */
public final class PortsCommand {

    private PortsCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ports")
                .then(Commands.literal("list").executes(PortsCommand::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(PortsCommand::info)))
                .then(Commands.literal("rename")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(PortsCommand::rename))))
                .then(Commands.literal("reroute")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> reroute(ctx, -1))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> reroute(ctx, IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("register")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> register(ctx, 16))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> register(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("dispatch")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("from", IntegerArgumentType.integer(1))
                                .then(Commands.argument("to", IntegerArgumentType.integer(1)).executes(PortsCommand::dispatch))))
                .then(Commands.literal("addberth")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(PortsCommand::addBerth)))
                .then(Commands.literal("fleet")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(PortsCommand::fleet))
                .then(Commands.literal("transfer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("from", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    // every name ports or vessels were created under
                                    var server = ctx.getSource().getServer();
                                    java.util.Set<String> names = new java.util.TreeSet<>();
                                    PortData.get(server).ports().forEach(p -> { if (!p.ownerName().isEmpty()) names.add(p.ownerName()); });
                                    org.webtrade.minecraftportsmod.fleet.FleetData.get(server).all()
                                            .forEach(v -> { if (!v.ownerName().isEmpty()) names.add(v.ownerName()); });
                                    return net.minecraft.commands.SharedSuggestionProvider.suggest(names, builder);
                                })
                                .then(Commands.argument("to", net.minecraft.commands.arguments.EntityArgument.player())
                                        .executes(PortsCommand::transfer))))
                .then(Commands.literal("navmap")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("step", IntegerArgumentType.integer(1, 64))
                                                .executes(PortsCommand::navmap)))))
                .then(Commands.literal("cache")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(PortsCommand::cache))
                .then(Commands.literal("rescan")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> rescan(ctx, 8))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> rescan(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        PortData data = PortData.get(level.getServer());
        var ports = data.ports(level.dimension());
        if (ports.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("minecraftportsmod.command.no_ports"), false);
            return 0;
        }
        for (Port p : ports) {
            long ok = RouteManager.routesOf(data, p.id()).stream().filter(Route::isUsable).count();
            ctx.getSource().sendSuccess(() -> Component.literal("#" + p.id() + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(p.name()).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("  " + p.office().toShortString() + "  docks: " + data.docksOf(p.id()).size()
                            + "  routes: " + ok).withStyle(ChatFormatting.GRAY)), false);
        }
        return ports.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        PortData data = PortData.get(ctx.getSource().getServer());
        Port p = data.port(IntegerArgumentType.getInteger(ctx, "id"));
        if (p == null) {
            ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.command.unknown_port"));
            return 0;
        }
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("== " + p.name() + " (#" + p.id() + ") ==").withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.literal("office " + p.office().toShortString()
                + "  anchorage " + (p.anchorage() == null ? "-" : p.anchorage().toShortString())
                + "  owner " + p.ownerName()), false);
        for (Dock d : data.docksOf(p.id())) {
            src.sendSuccess(() -> Component.literal("  berth #" + d.id() + " " + d.berth().toShortString()
                    + (d.occupant() == null ? " free" : " occupied")).withStyle(ChatFormatting.GRAY), false);
        }
        for (Route r : RouteManager.routesOf(data, p.id())) {
            Port o = data.port(r.other(p.id()));
            String name = o == null ? "?" : o.name();
            src.sendSuccess(() -> Component.literal("  -> " + name + ": " + r.status().getSerializedName()
                    + (r.isUsable() ? " " + Math.round(r.length()) + " blocks, " + (r.waypoints().length / 2 - 1) + " legs" : "")
                    + (RouteManager.isCalculating(r) ? " (calculating)" : "")).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        PortData data = PortData.get(level.getServer());
        Port p = data.port(IntegerArgumentType.getInteger(ctx, "id"));
        if (p == null) {
            ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.command.unknown_port"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        boolean admin = Commands.LEVEL_GAMEMASTERS.check(ctx.getSource().permissions());
        if (!admin && (player == null || p.owner() == null || !p.owner().equals(player.getUUID()))) {
            ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.command.not_owner"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        PortService.renamePort(level, p, name);
        ctx.getSource().sendSuccess(() -> Component.translatable("minecraftportsmod.command.renamed", p.name()), false);
        return 1;
    }

    private static int reroute(CommandContext<CommandSourceStack> ctx, int portId) {
        int n = RouteManager.requestRecalculation(ctx.getSource().getServer(), portId, -1);
        ctx.getSource().sendSuccess(() -> Component.translatable("minecraftportsmod.command.rerouting", n), false);
        return n;
    }

    /** Registers port offices and mooring posts that were placed without a player (commands, structures, WorldEdit). */
    private static int register(CommandContext<CommandSourceStack> ctx, int radius) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos center = BlockPos.containing(ctx.getSource().getPosition());
        PortData data = PortData.get(level.getServer());
        java.util.List<BlockPos> offices = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            var state = level.getBlockState(pos);
            if (state.is(ModContent.PORT_OFFICE) && data.portAt(level.dimension(), pos) == null) offices.add(pos.immutable());
        }
        offices.forEach(pos -> PortService.createPort(level, pos, null, null));
        ctx.getSource().sendSuccess(() -> Component.literal("Registered " + offices.size() + " ports"), true);
        return offices.size();
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        PortData data = PortData.get(level.getServer());
        Port from = data.port(IntegerArgumentType.getInteger(ctx, "from"));
        Port to = data.port(IntegerArgumentType.getInteger(ctx, "to"));
        if (from == null || to == null) {
            ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.command.unknown_port"));
            return 0;
        }
        VesselRecord vessel = FleetManager.spawnTestVessel(level, from);
        if (vessel == null) {
            ctx.getSource().sendFailure(Component.literal("No free berth at " + from.name()));
            return 0;
        }
        var src = ctx.getSource();
        FleetManager.dispatch(level, vessel, to, ok -> src.sendSuccess(() -> Component.literal(ok
                ? "Dispatched " + vessel.name() + " " + from.name() + " -> " + to.name() + ", " + Math.round(vessel.voyage().totalLength()) + " blocks"
                : "No way from " + from.name() + " to " + to.name()), true));
        return 1;
    }

    private static int addBerth(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Port p = PortData.get(level.getServer()).port(IntegerArgumentType.getInteger(ctx, "id"));
        if (p == null) {
            ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.command.unknown_port"));
            return 0;
        }
        Dock d = PortService.addBerth(level, p);
        if (d == null) {
            ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.berth.no_space"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Berth #" + d.id() + " at " + d.berth().toShortString()), true);
        return 1;
    }

    private static int fleet(CommandContext<CommandSourceStack> ctx) {
        var all = org.webtrade.minecraftportsmod.fleet.FleetData.get(ctx.getSource().getServer()).all();
        for (VesselRecord v : all) {
            String body = FleetManager.live(v.id()) != null ? "body" : "virtual";
            String extra = v.voyage() == null ? "" : " -> port " + v.voyage().destPortId() + " " + Math.round(v.voyage().progress())
                    + "/" + Math.round(v.voyage().totalLength());
            ctx.getSource().sendSuccess(() -> Component.literal(v.name() + " [" + v.state().getSerializedName() + ", " + body + "] "
                    + String.format("%.1f %.1f", v.x(), v.z()) + " port=" + v.portId() + " dock=" + v.dockId() + extra), false);
        }
        return all.size();
    }

    private static int transfer(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String from = StringArgumentType.getString(ctx, "from");
        ServerPlayer to = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "to");
        int[] n = FleetManager.transfer(ctx.getSource().getServer(), from, to);
        ctx.getSource().sendSuccess(() -> Component.translatable("minecraftportsmod.command.transferred",
                n[0], n[1], from, to.getGameProfile().name()), true);
        return n[0] + n[1];
    }

    /** ASCII map of what the navigation cache knows: # water, ~ predicted water, . land, , predicted land, blank unknown. */
    private static int navmap(CommandContext<CommandSourceStack> ctx) {
        int x0 = IntegerArgumentType.getInteger(ctx, "x"), z0 = IntegerArgumentType.getInteger(ctx, "z");
        int step = IntegerArgumentType.getInteger(ctx, "step");
        var cache = NavCacheManager.get(ctx.getSource().getLevel());
        StringBuilder out = new StringBuilder();
        for (int j = -16; j <= 16; j++) {
            StringBuilder row = new StringBuilder();
            for (int i = -32; i <= 32; i++) {
                int x = x0 + i * step, z = z0 + j * step;
                var nav = cache.getChunk(x >> 4, z >> 4);
                char c;
                if (nav == null) c = ' ';
                else if (nav.isNavigable(x & 15, z & 15)) c = nav.isPredicted() ? '~' : '#';
                else c = nav.isPredicted() ? ',' : '.';
                if (i == 0 && j == 0) c = '@';
                row.append(c);
            }
            out.append(row).append('\n');
        }
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    private static int cache(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        DimensionNavCache cache = NavCacheManager.get(level);
        ctx.getSource().sendSuccess(() -> Component.literal("Nav cache " + level.dimension().identifier()
                + ": " + cache.cachedChunkCount() + " chunks in " + cache.loadedRegionCount() + " regions, "
                + NavCacheManager.pendingScans() + " pending scans, " + RouteManager.queuedJobs() + " route jobs"), false);
        return 1;
    }

    private static int rescan(CommandContext<CommandSourceStack> ctx, int radius) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        int n = NavCacheManager.queueRescan(level, pos, radius);
        ctx.getSource().sendSuccess(() -> Component.translatable("minecraftportsmod.command.rescanning", n), false);
        return n;
    }
}
