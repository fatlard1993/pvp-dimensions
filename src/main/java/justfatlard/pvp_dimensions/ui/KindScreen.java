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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * Where a new preset comes from: one of the kinds of match, in families. A row each, picture, name
 * and a line about it, the same rows the menu lists arenas and presets with. Picking a kind makes
 * the preset and opens it.
 *
 * <p>This screen used to have a second half for importing a preset another server shared. A preset
 * copied into the presets folder is in the list from the next server start, so there is nothing
 * left for that half to do.
 */
public final class KindScreen {
	private KindScreen() {}

	public static final String TYPE = "pvp-dimensions:kinds";

	private static final int WIDTH = 236;
	private static final int ROW = 22;
	private static final int ROWS = 6;


	static void register(ScreenApi screens) {
		screens.onActionFallback(TYPE, KindScreen::pressed);
		screens.onClose(TYPE, player -> forget(player.getUUID()));
	}

	static void forget(UUID player) {
	}

	public static void show(ServerPlayer player) {
		if (!Access.admin(player)) return;
		MinecraftServer server = player.level().getServer();
		int inner = WIDTH - PAD * 2;
		int width = inner - 6;
		List<ComponentBuilder> rows = new ArrayList<>();
		int rowY = 0;
		int count = 0;

		{
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
		under.add(text("title", PAD, 7, "What kind of match?"));
		// No import button: a preset copied into config/pvp-dimensions/presets is in the list
		// below from the next server start, so there is nothing here for one to do.
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
				}
			}
		}
	}
}
