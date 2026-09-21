package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A match played more than once: rounds, and the series they add up to.
 *
 * <p>An arena used to be one game. Winning set {@code closesAt}, the celebration ran, and the
 * arena tore itself down. A series puts a fork in that one place: the round is scored the way the
 * goal always scored it, and then the arena either ends as before or wipes the round and plays
 * again with the same people, the same teams and the same plot.
 *
 * <p>What a round wipes is everything a goal counts - kills, points, lives, flags, the hill's
 * clock. What it keeps is everything about the people: who is here, which team they are on, what
 * they paid to get in, the kit they chose, and the rounds they have already won. Paying once for a
 * series and being charged again in round two would be a bug with a receipt.
 *
 * <p>Rounds stop as soon as the rest cannot change the answer, so a best of three ends at two-nil
 * instead of playing a dead rubber. A series that runs out level is a draw and nobody is crowned:
 * an arena with no way to break a tie should say so rather than invent a winner.
 *
 * <p>One thing a round cannot wipe is the ground. There is no terrain regeneration here, so a
 * second round is played on the map the first one left - craters, towers and all. That is
 * survivable for every goal that counts something (kills, hits, flags, banks) and fatal for the
 * one that reads the world instead: a colour takeover would open round two with round one's paint
 * already down and end it instantly. {@link #stripPaint} is the answer to that particular case,
 * and it is the only place here that touches blocks.
 */
public final class Rounds {
	private Rounds() {}

	/** How deep to chase a column of stacked terracotta before leaving it alone. */
	private static final int PAINT_DEPTH = 8;

	/** Whether this arena is playing a series at all. */
	public static boolean playing(Arena arena) {
		return arena.preset.rounds > 1;
	}

	/** Rounds still to play after this one. */
	public static int remaining(Arena arena) {
		return Math.max(0, arena.preset.rounds - arena.round);
	}

	/** "Round 2 of 3", or empty for a one-round match. */
	public static String standing(Arena arena) {
		return playing(arena) ? "Round " + arena.round + " of " + arena.preset.rounds : "";
	}

	// --- Scoring a round ---

	public static void teamWon(Arena arena, int team) {
		if (!playing(arena)) return;
		arena.teamRounds.merge(team, 1, Integer::sum);
		for (Arena.Member member : arena.members.values()) {
			if (member.team == team) member.roundsWon++;
		}
	}

	public static void playerWon(Arena arena, Arena.Member winner) {
		if (!playing(arena)) return;
		winner.roundsWon++;
	}

	/** A round won by everyone together, the way a co-op hunt is won. */
	public static void everyoneWon(Arena arena) {
		if (!playing(arena)) return;
		for (Arena.Member member : arena.members.values()) {
			if (!member.zombie) member.roundsWon++;
		}
	}

	// --- Where the series stands ---

	/**
	 * Whether the arena should close rather than play on: the last round is done, or the lead is
	 * already bigger than what is left to play for.
	 */
	public static boolean over(Arena arena) {
		if (!playing(arena)) return true;
		if (arena.round >= arena.preset.rounds) return true;
		int[] top = topTwo(arena);
		return top[0] - top[1] > remaining(arena);
	}

	/** The best and second-best rounds-won in the series, for deciding whether it is still live. */
	private static int[] topTwo(Arena arena) {
		List<Integer> counts = new ArrayList<>();
		if (arena.preset.teamsOn()) {
			for (int team = 0; team < arena.preset.teams; team++) counts.add(arena.teamRounds.getOrDefault(team, 0));
		} else {
			for (Arena.Member member : arena.members.values()) counts.add(member.roundsWon);
		}
		counts.sort(java.util.Comparator.reverseOrder());
		int best = counts.isEmpty() ? 0 : counts.get(0);
		int second = counts.size() > 1 ? counts.get(1) : 0;
		return new int[] {best, second};
	}

	/** The series tally as one line: "Red 2 · Blue 1", or the players who have won a round. */
	public static String tally(Arena arena) {
		if (!playing(arena)) return "";
		StringBuilder line = new StringBuilder();
		if (arena.preset.teamsOn()) {
			for (int team = 0; team < arena.preset.teams; team++) {
				if (team > 0) line.append(" · ");
				line.append(TeamColors.of(team).name()).append(" ").append(arena.teamRounds.getOrDefault(team, 0));
			}
			return line.toString();
		}
		List<Arena.Member> ranked = new ArrayList<>(arena.members.values());
		ranked.removeIf(member -> member.roundsWon == 0);
		ranked.sort(java.util.Comparator.comparingInt((Arena.Member member) -> member.roundsWon).reversed());
		if (ranked.isEmpty()) return "Nobody has won a round yet";
		for (int i = 0; i < ranked.size() && i < 4; i++) {
			if (i > 0) line.append(" · ");
			line.append(ranked.get(i).name).append(" ").append(ranked.get(i).roundsWon);
		}
		return line.toString();
	}

	/**
	 * Who took the series, for the words at the end: a team's name, a player's name, or null where
	 * it finished level and nobody did.
	 */
	public static @Nullable String winner(Arena arena) {
		if (!playing(arena)) return null;
		if (arena.preset.teamsOn()) {
			int best = -1;
			int bestTeam = -1;
			boolean tie = false;
			for (int team = 0; team < arena.preset.teams; team++) {
				int won = arena.teamRounds.getOrDefault(team, 0);
				if (won > best) {
					best = won;
					bestTeam = team;
					tie = false;
				} else if (won == best) {
					tie = true;
				}
			}
			return bestTeam < 0 || tie || best <= 0 ? null : TeamColors.of(bestTeam).name();
		}
		Arena.Member best = null;
		boolean tie = false;
		for (Arena.Member member : arena.members.values()) {
			if (best == null || member.roundsWon > best.roundsWon) {
				best = member;
				tie = false;
			} else if (member.roundsWon == best.roundsWon) {
				tie = true;
			}
		}
		return best == null || tie || best.roundsWon <= 0 ? null : best.name;
	}

	/** How a finished series reads: who took it and by what, or that it ended level. */
	public static String result(Arena arena) {
		if (!playing(arena)) return "";
		String who = winner(arena);
		String tally = tally(arena);
		return who == null ? "The series ends level: " + tally : who + " takes the series " + tally;
	}

	// --- Playing the next one ---

	/**
	 * Wipe the round and start the next one with the same people.
	 *
	 * <p>Goes back through {@code LOBBY} so {@code goLive} will run: it is the one path that
	 * builds the bases, starts the goal, hands out kits and sets everybody down, and a round that
	 * set itself up by hand would drift from it the first time either changed.
	 */
	public static void next(MinecraftServer server, Arena arena) {
		ServerLevel level = Arenas.level(server, arena);
		if (level == null || arena.phase == Arena.Phase.ENDED) return;

		arena.round++;
		wipeRound(server, arena, level);

		Arenas.tellInside(server, arena, standing(arena) + ". " + tally(arena));
		arena.phase = Arena.Phase.LOBBY;
		Arenas.goLive(server, arena);
		Arenas.vault(server).touch();
	}

	/** Everything a goal counted, put back to nothing; everything about the people, left alone. */
	private static void wipeRound(MinecraftServer server, Arena arena, ServerLevel level) {
		for (Arena.Member member : arena.members.values()) {
			member.kills = 0;
			member.deaths = 0;
			member.streak = 0;
			member.mobKills = 0;
			member.lastKiller = null;
			member.lives = -1;
			member.out = false;
			member.zombie = false;
			member.held = 0;
			member.points = 0;
		}

		arena.scores.clear();
		arena.flagNumbers.clear();
		arena.pools.clear();
		arena.fallen.clear();
		arena.winners.clear();
		arena.prize = -1;
		arena.firstBlood = false;
		arena.closesAt = 0;
		arena.modesChanged = 0;
		arena.modesWarned = 0;
		arena.marker = null;
		arena.markerMovesAt = 0;
		arena.waveIndex = -1;
		arena.waveNumber = 0;
		arena.nextWaveAt = 0;
		arena.waveStartedAt = 0;
		arena.wavesOver = false;
		arena.pinataIndex = 0;
		arena.nextPinataAt = 0;
		arena.pinatas.clear();
		arena.pinataMovesAt = 0;

		// The per-arena tallies these keep on the side, so the new round is not measured against
		// the last one's readings.
		Goals.forget(arena);
		Weather.forget(arena);
		Traps.forget(arena);
		Waves.forget(arena);
		Pinatas.clear(server, arena);

		if (arena.preset.activeGoal() == Preset.Goal.TAKEOVER) stripPaint(level, arena);

		// Everybody plays the next round: being out of lives, or one of the horde, was the last
		// round's news. The flags on the member are already back to nothing above; this takes the
		// horde's tag and its kit off the player, which is the half that lives on the body.
		for (Arena.Member member : arena.members.values()) {
			if (!member.inside) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			Horde.leave(player);
			// goLive equips everybody as though they were arriving, and arriving does not clear
			// what you are holding - there is nothing to clear the first time. A second round is
			// the first time that is wrong: without this, a best of seven hands out seven kits on
			// top of each other, plus whatever was looted on the way.
			//
			// Only where the arena took their own things off them at the door. Where it did not,
			// the inventory in their hands is their own, and emptying it between rounds would
			// destroy it.
			if (arena.preset.isolated()) player.getInventory().clearContent();
		}
	}

	/**
	 * Take the teams' terracotta off the ground.
	 *
	 * <p>A takeover is scored by reading the top block of every column, so the paint is the score.
	 * Left down, round two opens with round one already won. Removing it rather than replacing it
	 * with a guess at the original ground is deliberate: the paint was put on top of what was
	 * there, so taking it away uncovers it, and the mod has no record of what any particular
	 * column looked like before somebody stood on it.
	 */
	public static void stripPaint(ServerLevel level, Arena arena) {
		var footprint = arena.footprint();
		java.util.Set<Block> paint = new java.util.HashSet<>();
		for (int team = 0; team < arena.preset.teams; team++) paint.add(Goals.terracotta(team).getBlock());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = footprint.minX(); x < footprint.maxX(); x++) {
			for (int z = footprint.minZ(); z < footprint.maxZ(); z++) {
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				for (int depth = 0; depth < PAINT_DEPTH; depth++) {
					int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
					if (!paint.contains(level.getBlockState(pos.set(x, y, z)).getBlock())) break;
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
	}
}
