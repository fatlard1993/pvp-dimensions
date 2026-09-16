package justfatlard.pvp_dimensions.ui;

import java.util.UUID;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;

/** Registers the screens' handlers, once, and forgets a player's open screens when they go. */
public final class Screens {
	private Screens() {}

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		MainScreen.register(screens);
		EditorScreen.register(screens);
		KindScreen.register(screens);
		PickScreen.register(screens);
	}

	public static void forget(UUID player) {
		MainScreen.forget(player);
		EditorScreen.forget(player);
		KindScreen.forget(player);
	}
}
