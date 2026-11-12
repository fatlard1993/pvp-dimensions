package justfatlard.pvp_dimensions.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentDef;
import justfatlard.pvp_dimensions.onboarding.Demo;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.onboarding.Progress;
import net.minecraft.server.level.ServerPlayer;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * Every mod's demo, a row each like the menu's: its picture, its name, and the line about it, or
 * that it has been played. Any row plays that demo, from home or from inside another.
 */
public final class DemoScreen {
	private DemoScreen() {}

	public static final String TYPE = "pvp-dimensions:demos";

	private static final int WIDTH = 236;
	private static final int ROW = 22;
	private static final int ROWS = 6;

	static void register(ScreenApi screens) {
		screens.onActionFallback(TYPE, DemoScreen::pressed);
	}

	public static void show(ServerPlayer player) {
		List<Demo> all = Demos.all();
		Progress progress = Progress.of(player);
		int inner = WIDTH - PAD * 2;
		int width = inner - 6;
		List<ComponentBuilder> rows = new ArrayList<>();
		int rowY = 0;
		long played = 0;
		for (Demo demo : all) {
			boolean done = progress.played(demo);
			if (done) played++;
			String under = done ? "✔ Played" : demo.about().isEmpty() ? "Not played yet" : demo.about().getFirst();
			KindScreen.row(rows, "demo:" + demo.id(), rowY, width, demo.icon(), demo.name(), under,
				String.join(" ", demo.about()));
			rowY += ROW;
		}

		int y = 20;
		int listY = y;
		y += ROWS * ROW + 4;
		ScreenBuilder screen = new ScreenBuilder(TYPE).title("Demos").pauseGame(false);
		List<ComponentBuilder> under = new ArrayList<>();
		under.add(text("title", PAD, 7, all.isEmpty() ? "None of the mods here has a demo"
			: "Demos: " + played + " of " + all.size() + " played"));
		Demo next = Demos.next(player, null);
		if (next != null) under.add(button("next", PAD, y, 90, BUTTON, "Next unplayed", next.name()));
		under.add(button("close", PAD + inner - 50, y, 50, BUTTON, "Close"));
		y += BUTTON;

		int height = y + PAD;
		screen.size(WIDTH, height);
		screen.panel("dialog", 0, 0, WIDTH, height, panel());
		for (ComponentBuilder component : under) screen.component(component);
		List<ComponentDef> defs = new ArrayList<>();
		for (ComponentBuilder row : rows) defs.add(row.build());
		screen.scrollPanel("rows", PAD, listY, inner, ROWS * ROW, Map.of(
			ComponentType.PROP_ITEM_HEIGHT, String.valueOf(ROW),
			ComponentType.PROP_VISIBLE_ITEMS, String.valueOf(ROWS),
			ComponentType.PROP_TOTAL_ITEMS, String.valueOf(Math.max(all.size(), ROWS)),
			ComponentType.PROP_SHOW_SCROLLBAR, String.valueOf(all.size() > ROWS),
			ComponentType.PROP_SCROLL_OFFSET, "0",
			ComponentType.PROP_BACKGROUND, "#00000000"), defs);
		PandoricalApi.screens().open(player, screen.build());
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		if (id == null) return;
		if (id.equals("close")) {
			Menus.closeScreen(player);
			return;
		}
		String demo = id.equals("next") ? idOf(Demos.next(player, null)) : id.startsWith("demo:") ? id.substring(5) : null;
		if (demo == null) return;
		Menus.closeScreen(player);
		Demos.play(player, demo);
	}

	private static String idOf(Demo demo) {
		return demo == null ? null : demo.id();
	}
}
