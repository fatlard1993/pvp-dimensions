package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The waiting room: a glass box over the middle of the arena, where players gather before the
 * fight and can see the ground they are about to fight on.
 *
 * <p>With teams, its floor is striped in the teams' colours, and standing on a colour is how a
 * player picks that team: no menu, no command, and it works the same on every client. The white
 * stripe down the middle is for anyone who would rather be put wherever there is room.
 */
public final class Lobby {
	private Lobby() {}

	/** Inside, wall to wall. */
	public static final int INSIDE = 13;
	public static final int HEIGHT = 4;

	public static BlockPos floorCenter(Arena arena) {
		return arena.footprint().center(arena.lobbyY);
	}

	public static Vec3 arrival(Arena arena) {
		BlockPos center = floorCenter(arena);
		return new Vec3(center.getX() + 0.5, center.getY() + 1, center.getZ() + 0.5);
	}

	/** Every block of the box, with what goes there: floor, walls and roof. */
	private static List<BlockPos> shell(Arena arena) {
		List<BlockPos> positions = new ArrayList<>();
		BlockPos center = floorCenter(arena);
		int half = INSIDE / 2 + 1;
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				for (int dy = 0; dy <= HEIGHT + 1; dy++) {
					boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
					if (dy == 0 || dy == HEIGHT + 1 || edge) positions.add(center.offset(dx, dy, dz));
				}
			}
		}
		return positions;
	}

	public static void build(ServerLevel level, Arena arena) {
		BlockPos center = floorCenter(arena);
		int half = INSIDE / 2;
		for (BlockPos pos : shell(arena)) {
			boolean floor = pos.getY() == center.getY() && Math.abs(pos.getX() - center.getX()) <= half && Math.abs(pos.getZ() - center.getZ()) <= half;
			BlockState state = floor ? floorBlock(arena, pos.getX() - center.getX()) : Blocks.GLASS.defaultBlockState();
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				for (int dy = 1; dy <= HEIGHT; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		arena.lobbyStanding = true;
	}

	/**
	 * Taken apart the moment the fight starts, all at once: it is a few hundred blocks, and
	 * everyone is about to be set down on the ground below it, which must not still have a glass
	 * roof over it when their spawns are looked for.
	 */
	public static void remove(ServerLevel level, Arena arena) {
		for (BlockPos pos : shell(arena)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		arena.lobbyStanding = false;
	}

	/** A stripe of team colour across the floor, by how far east of the middle it is. */
	private static BlockState floorBlock(Arena arena, int offsetX) {
		BlockState white = Terrain.block("minecraft:white_concrete", Blocks.SNOW_BLOCK.defaultBlockState());
		if (!arena.preset.teamsOn()) return white;
		int team = teamAtOffset(arena, offsetX);
		if (team < 0) return white;
		return Terrain.block(TeamColors.of(team).concrete(), white);
	}

	/**
	 * Which team's stripe is at this offset: the floor split evenly among the teams, with the
	 * middle column left white.
	 */
	public static int teamAtOffset(Arena arena, int offsetX) {
		if (offsetX == 0) return -1;
		int teams = arena.preset.teams;
		int column = offsetX + INSIDE / 2;
		if (offsetX > 0) column--;
		int stripe = (INSIDE - 1) / teams;
		if (stripe <= 0) return -1;
		int team = column / stripe;
		return team < teams ? team : -1;
	}

	public static boolean inside(Arena arena, Vec3 position) {
		BlockPos center = floorCenter(arena);
		int half = INSIDE / 2 + 1;
		return Math.abs(position.x - (center.getX() + 0.5)) <= half + 1 && Math.abs(position.z - (center.getZ() + 0.5)) <= half + 1
			&& position.y >= center.getY() - 2 && position.y <= center.getY() + HEIGHT + 2;
	}

	/** The team a player is standing on the colour of, or -1 on white, glass, or outside the box. */
	public static int standingOn(ServerLevel level, Arena arena, Vec3 position) {
		if (!arena.preset.teamsOn() || !inside(arena, position)) return -1;
		BlockPos below = BlockPos.containing(position.x, position.y - 0.2, position.z);
		BlockPos center = floorCenter(arena);
		if (below.getY() != center.getY()) return -1;
		return teamAtOffset(arena, below.getX() - center.getX());
	}
}
