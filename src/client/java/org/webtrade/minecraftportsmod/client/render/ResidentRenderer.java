package org.webtrade.minecraftportsmod.client.render;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/** Residents look like players, each wearing one of the game's default skins. */
public class ResidentRenderer extends HumanoidMobRenderer<ResidentEntity, AvatarRenderState, PlayerModel> {

    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    private static final PlayerSkin[] PLAYER_SKINS = new PlayerSkin[SKINS.length];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            // the player model takes its texture from the render state's skin, like a real player's
            PLAYER_SKINS[i] = PlayerSkin.insecure(new ClientAsset.ResourceTexture(
                    Identifier.withDefaultNamespace("entity/player/wide/" + SKINS[i])), null, null, PlayerModelType.WIDE);
        }
    }

    private static final float CHILD_SCALE = 0.55F;

    public ResidentRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(ResidentEntity entity, AvatarRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.skin = PLAYER_SKINS[Math.floorMod(entity.skin(), PLAYER_SKINS.length)];
        state.showHat = true;
        state.showJacket = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        // children are half as tall (the whole model is scaled, like the scale attribute does)
        if (entity.isBaby()) state.scale *= CHILD_SCALE;
        if (entity.colony() && entity.getCustomName() != null) {
            // name · trade on top, what they are doing below
            net.minecraft.network.chat.MutableComponent tag = Component.empty();
            // a task to take: a yellow mark; one being done: a grey one
            if (entity.questMark() == 1) tag.append(Component.literal("! ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            if (entity.questMark() == 2) tag.append(Component.literal("? ").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
            if (entity.elder()) tag.append(Component.literal("★ ").withStyle(ChatFormatting.GOLD));
            tag.append(entity.getCustomName().copy().withStyle(entity.elder() ? ChatFormatting.GOLD : ChatFormatting.WHITE));
            var job = entity.colonyJob();
            tag.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            tag.append((job == null ? Component.translatable("minecraftportsmod.job.child") : job.displayName()).copy()
                    .withStyle(ChatFormatting.GRAY));
            if (state.nameTag != null) {
                state.nameTag = tag;
                Component doing = entity.activity();
                state.scoreText = doing.getString().isEmpty() ? null : doing.copy().withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW);
            }
        }
    }


    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
