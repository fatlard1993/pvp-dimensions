package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A snowball fight: what counts is the hit, not the harm.
 *
 * <p>Every other way of winning an arena here is settled by damage of some kind. This one is not
 * settled by damage at all - a snowball does nothing to a player and is not supposed to, which is
 * exactly why a fight made of them is worth having. What is counted is the thrown thing landing,
 * and nothing about the thing itself: snowballs, eggs, a handful of poop, anything a player can
 * throw at somebody. The preset decides what they are given to throw, because ammunition is the
 * kit's business and never the goal's.
 *
 * <p>On a server running stackz that also solves the supply: a stack holds millions, so the kit
 * handing out one stack of snowballs is a fight nobody runs out of.
 */
public final class Hits {
	private Hits() {}

	/** Hits are counted on the member itself, the way kills are, so every board already shows them. */
	public static void landed(MinecraftServer server, Arena arena, ServerPlayer thrower, ServerPlayer hit) {
		if (arena.phase != Arena.Phase.LIVE || arena.closesAt > 0) return;
		if (thrower.getUUID().equals(hit.getUUID())) return;

		Arena.Member scorer = arena.member(thrower.getUUID());
		Arena.Member struck = arena.member(hit.getUUID());
		if (scorer == null || struck == null || !scorer.inside || !struck.inside) return;
		if (scorer.watching || struck.watching) return;
		// Teammates are not targets: a fight between two sides is not improved by pelting your own.
		if (arena.preset.teamsOn() && scorer.team >= 0 && scorer.team == struck.team) return;

		scorer.points++;

		// Both ends are told, because a snowball that does nothing needs saying: the thrower
		// cannot see a health bar move and the struck cannot feel it.
		// Played at each of them rather than at the hit, so the thrower hears their own landing
		// from across the arena: high for the one who threw it, low for the one wearing it.
		thrower.level().playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(),
			SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.6F, 1.6F);
		hit.level().playSound(null, hit.getX(), hit.getY(), hit.getZ(),
			SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.6F, 0.8F);
		Say.bar(thrower, scorer.points + (scorer.points == 1 ? " hit" : " hits")
			+ (arena.preset.hitTarget > 0 ? " of " + arena.preset.hitTarget : ""));

		int target = arena.preset.hitTarget;
		if (target <= 0 || scorer.points < target) {
			Arenas.vault(server).touch();
			return;
		}
		if (arena.preset.teamsOn() && scorer.team >= 0) {
			Goals.winTeam(server, arena, scorer.team, scorer.name + " landed the " + target + "th hit");
		} else {
			Goals.winPlayer(server, arena, scorer, target + " hits");
		}
	}

	/** Where the round stands for one player, for the bar and the board. */
	public static String status(Arena arena, Arena.Member member) {
		if (member == null) return "";
		int target = arena.preset.hitTarget;
		return member.points + (target > 0 ? " of " + target : "") + (member.points == 1 ? " hit" : " hits");
	}

	/**
	 * Time ran out: the most hits takes it, and a tie takes nothing.
	 *
	 * <p>Counted by team where there are teams, since a team fight is won by the side and not by
	 * whoever in it threw straightest.
	 */
	public static boolean timeUp(MinecraftServer server, Arena arena) {
		if (arena.preset.teamsOn()) {
			java.util.Map<Integer, Integer> byTeam = new java.util.HashMap<>();
			for (Arena.Member member : arena.members.values()) {
				if (member.team >= 0) byTeam.merge(member.team, member.points, Integer::sum);
			}
			int best = -1;
			int bestTeam = -1;
			boolean tie = false;
			for (var entry : byTeam.entrySet()) {
				if (entry.getValue() > best) {
					best = entry.getValue();
					bestTeam = entry.getKey();
					tie = false;
				} else if (entry.getValue() == best) {
					tie = true;
				}
			}
			if (bestTeam < 0 || tie || best <= 0) return false;
			Goals.winTeam(server, arena, bestTeam, "the most hits: " + best);
			return true;
		}

		Arena.Member best = null;
		boolean tie = false;
		for (Arena.Member member : arena.members.values()) {
			if (best == null || member.points > best.points) {
				best = member;
				tie = false;
			} else if (member.points == best.points) {
				tie = true;
			}
		}
		if (best == null || tie || best.points <= 0) return false;
		Goals.winPlayer(server, arena, best, "the most hits: " + best.points);
		return true;
	}

	/** Whether this arena is counting hits at all, for the projectile hook to ask before it looks further. */
	public static boolean counting(Arena arena) {
		return arena.preset.activeGoal() == Preset.Goal.HITS;
	}
}
