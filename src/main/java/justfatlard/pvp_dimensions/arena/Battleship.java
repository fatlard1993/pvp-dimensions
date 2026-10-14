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
import org.jspecify.annotations.Nullable;

/**
 * Battleship, between two teams, played as at the table: a wall down the middle of the ground so
 * neither side sees the other's, and at each team's end two grids of ten by ten. One is the team's
 * own fleet, where it lays its ships and watches the other side's shots land on them; the other is
 * the other side's waters, where it fires.
 *
 * <p>First the fleets go out. Right-click a square of your fleet grid to lay the next ship there,
 * biggest first, lying across; right-click its first square again to turn it, or any other square
 * of it to pick it back up. Ring your bell when your fleet is out, and any ship not yet laid is laid
 * at random. Once both sides have rung, or the setup clock has run out, the shooting starts.
 *
 * <p>On your turn, right-click a square of their waters to fire there. A miss goes white, a hit red,
 * and a ship sunk black from end to end, on both boards. Sink their whole fleet first to win.
 *
 * <p>{@link Arena#bases} holds each team's waters (0 and 1), then each team's fleet (2 and 3): the
 * first corner of each grid, at the height of the floor it is set in. Hits landed are {@link
 * Arena#scores}. The fleets are not saved: a game carried over a restart lays its fleets again, on
 * clean boards.
 */
public final class Battleship {
	private Battleship() {}

	public static final int SIZE = 10;
	/** The classic fleet, biggest first. */
	private static final String[] SHIP_NAMES = {"Carrier", "Battleship", "Cruiser", "Submarine", "Destroyer"};
	private static final int[] SHIP_LENGTHS = {5, 4, 3, 3, 2};
	private static final long TURN_MILLIS = 30_000L;
	private static final long SETUP_MILLIS = 180_000L;
	/** Blocks kept between the back of a station and the arena's edge. */
	private static final int BEHIND = 6;
	/** Between the two grids of a station, rims and all. */
	private static final int APART = 6;

	private static final Random DRAW = new Random();

	/** A ship as laid: which of the fleet, where its first square is, and which way it lies. */
	private record Laid(int ship, int start, boolean across) {
		Set<Integer> cells() {
			Set<Integer> cells = new HashSet<>();
			for (int i = 0; i < SHIP_LENGTHS[ship]; i++) cells.add(across ? start + i : start + i * SIZE);
			return cells;
		}
	}

	private static final class Fleet {
		final Map<Integer, Laid> laid = new HashMap<>();
		boolean ready;

		/** The biggest ship not yet laid, or -1 when they all are. */
		int next() {
			for (int ship = 0; ship < SHIP_LENGTHS.length; ship++) if (!laid.containsKey(ship)) return ship;
			return -1;
		}

		@Nullable Laid at(int cell) {
			for (Laid ship : laid.values()) if (ship.cells().contains(cell)) return ship;
			return null;
		}

		Set<Integer> cells() {
			Set<Integer> all = new HashSet<>();
			for (Laid ship : laid.values()) all.addAll(ship.cells());
			return all;
		}
	}

	private static final class Game {
		final Fleet[] fleets = {new Fleet(), new Fleet()};
		/** The squares each team has fired at, on its board of the other's waters. */
		final List<Set<Integer>> fired = List.of(new HashSet<>(), new HashSet<>());
		boolean setup = true;
		long setupEnds = System.currentTimeMillis() + SETUP_MILLIS;
		final Turns turn;

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
		}

		boolean sunk(int team, int ship) {
			Laid laid = fleets[team].laid.get(ship);
			return laid != null && fired.get(1 - team).containsAll(laid.cells());
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- Laying ships ---

	/** Whether a ship would lie wholly on the board here, clear of every other ship. */
	private static boolean fits(Fleet fleet, Laid ship) {
		int row = ship.start() / SIZE;
		int column = ship.start() % SIZE;
		int length = SHIP_LENGTHS[ship.ship()];
		if (ship.across() ? column + length > SIZE : row + length > SIZE) return false;
		for (Laid other : fleet.laid.values()) {
			if (other.ship() == ship.ship()) continue;
			for (int cell : other.cells()) if (ship.cells().contains(cell)) return false;
		}
		return true;
	}

	/** Every ship not yet laid, laid at random where it fits. */
	private static void layRest(Fleet fleet, Random random) {
		for (int ship = fleet.next(); ship >= 0; ship = fleet.next()) {
			while (true) {
				Laid laid = new Laid(ship, random.nextInt(SIZE * SIZE), random.nextBoolean());
				if (!fits(fleet, laid)) continue;
				fleet.laid.put(ship, laid);
				break;
			}
		}
	}

	/** A fleet laid at random, for the tests: each ship's squares, biggest first. */
	public static List<Set<Integer>> fleet(Random random) {
		Fleet fleet = new Fleet();
		layRest(fleet, random);
		List<Set<Integer>> ships = new ArrayList<>();
		for (int ship = 0; ship < SHIP_LENGTHS.length; ship++) ships.add(fleet.laid.get(ship).cells());
		return ships;
	}

	/** Every square of this team's fleet, for the tests to aim at. */
	public static Set<Integer> fleetOf(Arena arena, int team) {
		Game game = games.get(arena.id);
		return game == null ? Set.of() : game.fleets[team].cells();
	}

	public static boolean settingUp(Arena arena) {
		Game game = games.get(arena.id);
		return game != null && game.setup;
	}

	/** A square's name as it is called out: a row's letter and a column's number, as "C4". */
	public static String name(int cell) {
		return (char) ('A' + cell / SIZE) + Integer.toString(cell % SIZE + 1);
	}

	// --- The stations ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		BlockPos[] waters = new BlockPos[2];
		BlockPos[] fleets = new BlockPos[2];
		for (int team = 0; team < 2; team++) {
			BlockPos middle = stationMiddle(level, arena, team);
			waters[team] = middle.offset(-SIZE / 2, 0, -SIZE - APART / 2);
			fleets[team] = middle.offset(-SIZE / 2, 0, APART / 2);
		}
		for (int team = 0; team < 2; team++) arena.bases.add(waters[team]);
		for (int team = 0; team < 2; team++) arena.bases.add(fleets[team]);
		Game game = new Game(DRAW.nextInt(2));
		games.put(arena.id, game);
		for (int team = 0; team < 2; team++) build(level, arena, game, team);
		Boards.wall(level, arena);
		Arenas.tellInside(server, arena, "Lay your fleet: right-click your fleet grid to place each ship, biggest first."
			+ " Click a ship's first square to turn it, any other square to pick it up. Ring your bell when you're ready");
	}

	/** The middle of a team's station: its own division where the ground is divided, else its end of the ground. */
	private static BlockPos stationMiddle(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		Terrain terrain = footprint.terrain();
		int x;
		int z;
		if (terrain.divided() && terrain.parts() >= 2) {
			int[] middle = terrain.partCenter(footprint, team);
			x = middle[0];
			z = middle[1];
		} else {
			int width = footprint.width();
			double radius = Math.max(SIZE, Math.min(width * Preset.GOAL_RING, width / 2.0 - SIZE / 2.0 - BEHIND));
			double angle = arena.preset.ringAngle(team);
			x = (int) Math.floor(footprint.centerX() + Math.cos(angle) * radius);
			z = (int) Math.floor(footprint.centerZ());
		}
		return new BlockPos(x, Builds.ground(level, arena, x, z), z);
	}

	/** A team's station: both grids as the game stands, their signs, and the bell. */
	private static void build(ServerLevel level, Arena arena, Game game, int team) {
		BlockPos waters = arena.bases.get(team);
		BlockPos fleet = arena.bases.get(2 + team);
		Builds.yard(level, waters.getX() + SIZE / 2, waters.getY(), waters.getZ() + SIZE + APART / 2, SIZE + 3, 3);
		BlockState rim = Terrain.block(TeamColors.of(team).concrete(), Blocks.STONE.defaultBlockState());
		for (BlockPos corner : new BlockPos[] {waters, fleet}) {
			for (int x = -2; x <= SIZE + 1; x++) {
				for (int z = -2; z <= SIZE + 1; z++) {
					boolean inside = x >= 0 && x < SIZE && z >= 0 && z < SIZE;
					if (!inside) level.setBlock(corner.offset(x, -1, z), rim, Block.UPDATE_ALL);
				}
			}
			for (int i = 0; i < SIZE; i++) {
				Boards.sign(level, corner.offset(-1, 0, i), String.valueOf((char) ('A' + i)), 12);
				Boards.sign(level, corner.offset(i, 0, -1), Integer.toString(i + 1), 8);
			}
		}
		for (int cell = 0; cell < SIZE * SIZE; cell++) {
			paintWaters(level, arena, game, team, cell);
			paintFleet(level, arena, game, team, cell);
		}
		Boards.sign(level, waters.offset(SIZE / 2, 0, SIZE + 1), "Their waters", 8);
		Boards.sign(level, fleet.offset(SIZE / 2, 0, -2), "Your fleet", 8);
		level.setBlock(bellAt(arena, team), Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** The bell between the two grids, out past their edge on the side away from the wall. */
	public static BlockPos bellAt(Arena arena, int team) {
		BlockPos waters = arena.bases.get(team);
		int x = awayFromWall(arena, team) > 0 ? waters.getX() + SIZE + 2 : waters.getX() - 3;
		return new BlockPos(x, waters.getY(), waters.getZ() + SIZE + APART / 2);
	}

	private static int awayFromWall(Arena arena, int team) {
		return arena.bases.get(team).getX() + SIZE / 2.0 > arena.footprint().centerX() ? 1 : -1;
	}

	/** A square of a team's board of the other side's waters: what it has found there. */
	private static void paintWaters(ServerLevel level, Arena arena, Game game, int team, int cell) {
		BlockState look;
		if (!game.fired.get(team).contains(cell)) {
			look = Blocks.TARGET.defaultBlockState();
		} else {
			Laid ship = game.fleets[1 - team].at(cell);
			look = ship == null ? wool("white") : game.sunk(1 - team, ship.ship()) ? wool("black") : wool("red");
		}
		level.setBlock(posOf(arena.bases.get(team), cell), look, Block.UPDATE_ALL);
	}

	/** A square of a team's own fleet: sea, a ship, and what the other side has hit or missed. */
	private static void paintFleet(ServerLevel level, Arena arena, Game game, int team, int cell) {
		Laid ship = game.fleets[team].at(cell);
		boolean shot = game.fired.get(1 - team).contains(cell);
		BlockState look;
		if (ship == null) look = shot ? wool("white") : Terrain.block("minecraft:light_blue_concrete", Blocks.LAPIS_BLOCK.defaultBlockState());
		else if (!shot) look = Terrain.block("minecraft:gray_concrete", Blocks.STONE.defaultBlockState());
		else look = game.sunk(team, ship.ship()) ? wool("black") : wool("red");
		level.setBlock(posOf(arena.bases.get(2 + team), cell), look, Block.UPDATE_ALL);
	}

	private static BlockState wool(String colour) {
		return Terrain.block("minecraft:" + colour + "_wool", Blocks.TARGET.defaultBlockState());
	}

	/** The square of a grid under this block, or -1. */
	private static int cellAt(BlockPos corner, BlockPos pos) {
		int x = pos.getX() - corner.getX();
		int z = pos.getZ() - corner.getZ();
		if (pos.getY() != corner.getY() - 1 || x < 0 || x >= SIZE || z < 0 || z >= SIZE) return -1;
		return z * SIZE + x;
	}

	public static BlockPos posOf(BlockPos corner, int cell) {
		return corner.offset(cell % SIZE, -1, cell / SIZE);
	}

	/** Whether this block is part of a station, its signs, its bell or the wall, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.BATTLESHIP) return false;
		if (Boards.inWall(arena, pos)) return true;
		if (arena.bases.size() < 4) return false;
		for (int team = 0; team < 2; team++) {
			if (pos.equals(bellAt(arena, team)) || pos.equals(bellAt(arena, team).below())) return true;
		}
		for (BlockPos corner : arena.bases) {
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			int up = pos.getY() - corner.getY();
			if (x >= -2 && x <= SIZE + 1 && z >= -2 && z <= SIZE + 1 && (up == -1 || up == 0)) return true;
		}
		return false;
	}

	// --- Right-clicks ---

	/** A player right-clicked a block: a ship laid, a bell rung, or a shot, if it is theirs and the time for it. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.BATTLESHIP || arena.bases.size() < 4) return InteractionResult.PASS;
		Game game = games.get(arena.id);
		if (game == null) return InteractionResult.PASS;
		for (int team = 0; team < 2; team++) {
			boolean bell = pos.equals(bellAt(arena, team));
			int waters = cellAt(arena.bases.get(team), pos);
			int fleet = cellAt(arena.bases.get(2 + team), pos);
			if (!bell && waters < 0 && fleet < 0) continue;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			Arena.Member member = Boards.playing(arena, player);
			if (member == null) return InteractionResult.FAIL;
			if (member.team != team) {
				Say.bar(player, "That's " + TeamColors.of(team).name() + "'s; yours is on your own side");
				return InteractionResult.FAIL;
			}
			if (bell) {
				ready(level.getServer(), level, arena, team, member.name);
				// Let it ring.
				return InteractionResult.PASS;
			}
			if (fleet >= 0) return lay(player, level, arena, game, team, fleet) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
			if (game.setup) {
				Say.bar(player, "Lay your fleet first; the shooting starts when both sides have rung their bells");
				return InteractionResult.FAIL;
			}
			if (game.turn.side != team) {
				Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
				return InteractionResult.FAIL;
			}
			if (game.fired.get(team).contains(waters)) {
				Say.bar(player, name(waters) + " has been fired at already");
				return InteractionResult.FAIL;
			}
			fire(level.getServer(), level, arena, team, waters, member.name);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/**
	 * A click on a team's own fleet grid while the fleets go out: the next ship laid from that
	 * square, a ship turned on its first square, or a ship picked up.
	 *
	 * @return whether anything changed
	 */
	private static boolean lay(@Nullable ServerPlayer player, ServerLevel level, Arena arena, Game game, int team, int cell) {
		Fleet fleet = game.fleets[team];
		if (!game.setup || fleet.ready) {
			if (player != null) Say.bar(player, game.setup ? "Your fleet is out; wait for the other side" : "The fleets are out; fire from their waters");
			return false;
		}
		Laid there = fleet.at(cell);
		Set<Integer> changed = new HashSet<>();
		if (there != null) {
			changed.addAll(there.cells());
			fleet.laid.remove(there.ship());
			if (there.start() == cell) {
				Laid turned = new Laid(there.ship(), cell, !there.across());
				if (!fits(fleet, turned)) {
					fleet.laid.put(there.ship(), there);
					if (player != null) Say.bar(player, "No room to turn the " + SHIP_NAMES[there.ship()] + " there");
					return false;
				}
				fleet.laid.put(there.ship(), turned);
				changed.addAll(turned.cells());
			} else if (player != null) {
				Say.bar(player, "Picked up the " + SHIP_NAMES[there.ship()]);
			}
		} else {
			int ship = fleet.next();
			if (ship < 0) {
				if (player != null) Say.bar(player, "Your whole fleet is out: ring your bell");
				return false;
			}
			Laid laid = new Laid(ship, cell, true);
			if (!fits(fleet, laid)) laid = new Laid(ship, cell, false);
			if (!fits(fleet, laid)) {
				if (player != null) Say.bar(player, "No room for the " + SHIP_NAMES[ship] + " (" + SHIP_LENGTHS[ship] + ") there");
				return false;
			}
			fleet.laid.put(ship, laid);
			changed.addAll(laid.cells());
			if (player != null) {
				int next = fleet.next();
				Say.bar(player, "The " + SHIP_NAMES[ship] + " is out" + (next < 0 ? "; your fleet is ready, ring your bell"
					: ". Next: the " + SHIP_NAMES[next] + " (" + SHIP_LENGTHS[next] + ")"));
			}
		}
		for (int square : changed) paintFleet(level, arena, game, team, square);
		BlockPos at = posOf(arena.bases.get(2 + team), cell);
		level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.8F, 1.0F);
		return true;
	}

	/** A team rings its bell: whatever is not laid is laid at random, and it is ready. */
	public static void ready(MinecraftServer server, ServerLevel level, Arena arena, int team, String by) {
		Game game = games.get(arena.id);
		if (game == null || !game.setup || game.fleets[team].ready) return;
		Fleet fleet = game.fleets[team];
		boolean random = fleet.next() >= 0;
		layRest(fleet, DRAW);
		fleet.ready = true;
		for (int cell = 0; cell < SIZE * SIZE; cell++) paintFleet(level, arena, game, team, cell);
		Arenas.tellInside(server, arena, by + " rings the bell: " + TeamColors.of(team).name() + "'s fleet is out"
			+ (random ? ", the rest of it laid at random" : ""));
		startIfReady(server, level, arena, game, false);
	}

	/** Both fleets out, or the setup clock run down, or one side with nobody to lay one: the shooting starts. */
	private static void startIfReady(MinecraftServer server, ServerLevel level, Arena arena, Game game, boolean timeUp) {
		boolean anyoneReady = game.fleets[0].ready || game.fleets[1].ready;
		for (int team = 0; team < 2; team++) {
			if (game.fleets[team].ready) continue;
			if (!timeUp && (Turns.present(arena, team) || !anyoneReady)) return;
		}
		for (int team = 0; team < 2; team++) {
			Fleet fleet = game.fleets[team];
			if (fleet.ready) continue;
			layRest(fleet, DRAW);
			fleet.ready = true;
			for (int cell = 0; cell < SIZE * SIZE; cell++) paintFleet(level, arena, game, team, cell);
		}
		game.setup = false;
		Arenas.tellInside(server, arena, "Both fleets are out. The shooting starts");
		turnTo(server, arena, game, game.turn.side);
	}

	// --- Firing ---

	/** A team fires at a square of the other team's waters. */
	public static void fire(MinecraftServer server, ServerLevel level, Arena arena, int team, int cell, String by) {
		Game game = games.get(arena.id);
		if (game == null || game.setup || game.turn.side != team || game.fired.get(team).contains(cell)) return;
		int enemy = 1 - team;
		game.fired.get(team).add(cell);
		Laid struck = game.fleets[enemy].at(cell);
		BlockPos square = posOf(arena.bases.get(team), cell);
		double x = square.getX() + 0.5, y = square.getY() + 1.2, z = square.getZ() + 0.5;
		String shooter = by + " fires at " + name(cell);
		if (struck == null) {
			paintWaters(level, arena, game, team, cell);
			paintFleet(level, arena, game, enemy, cell);
			level.sendParticles(ParticleTypes.SPLASH, x, y, z, 40, 0.4, 0.2, 0.4, 0.1);
			level.playSound(null, x, y, z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.0F, 1.0F);
			Arenas.tellInside(server, arena, shooter + ": a miss");
		} else {
			arena.scores.merge(team, 1, Integer::sum);
			Arenas.vault(server).touch();
			level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 2, 0.2, 0.2, 0.2, 0);
			level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.2F);
			boolean sunk = game.sunk(enemy, struck.ship());
			for (int part : sunk ? struck.cells() : Set.of(cell)) {
				paintWaters(level, arena, game, team, part);
				paintFleet(level, arena, game, enemy, part);
			}
			if (sunk) {
				String ship = TeamColors.of(enemy).name() + "'s " + SHIP_NAMES[struck.ship()];
				Arenas.tellInside(server, arena, shooter + ": a hit, and " + ship + " is sunk!");
				Boards.title(server, arena, team, "Sunk!", ship);
			} else {
				Arenas.tellInside(server, arena, shooter + ": a hit!");
			}
			if (afloat(arena, enemy) == 0) {
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
		int side = game.turn.side;
		Arenas.tellInside(server, arena, TeamColors.of(side).name() + " to fire: right-click a square of their waters");
		Boards.titleTeam(server, arena, side, "Your turn", "Fire at a square of their waters");
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: the fleets were never written down. Laid again, on clean boards.
			if (arena.bases.size() < 4) return;
			arena.scores.clear();
			game = new Game(0);
			games.put(arena.id, game);
			for (int team = 0; team < 2; team++) build(level, arena, game, team);
			Arenas.tellInside(server, arena, "The fleets were lost in the fog; lay them out again, and ring your bell");
			return;
		}
		if (game.setup) {
			boolean timeUp = now >= game.setupEnds;
			if (timeUp) Arenas.tellInside(server, arena, "Time's up for laying fleets: anything not out is laid at random");
			startIfReady(server, level, arena, game, timeUp);
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
		if (game == null) return "Ships putting out";
		if (game.setup) {
			long left = Math.max(0, (game.setupEnds - System.currentTimeMillis()) / 1000);
			return "Laying fleets, " + left / 60 + ":" + String.format("%02d", left % 60);
		}
		return TeamColors.of(game.turn.side).name() + " to fire";
	}

	/** This team's ships still afloat, for the board. */
	static int afloat(Arena arena, int team) {
		Game game = games.get(arena.id);
		if (game == null || game.setup) return SHIP_LENGTHS.length;
		int left = 0;
		for (int ship = 0; ship < SHIP_LENGTHS.length; ship++) if (!game.sunk(team, ship)) left++;
		return left;
	}

	static int ships() {
		return SHIP_LENGTHS.length;
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
