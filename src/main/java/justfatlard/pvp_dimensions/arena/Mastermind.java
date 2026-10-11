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
 * Mastermind, as a race: every team has a hidden code of four colours to crack on a board at its
 * own end, all at once. Right-click a square of the lit row to change its colour, crouching to go
 * back through them, and ring the team's bell to try the row. Beside it come the pegs: black for
 * each colour in its right place, white for each right colour in the wrong place. Crack it in ten
 * rows and it counts, and a new code is set; run out of rows and the code is shown, and a new one
 * set for nothing. The first team to crack {@link #TO_WIN} wins.
 *
 * <p>The codes are the game's, not the other teams': a code set by a team that then has nothing to
 * do but watch is half a game. Colours may repeat, as at the table.
 *
 * <p>A board is {@link Arena#bases}: each team's first corner, at the height of the floor it is set
 * in. Codes cracked are {@link Arena#scores}. The codes are not saved: a game carried over a restart
 * is set new ones, on clean boards.
 */
public final class Mastermind {
	private Mastermind() {}

	public static final int PLACES = 4;
	public static final int ROWS = 10;
	public static final int TO_WIN = 3;
	static final String[] COLOURS = {"red", "orange", "yellow", "lime", "light_blue", "purple"};
	public static final int COLOURS_COUNT = COLOURS.length;
	private static final int UNSET = -1;
	/** Where a row's pegs start: past its four squares and a gap. */
	private static final int PEGS = PLACES + 1;
	private static final int WIDE = PEGS + PLACES;
	private static final int BEHIND = 6;
	/** How long a cracked or lost code is left showing before the board clears. */
	private static final long SHOW_MILLIS = 4_000L;

	private static final Random DRAW = new Random();

	private static final class Board {
		int[] code;
		final List<int[]> tried = new ArrayList<>();
		final int[] row = new int[PLACES];
		long clearAt;

		Board(int[] code) {
			this.code = code;
			java.util.Arrays.fill(row, UNSET);
		}
	}

	private static final Map<String, Map<Integer, Board>> games = new HashMap<>();

	static int[] code(Random random) {
		int[] code = new int[PLACES];
		for (int i = 0; i < PLACES; i++) code[i] = random.nextInt(COLOURS.length);
		return code;
	}

	/** The pegs a guess earns: right colour in the right place, then right colour anywhere else. */
	public static int[] mark(int[] code, int[] guess) {
		int exact = 0;
		int[] inCode = new int[COLOURS.length];
		int[] inGuess = new int[COLOURS.length];
		for (int i = 0; i < PLACES; i++) {
			if (code[i] == guess[i]) exact++;
			inCode[code[i]]++;
			inGuess[guess[i]]++;
		}
		int common = 0;
		for (int colour = 0; colour < COLOURS.length; colour++) common += Math.min(inCode[colour], inGuess[colour]);
		return new int[] {exact, common - exact};
	}

	// --- The boards ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		Map<Integer, Board> boards = new HashMap<>();
		for (int team = 0; team < arena.preset.teams; team++) {
			BlockPos corner = Boards.atEnd(level, arena, team, ROWS, BEHIND);
			arena.bases.add(corner);
			Board board = new Board(code(DRAW));
			boards.put(team, board);
			Builds.yard(level, corner.getX() + WIDE / 2, corner.getY(), corner.getZ() + ROWS / 2, ROWS / 2 + 4, 3);
			draw(level, corner, team, board);
		}
		games.put(arena.id, boards);
		Arenas.tellInside(server, arena, "Every team has a code of four colours to crack. Right-click the lit row to set it, then ring your bell");
	}

	/** The board as it stands: the rows tried with their pegs, the row being set, those to come, the rim and the bell. */
	private static void draw(ServerLevel level, BlockPos corner, int team, Board board) {
		BlockState rim = Boards.rim(team);
		BlockState lit = Blocks.SEA_LANTERN.defaultBlockState();
		for (int x = -1; x <= WIDE; x++) {
			for (int z = -1; z <= ROWS; z++) {
				boolean edge = x < 0 || x >= WIDE || z < 0 || z >= ROWS;
				BlockState state;
				if (edge) state = (x == -1 || x == WIDE) && z == board.tried.size() && z < ROWS ? lit : rim;
				else if (x == PLACES) state = Blocks.SMOOTH_STONE.defaultBlockState();
				else state = square(board, x, z);
				level.setBlock(corner.offset(x, -1, z), state, Block.UPDATE_ALL);
			}
		}
		level.setBlock(bellAt(corner), Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
		Boards.sign(level, corner.offset(PLACES / 2, 0, -2), "Code", 8);
		Boards.sign(level, corner.offset(PEGS + PLACES / 2, 0, -2), "Pegs", 8);
	}

	private static BlockState square(Board board, int x, int z) {
		int tried = board.tried.size();
		if (x < PLACES) {
			if (z < tried) return colour(board.tried.get(z)[x]);
			if (z == tried) return board.row[x] == UNSET ? Boards.block("white_concrete") : colour(board.row[x]);
			return Boards.block("light_gray_concrete");
		}
		int peg = x - PEGS;
		if (z >= tried) return Boards.block("gray_concrete");
		int[] marks = mark(board.code, board.tried.get(z));
		if (peg < marks[0]) return Boards.block("black_wool");
		if (peg < marks[0] + marks[1]) return Boards.block("white_wool");
		return Boards.block("gray_concrete");
	}

	private static BlockState colour(int colour) {
		return Boards.block(COLOURS[colour] + "_concrete");
	}

	public static BlockPos bellAt(BlockPos corner) {
		return corner.offset(WIDE / 2, 0, -3);
	}

	/** Whether this block is part of a board, its signs or its bell, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.MASTERMIND) return false;
		for (BlockPos corner : arena.bases) {
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			int up = pos.getY() - corner.getY();
			if (x >= -1 && x <= WIDE && z >= -1 && z <= ROWS && up == -1) return true;
			if (up == 0 && (pos.equals(bellAt(corner)) || z == -2 && (x == PLACES / 2 || x == PEGS + PLACES / 2))) return true;
			if (pos.equals(bellAt(corner).below())) return true;
		}
		return false;
	}

	// --- Playing ---

	/** A player right-clicked a block: a square of their row turned, or their bell rung. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.MASTERMIND) return InteractionResult.PASS;
		Map<Integer, Board> boards = games.get(arena.id);
		if (boards == null) return InteractionResult.PASS;
		for (int team = 0; team < arena.bases.size(); team++) {
			BlockPos corner = arena.bases.get(team);
			boolean bell = pos.equals(bellAt(corner));
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			boolean square = pos.getY() == corner.getY() - 1 && x >= 0 && x < WIDE && z >= 0 && z < ROWS;
			if (!bell && !square) continue;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			Arena.Member member = Boards.playing(arena, player);
			Board board = boards.get(team);
			if (member == null || board == null) return InteractionResult.FAIL;
			if (member.team != team) {
				Say.bar(player, "That's " + TeamColors.of(team).name() + "'s board; yours is at your own end");
				return InteractionResult.FAIL;
			}
			if (board.clearAt > 0) {
				Say.bar(player, "A new code is coming");
				return InteractionResult.FAIL;
			}
			if (bell) {
				if (!tryRow(level.getServer(), level, arena, team, member.name)) Say.bar(player, "Set all four squares of the lit row first");
				// Let it ring.
				return InteractionResult.PASS;
			}
			if (x >= PLACES || z != board.tried.size()) {
				Say.bar(player, "Set the lit row, then ring the bell");
				return InteractionResult.FAIL;
			}
			turn(level, arena, team, x, player.isShiftKeyDown() ? -1 : 1);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/** A square of a team's lit row turned on to its next colour, or back to its last. */
	public static void turn(ServerLevel level, Arena arena, int team, int place, int by) {
		Board board = board(arena, team);
		if (board == null || board.clearAt > 0 || board.tried.size() >= ROWS) return;
		int now = board.row[place];
		board.row[place] = now == UNSET ? (by > 0 ? 0 : COLOURS.length - 1) : Math.floorMod(now + by, COLOURS.length);
		BlockPos corner = arena.bases.get(team);
		BlockPos at = corner.offset(place, -1, board.tried.size());
		level.setBlock(at, colour(board.row[place]), Block.UPDATE_ALL);
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	/**
	 * The lit row tried against the code, if all four are set.
	 *
	 * @return whether there was a whole row to try
	 */
	public static boolean tryRow(MinecraftServer server, ServerLevel level, Arena arena, int team, String by) {
		Board board = board(arena, team);
		if (board == null || board.clearAt > 0) return true;
		for (int colour : board.row) if (colour == UNSET) return false;
		int[] guess = board.row.clone();
		board.tried.add(guess);
		java.util.Arrays.fill(board.row, UNSET);
		BlockPos corner = arena.bases.get(team);
		int[] marks = mark(board.code, guess);
		String colour = TeamColors.of(team).name();
		if (marks[0] == PLACES) {
			int cracked = arena.scores.merge(team, 1, Integer::sum);
			Arenas.vault(server).touch();
			draw(level, corner, team, board);
			BlockPos middle = corner.offset(PLACES / 2, 0, board.tried.size() - 1);
			level.sendParticles(ParticleTypes.FIREWORK, middle.getX(), middle.getY() + 0.5, middle.getZ() + 0.5, 40, 1.5, 0.5, 0.5, 0.1);
			level.playSound(null, middle.getX(), middle.getY(), middle.getZ(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 2.0F, 1.0F);
			int rows = board.tried.size();
			if (cracked >= TO_WIN) {
				Goals.winTeam(server, arena, team, "cracked " + cracked + " codes first");
				return true;
			}
			Boards.title(server, arena, team, "Cracked!", colour + " in " + rows + (rows == 1 ? " row" : " rows") + " · " + Banks.tally(arena));
			Arenas.tellInside(server, arena, by + " cracks " + colour + "'s code in " + rows + (rows == 1 ? " row" : " rows") + "! " + Banks.tally(arena));
			board.clearAt = System.currentTimeMillis() + SHOW_MILLIS;
			return true;
		}
		draw(level, corner, team, board);
		level.playSound(null, corner.getX() + 0.5, corner.getY(), corner.getZ() + 0.5, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
		if (board.tried.size() >= ROWS) {
			Arenas.tellInside(server, arena, colour + " ran out of rows. The code was " + words(board.code) + "; a new one is set");
			board.clearAt = System.currentTimeMillis() + SHOW_MILLIS;
		}
		return true;
	}

	public static String words(int[] code) {
		List<String> names = new ArrayList<>();
		for (int colour : code) names.add(COLOURS[colour].replace('_', ' '));
		return String.join(", ", names);
	}

	private static Board board(Arena arena, int team) {
		Map<Integer, Board> boards = games.get(arena.id);
		return boards == null ? null : boards.get(team);
	}

	/** A team's code, for the tests. */
	public static int[] codeOf(Arena arena, int team) {
		Board board = board(arena, team);
		return board == null ? new int[0] : board.code.clone();
	}

	/** The colour set in a square of the lit row, for the tests; -1 for none yet. */
	public static int rowColour(Arena arena, int team, int place) {
		Board board = board(arena, team);
		return board == null ? UNSET : board.row[place];
	}

	public static int rowsTried(Arena arena, int team) {
		Board board = board(arena, team);
		return board == null ? 0 : board.tried.size();
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Map<Integer, Board> boards = games.get(arena.id);
		if (boards == null) {
			// Over a restart: the codes were never written down. New ones, on clean boards.
			if (arena.bases.size() < arena.preset.teams) return;
			boards = new HashMap<>();
			for (int team = 0; team < arena.bases.size(); team++) {
				Board board = new Board(code(DRAW));
				boards.put(team, board);
				draw(level, arena.bases.get(team), team, board);
			}
			games.put(arena.id, boards);
			Arenas.tellInside(server, arena, "The codes were forgotten; new ones are set, on clean boards");
			return;
		}
		for (Map.Entry<Integer, Board> entry : boards.entrySet()) {
			Board board = entry.getValue();
			if (board.clearAt == 0 || now < board.clearAt) continue;
			board.clearAt = 0;
			board.code = code(DRAW);
			board.tried.clear();
			java.util.Arrays.fill(board.row, UNSET);
			draw(level, arena.bases.get(entry.getKey()), entry.getKey(), board);
		}
	}

	// --- Words ---

	/** The bar's line: the viewer's rows left, and the codes cracked. */
	static String status(Arena arena, Arena.@org.jspecify.annotations.Nullable Member viewer) {
		Board board = viewer == null ? null : board(arena, viewer.team);
		String tally = Banks.tally(arena) + " cracked, first to " + TO_WIN;
		if (board == null) return tally;
		int left = ROWS - board.tried.size();
		return left + (left == 1 ? " row" : " rows") + " left  ·  " + tally;
	}

	static int rowsLeft(Arena arena, int team) {
		Board board = board(arena, team);
		return board == null ? ROWS : ROWS - board.tried.size();
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
