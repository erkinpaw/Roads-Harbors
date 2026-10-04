package org.webtrade.minecraftportsmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RaftRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.vessel.VesselEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a vessel according to its hull class: a plain boat uses the vanilla boat model of the wood it was built
 * from; sloops and brigs use {@link ShipModel}, gently rolling on the waves.
 */
public class VesselRenderer extends EntityRenderer<VesselEntity, VesselRenderState> {

    public static final ModelLayerLocation SLOOP_LAYER = new ModelLayerLocation(Minecraftportsmod.id("sloop"), "main");
    public static final ModelLayerLocation BRIG_LAYER = new ModelLayerLocation(Minecraftportsmod.id("brig"), "main");
    private static final Identifier SLOOP_TEXTURE = Minecraftportsmod.id("textures/entity/ship/sloop.png");
    private static final Identifier BRIG_TEXTURE = Minecraftportsmod.id("textures/entity/ship/brig.png");

    /**
     * Ship models are drawn this much higher than a boat: the entity floats with its origin ~0.37 blocks below the
     * water surface, and the deck must clear the surface or water shows through it.
     */
    private static final float SHIP_LIFT = 0.3F;

    private final Map<String, AbstractBoatRenderer> boats = new HashMap<>();
    private final AbstractBoatRenderer oak;
    private final ShipModel sloop;
    private final ShipModel brig;
    private final net.minecraft.client.renderer.item.ItemModelResolver items;
    /** What a laden trade vessel carries on deck, and where: {sideways, along, height} in blocks. */
    private static final net.minecraft.world.item.Item[] CARGO_ITEMS = {net.minecraft.world.item.Items.BARREL,
            net.minecraft.world.item.Items.CHEST, net.minecraft.world.item.Items.BARREL, net.minecraft.world.item.Items.HAY_BLOCK,
            net.minecraft.world.item.Items.BARREL};
    private static final double[][] CARGO_SPOTS = {{0.35, -0.9, 0}, {-0.35, -0.9, 0}, {0.35, -1.45, 0}, {-0.35, -1.45, 0}, {0.35, -0.9, 0.5}};

    private static ShipModel.Spec sloopSpec;
    private static ShipModel.Spec brigSpec;

    /** The sloop's model file (for the villages' trade ships). */
    public static ShipModel.Spec sloopSpec() {
        return sloopSpec;
    }

    /** The brig's model file (for the warships, drawn bigger). */
    public static ShipModel.Spec brigSpec() {
        return brigSpec;
    }

    public static void registerLayers() {
        sloopSpec = ShipModel.Spec.load("sloop");
        brigSpec = ShipModel.Spec.load("brig");
        ModelLayerRegistry.registerModelLayer(SLOOP_LAYER, () -> sloopSpec.layer());
        ModelLayerRegistry.registerModelLayer(BRIG_LAYER, () -> brigSpec.layer());
    }

    public VesselRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        oak = new BoatRenderer(ctx, ModelLayers.OAK_BOAT);
        boats.put("minecraft:oak_boat", oak);
        boats.put("minecraft:spruce_boat", new BoatRenderer(ctx, ModelLayers.SPRUCE_BOAT));
        boats.put("minecraft:birch_boat", new BoatRenderer(ctx, ModelLayers.BIRCH_BOAT));
        boats.put("minecraft:jungle_boat", new BoatRenderer(ctx, ModelLayers.JUNGLE_BOAT));
        boats.put("minecraft:acacia_boat", new BoatRenderer(ctx, ModelLayers.ACACIA_BOAT));
        boats.put("minecraft:cherry_boat", new BoatRenderer(ctx, ModelLayers.CHERRY_BOAT));
        boats.put("minecraft:dark_oak_boat", new BoatRenderer(ctx, ModelLayers.DARK_OAK_BOAT));
        boats.put("minecraft:pale_oak_boat", new BoatRenderer(ctx, ModelLayers.PALE_OAK_BOAT));
        boats.put("minecraft:mangrove_boat", new BoatRenderer(ctx, ModelLayers.MANGROVE_BOAT));
        boats.put("minecraft:bamboo_raft", new RaftRenderer(ctx, ModelLayers.BAMBOO_RAFT));
        items = ctx.getItemModelResolver();
        sloop = new ShipModel(ctx.bakeLayer(SLOOP_LAYER), sloopSpec);
        brig = new ShipModel(ctx.bakeLayer(BRIG_LAYER), brigSpec);
    }

    @Override
    public VesselRenderState createRenderState() {
        return new VesselRenderState();
    }

    @Override
    public void extractRenderState(VesselEntity vessel, VesselRenderState state, float partialTick) {
        // the vanilla boat renderer fills position, light, rotation, paddles etc.
        oak.extractRenderState(vessel, state, partialTick);
        state.tier = vessel.vesselType().tier();
        state.hullId = vessel.hullId();
        state.sailing = vessel.isAutopilot();
        state.cargo.clear();
        if (vessel.hasCargo() && state.tier > 0) {
            for (net.minecraft.world.item.Item item : CARGO_ITEMS) {
                var s = new net.minecraft.client.renderer.item.ItemStackRenderState();
                items.updateForNonLiving(s, new net.minecraft.world.item.ItemStack(item), net.minecraft.world.item.ItemDisplayContext.FIXED, vessel);
                state.cargo.add(s);
            }
        }
    }

    @Override
    protected float getShadowRadius(VesselRenderState state) {
        return state.tier == 0 ? 0.8F : state.tier == 1 ? 1.6F : 2.2F;
    }

    @Override
    public void submit(VesselRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.tier == 0) {
            boats.getOrDefault(state.hullId, oak).submit(state, pose, collector, camera);
            return;
        }
        ShipModel model = state.tier == 1 ? sloop : brig;
        Identifier texture = state.tier == 1 ? SLOOP_TEXTURE : BRIG_TEXTURE;

        pose.pushPose();
        pose.translate(0, 0.375 + SHIP_LIFT, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.yRot));
        // a gentle roll and pitch, a bit livelier when under way
        float t = state.ageInTicks;
        float amp = state.sailing ? 1.6F : 0.8F;
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 0.045F) * amp));
        pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(t * 0.06F + 1.3F) * amp * 0.5F));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.scale(model.scale(), model.scale(), model.scale());
        collector.submitModel(model, state, pose, texture, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        // the sails: smooth cloth
        boolean sailing = state.sailing;
        int light = state.lightCoords;
        float age = state.ageInTicks;
        collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(WarshipRenderer.SAIL),
                (p, vc) -> model.drawSails(p, vc, light, sailing, age, false));
        collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(WarshipRenderer.SAIL),
                (p, vc) -> model.drawSails(p, vc, light, sailing, age, true));
        pose.popPose();
        if (!state.cargo.isEmpty()) {
            // goods lashed on deck, abaft the mast
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-state.yRot));
            double deck = state.tier == 1 ? 1.05 : 1.2;
            for (int i = 0; i < state.cargo.size() && i < CARGO_SPOTS.length; i++) {
                double[] spot = CARGO_SPOTS[i];
                pose.pushPose();
                pose.translate(spot[0], deck + spot[2], spot[1] * (state.tier == 1 ? 1 : 1.4));
                pose.scale(0.9F, 0.9F, 0.9F);
                state.cargo.get(i).submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
                pose.popPose();
            }
            pose.popPose();
        }
        super.submit(state, pose, collector, camera);
    }

    /** Masts reach far above the small hitbox: don't cull the ship while any of it can be seen. */
    @Override
    protected AABB getBoundingBoxForCulling(VesselEntity vessel) {
        int tier = vessel.vesselType().tier();
        if (tier == 0) return super.getBoundingBoxForCulling(vessel);
        double r = tier == 1 ? 4 : 6.5;
        double h = tier == 1 ? 6.5 : 9;
        return vessel.getBoundingBox().inflate(r, 0, r).expandTowards(0, h, 0);
    }
}
