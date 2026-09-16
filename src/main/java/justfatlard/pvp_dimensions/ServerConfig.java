package justfatlard.pvp_dimensions;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The server's own limits, one value each for everybody: how many arenas at once, how many a
 * user may run, how long a waiting room waits. In {@code config/pvp-dimensions/server.properties}
 * and on the mod's page of the Pandorical mods menu, for ops.
 */
public final class ServerConfig {
	private ServerConfig() {}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pvp-dimensions").resolve("server.properties");
	private static final Properties values = new Properties();

	public static int maxArenas() {
		return number("max_arenas", 4);
	}

	public static int userArenas() {
		return number("user_arenas", 1);
	}

	public static int lobbyMinutes() {
		return number("lobby_minutes", 15);
	}

	private static int number(String key, int fallback) {
		try {
			return Integer.parseInt(values.getProperty(key, String.valueOf(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static void set(String key, int value) {
		values.setProperty(key, String.valueOf(value));
		save();
	}

	public static void load() {
		values.clear();
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				values.load(reader);
			} catch (IOException e) {
				PvpDimensions.LOGGER.error("Could not read {}", FILE, e);
			}
		}
		values.putIfAbsent("max_arenas", String.valueOf(maxArenas()));
		values.putIfAbsent("user_arenas", String.valueOf(userArenas()));
		values.putIfAbsent("lobby_minutes", String.valueOf(lobbyMinutes()));
		save();
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				values.store(writer, "PvP Dimensions: limits for the whole server");
			}
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Could not write {}", FILE, e);
		}
	}

	public static void menu() {
		var group = PandoricalApi.settings().serverGroup(PvpDimensions.MOD_ID, "PvP Dimensions");
		group.number("maxArenas", "Arenas at once", 1, 16, 1, 4)
			.describe("Every arena is a plot of ground kept loaded while people are in it")
			.backedBy(player -> maxArenas(), (player, value) -> set("max_arenas", value));
		group.number("userArenas", "Arenas each user may run", 1, 8, 1, 1)
			.describe("Admins are not counted")
			.backedBy(player -> userArenas(), (player, value) -> set("user_arenas", value));
		group.number("lobbyMinutes", "Waiting room waits, minutes", 1, 60, 1, 15)
			.describe("Then the fight starts by itself, or the arena closes if nobody came")
			.backedBy(player -> lobbyMinutes(), (player, value) -> set("lobby_minutes", value));
	}
}
