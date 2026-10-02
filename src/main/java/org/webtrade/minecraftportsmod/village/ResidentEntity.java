package org.webtrade.minecraftportsmod.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.webtrade.minecraftportsmod.economy.Profession;

import java.util.Optional;
import java.util.UUID;

/**
 * A resident of one of our settlements. Looks like a player (one of the default skins) and lives by the clock:
 * works at its trade by day (in the field, on the pier with a rod, at a tree with an axe, at the workshop's
 * blocks...), goes home in the evening and sleeps in its own bed at night. A skipper sails the settlement's
 * trade vessel and goes ashore in every port to do the deals at the market square.
 * Talking to any resident opens the settlement's market and orders.
 */
public class ResidentEntity extends PathfinderMob {

    /** When it last tried to walk somewhere and wasn't there yet (game time): a body standing still then is stuck. */
    public long walking;
    /** The part of the day its way-finding is set for (see {@link org.webtrade.minecraftportsmod.colony.Routine}). */
    public org.webtrade.minecraftportsmod.colony.Routine.Mode routine;

    public static final int SKINS = 9;
    private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PROFESSION = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    /** Village residents (the new villages): job (-1: none yet), child, head of the village, what they are doing. */
    private static final EntityDataAccessor<Integer> DATA_JOB = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_CHILD = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ELDER = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_COLONY = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Component> DATA_ACTIVITY = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.COMPONENT);

    /** What kind of place the resident works at. */
    public enum Work {
        /** A field: tends the wheat. */
        FIELD,
        /** The pier: fishes. */
        FISH,
        /** Trees around the village: fells them (swings at a trunk). */
        TREE,
        /** A work block in the workshop or a house. */
        WORKSHOP,
        /** The sheep pen. */
        PEN,
        /** The port office: keeps the books. */
        OFFICE,
        /** Nothing special: hangs about the square. */
        SQUARE
    }

    /** The settlement (= port id) this resident belongs to. */
    int settlement = -1;
    Work work = Work.SQUARE;
    /** Where the work is done, and what is worked at (may be null). */
    BlockPos job, workTarget;
    /** Head of the resident's bed. */
    BlockPos home;
    /** For a skipper: the trade vessel it sails. */
    UUID vessel;
    /** For a skipper: the stop (the run's departure time) it has already done its deals at. */
    long visitedStop = -1;
    /** What the resident is up to, for logs. */
    String debug = "";
    /** The village (of the new kind) and the person in it this body belongs to; -1 for settlement residents. */
    int colonyVillage = -1, colonyDweller = -1;

    public ResidentEntity(EntityType<? extends ResidentEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        // through doors (into the houses), around water (not wading across lakes with an armful of logs)
        if (getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav) nav.setCanOpenDoors(true);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 12.0F);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER, 2.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // a long follow range: paths to the fields, the pier and the market are longer than a mob's usual 16 blocks
        return createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.OpenDoorGoal(this, true));
        org.webtrade.minecraftportsmod.colony.DwellerGoals.register(this, goalSelector);
        goalSelector.addGoal(1, new ResidentGoals.Skipper(this));
        goalSelector.addGoal(2, new ResidentGoals.Sleep(this));
        goalSelector.addGoal(3, new ResidentGoals.Lunch(this));
        goalSelector.addGoal(4, new ResidentGoals.Work(this));
        goalSelector.addGoal(5, new ResidentGoals.StayNearHome(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.4));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        if (colony() && tickCount % 20 == 0) org.webtrade.minecraftportsmod.colony.Routine.apply(this);
        super.customServerAiStep(level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, 0);
        builder.define(DATA_PROFESSION, 0);
        builder.define(DATA_JOB, -1);
        builder.define(DATA_CHILD, false);
        builder.define(DATA_ELDER, false);
        builder.define(DATA_COLONY, false);
        builder.define(DATA_ACTIVITY, Component.empty());
    }

    /** Sets who this is; called once when the village is settled. */
    public void setup(int settlement, Profession profession, int skin, String name) {
        this.settlement = settlement;
        entityData.set(DATA_PROFESSION, profession.ordinal());
        entityData.set(DATA_SKIN, Math.floorMod(skin, SKINS));
        setCustomName(Component.literal(name));
    }

    // ------------------------------------------------------------------ residents of the new villages

    public boolean colony() {
        return colonyVillage >= 0 || entityData.get(DATA_COLONY);
    }

    public int colonyVillage() {
        return colonyVillage;
    }

    public int colonyDweller() {
        return colonyDweller;
    }

    /** Makes this body the person of a village, as the village's record says. */
    public void syncColony(org.webtrade.minecraftportsmod.colony.Dweller d, org.webtrade.minecraftportsmod.colony.Village v, long today) {
        colonyVillage = v.id;
        colonyDweller = d.id;
        settlement = -1;
        if (!entityData.get(DATA_COLONY)) entityData.set(DATA_COLONY, true);
        entityData.set(DATA_SKIN, Math.floorMod(d.skin, SKINS));
        int job = d.job() == null ? -1 : d.job().ordinal();
        if (entityData.get(DATA_JOB) != job) entityData.set(DATA_JOB, job);
        boolean child = d.child(today) || d.job() == null;
        if (entityData.get(DATA_CHILD) != child) {
            entityData.set(DATA_CHILD, child);
            refreshDimensions();
        }
        if (entityData.get(DATA_ELDER) != d.elder()) entityData.set(DATA_ELDER, d.elder());
        if (getCustomName() == null || !getCustomName().getString().equals(d.name)) setCustomName(Component.literal(d.name));
        setCustomNameVisible(true);
    }

    /** The job of a village resident, or null (a child). */
    public org.webtrade.minecraftportsmod.colony.Job colonyJob() {
        int j = entityData.get(DATA_JOB);
        var all = org.webtrade.minecraftportsmod.colony.Job.values();
        return j < 0 || j >= all.length ? null : all[j];
    }

    public boolean elder() {
        return entityData.get(DATA_ELDER);
    }

    public Component activity() {
        return entityData.get(DATA_ACTIVITY);
    }

    public void setActivity(Component c) {
        if (!entityData.get(DATA_ACTIVITY).equals(c)) entityData.set(DATA_ACTIVITY, c);
    }

    @Override
    public boolean isBaby() {
        return entityData.get(DATA_CHILD);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_CHILD.equals(key)) refreshDimensions();
    }

    public void setWork(Work work, BlockPos job, BlockPos target) {
        this.work = work;
        this.job = job;
        this.workTarget = target;
    }

    public void setHome(BlockPos bed) {
        this.home = bed;
    }

    public void setVessel(UUID vessel) {
        this.vessel = vessel;
    }

    public int skin() {
        return entityData.get(DATA_SKIN);
    }

    public Profession profession() {
        int p = entityData.get(DATA_PROFESSION);
        return Profession.values()[Math.max(0, Math.min(p, Profession.values().length - 1))];
    }

    public int settlement() {
        return settlement;
    }

    public UUID vessel() {
        return vessel;
    }

    public boolean isSkipper() {
        return vessel != null;
    }

    /** The tool of the resident's trade, held while working. */
    ItemStack tool() {
        return new ItemStack(switch (profession()) {
            case WOODCUTTER, SAWYER -> Items.IRON_AXE;
            case MINER -> Items.IRON_PICKAXE;
            case MASON -> Items.STONE_PICKAXE;
            case FARMER -> Items.IRON_HOE;
            case FISHER -> Items.FISHING_ROD;
            case SHEPHERD -> Items.SHEARS;
            case SMITH -> Items.IRON_INGOT;
            case SMELTER -> Items.COAL;
            case POTTER -> Items.CLAY_BALL;
            case BAKER -> Items.WHEAT;
            case MERCHANT -> Items.BOOK;
        });
    }

    public void hold(ItemStack stack) {
        if (!ItemStack.isSameItemSameComponents(getMainHandItem(), stack)) setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    /** Time of day, 0..23999 (0 = sunrise, 6000 noon, 12000 sunset). */
    public int dayTime() {
        return (int) (level().getOverworldClockTime() % 24000L);
    }

    boolean workHours() {
        int t = dayTime();
        return t >= 1000 && t < 11000;
    }

    /** Midday break: everyone meets on the square. */
    boolean lunchTime() {
        int t = dayTime();
        return t >= 5600 && t < 6600;
    }

    boolean night() {
        int t = dayTime();
        return t >= 12000 && t < 23500;
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("settlement", settlement);
        output.putInt("skin", skin());
        output.putInt("profession", entityData.get(DATA_PROFESSION));
        output.putString("work", work.name());
        output.storeNullable("job", BlockPos.CODEC, job);
        output.storeNullable("work_target", BlockPos.CODEC, workTarget);
        output.storeNullable("home", BlockPos.CODEC, home);
        output.storeNullable("vessel", UUIDUtil.CODEC, vessel);
        output.putLong("visited_stop", visitedStop);
        output.putInt("colony_village", colonyVillage);
        output.putInt("colony_dweller", colonyDweller);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlement = input.getIntOr("settlement", -1);
        entityData.set(DATA_SKIN, input.getIntOr("skin", 0));
        entityData.set(DATA_PROFESSION, input.getIntOr("profession", 0));
        try {
            work = Work.valueOf(input.getStringOr("work", Work.SQUARE.name()));
        } catch (IllegalArgumentException e) {
            work = Work.SQUARE;
        }
        job = input.read("job", BlockPos.CODEC).orElse(null);
        workTarget = input.read("work_target", BlockPos.CODEC).orElse(null);
        home = input.read("home", BlockPos.CODEC).orElse(null);
        vessel = input.read("vessel", UUIDUtil.CODEC).orElse(null);
        visitedStop = input.getLongOr("visited_stop", -1);
        colonyVillage = input.getIntOr("colony_village", -1);
        colonyDweller = input.getIntOr("colony_dweller", -1);
    }

    // ------------------------------------------------------------------ interaction

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (isSleeping()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer sp) {
            getNavigation().stop();
            lookAt(player, 30, 30);
            if (colony()) org.webtrade.minecraftportsmod.colony.ColonyService.openDweller(sp, this);
            else MarketService.open(sp, this);
        }
        return InteractionResult.SUCCESS;
    }

    /** Only the world itself (void, /kill) can hurt a resident; players and mobs can't. */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(level, source);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
        // tools are part of the trade, not loot
    }

    /** One line about what the resident is up to (logs, tests). */
    public String describe() {
        String where = job == null ? "-" : String.format(java.util.Locale.ROOT, "%.0f", Math.sqrt(distanceToSqr(job.getX(), job.getY(), job.getZ())));
        return (getCustomName() == null ? "?" : getCustomName().getString()) + " " + profession() + " work=" + work + " toJob=" + where
                + (isSleeping() ? " SLEEPING" : "") + (isPassenger() ? " aboard" : "") + (isSkipper() ? " skipper" : "")
                + " holds=" + getMainHandItem().getItem() + " home=" + (home != null)
                + " pos=" + blockPosition().toShortString() + " nav=" + (getNavigation().isDone() ? "done" : "moving") + " " + debug;
    }

    static Optional<BlockPos> opt(BlockPos p) {
        return Optional.ofNullable(p);
    }
}
