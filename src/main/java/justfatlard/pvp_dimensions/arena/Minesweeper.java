package justfatlard.pvp_dimensions.arena;

import com.mojang.math.Transformation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * Minesweeper, as a race: every team has a field of its own at its end of the ground, the same
 * size and as many mines, and the first to uncover every square without a mine wins. Right-click a
 * square to uncover it; crouch and right-click to flag it. An uncovered square shows how many of
 * the eight round it hold a mine, and one that touches none opens up everything round it. Right-
 * click a number with as many flags round it as it says to uncover the rest of its neighbours.
 *
 * <p>Uncover a mine and the field goes up: the team waits a few seconds, then starts again on a
 * new field. The first square of a field is never a mine, nor anything round it.
 *
 * <p>A field is {@link Arena#bases}: each team's first corner, at the height of the floor it is set
 * in. Squares uncovered on the field each team is on now are {@link Arena#scores}, so time running
 * out goes to whoever is furthest. The mines are not saved: over a restart every field starts
 * again.
 */
public final class Minesweeper {
	private Minesweeper() {}

	public static final int SIZE = 12;
	public static final int MINES = 22;
	private static final int BEHIND = 6;
	/** How long a team stands looking at its mines before the new field. */
	private static final long BLAST_MILLIS = 5_000L;
	public static final String TEXT_TAG = "pvp-dimensions.minesweeper";
	/** The colours the numbers have always been, one to eight. */
	private static final int[] NUMBER_COLOURS = {0x1976D2, 0x388E3C, 0xD32F2F, 0x7B1FA2, 0xFF8F00, 0x0097A7, 0x424242, 0x9E9E9E};

	private static final Random DRAW = new Random();

	private static final class Field {
		/** Empty until the first square is uncovered, which lays the mines round it. */
		final Set<Integer> mines = new HashSet<>();
		final Set<Integer> open = new HashSet<>();
		final Set<Integer> flags = new HashSet<>();
		/** When the field is set again after a mine; 0 while it is being played. */
		long resetAt;
	}

	private static final Map<String, Map<Integer, Field>> games = new HashMap<>();

	// --- The mines ---

	/** Mines laid at random on every square but this one and those round it. */
	public static Set<Integer> lay(Random random, int first) {
		Set<Integer> mines = new HashSet<>();
		Set<Integer> spared = new HashSet<>(neighbours(first));
		spared.add(first);
		while (mines.size() < MINES) {
			int square = random.nextInt(SIZE * SIZE);
			if (!spared.contains(square)) mines.add(square);
		}
		return mines;
	}

	public static Set<Integer> neighbours(int square) {
		Set<Integer> round = new HashSet<>();
		int row = square / SIZE;
		int column = square % SIZE;
		for (int dr = -1; dr <= 1; dr++) {
			for (int dc = -1; dc <= 1; dc++) {
				int r = row + dr;
				int c = column + dc;
				if ((dr != 0 || dc != 0) && r >= 0 && r < SIZE && c >= 0 && c < SIZE) round.add(r * SIZE + c);
			}
		}
		return round;
	}

	static int count(Set<Integer> mines, int square) {
		int near = 0;
		for (int round : neighbours(square)) if (mines.contains(round)) near++;
		return near;
	}

	// --- The fields ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		Map<Integer, Field> fields = new HashMap<>();
		for (int team = 0; team < arena.preset.teams; team++) {
			BlockPos corner = Boards.atEnd(level, arena, team, SIZE, BEHIND);
			arena.bases.add(corner);
			Builds.yard(level, corner.getX() + SIZE / 2, corner.getY(), corner.getZ() + SIZE / 2, SIZE / 2 + 3, 3);
			Field field = new Field();
			fields.put(team, field);
			draw(level, arena, team, field);
		}
		games.put(arena.id, fields);
		Arenas.tellInside(server, arena, "Clear your field before anyone else. Right-click to uncover, crouch and right-click to flag");
	}

	/** A field as it stands: every square, a rim in the team's colour, and the numbers over it. */
	private static void draw(ServerLevel level, Arena arena, int team, Field field) {
		BlockPos corner = arena.bases.get(team);
		clearNumbers(level, corner);
		BlockState rim = Boards.rim(team);
		for (int x = -1; x <= SIZE; x++) {
			for (int z = -1; z <= SIZE; z++) {
				boolean edge = x < 0 || x >= SIZE || z < 0 || z >= SIZE;
				level.setBlock(corner.offset(x, -1, z), edge ? rim : look(field, z * SIZE + x, false), Block.UPDATE_ALL);
			}
		}
		for (int square : field.open) number(level, corner, field, square);
	}

	private static BlockState look(Field field, int square, boolean blown) {
		boolean light = (square / SIZE + square % SIZE) % 2 == 0;
		if (blown && field.mines.contains(square)) return Boards.block("tnt");
		if (field.open.contains(square)) return Boards.block(light ? "smooth_sandstone" : "birch_planks");
		if (field.flags.contains(square)) return Boards.block("red_wool");
		return Boards.block(light ? "lime_concrete" : "lime_terracotta");
	}

	private static BlockPos squareAt(BlockPos corner, int square) {
		return corner.offset(square % SIZE, -1, square / SIZE);
	}

	/** The number lying on an uncovered square, if it touches any mines. */
	private static void number(ServerLevel level, BlockPos corner, Field field, int square) {
		int near = count(field.mines, square);
		if (near == 0) return;
		Display.TextDisplay text = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
		if (text == null) return;
		BlockPos at = squareAt(corner, square);
		// Lying face up on the square, the way the field is read from standing on it.
		text.snapTo(at.getX() + 0.5, at.getY() + 1.02, at.getZ() + 0.5, 180, -90);
		int colour = NUMBER_COLOURS[near - 1];
		text.setText(Component.literal(Integer.toString(near)).withStyle(style -> style.withBold(true).withColor(TextColor.fromRgb(colour))));
		text.setBackgroundColor(0);
		text.setTransformation(new Transformation(new Vector3f(0, -0.4F, 0), null, new Vector3f(3, 3, 3), null));
		text.addTag(TEXT_TAG);
		level.addFreshEntity(text);
	}

	private static void clearNumbers(ServerLevel level, BlockPos corner) {
		AABB area = new AABB(corner.getX() - 1, corner.getY() - 2, corner.getZ() - 1, corner.getX() + SIZE + 1, corner.getY() + 2, corner.getZ() + SIZE + 1);
		for (Display.TextDisplay text : level.getEntitiesOfClass(Display.TextDisplay.class, area, text -> text.entityTags().contains(TEXT_TAG))) text.discard();
	}

	/** Whether this block is part of a field or its rim, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.MINESWEEPER) return false;
		for (BlockPos corner : arena.bases) {
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			if (pos.getY() == corner.getY() - 1 && x >= -1 && x <= SIZE && z >= -1 && z <= SIZE) return true;
		}
		return false;
	}

	// --- Playing ---

	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.MINESWEEPER) return InteractionResult.PASS;
		Map<Integer, Field> fields = games.get(arena.id);
		if (fields == null) return InteractionResult.PASS;
		for (int team = 0; team < arena.bases.size(); team++) {
			BlockPos corner = arena.bases.get(team);
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			if (pos.getY() != corner.getY() - 1 || x < 0 || x >= SIZE || z < 0 || z >= SIZE) continue;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			Arena.Member member = Boards.playing(arena, player);
			Field field = fields.get(team);
			if (member == null || field == null) return InteractionResult.FAIL;
			if (member.team != team) {
				Say.bar(player, "That's " + TeamColors.of(team).name() + "'s field; yours is at your own end");
				return InteractionResult.FAIL;
			}
			if (field.resetAt > 0) {
				Say.bar(player, "A new field is coming");
				return InteractionResult.FAIL;
			}
			int square = z * SIZE + x;
			if (player.isShiftKeyDown()) flag(level, arena, team, square);
			else uncover(level.getServer(), level, arena, team, square, member.name);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	public static void flag(ServerLevel level, Arena arena, int team, int square) {
		Field field = field(arena, team);
		if (field == null || field.open.contains(square)) return;
		if (!field.flags.remove(square)) field.flags.add(square);
		BlockPos at = squareAt(arena.bases.get(team), square);
		level.setBlock(at, look(field, square, false), Block.UPDATE_ALL);
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.8F, 1.0F);
	}

	/**
	 * A team uncovers a square: a mine blows the field, a number shows, a square touching no mine
	 * opens everything round it, and a number already open with its flags all down opens the rest
	 * of its neighbours.
	 */
	public static void uncover(MinecraftServer server, ServerLevel level, Arena arena, int team, int square, String by) {
		Field field = field(arena, team);
		if (field == null || field.resetAt > 0 || field.flags.contains(square)) return;
		if (field.mines.isEmpty()) field.mines.addAll(lay(DRAW, square));
		Set<Integer> toOpen = new HashSet<>();
		if (field.open.contains(square)) {
			int flagged = 0;
			for (int round : neighbours(square)) if (field.flags.contains(round)) flagged++;
			if (flagged != count(field.mines, square)) return;
			for (int round : neighbours(square)) if (!field.flags.contains(round) && !field.open.contains(round)) toOpen.add(round);
		} else {
			toOpen.add(square);
		}
		for (int open : toOpen) {
			if (field.mines.contains(open)) {
				blow(server, level, arena, team, field, open, by);
				return;
			}
		}
		BlockPos corner = arena.bases.get(team);
		Deque<Integer> spreading = new ArrayDeque<>(toOpen);
		while (!spreading.isEmpty()) {
			int next = spreading.poll();
			if (field.open.contains(next) || field.mines.contains(next)) continue;
			field.open.add(next);
			field.flags.remove(next);
			level.setBlock(squareAt(corner, next), look(field, next, false), Block.UPDATE_ALL);
			number(level, corner, field, next);
			if (count(field.mines, next) == 0) spreading.addAll(neighbours(next));
		}
		BlockPos at = squareAt(corner, square);
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 0.8F, 1.2F);
		arena.scores.put(team, field.open.size());
		Arenas.vault(server).touch();
		if (field.open.size() >= SIZE * SIZE - MINES) Goals.winTeam(server, arena, team, "cleared its field first");
	}

	private static void blow(MinecraftServer server, ServerLevel level, Arena arena, int team, Field field, int square, String by) {
		BlockPos corner = arena.bases.get(team);
		for (int mine : field.mines) level.setBlock(squareAt(corner, mine), look(field, mine, true), Block.UPDATE_ALL);
		BlockPos at = squareAt(corner, square);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 1, 0, 0, 0, 0);
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.5F, 1.0F);
		field.resetAt = System.currentTimeMillis() + BLAST_MILLIS;
		arena.scores.put(team, 0);
		Arenas.vault(server).touch();
		Boards.titleTeam(server, arena, team, "Boom!", by + " found a mine. A new field in a moment");
		Arenas.tellInside(server, arena, by + " found a mine: " + TeamColors.of(team).name() + " starts a new field");
	}

	private static Field field(Arena arena, int team) {
		Map<Integer, Field> fields = games.get(arena.id);
		return fields == null ? null : fields.get(team);
	}

	/** A team's mines, for the tests: laid by uncovering this square first, if they are not yet. */
	public static Set<Integer> minesOf(Arena arena, int team, int first) {
		Field field = field(arena, team);
		if (field == null) return Set.of();
		if (field.mines.isEmpty()) field.mines.addAll(lay(DRAW, first));
		return Set.copyOf(field.mines);
	}

	public static boolean isOpen(Arena arena, int team, int square) {
		Field field = field(arena, team);
		return field != null && field.open.contains(square);
	}

	public static boolean blown(Arena arena, int team) {
		Field field = field(arena, team);
		return field != null && field.resetAt > 0;
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Map<Integer, Field> fields = games.get(arena.id);
		if (fields == null) {
			// Over a restart: the mines were never written down. Every field starts again.
			if (arena.bases.size() < arena.preset.teams) return;
			fields = new HashMap<>();
			for (int team = 0; team < arena.bases.size(); team++) {
				Field field = new Field();
				fields.put(team, field);
				draw(level, arena, team, field);
			}
			games.put(arena.id, fields);
			arena.scores.clear();
			Arenas.tellInside(server, arena, "The fields were swept clean; everyone starts a new one");
			return;
		}
		for (Map.Entry<Integer, Field> entry : fields.entrySet()) {
			Field field = entry.getValue();
			if (field.resetAt == 0 || now < field.resetAt) continue;
			Field fresh = new Field();
			entry.setValue(fresh);
			draw(level, arena, entry.getKey(), fresh);
		}
	}

	// --- Words ---

	static String status(Arena arena, Arena.@org.jspecify.annotations.Nullable Member viewer) {
		Field field = viewer == null ? null : field(arena, viewer.team);
		if (field == null) return Banks.tally(arena);
		int left = SIZE * SIZE - MINES - field.open.size();
		return field.resetAt > 0 ? "Boom! A new field in a moment" : left + " squares to clear  ·  " + field.flags.size() + "/" + MINES + " flagged";
	}

	static int toClear() {
		return SIZE * SIZE - MINES;
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
