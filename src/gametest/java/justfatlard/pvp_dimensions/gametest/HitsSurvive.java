package justfatlard.pvp_dimensions.gametest;

import com.google.gson.JsonObject;
import justfatlard.pvp_dimensions.arena.Hits;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A hits preset says the same thing after a save and a load.
 *
 * <p>This is here because the field beside it did not. {@code hitTarget} was added, offered in the
 * menu and read back on load, but never written - so every round used the default, every edit was
 * lost the moment the vault was saved, and the only symptom was a snowball fight that ended too
 * soon. Nothing failed, which is why it survived so long: a write that is missing and a value that
 * is merely unchanged look identical from inside the game.
 *
 * <p>So both halves of the round trip are asserted for both fields, against a value that is not
 * the default - a test that sets a field to what it already was would pass with the write still
 * missing.
 */
public final class HitsSurvive implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			Preset preset = new Preset();
			preset.goal = Preset.Goal.HITS;
			preset.hitKind = Preset.HitKind.MELEE;
			preset.hitTarget = 75;

			if (preset.hitKind == new Preset().hitKind || preset.hitTarget == new Preset().hitTarget) {
				throw new AssertionError("the test is asserting against the defaults and would pass either way");
			}

			JsonObject written = Presets.write(preset);
			if (!written.has("hit_kind")) throw new AssertionError("hit_kind was never written");
			if (!written.has("hit_target")) throw new AssertionError("hit_target was never written");

			Preset back = Presets.read(written);
			if (back.hitKind != Preset.HitKind.MELEE) {
				throw new AssertionError("hit_kind came back as " + back.hitKind);
			}
			if (back.hitTarget != 75) {
				throw new AssertionError("hit_target came back as " + back.hitTarget);
			}

			// And the gate the hooks ask: each kind counts its own, either counts both.
			check(melee(), Preset.HitKind.MELEE, true);
			check(melee(), Preset.HitKind.THROWN, false);
			check(thrown(), Preset.HitKind.THROWN, true);
			check(thrown(), Preset.HitKind.MELEE, false);
			check(any(), Preset.HitKind.MELEE, true);
			check(any(), Preset.HitKind.THROWN, true);
		});
	}

	private static Preset melee() {
		return of(Preset.HitKind.MELEE);
	}

	private static Preset thrown() {
		return of(Preset.HitKind.THROWN);
	}

	private static Preset any() {
		return of(Preset.HitKind.ANY);
	}

	private static Preset of(Preset.HitKind kind) {
		Preset preset = new Preset();
		preset.goal = Preset.Goal.HITS;
		preset.hitKind = kind;
		return preset;
	}

	private static void check(Preset preset, Preset.HitKind asked, boolean expected) {
		if (Hits.counts(preset, asked) != expected) {
			throw new AssertionError("a " + preset.hitKind + " match " + (expected ? "refused " : "counted ") + asked);
		}
	}
}
