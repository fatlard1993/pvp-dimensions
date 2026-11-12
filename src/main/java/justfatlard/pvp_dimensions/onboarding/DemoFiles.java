package justfatlard.pvp_dimensions.onboarding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.api.DemoHook;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Needs;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Every installed mod's demo, read out of its own jar.
 *
 * <p>A demo is a preset file as PvP Dimensions writes one, with a {@code demo} block added, at
 * {@code pvp-dimensions/demo.json} in the mod's resources; its saved ground, where it has any, is
 * the {@code .terrain} folder the preset was saved with, copied beside it as
 * {@code pvp-dimensions/demo.terrain}. So a demo is authored with the tools that make arenas: build
 * it on an arena's ground, save the terrain, save a preset on it, and copy the pair across.
 *
 * <pre>{@code
 * {
 *   "demo": {
 *     "name": "Amethyst Door",
 *     "icon": "amethyst-door-justfatlard:amethyst_door",
 *     "about": ["Lock a door to everyone but the people you build with."],
 *     "finish": true
 *   },
 *   "pvp_preset": 1,
 *   "needs": ["amethyst-door-justfatlard"],
 *   "terrain": "door_demo",
 *   "preset": { ... }
 * }
 * }</pre>
 *
 * <p>A file that will not read is logged and skipped: one mod's bad demo is not a reason for the
 * others to go missing.
 */
public final class DemoFiles {
	private DemoFiles() {}

	static final String FILE = "pvp-dimensions/demo.json";
	static final String TERRAIN = "pvp-dimensions/demo.terrain";
	/** Inside an installed copy of a demo's ground: the mod version it was copied from. */
	private static final String STAMP = "demo_version.txt";

	static List<Demo> read(MinecraftServer server) {
		Map<String, DemoHook> hooks = hooks();
		Set<String> here = Needs.namespaces(server);
		List<Demo> out = new ArrayList<>();
		boolean installed = false;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			Optional<Path> found = mod.findPath(FILE);
			if (found.isEmpty()) continue;
			String id = mod.getMetadata().getId();
			try {
				JsonObject json = JsonParser.parseString(Files.readString(found.get(), StandardCharsets.UTF_8)).getAsJsonObject();
				List<String> missing = new ArrayList<>();
				if (json.has("needs") && json.get("needs").isJsonArray()) {
					for (JsonElement need : json.getAsJsonArray("needs")) {
						if (!here.contains(need.getAsString())) missing.add(need.getAsString());
					}
				}
				if (!missing.isEmpty()) {
					PvpDimensions.LOGGER.warn("{}'s demo needs {}, which this server hasn't got; left out", id, missing);
					continue;
				}
				Preset preset = tame(Presets.readFile(json));
				if (preset.source == Preset.Source.SAVED) {
					String ground = groundName(id);
					Optional<Path> from = mod.findPath(TERRAIN);
					if (from.isEmpty()) throw new IOException("its preset is on saved ground and " + TERRAIN + " is missing");
					installed |= install(from.get(), ground, mod.getMetadata().getVersion().getFriendlyString());
					preset.saved = ground;
				}
				JsonObject about = json.has("demo") && json.get("demo").isJsonObject() ? json.getAsJsonObject("demo") : new JsonObject();
				String name = string(about, "name", mod.getMetadata().getName());
				preset.name = name;
				List<String> lines = lines(about.get("about"));
				if (lines.isEmpty() && !mod.getMetadata().getDescription().isBlank()) lines = List.of(mod.getMetadata().getDescription());
				out.add(new Demo(id, name, string(about, "icon", preset.icon), lines,
					about.has("finish") && about.get("finish").getAsBoolean(), preset, hooks.get(id)));
			} catch (Exception e) {
				PvpDimensions.LOGGER.error("{}'s demo could not be read and was left out", id, e);
			}
		}
		if (installed) SavedTerrains.load(server);
		out.sort(java.util.Comparator.comparing(demo -> demo.name().toLowerCase(java.util.Locale.ROOT)));
		return List.copyOf(out);
	}

	/**
	 * What any preset needs changing to be a demo, whatever its author left in it: played alone,
	 * straight onto the ground, always leavable, and no way to carry anything home. A demo anyone
	 * can start over and over must never be a way to make items.
	 */
	public static Preset tame(Preset preset) {
		preset.waitingRoom = false;
		// Taken in once it is live, so the one player is a late arrival; nobody else is invited.
		preset.lateJoin = Preset.LateJoin.ALLOW;
		preset.endWhenOneSideLeft = false;
		preset.exitCommand = true;
		preset.traversal = Preset.Traversal.ISOLATED;
		preset.rewards = new ItemList();
		preset.prizes = new ArrayList<>();
		preset.winnersKeepPack = false;
		preset.entryFee = new ItemList();
		preset.forUsers = false;
		return preset;
	}

	/** Where a mod's demo ground is kept among the saved terrains; its own name, so no admin's map is ever overwritten. */
	public static String groundName(String modId) {
		return SavedTerrains.clean("demo-" + modId);
	}

	/**
	 * The ground out of the jar and among the saved terrains, wherever the copy there is missing or
	 * from another version of the mod: a mod update that rebuilt its demo replaces it.
	 *
	 * @return whether anything was copied
	 */
	public static boolean install(Path from, String ground, String version) throws IOException {
		Path into = SavedTerrains.folder(ground);
		if (into == null) throw new IOException("the saved terrains are not loaded yet");
		Path stamp = into.resolve(STAMP);
		if (Files.exists(stamp) && Files.readString(stamp, StandardCharsets.UTF_8).strip().equals(version)) return false;
		if (Files.exists(into)) {
			try (var old = Files.walk(into)) {
				for (Path path : old.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
		Needs.copyTree(from, into);
		Files.writeString(stamp, version, StandardCharsets.UTF_8);
		return true;
	}

	/** Each mod's {@link DemoHook}, by the mod that declared it. */
	private static Map<String, DemoHook> hooks() {
		Map<String, DemoHook> out = new HashMap<>();
		for (EntrypointContainer<DemoHook> container : FabricLoader.getInstance().getEntrypointContainers(DemoHook.ENTRYPOINT, DemoHook.class)) {
			String id = container.getProvider().getMetadata().getId();
			try {
				out.put(id, container.getEntrypoint());
			} catch (Throwable e) {
				PvpDimensions.LOGGER.error("{}'s demo hook could not be loaded; its demo plays without it", id, e);
			}
		}
		return out;
	}

	private static String string(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	private static List<String> lines(@Nullable JsonElement element) {
		List<String> out = new ArrayList<>();
		if (element == null || element.isJsonNull()) return out;
		if (element.isJsonArray()) {
			for (JsonElement each : element.getAsJsonArray()) if (each.isJsonPrimitive() && !each.getAsString().isBlank()) out.add(each.getAsString().strip());
		} else if (element.isJsonPrimitive() && !element.getAsString().isBlank()) {
			out.add(element.getAsString().strip());
		}
		return out;
	}
}
