package org.webtrade.minecraftportsmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.ShipClass;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;

import java.util.EnumMap;
import java.util.Map;

/**
 * A warship: her class's model (the brig's, the galleon's, the ship of the line's) drawn {@link ShipClass#SCALE} times
 * as big as the fleet's ships, high enough that her deck and her lowest ports are clear of the water; black sails for
 * a pirate. She rolls on the waves, heels as she turns and goes down by the stern when she sinks.
 */
public class WarshipRenderer extends EntityRenderer<WarshipEntity, WarshipRenderer.State> {

    public static class State extends VesselRenderState {
        public ShipClass cls = ShipClass.BRIG;
        public boolean pirate;
        public int sinking;
        public float heel;
    }

    private static final Map<ShipClass, ModelLayerLocation> LAYERS = new EnumMap<>(ShipClass.class);
    private static final Map<ShipClass, ShipModel.Spec> SPECS = new EnumMap<>(ShipClass.class);

    /** The models of the classes that have none among the fleet's ships (the brig's is the fleet's). */
    public static void registerLayers() {
        for (ShipClass c : ShipClass.values()) {
            if (c == ShipClass.BRIG) continue;
            ModelLayerLocation layer = new ModelLayerLocation(Minecraftportsmod.id(c.model), "main");
            ShipModel.Spec spec = ShipModel.Spec.load(c.model);
            LAYERS.put(c, layer);
            SPECS.put(c, spec);
            ModelLayerRegistry.registerModelLayer(layer, spec::layer);
        }
    }

    private final BoatRenderer boat;
    private final Map<ShipClass, ShipModel> models = new EnumMap<>(ShipClass.class);

    public WarshipRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        boat = new BoatRenderer(ctx, ModelLayers.OAK_BOAT);
        models.put(ShipClass.BRIG, new ShipModel(ctx.bakeLayer(VesselRenderer.BRIG_LAYER), VesselRenderer.brigSpec()));
        for (var e : LAYERS.entrySet()) models.put(e.getKey(), new ShipModel(ctx.bakeLayer(e.getValue()), SPECS.get(e.getKey())));
    }

    private static Identifier texture(ShipClass c, boolean pirate) {
        return Minecraftportsmod.id("textures/entity/ship/" + c.model + (pirate ? "_pirate" : "") + ".png");
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(WarshipEntity ship, State state, float partialTick) {
        boat.extractRenderState(ship, state, partialTick);
        state.cls = ship.cls();
        state.tier = 2;
        state.sailing = ship.sails() > 0;
        state.pirate = ship.isPirate();
        state.sinking = ship.sinking();
        // heeling over in a turn: the faster and the harder, the more
        float turn = Mth.wrapDegrees(ship.getYRot() - ship.yRotO);
        state.heel = Mth.clamp(turn * 4F, -8F, 8F);
    }

    @Override
    protected float getShadowRadius(State state) {
        return 0;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        ShipModel model = models.get(state.cls);
        float t = state.ageInTicks;
        // (the big ones roll slower and less)
        float amp = (state.sailing ? 1.4F : 0.7F) / (float) Math.sqrt(state.cls.halfLength / 6.8);
        float roll = Mth.sin(t * 0.04F) * amp + state.heel;
        float pitch = Mth.sin(t * 0.055F + 1.3F) * amp * 0.5F;
        if (state.sinking > 0) {
            // down by the stern, listing over
            roll += Math.min(30F, state.sinking * 0.35F);
            pitch -= Math.min(18F, state.sinking * 0.2F);
        }
        pose.pushPose();
        pose.translate(0, 0.375 + state.cls.lift, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.yRot));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        float s = model.scale() * ShipClass.SCALE;
        pose.scale(s, s, s);
        collector.submitModel(model, state, pose, texture(state.cls, state.pirate), state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    @Override
    protected AABB getBoundingBoxForCulling(WarshipEntity ship) {
        double r = ship.cls().halfLength + 3;
        return ship.getBoundingBox().inflate(r, 0, r).expandTowards(0, 20, 0);
    }
}
