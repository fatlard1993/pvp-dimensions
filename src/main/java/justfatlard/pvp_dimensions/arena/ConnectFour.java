package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Connect four, between two teams: a frame standing in the middle of the ground, seven columns of
 * six. On your team's turn, right-click anywhere on a column to drop a piece of your colour down
 * it. Four in a row, across, up or slanting, takes the game; a full frame with no four is nobody's.
 * The frame empties and the side that lost goes first in the next. The first to {@link #TO_WIN}
 * games wins.
 *
 * <p>The frame is {@link Arena#bases}: its bottom-left slot, seen from the south. Games won are
 * {@link Arena#scores}. The pieces are not saved: a game carried over a restart starts the frame
 * again, with the games won kept.
 */
public final class ConnectFour {
	private ConnectFour() {}

	public static final int COLUMNS = 7;
	public static final int ROWS = 6;
	public static final int TO_WIN = 3;
	private static final long TURN_MILLIS = 30_000L;
	/** How long four in a row are shown off before the frame empties. */
	private static final long SHOW_MILLIS = 3_000L;

	private static final Random DRAW = new Random();
	private static final int EMPTY = -1;

	private static final class Game {
		/** The team in each slot, by column then row from the bottom; {@link #EMPTY} for none. */
		final int[][] slots = new int[COLUMNS][ROWS];
		final Turns turn;
		/** When the frame empties after a game is decided; 0 while one is being played. */
		long clearAt;
		/** Who goes first once it has. */
		int nextFirst;

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
			for (int[] column : slots) java.util.Arrays.fill(column, EMPTY);
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- The frame ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		BlockPos corner = Boards.inMiddle(level, arena, COLUMNS, 1).above();
		arena.bases.add(corner);
		Builds.yard(level, corner.getX() + COLUMNS / 2, corner.getY() - 1, corner.getZ(), COLUMNS / 2 + 4, ROWS + 3);
		Game game = new Game(DRAW.nextInt(2));
		games.put(arena.id, game);
		build(level, corner, game);
		turnTo(server, arena, game, game.turn.side);
	}

	/** The frame round the slots, and every slot as the game has it: glass for empty, concrete for a piece. */
	private static void build(ServerLevel level, BlockPos corner, Game game) {
		BlockState frame = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		for (int column = -1; column <= COLUMNS; column++) {
			for (int row = -1; row <= ROWS; row++) {
				boolean edge = column < 0 || column >= COLUMNS || row < 0 || row >= ROWS;
				level.setBlock(corner.offset(column, row, 0), edge ? frame : slotBlock(game.slots[column][row]), Block.UPDATE_ALL);
			}
		}
		// Feet on the frame, so it stands.
		for (int side : new int[] {-1, COLUMNS}) {
			level.setBlock(corner.offset(side, -1, -1), frame, Block.UPDATE_ALL);
			level.setBlock(corner.offset(side, -1, 1), frame, Block.UPDATE_ALL);
		}
	}

	private static BlockState slotBlock(int team) {
		return team == EMPTY ? Blocks.GLASS.defaultBlockState() : Boards.rim(team);
	}

	/** Whether this block is the frame or a slot of it, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.CONNECT_FOUR || arena.bases.isEmpty()) return false;
		BlockPos corner = arena.bases.get(0);
		int column = pos.getX() - corner.getX();
		int row = pos.getY() - corner.getY();
		int depth = pos.getZ() - corner.getZ();
		return column >= -1 && column <= COLUMNS && row >= -1 && row <= ROWS && Math.abs(depth) <= (row == -1 ? 1 : 0);
	}

	/** The column under this block, or -1: any slot of it, or the frame just above or below it. */
	private static int columnAt(BlockPos corner, BlockPos pos) {
		int column = pos.getX() - corner.getX();
		int row = pos.getY() - corner.getY();
		if (pos.getZ() != corner.getZ() || column < 0 || column >= COLUMNS || row < -1 || row > ROWS) return -1;
		return column;
	}

	// --- Dropping a piece ---

	/** A player right-clicked a block: a piece dropped, if it is a column of the frame and their turn. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.CONNECT_FOUR || arena.bases.isEmpty()) return InteractionResult.PASS;
		int column = columnAt(arena.bases.get(0), pos);
		if (column < 0) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
		Game game = games.get(arena.id);
		Arena.Member member = Boards.playing(arena, player);
		if (game == null || member == null) return InteractionResult.FAIL;
		if (game.clearAt > 0) {
			Say.bar(player, "The next game is about to start");
			return InteractionResult.FAIL;
		}
		if (game.turn.side != member.team) {
			Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
			return InteractionResult.FAIL;
		}
		if (game.slots[column][ROWS - 1] != EMPTY) {
			Say.bar(player, "That column is full");
			return InteractionResult.FAIL;
		}
		drop(level.getServer(), level, arena, member.team, column, member.name);
		return InteractionResult.SUCCESS;
	}

	/** A team drops a piece down a column. */
	public static void drop(MinecraftServer server, ServerLevel level, Arena arena, int team, int column, String by) {
		Game game = games.get(arena.id);
		if (game == null || game.clearAt > 0 || game.turn.side != team || game.slots[column][ROWS - 1] != EMPTY) return;
		int row = 0;
		while (game.slots[column][row] != EMPTY) row++;
		game.slots[column][row] = team;
		BlockPos corner = arena.bases.get(0);
		BlockPos slot = corner.offset(column, row, 0);
		level.setBlock(slot, slotBlock(team), Block.UPDATE_ALL);
		for (int above = ROWS - 1; above > row; above--) {
			level.sendParticles(ParticleTypes.CLOUD, slot.getX() + 0.5, corner.getY() + above + 0.5, slot.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0);
		}
		level.playSound(null, slot.getX() + 0.5, slot.getY() + 0.5, slot.getZ() + 0.5, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);

		List<int[]> four = four(game.slots, column, row);
		if (four != null) {
			int won = arena.scores.merge(team, 1, Integer::sum);
			Arenas.vault(server).touch();
			for (int[] cell : four) {
				BlockPos at = corner.offset(cell[0], cell[1], 0);
				level.sendParticles(ParticleTypes.FIREWORK, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
			}
			level.playSound(null, slot.getX() + 0.5, slot.getY() + 0.5, slot.getZ() + 0.5, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 2.0F, 1.0F);
			if (won >= TO_WIN) {
				Goals.winTeam(server, arena, team, "connected four, " + won + " games to " + arena.scores.getOrDefault(1 - team, 0));
				return;
			}
			Boards.title(server, arena, team, "Four in a row!", by + " · " + Banks.tally(arena));
			Arenas.tellInside(server, arena, by + " connects four for " + TeamColors.of(team).name() + "! " + Banks.tally(arena));
			game.clearAt = System.currentTimeMillis() + SHOW_MILLIS;
			game.nextFirst = 1 - team;
			return;
		}
		if (full(game.slots)) {
			Arenas.tellInside(server, arena, "The frame is full with no four: that game is nobody's");
			game.clearAt = System.currentTimeMillis() + SHOW_MILLIS;
			game.nextFirst = 1 - team;
			return;
		}
		turnTo(server, arena, game, 1 - team);
	}

	/** The four in a row through this slot, if its piece made one. */
	public static List<int[]> four(int[][] slots, int column, int row) {
		int team = slots[column][row];
		int[][] ways = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
		for (int[] way : ways) {
			List<int[]> line = new ArrayList<>();
			line.add(new int[] {column, row});
			for (int sign : new int[] {1, -1}) {
				int c = column + way[0] * sign;
				int r = row + way[1] * sign;
				while (c >= 0 && c < COLUMNS && r >= 0 && r < ROWS && slots[c][r] == team) {
					line.add(new int[] {c, r});
					c += way[0] * sign;
					r += way[1] * sign;
				}
			}
			if (line.size() >= 4) return line;
		}
		return null;
	}

	private static boolean full(int[][] slots) {
		for (int[] column : slots) if (column[ROWS - 1] == EMPTY) return false;
		return true;
	}

	/** The team in a slot, for the tests; -1 for none. */
	public static int slot(Arena arena, int column, int row) {
		Game game = games.get(arena.id);
		return game == null ? EMPTY : game.slots[column][row];
	}

	public static int side(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.turn.side;
	}

	public static boolean between(Arena arena) {
		Game game = games.get(arena.id);
		return game != null && game.clearAt > 0;
	}

	// --- Turns ---

	private static void turnTo(MinecraftServer server, Arena arena, Game game, int side) {
		if (game.turn.to(arena, side)) announce(server, arena, game);
	}

	private static void announce(MinecraftServer server, Arena arena, Game game) {
		int side = game.turn.side;
		Arenas.tellInside(server, arena, TeamColors.of(side).name() + " to drop: right-click a column of the frame");
		Boards.titleTeam(server, arena, side, "Your turn", "Right-click a column to drop a piece");
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: the pieces were never written down. The frame empties; the games won stand.
			if (arena.bases.isEmpty()) return;
			game = new Game(0);
			games.put(arena.id, game);
			build(level, arena.bases.get(0), game);
			Arenas.tellInside(server, arena, "The pieces fell out of the frame; a new game, with the games won kept");
			turnTo(server, arena, game, 0);
			return;
		}
		if (game.clearAt > 0) {
			if (now < game.clearAt) return;
			game.clearAt = 0;
			for (int[] column : game.slots) java.util.Arrays.fill(column, EMPTY);
			build(level, arena.bases.get(0), game);
			Arenas.tellInside(server, arena, "A new game: " + Banks.tally(arena));
			turnTo(server, arena, game, game.nextFirst);
			return;
		}
		int was = game.turn.side;
		Turns.Passed passed = game.turn.check(arena, now);
		if (passed == Turns.Passed.TOO_LONG) Arenas.tellInside(server, arena, TeamColors.of(was).name() + " took too long; the turn passes");
		if (passed != Turns.Passed.NO) announce(server, arena, game);
	}

	// --- Words ---

	static String status(Arena arena) {
		return Banks.tally(arena) + " games  ·  first to " + TO_WIN + "  ·  " + whoseTurn(arena);
	}

	static String whoseTurn(Arena arena) {
		Game game = games.get(arena.id);
		if (game == null) return "Setting up the frame";
		if (game.clearAt > 0) return "Next game coming";
		return TeamColors.of(game.turn.side).name() + " to drop";
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
