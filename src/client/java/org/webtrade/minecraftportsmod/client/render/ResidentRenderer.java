package org.webtrade.minecraftportsmod.client.render;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.webtrade.minecraftportsmod.village.ResidentEntity;

/**
 * Residents look like players, each wearing one of the game's default skins. Over a village's resident: a round badge
 * with what is in their hands, a ring round it filling as the work in hand goes on (a making at the bench, a building
 * going up); below it what they are doing; below that their trade's tool, their trade and their name.
 */
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
    /** The badge is shown this near. */
    private static final double BADGE = 20;

    /** The resident's state: the player's, and the badge over the head. */
    public static class State extends AvatarRenderState {
        boolean badge;
        Component doing = Component.empty(), title = Component.empty();
        float progress = -1;
        final ItemStackRenderState icon = new ItemStackRenderState(), jobIcon = new ItemStackRenderState();
        Vec3 at = Vec3.ZERO;
    }

    private final net.minecraft.client.renderer.item.ItemModelResolver items;

    public ResidentRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        items = context.getItemModelResolver();
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ResidentEntity entity, AvatarRenderState avatar, float partialTick) {
        super.extractRenderState(entity, avatar, partialTick);
        State state = (State) avatar;
        state.skin = PLAYER_SKINS[Math.floorMod(entity.skin(), PLAYER_SKINS.length)];
        state.showHat = true;
        state.showJacket = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        // children are half as tall (the whole model is scaled, like the scale attribute does)
        if (entity.isBaby()) state.scale *= CHILD_SCALE;
        state.badge = false;
        if (!entity.colony() || entity.getCustomName() == null) return;
        // (the badge instead of the plain name tag)
        state.nameTag = null;
        state.scoreText = null;
        if (state.distanceToCameraSq > BADGE * BADGE) return;
        state.badge = true;
        state.at = new Vec3(0, entity.getBbHeight() + 0.25, 0);
        var job = entity.colonyJob();
        net.minecraft.network.chat.MutableComponent title = Component.empty();
        // a task to take: a yellow mark; one being done: a grey one
        if (entity.questMark() == 1) title.append(Component.literal("! ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        if (entity.questMark() == 2) title.append(Component.literal("? ").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
        if (entity.elder()) title.append(Component.literal("★ ").withStyle(ChatFormatting.GOLD));
        title.append((job == null ? Component.translatable("minecraftportsmod.job.child") : job.displayName()).copy().withStyle(ChatFormatting.GRAY));
        title.append(Component.literal(" ").append(entity.getCustomName().copy().withStyle(entity.elder() ? ChatFormatting.GOLD : ChatFormatting.WHITE)));
        state.title = title;
        state.doing = entity.activity().copy().withStyle(ChatFormatting.YELLOW);
        state.progress = entity.progress();
        ItemStack held = entity.getMainHandItem();
        ItemStack tool = job == null ? ItemStack.EMPTY : new ItemStack(job.tool());
        items.updateForNonLiving(state.icon, held.isEmpty() ? tool : held, ItemDisplayContext.GUI, entity);
        items.updateForNonLiving(state.jobIcon, tool, ItemDisplayContext.GUI, entity);
        // (drawn with the buildings' badges: an avatar's state is drawn by the game's own player renderer)
        BuildingBadges.resident(state.x, state.y + state.at.y, state.z, state.title, state.jobIcon, state.doing, state.icon, state.progress,
                state.lightCoords);
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
