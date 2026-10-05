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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.webtrade.minecraftportsmod.colony.Building;
import org.webtrade.minecraftportsmod.colony.Dweller;
import org.webtrade.minecraftportsmod.colony.Res;
import org.webtrade.minecraftportsmod.colony.Village;
import org.webtrade.minecraftportsmod.colony.VillageData;
import org.webtrade.minecraftportsmod.colony.VillageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /village: the villages (admin and testing). */
public final class ColonyCommand {

    private ColonyCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("village")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(ColonyCommand::list))
                .then(Commands.literal("info").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ColonyCommand::info)))
                .then(Commands.literal("camp").executes(ColonyCommand::camp))
                .then(Commands.literal("sandbox").executes(ColonyCommand::sandbox))
                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ColonyCommand::remove)))
                .then(Commands.literal("tp").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ColonyCommand::tp)))
                .then(Commands.literal("day")
                        .executes(ctx -> day(ctx, 1))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 100)).executes(ctx -> day(ctx, IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("daylength")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(200, 240000)).executes(ColonyCommand::dayLength)))
                .then(Commands.literal("trail").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("other", IntegerArgumentType.integer(1)).executes(ctx -> {
                            var srv = ctx.getSource().getServer();
                            Village v = village(ctx);
                            Village o = org.webtrade.minecraftportsmod.colony.VillageData.get(srv).get(IntegerArgumentType.getInteger(ctx, "other"));
                            if (v == null || o == null || v == o) {
                                ctx.getSource().sendFailure(Component.literal("no such villages"));
                                return 0;
                            }
                            VillageManager.meet(srv, v, o);
                            ctx.getSource().sendSuccess(() -> Component.literal("trail " + v.id + "-" + o.id + " being worked out"), true);
                            return 1;
                        }))))
                .then(Commands.literal("sub").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("sub", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(org.webtrade.minecraftportsmod.colony.BuildingType.Sub.values())
                                        .map(org.webtrade.minecraftportsmod.colony.BuildingType.Sub::id), b))
                                .executes(ctx -> {
                                    Village v = village(ctx);
                                    var sub = org.webtrade.minecraftportsmod.colony.BuildingType.Sub.byId(StringArgumentType.getString(ctx, "sub"));
                                    if (v == null || sub == null) return 0;
                                    VillageManager.sub(ctx.getSource().getServer(), v, sub);
                                    ctx.getSource().sendSuccess(() -> Component.literal(v.name + ": " + sub.id() + " (" + sub.branch.id() + ")"), true);
                                    return 1;
                                }))))
                .then(Commands.literal("trade")
                        .then(Commands.literal("on").executes(ctx -> {
                            org.webtrade.minecraftportsmod.colony.Caravans.enabled = true;
                            ctx.getSource().sendSuccess(() -> Component.literal("merchants trade between villages"), true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            org.webtrade.minecraftportsmod.colony.Caravans.enabled = false;
                            ctx.getSource().sendSuccess(() -> Component.literal("no trade between villages (merchants stay home)"), true);
                            return 1;
                        })))
                .then(Commands.literal("roads")
                        .executes(ctx -> {
                            var data = org.webtrade.minecraftportsmod.colony.VillageData.get(ctx.getSource().getServer());
                            if (data.works().isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("no trails being made"), false);
                            for (var w : data.works()) {
                                String line = "#" + w.a + " - #" + w.b + ": " + (int) w.length() + " blocks, planned day " + w.planned + ", started "
                                        + w.started() + ", finished " + w.finished() + " | #" + w.a + " crew " + w.sideA().crew() + " state "
                                        + w.sideA().state() + " made " + (int) w.sideA().done() + " | #" + w.b + " crew " + w.sideB().crew() + " state "
                                        + w.sideB().state() + " made " + (int) w.sideB().done();
                                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            return 1;
                        })
                        .then(Commands.literal("finish").executes(ctx -> {
                            // (the ways as they are planned, all made at once: for looking at the ways themselves)
                            org.webtrade.minecraftportsmod.colony.Trails.finishAll(org.webtrade.minecraftportsmod.colony.VillageData.get(ctx.getSource().getServer()));
                            ctx.getSource().sendSuccess(() -> Component.literal("all trails made"), true);
                            return 1;
                        })))
                .then(Commands.literal("give")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("res", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Res.values()).map(Res::id), b))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(-10000, 10000)).executes(ColonyCommand::give)))))
                .then(Commands.literal("baby").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
                    Village v = village(ctx);
                    if (v == null) return 0;
                    Dweller d = VillageManager.baby(ctx.getSource().getServer(), v);
                    ctx.getSource().sendSuccess(() -> Component.literal("born: " + d.name), true);
                    return 1;
                })))
                .then(Commands.literal("build").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(org.webtrade.minecraftportsmod.colony.BuildingType.values())
                                        .map(org.webtrade.minecraftportsmod.colony.BuildingType::id), b))
                                .executes(ctx -> {
                                    Village v = village(ctx);
                                    var type = org.webtrade.minecraftportsmod.colony.BuildingType.byId(StringArgumentType.getString(ctx, "type"));
                                    if (v == null || type == null) return 0;
                                    Building b = VillageManager.buildNow(ctx.getSource().getLevel(), v, type);
                                    if (b == null) {
                                        ctx.getSource().sendFailure(Component.literal("no room"));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("built " + type.id() + " at " + b.origin.toShortString()), true);
                                    return 1;
                                })
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 3)).executes(ctx -> {
                                    Village v = village(ctx);
                                    var type = org.webtrade.minecraftportsmod.colony.BuildingType.byId(StringArgumentType.getString(ctx, "type"));
                                    if (v == null || type == null) return 0;
                                    Building b = VillageManager.buildNow(ctx.getSource().getLevel(), v, type, IntegerArgumentType.getInteger(ctx, "level"));
                                    if (b == null) {
                                        ctx.getSource().sendFailure(Component.literal("no room"));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("built " + type.id() + " L" + b.level() + " at " + b.origin.toShortString()), true);
                                    return 1;
                                })))))
                .then(Commands.literal("unlock").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(org.webtrade.minecraftportsmod.colony.BuildingType.values())
                                        .map(org.webtrade.minecraftportsmod.colony.BuildingType::id), b))
                                .executes(ctx -> {
                                    Village v = village(ctx);
                                    var type = org.webtrade.minecraftportsmod.colony.BuildingType.byId(StringArgumentType.getString(ctx, "type"));
                                    if (v == null || type == null) return 0;
                                    boolean ok = VillageManager.unlock(ctx.getSource().getServer(), v, type, true);
                                    ctx.getSource().sendSuccess(() -> Component.literal((ok ? "unlocked " : "could not unlock ") + type.id()), true);
                                    return ok ? 1 : 0;
                                }))))
                .then(Commands.literal("focus").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("branch", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"mine", "wood", "fish", "farm", "trade"}, b))
                                .executes(ctx -> {
                                    Village v = village(ctx);
                                    var br = org.webtrade.minecraftportsmod.colony.BuildingType.Branch.byId(StringArgumentType.getString(ctx, "branch"));
                                    if (v == null || br == null || !br.trade()) return 0;
                                    VillageManager.focus(ctx.getSource().getServer(), v, br);
                                    ctx.getSource().sendSuccess(() -> Component.literal("focus " + br.id()), true);
                                    return 1;
                                }))))
                .then(Commands.literal("raise").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("building", IntegerArgumentType.integer(1)).executes(ctx -> {
                            Village v = village(ctx);
                            if (v == null) return 0;
                            Building b = v.building(IntegerArgumentType.getInteger(ctx, "building"));
                            boolean ok = b != null && VillageManager.raise(ctx.getSource().getServer(), v, b);
                            ctx.getSource().sendSuccess(() -> Component.literal(ok ? "raising " + b.type.id() + " to " + b.goal() : "cannot raise"), true);
                            return ok ? 1 : 0;
                        }))))
                .then(Commands.literal("grow").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ColonyCommand::grow)
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(org.webtrade.minecraftportsmod.colony.Job.values())
                                        .map(org.webtrade.minecraftportsmod.colony.Job::id), b))
                                .executes(ctx -> {
                                    Village v = village(ctx);
                                    if (v == null) return 0;
                                    Dweller d = VillageManager.newcomer(ctx.getSource().getServer(), v,
                                            org.webtrade.minecraftportsmod.colony.Job.byId(StringArgumentType.getString(ctx, "job")));
                                    ctx.getSource().sendSuccess(() -> Component.literal("welcome " + d.name + " (" + d.job() + ")"), true);
                                    return 1;
                                })))));
    }

    private static Village village(CommandContext<CommandSourceStack> ctx) {
        Village v = VillageData.get(ctx.getSource().getServer()).get(IntegerArgumentType.getInteger(ctx, "id"));
        if (v == null) ctx.getSource().sendFailure(Component.literal("no such village"));
        return v;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        VillageData data = VillageData.get(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("day " + data.day() + " (" + data.dayTicks() + "/" + data.dayLength() + ")")
                .withStyle(ChatFormatting.GOLD), false);
        for (Village v : data.all()) {
            ctx.getSource().sendSuccess(() -> Component.literal("#" + v.id + " " + v.name + " ").append(v.level().displayName())
                    .append(" · " + v.population() + " people · " + v.buildings().size() + " buildings · " + v.center.toShortString()), false);
        }
        return data.all().size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        Village v = village(ctx);
        if (v == null) return 0;
        long today = VillageData.get(ctx.getSource().getServer()).day();
        var src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("== " + v.name + " ==").withStyle(ChatFormatting.GOLD).append(" ").append(v.level().displayName()), false);
        StringBuilder stock = new StringBuilder();
        for (Res r : Res.values()) stock.append(r.id()).append('=').append(v.stock(r)).append(" (+").append(v.made(r)).append(") ");
        src.sendSuccess(() -> Component.literal("stock: " + stock + " eaten=" + v.eaten() + " mood=" + v.mood() + " beds=" + v.beds()), false);
        for (Dweller d : v.dwellers()) {
            src.sendSuccess(() -> Component.literal(" " + d.name + " " + (d.job() == null ? "child" : d.job().id())
                    + (d.elder() ? " elder" : "") + " home=" + d.home() + (d.child(today) ? " (child)" : "")), false);
        }
        for (Building b : v.buildings()) {
            src.sendSuccess(() -> Component.literal(" [" + b.id + "] " + b.type.id() + " L" + b.level() + (b.goal() > 0 ? ">" + b.goal() : "") + " " + b.state().id() + " work=" + b.work()
                    + " at " + b.origin.toShortString()), false);
        }
        return 1;
    }

    private static int camp(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        Direction facing = src.getEntity() == null ? Direction.NORTH : src.getEntity().getDirection();
        List<String> taken = new ArrayList<>();
        VillageData.get(src.getServer()).all().forEach(v -> taken.add(v.name));
        boolean ru = java.util.Locale.getDefault().getLanguage().startsWith("ru");
        String name = org.webtrade.minecraftportsmod.fleet.Names.portName(ru, taken);
        // the camp is set up in front of whoever calls it: "facing" is where the water would be
        Village v = VillageManager.foundCamp(level, at.relative(facing, 10), facing, name, ru, "oak");
        src.sendSuccess(() -> Component.literal("founded #" + v.id + " " + v.name), true);
        return v.id;
    }

    /** A test village with every workshop, farms, homes and people, its store full: to try the workshops' queues out. */
    private static int sandbox(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        BlockPos at = BlockPos.containing(src.getPosition());
        Direction facing = src.getEntity() == null ? Direction.NORTH : src.getEntity().getDirection();
        boolean ru = java.util.Locale.getDefault().getLanguage().startsWith("ru");
        Village v = org.webtrade.minecraftportsmod.colony.Sandbox.build(src.getLevel(), at, facing, ru ? "Тестовая деревня" : "Test Village", ru);
        src.sendSuccess(() -> Component.literal("sandbox #" + v.id + " " + v.name + ": " + v.buildings().size() + " buildings, " + v.population() + " people"), true);
        return v.id;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        Village v = village(ctx);
        if (v == null) return 0;
        VillageManager.remove(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("removed #" + v.id), true);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Village v = village(ctx);
        if (v == null) return 0;
        var p = ctx.getSource().getPlayerOrException();
        p.teleportTo(ctx.getSource().getLevel(), v.center.getX() + 0.5, v.center.getY() + 1, v.center.getZ() + 6.5,
                java.util.Set.of(), p.getYRot(), p.getXRot(), false);
        return 1;
    }

    private static int day(CommandContext<CommandSourceStack> ctx, int days) {
        for (int i = 0; i < days; i++) VillageManager.advanceDay(ctx.getSource().getServer());
        // (N is how many days go by, not the day to go to: the day it is now, so that it cannot be taken so)
        long now = VillageData.get(ctx.getSource().getServer()).day();
        ctx.getSource().sendSuccess(() -> Component.literal("advanced " + days + " day(s), now day " + now), true);
        return days;
    }

    private static int dayLength(CommandContext<CommandSourceStack> ctx) {
        int t = IntegerArgumentType.getInteger(ctx, "ticks");
        VillageData.get(ctx.getSource().getServer()).setDayLength(t);
        ctx.getSource().sendSuccess(() -> Component.literal("village day = " + t + " ticks"), true);
        return t;
    }

    private static int give(CommandContext<CommandSourceStack> ctx) {
        Village v = village(ctx);
        if (v == null) return 0;
        String id = StringArgumentType.getString(ctx, "res");
        Res res = null;
        for (Res r : Res.values()) if (r.id().equals(id)) res = r;
        if (res == null) {
            ctx.getSource().sendFailure(Component.literal("unknown resource"));
            return 0;
        }
        VillageManager.give(ctx.getSource().getServer(), v, res, IntegerArgumentType.getInteger(ctx, "amount"));
        return 1;
    }

    private static int grow(CommandContext<CommandSourceStack> ctx) {
        Village v = village(ctx);
        if (v == null) return 0;
        Dweller d = VillageManager.newcomer(ctx.getSource().getServer(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("welcome " + d.name), true);
        return 1;
    }
}
