package org.webtrade.minecraftportsmod.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ship models loaded from {@code assets/minecraftportsmod/ship_models/<name>.json}, which are generated together with
 * their textures by {@code tools/ships/gen_ships.py}. Units are 1/32 block (the renderer scales by {@link #scale()}),
 * +Z is the bow, Y points down. Top-level parts tagged in the JSON are animated: sails ("fore_aft" / "square")
 * furl while moored and bulge a little under way, flags wave.
 */
public class ShipModel extends EntityModel<VesselRenderState> {

    /** A loaded model file: the layer to bake plus which top-level parts are animated how. */
    public record Spec(LayerDefinition layer, Map<String, String> animated, float scale) {

        public static Spec load(String name) {
            String path = "/assets/minecraftportsmod/ship_models/" + name + ".json";
            try (InputStream in = ShipModel.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("missing " + path);
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray size = json.getAsJsonArray("texture_size");
                MeshDefinition mesh = new MeshDefinition();
                Map<String, String> animated = new LinkedHashMap<>();
                for (JsonElement e : json.getAsJsonArray("parts")) {
                    JsonObject part = e.getAsJsonObject();
                    addPart(mesh.getRoot(), part);
                    if (part.has("anim") && !part.get("anim").isJsonNull()) {
                        animated.put(part.get("name").getAsString(), part.get("anim").getAsString());
                    }
                }
                float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1F;
                return new Spec(LayerDefinition.create(mesh, size.get(0).getAsInt(), size.get(1).getAsInt()), animated, scale);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not load ship model " + name, ex);
            }
        }

        private static void addPart(PartDefinition parent, JsonObject json) {
            CubeListBuilder cubes = CubeListBuilder.create();
            for (JsonElement e : json.getAsJsonArray("boxes")) {
                JsonObject b = e.getAsJsonObject();
                JsonArray uv = b.getAsJsonArray("uv"), from = b.getAsJsonArray("from"), s = b.getAsJsonArray("size");
                // (a box grown a hair all round, so that its faces don't flicker against another's in the same plane)
                float grow = b.has("grow") ? b.get("grow").getAsFloat() : 0F;
                cubes.texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt())
                        .addBox(from.get(0).getAsFloat(), from.get(1).getAsFloat(), from.get(2).getAsFloat(),
                                s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat(),
                                new net.minecraft.client.model.geom.builders.CubeDeformation(grow));
            }
            JsonArray p = json.getAsJsonArray("pivot"), r = json.getAsJsonArray("rotation");
            PartDefinition part = parent.addOrReplaceChild(json.get("name").getAsString(), cubes,
                    PartPose.offsetAndRotation(p.get(0).getAsFloat(), p.get(1).getAsFloat(), p.get(2).getAsFloat(),
                            r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat()));
            for (JsonElement c : json.getAsJsonArray("children")) addPart(part, c.getAsJsonObject());
        }
    }

    private record Sail(ModelPart part, boolean foreAndAft, float baseX, float baseY) {
    }

    private final List<Sail> sails = new ArrayList<>();
    private final List<ModelPart> flags = new ArrayList<>();
    private final float scale;

    public ShipModel(ModelPart root, Spec spec) {
        super(root, RenderTypes::entityCutout);
        this.scale = spec.scale();
        for (Map.Entry<String, String> e : spec.animated().entrySet()) {
            ModelPart part = root.getChild(e.getKey());
            switch (e.getValue()) {
                case "fore_aft" -> sails.add(new Sail(part, true, part.xRot, part.yRot));
                case "square" -> sails.add(new Sail(part, false, part.xRot, part.yRot));
                case "flag" -> flags.add(part);
                default -> {
                }
            }
        }
    }

    public float scale() {
        return scale;
    }

    @Override
    public void setupAnim(VesselRenderState state) {
        super.setupAnim(state);
        float t = state.ageInTicks;
        for (Sail sail : sails) {
            ModelPart p = sail.part;
            p.yScale = state.sailing ? 1F : 0.16F;
            float billow = state.sailing ? (0.6F + 0.4F * Mth.sin(t * 0.07F + p.z * 0.01F)) : 0F;
            if (sail.foreAndAft) {
                p.yRot = sail.baseY + billow * 0.08F;      // swings a little around the mast
            } else {
                p.xRot = sail.baseX - billow * 0.06F;      // square sails bulge forward
            }
        }
        for (ModelPart flag : flags) {
            flag.yRot = Mth.sin(t * 0.35F + flag.z * 0.02F) * 0.25F + (state.sailing ? 0F : 0.4F);
        }
    }
}
