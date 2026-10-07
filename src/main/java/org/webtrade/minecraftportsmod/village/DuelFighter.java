package org.webtrade.minecraftportsmod.village;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * A resident fighting a duel, as hard as it can be made: it closes in at a run, keeps at a sword's length circling
 * the player, strikes only with its sword fully swung (a jump first for a critical blow, as a player does), raises
 * its shield after a blow and when the player has a full swing ready, sidesteps the player's swings, and when badly
 * hurt falls back and heals (twice in a duel).
 */
final class DuelFighter {

    private DuelFighter() {
    }

    /** Ticks a full swing of its sword takes; the reach of a blow. */
    static final int SWING = 12;
    static final double REACH = 3.1;

    static void tick(ResidentEntity e, ServerLevel level) {
        ResidentEntity.Duel s = e.duel;
        ServerPlayer p = s.opponent == null ? null : level.getServer().getPlayerList().getPlayer(s.opponent);
        if (p == null || p.level() != level || !p.isAlive()) return;
        s.age++;
        e.getLookControl().setLookAt(p, 12, 12);
        // (the count before the fight: it stands and waits)
        if (s.age < ResidentEntity.Duel.COUNT) {
            e.getNavigation().stop();
            return;
        }
        double d = e.distanceTo(p);
        if (s.cooldown > 0) s.cooldown--;
        if (s.dodge > 0) s.dodge--;

        // badly hurt: falls back, then heals (twice in a duel)
        if (s.heals > 0 && s.retreat == 0 && e.getHealth() < e.getMaxHealth() * 0.35F) s.retreat = 50;
        if (s.retreat > 0) {
            s.retreat--;
            lower(e, s);
            Vec3 away = e.position().subtract(p.position()).normalize().scale(8).add(e.position());
            e.getNavigation().moveTo(away.x, away.y, away.z, 1.3);
            e.setSprinting(true);
            if (s.retreat == 20) {
                s.heals--;
                e.heal(8);
                e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                level.playSound(null, e.blockPosition(), SoundEvents.GENERIC_DRINK.value(), SoundSource.HOSTILE, 1F, 1F);
            }
            return;
        }

        // the shield up a while: blows from the front glance off it
        if (s.block > 0) {
            s.block--;
            if (!e.isUsingItem()) e.startUsingItem(InteractionHand.OFF_HAND);
            if (s.block == 0) lower(e, s);
            e.getMoveControl().strafe(-0.2F, s.side * 0.25F);
            return;
        }

        // the player's swing coming: a sidestep now and then
        if (p.swinging && d < REACH + 0.6 && s.dodge == 0 && e.onGround() && e.getRandom().nextFloat() < 0.15F) {
            Vec3 to = p.position().subtract(e.position()).normalize();
            Vec3 side = new Vec3(-to.z, 0, to.x).scale(s.side * 0.3);
            e.setDeltaMovement(e.getDeltaMovement().add(side.x, 0.2, side.z));
            s.dodge = 60;
        }
        // the player's full swing ready, near: the shield up at times
        if (d < REACH + 0.8 && s.cooldown > 4 && p.getAttackStrengthScale(0) > 0.9F && e.getRandom().nextFloat() < 0.03F) {
            s.block = 8 + e.getRandom().nextInt(8);
            return;
        }

        if (d > REACH - 0.3) {
            // in: at a run
            e.getNavigation().moveTo(p, d > 5 ? 1.15 : 1.0);
            e.setSprinting(d > 4);
        } else {
            // at a sword's length: circling, now one way, now the other
            e.getNavigation().stop();
            e.setSprinting(false);
            if (--s.turn <= 0) {
                s.side = e.getRandom().nextBoolean() ? 1 : -1;
                s.turn = 50 + e.getRandom().nextInt(50);
            }
            e.getMoveControl().strafe(d < 2.2 ? -0.25F : 0.1F, s.side * 0.35F);
        }

        // the blow: with the sword fully swung; a jump first, and struck falling (a critical blow)
        if (s.cooldown == 0 && d <= REACH + 0.6) {
            if (!s.leaping && e.onGround() && e.getRandom().nextFloat() < 0.5F) {
                e.jumpFromGround();
                s.leaping = true;
                return;
            }
            boolean falling = s.leaping && e.getDeltaMovement().y < 0 && !e.onGround();
            if ((falling || !s.leaping) && d <= REACH) {
                strike(e, p, level, falling);
                s.leaping = false;
                s.cooldown = SWING;
                // and the shield up after it, at times (the player's answer glances off)
                if (e.getRandom().nextFloat() < 0.25F) s.block = 8 + e.getRandom().nextInt(8);
            } else if (s.leaping && e.onGround()) {
                s.leaping = false;
            }
        }
    }

    private static void lower(ResidentEntity e, ResidentEntity.Duel s) {
        if (e.isUsingItem()) e.stopUsingItem();
        s.block = 0;
    }

    private static void strike(ResidentEntity e, ServerPlayer p, ServerLevel level, boolean crit) {
        e.swing(InteractionHand.MAIN_HAND);
        float dmg = (float) e.getAttributeValue(Attributes.ATTACK_DAMAGE) * (crit ? 1.5F : 1F);
        p.hurtServer(level, e.damageSources().mobAttack(e), dmg);
        if (crit) {
            level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1.2, p.getZ(), 12, 0.3, 0.4, 0.3, 0.2);
            level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1F, 1F);
        } else {
            level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1F, 1F);
        }
    }
}
