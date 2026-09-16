package justfatlard.pvp_dimensions.arena;

import java.util.List;
import justfatlard.pvp_dimensions.integration.ChestUtils;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A building for each team to start from, where the preset asks for one: at the middle of the
 * team's own ground, the size of the team that will hold it, in the world's own materials with the
 * team's colour worked in. Each has the team's chest, a crafting table and a furnace, and room at
 * its middle for whatever the goal puts there: a flag chest, a bank, a base cube.
 *
 * <p>Built when the fight starts, when the teams are known. Against the mobs with no teams, there
 * is one, in the middle, for everybody.
 *
 * <p>The styles: a <b>camp</b>, fenced and open; a <b>fort</b>, walled round a yard, with a
 * walkway behind its battlements and slits to shoot through; a <b>tower</b>, climbed inside to a
 * lookout; a <b>bunker</b>, dug in under a flat roof. Everything but the team's chest can be broken.
 */
public final class TeamBases {
	private TeamBases() {}

	/** A building's own directions: {@code f} toward its front, {@code l} across it, {@code up} up. */
	private record Frame(int x, int y, int z, Direction front) {
		Direction side() {
			return front.getClockWise();
		}

		BlockPos at(int f, int up, int l) {
			Direction side = side();
			return new BlockPos(x + front.getStepX() * f + side.getStepX() * l, y + up, z + front.getStepZ() * f + side.getStepZ() * l);
		}
	}

	public static void build(ServerLevel level, Arena arena) {
		arena.hearts.clear();
		Preset preset = arena.preset;
		if (!preset.basesOn()) return;
		if (preset.teamsOn()) {
			int cap = spacing(arena);
			for (int team = 0; team < preset.teams; team++) {
				arena.hearts.add(build(level, arena, Goals.baseSpot(level, arena, team), team, expected(arena, team), cap));
			}
		} else {
			Footprint footprint = arena.footprint();
			int x = (int) Math.floor(footprint.centerX());
			int z = (int) Math.floor(footprint.centerZ());
			BlockPos spot = new BlockPos(x, Builds.ground(level, arena, x, z), z);
			arena.hearts.add(build(level, arena, spot, -1, Math.max(1, arena.inside().size()), Integer.MAX_VALUE));
		}
	}

	/** How many a team's building is for: those on it now, or its share of everyone asked. */
	private static int expected(Arena arena, int team) {
		int asked = Math.max(arena.invited.size() + 1, arena.inside().size());
		int share = (int) Math.ceil(asked / (double) arena.preset.teams);
		return Math.max(1, Math.max(arena.teamSize(team), share));
	}

	/** On an undivided arena the teams sit round a ring; no building reaches halfway to the next. */
	private static int spacing(Arena arena) {
		if (arena.terrain().divided()) return Integer.MAX_VALUE;
		double radius = arena.footprint().width() * 0.3;
		double between = 2 * radius * Math.sin(Math.PI / arena.preset.teams);
		return Math.max(3, (int) (between / 2) - 2);
	}

	private static BlockPos build(ServerLevel level, Arena arena, BlockPos spot, int team, int players, int cap) {
		Preset preset = arena.preset;
		Builds.Palette palette = Builds.palette(preset.world, team >= 0 ? TeamColors.of(team) : null);
		int keep = keep(preset);
		int keepHeight = preset.activeGoal() == Preset.Goal.DESTRUCTION ? preset.baseSize : 2;
		Frame frame = new Frame(spot.getX(), spot.getY(), spot.getZ(), Builds.front(arena, spot.getX(), spot.getZ()));
		int room = Math.min(cap, Builds.room(arena, spot.getX(), spot.getZ(), 12));
		return switch (preset.teamBases) {
			case CAMP -> camp(level, arena, frame, palette, team, players, keep, room);
			case FORT -> fort(level, arena, frame, palette, team, players, keep, room);
			case TOWER -> tower(level, arena, frame, palette, team, players, keep, room);
			case BUNKER -> bunker(level, arena, frame, palette, team, players, keep, keepHeight, room);
			case NONE -> spot;
		};
	}

	/** How far round its middle a building keeps its floor clear, for what the goal puts there. */
	private static int keep(Preset preset) {
		return switch (preset.activeGoal()) {
			case CTF, BANK -> 1;
			case DESTRUCTION -> preset.baseSize / 2 + 1;
			default -> 0;
		};
	}

	// --- Camp ---

	private static BlockPos camp(ServerLevel level, Arena arena, Frame frame, Builds.Palette palette, int team, int players, int keep, int room) {
		int reach = Math.max(Math.clamp(3 + players / 2, 3, 6), keep + 2);
		reach = Math.min(reach, room - 1);
		if (reach < 2) return frame.at(0, 0, 0);
		Builds.yard(level, frame.x(), frame.y(), frame.z(), reach + 1, 5);
		for (int f = -reach; f <= reach; f++) {
			for (int l = -reach; l <= reach; l++) {
				if (Math.max(Math.abs(f), Math.abs(l)) != reach) continue;
				boolean gap = Math.abs(f) <= 1 || Math.abs(l) <= 1;
				boolean corner = Math.abs(f) == reach && Math.abs(l) == reach;
				if (corner) {
					for (int up = 0; up <= 2; up++) Builds.set(level, frame.at(f, up, l), pillar(palette));
					Builds.set(level, frame.at(f, 3, l), palette.light());
				} else if (!gap) {
					Builds.set(level, frame.at(f, 0, l), palette.fence());
				}
			}
		}
		banner(level, frame.at(reach - 1, 0, reach - 1), palette, frame.front());
		banner(level, frame.at(reach - 1, 0, -(reach - 1)), palette, frame.front());
		nook(level, arena, frame, palette, team, List.of(frame.at(-(reach - 1), 0, 0), frame.at(-(reach - 1), 0, 1), frame.at(-(reach - 1), 0, -1)), frame.front());
		int fire = Math.min(reach - 1, Math.max(2, keep + 2));
		if (fire < reach) {
			level.setBlock(frame.at(0, 0, -fire), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), Block.UPDATE_ALL);
		}
		return frame.at(0, 0, 0);
	}

	// --- Fort ---

	private static BlockPos fort(ServerLevel level, Arena arena, Frame frame, Builds.Palette palette, int team, int players, int keep, int room) {
		int half = Math.max(Math.clamp(3 + players / 2, 3, 7), keep + 2);
		half = Math.min(half, room - 3);
		if (half < 2) return camp(level, arena, frame, palette, team, players, keep, room);
		int wall = half + 1;
		Builds.yard(level, frame.x(), frame.y(), frame.z(), wall + 1, 8);
		for (int f = -wall; f <= wall; f++) {
			for (int l = -wall; l <= wall; l++) {
				int ring = Math.max(Math.abs(f), Math.abs(l));
				if (ring == wall) {
					boolean gate = f == wall && Math.abs(l) <= 1;
					// None in the back wall, where the ladders hang.
					boolean slit = !gate && f != -wall && Math.abs(f) != Math.abs(l) && (Math.abs(f) == wall ? l : f) % 3 == 0;
					for (int up = 0; up <= 3; up++) {
						if (gate && up <= 2) continue;
						if (slit && up == 1) {
							Builds.air(level, frame.at(f, up, l));
							continue;
						}
						Builds.set(level, frame.at(f, up, l), up == 2 ? palette.accent() : palette.wall());
					}
					if ((f + l) % 2 == 0) Builds.set(level, frame.at(f, 4, l), palette.trim());
				} else if (ring == half) {
					Builds.set(level, frame.at(f, 3, l), palette.floor());
				}
			}
		}
		// The corners stand a little higher, so each wall reads as a wall between two towers.
		for (int f : new int[] {-wall, wall}) {
			for (int l : new int[] {-wall, wall}) {
				for (int up = 0; up <= 5; up++) Builds.set(level, frame.at(f, up, l), palette.trim());
				Builds.set(level, frame.at(f, 6, l), palette.light());
			}
		}
		// Ladders up to the walkway, in the back corners, with a hole in the walkway to climb through.
		for (int l : new int[] {-(half - 1), half - 1}) {
			for (int up = 0; up <= 3; up++) Builds.ladder(level, frame.at(-half, up, l), frame.front().getOpposite());
		}
		banner(level, frame.at(wall, 4, 2), palette, frame.front());
		banner(level, frame.at(wall, 4, -2), palette, frame.front());
		Builds.set(level, frame.at(half, 4, half), palette.light());
		Builds.set(level, frame.at(half, 4, -half), palette.light());
		nook(level, arena, frame, palette, team, List.of(frame.at(-half, 0, 0), frame.at(-half, 0, 1), frame.at(-half, 0, -1)), frame.front());
		return frame.at(0, 0, 0);
	}

	// --- Tower ---

	private static BlockPos tower(ServerLevel level, Arena arena, Frame frame, Builds.Palette palette, int team, int players, int keep, int room) {
		int wall = Math.clamp(2 + players / 3, 2, 4);
		if (keep > 0) wall = Math.max(wall, Math.max(3, keep + 2));
		wall = Math.min(wall, room - 2);
		if (wall < 2) return camp(level, arena, frame, palette, team, players, keep, room);
		int height = Math.clamp(12 + 2 * players, 12, 24);
		raise(level, frame, palette, wall, height);
		banner(level, frame.at(wall, height + 1, wall), palette, frame.front());
		nook(level, arena, frame, palette, team, List.of(frame.at(0, 0, -(wall - 1)), frame.at(1, 0, -(wall - 1)), frame.at(-1, 0, -(wall - 1))), frame.side());
		return frame.at(0, 0, 0);
	}

	/**
	 * The tower itself, for a team or a race's finish: square walls with a door at the front, a
	 * ladder up the back, a floor every five blocks with a hole for it, windows on every side, and
	 * a roof walled round with battlements, the ladder coming up through it.
	 */
	static void raise(ServerLevel level, Frame frame, Builds.Palette palette, int wall, int height) {
		Builds.yard(level, frame.x(), frame.y(), frame.z(), wall + 2, height + 3);
		for (int f = -wall; f <= wall; f++) {
			for (int l = -wall; l <= wall; l++) {
				int ring = Math.max(Math.abs(f), Math.abs(l));
				boolean ladder = f == -(wall - 1) && l == 0;
				for (int up = 0; up < height; up++) {
					if (ring == wall) {
						boolean door = f == wall && l == 0 && up <= 1;
						// No window behind the ladder: a ladder can't hang on glass.
						boolean window = up % 4 == 2 && (f == 0 || l == 0) && f != -wall;
						if (door) continue;
						Builds.set(level, frame.at(f, up, l), window ? palette.window() : up == height - 2 ? palette.accent() : palette.wall());
					} else if (up > 0 && up % 5 == 0 && !ladder) {
						Builds.set(level, frame.at(f, up, l), palette.floor());
					}
				}
				if (ring <= wall && !ladder) Builds.set(level, frame.at(f, height, l), palette.floor());
				if (ring == wall && (f + l) % 2 == 0) Builds.set(level, frame.at(f, height + 1, l), palette.trim());
			}
		}
		for (int up = 0; up <= height; up++) Builds.ladder(level, frame.at(-(wall - 1), up, 0), frame.front().getOpposite());
		for (int up = 5; up < height; up += 5) Builds.set(level, frame.at(wall - 1, up + 1, wall - 1), palette.light());
		Builds.set(level, frame.at(wall - 1, 0, wall - 1), palette.light());
	}

	/** A race's finish tower: a team's tower in no team's colour, with the height the preset asks for. */
	static BlockPos raceTower(ServerLevel level, Preset.World world, BlockPos spot, Direction front, int height) {
		Frame frame = new Frame(spot.getX(), spot.getY(), spot.getZ(), front);
		raise(level, frame, Builds.palette(world, null), 3, height);
		return frame.at(0, height, 0);
	}

	// --- Bunker ---

	private static BlockPos bunker(ServerLevel level, Arena arena, Frame frame, Builds.Palette palette, int team, int players, int keep,
			int keepHeight, int room) {
		int half = Math.max(Math.clamp(2 + players / 2, 2, 6), keep + 1);
		half = Math.min(half, room - 2);
		if (half < 2) return camp(level, arena, frame, palette, team, players, keep, room);
		int tall = Math.max(3, keepHeight + 1);
		int floor = -1 - tall;
		Builds.yard(level, frame.x(), frame.y(), frame.z(), half + 2, 3);
		for (int f = -half - 1; f <= half + 1; f++) {
			for (int l = -half - 1; l <= half + 1; l++) {
				int ring = Math.max(Math.abs(f), Math.abs(l));
				Builds.set(level, frame.at(f, floor - 1, l), palette.trim());
				boolean lamp = ring < half && Math.floorMod(f, 3) == 0 && Math.floorMod(l, 3) == 0;
				Builds.set(level, frame.at(f, -1, l), lamp ? palette.ceilingLight() : palette.trim());
				for (int up = floor; up < -1; up++) {
					if (ring == half + 1) Builds.set(level, frame.at(f, up, l), palette.wall());
					else Builds.air(level, frame.at(f, up, l));
				}
			}
		}
		// A way down at the front and another at the back, each a ladder in a shaft ringed at the top in the team's colour.
		for (int f : new int[] {half, -half}) {
			Direction wall = f > 0 ? frame.front() : frame.front().getOpposite();
			for (int up = floor; up <= -1; up++) Builds.ladder(level, frame.at(f, up, 0), wall);
			for (int df = -1; df <= 1; df++) {
				for (int dl = -1; dl <= 1; dl++) {
					if (df == 0 && dl == 0) continue;
					BlockPos rim = frame.at(f + df, 0, dl);
					if (Math.abs(f + df) <= half + 1) Builds.set(level, rim, palette.accent());
				}
			}
		}
		banner(level, frame.at(0, 0, 0), palette, frame.front());
		nook(level, arena, frame, palette, team, List.of(frame.at(0, floor, -half), frame.at(1, floor, -half), frame.at(-1, floor, -half)), frame.side());
		return frame.at(0, floor, 0);
	}

	// --- Pieces ---

	private static BlockState pillar(Builds.Palette palette) {
		BlockState pillar = palette.pillar();
		return pillar.hasProperty(RotatedPillarBlock.AXIS) ? pillar.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : pillar;
	}

	private static void banner(ServerLevel level, BlockPos pos, Builds.Palette palette, Direction facing) {
		BlockState banner = palette.banner();
		if (banner.hasProperty(BannerBlock.ROTATION)) banner = banner.setValue(BannerBlock.ROTATION, facing.get2DDataValue() * 4 % 16);
		level.setBlock(pos, banner, Block.UPDATE_ALL);
	}

	/**
	 * The team's chest, painted its colour, and a crafting table and a furnace beside it: the
	 * first three places in {@code spots}, the chest first, facing {@code facing}.
	 */
	private static void nook(ServerLevel level, Arena arena, Frame frame, Builds.Palette palette, int team, List<BlockPos> spots, Direction facing) {
		BlockPos chest = spots.get(0);
		level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), Block.UPDATE_ALL);
		if (team >= 0) ChestUtils.paint(level, chest, palette.dye());
		arena.teamChests.put(chest, team);
		Builds.set(level, spots.get(1), Blocks.CRAFTING_TABLE.defaultBlockState());
		level.setBlock(spots.get(2), Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, facing), Block.UPDATE_ALL);
	}
}
