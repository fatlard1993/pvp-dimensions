package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Bowling: a lane for each team, side by side down the middle of the ground, ten pins at the far
 * end of each. A team bowls its own ten frames on its own lane, its players taking a frame each in
 * turn, and the highest score when every lane is done wins. Scored as at any alley: a strike is ten
 * and the next two balls, a spare ten and the next one, and the tenth frame has a third ball for
 * either.
 *
 * <p>The pins and the ball are cubes, like every ball here: the pins white, the ball in the team's
 * colour, and only the ball takes a hit, only from whoever's frame it is. The ball is the heavier,
 * so it carries on through the pins it hits, and they scatter into each other. Each pin is two
 * cubes, a head riding its foot: knock the foot and the head is thrown off, so a pin goes over. A
 * pin is down once its head is off or its foot is knocked off its spot.
 *
 * <p>Each lane is {@link Arena#bases}: the middle of the foul line on its floor, at the height the
 * ball rolls at. Each team's score so far is {@link Arena#scores}. The frames are not saved: a game
 * carried over a restart starts again from the first.
 */
public final class Bowling {
	private Bowling() {}

	/** Blocks from the foul line to the head pin. */
	private static final int TO_HEAD_PIN = 15;
	/** The lane runs on behind the back pins to the pit. */
	private static final int LENGTH = 21;
	private static final int HALF_WIDTH = 3;
	/** Two across, like a pool pocket, so the ball fits into it. */
	private static final int GUTTER = 2;
	/** Where the bowler stands, behind the foul line. */
	private static final int APPROACH = 5;
	/** From one lane's middle to the next: the lane, a gutter each side, and a rail between. */
	private static final int PITCH = HALF_WIDTH * 2 + 1 + GUTTER * 2 + 1;
	private static final double PIN_ACROSS = 1.3;
	private static final double PIN_ROW = PIN_ACROSS * 0.866;
	/** Off its spot by this much, a pin is down. */
	private static final double KNOCKED = 0.5;

	/** What a rolling ball keeps of its speed a tick on the lane, and what the game would leave it. */
	private static final double BALL_ROLL = 0.985;
	private static final double PIN_ROLL = 0.93;
	private static final double GAME_ROLL = 0.87;
	private static final double SOFT = 0.5;
	/** How fast a pin's foot has to be knocked for its head to come off it. */
	private static final double TOPPLE = 0.06;
	/** The ball is three pins' weight: it goes on through, as a ball does. */
	private static final double BALL_WEIGHT = 3;
	private static final double RESTITUTION = 0.8;

	private static final long AIM_MILLIS = 45_000L;
	private static final long ROLL_LIMIT_MILLIS = 15_000L;
	private static final int STILL_TICKS = 10;
	private static final double STILL_SPEED = 0.01;


	private enum Phase { AIM, ROLL, DONE }

	private static final class Lane {
		final int team;
		final List<Integer> rolls = new ArrayList<>();
		/** Balls bowled to settle a tie, after the ten frames: pins, one ball a round. */
		final List<Integer> rollOff = new ArrayList<>();
		Phase phase = Phase.AIM;
		@Nullable UUID bowler;
		int turnInTeam;
		long since = System.currentTimeMillis();
		int stillTicks;
		int standingBefore = 10;
		Balls.@Nullable Ball ball;
		final Balls.Ball[] pins = new Balls.Ball[10];
		/** The top of each pin, riding its foot until a knock throws it off. */
		final Balls.Ball[] heads = new Balls.Ball[10];

		Lane(int team) {
			this.team = team;
		}
	}

	private static final class Game {
		final Map<Integer, Lane> lanes = new HashMap<>();
		/** Whether the teams level after ten frames are bowling a ball each to settle it. */
		boolean rollingOff;
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- The alley ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		int teams = Math.max(1, arena.preset.teams);
		var footprint = arena.footprint();
		int cx = (int) Math.floor(footprint.centerX());
		int cz = (int) Math.floor(footprint.centerZ());
		int y = Builds.ground(level, arena, cx, cz);
		int span = PITCH * teams;
		int startX = cx - (APPROACH + LENGTH) / 2 + APPROACH;
		Builds.yard(level, cx, y, cz, Math.max(APPROACH + LENGTH + 4, span + 2) / 2 + 3, 4);
		for (int team = 0; team < teams; team++) {
			BlockPos foul = new BlockPos(startX, y, cz - span / 2 + PITCH / 2 + team * PITCH);
			arena.bases.add(foul);
			build(level, foul, team);
		}
		Game game = new Game();
		games.put(arena.id, game);
		for (int team = 0; team < teams; team++) {
			Lane lane = new Lane(team);
			game.lanes.put(team, lane);
			startFrame(server, level, arena, lane);
		}
	}

	/** One lane: the approach, the boards, a gutter each side, the rails, the pit and its back wall. */
	private static void build(ServerLevel level, BlockPos foul, int team) {
		BlockState boards = Blocks.BIRCH_PLANKS.defaultBlockState();
		BlockState approach = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState rail = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState line = Terrain.block(TeamColors.of(team).concrete(), Blocks.STONE.defaultBlockState());
		BlockState pit = Terrain.block("minecraft:black_concrete", Blocks.COAL_BLOCK.defaultBlockState());
		int edge = HALF_WIDTH + GUTTER + 1;
		for (int along = -APPROACH; along <= LENGTH + 2; along++) {
			for (int across = -edge; across <= edge; across++) {
				BlockPos column = foul.offset(along, 0, across);
				int out = Math.abs(across);
				boolean back = along == LENGTH + 2;
				boolean railed = out == edge && along >= 0;
				if (back || railed) {
					level.setBlock(column, rail, Block.UPDATE_ALL);
					level.setBlock(column.below(), rail, Block.UPDATE_ALL);
					level.setBlock(column.above(), back ? rail : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					continue;
				}
				level.setBlock(column, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(column.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				boolean sunk = along >= 0 && (out > HALF_WIDTH || along >= LENGTH);
				if (sunk) {
					level.setBlock(column.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					level.setBlock(column.below(2), pit, Block.UPDATE_ALL);
				} else {
					BlockState floor = along < 0 ? (along == -1 ? line : approach) : boards;
					level.setBlock(column.below(), floor, Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Whether this block is part of an alley, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.BOWLING) return false;
		int edge = HALF_WIDTH + GUTTER + 1;
		for (BlockPos foul : arena.bases) {
			int along = pos.getX() - foul.getX();
			int across = pos.getZ() - foul.getZ();
			int up = pos.getY() - foul.getY();
			if (along >= -APPROACH && along <= LENGTH + 2 && Math.abs(across) <= edge && up >= -2 && up <= 1) return true;
		}
		return false;
	}

	private static Vec3 start(BlockPos foul) {
		return new Vec3(foul.getX() + 1.5, foul.getY(), foul.getZ() + 0.5);
	}

	/** The ten spots, head pin first, in rows of one, two, three and four. */
	static Vec3 spot(BlockPos foul, int pin) {
		int row = 0;
		while ((row + 1) * (row + 2) / 2 <= pin) row++;
		int inRow = pin - row * (row + 1) / 2;
		return new Vec3(foul.getX() + TO_HEAD_PIN + 0.5 + row * PIN_ROW, foul.getY(), foul.getZ() + 0.5 + (inRow - row / 2.0) * PIN_ACROSS);
	}

	private static ItemStack stack(String block) {
		return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(block)).map(ItemStack::new).orElse(new ItemStack(Items.BONE_BLOCK));
	}

	private static ItemStack ballOf(int team) {
		return stack(TeamColors.of(team).dye() + "_concrete_powder");
	}

	// --- Frames ---

	/**
	 * Where a lane's game is: the frame (0 to 9), the ball in it, the pins standing for it, whether
	 * they were racked fresh for it - all ten down is then a strike, not a spare - and whether the
	 * game is over.
	 */
	public record Frame(int frame, int ball, int standing, boolean fresh, boolean over) {}

	public static Frame frameOf(List<Integer> rolls) {
		int i = 0;
		for (int frame = 0; frame < 9; frame++) {
			if (i >= rolls.size()) return new Frame(frame, 0, 10, true, false);
			if (rolls.get(i) == 10) {
				i++;
				continue;
			}
			if (i + 1 >= rolls.size()) return new Frame(frame, 1, 10 - rolls.get(i), false, false);
			i += 2;
		}
		int balls = rolls.size() - i;
		if (balls == 0) return new Frame(9, 0, 10, true, false);
		int first = rolls.get(i);
		if (balls == 1) return first == 10 ? new Frame(9, 1, 10, true, false) : new Frame(9, 1, 10 - first, false, false);
		int second = rolls.get(i + 1);
		if (balls == 2) {
			if (first != 10 && first + second < 10) return new Frame(9, 2, 0, false, true);
			// A third ball, at a full rack after a spare or two strikes, or at what the second left.
			boolean fresh = first == 10 ? second == 10 : true;
			return new Frame(9, 2, fresh ? 10 : 10 - second, fresh, false);
		}
		return new Frame(9, 3, 0, false, true);
	}

	/** The score so far, as on the sheet: a strike or spare waiting on its next balls counts what it has. */
	public static int score(List<Integer> rolls) {
		int total = 0;
		int i = 0;
		for (int frame = 0; frame < 10 && i < rolls.size(); frame++) {
			if (rolls.get(i) == 10) {
				total += 10 + at(rolls, i + 1) + at(rolls, i + 2);
				i++;
			} else if (at(rolls, i) + at(rolls, i + 1) == 10 && i + 1 < rolls.size()) {
				total += 10 + at(rolls, i + 2);
				i += 2;
			} else {
				total += at(rolls, i) + at(rolls, i + 1);
				i += 2;
			}
		}
		return total;
	}

	private static int at(List<Integer> rolls, int i) {
		return i < rolls.size() ? rolls.get(i) : 0;
	}

	/** A new frame: a full rack, the ball back at the line, and the next of the team up to bowl it. */
	private static void startFrame(MinecraftServer server, ServerLevel level, Arena arena, Lane lane) {
		rack(level, arena, lane);
		lane.bowler = null;
		List<Arena.Member> team = bowlers(arena, lane.team);
		if (!team.isEmpty()) {
			lane.bowler = team.get(Math.floorMod(lane.turnInTeam++, team.size())).id;
		}
		nextBall(server, level, arena, lane);
	}

	/** The ball back at the line for the bowler's next. */
	private static void nextBall(MinecraftServer server, ServerLevel level, Arena arena, Lane lane) {
		BlockPos foul = arena.bases.get(lane.team);
		if (lane.ball == null || !lane.ball.cube.isAlive()) lane.ball = Balls.spawn(level, arena, start(foul), ballOf(lane.team), null);
		else Balls.place(lane.ball, start(foul));
		lane.phase = Phase.AIM;
		lane.since = System.currentTimeMillis();
		lane.standingBefore = standing(arena, lane);
		Arena.Member bowler = lane.bowler == null ? null : arena.member(lane.bowler);
		if (bowler != null) announce(server, arena, lane, bowler);
	}

	private static void rack(ServerLevel level, Arena arena, Lane lane) {
		BlockPos foul = arena.bases.get(lane.team);
		for (int pin = 0; pin < 10; pin++) {
			clear(arena, lane, pin);
			Vec3 at = spot(foul, pin);
			Balls.Ball foot = Balls.spawn(level, arena, at, stack("white_concrete_powder"), null);
			Balls.Ball head = Balls.spawn(level, arena, at.add(0, 1.1, 0), stack("white_concrete_powder"), null);
			lane.pins[pin] = foot;
			lane.heads[pin] = head;
			if (foot != null && head != null) head.cube.startRiding(foot.cube, true, false);
		}
	}

	/** A pin taken off the lane, foot and head. */
	private static void clear(Arena arena, Lane lane, int pin) {
		if (lane.heads[pin] != null) Balls.remove(arena, lane.heads[pin]);
		if (lane.pins[pin] != null) Balls.remove(arena, lane.pins[pin]);
		lane.heads[pin] = null;
		lane.pins[pin] = null;
	}

	private static List<Arena.Member> bowlers(Arena arena, int team) {
		List<Arena.Member> members = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) if (member.team == team) members.add(member);
		members.sort((a, b) -> a.id.compareTo(b.id));
		return members;
	}

	private static void announce(MinecraftServer server, Arena arena, Lane lane, Arena.Member bowler) {
		ServerPlayer player = server.getPlayerList().getPlayer(bowler.id);
		if (player == null) return;
		Frame frame = lane.rollOff.isEmpty() && !games.get(arena.id).rollingOff ? frameOf(lane.rolls) : null;
		String what = frame == null ? "A ball to settle the tie" : "Frame " + (frame.frame() + 1) + ", ball " + (frame.ball() + 1);
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(what + ": hit your ball down the lane; crouch for a soft one")
			.withStyle(ChatFormatting.YELLOW)));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Your bowl")
			.withStyle(style -> style.withColor(TeamColors.of(lane.team).team().textColor()))));
	}

	/** Pins still on their spots. */
	private static int standing(Arena arena, Lane lane) {
		BlockPos foul = arena.bases.get(lane.team);
		int up = 0;
		for (int pin = 0; pin < 10; pin++) if (up(lane, foul, pin)) up++;
		return up;
	}

	/** A pin still standing: its foot on its spot, and its head still on its foot. */
	private static boolean up(Lane lane, BlockPos foul, int pin) {
		Balls.Ball ball = lane.pins[pin];
		Balls.Ball head = lane.heads[pin];
		if (ball == null || !ball.cube.isAlive()) return false;
		if (head == null || !head.cube.isAlive() || head.cube.getVehicle() != ball.cube) return false;
		Vec3 at = ball.cube.position();
		Vec3 home = spot(foul, pin);
		return Math.hypot(at.x - home.x, at.z - home.z) < KNOCKED && at.y > foul.getY() - 0.5;
	}

	/** Whether this pin has been knocked down: the tests' way to see a lane. */
	public static boolean pinDown(Arena arena, int team, int pin) {
		Game game = games.get(arena.id);
		Lane lane = game == null ? null : game.lanes.get(team);
		return lane == null || !up(lane, arena.bases.get(team), pin);
	}

	public static @Nullable SulfurCube ballOf(Arena arena, int team) {
		Game game = games.get(arena.id);
		Lane lane = game == null ? null : game.lanes.get(team);
		return lane == null || lane.ball == null ? null : lane.ball.cube;
	}

	public static List<Integer> rolls(Arena arena, int team) {
		Game game = games.get(arena.id);
		Lane lane = game == null ? null : game.lanes.get(team);
		return lane == null ? List.of() : List.copyOf(lane.rolls);
	}

	// --- Bowling the ball ---

	private static @Nullable Lane laneOf(Arena arena, SulfurCube cube) {
		Game game = games.get(arena.id);
		if (game == null) return null;
		for (Lane lane : game.lanes.values()) {
			if (lane.ball != null && lane.ball.cube == cube) return lane;
			for (Balls.Ball pin : lane.pins) if (pin != null && pin.cube == cube) return lane;
			for (Balls.Ball head : lane.heads) if (head != null && head.cube == cube) return lane;
		}
		return null;
	}

	private static @Nullable Arena arenaOf(SulfurCube cube) {
		if (!cube.entityTags().contains(Balls.TAG) || !(cube.level() instanceof ServerLevel level)) return null;
		Arena arena = Arenas.at(level, cube.getX(), cube.getZ());
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.preset.activeGoal() != Preset.Goal.BOWLING) return null;
		return arena;
	}

	/** Only a lane's ball takes a hit, only from whoever's frame it is, and only before it is bowled. */
	static boolean mayStrike(SulfurCube cube, @Nullable Entity by) {
		Arena arena = arenaOf(cube);
		if (arena == null) return true;
		Lane lane = laneOf(arena, cube);
		if (lane == null || lane.phase != Phase.AIM || lane.ball == null || lane.ball.cube != cube) return false;
		if (by instanceof ServerPlayer player && !player.getUUID().equals(lane.bowler)) {
			Arena.Member bowler = lane.bowler == null ? null : arena.member(lane.bowler);
			Say.bar(player, bowler == null ? "Nobody's up on this lane" : "It's " + bowler.name + "'s frame on this lane");
			return false;
		}
		return by instanceof ServerPlayer;
	}

	/** The ball has been bowled: kept flat on the lane, gentler if crouching, and rolling. */
	static void struck(SulfurCube cube, @Nullable Entity by) {
		Arena arena = arenaOf(cube);
		if (arena == null || !(by instanceof ServerPlayer player)) return;
		Lane lane = laneOf(arena, cube);
		if (lane == null || lane.phase != Phase.AIM || lane.ball == null || lane.ball.cube != cube || !player.getUUID().equals(lane.bowler)) return;
		Vec3 going = cube.getDeltaMovement();
		double scale = player.isShiftKeyDown() ? SOFT : 1;
		cube.setDeltaMovement(going.x * scale, 0, going.z * scale);
		cube.needsSync = true;
		lane.phase = Phase.ROLL;
		lane.since = System.currentTimeMillis();
		lane.stillTicks = 0;
	}

	/** A ball bowled for the tests, as a player's hit would send it. */
	public static void bowl(Arena arena, int team, Vec3 velocity) {
		Game game = games.get(arena.id);
		Lane lane = game == null ? null : game.lanes.get(team);
		if (lane == null || lane.ball == null || lane.phase != Phase.AIM) return;
		lane.ball.cube.setDeltaMovement(velocity);
		lane.ball.cube.needsSync = true;
		lane.phase = Phase.ROLL;
		lane.since = System.currentTimeMillis();
		lane.stillTicks = 0;
	}

	// --- Every tick ---

	static void physics(MinecraftServer server, ServerLevel level, Arena arena, List<Balls.Ball> all) {
		Game game = games.get(arena.id);
		if (game == null) return;
		for (Lane lane : game.lanes.values()) {
			List<Balls.Ball> balls = new ArrayList<>();
			if (lane.ball != null && lane.ball.cube.isAlive()) balls.add(lane.ball);
			for (Balls.Ball pin : lane.pins) if (pin != null && pin.cube.isAlive()) balls.add(pin);
			// A head thrown off is loose on the lane, and knocks like any pin.
			for (Balls.Ball head : lane.heads) if (head != null && head.cube.isAlive() && !head.cube.isPassenger()) balls.add(head);
			for (Balls.Ball ball : balls) roll(ball, ball == lane.ball ? BALL_ROLL : PIN_ROLL);
			Balls.knock(balls, RESTITUTION, ball -> ball == lane.ball ? BALL_WEIGHT : 1);
			topple(level, lane);
			if (lane.phase == Phase.ROLL) settle(server, level, arena, game, lane, balls);
		}
	}

	/**
	 * A pin whose foot has been knocked loses its head: thrown off ahead of the knock and up, so
	 * the pin goes over the way a pin does, instead of a cube sliding along the boards upright.
	 */
	private static void topple(ServerLevel level, Lane lane) {
		for (int pin = 0; pin < 10; pin++) {
			Balls.Ball foot = lane.pins[pin];
			Balls.Ball head = lane.heads[pin];
			if (foot == null || head == null || head.cube.getVehicle() != foot.cube) continue;
			Vec3 going = foot.cube.getDeltaMovement();
			if (going.x * going.x + going.z * going.z < TOPPLE * TOPPLE) continue;
			head.cube.stopRiding();
			head.cube.setDeltaMovement(going.x * 1.4, 0.3, going.z * 1.4);
			head.cube.needsSync = true;
			level.playSound(null, foot.cube.getX(), foot.cube.getY(), foot.cube.getZ(), SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 1.0F, 1.4F);
		}
	}

	private static void roll(Balls.Ball ball, double keep) {
		Vec3 going = ball.cube.getDeltaMovement();
		if (!ball.cube.onGround() || going.x * going.x + going.z * going.z < STILL_SPEED * STILL_SPEED * 4) return;
		double scale = keep / GAME_ROLL;
		ball.cube.setDeltaMovement(going.x * scale, going.y, going.z * scale);
		ball.cube.needsSync = true;
	}

	private static void settle(MinecraftServer server, ServerLevel level, Arena arena, Game game, Lane lane, List<Balls.Ball> balls) {
		BlockPos foul = arena.bases.get(lane.team);
		boolean still = true;
		for (Balls.Ball ball : balls) {
			// A ball in the gutter or the pit is out of it, rolling or not.
			if (ball.cube.getY() < foul.getY() - 0.5) continue;
			Vec3 going = ball.cube.getDeltaMovement();
			if (going.x * going.x + going.z * going.z > STILL_SPEED * STILL_SPEED || !ball.cube.onGround()) {
				still = false;
				break;
			}
		}
		lane.stillTicks = still ? lane.stillTicks + 1 : 0;
		if (lane.stillTicks < STILL_TICKS && System.currentTimeMillis() - lane.since < ROLL_LIMIT_MILLIS) return;
		resolve(server, level, arena, game, lane, lane.standingBefore - standing(arena, lane));
	}

	/** A ball is done with: the pins it took counted, the fallen ones cleared, and on to the next ball or frame. */
	private static void resolve(MinecraftServer server, ServerLevel level, Arena arena, Game game, Lane lane, int down) {
		BlockPos foul = arena.bases.get(lane.team);
		for (int pin = 0; pin < 10; pin++) {
			if (lane.pins[pin] != null && !up(lane, foul, pin)) clear(arena, lane, pin);
		}
		Arena.Member bowler = lane.bowler == null ? null : arena.member(lane.bowler);
		String who = bowler == null ? TeamColors.of(lane.team).name() : bowler.name;
		Vec3 deck = spot(foul, 0);
		if (game.rollingOff) {
			lane.rollOff.add(down);
			lane.phase = Phase.DONE;
			Arenas.tellInside(server, arena, who + " knocks down " + pins(down) + " to settle it");
			rollOffDone(server, level, arena, game);
			return;
		}
		Frame was = frameOf(lane.rolls);
		lane.rolls.add(down);
		arena.scores.put(lane.team, score(lane.rolls));
		Arenas.vault(server).touch();
		boolean strike = was.fresh() && down == 10;
		boolean spare = !was.fresh() && down == was.standing() && down > 0;
		if (strike || spare) {
			level.playSound(null, deck.x, deck.y, deck.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 2.0F, 1.0F);
			Boards.title(server, arena, lane.team, strike ? "Strike!" : "Spare!", who);
		}
		Arenas.tellInside(server, arena, who + (strike ? " bowls a strike!" : spare ? " picks up the spare!" : down == 0 ? " misses everything" : " knocks down " + pins(down))
			+ " · " + TeamColors.of(lane.team).name() + " " + score(lane.rolls));
		Frame now = frameOf(lane.rolls);
		if (now.over()) {
			lane.phase = Phase.DONE;
			if (lane.ball != null) Balls.remove(arena, lane.ball);
			lane.ball = null;
			for (int pin = 0; pin < 10; pin++) clear(arena, lane, pin);
			Arenas.tellInside(server, arena, TeamColors.of(lane.team).name() + " has bowled its ten frames, for " + score(lane.rolls));
			allDone(server, level, arena, game);
			return;
		}
		if (now.frame() != was.frame()) {
			startFrame(server, level, arena, lane);
			return;
		}
		if (now.fresh()) rack(level, arena, lane);
		nextBall(server, level, arena, lane);
	}

	private static String pins(int count) {
		return count + (count == 1 ? " pin" : " pins");
	}

	// --- The end ---

	/** Every lane with anybody on it is done: the highest score wins, and a tie bowls it off. */
	private static void allDone(MinecraftServer server, ServerLevel level, Arena arena, Game game) {
		for (Lane lane : game.lanes.values()) if (lane.phase != Phase.DONE && Turns.present(arena, lane.team)) return;
		List<Lane> best = leaders(game, lane -> score(lane.rolls));
		if (best.size() == 1) {
			Goals.winTeam(server, arena, best.get(0).team, "bowled " + score(best.get(0).rolls));
			return;
		}
		game.rollingOff = true;
		Arenas.tellInside(server, arena, "Level on " + score(best.get(0).rolls) + ": one ball each at a full rack to settle it");
		for (Lane lane : best) {
			lane.rollOff.clear();
			startFrame(server, level, arena, lane);
		}
	}

	private static void rollOffDone(MinecraftServer server, ServerLevel level, Arena arena, Game game) {
		List<Lane> rolling = new ArrayList<>();
		for (Lane lane : game.lanes.values()) if (lane.rollOff.size() > 0 || lane.phase != Phase.DONE) rolling.add(lane);
		for (Lane lane : rolling) if (lane.phase != Phase.DONE && Turns.present(arena, lane.team)) return;
		Game only = new Game();
		for (Lane lane : rolling) only.lanes.put(lane.team, lane);
		List<Lane> best = leaders(only, lane -> lane.rollOff.isEmpty() ? -1 : lane.rollOff.get(lane.rollOff.size() - 1));
		if (best.size() == 1) {
			Goals.winTeam(server, arena, best.get(0).team, "bowled " + score(best.get(0).rolls) + " and won the bowl-off");
			return;
		}
		for (Lane lane : game.lanes.values()) if (!best.contains(lane)) lane.rollOff.clear();
		Arenas.tellInside(server, arena, "Level again: another ball each");
		for (Lane lane : best) startFrame(server, level, arena, lane);
	}

	private static List<Lane> leaders(Game game, java.util.function.ToIntFunction<Lane> by) {
		List<Lane> best = new ArrayList<>();
		int top = Integer.MIN_VALUE;
		for (Lane lane : game.lanes.values()) {
			int value = by.applyAsInt(lane);
			if (value > top) {
				best.clear();
				top = value;
			}
			if (value == top) best.add(lane);
		}
		return best;
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: the sheets were never written down. The lanes are swept and it starts again.
			if (arena.bases.size() < arena.preset.teams) return;
			for (Balls.Ball ball : List.copyOf(Balls.of(level, arena))) Balls.remove(arena, ball);
			arena.scores.clear();
			begin(server, level, arena);
			Arenas.tellInside(server, arena, "The score sheets blew away; everyone starts again from the first frame");
			return;
		}
		for (Lane lane : game.lanes.values()) {
			if (lane.phase != Phase.AIM) continue;
			Arena.Member bowler = lane.bowler == null ? null : arena.member(lane.bowler);
			boolean gone = bowler == null || !bowler.inside || bowler.watching || bowler.out;
			if (gone) {
				List<Arena.Member> team = bowlers(arena, lane.team);
				if (team.isEmpty()) continue;
				lane.bowler = team.get(Math.floorMod(lane.turnInTeam++, team.size())).id;
				nextBall(server, level, arena, lane);
			} else if (now - lane.since >= AIM_MILLIS) {
				Arenas.tellInside(server, arena, bowler.name + " took too long: a ball bowled for nothing");
				resolve(server, level, arena, game, lane, 0);
			}
		}
	}

	// --- Words ---

	/** The bar's line: the viewer's lane, its frame and its score. */
	static String status(Arena arena, Arena.@Nullable Member viewer) {
		Game game = games.get(arena.id);
		if (game == null) return "Setting up the pins";
		Lane lane = viewer == null ? null : game.lanes.get(viewer.team);
		if (lane == null) return Banks.tally(arena);
		return frameWords(arena, lane) + "  ·  " + score(lane.rolls) + "  ·  " + Banks.tally(arena);
	}

	static String frameWords(Arena arena, Lane lane) {
		if (lane.phase == Phase.DONE) return "Done";
		Game game = games.get(arena.id);
		if (game != null && game.rollingOff) return "Bowl-off";
		Frame frame = frameOf(lane.rolls);
		Arena.Member bowler = lane.bowler == null ? null : arena.member(lane.bowler);
		return "Frame " + (frame.frame() + 1) + (bowler == null ? "" : ", " + bowler.name + " up");
	}

	/** For the foot of the board: who is up on the viewer's lane. */
	static String upWords(Arena arena, Arena.@Nullable Member viewer) {
		Game game = games.get(arena.id);
		Lane lane = game == null || viewer == null ? null : game.lanes.get(viewer.team);
		return lane == null ? "" : frameWords(arena, lane);
	}

	/** For the board: this team's frame, or that it is done. */
	static String laneWords(Arena arena, int team) {
		Game game = games.get(arena.id);
		Lane lane = game == null ? null : game.lanes.get(team);
		if (lane == null) return "";
		if (lane.phase == Phase.DONE) return "done";
		return "frame " + (frameOf(lane.rolls).frame() + 1);
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
