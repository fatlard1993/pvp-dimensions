package justfatlard.pvp_dimensions.arena;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where the preset lets them, an arena's zombies break through what keeps them from their target.
 * One that has got no closer for a couple of seconds, with no way round, bangs on what is in its
 * way until it breaks: a closed door or trapdoor near it first, then the block ahead, then the one
 * under its feet where the target is below. Harder blocks take longer; only what
 * {@link Mobs#mayBreak} allows is touched.
 */
public final class ZombieBreaks extends Goal {
	/** Ticks without getting any closer before a zombie counts as stuck. */
	private static final int STUCK = 40;
	private static final double CLOSER = 0.5;
	private static final double REACH = 2.5;
	private static final int TICKS_PER_HARDNESS = 40;
	private static final int FASTEST = 20;
	private static final int SLOWEST = 240;
	/** Ticks between looks for something to break, for a zombie stuck behind what it may not. */
	private static final int LOOK_EVERY = 10;

	private final Zombie zombie;
	private @Nullable LivingEntity chasing;
	private double closest;
	private long closestAt;
	private long lookedAt;
	private @Nullable BlockPos block;
	private @Nullable BlockState banging;
	private int hits;
	private int needed;

	public ZombieBreaks(Zombie zombie) {
		this.zombie = zombie;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		LivingEntity target = zombie.getTarget();
		if (!stuck(target)) return false;
		ServerLevel level = getServerLevel(zombie);
		if (level.getGameTime() - lookedAt < LOOK_EVERY) return false;
		lookedAt = level.getGameTime();
		Arena arena = Arenas.at(level, zombie.getX(), zombie.getZ());
		if (arena == null || !arena.preset.zombiesBreak) return false;
		block = inTheWay(level, target);
		return block != null;
	}

	@Override
	public boolean canContinueToUse() {
		LivingEntity target = zombie.getTarget();
		return block != null && hits < needed && target != null && target.isAlive()
			&& zombie.level().getBlockState(block) == banging && block.closerToCenterThan(zombie.position(), REACH + 1);
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		banging = zombie.level().getBlockState(block);
		hits = 0;
		needed = Mth.clamp(Math.round(banging.getDestroySpeed(zombie.level(), block) * TICKS_PER_HARDNESS), FASTEST, SLOWEST);
		zombie.getNavigation().stop();
	}

	@Override
	public void stop() {
		if (block != null) zombie.level().destroyBlockProgress(zombie.getId(), block, -1);
		block = null;
		banging = null;
	}

	@Override
	public void tick() {
		ServerLevel level = getServerLevel(zombie);
		zombie.getLookControl().setLookAt(Vec3.atCenterOf(block));
		if (zombie.getRandom().nextInt(20) == 0) {
			boolean iron = banging.is(Blocks.IRON_DOOR) || banging.is(Blocks.IRON_TRAPDOOR);
			level.levelEvent(iron ? LevelEvent.SOUND_ZOMBIE_IRON_DOOR : LevelEvent.SOUND_ZOMBIE_WOODEN_DOOR, block, 0);
			if (!zombie.isSwinging()) zombie.swingForAttack(zombie.getUsedItemHand());
		}
		hits++;
		if (hits < needed) {
			level.destroyBlockProgress(zombie.getId(), block, hits * 10 / needed);
			return;
		}
		if (!Mobs.mayBreak(level, block)) return;
		if (door(banging)) level.levelEvent(LevelEvent.SOUND_ZOMBIE_DOOR_CRASH, block, 0);
		level.destroyBlock(block, false, zombie);
	}

	/** Whether it has got no closer to its target in a while, and its path doesn't get there either. */
	private boolean stuck(@Nullable LivingEntity target) {
		long now = zombie.level().getGameTime();
		if (target == null || !target.isAlive()) {
			chasing = null;
			return false;
		}
		double distance = zombie.distanceTo(target);
		if (target != chasing || distance < closest - CLOSER) {
			chasing = target;
			closest = distance;
			closestAt = now;
			return false;
		}
		if (distance < REACH || now - closestAt < STUCK) return false;
		Path path = zombie.getNavigation().getPath();
		return path == null || !path.canReach() || path.isDone();
	}

	private @Nullable BlockPos inTheWay(ServerLevel level, LivingEntity target) {
		BlockPos feet = zombie.blockPosition();
		double towardX = target.getX() - zombie.getX();
		double towardZ = target.getZ() - zombie.getZ();

		BlockPos nearestDoor = null;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 2, 2))) {
			BlockState state = level.getBlockState(pos);
			if (!door(state) || state.getValue(BlockStateProperties.OPEN)) continue;
			if (!pos.closerToCenterThan(zombie.position(), REACH)) continue;
			if ((pos.getX() + 0.5 - zombie.getX()) * towardX + (pos.getZ() + 0.5 - zombie.getZ()) * towardZ < 0) continue;
			if (!Mobs.mayBreak(level, pos)) continue;
			if (nearestDoor == null || pos.distSqr(feet) < nearestDoor.distSqr(feet)) nearestDoor = pos.immutable();
		}
		if (nearestDoor != null) return nearestDoor;

		if (towardX * towardX + towardZ * towardZ > 0.25) {
			BlockPos ahead = feet.relative(Direction.getApproximateNearest(towardX, 0, towardZ));
			if (Mobs.mayBreak(level, ahead)) return ahead;
			if (zombie.getBbHeight() > 1 && Mobs.mayBreak(level, ahead.above())) return ahead.above();
		}
		if (target.getY() < zombie.getY() - 1.5 && Mobs.mayBreak(level, feet.below())) return feet.below();
		return null;
	}

	private static boolean door(BlockState state) {
		return (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) && state.hasProperty(BlockStateProperties.OPEN);
	}
}
