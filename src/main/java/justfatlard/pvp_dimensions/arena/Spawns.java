package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Where a player can be put down in an arena: on ground, with room to stand, not in lava. */
public final class Spawns {
	private Spawns() {}

	private static final RandomSource RANDOM = RandomSource.create();
	/** Kept this far in from the border and every wall, so nobody lands with their face in one. */
	private static final int MARGIN = 3;

	/** Where this player starts or comes back: by the preset's rule, their team, and luck. */
	public static Vec3 forPlayer(ServerLevel level, Arena arena, int team) {
		Preset preset = arena.preset;
		Footprint footprint = arena.footprint();
		if (preset.activeGoal() == Preset.Goal.RACE && arena.phase == Arena.Phase.LIVE) {
			Vec3 start = Markers.raceStart(level, arena, team);
			if (start != null) return start;
		}
		if (preset.spawn == Preset.Spawn.TEAM && preset.teamsOn() && team >= 0 && team < arena.hearts.size()) {
			BlockPos heart = arena.hearts.get(team);
			return near(level, arena, heart.getX(), heart.getZ(), 3);
		}
		return switch (preset.spawn) {
			case CENTER -> near(level, arena, footprint.minX() + footprint.width() / 2, footprint.minZ() + footprint.width() / 2, 6);
			case TEAM -> team >= 0 && preset.teamsOn() ? teamSpot(level, arena, team) : random(level, arena);
			case RANDOM -> random(level, arena);
		};
	}

	/**
	 * A team's own ground: the divisions that are theirs when the arena is divided, or otherwise a
	 * point on a ring round the middle, the teams spaced evenly about it.
	 */
	private static Vec3 teamSpot(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		Terrain terrain = footprint.terrain();
		int width = footprint.width();
		if (terrain.divided()) {
			int cells = terrain.parts();
			java.util.List<Integer> mine = new java.util.ArrayList<>();
			for (int cell = 0; cell < cells; cell++) {
				if (cell % arena.preset.teams == team) mine.add(cell);
			}
			if (!mine.isEmpty()) {
				int[] middle = terrain.partCenter(footprint, mine.get(RANDOM.nextInt(mine.size())));
				return near(level, arena, middle[0], middle[1], Math.max(4, width / (Math.max(terrain.cols(), (int) Math.ceil(Math.sqrt(cells))) * 4)));
			}
		}
		double angle = Math.PI * 2 * team / arena.preset.teams + Math.PI / 4;
		double radius = width * 0.32;
		int x = (int) (footprint.centerX() + Math.cos(angle) * radius);
		int z = (int) (footprint.centerZ() + Math.sin(angle) * radius);
		return near(level, arena, x, z, 5);
	}

	/** Anywhere inside the border, as it stands now: a closing border keeps drawing the spawns in with it. */
	public static Vec3 random(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		int across = (int) Borders.size(arena, System.currentTimeMillis()) - MARGIN * 2;
		int fromX = (int) Math.floor(footprint.centerX() - across / 2.0);
		int fromZ = (int) Math.floor(footprint.centerZ() - across / 2.0);
		for (int attempt = 0; attempt < 40; attempt++) {
			int x = fromX + RANDOM.nextInt(Math.max(1, across));
			int z = fromZ + RANDOM.nextInt(Math.max(1, across));
			Vec3 spot = standAt(level, arena, x, z, attempt < 25);
			if (spot != null) return spot;
		}
		return raft(level, arena, fromX + RANDOM.nextInt(Math.max(1, across)), fromZ + RANDOM.nextInt(Math.max(1, across)));
	}

	/** Somewhere within {@code radius} of a point, trying the point first. */
	public static Vec3 near(ServerLevel level, Arena arena, int x, int z, int radius) {
		Vec3 spot = standAt(level, arena, x, z, true);
		if (spot != null) return spot;
		for (int attempt = 0; attempt < 40; attempt++) {
			int tryX = x + RANDOM.nextInt(radius * 2 + 1) - radius;
			int tryZ = z + RANDOM.nextInt(radius * 2 + 1) - radius;
			spot = standAt(level, arena, tryX, tryZ, attempt < 25);
			if (spot != null) return spot;
		}
		return raft(level, arena, x, z);
	}

	public static Vec3 standAt(ServerLevel level, Arena arena, int x, int z) {
		return standAt(level, arena, x, z, false);
	}

	/**
	 * Standing room at this column, or null. Looked for from the top of the arena down, so in the
	 * nether, under its roof, it is the first cave floor with room over it rather than the roof.
	 *
	 * @param open only ground about as high as the ground around it: not the bottom of a pit, not
	 *             the top of a pillar or a tree
	 */
	public static Vec3 standAt(ServerLevel level, Arena arena, int x, int z, boolean open) {
		Footprint footprint = arena.footprint();
		if (x < footprint.minX() + MARGIN || x >= footprint.maxX() - MARGIN || z < footprint.minZ() + MARGIN || z >= footprint.maxZ() - MARGIN) {
			return null;
		}
		if (!level.hasChunk(x >> 4, z >> 4)) return null;
		Terrain terrain = footprint.terrain();
		if (terrain.wallAt(footprint, x, z) != null || nearWall(footprint, terrain, x, z)) return null;

		boolean underRoof = arena.preset.roofed() && arena.preset.source == Preset.Source.GENERATE;
		int top = underRoof
			? arena.wallTop - 4
			: Math.min(arena.wallTop - 2, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
		for (int y = top; y > level.getMinY(); y--) {
			pos.setY(y);
			if (arena.lobbyStanding && y >= arena.lobbyY - 1 && y <= arena.lobbyY + Lobby.HEIGHT + 1) continue;
			BlockState feet = level.getBlockState(pos);
			BlockState head = level.getBlockState(pos.above());
			BlockState ground = level.getBlockState(pos.below());
			if (roomy(feet) && roomy(head) && floor(ground)) {
				if (open && !underRoof && !openAround(level, x, y, z)) return null;
				return new Vec3(x + 0.5, y, z + 0.5);
			}
		}
		return null;
	}

	private static boolean openAround(ServerLevel level, int x, int y, int z) {
		int[][] around = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
		for (int[] step : around) {
			int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + step[0], z + step[1]);
			if (Math.abs(height - y) > 3) return false;
		}
		return true;
	}

	private static boolean nearWall(Footprint footprint, Terrain terrain, int x, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (terrain.wallAt(footprint, x + dx, z + dz) != null) return true;
			}
		}
		return false;
	}

	private static boolean roomy(BlockState state) {
		return state.isAir() || !solid(state) && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE);
	}

	public static boolean solid(BlockState state) {
		return state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP);
	}

	private static boolean floor(BlockState state) {
		return solid(state) && !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CACTUS) && !state.is(Blocks.BEDROCK)
			&& !state.is(Blocks.POWDER_SNOW) && state.getFluidState().isEmpty() && !state.is(BlockTags.LEAVES)
			&& !state.is(BlockTags.LOGS);
	}

	/**
	 * Nowhere to stand at all, which open sea or a void of islands can mean: a little raft of
	 * planks where standing room was wanted, on the water if there is water, or in the air at the
	 * arena's ground level if there is nothing.
	 */
	private static Vec3 raft(ServerLevel level, Arena arena, int x, int z) {
		Footprint footprint = arena.footprint();
		x = Math.max(footprint.minX() + MARGIN, Math.min(footprint.maxX() - MARGIN - 1, x));
		z = Math.max(footprint.minZ() + MARGIN, Math.min(footprint.maxZ() - MARGIN - 1, z));
		int top = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) : level.getMinY();
		int y = top > level.getMinY() + 1 ? Math.min(top, arena.wallTop - 3) : arena.surfaceY;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(pos.set(x + dx, y - 1, z + dz), Blocks.OAK_PLANKS.defaultBlockState());
				level.setBlockAndUpdate(pos.set(x + dx, y, z + dz), Blocks.AIR.defaultBlockState());
				level.setBlockAndUpdate(pos.set(x + dx, y + 1, z + dz), Blocks.AIR.defaultBlockState());
			}
		}
		return new Vec3(x + 0.5, y, z + 0.5);
	}
}
