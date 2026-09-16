package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.jspecify.annotations.Nullable;

/**
 * An arena's teams, as the game's own scoreboard teams: that is what colours a name over a head
 * and in the player list, and what keeps teammates' swords off each other, on every client.
 *
 * <p>A player can be on one scoreboard team at a time, so the one they were on before is kept in
 * their {@link Visit} and handed back when they leave.
 *
 * <p>Names over heads are hidden the same way, and only a team can hide them: in a free for all
 * with names hidden, everyone fighting is on one uncoloured team that lets its members hurt each
 * other and does not show them each other's invisibility.
 */
public final class Teams {
	private Teams() {}

	public static String teamName(Arena arena, int team) {
		return "pvpd_" + arena.id + "_" + team;
	}

	private static String hordeName(Arena arena) {
		return "pvpd_" + arena.id + "_horde";
	}

	private static String hiddenName(Arena arena) {
		return "pvpd_" + arena.id + "_hidden";
	}

	/** Whether a free-for-all arena keeps its players on the one team that hides their names. */
	private static boolean hidesFreeForAll(Arena arena) {
		return !arena.preset.teamsOn() && arena.preset.nameTags != Preset.NameTags.SHOWN;
	}

	public static void create(MinecraftServer server, Arena arena) {
		Scoreboard scoreboard = server.getScoreboard();
		if (hidesFreeForAll(arena)) {
			PlayerTeam hidden = scoreboard.getPlayerTeam(hiddenName(arena));
			if (hidden == null) hidden = scoreboard.addPlayerTeam(hiddenName(arena));
			hidden.setNameTagVisibility(Team.Visibility.NEVER);
			hidden.setAllowFriendlyFire(true);
			hidden.setSeeFriendlyInvisibles(false);
		}
		if (arena.preset.hordeOn()) {
			PlayerTeam horde = scoreboard.getPlayerTeam(hordeName(arena));
			if (horde == null) horde = scoreboard.addPlayerTeam(hordeName(arena));
			horde.setDisplayName(Component.literal("The horde"));
			horde.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.DARK_GREEN));
			horde.setPlayerPrefix(Component.literal("[Zombie] ").withColor(0x3F8F3F));
			horde.setAllowFriendlyFire(false);
			horde.setSeeFriendlyInvisibles(true);
		}
		if (!arena.preset.teamsOn()) return;
		Team.Visibility names = switch (arena.preset.nameTags) {
			case SHOWN -> Team.Visibility.ALWAYS;
			case TEAMMATES -> Team.Visibility.HIDE_FOR_OTHER_TEAMS;
			case HIDDEN -> Team.Visibility.NEVER;
		};
		for (int i = 0; i < arena.preset.teams; i++) {
			String name = teamName(arena, i);
			PlayerTeam team = scoreboard.getPlayerTeam(name);
			if (team == null) team = scoreboard.addPlayerTeam(name);
			TeamColors.Colour colour = TeamColors.of(i);
			team.setDisplayName(Component.literal(colour.name()));
			team.setColor(java.util.Optional.of(colour.team()));
			team.setPlayerPrefix(Component.literal("[" + colour.name() + "] ").withColor(colour.team().rgb()));
			team.setAllowFriendlyFire(arena.preset.friendlyFire);
			team.setSeeFriendlyInvisibles(true);
			team.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
			team.setNameTagVisibility(names);
		}
	}

	public static void remove(MinecraftServer server, Arena arena) {
		Scoreboard scoreboard = server.getScoreboard();
		for (int i = 0; i < TeamColors.MAX; i++) {
			PlayerTeam team = scoreboard.getPlayerTeam(teamName(arena, i));
			if (team != null) scoreboard.removePlayerTeam(team);
		}
		PlayerTeam hidden = scoreboard.getPlayerTeam(hiddenName(arena));
		if (hidden != null) scoreboard.removePlayerTeam(hidden);
		PlayerTeam horde = scoreboard.getPlayerTeam(hordeName(arena));
		if (horde != null) scoreboard.removePlayerTeam(horde);
	}

	/** Whether a member may join this team and keep the teams even: no more on it than on the smallest, not counting them. */
	public static boolean open(Arena arena, int team, Arena.Member member) {
		int smallest = Integer.MAX_VALUE;
		int there = 0;
		for (int i = 0; i < arena.preset.teams; i++) {
			int size = 0;
			for (Arena.Member other : arena.members.values()) if (other != member && other.inside && other.team == i) size++;
			smallest = Math.min(smallest, size);
			if (i == team) there = size;
		}
		return there <= smallest;
	}

	/** The team with the fewest players inside, the lowest-numbered on a tie. */
	public static int smallest(Arena arena) {
		int best = 0;
		for (int i = 1; i < arena.preset.teams; i++) {
			if (arena.teamSize(i) < arena.teamSize(best)) best = i;
		}
		return best;
	}

	/** On a team, remembering the one they were on outside, the first time only. */
	public static void put(ServerPlayer player, Arena arena, Arena.Member member, int team) {
		member.team = team;
		join(player, player.level().getServer().getScoreboard().getPlayerTeam(teamName(arena, team)));
	}

	/** Onto the horde's team, green-named, whatever team they fought on. */
	public static void horde(ServerPlayer player, Arena arena) {
		PlayerTeam horde = player.level().getServer().getScoreboard().getPlayerTeam(hordeName(arena));
		if (horde == null) {
			create(player.level().getServer(), arena);
			horde = player.level().getServer().getScoreboard().getPlayerTeam(hordeName(arena));
		}
		join(player, horde);
	}

	/** Into a free-for-all arena's nameless crowd, where its names are hidden; nothing otherwise. */
	public static void hide(ServerPlayer player, Arena arena) {
		if (hidesFreeForAll(arena)) join(player, player.level().getServer().getScoreboard().getPlayerTeam(hiddenName(arena)));
	}

	private static void join(ServerPlayer player, @Nullable PlayerTeam scoreboardTeam) {
		if (scoreboardTeam == null) return;
		Scoreboard scoreboard = player.level().getServer().getScoreboard();
		String name = player.getScoreboardName();
		PlayerTeam current = scoreboard.getPlayersTeam(name);
		Visit visit = Visit.of(player);
		if (visit != null && visit.team().isEmpty() && current != null && !current.getName().startsWith("pvpd_")) {
			Visit.set(player, visit.withTeam(current.getName()));
		}
		scoreboard.addPlayerToTeam(name, scoreboardTeam);
	}

	/** Off the arena's team and back on whatever they were on before, if it is still there. */
	public static void restore(ServerPlayer player, @Nullable String previous) {
		Scoreboard scoreboard = player.level().getServer().getScoreboard();
		String name = player.getScoreboardName();
		PlayerTeam current = scoreboard.getPlayersTeam(name);
		if (current != null && current.getName().startsWith("pvpd_")) scoreboard.removePlayerFromTeam(name, current);
		if (previous != null) {
			PlayerTeam before = scoreboard.getPlayerTeam(previous);
			if (before != null) scoreboard.addPlayerToTeam(name, before);
		}
	}
}
