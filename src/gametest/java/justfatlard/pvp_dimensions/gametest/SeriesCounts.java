package justfatlard.pvp_dimensions.gametest;

import com.google.gson.JsonObject;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Rounds;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A series stops when it is decided, and not before.
 *
 * <p>The arithmetic is the part of rounds with no symptom when it is wrong. A best of three that
 * plays a third round at two-nil merely wastes everybody's evening; one that stops at one-nil
 * hands the match to whoever won first. Neither throws, and both look like a working feature from
 * inside the game, so the counting is asserted here rather than watched for.
 *
 * <p>The round-trip is checked for the same reason the hits one is: a preset field that is never
 * written is invisible until somebody notices their setting did not stick.
 */
public final class SeriesCounts implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			roundTrip();
			oneRoundIsNotASeries();
			bestOfThreeStopsAtTwoNil();
			bestOfThreePlaysOnAtOneNil();
			aLeadEqualToWhatIsLeftIsNotDecided();
			levelSeriesHasNoWinner();
		});
	}

	private static void roundTrip() {
		Preset preset = new Preset();
		preset.rounds = 5;
		if (preset.rounds == new Preset().rounds) throw new AssertionError("asserting against the default");
		JsonObject written = Presets.write(preset);
		if (!written.has("rounds")) throw new AssertionError("rounds was never written");
		if (Presets.read(written).rounds != 5) throw new AssertionError("rounds came back as " + Presets.read(written).rounds);
	}

	/** One round is the old behaviour: no series, and over the moment it is won. */
	private static void oneRoundIsNotASeries() {
		Arena arena = arena(1, 2);
		if (Rounds.playing(arena)) throw new AssertionError("a one-round match called itself a series");
		if (!Rounds.over(arena)) throw new AssertionError("a one-round match wanted a second round");
		if (!Rounds.tally(arena).isEmpty()) throw new AssertionError("a one-round match showed a tally");
	}

	private static void bestOfThreeStopsAtTwoNil() {
		Arena arena = arena(3, 2);
		Rounds.teamWon(arena, 0);
		arena.round = 2;
		Rounds.teamWon(arena, 0);
		// Two-nil with one to play: the third cannot change it.
		if (!Rounds.over(arena)) throw new AssertionError("a decided best of three wanted a dead rubber");
		if (!"Red".equals(Rounds.winner(arena))) throw new AssertionError("the series went to " + Rounds.winner(arena));
	}

	private static void bestOfThreePlaysOnAtOneNil() {
		Arena arena = arena(3, 2);
		Rounds.teamWon(arena, 0);
		// One-nil with two to play is still anybody's.
		if (Rounds.over(arena)) throw new AssertionError("a best of three ended at one-nil");
	}

	/**
	 * One-nil with exactly one round left: the lead equals what is left, so the last round can
	 * still level it and has to be played.
	 *
	 * <p>This is the only case that tells the rule from its off-by-one. Everywhere else the lead
	 * is either clearly bigger than what remains or clearly smaller, and both readings agree; here
	 * they differ, and the wrong one ends a best of three at one-nil with a round in hand.
	 */
	private static void aLeadEqualToWhatIsLeftIsNotDecided() {
		Arena arena = arena(3, 2);
		arena.round = 2;
		Rounds.teamWon(arena, 0);
		if (Rounds.over(arena)) {
			throw new AssertionError("a best of three stopped at one-nil with a round still to play");
		}
	}

	private static void levelSeriesHasNoWinner() {
		Arena arena = arena(2, 2);
		Rounds.teamWon(arena, 0);
		arena.round = 2;
		Rounds.teamWon(arena, 1);
		if (!Rounds.over(arena)) throw new AssertionError("a series past its last round wanted another");
		if (Rounds.winner(arena) != null) throw new AssertionError("a level series crowned " + Rounds.winner(arena));
		if (!Rounds.result(arena).startsWith("The series ends level")) {
			throw new AssertionError("a level series read as: " + Rounds.result(arena));
		}
	}

	/** An arena with nothing in it but the preset the counting reads. */
	private static Arena arena(int rounds, int teams) {
		Preset preset = new Preset();
		preset.rounds = rounds;
		preset.teams = teams;
		preset.mode = Preset.Mode.TEAMS;
		return new Arena("test", 1, "test", preset, java.util.UUID.randomUUID(), "tester",
			net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0, 1, 64, 64, 80, 70, 0L);
	}
}
