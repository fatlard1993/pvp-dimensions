package justfatlard.pvp_dimensions.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * What a preset needs beyond the game, and whether this server has it.
 *
 * <p>A preset names mods all over itself: every item, block, mob, biome and enchantment it uses
 * that isn't the game's own, and Pinata where it has pinatas. A server missing any of them can
 * still read the file, but a kit or a prize missing its modded half is quietly a different game,
 * so the preset is shown with what it wants and kept from being started.
 *
 * <p>This used to be the whole story of a separate shared folder, checked once as a preset was
 * imported out of it. There is no importing now - {@link Presets} reads the folder the presets
 * already live in - so what is left here is the question itself, asked of every preset each time
 * the folder changes.
 */
public final class Needs {
	private Needs() {}

	private static final Pattern NAMED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	/** Namespaces every server has: the game's, and this mod's own mob kinds. */
	private static final Set<String> BUILT_IN = Set.of("minecraft", "pvp-dimensions-justfatlard");

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

	/** Every namespace this server can actually resolve: its mods, and everything in its registries. */
	static Set<String> namespaces(MinecraftServer server) {
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

	static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path source : walk.toList()) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) Files.createDirectories(target);
				else Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}
}
