package org.webtrade.minecraftportsmod.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A cannonball: an iron ball flying in an arc. It holes a ship it hits (not the one that fired it), hurts whoever it
 * hits, throws up a fountain where it falls into the sea and stops dead against the land (no blocks are broken).
 * It flies the same on the server and on the client (the client only shows it); what it hits is the server's.
 */
public class CannonballEntity extends Entity {

    /** Gravity and air drag, per tick. */
    static final double GRAVITY = 0.03, DRAG = 0.995;
    /** What a ball does to a hull it holes, and to a body it strikes. */
    static final float HULL_DAMAGE = 4, BODY_DAMAGE = 8;

    /** The ship that fired it (an entity id: its hits don't count against her). */
    int ship = -1;

    public CannonballEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** How far (blocks) a ball fired at this speed and elevation flies before it falls back to the height it left. */
    static double range(double speed, double elevationDeg) {
        double e = Math.toRadians(elevationDeg);
        double vx = speed * Math.cos(e), vy = speed * Math.sin(e), x = 0, y = 0;
        for (int t = 0; t < 400; t++) {
            x += vx;
            y += vy;
            vy -= GRAVITY;
            vx *= DRAG;
            vy *= DRAG;
            if (y < -0.5) return x;
        }
        return x;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 from = position();
        Vec3 v = getDeltaMovement();
        Vec3 to = from.add(v);
        if (level() instanceof ServerLevel level) {
            if (tickCount > 200 || hit(level, from, to)) {
                discard();
                return;
            }
        } else {
            level().addParticle(ParticleTypes.SMOKE, from.x, from.y, from.z, 0, 0.01, 0);
        }
        setPos(to);
        setDeltaMovement(v.x * DRAG, (v.y - GRAVITY) * DRAG, v.z * DRAG);
    }

    /** Does the ball strike something between two points (looked at a step at a time)? */
    private boolean hit(ServerLevel level, Vec3 from, Vec3 to) {
        double len = to.subtract(from).length();
        int steps = Math.max(1, (int) Math.ceil(len / 0.5));
        for (int i = 1; i <= steps; i++) {
            Vec3 p = from.lerp(to, i / (double) steps);
            // a ship
            for (WarshipEntity s : level.getEntitiesOfClass(WarshipEntity.class, new AABB(p, p).inflate(WarshipEntity.HALF_LENGTH + 1))) {
                if (s.getId() == ship || s.sinking() > 0 || !s.hits(p)) continue;
                s.struck(level, p, HULL_DAMAGE, level.getEntity(ship));
                return true;
            }
            // somebody (not aboard the ship that fired)
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(0.6))) {
                if (e.getVehicle() != null && e.getVehicle().getId() == ship) continue;
                Entity shooter = level.getEntity(ship);
                DamageSource src = shooter instanceof WarshipEntity w && w.captain() != null
                        ? level.damageSources().explosion(this, w.captain()) : level.damageSources().explosion(this, null);
                e.hurtServer(level, src, BODY_DAMAGE);
                level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                return true;
            }
            BlockPos b = BlockPos.containing(p);
            BlockState st = level.getBlockState(b);
            // the sea: a fountain
            if (!st.getFluidState().isEmpty()) {
                level.sendParticles(ParticleTypes.SPLASH, p.x, b.getY() + 1, p.z, 40, 0.4, 0.1, 0.4, 0.3);
                level.sendParticles(ParticleTypes.BUBBLE, p.x, b.getY() + 0.5, p.z, 15, 0.3, 0.3, 0.3, 0.1);
                level.sendParticles(ParticleTypes.CLOUD, p.x, b.getY() + 1.5, p.z, 6, 0.3, 0.8, 0.3, 0.02);
                level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.4F, 0.8F + random.nextFloat() * 0.3F);
                return true;
            }
            // the land: it stops dead (nothing is broken)
            if (!st.getCollisionShape(level, b).isEmpty()) {
                level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, st), p.x, p.y, p.z, 20, 0.3, 0.3, 0.3, 0.15);
                level.sendParticles(ParticleTypes.POOF, p.x, p.y, p.z, 4, 0.2, 0.2, 0.2, 0.02);
                level.playSound(null, p.x, p.y, p.z, st.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.6F);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
