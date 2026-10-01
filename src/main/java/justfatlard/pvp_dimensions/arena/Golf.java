package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Golf: everyone has a ball of their own and plays the same holes in turn, each from where the last
 * one was. Every kick of your own ball is a stroke; walking into it does nothing, so a ball only
 * moves by a stroke. Anybody can kick anybody's ball, which costs its owner nothing and helps them
 * not at all. Fewest strokes over the course wins.
 *
 * <p>The course is new every time: {@link Arena#bases} holds its cups in order, each a pit three
 * across with a flag beside it and sparks over it. A ball lost to lava or the void comes back to
 * the start of its hole, a stroke the worse.
 *
 * <p>Counted on the member: {@link Arena.Member#held} is the holes sunk, {@link Arena.Member#points}
 * the strokes.
 */
public final class Golf {
	private Golf() {}

	/** How near the rest of the course a cup may be. */
	private static final int APART = 8;
	/** How far from its hole's cup a tee is set. */
	private static final int TEE_BACK = 4;

	/** Who has been given a ball, so one that is gone is a lost ball and not a first. */
	private static final Map<String, Set<UUID>> given = new HashMap<>();
	private static final Map<String, Long> sparked = new HashMap<>();

	static void begin(ServerLevel level, Arena arena) {
		arena.bases.clear();
		Footprint footprint = arena.footprint();
		Vec3 from = Balls.middle(level, arena);
		double reach = Math.max(10, footprint.width() / 4.0);
		for (int hole = 0; hole < arena.preset.golfHoles; hole++) {
			BlockPos cup = cupSpot(level, arena, from, reach);
			arena.bases.add(cup);
			dig(level, arena, cup);
			from = Vec3.atBottomCenterOf(cup);
		}
		given.remove(arena.id);
	}

	private static BlockPos cupSpot(ServerLevel level, Arena arena, Vec3 from, double reach) {
		BlockPos best = null;
		double bestScore = -1;
		for (int attempt = 0; attempt < 40; attempt++) {
			Vec3 spot = Spawns.random(level, arena);
			int x = (int) Math.floor(spot.x);
			int z = (int) Math.floor(spot.z);
			if (Builds.room(arena, x, z, 3) < 3) continue;
			double apart = Double.MAX_VALUE;
			for (BlockPos cup : arena.bases) apart = Math.min(apart, Math.hypot(cup.getX() - x, cup.getZ() - z));
			if (apart < APART) continue;
			// As near the wanted length of a hole as can be had.
			double score = -Math.abs(Math.hypot(from.x - x, from.z - z) - reach);
			if (best == null || score > bestScore) {
				best = new BlockPos(x, Builds.ground(level, arena, x, z), z);
				bestScore = score;
			}
		}
		if (best != null) return best;
		Vec3 near = Spawns.random(level, arena);
		return BlockPos.containing(near);
	}

	/** The cup: a yard levelled round it, a pit three across and one deep, and a flag on a pole. */
	private static void dig(ServerLevel level, Arena arena, BlockPos cup) {
		Builds.yard(level, cup.getX(), cup.getY(), cup.getZ(), 3, 3);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) level.setBlock(cup.offset(dx, -1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		for (int up = 0; up < 3; up++) level.setBlock(cup.offset(2, up, 2), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(cup.offset(2, 3, 2), justfatlard.pvp_dimensions.world.Terrain.block("minecraft:red_wool", Blocks.REDSTONE_BLOCK.defaultBlockState()), Block.UPDATE_ALL);
	}

	/** Whether this block is part of a cup: its floor and its flag. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.GOLF) return false;
		for (BlockPos cup : arena.bases) {
			int dx = pos.getX() - cup.getX();
			int dz = pos.getZ() - cup.getZ();
			int up = pos.getY() - cup.getY();
			if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && up == -2) return true;
			if (dx == 2 && dz == 2 && up >= 0 && up <= 3) return true;
		}
		return false;
	}

	/** Where a hole is played from: the middle for the first, beside the last cup for the rest, towards the next. */
	private static Vec3 tee(ServerLevel level, Arena arena, int hole) {
		if (hole == 0 || hole > arena.bases.size()) return Balls.middle(level, arena);
		BlockPos last = arena.bases.get(hole - 1);
		BlockPos next = arena.bases.get(Math.min(hole, arena.bases.size() - 1));
		Vec3 toward = new Vec3(next.getX() - last.getX(), 0, next.getZ() - last.getZ());
		Vec3 step = toward.lengthSqr() < 1 ? new Vec3(1, 0, 0) : toward.normalize();
		return Spawns.near(level, arena, (int) Math.floor(last.getX() + step.x * TEE_BACK), (int) Math.floor(last.getZ() + step.z * TEE_BACK), 1);
	}

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		int holes = arena.bases.size();
		if (holes == 0) return;
		if (now - sparked.getOrDefault(arena.id, 0L) >= 1000) {
			sparked.put(arena.id, now);
			for (BlockPos cup : arena.bases) {
				for (int up = 0; up < 4; up++) level.sendParticles(ParticleTypes.END_ROD, cup.getX() + 0.5, cup.getY() + 1 + up, cup.getZ() + 0.5, 1, 0.1, 0.3, 0.1, 0);
			}
		}
		Set<UUID> handed = given.computeIfAbsent(arena.id, id -> new HashSet<>());
		for (Arena.Member member : arena.fighting()) {
			if (member.held >= holes) continue;
			Balls.Ball ball = Balls.owned(level, arena, member.id);
			if (ball == null) {
				boolean lost = handed.contains(member.id);
				Balls.spawn(level, arena, tee(level, arena, member.held), Balls.swallowed(arena.preset.ballBlock), member.id);
				handed.add(member.id);
				if (lost) {
					member.points++;
					ServerPlayer player = server.getPlayerList().getPlayer(member.id);
					if (player != null) Say.to(player, "Your ball was lost; it's back at the tee, a stroke the worse");
				}
				continue;
			}
			if (in(ball, arena.bases.get(member.held))) sunk(server, level, arena, member, ball);
		}
	}

	/** In the pit: over its three by three, and below the ground round it. */
	private static boolean in(Balls.Ball ball, BlockPos cup) {
		Vec3 centre = ball.cube.getBoundingBox().getCenter();
		return Math.abs(centre.x - (cup.getX() + 0.5)) < 1.5 && Math.abs(centre.z - (cup.getZ() + 0.5)) < 1.5 && centre.y < cup.getY();
	}

	private static void sunk(MinecraftServer server, ServerLevel level, Arena arena, Arena.Member member, Balls.Ball ball) {
		member.held++;
		Arenas.vault(server).touch();
		int holes = arena.bases.size();
		Vec3 at = ball.cube.position();
		level.playSound(null, at.x, at.y, at.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.2F);
		if (member.held >= holes) {
			Balls.remove(arena, ball);
			Arenas.tellInside(server, arena, member.name + " has finished, on " + strokes(member.points));
			everyoneDone(server, arena);
			return;
		}
		Balls.place(ball, tee(level, arena, member.held));
		ServerPlayer player = server.getPlayerList().getPlayer(member.id);
		if (player != null) Say.to(player, "Hole " + member.held + " sunk, " + strokes(member.points) + " so far. On to hole " + (member.held + 1) + ": follow the sparks");
	}

	/** A kick of one's own ball, which is a stroke. */
	static void stroke(MinecraftServer server, Arena arena, Balls.Ball ball, ServerPlayer player, Arena.Member member) {
		if (arena.preset.activeGoal() != Preset.Goal.GOLF || !player.getUUID().equals(ball.owner)) return;
		member.points++;
		Say.bar(player, "Stroke " + member.points);
	}

	private static String strokes(int count) {
		return count + (count == 1 ? " stroke" : " strokes");
	}

	/** Everyone still playing has finished: the fewest strokes takes it. */
	private static void everyoneDone(MinecraftServer server, Arena arena) {
		int holes = arena.bases.size();
		for (Arena.Member member : arena.fighting()) if (member.held < holes) return;
		decide(server, arena, "the fewest strokes");
	}

	/** The most holes sunk, then the fewest strokes; a tie takes nothing. */
	private static boolean decide(MinecraftServer server, Arena arena, String how) {
		Arena.Member best = null;
		boolean tie = false;
		for (Arena.Member member : arena.members.values()) {
			if (!member.inside || member.held == 0 && member.points == 0) continue;
			int order = best == null ? 1 : Integer.compare(member.held, best.held) != 0 ? Integer.compare(member.held, best.held) : Integer.compare(best.points, member.points);
			if (order > 0) {
				best = member;
				tie = false;
			} else if (order == 0) {
				tie = true;
			}
		}
		if (best == null || tie) return false;
		String words = how + ": " + best.held + (best.held == 1 ? " hole" : " holes") + " in " + strokes(best.points);
		if (arena.preset.teamsOn() && best.team >= 0) Goals.winTeam(server, arena, best.team, best.name + " had " + words);
		else Goals.winPlayer(server, arena, best, words);
		return true;
	}

	static boolean timeUp(MinecraftServer server, Arena arena) {
		return decide(server, arena, "furthest round, fewest strokes");
	}

	/** The bar's line: the hole being played and the strokes so far. */
	static String status(Arena arena, Arena.@Nullable Member member) {
		if (member == null) return "";
		int holes = Math.max(1, arena.bases.size());
		if (member.held >= holes) return "Finished, on " + strokes(member.points);
		return "Hole " + (member.held + 1) + " of " + holes + "  ·  " + strokes(member.points);
	}

	/** For the board: holes first, then the fewer strokes the better. */
	static int rank(Arena.Member member) {
		return member.held * 10_000 - member.points;
	}

	public static void forget(Arena arena) {
		given.remove(arena.id);
		sparked.remove(arena.id);
	}
}
