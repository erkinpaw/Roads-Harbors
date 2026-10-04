package org.webtrade.minecraftportsmod.registry;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.block.PortOfficeBlock;
import org.webtrade.minecraftportsmod.vessel.VesselEntity;

import java.util.function.Function;

public final class ModContent {

    public static final Block PORT_OFFICE = block("port_office", PortOfficeBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD).noOcclusion());

    public static final Item PORT_OFFICE_ITEM = blockItem(PORT_OFFICE);

    /** Stands by the fire of every village: the village's own screen. Can't be broken by hand. */
    public static final Block VILLAGE_BOARD = block("village_board", org.webtrade.minecraftportsmod.block.VillageBoardBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0F, 3600000.0F).sound(SoundType.WOOD).noOcclusion().noLootTable());
    public static final Item VILLAGE_BOARD_ITEM = blockItem(VILLAGE_BOARD);

    /** The cartographer's table in the village: the map of what the scouts found. Put there by the village. */
    public static final Block MAP_TABLE = block("map_table", org.webtrade.minecraftportsmod.block.MapTableBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0F, 3600000.0F).sound(SoundType.WOOD).noLootTable());
    public static final Item MAP_TABLE_ITEM = blockItem(MAP_TABLE);

    /** Marks a village building site. Put up and taken away by the village. */
    public static final Block CONSTRUCTION_SITE = block("construction_site", org.webtrade.minecraftportsmod.block.ConstructionSiteBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0F, 3600000.0F).sound(SoundType.WOOD).noOcclusion().noLootTable());

    /** A player's boundary stone: marks their plot in a village. Only its owner can take it up. */
    public static final Block PLOT_MARKER = block("plot_marker", org.webtrade.minecraftportsmod.block.PlotMarkerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5F, 1200.0F).sound(SoundType.STONE).noOcclusion().noLootTable());
    public static final Item PLOT_MARKER_ITEM = blockItem(PLOT_MARKER);

    public static final EntityType<VesselEntity> VESSEL = entity("vessel",
            EntityType.Builder.<VesselEntity>of(VesselEntity::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(1.375F, 0.5625F)
                    .eyeHeight(0.5625F)
                    .clientTrackingRange(10)
                    .updateInterval(1));

    public static final EntityType<org.webtrade.minecraftportsmod.vessel.VesselDeckEntity> VESSEL_DECK = entity("vessel_deck",
            EntityType.Builder.<org.webtrade.minecraftportsmod.vessel.VesselDeckEntity>of(
                            org.webtrade.minecraftportsmod.vessel.VesselDeckEntity::new, MobCategory.MISC)
                    .noLootTable()
                    .noSave()
                    .noSummon()
                    .fireImmune()
                    .sized(2.0F, org.webtrade.minecraftportsmod.vessel.VesselDeckEntity.HEIGHT)
                    .clientTrackingRange(10)
                    .updateInterval(1));

    public static final EntityType<org.webtrade.minecraftportsmod.village.ResidentEntity> RESIDENT = entity("resident",
            EntityType.Builder.<org.webtrade.minecraftportsmod.village.ResidentEntity>of(
                            org.webtrade.minecraftportsmod.village.ResidentEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10));

    /** The warships: a brig, a galleon, a ship of the line, sailed by hand (or a pirate's, sailed by the server). */
    public static final EntityType<org.webtrade.minecraftportsmod.combat.WarshipEntity> WARSHIP = warship("warship", 4.0F, 1.2F);
    public static final EntityType<org.webtrade.minecraftportsmod.combat.WarshipEntity> GALLEON = warship("galleon", 4.6F, 1.2F);
    public static final EntityType<org.webtrade.minecraftportsmod.combat.WarshipEntity> SHIP_OF_THE_LINE = warship("ship_of_the_line", 5.4F, 1.2F);

    /** The kind of warship an entity type is. */
    public static org.webtrade.minecraftportsmod.combat.ShipClass shipClass(EntityType<?> type) {
        return type == GALLEON ? org.webtrade.minecraftportsmod.combat.ShipClass.GALLEON
                : type == SHIP_OF_THE_LINE ? org.webtrade.minecraftportsmod.combat.ShipClass.LINE : org.webtrade.minecraftportsmod.combat.ShipClass.BRIG;
    }

    /** The entity type of a kind of warship. */
    public static EntityType<org.webtrade.minecraftportsmod.combat.WarshipEntity> warshipType(org.webtrade.minecraftportsmod.combat.ShipClass c) {
        return switch (c) {
            case BRIG -> WARSHIP;
            case GALLEON -> GALLEON;
            case LINE -> SHIP_OF_THE_LINE;
        };
    }

    private static EntityType<org.webtrade.minecraftportsmod.combat.WarshipEntity> warship(String name, float width, float height) {
        return entity(name, EntityType.Builder.<org.webtrade.minecraftportsmod.combat.WarshipEntity>of(org.webtrade.minecraftportsmod.combat.WarshipEntity::new, MobCategory.MISC)
                .noLootTable()
                .sized(width, height)
                .eyeHeight(1.0F)
                .clientTrackingRange(16)
                .updateInterval(1));
    }

    /** A warship's sailor (her captain, a gunner, a marine, a hand): kept by his ship, never saved himself. */
    public static final EntityType<org.webtrade.minecraftportsmod.combat.SailorEntity> SAILOR = entity("sailor",
            EntityType.Builder.<org.webtrade.minecraftportsmod.combat.SailorEntity>of(org.webtrade.minecraftportsmod.combat.SailorEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).noSave().noSummon().clientTrackingRange(10).updateInterval(1));
    public static final EntityType<org.webtrade.minecraftportsmod.combat.MusketBallEntity> MUSKET_BALL = entity("musket_ball",
            EntityType.Builder.<org.webtrade.minecraftportsmod.combat.MusketBallEntity>of(org.webtrade.minecraftportsmod.combat.MusketBallEntity::new, MobCategory.MISC)
                    .sized(0.15F, 0.15F).noSave().noSummon().noLootTable().clientTrackingRange(8).updateInterval(1));
    public static final Item MUSKET_ITEM = item("musket", p -> new org.webtrade.minecraftportsmod.combat.MusketItem(p.stacksTo(1)));

    public static final EntityType<org.webtrade.minecraftportsmod.combat.CannonballEntity> CANNONBALL = entity("cannonball",
            EntityType.Builder.<org.webtrade.minecraftportsmod.combat.CannonballEntity>of(org.webtrade.minecraftportsmod.combat.CannonballEntity::new, MobCategory.MISC)
                    .noLootTable()
                    .noSave()
                    .sized(0.4F, 0.4F)
                    .clientTrackingRange(16)
                    .updateInterval(2));

    public static final Item WARSHIP_SPAWN_EGG = item("warship_spawn_egg",
            p -> new net.minecraft.world.item.SpawnEggItem(p.spawnEgg(WARSHIP)));
    public static final Item GALLEON_SPAWN_EGG = item("galleon_spawn_egg",
            p -> new net.minecraft.world.item.SpawnEggItem(p.spawnEgg(GALLEON)));
    public static final Item SHIP_OF_THE_LINE_SPAWN_EGG = item("ship_of_the_line_spawn_egg",
            p -> new net.minecraft.world.item.SpawnEggItem(p.spawnEgg(SHIP_OF_THE_LINE)));

    public static final net.fabricmc.fabric.api.menu.v1.ExtendedMenuType<org.webtrade.minecraftportsmod.vessel.HoldMenu, Integer> HOLD_MENU =
            Registry.register(BuiltInRegistries.MENU, Minecraftportsmod.id("hold"),
                    new net.fabricmc.fabric.api.menu.v1.ExtendedMenuType<>(org.webtrade.minecraftportsmod.vessel.HoldMenu::new,
                            net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    private ModContent() {
    }

    public static void register() {
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(RESIDENT,
                org.webtrade.minecraftportsmod.village.ResidentEntity.createAttributes());
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(SAILOR,
                org.webtrade.minecraftportsmod.combat.SailorEntity.createAttributes());
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> output.accept(MUSKET_ITEM));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
            output.accept(PORT_OFFICE_ITEM);
        });
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS).register(output -> {
            output.accept(WARSHIP_SPAWN_EGG);
            output.accept(GALLEON_SPAWN_EGG);
            output.accept(SHIP_OF_THE_LINE_SPAWN_EGG);
        });
    }

    private static Block block(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Minecraftportsmod.id(name));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
    }

    private static Item blockItem(Block block) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BuiltInRegistries.BLOCK.getKey(block));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    private static Item item(String name, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Minecraftportsmod.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
    }

    private static <T extends net.minecraft.world.entity.Entity> EntityType<T> entity(String name, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Minecraftportsmod.id(name));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }
}
