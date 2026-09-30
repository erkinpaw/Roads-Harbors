package org.webtrade.minecraftportsmod.colony;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.Minecraftportsmod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The village tree as it is designed in the web editor (docs/village-tree), exported into the mod by
 * {@code python model.py export} to {@code data/minecraftportsmod/village/tree.json}: what each building makes by
 * its recipes, and the items (the category each counts to in the store).
 * <p>
 * A recipe is one item: what one making takes (in the tree's categories) and gives (pieces of the item), and how
 * many makings one worker does in a day. A building's level opens its recipes; the ones below stay.
 */
public final class TreeData {

    private TreeData() {
    }

    /** What one making takes of a category. */
    public record Input(String cat, int n) {
    }

    /**
     * @param index  its place in the building's list (what orders refer to)
     * @param item   the item made (an id of the tree's items), or null for a whole category
     * @param cat    the category it counts to
     * @param n      pieces a making gives
     * @param per    makings a day by one worker
     * @param chance in % that a making gives anything (100: always)
     */
    public record Recipe(String building, int index, int lvl, String item, String cat, int n, List<Input> use, int per, int chance) {
        /** Can the mod's village make it: all it takes is kept in the village's stores. */
        public boolean makeable() {
            for (Input in : use) if (res(in.cat) == null) return false;
            return true;
        }
    }

    public record TreeItem(String id, String res, int units, String name) {
    }

    private static Map<String, List<Recipe>> recipes;
    private static Map<String, TreeItem> items;

    private static synchronized void load() {
        if (recipes != null) return;
        recipes = new HashMap<>();
        items = new HashMap<>();
        try (InputStream in = TreeData.class.getResourceAsStream("/data/minecraftportsmod/village/tree.json")) {
            if (in == null) {
                Minecraftportsmod.LOGGER.warn("village tree data not found");
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : root.getAsJsonObject("items").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                items.put(e.getKey(), new TreeItem(e.getKey(), o.get("res").getAsString(), o.get("units").getAsInt(), o.get("name").getAsString()));
            }
            for (var e : root.getAsJsonObject("buildings").entrySet()) {
                List<Recipe> list = new ArrayList<>();
                int i = 0;
                for (JsonElement el : e.getValue().getAsJsonObject().getAsJsonArray("recipes")) {
                    JsonObject r = el.getAsJsonObject(), out = r.getAsJsonObject("out");
                    String item = out.has("i") ? out.get("i").getAsString() : null;
                    String cat = item != null ? (items.containsKey(item) ? items.get(item).res() : "") : out.get("r").getAsString();
                    List<Input> use = new ArrayList<>();
                    for (JsonElement u : r.getAsJsonArray("use")) {
                        JsonObject uo = u.getAsJsonObject();
                        use.add(new Input(uo.get("r").getAsString(), uo.get("n").getAsInt()));
                    }
                    list.add(new Recipe(e.getKey(), i++, r.has("lvl") ? r.get("lvl").getAsInt() : 1, item, cat,
                            out.has("n") ? out.get("n").getAsInt() : 1, List.copyOf(use), r.has("per") ? r.get("per").getAsInt() : 1,
                            r.has("p") ? r.get("p").getAsInt() : 100));
                }
                recipes.put(e.getKey(), List.copyOf(list));
            }
            Minecraftportsmod.LOGGER.info("village tree: {} buildings, {} items", recipes.size(), items.size());
        } catch (Exception ex) {
            Minecraftportsmod.LOGGER.error("village tree data unreadable", ex);
        }
    }

    /** A building's recipes, all levels (empty when the tree has none for it). */
    public static List<Recipe> recipes(BuildingType type) {
        load();
        return recipes.getOrDefault(type.id(), List.of());
    }

    public static TreeItem item(String id) {
        load();
        return items.get(id);
    }

    /** The store of the mod's village a category of the tree is kept in (null: the mod has no such store yet). */
    public static Res res(String cat) {
        return switch (cat) {
            case "log" -> Res.WOOD;
            case "planks" -> Res.PLANKS;
            case "sticks" -> Res.STICKS;
            case "stone" -> Res.STONE;
            case "metal" -> Res.IRON;
            case "fuel" -> Res.COAL;
            case "food" -> Res.FOOD;
            default -> null;
        };
    }

    /**
     * The game's item for an item of the tree: its own id if the game has it ("chest", "ladder"), else of the
     * village's wood ("stairs" → "spruce_stairs"), else of oak.
     */
    public static Item gameItem(Village v, String id) {
        for (String name : new String[]{id, (v == null ? "oak" : v.wood) + "_" + id, "oak_" + id}) {
            var it = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(name));
            if (it.isPresent() && it.get() != Items.AIR) return it.get();
        }
        return Items.BARRIER;
    }

    public static ItemStack stack(Village v, Recipe r, int count) {
        return r.item() == null ? new ItemStack(Items.BARREL, count) : new ItemStack(gameItem(v, r.item()), count);
    }
}
