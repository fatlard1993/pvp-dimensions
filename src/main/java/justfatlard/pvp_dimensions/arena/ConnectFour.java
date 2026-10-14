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
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Connect four, between two teams: a glass frame standing in the middle of the ground, seven
 * channels of six. On your team's turn, right-click a channel's chute, or the glass in front of
 * it, and a cube in your colour drops down it and lands on whatever is already there. Four in a
 * row, across, up or slanting, takes the game; a full frame with no four is nobody's. The frame
 * empties and the side that lost goes first in the next. The first to {@link #TO_WIN} games wins.
 *
 * <p>The pieces are small sulfur cubes, each holding its team's concrete powder. A cube does not
 * stand on another, so each is let fall on its own and held where it lands, in the slot the game
 * has counted it into.
 *
 * <p>The frame is {@link Arena#bases}: its bottom-left slot, seen from the north. Games won are
 * {@link Arena#scores}. The pieces are not saved: a game carried over a restart starts the frame
 * again, with the games won kept.
 */
public final class ConnectFour {
	private ConnectFour() {}

	public static final int COLUMNS = 7;
	public static final int ROWS = 6;
	public static final int TO_WIN = 3;
	/** The mark on a piece, which is the frame's and nobody's to touch. */
	public static final String TAG = "pvp-dimensions.connect_four";
	/** Blocks from one channel to the next: the channel and a pane of glass between. */
	private static final int STEP = 2;
	private static final int WIDE = (COLUMNS - 1) * STEP + 1;
	private static final long TURN_MILLIS = 30_000L;
	/** How long four in a row are shown off before the frame empties. */
	private static final long SHOW_MILLIS = 3_000L;
	/** A piece that has not landed in this long is set down where it belongs. */
	private static final long FALL_LIMIT_MILLIS = 3_000L;

	private static final Random DRAW = new Random();
	private static final int EMPTY = -1;

	/** A piece on its way down: whose, the slot it is falling to, and the cube. */
	private record Falling(int team, int column, int row, SulfurCube cube, String by, long since) {}

	private static final class Game {
		/** The team in each slot, by column then row from the bottom; {@link #EMPTY} for none. */
		final int[][] slots = new int[COLUMNS][ROWS];
		final Turns turn;
		@Nullable Falling falling;
		/** When the frame empties after a game is decided; 0 while one is being played. */
		long clearAt;
		/** Who goes first once it has. */
		int nextFirst;

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
			for (int[] column : slots) java.util.Arrays.fill(column, EMPTY);
		}

		/** The lowest empty slot in a column, or -1 when it is full. */
		int landing(int column) {
			for (int row = 0; row < ROWS; row++) if (slots[column][row] == EMPTY) return row;
			return -1;
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- The frame ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		BlockPos corner = Boards.inMiddle(level, arena, WIDE, 1).above();
		arena.bases.add(corner);
		Builds.yard(level, corner.getX() + WIDE / 2, corner.getY() - 1, corner.getZ(), WIDE / 2 + 4, ROWS + 3);
		Game game = new Game(DRAW.nextInt(2));
		games.put(arena.id, game);
		build(level, corner);
		turnTo(server, arena, game, game.turn.side);
	}

	/**
	 * The frame: a channel of air for each column, glass round and between them so both sides see
	 * in, a base of dark oak, and a hopper over each channel for its chute.
	 */
	private static void build(ServerLevel level, BlockPos corner) {
		BlockState wood = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		BlockState chute = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN);
		for (int x = -1; x <= WIDE; x++) {
			for (int z = -1; z <= 1; z++) {
				for (int row = -1; row <= ROWS; row++) {
					boolean side = x < 0 || x >= WIDE;
					boolean channel = !side && x % STEP == 0 && z == 0;
					BlockState state;
					if (row < 0 || side) state = wood;
					else if (row == ROWS) state = channel ? chute : wood;
					else state = channel ? Blocks.AIR.defaultBlockState() : glass;
					level.setBlock(corner.offset(x, row, z), state, Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Whether this block is the frame, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.CONNECT_FOUR || arena.bases.isEmpty()) return false;
		BlockPos corner = arena.bases.get(0);
		int x = pos.getX() - corner.getX();
		int row = pos.getY() - corner.getY();
		int z = pos.getZ() - corner.getZ();
		return x >= -1 && x <= WIDE && row >= -1 && row <= ROWS && Math.abs(z) <= 1;
	}

	/** The column under this block, or -1: its chute, or the glass before or behind its channel. */
	private static int columnAt(BlockPos corner, BlockPos pos) {
		int x = pos.getX() - corner.getX();
		int row = pos.getY() - corner.getY();
		int z = pos.getZ() - corner.getZ();
		if (x < 0 || x >= WIDE || x % STEP != 0 || Math.abs(z) > 1 || row < 0 || row > ROWS) return -1;
		return x / STEP;
	}

	private static Vec3 slotAt(BlockPos corner, int column, int row) {
		return new Vec3(corner.getX() + column * STEP + 0.5, corner.getY() + row, corner.getZ() + 0.5);
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
		if (game.falling != null) {
			Say.bar(player, "Wait for it to land");
			return InteractionResult.FAIL;
		}
		if (game.turn.side != member.team) {
			Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
			return InteractionResult.FAIL;
		}
		if (game.landing(column) < 0) {
			Say.bar(player, "That column is full");
			return InteractionResult.FAIL;
		}
		drop(level.getServer(), level, arena, member.team, column, member.name);
		return InteractionResult.SUCCESS;
	}

	/** A team drops a piece down a column: a cube let go at the top, to land on whatever is there. */
	public static void drop(MinecraftServer server, ServerLevel level, Arena arena, int team, int column, String by) {
		Game game = games.get(arena.id);
		if (game == null || game.clearAt > 0 || game.falling != null || game.turn.side != team) return;
		int row = game.landing(column);
		if (row < 0) return;
		BlockPos corner = arena.bases.get(0);
		SulfurCube cube = piece(level, team, slotAt(corner, column, ROWS - 1).add(0, 0.3, 0));
		if (cube == null) {
			settle(server, level, arena, game, team, column, row, by);
			return;
		}
		game.falling = new Falling(team, column, row, cube, by, System.currentTimeMillis());
		level.playSound(null, cube.getX(), cube.getY(), cube.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 0.6F);
	}

	/** A small cube in the team's colour: still, silent, unhurt, and the arena's. */
	private static @Nullable SulfurCube piece(ServerLevel level, int team, Vec3 at) {
		SulfurCube cube = EntityTypes.SULFUR_CUBE.create(level, EntitySpawnReason.EVENT);
		if (cube == null) return null;
		cube.addTag(TAG);
		cube.addTag(Mobs.OURS);
		cube.setSize(1, true);
		Identifier powder = Identifier.withDefaultNamespace(TeamColors.of(team).dye() + "_concrete_powder");
		cube.setItemSlot(EquipmentSlot.BODY, BuiltInRegistries.ITEM.getOptional(powder).map(ItemStack::new).orElse(new ItemStack(Items.DIRT)));
		cube.setDropChance(EquipmentSlot.BODY, 0);
		cube.setNoAi(true);
		cube.setSilent(true);
		cube.setPermanentlyInvulnerable(true);
		cube.setPersistenceRequired();
		cube.snapTo(at.x, at.y, at.z, 0, 0);
		level.addFreshEntity(cube);
		return cube;
	}

	/** Every tick: a falling piece, once it reaches its slot, held there and counted. */
	public static void physics(MinecraftServer server) {
		if (games.isEmpty()) return;
		for (Arena arena : List.copyOf(Arenas.vault(server).arenas().values())) {
			Game game = games.get(arena.id);
			if (game == null || game.falling == null || arena.bases.isEmpty()) continue;
			ServerLevel level = Arenas.level(server, arena);
			if (level == null) continue;
			Falling falling = game.falling;
			Vec3 slot = slotAt(arena.bases.get(0), falling.column(), falling.row());
			boolean late = System.currentTimeMillis() - falling.since() > FALL_LIMIT_MILLIS;
			if (falling.cube().isAlive() && falling.cube().getY() > slot.y + 0.01 && !late) continue;
			game.falling = null;
			hold(falling.cube(), slot);
			settle(server, level, arena, game, falling.team(), falling.column(), falling.row(), falling.by());
		}
	}

	private static void hold(SulfurCube cube, Vec3 slot) {
		if (!cube.isAlive()) return;
		cube.setNoGravity(true);
		cube.setDeltaMovement(Vec3.ZERO);
		cube.setPos(slot.x, slot.y, slot.z);
		cube.needsSync = true;
	}

	/** A piece has landed: counted into its slot, and four in a row looked for. */
	private static void settle(MinecraftServer server, ServerLevel level, Arena arena, Game game, int team, int column, int row, String by) {
		game.slots[column][row] = team;
		Vec3 slot = slotAt(arena.bases.get(0), column, row);
		level.playSound(null, slot.x, slot.y, slot.z, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);

		List<int[]> four = four(game.slots, column, row);
		if (four != null) {
			int won = arena.scores.merge(team, 1, Integer::sum);
			Arenas.vault(server).touch();
			for (int[] cell : four) {
				Vec3 at = slotAt(arena.bases.get(0), cell[0], cell[1]);
				level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 0.3, at.z, 10, 0.3, 0.3, 0.3, 0.05);
			}
			level.playSound(null, slot.x, slot.y, slot.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 2.0F, 1.0F);
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

	/** Every piece in or round the frame taken away. */
	private static void clearPieces(ServerLevel level, BlockPos corner) {
		AABB frame = new AABB(corner.getX() - 2, corner.getY() - 2, corner.getZ() - 2, corner.getX() + WIDE + 2, corner.getY() + ROWS + 2, corner.getZ() + 3);
		for (SulfurCube cube : level.getEntitiesOfClass(SulfurCube.class, frame, cube -> cube.entityTags().contains(TAG))) cube.discard();
	}

	/** The team in a slot, for the tests; -1 for none. */
	public static int slot(Arena arena, int column, int row) {
		Game game = games.get(arena.id);
		return game == null ? EMPTY : game.slots[column][row];
	}

	/** The cube standing in a slot, for the tests: one held there, or none. */
	public static @Nullable SulfurCube pieceAt(ServerLevel level, Arena arena, int column, int row) {
		Vec3 slot = slotAt(arena.bases.get(0), column, row);
		List<SulfurCube> near = level.getEntitiesOfClass(SulfurCube.class, new AABB(slot, slot).inflate(0.3, 0.3, 0.3),
			cube -> cube.entityTags().contains(TAG));
		return near.isEmpty() ? null : near.get(0);
	}

	/** The block a player clicks to drop down a column: its chute. */
	public static BlockPos chute(Arena arena, int column) {
		return arena.bases.get(0).offset(column * STEP, ROWS, 0);
	}

	public static int side(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.turn.side;
	}

	public static boolean falling(Arena arena) {
		Game game = games.get(arena.id);
		return game != null && game.falling != null;
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
		Arenas.tellInside(server, arena, TeamColors.of(side).name() + " to drop: right-click a chute, or the glass in front of a channel");
		Boards.titleTeam(server, arena, side, "Your turn", "Right-click a channel to drop a cube");
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: the pieces were never written down. The frame empties; the games won stand.
			if (arena.bases.isEmpty()) return;
			game = new Game(0);
			games.put(arena.id, game);
			clearPieces(level, arena.bases.get(0));
			build(level, arena.bases.get(0));
			Arenas.tellInside(server, arena, "The pieces fell out of the frame; a new game, with the games won kept");
			turnTo(server, arena, game, 0);
			return;
		}
		if (game.clearAt > 0) {
			if (now < game.clearAt) return;
			game.clearAt = 0;
			for (int[] column : game.slots) java.util.Arrays.fill(column, EMPTY);
			clearPieces(level, arena.bases.get(0));
			Arenas.tellInside(server, arena, "A new game: " + Banks.tally(arena));
			turnTo(server, arena, game, game.nextFirst);
			return;
		}
		if (game.falling != null) return;
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
		if (game.falling != null) return "Dropping";
		return TeamColors.of(game.turn.side).name() + " to drop";
	}

	public static void forget(Arena arena) {
		Game game = games.remove(arena.id);
		if (game != null && game.falling != null) game.falling.cube().discard();
	}
}
