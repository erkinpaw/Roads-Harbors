package org.webtrade.minecraftportsmod.client.render;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.webtrade.minecraftportsmod.combat.SailorEntity;

/** A warship's sailor: a man in one of the game's default skins, what his post gives him in hand. */
public class SailorRenderer extends HumanoidMobRenderer<SailorEntity, AvatarRenderState, PlayerModel> {

    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    private static final PlayerSkin[] PLAYER_SKINS = new PlayerSkin[SKINS.length];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            PLAYER_SKINS[i] = PlayerSkin.insecure(new ClientAsset.ResourceTexture(
                    Identifier.withDefaultNamespace("entity/player/wide/" + SKINS[i])), null, null, PlayerModelType.WIDE);
        }
    }

    public SailorRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(SailorEntity entity, AvatarRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.skin = PLAYER_SKINS[Math.floorMod(entity.skin(), PLAYER_SKINS.length)];
        state.showHat = true;
        state.showJacket = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showLeftPants = true;
        state.showRightPants = true;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
