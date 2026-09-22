package justfatlard.pvp_dimensions.gametest;

import com.google.gson.JsonObject;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A preset file is readable in every shape one has ever been written in.
 *
 * <p>There were two formats and two folders: bare presets in the live folder, and the same preset
 * wrapped in a header in the shared one. Now there is one of each, and the wrapper is what gets
 * written - which means every file already on a server is in the old shape, and a server that
 * could not read them would look exactly like a server with no presets. Nothing throws; the list
 * is just empty, and the mod helpfully writes a fresh default into the folder beside the ones it
 * failed to read.
 *
 * <p>So both shapes are asserted, and the wrapper is asserted to carry what makes a file portable:
 * the format marker, the name and the mods it needs. That last one is the whole reason the header
 * exists, and it is invisible until somebody copies a preset to a server without the mods.
 */
public final class PresetFiles implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			wrappedIsWhatIsWritten();
			bareStillReads();
			wrappedReads();
		});
	}

	private static Preset made() {
		Preset preset = new Preset();
		preset.name = "Carried Over";
		preset.rounds = 3;
		preset.hitKind = Preset.HitKind.MELEE;
		return preset;
	}

	private static void wrappedIsWhatIsWritten() {
		JsonObject file = Presets.writeFile(made());
		if (!file.has("pvp_preset")) throw new AssertionError("the file has no format marker");
		if (!file.has("needs") || !file.get("needs").isJsonArray()) throw new AssertionError("the file does not say what it needs");
		if (!file.has("preset") || !file.get("preset").isJsonObject()) throw new AssertionError("the file holds no preset");
		if (!"Carried Over".equals(file.get("name").getAsString())) throw new AssertionError("the file is not named for its preset");

		Preset back = Presets.readFile(file);
		if (back.rounds != 3 || back.hitKind != Preset.HitKind.MELEE) {
			throw new AssertionError("a written file did not read back: rounds=" + back.rounds + " kind=" + back.hitKind);
		}
	}

	/** The shape every preset already on disk is in, before this server ever saves it again. */
	private static void bareStillReads() {
		JsonObject bare = Presets.write(made());
		if (bare.has("preset")) throw new AssertionError("a bare preset looks like a wrapped one; they cannot be told apart");
		Preset back = Presets.readFile(bare);
		if (back.rounds != 3) throw new AssertionError("an old bare preset file was not read");
		if (!"Carried Over".equals(back.name)) throw new AssertionError("an old bare preset lost its name");
	}

	/** The shape the old shared folder wrote, which is the same wrapper under a different marker. */
	private static void wrappedReads() {
		JsonObject old = new JsonObject();
		old.addProperty("shared_preset", 1);
		old.addProperty("name", "Carried Over");
		old.add("preset", Presets.write(made()));
		Preset back = Presets.readFile(old);
		if (back.rounds != 3) throw new AssertionError("an exported preset from the old shared folder was not read");
	}
}
