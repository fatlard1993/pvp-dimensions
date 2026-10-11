package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Collections;
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
 * Memory: thirty-six cards face down in the middle of the ground, eighteen pairs of blocks. The
 * teams take turns, turning two cards over with a right-click each. A pair stays up and is the
 * team's, and the team goes again; two that differ turn back down, and the turn passes. When every
 * pair is found the team with the most wins; level, and a new deal is laid out with the pairs so
 * far still counting.
 *
 * <p>The board is {@link Arena#bases}: its first corner, at the height of the floor it is set in.
 * Pairs found are {@link Arena#scores}. The deal is not saved: a game carried over a restart is
 * dealt again, with the pairs found kept.
 */
public final class Memory {
	private Memory() {}

	public static final int SIZE = 6;
	public static final int PAIRS = SIZE * SIZE / 2;
	/** The faces: blocks whose tops tell apart at a glance. */
	private static final String[] FACES = {
		"diamond_block", "gold_block", "emerald_block", "lapis_block", "redstone_block", "iron_block",
		"coal_block", "copper_block", "amethyst_block", "pumpkin", "melon", "hay_block",
		"slime_block", "tnt", "glowstone", "sea_lantern", "crafting_table", "bricks"};
	private static final long TURN_MILLIS = 30_000L;
	/** How long two cards that differ stay up for everyone to see. */
	private static final long PEEK_MILLIS = 2_000L;

	private static final Random DRAW = new Random();

	private static final class Game {
		/** The face under each card, by {@code row * SIZE + column}. */
		final int[] faces = new int[SIZE * SIZE];
		final boolean[] found = new boolean[SIZE * SIZE];
		final List<Integer> up = new ArrayList<>();
		/** When the two cards up that differ turn back down; 0 while none are waiting to. */
		long downAt;
		final Turns turn;

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	static void deal(Game game, Random random) {
		List<Integer> cards = new ArrayList<>();
		for (int face = 0; face < PAIRS; face++) {
			cards.add(face);
			cards.add(face);
		}
		Collections.shuffle(cards, random);
		for (int i = 0; i < cards.size(); i++) game.faces[i] = cards.get(i);
		java.util.Arrays.fill(game.found, false);
		game.up.clear();
		game.downAt = 0;
	}

	// --- The board ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		BlockPos corner = Boards.inMiddle(level, arena, SIZE * 2 - 1, SIZE * 2 - 1);
		arena.bases.add(corner);
		Builds.yard(level, corner.getX() + SIZE - 1, corner.getY(), corner.getZ() + SIZE - 1, SIZE + 3, 3);
		Game game = new Game(DRAW.nextInt(Math.max(1, arena.preset.teams)));
		deal(game, DRAW);
		games.put(arena.id, game);
		build(level, corner, game);
		turnTo(server, arena, game, game.turn.side);
	}

	/** The cards a block apart, so each is its own to stand by and click, on a floor of dark oak. */
	private static void build(ServerLevel level, BlockPos corner, Game game) {
		BlockState table = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		int span = SIZE * 2 - 1;
		for (int x = -1; x <= span; x++) {
			for (int z = -1; z <= span; z++) level.setBlock(corner.offset(x, -1, z), table, Block.UPDATE_ALL);
		}
		for (int card = 0; card < SIZE * SIZE; card++) show(level, corner, game, card);
	}

	private static BlockPos cardAt(BlockPos corner, int card) {
		return corner.offset(card % SIZE * 2, -1, card / SIZE * 2);
	}

	private static void show(ServerLevel level, BlockPos corner, Game game, int card) {
		boolean faceUp = game.found[card] || game.up.contains(card);
		level.setBlock(cardAt(corner, card), faceUp ? Boards.block(FACES[game.faces[card]]) : Boards.block("white_concrete"), Block.UPDATE_ALL);
	}

	/** The card under this block, or -1. */
	private static int cardOf(BlockPos corner, BlockPos pos) {
		int x = pos.getX() - corner.getX();
		int z = pos.getZ() - corner.getZ();
		if (pos.getY() != corner.getY() - 1 || x < 0 || z < 0 || x % 2 != 0 || z % 2 != 0 || x / 2 >= SIZE || z / 2 >= SIZE) return -1;
		return z / 2 * SIZE + x / 2;
	}

	/** Whether this block is part of the table, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.MEMORY || arena.bases.isEmpty()) return false;
		BlockPos corner = arena.bases.get(0);
		int x = pos.getX() - corner.getX();
		int z = pos.getZ() - corner.getZ();
		return pos.getY() == corner.getY() - 1 && x >= -1 && x <= SIZE * 2 - 1 && z >= -1 && z <= SIZE * 2 - 1;
	}

	// --- Turning cards ---

	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.MEMORY || arena.bases.isEmpty()) return InteractionResult.PASS;
		int card = cardOf(arena.bases.get(0), pos);
		if (card < 0) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
		Game game = games.get(arena.id);
		Arena.Member member = Boards.playing(arena, player);
		if (game == null || member == null) return InteractionResult.FAIL;
		if (game.turn.side != member.team) {
			Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
			return InteractionResult.FAIL;
		}
		if (game.downAt > 0) {
			Say.bar(player, "Wait for those two to turn back");
			return InteractionResult.FAIL;
		}
		if (game.found[card] || game.up.contains(card)) {
			Say.bar(player, "That card is already up");
			return InteractionResult.FAIL;
		}
		flip(level.getServer(), level, arena, member.team, card, member.name);
		return InteractionResult.SUCCESS;
	}

	/** A team turns a card over. */
	public static void flip(MinecraftServer server, ServerLevel level, Arena arena, int team, int card, String by) {
		Game game = games.get(arena.id);
		if (game == null || game.turn.side != team || game.downAt > 0 || game.found[card] || game.up.contains(card)) return;
		BlockPos corner = arena.bases.get(0);
		game.up.add(card);
		show(level, corner, game, card);
		BlockPos at = cardAt(corner, card);
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.0F);
		if (game.up.size() < 2) return;
		int a = game.up.get(0);
		int b = game.up.get(1);
		if (game.faces[a] != game.faces[b]) {
			game.downAt = System.currentTimeMillis() + PEEK_MILLIS;
			return;
		}
		game.found[a] = true;
		game.found[b] = true;
		game.up.clear();
		int pairs = arena.scores.merge(team, 1, Integer::sum);
		Arenas.vault(server).touch();
		for (int card2 : new int[] {a, b}) {
			BlockPos pos = cardAt(corner, card2);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0);
		}
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.2F);
		Arenas.tellInside(server, arena, by + " finds a pair for " + TeamColors.of(team).name() + " (" + pairs + "), and goes again");
		boolean done = true;
		for (boolean f : game.found) done &= f;
		if (done) cleared(server, level, arena, game);
		else game.turn.since = System.currentTimeMillis();
	}

	/** Every pair is found: the most wins, and a tie is dealt again. */
	private static void cleared(MinecraftServer server, ServerLevel level, Arena arena, Game game) {
		int best = -1;
		int top = -1;
		boolean tie = false;
		for (int team = 0; team < arena.preset.teams; team++) {
			int pairs = arena.scores.getOrDefault(team, 0);
			if (pairs > top) {
				best = team;
				top = pairs;
				tie = false;
			} else if (pairs == top) {
				tie = true;
			}
		}
		if (!tie && best >= 0) {
			Goals.winTeam(server, arena, best, "found " + top + " pairs");
			return;
		}
		Arenas.tellInside(server, arena, "Level on " + top + " pairs: a new deal, and the pairs so far still count");
		deal(game, DRAW);
		build(level, arena.bases.get(0), game);
	}

	/** The face of a card, for the tests. */
	public static int faceOf(Arena arena, int card) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.faces[card];
	}

	public static boolean isUp(Arena arena, int card) {
		Game game = games.get(arena.id);
		return game != null && (game.found[card] || game.up.contains(card));
	}

	public static int side(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.turn.side;
	}

	public static BlockPos cardPos(Arena arena, int card) {
		return cardAt(arena.bases.get(0), card);
	}

	// --- Turns ---

	private static void turnTo(MinecraftServer server, Arena arena, Game game, int side) {
		if (game.turn.to(arena, side)) announce(server, arena, game);
	}

	private static void announce(MinecraftServer server, Arena arena, Game game) {
		int side = game.turn.side;
		Arenas.tellInside(server, arena, TeamColors.of(side).name() + "'s turn: right-click two cards");
		Boards.titleTeam(server, arena, side, "Your turn", "Turn over two cards");
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: the deal was never written down. Dealt again; the pairs found stand.
			if (arena.bases.isEmpty()) return;
			game = new Game(0);
			deal(game, DRAW);
			games.put(arena.id, game);
			build(level, arena.bases.get(0), game);
			Arenas.tellInside(server, arena, "The cards were swept up and dealt again; the pairs found still count");
			turnTo(server, arena, game, 0);
			return;
		}
		BlockPos corner = arena.bases.get(0);
		if (game.downAt > 0) {
			if (now < game.downAt) return;
			turnBack(level, corner, game);
			turnTo(server, arena, game, game.turn.side + 1);
			return;
		}
		int was = game.turn.side;
		Turns.Passed passed = game.turn.check(arena, now);
		if (passed == Turns.Passed.NO) return;
		turnBack(level, corner, game);
		if (passed == Turns.Passed.TOO_LONG) Arenas.tellInside(server, arena, TeamColors.of(was).name() + " took too long; the turn passes");
		announce(server, arena, game);
	}

	private static void turnBack(ServerLevel level, BlockPos corner, Game game) {
		List<Integer> up = List.copyOf(game.up);
		game.up.clear();
		game.downAt = 0;
		for (int card : up) show(level, corner, game, card);
	}

	// --- Words ---

	static String status(Arena arena) {
		return Banks.tally(arena) + " pairs  ·  " + whoseTurn(arena);
	}

	static String whoseTurn(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? "Dealing" : TeamColors.of(game.turn.side).name() + "'s turn";
	}

	static int left(Arena arena) {
		Game game = games.get(arena.id);
		if (game == null) return PAIRS;
		int found = 0;
		for (boolean f : game.found) if (f) found++;
		return PAIRS - found / 2;
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
