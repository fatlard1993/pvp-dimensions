package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Pool: a table in the middle, a cue ball, a set of balls in each team's colour and the 8-ball.
 * The teams take turns at the cue ball, one shooter at a time; clear your colour, then sink the
 * 8-ball to win.
 *
 * <p>Every ball holds a block of the one feel, so they all roll alike and only look different: the
 * cue ball bone, the 8-ball coal, and each team's balls its colour of concrete powder. Only the
 * cue ball takes a hit, and only from whoever's shot it is; a full swing is a hard shot and a
 * crouched one a soft one. The cube does not know other cubes are there, so the balls knock into
 * each other here, every tick.
 *
 * <p>A shot lasts until every ball has stopped. Pot one of yours and you shoot again. Pot the cue
 * ball and it comes back to the head of the table for the next side. Pot the 8-ball before your
 * colour is cleared and your side has lost: with two sides, the other wins it.
 *
 * <p>The table is {@link Arena#bases}: its first corner inside the cushions, at the height the
 * balls roll at. The balls down for each team are {@link Arena#scores}. Whose turn it is lives here
 * and starts over after a restart, with the first side to shoot.
 */
public final class Pool {
	private Pool() {}

	/** The playing surface inside the cushions: twice as long as it is wide. */
	private static final int LENGTH = 24;
	private static final int WIDTH = 12;
	/**
	 * Each pocket is a pit two across, so a ball a little over a block wide drops into it, and one
	 * deep, so a player who steps in can step out again.
	 */
	private static final int POCKET = 2;
	/** How long a shooter has to take a shot before the turn passes. */
	private static final long SHOT_CLOCK_MILLIS = 60_000L;
	/** How long a shot may roll before it is called over regardless: a ball trapped rocking in a corner. */
	private static final long ROLL_LIMIT_MILLIS = 20_000L;
	/** Ticks every ball must be still for the shot to be over. */
	private static final int STILL_TICKS = 10;
	private static final double STILL_SPEED = 0.01;
	/** What is left of a shot struck crouching. */
	private static final double SOFT = 0.4;
	/**
	 * What a rolling ball keeps of its speed from one tick to the next on the cloth, and what the
	 * game leaves it: a cube holding a block of this feel, on wool, keeps about 0.87, measured. Left
	 * at that, a full shot dies in four blocks; a pool table wants a ball that runs.
	 */
	private static final double CLOTH_ROLL = 0.97;
	private static final double GAME_ROLL = 0.87;
	/**
	 * How much of their closing speed two balls keep: all of it, so a ball struck full stops dead
	 * and the other runs on, as a stun shot does. A little less, and the cloth's fast roll turns the
	 * sliver left over into a white that creeps on after the ball it hit.
	 */
	private static final double RESTITUTION = 1.0;

	private static final ItemStack CUE = new ItemStack(Items.BONE_BLOCK);
	private static final ItemStack EIGHT = new ItemStack(Items.COAL_BLOCK);
	/** A ball's kind: the cue ball, the 8-ball, or a team's (0 and up). */
	private static final int CUE_BALL = -1;
	private static final int EIGHT_BALL = -2;

	private enum Phase { AIM, ROLL }

	/** A game in progress, by arena id. */
	private static final class Play {
		int side;
		@Nullable UUID shooter;
		Phase phase = Phase.AIM;
		long since = System.currentTimeMillis();
		int stillTicks;
		/** Whether the side shooting had cleared its colour when the shot was struck. */
		boolean clearedAtShot;
		final List<Integer> pocketed = new ArrayList<>();
		final List<Integer> offTable = new ArrayList<>();
		/** Sides out of it, for sinking the 8-ball early where more than two play. */
		final Set<Integer> out = new HashSet<>();
		/** The next of each team to shoot, by their place in the team. */
		final Map<Integer, Integer> turnInTeam = new HashMap<>();
	}

	private static final Map<String, Play> plays = new HashMap<>();

	// --- The table ---

	/** The first corner of the cloth inside the cushions, at the height the balls roll at. */
	private static @Nullable BlockPos corner(Arena arena) {
		return arena.bases.isEmpty() ? null : arena.bases.get(0);
	}

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		Vec3 middle = Balls.middle(level, arena);
		int cx = (int) Math.floor(middle.x);
		int cz = (int) Math.floor(middle.z);
		int y = Builds.ground(level, arena, cx, cz);
		BlockPos corner = new BlockPos(cx - LENGTH / 2, y, cz - WIDTH / 2);
		arena.bases.add(corner);
		build(level, corner);
		rack(level, arena, corner);
		Play play = new Play();
		play.side = 0;
		plays.put(arena.id, play);
		nextShooter(server, arena, play, true);
	}

	/** Whether this column of the cloth is a pocket, by its place on the table. */
	private static boolean pocket(int along, int across) {
		boolean end = along < POCKET || along >= LENGTH - POCKET;
		boolean middle = along >= LENGTH / 2 - POCKET / 2 && along < LENGTH / 2 + POCKET / 2;
		boolean side = across < POCKET || across >= WIDTH - POCKET;
		return side && (end || middle);
	}

	private static void build(ServerLevel level, BlockPos corner) {
		BlockState cloth = Terrain.block("minecraft:green_wool", Blocks.MOSS_BLOCK.defaultBlockState());
		BlockState rail = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState pit = Terrain.block("minecraft:black_concrete", Blocks.COAL_BLOCK.defaultBlockState());
		Builds.yard(level, corner.getX() + LENGTH / 2, corner.getY(), corner.getZ() + WIDTH / 2, LENGTH / 2 + 3, 4);
		for (int along = -1; along <= LENGTH; along++) {
			for (int across = -1; across <= WIDTH; across++) {
				BlockPos column = corner.offset(along, 0, across);
				boolean cushion = along < 0 || along >= LENGTH || across < 0 || across >= WIDTH;
				if (cushion) {
					level.setBlock(column, rail, Block.UPDATE_ALL);
					level.setBlock(column.below(), rail, Block.UPDATE_ALL);
				} else if (pocket(along, across)) {
					level.setBlock(column.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					level.setBlock(column.below(2), pit, Block.UPDATE_ALL);
				} else {
					level.setBlock(column.below(), cloth, Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Whether this block is part of the table, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.POOL) return false;
		BlockPos corner = corner(arena);
		if (corner == null) return false;
		int along = pos.getX() - corner.getX();
		int across = pos.getZ() - corner.getZ();
		int up = pos.getY() - corner.getY();
		return along >= -1 && along <= LENGTH && across >= -1 && across <= WIDTH && up >= -2 && up <= 0;
	}

	/** Where the cue ball is set: a quarter of the way along, in the middle. */
	private static Vec3 head(BlockPos corner) {
		return new Vec3(corner.getX() + LENGTH / 4.0, corner.getY(), corner.getZ() + WIDTH / 2.0);
	}

	/** Where the rack's front ball sits, and where a ball off the table comes back to. */
	private static Vec3 foot(BlockPos corner) {
		return new Vec3(corner.getX() + LENGTH * 3 / 4.0, corner.getY(), corner.getZ() + WIDTH / 2.0);
	}

	/** How many balls each team has: seven between two, fewer the more there are. */
	static int perTeam(Arena arena) {
		return Math.max(2, 14 / Math.max(2, arena.preset.teams));
	}

	/**
	 * The balls in a triangle behind the foot spot, the 8-ball in the middle of the third row as
	 * it is racked at a table, the teams' colours shuffled through the rest.
	 */
	private static void rack(ServerLevel level, Arena arena, BlockPos corner) {
		List<Integer> kinds = new ArrayList<>();
		for (int team = 0; team < arena.preset.teams; team++) {
			for (int i = 0; i < perTeam(arena); i++) kinds.add(team);
		}
		Collections.shuffle(kinds);
		int eightAt = Math.min(4, kinds.size());
		kinds.add(eightAt, EIGHT_BALL);

		Vec3 foot = foot(corner);
		Balls.Ball cue = Balls.spawn(level, arena, head(corner), CUE.copy(), null);
		double gap = (cue == null ? 1.04 : cue.cube.getBbWidth()) + 0.06;
		int placed = 0;
		for (int row = 0; placed < kinds.size(); row++) {
			for (int i = 0; i <= row && placed < kinds.size(); i++, placed++) {
				double along = foot.x + row * gap * 0.866;
				double across = foot.z + (i - row / 2.0) * gap;
				Balls.spawn(level, arena, new Vec3(along, foot.y, across), stackOf(kinds.get(placed)), null);
			}
		}
	}

	private static ItemStack stackOf(int kind) {
		if (kind == CUE_BALL) return CUE.copy();
		if (kind == EIGHT_BALL) return EIGHT.copy();
		Identifier id = Identifier.withDefaultNamespace(TeamColors.of(kind).dye() + "_concrete_powder");
		return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(new ItemStack(Items.COARSE_DIRT));
	}

	/** Which ball this is, by the block it holds; read back the same after a restart. */
	private static int kind(Arena arena, SulfurCube cube) {
		ItemStack held = cube.getItemBySlot(EquipmentSlot.BODY);
		if (held.is(Items.BONE_BLOCK)) return CUE_BALL;
		if (held.is(Items.COAL_BLOCK)) return EIGHT_BALL;
		for (int team = 0; team < arena.preset.teams; team++) if (held.is(stackOf(team).getItem())) return team;
		return EIGHT_BALL;
	}

	// --- Whose shot ---

	/** The members a side shoots with, in a steady order. */
	private static List<Arena.Member> shooters(Arena arena, int side) {
		List<Arena.Member> team = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) if (member.team == side) team.add(member);
		team.sort((a, b) -> a.id.compareTo(b.id));
		return team;
	}

	/**
	 * The turn to the next side with anyone to shoot, or to the same side again, and the next of
	 * them at the cue. Where nobody at all is left to shoot, nobody is.
	 */
	private static void nextShooter(MinecraftServer server, Arena arena, Play play, boolean sameSide) {
		int teams = arena.preset.teams;
		for (int step = sameSide ? 0 : 1; step <= teams; step++) {
			int side = Math.floorMod(play.side + step, teams);
			if (play.out.contains(side)) continue;
			List<Arena.Member> team = shooters(arena, side);
			if (team.isEmpty()) continue;
			int turn = play.turnInTeam.getOrDefault(side, 0);
			Arena.Member next = team.get(Math.floorMod(turn, team.size()));
			play.turnInTeam.put(side, turn + 1);
			play.side = side;
			play.shooter = next.id;
			play.phase = Phase.AIM;
			play.since = System.currentTimeMillis();
			announce(server, arena, next, side);
			return;
		}
		play.shooter = null;
	}

	private static void announce(MinecraftServer server, Arena arena, Arena.Member shooter, int side) {
		String colour = TeamColors.of(side).name();
		Arenas.tellInside(server, arena, shooter.name + " to shoot, for " + colour
			+ (cleared(arena, side) ? ": on the 8-ball" : ""));
		ServerPlayer player = server.getPlayerList().getPlayer(shooter.id);
		if (player == null) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(
			cleared(arena, side) ? "Sink the 8-ball" : "Hit the white ball; crouch for a soft shot").withStyle(ChatFormatting.YELLOW)));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Your shot")
			.withStyle(style -> style.withColor(TeamColors.of(side).team().textColor()))));
	}

	private static boolean cleared(Arena arena, int side) {
		return arena.scores.getOrDefault(side, 0) >= perTeam(arena);
	}

	// --- Striking ---

	/**
	 * Whether this hit on a ball is let through: on a pool table, only the cue ball, only by
	 * whoever's shot it is, and only while the balls are still. Any other ball is left alone.
	 */
	public static boolean mayStrike(SulfurCube cube, @Nullable Entity by) {
		Arena arena = arenaOf(cube);
		if (arena == null) return true;
		Play play = plays.get(arena.id);
		if (play == null || play.phase != Phase.AIM || kind(arena, cube) != CUE_BALL) return false;
		return by instanceof ServerPlayer player && player.getUUID().equals(play.shooter);
	}

	/** The cue ball has been struck: kept flat on the cloth, gentler if crouching, and the shot is on. */
	public static void struck(SulfurCube cube, @Nullable Entity by) {
		Arena arena = arenaOf(cube);
		if (arena == null || !(by instanceof ServerPlayer player)) return;
		Play play = plays.get(arena.id);
		if (play == null || play.phase != Phase.AIM || !player.getUUID().equals(play.shooter)) return;
		Vec3 going = cube.getDeltaMovement();
		double scale = player.isShiftKeyDown() ? SOFT : 1;
		cube.setDeltaMovement(going.x * scale, 0, going.z * scale);
		cube.needsSync = true;
		play.phase = Phase.ROLL;
		play.since = System.currentTimeMillis();
		play.stillTicks = 0;
		play.clearedAtShot = cleared(arena, play.side);
		play.pocketed.clear();
		play.offTable.clear();
	}

	/** The arena whose pool table this ball is on, if it is a pool ball in a live game. */
	private static @Nullable Arena arenaOf(SulfurCube cube) {
		if (!cube.entityTags().contains(Balls.TAG) || !(cube.level() instanceof ServerLevel level)) return null;
		Arena arena = Arenas.at(level, cube.getX(), cube.getZ());
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.preset.activeGoal() != Preset.Goal.POOL) return null;
		return arena;
	}

	// --- Every tick: the balls against each other, the pockets, and the end of a shot ---

	public static void physics(MinecraftServer server) {
		if (plays.isEmpty()) return;
		for (Arena arena : List.copyOf(Arenas.vault(server).arenas().values())) {
			Play play = plays.get(arena.id);
			if (play == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0) continue;
			ServerLevel level = Arenas.level(server, arena);
			BlockPos corner = corner(arena);
			if (level == null || corner == null) continue;
			List<Balls.Ball> balls = Balls.of(level, arena);
			roll(balls);
			collide(balls);
			drop(level, arena, play, corner, balls);
			if (play.phase == Phase.ROLL) settle(server, level, arena, play, corner, balls);
		}
	}

	/**
	 * Each ball still rolling given back what the cloth would not have taken. One nearly stopped is
	 * left to the game, so it comes to rest.
	 */
	private static void roll(List<Balls.Ball> balls) {
		for (Balls.Ball ball : balls) {
			Vec3 going = ball.cube.getDeltaMovement();
			if (!ball.cube.onGround() || going.x * going.x + going.z * going.z < STILL_SPEED * STILL_SPEED * 4) continue;
			double keep = CLOTH_ROLL / GAME_ROLL;
			ball.cube.setDeltaMovement(going.x * keep, going.y, going.z * keep);
			ball.cube.needsSync = true;
		}
	}

	/**
	 * Two balls that meet trade what they had along the line between their middles, as balls of
	 * one weight do, and are set apart where they had sunk into each other.
	 */
	private static void collide(List<Balls.Ball> balls) {
		for (int i = 0; i < balls.size(); i++) {
			SulfurCube a = balls.get(i).cube;
			for (int j = i + 1; j < balls.size(); j++) {
				SulfurCube b = balls.get(j).cube;
				double reach = (a.getBbWidth() + b.getBbWidth()) / 2;
				double dx = b.getX() - a.getX();
				double dz = b.getZ() - a.getZ();
				double apart = Math.sqrt(dx * dx + dz * dz);
				if (apart >= reach || apart < 1.0E-4 || Math.abs(b.getY() - a.getY()) > 0.5) continue;
				double nx = dx / apart;
				double nz = dz / apart;
				Vec3 va = a.getDeltaMovement();
				Vec3 vb = b.getDeltaMovement();
				double closing = (va.x - vb.x) * nx + (va.z - vb.z) * nz;
				if (closing > 0) {
					double impulse = closing * (1 + RESTITUTION) / 2;
					a.setDeltaMovement(va.x - impulse * nx, va.y, va.z - impulse * nz);
					b.setDeltaMovement(vb.x + impulse * nx, vb.y, vb.z + impulse * nz);
					a.needsSync = true;
					b.needsSync = true;
					if (closing > 0.05) a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.STONE_HIT, SoundSource.PLAYERS, 0.6F, 1.6F);
				}
				double push = (reach - apart) / 2;
				a.setPos(a.getX() - nx * push, a.getY(), a.getZ() - nz * push);
				b.setPos(b.getX() + nx * push, b.getY(), b.getZ() + nz * push);
			}
		}
	}

	/** Balls down a pocket, or off the table altogether, are taken off it and remembered for the shot. */
	private static void drop(ServerLevel level, Arena arena, Play play, BlockPos corner, List<Balls.Ball> balls) {
		for (Balls.Ball ball : List.copyOf(balls)) {
			Vec3 at = ball.cube.position();
			boolean down = at.y < corner.getY() - 0.5;
			boolean off = at.x < corner.getX() - 1 || at.x > corner.getX() + LENGTH + 1
				|| at.z < corner.getZ() - 1 || at.z > corner.getZ() + WIDTH + 1;
			if (!down && !off) continue;
			int kind = kind(arena, ball.cube);
			Balls.remove(arena, ball);
			if (down && !off) {
				play.pocketed.add(kind);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOD_FALL, SoundSource.PLAYERS, 1.0F, 0.8F);
			} else {
				play.offTable.add(kind);
			}
		}
	}

	private static void settle(MinecraftServer server, ServerLevel level, Arena arena, Play play, BlockPos corner, List<Balls.Ball> balls) {
		boolean still = true;
		for (Balls.Ball ball : balls) {
			Vec3 going = ball.cube.getDeltaMovement();
			if (going.x * going.x + going.z * going.z > STILL_SPEED * STILL_SPEED || !ball.cube.onGround()) {
				still = false;
				break;
			}
		}
		play.stillTicks = still ? play.stillTicks + 1 : 0;
		if (play.stillTicks < STILL_TICKS && System.currentTimeMillis() - play.since < ROLL_LIMIT_MILLIS) return;
		resolve(server, level, arena, play, corner);
	}

	/** The shot is over: count what went down, and say whose shot it is now. */
	private static void resolve(MinecraftServer server, ServerLevel level, Arena arena, Play play, BlockPos corner) {
		int side = play.side;
		int own = 0;
		boolean scratch = play.offTable.contains(CUE_BALL) || play.pocketed.contains(CUE_BALL);
		boolean eight = play.pocketed.contains(EIGHT_BALL) || play.offTable.contains(EIGHT_BALL);
		for (int kind : play.pocketed) {
			if (kind < 0) continue;
			arena.scores.merge(kind, 1, Integer::sum);
			if (kind == side) own++;
		}
		Arenas.vault(server).touch();
		for (int kind : play.offTable) if (kind >= 0) Balls.spawn(level, arena, clear(level, arena, foot(corner)), stackOf(kind), null);

		if (eight) {
			if (play.clearedAtShot && !scratch) {
				Goals.winTeam(server, arena, side, "sank the 8-ball");
				return;
			}
			String how = scratch ? "sank the 8-ball and the white" : "sank the 8-ball before clearing their colour";
			play.out.add(side);
			List<Integer> left = new ArrayList<>();
			for (int team = 0; team < arena.preset.teams; team++) if (!play.out.contains(team)) left.add(team);
			if (left.size() == 1) {
				Goals.winTeam(server, arena, left.get(0), TeamColors.of(side).name() + " " + how);
				return;
			}
			Arenas.tellInside(server, arena, TeamColors.of(side).name() + " " + how + ", and is out");
			Balls.spawn(level, arena, clear(level, arena, foot(corner)), EIGHT.copy(), null);
		}
		if (scratch) {
			Balls.spawn(level, arena, clear(level, arena, head(corner)), CUE.copy(), null);
			Arenas.tellInside(server, arena, "The white went down: it's back at the head of the table");
		}
		if (!play.clearedAtShot && cleared(arena, side) && !eight) {
			Arenas.tellInside(server, arena, TeamColors.of(side).name() + " has cleared its colour: the 8-ball next");
		}
		boolean again = own > 0 && !scratch && !eight;
		if (again) {
			// The same shooter goes again, as at any table.
			play.phase = Phase.AIM;
			play.since = System.currentTimeMillis();
			ServerPlayer player = play.shooter == null ? null : server.getPlayerList().getPlayer(play.shooter);
			if (player != null) {
				Arena.Member member = arena.member(play.shooter);
				if (member != null) announce(server, arena, member, side);
				return;
			}
		}
		nextShooter(server, arena, play, false);
	}

	/** A spot at or beside this one with no ball on it. */
	private static Vec3 clear(ServerLevel level, Arena arena, Vec3 spot) {
		List<Balls.Ball> balls = Balls.of(level, arena);
		for (int ring = 0; ring < 6; ring++) {
			for (int step = 0; step < Math.max(1, ring * 6); step++) {
				double angle = Math.PI * 2 * step / Math.max(1, ring * 6);
				Vec3 at = spot.add(Math.cos(angle) * ring * 1.2, 0, Math.sin(angle) * ring * 1.2);
				boolean taken = false;
				for (Balls.Ball ball : balls) if (ball.cube.position().distanceTo(at) < 1.2) taken = true;
				if (!taken) return at;
			}
		}
		return spot;
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Play play = plays.get(arena.id);
		if (play == null) {
			// A game carried over a restart: the table stands and the balls are where they were; the
			// turn starts again with the first side.
			if (corner(arena) == null) return;
			play = new Play();
			plays.put(arena.id, play);
			nextShooter(server, arena, play, true);
			return;
		}
		if (play.phase != Phase.AIM) return;
		Arena.Member shooter = play.shooter == null ? null : arena.member(play.shooter);
		boolean gone = shooter == null || !shooter.inside || shooter.watching || shooter.out;
		if (gone) {
			nextShooter(server, arena, play, true);
		} else if (now - play.since >= SHOT_CLOCK_MILLIS) {
			Arenas.tellInside(server, arena, shooter.name + " ran out of time; the turn passes");
			nextShooter(server, arena, play, false);
		}
	}

	// --- Words ---

	/** The bar's line: each side's balls down, and whose shot it is. */
	static String status(MinecraftServer server, Arena arena) {
		return Banks.tally(arena) + " of " + perTeam(arena) + "  ·  " + whoseShot(server, arena);
	}

	static String whoseShot(MinecraftServer server, Arena arena) {
		Play play = plays.get(arena.id);
		if (play == null) return "Racking up";
		if (play.phase == Phase.ROLL) return "Balls rolling";
		Arena.Member shooter = play.shooter == null ? null : arena.member(play.shooter);
		if (shooter == null) return "Nobody to shoot";
		return shooter.name + " to shoot" + (cleared(arena, play.side) ? ", on the 8" : "");
	}

	public static void forget(Arena arena) {
		plays.remove(arena.id);
	}
}
