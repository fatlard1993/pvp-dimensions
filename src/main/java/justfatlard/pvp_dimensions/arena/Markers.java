package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The pad a goal is played on: the hill to hold, or a race's finish. Three blocks by three with a
 * beacon at its middle, so its beam can be seen from anywhere in the arena, and nothing about it
 * can be broken. The hill is gold; the finish, emerald.
 *
 * <p>{@link Arena#marker} is the beacon. The pad's top is level with it, its base the layer under.
 */
public final class Markers {
	private Markers() {}

	/** How far over the ground a finish in the sky floats, and how far under it a buried one lies. */
	private static final int SKY = 20;
	private static final int BURIED = 12;
	private static final int TOWER = 24;

	/** A finish in the sky or underground can't show a beam; a column of sparks marks it instead. */
	public static void tick(ServerLevel level, Arena arena, long now) {
		if (arena.marker == null) return;
		if (now / 1000 % 5 == 0) repair(level, arena);
		BlockPos beacon = arena.marker;
		for (int i = 0; i < 3; i++) {
			level.sendParticles(ParticleTypes.END_ROD, beacon.getX() + 0.5, beacon.getY() + 1.2 + i * 0.6, beacon.getZ() + 0.5, 2, 0.6, 0.2, 0.6, 0.01);
		}
		if (arena.preset.activeGoal() == Preset.Goal.RACE && arena.preset.finishStyle == Preset.FinishStyle.BURIED) {
			int top = Builds.ground(level, arena, beacon.getX(), beacon.getZ()) + 10;
			for (int y = beacon.getY() + 3; y < top; y += 2) {
				level.sendParticles(ParticleTypes.END_ROD, beacon.getX() + 0.5, y, beacon.getZ() + 0.5, 1, 0.1, 0.4, 0.1, 0);
			}
		}
	}

	/** The hill, where the preset puts it, the first time or moved: the old pad back to ground first. */
	public static void placeHill(ServerLevel level, Arena arena, boolean anywhere) {
		if (arena.marker != null) remove(level, arena.marker);
		int[] at = anywhere ? spot(level, arena, 3) : middle(arena, 3);
		BlockPos beacon = new BlockPos(at[0], Builds.ground(level, arena, at[0], at[1]) - 1, at[1]);
		pad(level, beacon, Blocks.GOLD_BLOCK.defaultBlockState(), true);
		arena.marker = beacon;
	}

	/** The finish: on the ground, atop a tower, floating over the ground, or buried under it. */
	public static void placeFinish(ServerLevel level, Arena arena) {
		Preset preset = arena.preset;
		int reach = preset.finishStyle == Preset.FinishStyle.TOWER ? 6 : 3;
		int[] at = preset.finishPlace == Preset.MarkerPlace.RANDOM ? spot(level, arena, reach) : middle(arena, reach);
		int ground = Builds.ground(level, arena, at[0], at[1]);
		BlockState emerald = Blocks.EMERALD_BLOCK.defaultBlockState();
		BlockPos beacon = switch (preset.finishStyle) {
			case GROUND -> new BlockPos(at[0], ground - 1, at[1]);
			case TOWER -> TeamBases.raceTower(level, preset.world, new BlockPos(at[0], ground, at[1]), Builds.front(arena, at[0], at[1]), TOWER);
			case SKY -> new BlockPos(at[0], Math.min(ground + SKY, arena.wallTop - 4), at[1]);
			case BURIED -> new BlockPos(at[0], Math.max(ground - BURIED, level.getMinY() + 3), at[1]);
		};
		pad(level, beacon, emerald, preset.finishStyle != Preset.FinishStyle.BURIED);
		if (preset.finishStyle == Preset.FinishStyle.BURIED) {
			// A little room over it, so whoever digs down to it has somewhere to stand.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int up = 1; up <= 2; up++) Builds.air(level, beacon.offset(dx, up, dz));
				}
			}
		}
		arena.marker = beacon;
	}

	/**
	 * The pad itself: its base, the beacon with a ring round it, room to stand on it and, where
	 * the beam should show, nothing over the beacon to hide it.
	 */
	private static void pad(ServerLevel level, BlockPos beacon, BlockState base, boolean beam) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(beacon.offset(dx, -1, dz), base, Block.UPDATE_ALL);
				level.setBlock(beacon.offset(dx, 0, dz), dx == 0 && dz == 0 ? Blocks.BEACON.defaultBlockState() : base, Block.UPDATE_ALL);
				for (int up = 1; up <= 2; up++) Builds.air(level, beacon.offset(dx, up, dz));
			}
		}
		if (beam) {
			for (int up = 3; up <= 48; up++) {
				BlockPos above = beacon.above(up);
				if (level.isOutsideBuildHeight(above)) break;
				BlockState state = level.getBlockState(above);
				if (state.is(Blocks.BEDROCK)) break;
				if (!state.isAir() && state.canOcclude()) Builds.air(level, above);
			}
		}
	}

	/** The pad gone, its place filled with the ground beside it. */
	private static void remove(ServerLevel level, BlockPos beacon) {
		BlockState top = level.getBlockState(beacon.offset(2, 0, 0));
		BlockState under = level.getBlockState(beacon.offset(2, -1, 0));
		if (!Spawns.solid(top)) top = Blocks.DIRT.defaultBlockState();
		if (!Spawns.solid(under)) under = Blocks.DIRT.defaultBlockState();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(beacon.offset(dx, 0, dz), top, Block.UPDATE_ALL);
				level.setBlock(beacon.offset(dx, -1, dz), under, Block.UPDATE_ALL);
			}
		}
	}

	/** Whether this block is part of the pad, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		BlockPos beacon = arena.marker;
		if (beacon == null) return false;
		return Math.abs(pos.getX() - beacon.getX()) <= 1 && Math.abs(pos.getZ() - beacon.getZ()) <= 1
			&& (pos.getY() == beacon.getY() || pos.getY() == beacon.getY() - 1);
	}

	/** Whether a player is standing on the pad, or jumping on it. */
	public static boolean on(Arena arena, ServerPlayer player) {
		BlockPos beacon = arena.marker;
		if (beacon == null || !player.isAlive() || player.isSpectator()) return false;
		BlockPos feet = player.blockPosition();
		return Math.abs(feet.getX() - beacon.getX()) <= 1 && Math.abs(feet.getZ() - beacon.getZ()) <= 1
			&& feet.getY() >= beacon.getY() + 1 && feet.getY() <= beacon.getY() + 2;
	}

	/** Everyone fighting who is on the pad now. */
	public static List<Arena.Member> standing(MinecraftServer server, Arena arena) {
		List<Arena.Member> on = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null && player.level().dimension().equals(arena.dimension) && on(arena, player)) on.add(member);
		}
		return on;
	}

	/**
	 * Where a race starts: everyone the same way from the finish, round a ring as wide as the
	 * arena lets it be, each team its own side of it.
	 */
	public static @Nullable Vec3 raceStart(ServerLevel level, Arena arena, int team) {
		BlockPos finish = arena.marker;
		if (finish == null) return null;
		Footprint footprint = arena.footprint();
		double toEdge = Math.min(Math.min(finish.getX() - footprint.minX(), footprint.maxX() - finish.getX()),
			Math.min(finish.getZ() - footprint.minZ(), footprint.maxZ() - finish.getZ())) - 6;
		double radius = Math.max(8, Math.min(footprint.width() * 0.4, toEdge));
		double angle = team >= 0 && arena.preset.teamsOn()
			? Math.PI * 2 * team / arena.preset.teams + Math.PI / 4
			: level.getRandom().nextDouble() * Math.PI * 2;
		int x = (int) (finish.getX() + Math.cos(angle) * radius);
		int z = (int) (finish.getZ() + Math.sin(angle) * radius);
		return Spawns.near(level, arena, x, z, 4);
	}

	/**
	 * The middle, or the nearest place to it with {@code reach} of open ground round it: where
	 * slices of a pie meet, the middle itself is all wall.
	 */
	private static int[] middle(Arena arena, int reach) {
		Footprint footprint = arena.footprint();
		int x = (int) Math.floor(footprint.centerX());
		int z = (int) Math.floor(footprint.centerZ());
		for (int ring = 0; ring <= footprint.width() / 3; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && Builds.room(arena, x + dx, z + dz, reach) >= reach) return new int[] {x + dx, z + dz};
				}
			}
		}
		return new int[] {x, z};
	}

	/** Somewhere a pad fits, away from the walls and the border. */
	private static int[] spot(ServerLevel level, Arena arena, int reach) {
		for (int attempt = 0; attempt < 30; attempt++) {
			Vec3 spot = Spawns.random(level, arena);
			int x = (int) Math.floor(spot.x);
			int z = (int) Math.floor(spot.z);
			if (Builds.room(arena, x, z, reach) >= reach) return new int[] {x, z};
		}
		return middle(arena, reach);
	}

	/** The pad put back as it was, where something blew a hole in it. */
	public static void repair(ServerLevel level, Arena arena) {
		BlockPos beacon = arena.marker;
		if (beacon == null || !level.hasChunk(beacon.getX() >> 4, beacon.getZ() >> 4)) return;
		BlockState base = level.getBlockState(beacon.offset(1, 0, 0));
		if (!base.is(Blocks.GOLD_BLOCK) && !base.is(Blocks.EMERALD_BLOCK)) {
			base = arena.preset.activeGoal() == Preset.Goal.HILL ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.EMERALD_BLOCK.defaultBlockState();
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockState top = dx == 0 && dz == 0 ? Blocks.BEACON.defaultBlockState() : base;
				if (level.getBlockState(beacon.offset(dx, 0, dz)) != top) level.setBlock(beacon.offset(dx, 0, dz), top, Block.UPDATE_ALL);
				if (level.getBlockState(beacon.offset(dx, -1, dz)) != base) level.setBlock(beacon.offset(dx, -1, dz), base, Block.UPDATE_ALL);
			}
		}
	}
}
