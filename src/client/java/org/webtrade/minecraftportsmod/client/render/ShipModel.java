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
    public record Spec(LayerDefinition layer, Map<String, String> animated, float scale, List<Cloth> cloths) {

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
                List<Cloth> cloths = new ArrayList<>();
                if (json.has("sails")) {
                    for (JsonElement e : json.getAsJsonArray("sails")) {
                        JsonObject o = e.getAsJsonObject();
                        float[][] pts = new float[4][3];
                        JsonArray a = o.getAsJsonArray("pts");
                        for (int i = 0; i < 4; i++) for (int k = 0; k < 3; k++) pts[i][k] = a.get(i).getAsJsonArray().get(k).getAsFloat() / 16F;
                        JsonArray b = o.getAsJsonArray("belly");
                        float[] belly = {b.get(0).getAsFloat() / 16F, b.get(1).getAsFloat() / 16F, b.get(2).getAsFloat() / 16F};
                        cloths.add(new Cloth(pts, belly, "top".equals(o.get("furl").getAsString()), o.get("emblem").getAsBoolean()));
                    }
                }
                return new Spec(LayerDefinition.create(mesh, size.get(0).getAsInt(), size.get(1).getAsInt()), animated, scale, cloths);
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

    /**
     * A sail drawn as smooth cloth: its corners (head left, head right, foot right, foot left; a triangle's head
     * twice), in model units; how far its middle bellies out and which way; whether it furls up to its yard (else down);
     * whether it bears the ship's emblem.
     */
    public record Cloth(float[][] pts, float[] belly, boolean furlsUp, boolean emblem) {
    }

    /** Cells of a sail's cloth across and down. */
    private static final int CELLS = 10;

    /**
     * Draws the sails as smooth cloth, full of wind (or furled), in the model's frame (the pose the model is drawn
     * with). {@code emblem} draws on the sails that bear the emblem (null: the plain cloth for all).
     */
    public void drawSails(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc, int light,
                          boolean sailing, float t, boolean emblems) {
        for (Cloth c : cloths) {
            if (c.emblem != emblems) continue;
            float billow = sailing ? 0.85F + 0.15F * Mth.sin(t * 0.07F + c.pts[0][2] * 2F) : 0.1F;
            // furled: the cloth gathered in a narrow band at its yard (or its boom)
            float v0 = 0, v1 = 1;
            if (!sailing) {
                if (c.furlsUp) v1 = 0.12F;
                else v0 = 0.88F;
            }
            float[][][] grid = new float[CELLS + 1][CELLS + 1][];
            for (int i = 0; i <= CELLS; i++) {
                for (int j = 0; j <= CELLS; j++) {
                    float u = i / (float) CELLS, v = v0 + (v1 - v0) * j / (float) CELLS;
                    grid[i][j] = point(c, u, v, billow);
                }
            }
            for (int i = 0; i < CELLS; i++) {
                for (int j = 0; j < CELLS; j++) {
                    float[] a = grid[i][j], b = grid[i + 1][j], d = grid[i + 1][j + 1], e = grid[i][j + 1];
                    // the normal of the cell (for the light), and its shade: the cloth darker where it bellies away
                    float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = e[0] - a[0], vy = e[1] - a[1], vz = e[2] - a[2];
                    float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                    float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len < 1e-6F) {
                        nx = 0;
                        ny = 1;
                        nz = 0;
                    } else {
                        nx /= len;
                        ny /= len;
                        nz /= len;
                    }
                    float u0 = i / (float) CELLS, u1 = (i + 1) / (float) CELLS, w0 = j / (float) CELLS, w1 = (j + 1) / (float) CELLS;
                    vertex(pose, vc, a, u0, w0, light, nx, ny, nz);
                    vertex(pose, vc, b, u1, w0, light, nx, ny, nz);
                    vertex(pose, vc, d, u1, w1, light, nx, ny, nz);
                    vertex(pose, vc, e, u0, w1, light, nx, ny, nz);
                }
            }
        }
    }

    /** A point of a sail's cloth: across (u) and down (v) its corners, bellied out the most in the middle and low down. */
    private static float[] point(Cloth c, float u, float v, float billow) {
        float[][] p = c.pts;
        float[] out = new float[3];
        float bulge = billow * (float) (Math.sin(Math.PI * u) * Math.sin(Math.PI * Math.min(1.0, v * 1.15)));
        for (int k = 0; k < 3; k++) {
            float top = p[0][k] + (p[1][k] - p[0][k]) * u, bot = p[3][k] + (p[2][k] - p[3][k]) * u;
            out[k] = top + (bot - top) * v + c.belly[k] * bulge;
        }
        return out;
    }

    private static void vertex(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc, float[] p, float u, float v,
                               int light, float nx, float ny, float nz) {
        vc.addVertex(pose, p[0], p[1], p[2]).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    private final List<Cloth> cloths;

    private final List<Sail> sails = new ArrayList<>();
    private final List<ModelPart> flags = new ArrayList<>();
    private final float scale;

    public ShipModel(ModelPart root, Spec spec) {
        super(root, RenderTypes::entityCutout);
        this.scale = spec.scale();
        this.cloths = spec.cloths();
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
