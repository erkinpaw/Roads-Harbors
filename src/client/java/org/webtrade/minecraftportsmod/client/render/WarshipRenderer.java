package org.webtrade.minecraftportsmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.webtrade.minecraftportsmod.Minecraftportsmod;
import org.webtrade.minecraftportsmod.combat.WarshipEntity;

/**
 * A warship: the brig's model drawn {@link WarshipEntity#MODEL_SCALE} times as big, black sails for a pirate, a row
 * of guns along each side; rolling on the waves, heeling as she turns, and going down by the stern when she sinks.
 */
public class WarshipRenderer extends EntityRenderer<WarshipEntity, WarshipRenderer.State> {

    private static final Identifier TEXTURE = Minecraftportsmod.id("textures/entity/ship/brig.png");
    private static final Identifier PIRATE = Minecraftportsmod.id("textures/entity/ship/brig_pirate.png");
    /** As the fleet's ships: drawn higher than a boat so the deck clears the water. */
    private static final float LIFT = 0.3F;

    public static class State extends VesselRenderState {
        public boolean pirate;
        public int sinking;
        public float heel;
        public final ItemStackRenderState gun = new ItemStackRenderState();
    }

    private final BoatRenderer boat;
    private final ShipModel model;
    private final net.minecraft.client.renderer.item.ItemModelResolver items;

    public WarshipRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        boat = new BoatRenderer(ctx, ModelLayers.OAK_BOAT);
        model = new ShipModel(ctx.bakeLayer(VesselRenderer.BRIG_LAYER), VesselRenderer.brigSpec());
        items = ctx.getItemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(WarshipEntity ship, State state, float partialTick) {
        boat.extractRenderState(ship, state, partialTick);
        state.tier = 2;
        state.sailing = ship.sails() > 0;
        state.pirate = ship.isPirate();
        state.sinking = ship.sinking();
        // heeling over in a turn: the faster and the harder, the more
        float turn = Mth.wrapDegrees(ship.getYRot() - ship.yRotO);
        state.heel = Mth.clamp(turn * 4F, -8F, 8F);
        items.updateForNonLiving(state.gun, new ItemStack(Items.COAL_BLOCK), ItemDisplayContext.FIXED, ship);
    }

    @Override
    protected float getShadowRadius(State state) {
        return 0;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        float t = state.ageInTicks;
        float amp = state.sailing ? 1.4F : 0.7F;
        float roll = Mth.sin(t * 0.04F) * amp + state.heel;
        float pitch = Mth.sin(t * 0.055F + 1.3F) * amp * 0.5F;
        if (state.sinking > 0) {
            // down by the stern, listing over
            roll += Math.min(30F, state.sinking * 0.35F);
            pitch -= Math.min(18F, state.sinking * 0.2F);
        }
        // the guns, along both sides (in the ship's frame: z to the bow, starboard to -x), barrels out over the side
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        pose.mulPose(Axis.ZP.rotationDegrees(-roll));
        pose.mulPose(Axis.XP.rotationDegrees(-pitch));
        for (int side : new int[]{-1, 1}) {
            for (int g = 0; g < WarshipEntity.GUNS; g++) {
                pose.pushPose();
                pose.translate(-side * (WarshipEntity.HALF_BEAM - 0.15), WarshipEntity.DECK + 0.35, WarshipEntity.gunAlong(g));
                pose.scale(1.4F, 0.4F, 0.4F);
                state.gun.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
                pose.popPose();
            }
        }
        pose.popPose();

        pose.pushPose();
        pose.translate(0, 0.375 + LIFT, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.yRot));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        float s = model.scale() * WarshipEntity.MODEL_SCALE;
        pose.scale(s, s, s);
        collector.submitModel(model, state, pose, state.pirate ? PIRATE : TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    @Override
    protected AABB getBoundingBoxForCulling(WarshipEntity ship) {
        return ship.getBoundingBox().inflate(9, 0, 9).expandTowards(0, 19, 0);
    }
}
