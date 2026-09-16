package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * How an arena's giant fights: it lumbers after its target and, once in reach, marks the ground
 * where the target stands, which cracks for a second, then smashes it. Everyone on the spot is
 * hurt and thrown, and the ground there breaks, and whatever was built on it.
 *
 * <p>What the smash won't break: anything holding something (chests, pinatas, a Dead Heads head,
 * beds), anything a creeper couldn't break either, a wall still standing, a flag chest or a base
 * cube, or anything outside its own arena.
 */
public final class GiantSmash extends Goal {
	private static final double WALK = 0.6;
	private static final double REACH = 7;
	private static final double REACH_UP = 14;
	private static final int WIND_UP = 20;
	private static final int COOLDOWN = 40;
	/** How far from the spot the ground breaks, and how far the blow lands. */
	private static final double CRATER = 2.5;
	private static final double BLOW = 3.5;
	private static final float HARDEST = 20;
	private static final float CENTRE_DAMAGE = 16;
	private static final float EDGE_DAMAGE = 6;

	private final Giant giant;
	private int windUp;
	private int cooldown;
	private @Nullable Vec3 spot;
	private final List<BlockPos> crater = new ArrayList<>();

	public GiantSmash(Giant giant) {
		this.giant = giant;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		LivingEntity target = giant.getTarget();
		return target != null && target.isAlive() && giant.canAttack(target);
	}

	@Override
	public boolean canContinueToUse() {
		return windUp > 0 || canUse();
	}

	@Override
	public void stop() {
		if (windUp > 0 && giant.level() instanceof ServerLevel level) uncrack(level);
		windUp = 0;
		spot = null;
		giant.getNavigation().stop();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (!(giant.level() instanceof ServerLevel level)) return;
		if (windUp > 0 && spot != null) {
			giant.getMoveControl().setWait();
			giant.getLookControl().setLookAt(spot);
			windUp--;
			if (windUp == 0) smash(level, spot);
			else crack(level, WIND_UP - windUp);
			return;
		}
		LivingEntity target = giant.getTarget();
		if (target == null) return;
		giant.getLookControl().setLookAt(target, 30, 30);
		if (cooldown > 0) cooldown--;
		double across = Math.hypot(target.getX() - giant.getX(), target.getZ() - giant.getZ());
		double up = target.getY() - giant.getY();
		if (cooldown == 0 && across <= REACH && up <= REACH_UP && up >= -REACH) {
			giant.getNavigation().stop();
			spot = target.position();
			windUp = WIND_UP;
			pickCrater(level, BlockPos.containing(spot));
			level.playSound(null, giant.getX(), giant.getY(), giant.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2F, 0.5F);
		} else if (giant.tickCount % 10 == 0 || giant.getNavigation().isDone()) {
			giant.getNavigation().moveTo(target, WALK);
		}
	}

	/** The blocks the smash will take: a ragged bowl round the spot, shallow under it, up to head height and a bit over. */
	private void pickCrater(ServerLevel level, BlockPos centre) {
		crater.clear();
		int reach = (int) Math.ceil(CRATER);
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					double sink = dy < 0 ? dy * 1.6 : 0;
					double distance = dx * dx + dz * dz + sink * sink;
					if (distance > CRATER * CRATER * (0.7 + giant.getRandom().nextDouble() * 0.5)) continue;
					BlockPos pos = centre.offset(dx, dy, dz);
					if (breakable(level, pos)) crater.add(pos);
				}
			}
		}
	}

	private boolean breakable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
		if (state.getDestroySpeed(level, pos) < 0 || state.getBlock().getExplosionResistance() > HARDEST) return false;
		Arena arena = Arenas.at(level, pos.getX(), pos.getZ());
		if (arena == null || Goals.protectedBlock(arena, pos, null)) return false;
		Footprint footprint = arena.footprint();
		BlockState wall = arena.wallsDown ? null : footprint.terrain().wallAt(footprint, pos.getX(), pos.getZ());
		return wall == null || state != wall;
	}

	/** The ground under the spot cracking as the giant winds up: a warning, and a second to get out. */
	private void crack(ServerLevel level, int tick) {
		int stage = tick * 10 / WIND_UP;
		for (int i = 0; i < crater.size(); i++) {
			BlockPos pos = crater.get(i);
			if (pos.getY() < spot.y) level.destroyBlockProgress(crackId(i), pos, stage);
		}
		if (tick % 4 == 0) {
			BlockState ground = level.getBlockState(BlockPos.containing(spot).below());
			if (!ground.isAir()) {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), spot.x, spot.y + 0.1, spot.z, 12, CRATER / 2, 0.05, CRATER / 2, 0.1);
			}
		}
	}

	private void uncrack(ServerLevel level) {
		for (int i = 0; i < crater.size(); i++) level.destroyBlockProgress(crackId(i), crater.get(i), -1);
	}

	/** The game shows one crack per id, so each block of the crater gets its own, well clear of any entity's. */
	private int crackId(int index) {
		return -(giant.getId() * 64 + index + 1);
	}

	private void smash(ServerLevel level, Vec3 at) {
		giant.swingForAttack(InteractionHand.MAIN_HAND);
		uncrack(level);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 3F, 0.6F);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1F, 0.7F);
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 4, 1.2, 0.3, 1.2, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.3, at.z, 20, CRATER / 2, 0.2, CRATER / 2, 0.05);

		AABB reach = new AABB(at.x - BLOW, at.y - 2, at.z - BLOW, at.x + BLOW, at.y + 4, at.z + BLOW);
		for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, reach, entity -> entity != giant && entity.isAlive() && !Mobs.sameSide(giant, entity))) {
			double across = Math.hypot(hit.getX() - at.x, hit.getZ() - at.z);
			if (across > BLOW) continue;
			float damage = (float) (CENTRE_DAMAGE - (CENTRE_DAMAGE - EDGE_DAMAGE) * across / BLOW);
			if (!hit.hurtServer(level, giant.damageSources().mobAttack(giant), damage)) continue;
			double away = across < 0.1 ? 0 : 0.6 / across;
			hit.push((hit.getX() - at.x) * away, 0.6, (hit.getZ() - at.z) * away);
			hit.needsSync = true;
		}

		for (BlockPos pos : crater) {
			if (breakable(level, pos)) level.destroyBlock(pos, false, giant);
		}
		crater.clear();
		cooldown = COOLDOWN;
		spot = null;
	}
}
