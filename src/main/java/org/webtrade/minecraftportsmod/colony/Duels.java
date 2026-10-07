package org.webtrade.minecraftportsmod.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.minecraftportsmod.Experimental;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.registry.ModContent;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Experimental: a duel with a village's person. The player is taken to the arena (a world of its own), with iron
 * armour and an iron sword and no hunger, against the person's likeness at its hardest (see the resident's duel); the
 * fight over (the fighter down, or the player near death: no death in a duel), the player is back where they stood,
 * with all they had. What they had is kept with the world while the duel lasts (a game closed mid-fight gives it back).
 */
public final class Duels {

    private Duels() {
    }

    public static final ResourceKey<Level> ARENA = ResourceKey.create(Registries.DIMENSION, Minecraftportsmod.id("arena"));

    /** The arena's floor (the flat world's sand) and the walls' ring. */
    static final int FLOOR = -60, RADIUS = 16, SPACING = 256;

    /** The hearts a player has more in a duel (three rows in all). */
    static final Identifier HEARTS = Minecraftportsmod.id("duel_hearts");

    /** What a player had and where they stood before a duel. */
    record Kept(String dim, double x, double y, double z, float yaw, float pitch, List<ItemStack> items, float health, int food, float saturation, String mode) {
        static final Codec<Kept> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dim").forGetter(Kept::dim),
                Codec.DOUBLE.fieldOf("x").forGetter(Kept::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Kept::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Kept::z),
                Codec.FLOAT.fieldOf("yaw").forGetter(Kept::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(Kept::pitch),
                ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Kept::items),
                Codec.FLOAT.fieldOf("health").forGetter(Kept::health),
                Codec.INT.fieldOf("food").forGetter(Kept::food),
                Codec.FLOAT.fieldOf("saturation").forGetter(Kept::saturation),
                Codec.STRING.fieldOf("mode").forGetter(Kept::mode)
        ).apply(i, Kept::new));
    }

    /** The players in a duel now: what they had, kept with the world. */
    public static final class Store extends SavedData {
        static final Codec<Store> CODEC = Codec.unboundedMap(Codec.STRING, Kept.CODEC).xmap(m -> {
            Store s = new Store();
            m.forEach((k, v) -> {
                try {
                    s.kept.put(UUID.fromString(k), v);
                } catch (IllegalArgumentException ignored) {
                }
            });
            return s;
        }, s -> {
            Map<String, Kept> m = new HashMap<>();
            s.kept.forEach((k, v) -> m.put(k.toString(), v));
            return m;
        });
        static final SavedDataType<Store> TYPE = new SavedDataType<>(Minecraftportsmod.id("duels"), Store::new, CODEC, null);
        final Map<UUID, Kept> kept = new HashMap<>();
    }

    static Store store(MinecraftServer srv) {
        return srv.getDataStorage().computeIfAbsent(Store.TYPE);
    }

    /** A duel going on: the player's fighter, the arena's place (its slot), its start. */
    record Fight(UUID fighter, int slot, long start) {
    }

    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

    public static boolean inDuel(ServerPlayer p) {
        return FIGHTS.containsKey(p.getUUID());
    }

    /** Can a player call this person out (the experiment on, a grown-up of the village, the player free)? */
    public static boolean can(ServerPlayer p, Dweller d, long today) {
        return Experimental.duels() && d != null && !d.child(today) && !inDuel(p) && p.level().getServer().getLevel(ARENA) != null;
    }

    /** A player calls a person out: off to the arena. */
    public static boolean start(ServerPlayer p, Village v, Dweller d) {
        MinecraftServer srv = p.level().getServer();
        ServerLevel arena = srv.getLevel(ARENA);
        if (arena == null || inDuel(p) || !Experimental.duels()) return false;
        int slot = 0;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Fight f : FIGHTS.values()) used.add(f.slot);
        while (used.contains(slot)) slot++;
        int cx = slot * SPACING;
        // (the arena's place kept loaded while the duel lasts)
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) arena.setChunkForced((cx >> 4) + dx, dz, true);
        build(arena, cx);
        // what the player has and where they stand: kept with the world
        var inv = p.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) items.add(inv.getItem(i).copy());
        Kept kept = new Kept(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), items,
                p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.gameMode.getGameModeForPlayer().getName());
        Store st = store(srv);
        st.kept.put(p.getUUID(), kept);
        st.setDirty();
        // the kit: iron armour, an iron sword
        inv.clearContent();
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        inv.setSelectedSlot(0);
        p.setGameMode(GameType.SURVIVAL);
        // (three rows of hearts in a duel)
        var health = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (health != null) {
            health.removeModifier(HEARTS);
            health.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(HEARTS, 40,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        }
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5);
        p.removeAllEffects();
        p.teleportTo(arena, cx - 7.5, FLOOR + 1, 0.5, java.util.Set.of(), -90, 0, false);
        // the fighter: the person's likeness
        ResidentEntity e = ModContent.RESIDENT.create(arena, EntitySpawnReason.MOB_SUMMONED);
        if (e == null) {
            end(p, false);
            return false;
        }
        e.snapTo(cx + 8.5, FLOOR + 1, 0.5, 90, 0);
        e.becomeDuelist(p.getUUID(), d.name, d.skin);
        arena.addFreshEntity(e);
        FIGHTS.put(p.getUUID(), new Fight(e.getUUID(), slot, srv.getTickCount()));
        Minecraftportsmod.LOGGER.info("[duel] {} against {} ({} #{}) in the arena's place {}", p.getName().getString(), d.name, v.name, d.id, slot);
        v.log(VillageData.get(srv).day, Component.translatable("minecraftportsmod.vlog.duel", p.getName(), d.name));
        return true;
    }

    /** The arena at a place: a round floor, a wall about it, lanterns on the wall. Built once. */
    static void build(ServerLevel arena, int cx) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) arena.getChunk((cx >> 4) + dx, dz);
        BlockPos mark = new BlockPos(cx, FLOOR, 0);
        if (arena.getBlockState(mark).is(Blocks.CHISELED_STONE_BRICKS)) return;
        for (int dx = -RADIUS - 2; dx <= RADIUS + 2; dx++) {
            for (int dz = -RADIUS - 2; dz <= RADIUS + 2; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                BlockPos at = new BlockPos(cx + dx, FLOOR, dz);
                if (r <= RADIUS) {
                    arena.setBlockAndUpdate(at, (dx + dz & 1) == 0 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : Blocks.SANDSTONE.defaultBlockState());
                    for (int y = 1; y <= 6; y++) arena.setBlockAndUpdate(at.above(y), Blocks.AIR.defaultBlockState());
                } else if (r <= RADIUS + 1.5) {
                    for (int y = 0; y <= 4; y++) arena.setBlockAndUpdate(at.above(y), Blocks.STONE_BRICKS.defaultBlockState());
                    if ((dx * 7 + dz * 13 & 7) == 0) arena.setBlockAndUpdate(at.above(5), Blocks.LANTERN.defaultBlockState());
                }
            }
        }
        arena.setBlockAndUpdate(mark, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
    }

    /** Every tick: hunger stands still, the count, the end of the fight. */
    static void tick(MinecraftServer srv) {
        if (FIGHTS.isEmpty()) return;
        for (var e : new ArrayList<>(FIGHTS.entrySet())) {
            ServerPlayer p = srv.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            Fight f = e.getValue();
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(5);
            long age = srv.getTickCount() - f.start;
            if (age >= 0 && age <= ResidentEntity.Duel.COUNT && age % 20 == 0) {
                int left = (int) ((ResidentEntity.Duel.COUNT - age) / 20);
                title(p, left > 0 ? Component.literal(String.valueOf(left)).withStyle(ChatFormatting.GOLD)
                        : Component.translatable("minecraftportsmod.duel.fight").withStyle(ChatFormatting.RED), Component.empty());
                p.level().playSound(null, p.blockPosition(), left > 0 ? SoundEvents.NOTE_BLOCK_HAT.value() : SoundEvents.RAID_HORN.value(),
                        SoundSource.PLAYERS, 0.8F, 1F);
            }
            ServerLevel arena = srv.getLevel(ARENA);
            Entity fighter = arena == null ? null : arena.getEntity(f.fighter);
            if (p.level() != arena) end(p, false);
            // (the fighter down: won; a fighter not to be found a while after the start, the same)
            else if (fighter != null ? !fighter.isAlive() : age > 40) end(p, true);
        }
    }

    /** A player in a duel about to die: the fight lost instead. False: the death does not come. */
    public static boolean allowDeath(ServerPlayer p) {
        if (!inDuel(p)) return true;
        p.setHealth(1);
        end(p, false);
        return false;
    }

    /** The fight over: the fighter gone, the player back where they stood with all they had. */
    static void end(ServerPlayer p, boolean won) {
        MinecraftServer srv = p.level().getServer();
        Fight f = FIGHTS.remove(p.getUUID());
        ServerLevel arena = srv.getLevel(ARENA);
        // (the winner's hearts left: the player's, or the fighter's)
        Entity winner = won ? p : f == null || arena == null ? null : arena.getEntity(f.fighter);
        float left = 0, of = 0;
        if (winner instanceof net.minecraft.world.entity.LivingEntity l) {
            left = l.getHealth();
            of = l.getMaxHealth();
        }
        Minecraftportsmod.LOGGER.info("[duel] {} {} the duel ({} of {} health left to the winner)", p.getName().getString(), won ? "won" : "lost", left, of);
        if (f != null && arena != null) {
            Entity fighter = arena.getEntity(f.fighter);
            if (fighter != null) fighter.discard();
            int cx = f.slot * SPACING;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) arena.setChunkForced((cx >> 4) + dx, dz, false);
        }
        restore(p);
        Component hearts = of <= 0 ? Component.empty() : Component.literal("❤ " + (int) Math.ceil(left) + " / " + (int) of).withStyle(ChatFormatting.RED);
        title(p, Component.translatable(won ? "minecraftportsmod.duel.won" : "minecraftportsmod.duel.lost")
                .withStyle(won ? ChatFormatting.GOLD : ChatFormatting.DARK_RED), hearts);
        p.level().playSound(null, p.blockPosition(), won ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.7F, 1F);
    }

    /** What a player had, given back, and the player back where they stood (if a duel kept it). */
    static void restore(ServerPlayer p) {
        MinecraftServer srv = p.level().getServer();
        Store st = store(srv);
        Kept k = st.kept.remove(p.getUUID());
        if (k == null) return;
        st.setDirty();
        var health = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (health != null) health.removeModifier(HEARTS);
        var inv = p.getInventory();
        inv.clearContent();
        for (int i = 0; i < Math.min(inv.getContainerSize(), k.items().size()); i++) inv.setItem(i, k.items().get(i).copy());
        inv.setChanged();
        p.removeAllEffects();
        p.setHealth(Math.max(1, k.health()));
        p.getFoodData().setFoodLevel(k.food());
        p.getFoodData().setSaturation(k.saturation());
        GameType mode = GameType.byName(k.mode(), GameType.SURVIVAL);
        p.setGameMode(mode);
        ServerLevel back = srv.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(k.dim())));
        if (back == null) back = srv.overworld();
        p.teleportTo(back, k.x(), k.y(), k.z(), java.util.Set.of(), k.yaw(), k.pitch(), false);
    }

    /** A player coming in with a duel still kept (the game closed mid-fight): given back what they had. */
    static void joined(ServerPlayer p) {
        if (!FIGHTS.containsKey(p.getUUID()) && store(p.level().getServer()).kept.containsKey(p.getUUID())) restore(p);
    }

    /** A player leaving mid-fight: the fight lost, all given back at once. */
    static void left(ServerPlayer p) {
        if (FIGHTS.containsKey(p.getUUID())) end(p, false);
    }

    static void clear() {
        FIGHTS.clear();
    }

    private static void title(ServerPlayer p, Component title, Component sub) {
        // (the subtitle first: it shows with the title that follows)
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    /** Tests: the fighter of a player's duel, or null. */
    public static ResidentEntity fighter(ServerPlayer p) {
        Fight f = FIGHTS.get(p.getUUID());
        ServerLevel arena = p.level().getServer().getLevel(ARENA);
        return f == null || arena == null || !(arena.getEntity(f.fighter) instanceof ResidentEntity r) ? null : r;
    }
}
