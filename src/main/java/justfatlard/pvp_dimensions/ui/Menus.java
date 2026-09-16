package justfatlard.pvp_dimensions.ui;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.preset.Field;
import net.minecraft.server.level.ServerPlayer;

/** Which menu a player gets: the screen on a Pandorical client, clickable chat on any other. */
public final class Menus {
	private Menus() {}

	public static void open(ServerPlayer player) {
		if (PandoricalApi.isAvailable(player)) MainScreen.show(player);
		else ChatMenus.main(player);
	}

	/** Where a new preset starts: the kinds of match, and the presets others have shared. */
	public static void newPreset(ServerPlayer player) {
		if (PandoricalApi.isAvailable(player)) KindScreen.show(player);
		else ChatMenus.kinds(player);
	}

	public static void edit(ServerPlayer player, String presetId, Field.Section section) {
		if (PandoricalApi.isAvailable(player)) EditorScreen.show(player, presetId, section);
		else ChatMenus.preset(player, presetId, section);
	}

	public static void closeScreen(ServerPlayer player) {
		String open = PandoricalApi.getOpenScreenId(player.getUUID());
		if (open != null) PandoricalApi.screens().close(player, open);
	}
}
