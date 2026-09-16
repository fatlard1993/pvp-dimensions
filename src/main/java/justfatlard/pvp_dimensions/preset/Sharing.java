package justfatlard.pvp_dimensions.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Presets carried between servers: exported to a file in {@code config/pvp-dimensions/shared},
 * copied to the other server's, and imported there from the menu or a command.
 *
 * <p>A file says which mods its game needs: every item, block, mob, biome and enchantment it
 * names that isn't the game's own, and Pinata where it has pinatas. A server missing any of them
 * says which and won't import it, since a kit or a prize missing its modded half would quietly be
 * a different game. A preset on saved ground takes the ground with it, in a folder beside the file.
 */
public final class Sharing {
	private Sharing() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Pattern NAMED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	/** Namespaces every server has: the game's, and this mod's own mob kinds. */
	private static final Set<String> BUILT_IN = Set.of("minecraft", "pvp-dimensions-justfatlard");
	private static final String TERRAIN_SUFFIX = ".terrain";

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("pvp-dimensions").resolve("shared");
	}

	/** The mods a preset needs beyond the game, by the namespaces of everything it names. */
	public static Set<String> needs(Preset preset) {
		Set<String> mods = new TreeSet<>();
		collect(Presets.write(preset), mods);
		if (preset.pinata != Preset.PinataMode.OFF) mods.add("pinata");
		mods.removeAll(BUILT_IN);
		return mods;
	}

	private static void collect(JsonElement element, Set<String> mods) {
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				namespace(entry.getKey(), mods);
				collect(entry.getValue(), mods);
			}
		} else if (element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) collect(item, mods);
		} else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			namespace(element.getAsString(), mods);
		}
	}

	private static void namespace(String text, Set<String> mods) {
		if (!NAMED.matcher(text).matches()) return;
		mods.add(text.substring(0, text.indexOf(':')));
	}

	/**
	 * The preset to a file named for its id, with what it needs written on the front, and its
	 * saved ground beside it where it has some. What to tell whoever asked.
	 */
	public static String export(String id, Preset preset) {
		Set<String> mods = needs(preset);
		JsonObject file = new JsonObject();
		file.addProperty("shared_preset", 1);
		file.addProperty("name", preset.name);
		file.addProperty("made_with", "Minecraft " + SharedConstants.getCurrentVersion().name() + ", PvP Dimensions "
			+ FabricLoader.getInstance().getModContainer(PvpDimensions.MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		JsonArray needed = new JsonArray();
		mods.forEach(needed::add);
		file.add("needs", needed);
		boolean terrain = preset.source == Preset.Source.SAVED && !preset.saved.isEmpty();
		if (terrain) file.addProperty("terrain", preset.saved);
		file.add("preset", Presets.write(preset));
		try {
			Files.createDirectories(folder());
			Files.writeString(folder().resolve(id + ".json"), GSON.toJson(file), StandardCharsets.UTF_8);
			if (terrain) {
				Path from = SavedTerrains.folder(preset.saved);
				if (from != null && Files.isDirectory(from)) copyTree(from, folder().resolve(id + TERRAIN_SUFFIX));
			}
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Preset {} could not be exported", id, e);
			return "Couldn't write the file: " + e.getMessage();
		}
		return "Exported to config/pvp-dimensions/shared/" + id + ".json" + (terrain ? ", with its ground beside it" : "")
			+ ". Copy it to the other server's shared folder and import it there"
			+ (mods.isEmpty() ? "" : ". It needs " + String.join(", ", mods));
	}

	/** A file in the shared folder, and whether this server can take it. */
	public record Offer(String file, String name, String icon, List<String> missing, @Nullable String problem) {
		public boolean ready() {
			return missing.isEmpty() && problem == null;
		}

		public String describe() {
			if (problem != null) return problem;
			if (!missing.isEmpty()) return "Needs " + String.join(", ", missing) + ", which this server doesn't have";
			return "Ready to import";
		}
	}

	/** Everything waiting in the shared folder, by name. */
	public static List<Offer> offers(MinecraftServer server) {
		List<Offer> offers = new ArrayList<>();
		Set<String> here = namespaces(server);
		try {
			Files.createDirectories(folder());
			try (Stream<Path> files = Files.list(folder())) {
				for (Path path : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) offers.add(offer(path, here));
			}
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("The shared folder could not be listed", e);
		}
		return offers;
	}

	private static Offer offer(Path path, Set<String> here) {
		String file = path.getFileName().toString().replaceFirst("\\.json$", "");
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!json.has("preset") || !json.get("preset").isJsonObject()) return new Offer(file, file, "minecraft:paper", List.of(), "Not a shared preset");
			JsonObject preset = json.getAsJsonObject("preset");
			List<String> missing = new ArrayList<>();
			if (json.has("needs") && json.get("needs").isJsonArray()) {
				for (JsonElement mod : json.getAsJsonArray("needs")) {
					if (!here.contains(mod.getAsString())) missing.add(mod.getAsString());
				}
			}
			String name = json.has("name") ? json.get("name").getAsString() : file;
			String icon = preset.has("icon") ? preset.get("icon").getAsString() : "minecraft:paper";
			String iconSpace = icon.contains(":") ? icon.substring(0, icon.indexOf(':')) : "minecraft";
			return new Offer(file, name, here.contains(iconSpace) ? icon : "minecraft:paper", missing, null);
		} catch (Exception e) {
			return new Offer(file, file, "minecraft:paper", List.of(), "Can't be read: " + e.getMessage());
		}
	}

	/**
	 * A shared file made a preset of this server's, its ground brought in with it: the new
	 * preset's id, or null with {@code told} saying why not.
	 */
	public static @Nullable String importFile(MinecraftServer server, String file, List<String> told) {
		Path path = folder().resolve(file + ".json");
		if (!Files.isRegularFile(path) || !path.normalize().startsWith(folder().normalize())) {
			told.add("There's no " + file + ".json in config/pvp-dimensions/shared");
			return null;
		}
		Offer offer = offer(path, namespaces(server));
		if (!offer.ready()) {
			told.add(offer.name() + ": " + offer.describe());
			return null;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			Preset preset = Presets.read(json.getAsJsonObject("preset"));
			if (json.has("terrain")) {
				String terrain = json.get("terrain").getAsString();
				Path ground = folder().resolve(file + TERRAIN_SUFFIX);
				if (!SavedTerrains.names().contains(terrain)) {
					Path into = SavedTerrains.folder(terrain);
					if (Files.isDirectory(ground) && into != null) {
						copyTree(ground, into);
						SavedTerrains.load(server);
					} else {
						told.add("Its saved ground, " + terrain + ", didn't come with it; save or copy some ground by that name before starting it");
					}
				}
			}
			String id = Presets.save(null, preset);
			told.add("Imported " + preset.name + " as a new preset");
			return id;
		} catch (Exception e) {
			told.add("Couldn't import " + file + ": " + e.getMessage());
			return null;
		}
	}

	/** Every namespace this server has anything in: its mods, and whatever its registries hold. */
	private static Set<String> namespaces(MinecraftServer server) {
		Set<String> here = new TreeSet<>(BUILT_IN);
		FabricLoader.getInstance().getAllMods().forEach(mod -> here.add(mod.getMetadata().getId()));
		for (Registry<?> registry : List.<Registry<?>>of(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK, BuiltInRegistries.ENTITY_TYPE,
				BuiltInRegistries.DATA_COMPONENT_TYPE, BuiltInRegistries.MOB_EFFECT, BuiltInRegistries.POTION)) {
			for (Identifier key : registry.keySet()) here.add(key.getNamespace());
		}
		server.registryAccess().lookup(Registries.BIOME).ifPresent(biomes -> biomes.keySet().forEach(key -> here.add(key.getNamespace())));
		server.registryAccess().lookup(Registries.ENCHANTMENT).ifPresent(all -> all.keySet().forEach(key -> here.add(key.getNamespace())));
		return here;
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path source : walk.toList()) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) Files.createDirectories(target);
				else Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}
}
