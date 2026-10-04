package org.webtrade.minecraftportsmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.minecraftportsmod.combat.CannonballEntity;

/** A cannonball: a small black ball (a block of coal, small). */
public class CannonballRenderer extends EntityRenderer<CannonballEntity, CannonballRenderer.State> {

    public static class State extends EntityRenderState {
        public final ItemStackRenderState ball = new ItemStackRenderState();
    }

    private final net.minecraft.client.renderer.item.ItemModelResolver items;

    public CannonballRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        items = ctx.getItemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CannonballEntity ball, State state, float partialTick) {
        super.extractRenderState(ball, state, partialTick);
        items.updateForNonLiving(state.ball, new ItemStack(Items.COAL_BLOCK), ItemDisplayContext.FIXED, ball);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0, 0.2, 0);
        pose.scale(0.7F, 0.7F, 0.7F);
        state.ball.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }
}
