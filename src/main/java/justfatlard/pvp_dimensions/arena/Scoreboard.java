package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What the game looks like from where one player stands, as a few lines for the corner of their
 * screen: who is ahead and by how much, each side's bar filling toward the win, the one holding
 * the hill or carrying a flag lit up, and a word underneath on what is happening now.
 *
 * <p>Made fresh each second from the arena, the same as the words on the action bar, of which it
 * is the drawn version. {@link justfatlard.pvp_dimensions.ui.GameHud} draws it.
 */
public final class Scoreboard {
	private Scoreboard() {}

	/** One side on the board: a team's colour or a player's face, a name, a score and a bar. */
	public record Line(String key, @Nullable UUID face, int colour, String label, String value, float fill, boolean lit) {}

	/** @param goal what wins, in a word or two, beside the title; empty for nothing */
	public record Board(String title, String goal, List<Line> lines, @Nullable String footer) {}

	private static final int PLAYER_COLOUR = 0xFFE0E0E0;
	/** Players shown by name, besides whoever is looking. */
	private static final int TOP = 3;

	public static @Nullable Board of(MinecraftServer server, Arena arena, Arena.Member viewer) {
		Preset preset = arena.preset;
		List<Line> lines = new ArrayList<>();
		String title;
		String goal = "";
		String footer = null;
		switch (preset.activeGoal()) {
			case HILL -> {
				int target = preset.hillTarget * 60;
				String holder = Hill.holding(arena);
				if (preset.teamsOn()) {
					int most = Math.max(1, arena.scores.values().stream().mapToInt(Integer::intValue).max().orElse(1));
					for (int team = 0; team < preset.teams; team++) {
						int held = arena.scores.getOrDefault(team, 0);
						lines.add(team(team, clock(held), fraction(held, target > 0 ? target : most), holder.equals("team" + team)));
					}
				} else {
					int most = Math.max(1, arena.members.values().stream().mapToInt(member -> member.held).max().orElse(1));
					players(arena, viewer, member -> member.held, member -> clock(member.held),
						member -> fraction(member.held, target > 0 ? target : most), member -> holder.equals(member.id.toString()), lines);
				}
				title = "The hill";
				goal = preset.hillTarget > 0 ? clock(target) + " wins" : "";
				footer = holder.equals("contested") ? "Contested!" : holder.isEmpty() ? "Nobody holds the hill"
					: Hill.sideName(arena, holder) + " on the hill";
			}
			case BANK -> {
				int most = Math.max(1, arena.scores.values().stream().mapToInt(Integer::intValue).max().orElse(1));
				for (int team = 0; team < preset.teams; team++) {
					int points = arena.scores.getOrDefault(team, 0);
					lines.add(team(team, points + " pts", fraction(points, preset.bankTarget > 0 ? preset.bankTarget : most), viewer.team == team));
				}
				title = "Banking";
				goal = preset.bankTarget > 0 ? preset.bankTarget + " wins" : "";
				footer = Banks.values(preset, 3);
			}
			case CTF -> {
				List<String> carried = new ArrayList<>();
				boolean[] away = new boolean[preset.teams];
				for (Arena.Member member : arena.fighting()) {
					ServerPlayer player = server.getPlayerList().getPlayer(member.id);
					if (player == null) continue;
					for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
						ItemStack stack = player.getInventory().getItem(slot);
						int team = stack.isEmpty() ? -1 : Goals.flagTeam(arena, stack);
						if (team >= 0 && team < preset.teams && team != member.team) {
							away[team] = true;
							carried.add(member.name + " has " + TeamColors.of(team).name() + "'s flag");
						}
					}
				}
				for (int team = 0; team < preset.teams; team++) {
					int captures = arena.scores.getOrDefault(team, 0);
					lines.add(team(team, captures + " of " + preset.captures, fraction(captures, preset.captures), away[team]));
				}
				title = "Flags";
				goal = preset.captures + (preset.captures == 1 ? " capture" : " captures");
				footer = carried.isEmpty() ? "Every flag is home" : String.join("; ", carried);
			}
			case KILLS -> {
				int target = preset.killTarget;
				if (preset.teamsOn()) {
					int[] totals = new int[preset.teams];
					for (Arena.Member member : arena.members.values()) if (member.team >= 0 && member.team < preset.teams) totals[member.team] += member.kills;
					int most = Math.max(1, java.util.Arrays.stream(totals).max().orElse(1));
					for (int team = 0; team < preset.teams; team++) {
						lines.add(team(team, totals[team] + " kills", fraction(totals[team], target > 0 ? target : most), viewer.team == team));
					}
				} else {
					int most = Math.max(1, arena.members.values().stream().mapToInt(member -> member.kills).max().orElse(1));
					players(arena, viewer, member -> member.kills, member -> member.kills + (member.kills == 1 ? " kill" : " kills"),
						member -> fraction(member.kills, target > 0 ? target : most), member -> member == viewer, lines);
				}
				title = "Kills";
				goal = target > 0 ? target + " wins" : "";
			}
			case MOBS -> {
				int count = preset.mobGoalCount;
				switch (preset.mobScope()) {
					case EVERYONE -> {
						int all = arena.members.values().stream().mapToInt(member -> member.mobKills).sum();
						lines.add(new Line("all", null, 0xFF55C455, "Together", all + " of " + count, fraction(all, count), false));
					}
					case TEAM -> {
						for (int team = 0; team < preset.teams; team++) {
							int finalTeam = team;
							int teamCount = arena.members.values().stream().filter(member -> member.team == finalTeam).mapToInt(member -> member.mobKills).sum();
							lines.add(team(team, teamCount + " of " + count, fraction(teamCount, count), viewer.team == team));
						}
					}
					case PLAYER -> players(arena, viewer, member -> member.mobKills, member -> member.mobKills + " of " + count,
						member -> fraction(member.mobKills, count), member -> member == viewer, lines);
				}
				title = "Mob hunt";
				goal = count + " " + Goals.mobWords(preset);
			}
			case TAKEOVER -> {
				var coverage = Goals.coverage(arena);
				int target = preset.takeoverPercent;
				for (int team = 0; team < preset.teams; team++) {
					int percent = coverage.getOrDefault(team, 0);
					lines.add(team(team, percent + "%", fraction(percent, target > 0 ? target : 100), viewer.team == team));
				}
				title = "Takeover";
				goal = target > 0 ? target + "% wins" : "";
			}
			case DESTRUCTION -> {
				ServerLevel level = Arenas.level(server, arena);
				for (int team = 0; team < preset.teams && level != null; team++) {
					int[] left = arena.fallen.contains(team) ? new int[] {0, 1} : Goals.baseLeft(level, arena, team);
					lines.add(team(team, arena.fallen.contains(team) ? "fallen" : left[0] + " left", fraction(left[0], left[1]), viewer.team == team));
				}
				title = "Destroy their base";
			}
			case RACE -> {
				BlockPos finish = arena.marker;
				if (finish == null) return null;
				List<Arena.Member> racing = new ArrayList<>();
				java.util.Map<UUID, Integer> distance = new java.util.HashMap<>();
				for (Arena.Member member : arena.fighting()) {
					ServerPlayer player = server.getPlayerList().getPlayer(member.id);
					if (player == null || !player.level().dimension().equals(arena.dimension)) continue;
					distance.put(member.id, (int) Math.round(Math.sqrt(player.distanceToSqr(finish.getX() + 0.5, finish.getY() + 1, finish.getZ() + 0.5))));
					racing.add(member);
				}
				racing.sort(Comparator.comparingInt(member -> distance.get(member.id)));
				int far = Math.max(1, distance.values().stream().mapToInt(Integer::intValue).max().orElse(1));
				for (int i = 0; i < racing.size() && lines.size() < TOP + 1; i++) {
					Arena.Member member = racing.get(i);
					if (i >= TOP && member != viewer) continue;
					int metres = distance.get(member.id);
					lines.add(new Line(member.id.toString(), member.id, colour(arena, member), (i + 1) + ". " + member.name, metres + "m",
						1F - fraction(metres, far), member == viewer));
				}
				title = "Race";
				goal = "to the beacon";
				footer = Race.status(server, arena, viewer);
			}
			case WAVES -> {
				int[] left = Waves.left(arena);
				lines.add(new Line("wave", null, 0xFFD04A4A, "Mobs left", String.valueOf(left[0]), fraction(left[0], Math.max(1, left[1])), false));
				title = "Wave " + Math.max(1, arena.waveNumber);
				goal = preset.waves.isEmpty() ? "" : "of " + preset.waves.size();
				footer = Waves.status(arena, System.currentTimeMillis());
			}
			default -> {
				if (preset.mobStyle != Preset.MobStyle.WAVES && !preset.livesOn() && !preset.hordeOn()) return null;
				if (preset.mobStyle == Preset.MobStyle.WAVES) {
					int[] left = Waves.left(arena);
					lines.add(new Line("wave", null, 0xFFD04A4A, "Mobs left", String.valueOf(left[0]), fraction(left[0], Math.max(1, left[1])), false));
					footer = Waves.status(arena, System.currentTimeMillis());
				}
				title = arena.title();
			}
		}
		String extra = lives(arena, viewer);
		if (extra != null) footer = footer == null ? extra : footer + " · " + extra;
		return new Board(title, goal, lines, footer);
	}

	/** Lives left, the viewer's or their team's, and how the horde stands, where either counts. */
	private static @Nullable String lives(Arena arena, Arena.Member viewer) {
		Preset preset = arena.preset;
		List<String> words = new ArrayList<>();
		if (preset.livesOn() && !viewer.zombie) {
			int left = preset.pooledLives() && viewer.team >= 0 ? arena.pools.getOrDefault(viewer.team, preset.livesCount)
				: viewer.lives >= 0 ? viewer.lives : preset.livesCount;
			words.add((preset.pooledLives() ? "Team lives " : "Lives ") + left);
		}
		if (preset.hordeOn()) {
			long standing = arena.fighting().size();
			long zombies = arena.members.values().stream().filter(member -> member.zombie && member.inside).count();
			if (zombies > 0) words.add(standing + " standing, " + zombies + " zombie" + (zombies == 1 ? "" : "s"));
		}
		return words.isEmpty() ? null : String.join(" · ", words);
	}

	private static Line team(int team, String value, float fill, boolean lit) {
		TeamColors.Colour colour = TeamColors.of(team);
		return new Line("team" + team, null, 0xFF000000 | colour.team().rgb(), colour.name(), value, fill, lit);
	}

	/** The top few by a score, and the viewer too if they aren't among them. */
	private static void players(Arena arena, Arena.Member viewer, ToIntFunction<Arena.Member> score, java.util.function.Function<Arena.Member, String> value,
			java.util.function.Function<Arena.Member, Float> fill, java.util.function.Predicate<Arena.Member> lit, List<Line> lines) {
		List<Arena.Member> ranked = new ArrayList<>(arena.members.values().stream().filter(member -> member.inside && !member.watching).toList());
		ranked.sort(Comparator.comparingInt(score).reversed());
		boolean viewerShown = false;
		for (int i = 0; i < ranked.size() && i < TOP; i++) {
			Arena.Member member = ranked.get(i);
			viewerShown |= member == viewer;
			lines.add(new Line(member.id.toString(), member.id, colour(arena, member), (i + 1) + ". " + member.name, value.apply(member), fill.apply(member), lit.test(member)));
		}
		if (!viewerShown && ranked.contains(viewer)) {
			int place = ranked.indexOf(viewer) + 1;
			lines.add(new Line(viewer.id.toString(), viewer.id, colour(arena, viewer), place + ". " + viewer.name, value.apply(viewer), fill.apply(viewer), lit.test(viewer)));
		}
	}

	private static int colour(Arena arena, Arena.Member member) {
		return arena.preset.teamsOn() && member.team >= 0 ? 0xFF000000 | TeamColors.of(member.team).team().rgb() : PLAYER_COLOUR;
	}

	private static float fraction(int of, int whole) {
		return whole <= 0 ? 0F : Math.clamp(of / (float) whole, 0F, 1F);
	}

	private static String clock(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}
}
