package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.Map;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jspecify.annotations.Nullable;

/**
 * Keep-away: one ball, no goals. The ball is whoever touched it last, until somebody else does,
 * and every second it is yours counts for you, or for your team. Whoever has it glows.
 *
 * <p>Counted the way the hill is: in the team's score with teams, and in each player's own
 * {@link Arena.Member#held} without.
 */
public final class KeepAway {
	private KeepAway() {}

	private static final Map<String, Long> counted = new HashMap<>();

	static void begin(ServerLevel level, Arena arena, long now) {
		Balls.Ball ball = ball(level, arena);
		if (ball != null) Balls.place(ball, Balls.middle(level, arena));
		counted.put(arena.id, now);
	}

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Balls.Ball ball = ball(level, arena);
		if (ball == null) return;
		Balls.rescue(server, arena, ball, Balls.middle(level, arena), now);
		if (now - counted.getOrDefault(arena.id, now) < 1000) return;
		counted.put(arena.id, now);

		Arena.Member holder = Balls.lastTouch(arena, ball);
		if (holder == null) return;
		ServerPlayer player = server.getPlayerList().getPlayer(holder.id);
		if (player != null) player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));

		int target = arena.preset.keepTarget * 60;
		if (arena.preset.teamsOn() && holder.team >= 0) {
			int seconds = arena.scores.merge(holder.team, 1, Integer::sum);
			if (target > 0 && seconds >= target) Goals.winTeam(server, arena, holder.team, "kept the ball for " + Fields.duration(arena.preset.keepTarget).toLowerCase());
		} else {
			holder.held++;
			if (target > 0 && holder.held >= target) Goals.winPlayer(server, arena, holder, "kept the ball for " + Fields.duration(arena.preset.keepTarget).toLowerCase());
		}
	}

	private static Balls.@Nullable Ball ball(ServerLevel level, Arena arena) {
		return Balls.single(level, arena, Balls.swallowed(arena.preset.ballBlock));
	}

	/** Who has the ball, by name, or null for nobody yet. */
	static @Nullable String holder(MinecraftServer server, Arena arena) {
		ServerLevel level = Arenas.level(server, arena);
		if (level == null) return null;
		for (Balls.Ball ball : Balls.of(level, arena)) {
			Arena.Member member = Balls.lastTouch(arena, ball);
			if (member != null) return arena.preset.teamsOn() && member.team >= 0 ? member.name + " (" + TeamColors.of(member.team).name() + ")" : member.name;
		}
		return null;
	}

	/** The bar's line: each side's time with the ball, and what wins. */
	static String status(Arena arena, Arena.@Nullable Member viewer) {
		StringBuilder line = new StringBuilder();
		if (arena.preset.teamsOn()) {
			for (int team = 0; team < arena.preset.teams; team++) {
				if (team > 0) line.append("  ");
				line.append(TeamColors.of(team).name()).append(" ").append(clock(arena.scores.getOrDefault(team, 0)));
			}
		} else if (viewer != null) {
			line.append("You ").append(clock(viewer.held));
		}
		if (arena.preset.keepTarget > 0) line.append("  ·  ").append(Fields.duration(arena.preset.keepTarget).toLowerCase()).append(" wins");
		return line.toString();
	}

	static String clock(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	public static void forget(Arena arena) {
		counted.remove(arena.id);
	}

	/** Time ran out with teams: the scores say it. Without, the longest holder takes it. */
	static boolean timeUp(MinecraftServer server, Arena arena) {
		if (arena.preset.teamsOn()) return false;
		Arena.Member best = null;
		boolean tie = false;
		for (Arena.Member member : arena.members.values()) {
			if (best == null || member.held > best.held) {
				best = member;
				tie = false;
			} else if (member.held == best.held) {
				tie = true;
			}
		}
		if (best == null || tie || best.held <= 0) return false;
		Goals.winPlayer(server, arena, best, "kept the ball longest");
		return true;
	}
}
