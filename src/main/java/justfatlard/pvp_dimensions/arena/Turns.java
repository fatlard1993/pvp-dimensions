package justfatlard.pvp_dimensions.arena;

/**
 * Whose turn it is, for the games the sides take in turns: battleship, guess who, connect four,
 * memory. The turn goes round the sides in order, past any with nobody there to take it, and
 * passes on from a side that sits on it too long.
 *
 * <p>Kept with the game it times, so it is lost with it over a restart and starts again.
 */
final class Turns {
	/** Why a turn moved on its own. */
	enum Passed { NO, NOBODY_THERE, TOO_LONG }

	private final long limitMillis;
	int side;
	long since = System.currentTimeMillis();

	Turns(long limitMillis, int first) {
		this.limitMillis = limitMillis;
		this.side = first;
	}

	/**
	 * The turn to this side, or round from it to the first with somebody there. Where no side has
	 * anybody, it stays with this one.
	 *
	 * @return whether whoever has it now is there to take it
	 */
	boolean to(Arena arena, int wanted) {
		int teams = Math.max(1, arena.preset.teams);
		since = System.currentTimeMillis();
		for (int step = 0; step < teams; step++) {
			int team = Math.floorMod(wanted + step, teams);
			if (!present(arena, team)) continue;
			side = team;
			return true;
		}
		side = Math.floorMod(wanted, teams);
		return false;
	}

	/** The turn on to the next side round. */
	boolean pass(Arena arena) {
		return to(arena, side + 1);
	}

	/**
	 * Every half second: the turn taken from a side with nobody left to take it, or one that has
	 * had it too long, where another side is there to have it instead.
	 */
	Passed check(Arena arena, long now) {
		if (!anyoneElse(arena)) return Passed.NO;
		if (!present(arena, side)) {
			pass(arena);
			return Passed.NOBODY_THERE;
		}
		if (now - since < limitMillis) return Passed.NO;
		pass(arena);
		return Passed.TOO_LONG;
	}

	/** The time left on this turn, in whole seconds. */
	int secondsLeft(long now) {
		return (int) Math.max(0, (limitMillis - (now - since) + 999) / 1000);
	}

	private boolean anyoneElse(Arena arena) {
		for (Arena.Member member : arena.fighting()) if (member.team != side) return true;
		return false;
	}

	static boolean present(Arena arena, int team) {
		for (Arena.Member member : arena.fighting()) if (member.team == team) return true;
		return false;
	}
}
