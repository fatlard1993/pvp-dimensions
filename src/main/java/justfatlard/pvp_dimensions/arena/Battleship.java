package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Battleship, between two teams: each has a fleet hidden in its waters and a plotting board of the
 * other team's, ten by ten, on its own end of the ground. On your team's turn, right-click a square
 * of your board to fire there. A miss goes white, a hit red, and a ship sunk goes black from end to
 * end. Sink their whole fleet first to win.
 *
 * <p>The fleets are never built. A fleet standing in the world is a fleet somebody walks over to
 * look at, so they are kept here, and all anyone sees of one is what has been fired at it. That
 * makes both boards fair to show to everyone: they hold nothing their own team did not find out.
 *
 * <p>A board is {@link Arena#bases}: each team's board's first corner, at the height of the floor
 * it is set in. Hits each team has landed are {@link Arena#scores}. The fleets are not saved: a game
 * carried over a restart is dealt new fleets, on clean boards.
 */
public final class Battleship {
	private Battleship() {}

	public static final int SIZE = 10;
	/** The classic fleet, biggest first. */
	private static final String[] SHIP_NAMES = {"Carrier", "Battleship", "Cruiser", "Submarine", "Destroyer"};
	private static final int[] SHIP_LENGTHS = {5, 4, 3, 3, 2};
	private static final long TURN_MILLIS = 30_000L;
	/** Blocks kept between the back of a board and the arena's edge. */
	private static final int BEHIND = 6;

	private static final Random DRAW = new Random();

	/** A ship: its name, the squares it covers, and those hit. */
	private record Ship(String name, Set<Integer> cells, Set<Integer> hit) {
		boolean sunk() {
			return hit.size() >= cells.size();
		}
	}

	private static final class Game {
		final Map<Integer, List<Ship>> fleets = new HashMap<>();
		/** The squares each team has fired at, on its board of the other's waters. */
		final Map<Integer, Set<Integer>> fired = new HashMap<>();
		final Turns turn;

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- The fleets ---

	/**
	 * A fleet laid out at random: each ship along a row or down a column, wholly on the board, and
	 * on no square another holds. Ships may touch, as at any table.
	 *
	 * @return each ship's squares, as {@code row * SIZE + column}
	 */
	public static List<Set<Integer>> fleet(Random random) {
		List<Set<Integer>> ships = new ArrayList<>();
		Set<Integer> taken = new HashSet<>();
		for (int length : SHIP_LENGTHS) {
			while (true) {
				boolean across = random.nextBoolean();
				int row = random.nextInt(across ? SIZE : SIZE - length + 1);
				int column = random.nextInt(across ? SIZE - length + 1 : SIZE);
				Set<Integer> cells = new HashSet<>();
				for (int i = 0; i < length; i++) cells.add((across ? row : row + i) * SIZE + (across ? column + i : column));
				if (cells.stream().anyMatch(taken::contains)) continue;
				taken.addAll(cells);
				ships.add(cells);
				break;
			}
		}
		return ships;
	}

	/** A square's name as it is called out: a row's letter and a column's number, as "C4". */
	public static String name(int cell) {
		return (char) ('A' + cell / SIZE) + Integer.toString(cell % SIZE + 1);
	}

	private static void deal(Game game, int teams) {
		game.fleets.clear();
		game.fired.clear();
		for (int team = 0; team < teams; team++) {
			List<Ship> ships = new ArrayList<>();
			List<Set<Integer>> cells = fleet(DRAW);
			for (int i = 0; i < cells.size(); i++) ships.add(new Ship(SHIP_NAMES[i], cells.get(i), new HashSet<>()));
			game.fleets.put(team, ships);
			game.fired.put(team, new HashSet<>());
		}
	}

	/** Every square of this team's fleet, for the tests to aim at. */
	public static Set<Integer> fleetOf(Arena arena, int team) {
		Game game = games.get(arena.id);
		Set<Integer> all = new HashSet<>();
		if (game == null) return all;
		for (Ship ship : game.fleets.getOrDefault(team, List.of())) all.addAll(ship.cells());
		return all;
	}

	// --- The boards ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		for (int team = 0; team < 2; team++) {
			BlockPos corner = Boards.atEnd(level, arena, team, SIZE, BEHIND);
			arena.bases.add(corner);
			build(level, corner, team);
		}
		Game game = new Game(DRAW.nextInt(2));
		deal(game, 2);
		games.put(arena.id, game);
		turnTo(server, arena, game, game.turn.side);
	}

	/**
	 * The board: a square of targets set in the floor, a rim of the team's colour round it, and a
	 * sign for every row's letter and every column's number.
	 */
	private static void build(ServerLevel level, BlockPos corner, int team) {
		Builds.yard(level, corner.getX() + SIZE / 2, corner.getY(), corner.getZ() + SIZE / 2, SIZE / 2 + 3, 3);
		BlockState rim = Terrain.block(TeamColors.of(team).concrete(), Blocks.STONE.defaultBlockState());
		for (int x = -2; x <= SIZE + 1; x++) {
			for (int z = -2; z <= SIZE + 1; z++) {
				boolean inside = x >= 0 && x < SIZE && z >= 0 && z < SIZE;
				level.setBlock(corner.offset(x, -1, z), inside ? Blocks.TARGET.defaultBlockState() : rim, Block.UPDATE_ALL);
			}
		}
		for (int i = 0; i < SIZE; i++) {
			Boards.sign(level, corner.offset(-1, 0, i), String.valueOf((char) ('A' + i)), 12);
			Boards.sign(level, corner.offset(i, 0, -1), Integer.toString(i + 1), 8);
		}
	}

	private static BlockState wool(String colour) {
		return Terrain.block("minecraft:" + colour + "_wool", Blocks.TARGET.defaultBlockState());
	}

	/** The square of a board under this block, or -1. */
	private static int cellAt(BlockPos corner, BlockPos pos) {
		int x = pos.getX() - corner.getX();
		int z = pos.getZ() - corner.getZ();
		if (pos.getY() != corner.getY() - 1 || x < 0 || x >= SIZE || z < 0 || z >= SIZE) return -1;
		return z * SIZE + x;
	}

	private static BlockPos posOf(BlockPos corner, int cell) {
		return corner.offset(cell % SIZE, -1, cell / SIZE);
	}

	/** Whether this block is part of a board, its rim or its signs, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.BATTLESHIP) return false;
		for (BlockPos corner : arena.bases) {
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			int up = pos.getY() - corner.getY();
			if (x >= -2 && x <= SIZE + 1 && z >= -2 && z <= SIZE + 1 && (up == -1 || up == 0 && (x == -1 || z == -1))) return true;
		}
		return false;
	}

	// --- Firing ---

	/** A player right-clicked a block: a shot, if it is a square of their team's board and their turn. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.BATTLESHIP || arena.bases.size() < 2) return InteractionResult.PASS;
		int board = -1;
		int cell = -1;
		for (int team = 0; team < arena.bases.size() && cell < 0; team++) {
			cell = cellAt(arena.bases.get(team), pos);
			if (cell >= 0) board = team;
		}
		if (cell < 0) return InteractionResult.PASS;
		// Both hands ask; the one shot is the main hand's.
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
		Game game = games.get(arena.id);
		Arena.Member member = arena.member(player.getUUID());
		if (game == null || member == null || member.watching || member.out) return InteractionResult.FAIL;
		if (member.team != board) {
			Say.bar(player, "That's " + TeamColors.of(board).name() + "'s board; yours is at your own end");
			return InteractionResult.FAIL;
		}
		if (game.turn.side != member.team) {
			Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
			return InteractionResult.FAIL;
		}
		if (game.fired.get(board).contains(cell)) {
			Say.bar(player, name(cell) + " has been fired at already");
			return InteractionResult.FAIL;
		}
		fire(player.level().getServer(), level, arena, board, cell, member.name);
		return InteractionResult.SUCCESS;
	}

	/** A team fires at a square of the other team's waters. */
	public static void fire(MinecraftServer server, ServerLevel level, Arena arena, int team, int cell, String by) {
		Game game = games.get(arena.id);
		if (game == null || game.turn.side != team || game.fired.get(team).contains(cell)) return;
		int enemy = 1 - team;
		game.fired.get(team).add(cell);
		BlockPos corner = arena.bases.get(team);
		BlockPos square = posOf(corner, cell);
		Ship struck = null;
		for (Ship ship : game.fleets.get(enemy)) {
			if (!ship.cells().contains(cell)) continue;
			ship.hit().add(cell);
			struck = ship;
		}
		double x = square.getX() + 0.5, y = square.getY() + 1.2, z = square.getZ() + 0.5;
		String shooter = by + " fires at " + name(cell);
		if (struck == null) {
			level.setBlock(square, wool("white"), Block.UPDATE_ALL);
			level.sendParticles(ParticleTypes.SPLASH, x, y, z, 40, 0.4, 0.2, 0.4, 0.1);
			level.playSound(null, x, y, z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.0F, 1.0F);
			Arenas.tellInside(server, arena, shooter + ": a miss");
		} else {
			arena.scores.merge(team, 1, Integer::sum);
			Arenas.vault(server).touch();
			level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 2, 0.2, 0.2, 0.2, 0);
			level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.2F);
			if (struck.sunk()) {
				for (int part : struck.cells()) level.setBlock(posOf(corner, part), wool("black"), Block.UPDATE_ALL);
				Arenas.tellInside(server, arena, shooter + ": a hit, and " + TeamColors.of(enemy).name() + "'s " + struck.name() + " is sunk!");
				Boards.title(server, arena, team, "Sunk!", TeamColors.of(enemy).name() + "'s " + struck.name());
			} else {
				level.setBlock(square, wool("red"), Block.UPDATE_ALL);
				Arenas.tellInside(server, arena, shooter + ": a hit!");
			}
			if (game.fleets.get(enemy).stream().allMatch(Ship::sunk)) {
				Goals.winTeam(server, arena, team, "sank " + TeamColors.of(enemy).name() + "'s whole fleet");
				return;
			}
		}
		turnTo(server, arena, game, enemy);
	}

	/** The turn to this side, or past it to the other where it has nobody to fire. */
	private static void turnTo(MinecraftServer server, Arena arena, Game game, int side) {
		if (game.turn.to(arena, side)) announce(server, arena, game);
	}

	private static void announce(MinecraftServer server, Arena arena, Game game) {
		Arenas.tellInside(server, arena, TeamColors.of(game.turn.side).name() + " to fire: right-click a square of your board");
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: the boards stand, but the fleets were never written down. New ones, and clean boards.
			if (arena.bases.size() < 2) return;
			for (int team = 0; team < 2; team++) build(level, arena.bases.get(team), team);
			arena.scores.clear();
			game = new Game(0);
			deal(game, 2);
			games.put(arena.id, game);
			Arenas.tellInside(server, arena, "The fleets were lost in the fog; new ones are out, on clean boards");
			turnTo(server, arena, game, 0);
			return;
		}
		int was = game.turn.side;
		Turns.Passed passed = game.turn.check(arena, now);
		if (passed == Turns.Passed.TOO_LONG) Arenas.tellInside(server, arena, TeamColors.of(was).name() + " took too long; the turn passes");
		if (passed != Turns.Passed.NO) announce(server, arena, game);
	}

	// --- Words ---

	/** The bar's line: each side's hits, and whose turn it is. */
	static String status(Arena arena) {
		return Banks.tally(arena) + " hits  ·  " + whoseTurn(arena);
	}

	static String whoseTurn(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? "Ships putting out" : TeamColors.of(game.turn.side).name() + " to fire";
	}

	/** This team's ships still afloat, for the board. */
	static int afloat(Arena arena, int team) {
		Game game = games.get(arena.id);
		if (game == null) return SHIP_LENGTHS.length;
		int left = 0;
		for (Ship ship : game.fleets.getOrDefault(team, List.of())) if (!ship.sunk()) left++;
		return left;
	}

	static int ships() {
		return SHIP_LENGTHS.length;
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
