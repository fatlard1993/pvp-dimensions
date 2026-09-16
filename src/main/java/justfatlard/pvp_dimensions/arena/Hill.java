package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * King of the hill: a gold pad to stand on, and a second counted for every second one side has it
 * to itself. Two sides on it at once is a fight, and counts for nobody. Whoever holds it glows.
 *
 * <p>The first side to the preset's time wins; with no time set, whoever held it longest when time
 * is up. It can move somewhere new every few minutes, so nobody digs in round it for good.
 */
public final class Hill {
	private Hill() {}

	/** When each arena's hill last counted a second. */
	private static final Map<String, Long> counted = new HashMap<>();
	/** Who each arena's hill was last said to belong to: a side, "contested", or "" for nobody. */
	private static final Map<String, String> held = new HashMap<>();

	public static void begin(ServerLevel level, Arena arena, long now) {
		Markers.placeHill(level, arena, arena.preset.hillPlace == Preset.MarkerPlace.RANDOM);
		arena.markerMovesAt = arena.preset.hillMoves > 0 ? now + arena.preset.hillMoves * 60_000L : 0;
		counted.put(arena.id, now);
	}

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Preset preset = arena.preset;
		if (arena.marker == null) begin(level, arena, now);
		if (arena.markerMovesAt > 0 && now >= arena.markerMovesAt) {
			Markers.placeHill(level, arena, true);
			GoalCompass.retarget(server, arena);
			Moments.hillMoved(arena);
			arena.markerMovesAt = now + preset.hillMoves * 60_000L;
			Arenas.tellInside(server, arena, "The hill has moved! Follow the beam");
		}
		long last = counted.getOrDefault(arena.id, now);
		if (now - last < 1000) return;
		counted.put(arena.id, now);
		Markers.tick(level, arena, now);

		List<Arena.Member> on = Markers.standing(server, arena);
		Set<String> sides = new LinkedHashSet<>();
		for (Arena.Member member : on) sides.add(side(arena, member));
		String holding = sides.size() == 1 ? sides.iterator().next() : sides.isEmpty() ? "" : "contested";
		if (!holding.equals(held.getOrDefault(arena.id, ""))) {
			held.put(arena.id, holding);
			if (holding.equals("contested")) Arenas.tellInside(server, arena, "The hill is contested");
			else if (!holding.isEmpty()) Arenas.tellInside(server, arena, sideName(arena, holding) + " " + (preset.teamsOn() ? "have" : "has") + " the hill");
		}
		for (Arena.Member member : on) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null) player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
		}
		if (sides.size() != 1) return;

		int target = preset.hillTarget * 60;
		if (preset.teamsOn()) {
			int team = on.get(0).team;
			int seconds = arena.scores.merge(team, 1, Integer::sum);
			Moments.hill(server, arena, holding, on, seconds);
			if (target > 0 && seconds >= target) Goals.winTeam(server, arena, team, "held the hill for " + Fields.duration(preset.hillTarget).toLowerCase());
		} else {
			Arena.Member holder = on.get(0);
			holder.held++;
			Moments.hill(server, arena, holding, on, holder.held);
			if (target > 0 && holder.held >= target) Goals.winPlayer(server, arena, holder, "held the hill for " + Fields.duration(preset.hillTarget).toLowerCase());
		}
	}

	static String side(Arena arena, Arena.Member member) {
		return arena.preset.teamsOn() ? "team" + member.team : member.id.toString();
	}

	/** Who has the hill now: a side, "contested", or "" for nobody. */
	static String holding(Arena arena) {
		return held.getOrDefault(arena.id, "");
	}

	static String sideName(Arena arena, String side) {
		if (side.startsWith("team")) return TeamColors.of(Integer.parseInt(side.substring(4))).name();
		Arena.Member member = arena.member(UUID.fromString(side));
		return member != null ? member.name : "Somebody";
	}

	/** The bar's line: each side's time, and who has it now. */
	public static String status(Arena arena, Arena.@Nullable Member viewer) {
		StringBuilder line = new StringBuilder();
		if (arena.preset.teamsOn()) {
			for (int team = 0; team < arena.preset.teams; team++) {
				if (team > 0) line.append("  ");
				line.append(TeamColors.of(team).name()).append(" ").append(clock(arena.scores.getOrDefault(team, 0)));
			}
		} else {
			Arena.Member leader = null;
			for (Arena.Member member : arena.members.values()) {
				if (leader == null || member.held > leader.held) leader = member;
			}
			if (viewer != null) line.append("You ").append(clock(viewer.held));
			if (leader != null && leader != viewer && leader.held > 0) line.append("  ").append(leader.name).append(" leads ").append(clock(leader.held));
		}
		String holder = held.getOrDefault(arena.id, "");
		if (holder.equals("contested")) line.append("  ·  contested");
		else if (!holder.isEmpty()) line.append("  ·  ").append(sideName(arena, holder)).append(" on it");
		if (arena.preset.hillTarget > 0) line.append("  ·  ").append(Fields.duration(arena.preset.hillTarget).toLowerCase()).append(" wins");
		return line.toString();
	}

	private static String clock(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	public static void forget(Arena arena) {
		counted.remove(arena.id);
		held.remove(arena.id);
	}
}
