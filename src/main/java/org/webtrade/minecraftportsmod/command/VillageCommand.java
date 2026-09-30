package org.webtrade.minecraftportsmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.webtrade.minecraftportsmod.economy.EconomyManager;
import org.webtrade.minecraftportsmod.economy.Good;
import org.webtrade.minecraftportsmod.economy.Settlement;
import org.webtrade.minecraftportsmod.economy.SettlementData;
import org.webtrade.minecraftportsmod.economy.Specialization;
import org.webtrade.minecraftportsmod.economy.TradeRun;
import org.webtrade.minecraftportsmod.fleet.FleetManager;
import org.webtrade.minecraftportsmod.fleet.VesselRecord;
import org.webtrade.minecraftportsmod.port.Port;
import org.webtrade.minecraftportsmod.port.PortData;

import java.util.Arrays;
import java.util.Locale;

/**
 * Admin tools for settlements (stage 1: they are founded by hand, world generation comes later):
 * /village found &lt;spec&gt; [port] | remove &lt;port&gt; | list | info &lt;port&gt; | log &lt;port&gt; | runs | step [days] | daylength &lt;ticks&gt;
 */
public final class VillageCommand {

    private VillageCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("settlement")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("found")
                        .then(Commands.argument("spec", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(Specialization.values()).map(Specialization::id), b))
                                .executes(ctx -> found(ctx, -1))
                                .then(Commands.argument("port", IntegerArgumentType.integer(1))
                                        .executes(ctx -> found(ctx, IntegerArgumentType.getInteger(ctx, "port"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1)).executes(VillageCommand::remove)))
                .then(Commands.literal("list").executes(VillageCommand::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1)).executes(VillageCommand::info)))
                .then(Commands.literal("log")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1)).executes(VillageCommand::log)))
                .then(Commands.literal("runs").executes(VillageCommand::runs))
                .then(Commands.literal("step")
                        .executes(ctx -> step(ctx, 1))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> step(ctx, IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("plan")
                        .executes(VillageCommand::plan)
                        .then(Commands.literal("on").executes(ctx -> planMode(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> planMode(ctx, false))))
                .then(Commands.literal("sites").executes(VillageCommand::sites))
                .then(Commands.literal("probe").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                            String r = org.webtrade.minecraftportsmod.worldgen.WorldPlanner.probe(
                                    IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"));
                            ctx.getSource().sendSuccess(() -> Component.literal(r), false);
                            return 1;
                        }))))
                .then(Commands.literal("residents")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1))
                                .executes(ctx -> residents(ctx, 6))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                        .executes(ctx -> residents(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("event")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(org.webtrade.minecraftportsmod.economy.EventType.values())
                                                        .map(org.webtrade.minecraftportsmod.economy.EventType::id), b))
                                        .executes(VillageCommand::event))))
                .then(Commands.literal("daylength")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(200, 240000)).executes(VillageCommand::dayLength))));
    }

    private static int event(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer srv = ctx.getSource().getServer();
        Settlement s = SettlementData.get(srv).get(IntegerArgumentType.getInteger(ctx, "port"));
        String id = StringArgumentType.getString(ctx, "type");
        var type = Arrays.stream(org.webtrade.minecraftportsmod.economy.EventType.values()).filter(t -> t.id().equals(id)).findFirst().orElse(null);
        if (s == null || type == null) {
            ctx.getSource().sendFailure(Component.literal("unknown port or event"));
            return 0;
        }
        EconomyManager.startEvent(srv, s, type);
        ctx.getSource().sendSuccess(() -> Component.literal("event " + id + " in #" + s.id()), true);
        return 1;
    }

    private static int found(CommandContext<CommandSourceStack> ctx, int portId) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer srv = src.getServer();
        Specialization spec = Specialization.byId(StringArgumentType.getString(ctx, "spec"));
        if (spec == null) {
            src.sendFailure(Component.translatable("minecraftportsmod.command.unknown_spec"));
            return 0;
        }
        PortData ports = PortData.get(srv);
        Port port = portId > 0 ? ports.port(portId)
                : ports.nearestPort(src.getLevel().dimension(), BlockPos.containing(src.getPosition()), 256);
        if (port == null) {
            src.sendFailure(Component.translatable("minecraftportsmod.command.unknown_port"));
            return 0;
        }
        Settlement s = EconomyManager.found(srv, port, spec);
        if (s == null) {
            src.sendFailure(Component.translatable("minecraftportsmod.command.already_settled", port.name()));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("minecraftportsmod.command.founded", port.name(), spec.displayName(), s.population())
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static Settlement settlement(CommandContext<CommandSourceStack> ctx) {
        Settlement s = SettlementData.get(ctx.getSource().getServer()).get(IntegerArgumentType.getInteger(ctx, "port"));
        if (s == null) ctx.getSource().sendFailure(Component.translatable("minecraftportsmod.command.no_settlement"));
        return s;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        Settlement s = settlement(ctx);
        if (s == null) return 0;
        EconomyManager.remove(ctx.getSource().getServer(), s.id());
        ctx.getSource().sendSuccess(() -> Component.literal("removed #" + s.id()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer srv = ctx.getSource().getServer();
        SettlementData data = SettlementData.get(srv);
        ctx.getSource().sendSuccess(() -> Component.literal("day " + data.day() + " (" + data.dayTicks() + "/" + data.dayLength() + ")")
                .withStyle(ChatFormatting.GRAY), false);
        for (Settlement s : data.all()) {
            ctx.getSource().sendSuccess(() -> Component.literal("#" + s.id() + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(EconomyManager.name(srv, s)).withStyle(ChatFormatting.GOLD))
                    .append(" ").append(s.spec().displayName())
                    .append(Component.literal(String.format(Locale.ROOT, "  pop %d  houses %d  mood %.2f  food %.2f  treasury %.1f",
                            s.population(), s.houses(), s.happiness(), s.need("food"), s.treasury())).withStyle(ChatFormatting.GRAY)), false);
        }
        return data.all().size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        Settlement s = settlement(ctx);
        if (s == null) return 0;
        CommandSourceStack src = ctx.getSource();
        MinecraftServer srv = src.getServer();
        src.sendSuccess(() -> Component.literal("== " + EconomyManager.name(srv, s) + " ==").withStyle(ChatFormatting.GOLD), false);
        StringBuilder jobs = new StringBuilder();
        s.workers().forEach((p, n) -> jobs.append(p.id()).append('=').append(n).append(' '));
        src.sendSuccess(() -> Component.literal("workers: " + jobs), false);
        StringBuilder needs = new StringBuilder();
        for (String n : new String[]{"food", "fuel", "tools", "luxury", "housing", "productivity"}) {
            needs.append(n).append(String.format(Locale.ROOT, "=%.2f ", s.need(n)));
        }
        src.sendSuccess(() -> Component.literal("needs: " + needs), false);
        for (Good g : Good.values()) {
            double stock = s.stock(g), prod = s.produced(g), cons = s.consumed(g);
            if (stock < 0.5 && prod < 0.05 && cons < 0.05) continue;
            src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %-15s stock %7.1f  +%6.1f  -%6.1f  demand %6.1f  price %.3f",
                    g.id(), stock, prod, cons, s.demand(g), EconomyManager.price(s, g))), false);
        }
        return 1;
    }

    private static int log(CommandContext<CommandSourceStack> ctx) {
        Settlement s = settlement(ctx);
        if (s == null) return 0;
        var entries = s.log();
        for (int i = Math.min(entries.size(), 15) - 1; i >= 0; i--) {
            var e = entries.get(i);
            ctx.getSource().sendSuccess(() -> Component.literal("[" + e.day() + "] ").withStyle(ChatFormatting.GRAY).append(e.text()), false);
        }
        return entries.size();
    }

    private static int runs(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer srv = ctx.getSource().getServer();
        SettlementData data = SettlementData.get(srv);
        for (TradeRun r : data.runs()) {
            VesselRecord v = FleetManager.record(srv, r.vessel());
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s  #%d -> #%d  %s  cargo %.0f  vessel %s",
                    v == null ? "?" : v.name(), r.home(), r.partner(), r.phase().getSerializedName(), r.cargoUnits(),
                    v == null ? "-" : v.state().getSerializedName())), false);
        }
        return data.runs().size();
    }

    private static int step(CommandContext<CommandSourceStack> ctx, int days) {
        MinecraftServer srv = ctx.getSource().getServer();
        EconomyManager.advanceDay(srv, SettlementData.get(srv), days);
        ctx.getSource().sendSuccess(() -> Component.literal("advanced " + days + " day(s)"), true);
        return days;
    }

    private static int plan(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer srv = ctx.getSource().getServer();
        var plan = org.webtrade.minecraftportsmod.worldgen.WorldPlan.get(srv);
        long built = plan.sites().stream().filter(s -> s.state() == org.webtrade.minecraftportsmod.worldgen.WorldPlan.State.BUILT).count();
        long failed = plan.sites().stream().filter(s -> s.state() == org.webtrade.minecraftportsmod.worldgen.WorldPlan.State.FAILED).count();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "world plan: %s  cells %d (+%d queued)  sites %d (built %d, failed %d)  lanes %d (+%d queued)  predicted %d (+%d)  routes ok %d  building now %d",
                plan.enabled() ? "on" : "off", plan.cellCount(), org.webtrade.minecraftportsmod.worldgen.WorldPlanner.queuedCells(),
                plan.sites().size(), built, failed, plan.lanes().size(), org.webtrade.minecraftportsmod.worldgen.WorldPlanner.queuedLanes(),
                plan.predictedCount(), org.webtrade.minecraftportsmod.worldgen.WorldPlanner.queuedPredictions(),
                PortData.get(srv).routes().stream().filter(org.webtrade.minecraftportsmod.port.Route::isUsable).count(),
                org.webtrade.minecraftportsmod.worldgen.WorldPlanner.jobs())), false);
        return plan.sites().size();
    }

    private static int planMode(CommandContext<CommandSourceStack> ctx, boolean on) {
        org.webtrade.minecraftportsmod.worldgen.WorldPlanner.setEnabled(ctx.getSource().getServer(), on);
        ctx.getSource().sendSuccess(() -> Component.literal("world plan " + (on ? "on" : "off")), true);
        return 1;
    }

    private static int sites(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer srv = ctx.getSource().getServer();
        var plan = org.webtrade.minecraftportsmod.worldgen.WorldPlan.get(srv);
        for (var s : plan.sites()) {
            Settlement st = s.portId() >= 0 ? SettlementData.get(srv).get(s.portId()) : null;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "#%d %s  %d, %d  %s%s%s", s.id, s.name, s.x, s.z,
                    s.state().getSerializedName(), s.river ? "  river" : "", st == null ? "" : "  " + st.spec().id() + " pop " + st.population())), false);
        }
        return plan.sites().size();
    }

    private static int residents(CommandContext<CommandSourceStack> ctx, int count) {
        Settlement s = settlement(ctx);
        if (s == null) return 0;
        MinecraftServer srv = ctx.getSource().getServer();
        Port port = PortData.get(srv).port(s.portId());
        var level = srv.getLevel(port.dimension());
        boolean ru = org.webtrade.minecraftportsmod.fleet.Names.cyrillic(port.name());
        if (s.layout() == null) s.setLayout(org.webtrade.minecraftportsmod.village.VillageLayout.ofOffice(port.office()));
        int n = org.webtrade.minecraftportsmod.village.Residents.spawn(level, s, count, ru, net.minecraft.util.RandomSource.create());
        ctx.getSource().sendSuccess(() -> Component.literal("spawned " + n + " residents"), true);
        return n;
    }

    private static int dayLength(CommandContext<CommandSourceStack> ctx) {
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        EconomyManager.setDayLength(ctx.getSource().getServer(), ticks);
        ctx.getSource().sendSuccess(() -> Component.literal("economy day = " + ticks + " ticks"), true);
        return ticks;
    }
}
