package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Soccer, and the games that are soccer with other goals: a ball, a goal at each team's end, and
 * the ball back in the middle after every goal.
 *
 * <p>A goal is one of two shapes. A net on the ground, five wide and three high; or a line across
 * the ground, which counts the ball pushed anywhere past it: with a heavy ball, a shoving match. Each is measured from {@link Arena#bases}, one spot per team on its own spoke from the
 * middle, and faces the middle.
 *
 * <p>A goal counts for whoever touched the ball last, if they are not the side whose goal it went
 * in. With two teams an own goal still counts, for the other side; with more there is no other
 * side to give it to, so it counts for nobody.
 */
public final class Soccer {
	private Soccer() {}

	/** Columns either side of a net's middle: a mouth five wide. */
	private static final int HALF = 2;
	private static final int HEIGHT = 3;
	/** Rows from the goal line to the back of the net. */
	private static final int DEPTH = 3;
	/** Blocks kept between the back of a goal and the arena's edge. */
	private static final int BEHIND = 9;

	static void begin(ServerLevel level, Arena arena) {
		arena.bases.clear();
		for (int team = 0; team < arena.preset.teams; team++) arena.bases.add(goalSpot(level, arena, team));
		for (int team = 0; team < arena.preset.teams; team++) build(level, arena, team);
		Balls.Ball ball = ball(level, arena);
		if (ball != null) Balls.place(ball, Balls.middle(level, arena));
	}

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (arena.bases.size() < arena.preset.teams) return;
		Balls.Ball ball = ball(level, arena);
		if (ball == null) return;
		int into = goalHolding(arena, ball);
		if (into >= 0) scored(server, level, arena, into, ball);
		else Balls.rescue(server, arena, ball, Balls.middle(level, arena), now);
	}

	private static Balls.Ball ball(ServerLevel level, Arena arena) {
		return Balls.single(level, arena, Balls.swallowed(arena.preset.ballBlock));
	}

	// --- Where a goal is ---

	/** On the team's own spoke from the middle, as far out as leaves the goal clear of the edge. */
	private static BlockPos goalSpot(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		int width = footprint.width();
		double radius = Math.max(HALF + DEPTH, Math.min(width * Preset.GOAL_RING, width / 2.0 - BEHIND));
		double angle = arena.preset.ringAngle(team);
		int x = (int) Math.floor(footprint.centerX() + Math.cos(angle) * radius);
		int z = (int) Math.floor(footprint.centerZ() + Math.sin(angle) * radius);
		return new BlockPos(x, Builds.ground(level, arena, x, z), z);
	}

	/** A place against a goal: across it, back from its front away from the middle, up from its ground. */
	private record Place(int across, int back, int up) {}

	private static Place place(Arena arena, BlockPos mouth, BlockPos pos) {
		Direction back = Builds.front(arena, mouth.getX(), mouth.getZ()).getOpposite();
		Direction side = back.getClockWise();
		int dx = pos.getX() - mouth.getX();
		int dz = pos.getZ() - mouth.getZ();
		return new Place(dx * side.getStepX() + dz * side.getStepZ(), dx * back.getStepX() + dz * back.getStepZ(), pos.getY() - mouth.getY());
	}

	/** How far either side of the middle a line runs: the whole arena between two teams, a stretch of it between more. */
	private static int lineHalf(Arena arena) {
		return arena.preset.teams == 2 ? arena.footprint().width() : arena.footprint().width() / 4;
	}

	/**
	 * Whether a block at this place is part of the goal. A net is its posts and the bar across the
	 * front, the net down both sides, along the back and over the top, and the ground under it; a
	 * line, the strip of ground it is drawn on.
	 */
	private static boolean frame(Arena arena, Place at) {
		int side = Math.abs(at.across());
		return switch (arena.preset.goalShape) {
			case NET -> at.back() >= 0 && at.back() <= DEPTH && side <= HALF + 1 && at.up() >= -1 && at.up() <= HEIGHT
				&& (at.up() == -1 || at.up() == HEIGHT || side == HALF + 1 || at.back() == DEPTH);
			case LINE -> at.back() == 0 && side <= lineHalf(arena) && Math.abs(at.up() + 1) <= 3;
		};
	}

	private static void build(ServerLevel level, Arena arena, int team) {
		BlockPos mouth = arena.bases.get(team);
		Direction back = Builds.front(arena, mouth.getX(), mouth.getZ()).getOpposite();
		Direction side = back.getClockWise();
		BlockState white = Terrain.block("minecraft:white_concrete", Blocks.QUARTZ_BLOCK.defaultBlockState());
		BlockState colour = Terrain.block("minecraft:" + TeamColors.of(team).dye() + "_concrete", white);
		BlockState glass = Terrain.block("minecraft:" + TeamColors.of(team).dye() + "_stained_glass", Blocks.GLASS.defaultBlockState());
		switch (arena.preset.goalShape) {
			case NET -> {
				BlockPos middle = mouth.relative(back, DEPTH / 2);
				Builds.yard(level, middle.getX(), mouth.getY(), middle.getZ(), HALF + 2, HEIGHT + 2);
				for (int across = -HALF - 1; across <= HALF + 1; across++) {
					for (int behind = 0; behind <= DEPTH; behind++) {
						for (int up = 0; up <= HEIGHT; up++) {
							if (!frame(arena, new Place(across, behind, up))) continue;
							level.setBlock(mouth.relative(side, across).relative(back, behind).above(up), behind == 0 ? white : glass, Block.UPDATE_ALL);
						}
					}
				}
			}
			case LINE -> {
				Footprint footprint = arena.footprint();
				for (int across = -lineHalf(arena); across <= lineHalf(arena); across++) {
					BlockPos column = mouth.relative(side, across);
					if (column.getX() < footprint.minX() || column.getX() >= footprint.maxX()
						|| column.getZ() < footprint.minZ() || column.getZ() >= footprint.maxZ()) continue;
					int ground = Builds.ground(level, arena, column.getX(), column.getZ());
					if (Math.abs(ground - mouth.getY()) > 3) continue;
					level.setBlock(column.atY(ground - 1), colour, Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Whether this block is part of a goal, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.SOCCER) return false;
		for (BlockPos mouth : arena.bases) if (frame(arena, place(arena, mouth, pos))) return true;
		return false;
	}

	/** Whose goal the ball is in; -1 for nobody's. */
	private static int goalHolding(Arena arena, Balls.Ball ball) {
		Vec3 centre = ball.cube.getBoundingBox().getCenter();
		for (int team = 0; team < arena.bases.size(); team++) {
			BlockPos mouth = arena.bases.get(team);
			Direction back = Builds.front(arena, mouth.getX(), mouth.getZ()).getOpposite();
			Direction side = back.getClockWise();
			double dx = centre.x - (mouth.getX() + 0.5);
			double dz = centre.z - (mouth.getZ() + 0.5);
			double behind = dx * back.getStepX() + dz * back.getStepZ();
			double across = dx * side.getStepX() + dz * side.getStepZ();
			double up = centre.y - mouth.getY();
			boolean in = switch (arena.preset.goalShape) {
				// Past the line once the middle of the ball is behind the posts.
				case NET -> behind > 0.5 && behind < DEPTH - 0.5 && Math.abs(across) < HALF + 0.5 && up >= 0 && up < HEIGHT;
				case LINE -> behind > 0.5 && Math.abs(across) < lineHalf(arena) + 0.5;
			};
			if (in) return team;
		}
		return -1;
	}

	// --- Scoring ---

	private static void scored(MinecraftServer server, ServerLevel level, Arena arena, int into, Balls.Ball ball) {
		Arena.Member toucher = Balls.lastTouch(arena, ball);
		boolean own = toucher != null && toucher.team == into;
		int by = toucher != null && !own && toucher.team >= 0 ? toucher.team : arena.preset.teams == 2 ? 1 - into : -1;

		Vec3 at = ball.cube.position();
		level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 1, at.z, 60, 1.5, 1, 1.5, 0.15);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 3.0F, 1.0F);
		Balls.place(ball, Balls.middle(level, arena));

		if (by < 0) {
			Arenas.tellInside(server, arena, (toucher != null ? toucher.name + " put it in their own goal" : "In " + TeamColors.of(into).name() + "'s goal")
				+ "; with no one side to give it to, it counts for nobody");
			return;
		}
		int score = arena.scores.merge(by, 1, Integer::sum);
		Arenas.vault(server).touch();
		String who = toucher == null ? TeamColors.of(by).name() + " scores"
			: own ? "Own goal by " + toucher.name + ", for " + TeamColors.of(by).name()
			: toucher.name + " scores for " + TeamColors.of(by).name();
		Component title = Component.literal("Goal!")
			.withStyle(style -> style.withColor(TeamColors.of(by).team().textColor()));
		Component subtitle = Component.literal(who + " · " + Banks.tally(arena)).withStyle(ChatFormatting.YELLOW);
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
		}
		Arenas.tellInside(server, arena, who + "! " + Banks.tally(arena));
		int target = arena.preset.goalTarget;
		if (target > 0 && score >= target) Goals.winTeam(server, arena, by, score + (score == 1 ? " goal" : " goals"));
	}

	// --- The board ---

	/** The bar's line: each team's goals, and what wins. */
	static String status(Arena arena) {
		int target = arena.preset.goalTarget;
		return Banks.tally(arena) + (target > 0 ? "  ·  " + target + (target == 1 ? " goal wins" : " goals win") : "");
	}

	/** Who touched the ball last, for the foot of the board. */
	static String lastTouchWords(MinecraftServer server, Arena arena) {
		ServerLevel level = Arenas.level(server, arena);
		if (level == null) return "";
		for (Balls.Ball ball : Balls.of(level, arena)) {
			Arena.Member member = Balls.lastTouch(arena, ball);
			if (member != null) return "Last touch: " + member.name;
		}
		return "Kick-off";
	}
}
