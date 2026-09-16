package justfatlard.pvp_dimensions.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.PixelCanvas;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentDef;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Places;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.preset.Field;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * A preset, one section at a time: a tab for each, and a row for each setting with the control
 * that changes it. Every change is saved as it is made. Kits and item lists are edited by
 * holding the items, which closes this and opens again when they are done.
 */
public final class EditorScreen {
	private EditorScreen() {}

	public static final String TYPE = "pvp-dimensions:editor";

	/** Fits the smallest window the game allows, 320 by 240 at any GUI scale. */
	private static final int WIDTH = 316;
	private static final int ROW = 22;
	private static final int ROWS = 6;
	private static final int CONTROL = 150;

	private record Editing(String preset, Field.Section section, int scroll, boolean deleting) {}

	private static final Map<UUID, Editing> editing = new ConcurrentHashMap<>();

	static void register(ScreenApi screens) {
		screens.onActionFallback(TYPE, EditorScreen::pressed);
		screens.onClose(TYPE, player -> editing.remove(player.getUUID()));
	}

	static void forget(UUID player) {
		editing.remove(player);
	}

	public static void show(ServerPlayer player, String presetId, Field.Section section) {
		Editing before = editing.get(player.getUUID());
		int scroll = before != null && before.preset().equals(presetId) && before.section() == section ? before.scroll() : 0;
		show(player, new Editing(presetId, section, scroll, false));
	}

	private static void show(ServerPlayer player, Editing state) {
		if (!Access.admin(player)) return;
		Preset preset = Presets.get(state.preset());
		if (preset == null) {
			MainScreen.show(player);
			return;
		}
		editing.put(player.getUUID(), state);
		int inner = WIDTH - PAD * 2;
		List<ComponentBuilder> under = new ArrayList<>();
		List<ComponentBuilder> over = new ArrayList<>();
		int y = 20;

		List<Field.Section> tabs = new ArrayList<>();
		for (Field.Section section : Field.Section.values()) {
			tabs.add(section);
		}
		// A row of tabs while they fit along one; two rows only if ever there are more.
		int perRow = tabs.size() <= 5 ? tabs.size() : (tabs.size() + 1) / 2;
		int tabW = (inner - 2 * (perRow - 1)) / perRow;
		for (int i = 0; i < tabs.size(); i++) {
			Field.Section section = tabs.get(i);
			int tabX = PAD + (i % perRow) * (tabW + 2);
			int tabY = y + (i / perRow) * (BUTTON + 2);
			ComponentBuilder tab = button("tab:" + section.name(), tabX, tabY, tabW, BUTTON, section.label);
			if (section == state.section()) tab.prop(ComponentType.PROP_STYLE, "pressed");
			under.add(tab);
		}
		y += ((tabs.size() + perRow - 1) / perRow) * (BUTTON + 2) + 2;

		List<ComponentBuilder> rows = new ArrayList<>();
		int rowY = 0;
		int count = 0;
		int controlX = inner - 6 - CONTROL;
		for (Field field : Fields.of(preset)) {
			if (field.section != state.section()) continue;
			int used = row(rows, preset, field, rowY, controlX, inner - 6);
			rowY += ROW * used;
			count += used;
		}
		int listY = y;
		int shown = Math.max(1, Math.min(count, ROWS));
		y += ROWS * ROW + 4;

		if (state.deleting()) {
			under.add(text("sure", PAD, y + 6, clip("Delete " + preset.name + " for good?", 30)));
			under.add(button("delete_yes", PAD + inner - 104, y, 50, BUTTON, "Delete"));
			under.add(button("delete_no", PAD + inner - 50, y, 50, BUTTON, "Keep").prop(ComponentType.PROP_STYLE, "accepted"));
		} else {
			boolean canStart = Visit.of(player) == null;
			under.add(button("start", PAD, y, 70, BUTTON, "Start it", canStart ? "Make an arena from this and go in" : "Leave your arena first")
				.prop(ComponentType.PROP_STYLE, "accepted").prop(ComponentType.PROP_ENABLED, String.valueOf(canStart)));
			under.add(button("back", PAD + 74, y, 50, BUTTON, "Back", "To the menu"));
			under.add(button("copy", PAD + inner - 104, y, 50, BUTTON, "Copy", "A new preset, the same as this one"));
			under.add(button("delete", PAD + inner - 50, y, 50, BUTTON, "Delete"));
		}
		y += BUTTON;

		int height = y + PAD;
		ScreenBuilder screen = new ScreenBuilder(TYPE).title(preset.name).pauseGame(false).size(WIDTH, height);
		screen.panel("dialog", 0, 0, WIDTH, height, panel());
		screen.component(icon("title_icon", PAD, 3, preset.icon, 0.75F));
		screen.component(text("title", PAD + 16, 7, clip(preset.name, 36)));
		for (ComponentBuilder component : under) screen.component(component);
		for (ComponentBuilder component : over) screen.component(component);
		List<ComponentDef> defs = new ArrayList<>();
		for (ComponentBuilder row : rows) defs.add(row.build());
		screen.scrollPanel("rows", PAD, listY, inner, ROWS * ROW, Map.of(
			ComponentType.PROP_ITEM_HEIGHT, String.valueOf(ROW),
			ComponentType.PROP_VISIBLE_ITEMS, String.valueOf(ROWS),
			ComponentType.PROP_TOTAL_ITEMS, String.valueOf(Math.max(count, shown)),
			ComponentType.PROP_SHOW_SCROLLBAR, String.valueOf(count > ROWS),
			ComponentType.PROP_SCROLL_OFFSET, String.valueOf(Math.max(0, Math.min(state.scroll(), Math.max(0, count - ROWS)))),
			ComponentType.PROP_BACKGROUND, "#00000000"), defs);
		PandoricalApi.screens().open(player, screen.build());
	}

	/** Words on a note that says what the game does instead: the colour of a warning, not a mistake. */
	private static final String WARM = "#FF8A5200";
	/** A cell of a grid: a spawn egg, say, with a letter or two beside it. */
	private static final int CELL = 26;

	/**
	 * A setting's row: its name on the left, its control on the right; a grid its name and then
	 * as many rows of cells as it takes.
	 *
	 * @return how many rows it took
	 */
	private static int row(List<ComponentBuilder> rows, Preset preset, Field field, int y, int x, int width) {
		String key = field.key;
		String help = field.help();
		if (field instanceof Field.Heading) {
			rows.add(faint("h:" + key, 2, y + 8, clip(field.label, 48)));
			return 1;
		}
		if (field instanceof Field.Note note) {
			rows.add(text("n:" + key, 2, y + 7, clip(note.display(preset), 52)).prop(ComponentType.PROP_COLOR, note.warm ? WARM : INK));
			return 1;
		}
		if (field instanceof Field.Picture picture) {
			Field.Picture.Canvas canvas = picture.draw(preset);
			int boxH = picture.height * ROW - 4;
			boolean square = canvas.columns() == canvas.rows();
			int boxW = square ? boxH : width - 4;
			StringBuilder palette = new StringBuilder();
			for (int colour : canvas.palette()) {
				if (!palette.isEmpty()) palette.append(',');
				palette.append(String.format("#%08X", colour));
			}
			rows.add(new ComponentBuilder("p:" + key, ComponentType.PIXEL_CANVAS).bounds(2, y + 2, boxW, boxH)
				.prop(ComponentType.PROP_CANVAS_COLUMNS, String.valueOf(canvas.columns()))
				.prop(ComponentType.PROP_CANVAS_ROWS, String.valueOf(canvas.rows()))
				.prop(ComponentType.PROP_CANVAS_PALETTE, palette.toString())
				.prop(ComponentType.PROP_CANVAS_PIXELS, PixelCanvas.encode(canvas.cells()))
				.prop(ComponentType.PROP_CANVAS_INK, "-1"));
			List<Field.Picture.Key> words = canvas.key();
			if (square) {
				for (int i = 0; i < words.size(); i++) {
					rows.add(text("k" + i + ":" + key, boxW + 12, y + 6 + i * 12, clip(words.get(i).words(), 30))
						.prop(ComponentType.PROP_COLOR, String.format("#%08X", words.get(i).colour())));
				}
				return picture.height;
			}
			int kx = 2;
			for (int i = 0; i < words.size(); i++) {
				String said = words.get(i).words();
				rows.add(text("k" + i + ":" + key, kx, y + picture.height * ROW + 6, said)
					.prop(ComponentType.PROP_COLOR, String.format("#%08X", words.get(i).colour())));
				kx += said.length() * 6 + 10;
			}
			return picture.height + 1;
		}
		if (field instanceof Field.Grid grid) {
			rows.add(text("l:" + key, 2, y + 6, clip(field.label, 22)));
			rows.add(faint("v:" + key, x, y + 6, clip(grid.display(preset), 24)));
			List<Field.Grid.Cell> cells = grid.cells(preset);
			int perRow = Math.max(1, width / (CELL + 1));
			for (int i = 0; i < cells.size(); i++) {
				Field.Grid.Cell cell = cells.get(i);
				int cx = 2 + (i % perRow) * (CELL + 1);
				int cy = y + ROW * (1 + i / perRow);
				String id = "g:" + key + "|" + cell.key();
				ComponentBuilder button = button(id, cx, cy, CELL, BUTTON, "", cell.tooltip());
				if (cell.dim()) button.prop(ComponentType.PROP_STYLE, "pressed");
				if (cell.accent() != 0) button.prop(ComponentType.PROP_ACCENT, String.format("#%08X", cell.accent()));
				rows.add(button);
				if (cell.small()) rows.add(icon(id + "_egg", cx + 5, cy + 4, cell.item(), 0.7F));
				else rows.add(icon(id + "_egg", cx + 3, cy + 2, cell.item(), 1F));
				if (!cell.badge().isEmpty()) rows.add(icon(id + "_badge", cx + CELL - 15, cy - 3, cell.badge(), 0.5F));
				if (!cell.mark().isEmpty()) {
					rows.add(text(id + "_mark", cx + CELL - 7, cy + 11, cell.mark()).prop(ComponentType.PROP_COLOR, "#FFFFFFFF")
						.prop(ComponentType.PROP_SHADOW, "true"));
				}
			}
			return 1 + (cells.size() + perRow - 1) / perRow;
		}
		if (!field.label.isEmpty()) rows.add(text("l:" + key, 2, y + 6, clip(field.label, 22)));
		switch (field) {
			case Field.Toggle toggle -> rows.add(button("t:" + key, x, y, CONTROL, BUTTON, toggle.display(preset), help)
				.prop(ComponentType.PROP_STYLE, toggle.get(preset) ? "accepted" : "default"));
			case Field.Choice choice -> stepper(rows, key, x, y, choice.display(preset), help, true, true);
			case Field.Number number -> stepper(rows, key, x, y, number.display(preset), help, number.canStep(preset, -1), number.canStep(preset, 1));
			case Field.Text text -> {
				if (key.equals("icon")) {
					rows.add(icon("i:" + key, x + 2, y + 2, text.get(preset), 1F));
					rows.add(button("held:" + key, x + 22, y, CONTROL - 22, BUTTON, "Use what I hold", help));
				} else {
					rows.add(new ComponentBuilder("in:" + key, ComponentType.TEXT_INPUT)
						.bounds(x, y + 2, CONTROL, 16)
						.prop(ComponentType.PROP_VALUE, text.get(preset))
						.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(text.maxLength)));
				}
			}
			case Field.BlockRef block -> {
				String id = block.get(preset);
				if (id != null && !id.isEmpty()) rows.add(icon("i:" + key, x + 2, y + 2, itemOf(id), 1F));
				rows.add(faint("v:" + key, x + 22, y + 6, clip(block.display(preset), 12)));
				rows.add(button("held:" + key, x + CONTROL - 50, y, 50, BUTTON, "Held", "Set it to the block in your hand"
					+ (block.allowsNone() ? "; hold nothing to leave it unchanged" : "")));
			}
			case Field.Items items -> {
				List<ItemStack> stacks = items.stacks(preset);
				int room = items.canShuffle() ? 4 : 5;
				int shown = stacks.size() > room ? room - 1 : stacks.size();
				for (int i = 0; i < shown; i++) {
					ItemStack stack = stacks.get(i);
					rows.add(icon("i" + i + ":" + key, x + i * 17, y + 2, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
				}
				if (stacks.isEmpty()) rows.add(faint("v:" + key, x + 2, y + 6, "Nothing"));
				else if (stacks.size() > shown) rows.add(faint("more:" + key, x + shown * 17 + 1, y + 6, "+" + (stacks.size() - shown)));
				if (items.canShuffle()) rows.add(button("mix:" + key, x + CONTROL - 74, y, 20, BUTTON, "⚄", "Fill it at random, to start from"));
				rows.add(button("items:" + key, x + CONTROL - 52, y, 36, BUTTON, "Edit", (help.isEmpty() ? "" : help + ". ")
					+ "Hold the items you want, then say done"));
				rows.add(button("clear:" + key, x + CONTROL - 14, y, 14, BUTTON, "✕", "Empty it"));
			}
			case Field.Action action -> rows.add(button("act:" + key, x, y, CONTROL, BUTTON, action.button, help));
			case Field.Heading heading -> { }
			case Field.Note note -> { }
			case Field.Grid grid -> { }
			case Field.Picture picture -> { }
		}
		return 1;
	}

	private static void stepper(List<ComponentBuilder> rows, String key, int x, int y, String value, String help, boolean back, boolean forward) {
		rows.add(button("prev:" + key, x, y, 20, BUTTON, "<", help).prop(ComponentType.PROP_ENABLED, String.valueOf(back)));
		rows.add(centred("v:" + key, x + 20, y + 6, CONTROL - 40, clip(value, 20)));
		rows.add(button("next:" + key, x + CONTROL - 20, y, 20, BUTTON, ">", help).prop(ComponentType.PROP_ENABLED, String.valueOf(forward)));
	}

	/** The item for a block, for its picture; air and blocks with no item show as a barrier. */
	private static String itemOf(String blockId) {
		Identifier parsed = Identifier.tryParse(blockId);
		if (parsed == null) return "minecraft:barrier";
		return BuiltInRegistries.BLOCK.getOptional(parsed)
			.map(block -> block.asItem() == net.minecraft.world.item.Items.AIR ? "minecraft:barrier" : BuiltInRegistries.ITEM.getKey(block.asItem()).toString())
			.orElse("minecraft:barrier");
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		Editing state = editing.get(player.getUUID());
		if (id == null || state == null || !Access.admin(player)) return;
		if (id.equals("rows")) {
			try {
				editing.put(player.getUUID(), new Editing(state.preset(), state.section(), Integer.parseInt(data.getOrDefault("scroll_offset", "0")), state.deleting()));
			} catch (NumberFormatException ignored) {
			}
			return;
		}
		Preset preset = Presets.get(state.preset());
		if (preset == null) return;

		switch (id) {
			case "back" -> {
				editing.remove(player.getUUID());
				MainScreen.show(player);
				return;
			}
			case "start" -> {
				startNow(player, state.preset(), preset);
				return;
			}
			case "copy" -> {
				Preset copy = Presets.copy(preset);
				copy.name = clip(preset.name + " copy", 24);
				show(player, new Editing(Presets.save(null, copy), Field.Section.GAME, 0, false));
				return;
			}
			case "delete" -> {
				show(player, new Editing(state.preset(), state.section(), state.scroll(), true));
				return;
			}
			case "delete_no" -> {
				show(player, new Editing(state.preset(), state.section(), state.scroll(), false));
				return;
			}
			case "delete_yes" -> {
				Presets.delete(state.preset());
				editing.remove(player.getUUID());
				MainScreen.show(player);
				return;
			}
			default -> { }
		}

		int colon = id.indexOf(':');
		if (colon < 0) return;
		String kind = id.substring(0, colon);
		String key = id.substring(colon + 1);
		String cell = null;
		if (kind.equals("g")) {
			int bar = key.indexOf('|');
			if (bar < 0) return;
			cell = key.substring(bar + 1);
			key = key.substring(0, bar);
		}
		if (kind.equals("tab")) {
			try {
				show(player, new Editing(state.preset(), Field.Section.valueOf(key), 0, false));
			} catch (IllegalArgumentException ignored) {
			}
			return;
		}
		Field field = Fields.find(preset, key);
		if (field == null) return;
		String problem = null;
		switch (kind) {
			case "t" -> {
				if (field instanceof Field.Toggle toggle) toggle.flip(preset);
			}
			case "prev", "next" -> {
				int by = kind.equals("next") ? 1 : -1;
				if (field instanceof Field.Choice choice) choice.step(preset, by);
				else if (field instanceof Field.Number number) number.step(preset, by);
			}
			case "in" -> {
				// Typing saves as it goes and leaves the screen be, so the box keeps its cursor.
				String typed = data.getOrDefault("text", "");
				if (!typed.isBlank() && field.set(preset, typed) == null) Presets.save(state.preset(), preset);
				return;
			}
			case "held" -> problem = held(player, preset, field);
			case "items" -> {
				editing.remove(player.getUUID());
				ItemSessions.start(player, state.preset(), key);
				return;
			}
			case "clear" -> {
				if (field instanceof Field.Items items) items.clear(preset);
			}
			case "mix" -> {
				if (field instanceof Field.Items items) items.shuffle(preset);
			}
			case "g" -> {
				if (field instanceof Field.Grid grid) grid.press(preset, cell);
			}
			case "act" -> {
				if (key.equals("swap.add")) problem = held(player, preset, field);
				else if (key.equals("export")) Say.to(player, justfatlard.pvp_dimensions.preset.Sharing.export(state.preset(), preset));
				else if (field instanceof Field.Action action) action.run(preset);
			}
			default -> {
				return;
			}
		}
		if (problem != null) Say.bar(player, problem);
		else Presets.save(state.preset(), preset);
		show(player, editing.getOrDefault(player.getUUID(), state));
	}

	/** Set from the hand: the picture from any item, a block setting from a block. */
	private static String held(ServerPlayer player, Preset preset, Field field) {
		ItemStack stack = player.getMainHandItem();
		if (field.key.equals("icon")) {
			if (stack.isEmpty()) return "Hold the item to use as its picture";
			preset.icon = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			return null;
		}
		if (stack.isEmpty()) {
			if (field instanceof Field.BlockRef block && block.allowsNone()) return block.set(preset, "none");
			return "Hold a block";
		}
		if (!(stack.getItem() instanceof BlockItem blockItem)) return "That isn't a block";
		String block = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
		if (field.key.equals("swap.add")) {
			preset.swaps.putIfAbsent(block, block);
			return null;
		}
		return field.set(preset, block);
	}

	private static void startNow(ServerPlayer player, String presetId, Preset preset) {
		if (Visit.of(player) != null) {
			Say.to(player, "Leave your arena before starting another");
			return;
		}
		editing.remove(player.getUUID());
		Menus.closeScreen(player);
		Arenas.Start options = new Arenas.Start(Arenas.Invite.EVERYONE, List.of(), !Places.isArena(player.level().dimension()), true);
		Arena arena = Arenas.start(player.level().getServer(), player, presetId, preset, options, words -> Say.to(player, words));
		if (arena != null) Say.to(player, "Making " + arena.title() + "; you'll be taken in when it's ready");
	}
}
