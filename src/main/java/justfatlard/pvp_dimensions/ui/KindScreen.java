package justfatlard.pvp_dimensions.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentDef;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Field;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Presets;
import justfatlard.pvp_dimensions.preset.Sharing;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * Where a new preset comes from: one of the kinds of match, in families, or a preset another
 * server shared. A row each, picture, name and a line about it, the same rows the menu lists
 * arenas and presets with. Picking a kind makes the preset and opens it.
 */
public final class KindScreen {
	private KindScreen() {}

	public static final String TYPE = "pvp-dimensions:kinds";

	private static final int WIDTH = 236;
	private static final int ROW = 22;
	private static final int ROWS = 6;

	/** Who is looking at what: the kinds, or the shared folder. */
	private static final Map<UUID, Boolean> importing = new ConcurrentHashMap<>();

	static void register(ScreenApi screens) {
		screens.onActionFallback(TYPE, KindScreen::pressed);
		screens.onClose(TYPE, player -> forget(player.getUUID()));
	}

	static void forget(UUID player) {
		importing.remove(player);
	}

	public static void show(ServerPlayer player) {
		show(player, importing.getOrDefault(player.getUUID(), false));
	}

	private static void show(ServerPlayer player, boolean imports) {
		if (!Access.admin(player)) return;
		MinecraftServer server = player.level().getServer();
		importing.put(player.getUUID(), imports);
		int inner = WIDTH - PAD * 2;
		int width = inner - 6;
		List<ComponentBuilder> rows = new ArrayList<>();
		int rowY = 0;
		int count = 0;

		if (imports) {
			List<Sharing.Offer> offers = Sharing.offers(server);
			if (offers.isEmpty()) {
				rows.add(faint("none", 2, 8, "Nothing in config/pvp-dimensions/shared yet"));
				count++;
			}
			for (Sharing.Offer offer : offers) {
				ComponentBuilder button = row(rows, "import:" + offer.file(), rowY, width, offer.icon(), offer.name(),
					offer.describe(), offer.name() + ": " + offer.describe());
				if (!offer.ready()) button.prop(ComponentType.PROP_ACCENT, "#FFD04A4A");
				rowY += ROW;
				count++;
			}
		} else {
			for (Kinds.Family family : Kinds.Family.values()) {
				List<Kinds> here = new ArrayList<>();
				for (Kinds kind : Kinds.values()) {
					if (kind.family == family && kind.here()) here.add(kind);
				}
				if (here.isEmpty()) continue;
				// A whole row for the family's name, so every row lines up with the scroll's steps.
				rows.add(faint("f:" + family.name(), 2, rowY + 12, family.label));
				rowY += ROW;
				count++;
				for (Kinds kind : here) {
					row(rows, "kind:" + kind.name(), rowY, width, kind.icon, kind.label, kind.about, kind.label + ": " + kind.about);
					rowY += ROW;
					count++;
				}
			}
		}

		int y = 20;
		int listY = y;
		y += ROWS * ROW + 4;
		ScreenBuilder screen = new ScreenBuilder(TYPE).title("New game").pauseGame(false);
		List<ComponentBuilder> under = new ArrayList<>();
		under.add(text("title", PAD, 7, imports ? "Import a preset" : "What kind of match?"));
		under.add(button("swap", PAD, y, 86, BUTTON, imports ? "The kinds" : "Import one",
			imports ? "Back to the kinds of match" : "A preset another server shared, from config/pvp-dimensions/shared"));
		under.add(button("back", PAD + inner - 50, y, 50, BUTTON, "Back", "To the menu"));
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
			ComponentType.PROP_TOTAL_ITEMS, String.valueOf(Math.max(count, ROWS)),
			ComponentType.PROP_SHOW_SCROLLBAR, String.valueOf(count > ROWS),
			ComponentType.PROP_SCROLL_OFFSET, "0",
			ComponentType.PROP_BACKGROUND, "#00000000"), defs);
		PandoricalApi.screens().open(player, screen.build());
	}

	/**
	 * A row of a list: the button first, then its picture and words over it, so a press anywhere
	 * along it is the row's. The button comes back for whoever wants to mark or colour it.
	 */
	static ComponentBuilder row(List<ComponentBuilder> rows, String id, int y, int width, String item, String name, String under, String tooltip) {
		// Six pixels a letter, less the picture and whatever button sits at the end of the row.
		int room = (width - 26) / 6;
		ComponentBuilder button = button(id, 0, y, width, ROW - 2, "", tooltip);
		rows.add(button);
		rows.add(icon(id + "_icon", 3, y + 2, item, 1F));
		rows.add(lit(id + "_name", 23, y + 1, clip(name, room)));
		if (!under.isEmpty()) rows.add(litFaint(id + "_under", 23, y + 11, clip(under, room)));
		return button;
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		if (id == null || !Access.admin(player)) return;
		MinecraftServer server = player.level().getServer();
		switch (id) {
			case "back" -> {
				forget(player.getUUID());
				MainScreen.show(player);
			}
			case "swap" -> show(player, !importing.getOrDefault(player.getUUID(), false));
			default -> {
				if (id.startsWith("kind:")) {
					Kinds made;
					try {
						made = Kinds.valueOf(id.substring(5));
					} catch (IllegalArgumentException ignored) {
						return;
					}
					forget(player.getUUID());
					EditorScreen.show(player, Presets.save(null, made.make()), Field.Section.GAME);
				} else if (id.startsWith("import:")) {
					List<String> told = new ArrayList<>();
					String made = Sharing.importFile(server, id.substring(7), told);
					for (String line : told) Say.to(player, line);
					if (made == null) return;
					forget(player.getUUID());
					EditorScreen.show(player, made, Field.Section.GAME);
				}
			}
		}
	}
}
