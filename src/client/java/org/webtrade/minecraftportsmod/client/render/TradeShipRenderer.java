package org.webtrade.minecraftportsmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.vessel.TradeShipEntity;

/** A village's trade ship: the sloop or the brig of the fleet's models, rolling on the waves, goods lashed on deck when laden. */
public class TradeShipRenderer extends EntityRenderer<TradeShipEntity, VesselRenderState> {

    private static final Identifier SLOOP_TEXTURE = Minecraftportsmod.id("textures/entity/ship/sloop.png");
    private static final Identifier BRIG_TEXTURE = Minecraftportsmod.id("textures/entity/ship/brig.png");
    private static final float SHIP_LIFT = 0.3F;
    private static final net.minecraft.world.item.Item[] CARGO_ITEMS = {net.minecraft.world.item.Items.BARREL,
            net.minecraft.world.item.Items.CHEST, net.minecraft.world.item.Items.BARREL, net.minecraft.world.item.Items.HAY_BLOCK,
            net.minecraft.world.item.Items.BARREL};
    private static final double[][] CARGO_SPOTS = {{0.35, -0.9, 0}, {-0.35, -0.9, 0}, {0.35, -1.45, 0}, {-0.35, -1.45, 0}, {0.35, -0.9, 0.5}};

    private final ShipModel sloop, brig;
    private final net.minecraft.client.renderer.item.ItemModelResolver items;

    public TradeShipRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        sloop = new ShipModel(ctx.bakeLayer(VesselRenderer.SLOOP_LAYER), VesselRenderer.sloopSpec());
        brig = new ShipModel(ctx.bakeLayer(VesselRenderer.BRIG_LAYER), VesselRenderer.brigSpec());
        items = ctx.getItemModelResolver();
    }

    @Override
    public VesselRenderState createRenderState() {
        return new VesselRenderState();
    }

    @Override
    public void extractRenderState(TradeShipEntity ship, VesselRenderState state, float partialTick) {
        super.extractRenderState(ship, state, partialTick);
        state.yRot = ship.getYRot(partialTick);
        state.tier = Math.max(1, Math.min(2, ship.tier()));
        state.sailing = ship.sailing();
        // (on the stocks: a bare hull, no sails bent on)
        state.hullId = ship.onStocks() ? "stocks" : "";
        state.cargo.clear();
        if (ship.laden()) {
            for (net.minecraft.world.item.Item item : CARGO_ITEMS) {
                var s = new net.minecraft.client.renderer.item.ItemStackRenderState();
                items.updateForNonLiving(s, new net.minecraft.world.item.ItemStack(item), net.minecraft.world.item.ItemDisplayContext.FIXED, ship);
                state.cargo.add(s);
            }
        }
    }

    @Override
    protected float getShadowRadius(VesselRenderState state) {
        return state.tier == 1 ? 1.6F : 2.2F;
    }

    @Override
    public void submit(VesselRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        ShipModel model = state.tier == 1 ? sloop : brig;
        Identifier texture = state.tier == 1 ? SLOOP_TEXTURE : BRIG_TEXTURE;
        pose.pushPose();
        pose.translate(0, 0.375 + SHIP_LIFT, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.yRot));
        float t = state.ageInTicks;
        float amp = state.sailing ? 1.6F : 0.8F;
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 0.045F) * amp));
        pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(t * 0.06F + 1.3F) * amp * 0.5F));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.scale(model.scale(), model.scale(), model.scale());
        collector.submitModel(model, state, pose, texture, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        if ("stocks".equals(state.hullId)) {
            pose.popPose();
            super.submit(state, pose, collector, camera);
            return;
        }
        boolean sailing = state.sailing;
        int light = state.lightCoords;
        float age = state.ageInTicks;
        collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(WarshipRenderer.SAIL),
                (p, vc) -> model.drawSails(p, vc, light, sailing, age, false));
        collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(WarshipRenderer.SAIL),
                (p, vc) -> model.drawSails(p, vc, light, sailing, age, true));
        pose.popPose();
        if (!state.cargo.isEmpty()) {
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

    /** Masts reach far above the small hitbox. */
    @Override
    protected AABB getBoundingBoxForCulling(TradeShipEntity ship) {
        return ship.getBoundingBox().inflate(6.5, 0, 6.5).expandTowards(0, 9, 0);
    }
}
