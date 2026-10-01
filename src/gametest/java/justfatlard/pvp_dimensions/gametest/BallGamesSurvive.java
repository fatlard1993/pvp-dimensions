package justfatlard.pvp_dimensions.gametest;

import com.google.gson.JsonObject;
import justfatlard.pvp_dimensions.arena.Hits;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A ball game's preset says the same thing after a save and a load, soccer is only soccer with
 * teams, and a ball hit is its own kind of hit.
 *
 * <p>Asserted against values that are not the defaults, for the reason {@link HitsSurvive} gives:
 * a write that is missing and a value left at its default look the same from inside the game.
 */
public final class BallGamesSurvive implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			Preset preset = new Preset();
			preset.mode = Preset.Mode.TEAMS;
			preset.goal = Preset.Goal.SOCCER;
			preset.goalTarget = 7;
			preset.ballBlock = "minecraft:packed_ice";
			preset.goalShape = Preset.GoalShape.LINE;
			preset.keepTarget = 7;
			preset.golfHoles = 9;
			preset.ballCount = 5;

			Preset fresh = new Preset();
			if (preset.goalTarget == fresh.goalTarget || preset.ballBlock.equals(fresh.ballBlock) || preset.goalShape == fresh.goalShape
				|| preset.keepTarget == fresh.keepTarget || preset.golfHoles == fresh.golfHoles || preset.ballCount == fresh.ballCount) {
				throw new AssertionError("the test is asserting against the defaults and would pass either way");
			}

			JsonObject written = Presets.write(preset);
			if (!written.has("goal_target")) throw new AssertionError("goal_target was never written");
			for (String key : new String[] {"ball", "goal_shape", "keep_target", "golf_holes", "ball_count"}) {
				if (!written.has(key)) throw new AssertionError(key + " was never written");
			}

			Preset back = Presets.read(written);
			if (back.goalTarget != 7) throw new AssertionError("goal_target came back as " + back.goalTarget);
			if (!back.ballBlock.equals("minecraft:packed_ice")) throw new AssertionError("ball came back as " + back.ballBlock);
			if (back.goalShape != Preset.GoalShape.LINE) throw new AssertionError("goal_shape came back as " + back.goalShape);
			if (back.keepTarget != 7) throw new AssertionError("keep_target came back as " + back.keepTarget);
			if (back.golfHoles != 9) throw new AssertionError("golf_holes came back as " + back.golfHoles);
			if (back.ballCount != 5) throw new AssertionError("ball_count came back as " + back.ballCount);
			if (back.activeGoal() != Preset.Goal.SOCCER) throw new AssertionError("a team soccer preset plays for " + back.activeGoal());

			// Two goals need two sides: without teams there is nobody to score for.
			back.mode = Preset.Mode.FFA;
			if (back.activeGoal() != Preset.Goal.TIME) throw new AssertionError("soccer without teams plays for " + back.activeGoal());

			// A ball hit is not a thrown one or a punch, and either of those is not a ball hit:
			// dodgeball is not won by an egg, and a snowball fight is not won by a ball.
			Preset dodge = new Preset();
			dodge.goal = Preset.Goal.HITS;
			dodge.hitKind = Preset.HitKind.BALL;
			if (!Hits.counts(dodge, Preset.HitKind.BALL)) throw new AssertionError("dodgeball refused a ball hit");
			if (Hits.counts(dodge, Preset.HitKind.THROWN)) throw new AssertionError("dodgeball counted a thrown hit");
			dodge.hitKind = Preset.HitKind.ANY;
			if (Hits.counts(dodge, Preset.HitKind.BALL)) throw new AssertionError("a thrown-or-melee match counted a ball hit");
		});
	}
}
