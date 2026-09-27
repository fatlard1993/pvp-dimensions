package justfatlard.pvp_dimensions.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.ComponentUpdateBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import justfatlard.pvp_dimensions.preset.Fields;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * Every block there is, found by typing part of its name and taken with a click, so a block
 * setting never needs the block in hand, or the editor closed to go and fetch it.
 *
 * <p>The grid is laid out once and refilled in place as the search changes, which leaves the
 * search box holding the keyboard between letters.
 */
final class BlockPicker {
	private BlockPicker() {}

	static final String SEARCH = "pick_search";
	static final int COLUMNS = 11;
	static final int ROWS = 5;
	static final int PAGE = COLUMNS * ROWS;
	private static final int CELL = 26;
	private static final int GAP = 1;

	record Block(String id, String name, String item) {}

	private static @Nullable List<Block> all;

	/** Every block with an item to picture it by, by name. Built on first use, when the registries are done. */
	private static List<Block> all() {
		List<Block> blocks = all;
		if (blocks != null) return blocks;
		blocks = new ArrayList<>();
		for (var block : BuiltInRegistries.BLOCK) {
			if (block.asItem() == Items.AIR) continue;
			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			blocks.add(new Block(id, Fields.blockName(id), BuiltInRegistries.ITEM.getKey(block.asItem()).toString()));
		}
		blocks.sort(Comparator.comparing(Block::name));
		return all = List.copyOf(blocks);
	}

	/** The blocks whose name or id holds every word typed, those starting with the first word ahead. */
	static List<Block> matches(String query) {
		String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
		if (words.length == 1 && words[0].isEmpty()) return all();
		List<Block> ahead = new ArrayList<>();
		List<Block> rest = new ArrayList<>();
		for (Block block : all()) {
			String name = block.name().toLowerCase(Locale.ROOT);
			String haystack = name + " " + block.id();
			boolean every = true;
			for (String word : words) every &= haystack.contains(word);
			if (!every) continue;
			(name.startsWith(words[0]) ? ahead : rest).add(block);
		}
		ahead.addAll(rest);
		return ahead;
	}

	static int pages(List<Block> found) {
		return Math.max(1, (found.size() + PAGE - 1) / PAGE);
	}

	static @Nullable Block at(String query, int page, int cell) {
		List<Block> found = matches(query);
		int index = page * PAGE + cell;
		return cell >= 0 && cell < PAGE && index < found.size() ? found.get(index) : null;
	}

	/** The picker's own rows: what is being set, the search, the grid, the way through the pages. @return how tall */
	static int layout(List<ComponentBuilder> into, int x, int y, int width, String label, String query, int page, @Nullable String unset) {
		Map<String, Map<String, String>> state = state(query, page);
		int top = y;
		into.add(text("pick_label", x, y + 4, clip(label, 50)));
		y += 16;
		into.add(new ComponentBuilder(SEARCH, ComponentType.TEXT_INPUT).bounds(x, y, width, 16)
			.prop(ComponentType.PROP_VALUE, query)
			.prop(ComponentType.PROP_PLACEHOLDER, "Type part of a block's name")
			.prop(ComponentType.PROP_MAX_LENGTH, "40")
			.prop(ComponentType.PROP_FOCUSED, "true"));
		y += 20;
		int gridX = x + (width - COLUMNS * (CELL + GAP) + GAP) / 2;
		for (int i = 0; i < PAGE; i++) {
			int cx = gridX + (i % COLUMNS) * (CELL + GAP);
			int cy = y + (i / COLUMNS) * (BUTTON + GAP);
			into.add(button("pick:" + i, cx, cy, CELL, BUTTON, "").props(state.get("pick:" + i)));
			into.add(icon("pick_icon:" + i, cx + 5, cy + 2, "minecraft:air", 1F).props(state.get("pick_icon:" + i)));
		}
		y += ROWS * (BUTTON + GAP) + 3;
		into.add(button("pick_prev", x, y, 20, BUTTON, "<").props(state.get("pick_prev")));
		into.add(centred("pick_page", x + 20, y + 6, 70, "").props(state.get("pick_page")));
		into.add(button("pick_next", x + 90, y, 20, BUTTON, ">").props(state.get("pick_next")));
		int right = x + width;
		into.add(button("pick_cancel", right - 50, y, 50, BUTTON, "Back", "Change nothing"));
		into.add(button("pick_held", right - 94, y, 42, BUTTON, "Held", "The block in your hand"));
		if (unset != null) into.add(button("pick_none", right - 164, y, 68, BUTTON, clip(unset, 11), unset));
		return y + BUTTON - top;
	}

	/** What changes on the screen when the search or the page does. */
	static List<ComponentUpdate> refresh(String query, int page) {
		List<ComponentUpdate> updates = new ArrayList<>();
		state(query, page).forEach((id, props) -> updates.add(new ComponentUpdateBuilder(id).props(props).build()));
		return updates;
	}

	/** Each part of the grid and the page count as this search and page leave it, by component. */
	private static Map<String, Map<String, String>> state(String query, int page) {
		List<Block> found = matches(query);
		int pages = pages(found);
		Map<String, Map<String, String>> state = new LinkedHashMap<>();
		for (int i = 0; i < PAGE; i++) {
			int index = page * PAGE + i;
			Block block = index < found.size() ? found.get(index) : null;
			String shown = String.valueOf(block != null);
			state.put("pick:" + i, Map.of(ComponentType.PROP_VISIBLE, shown, ComponentType.PROP_TOOLTIP, block != null ? block.name() : ""));
			state.put("pick_icon:" + i, Map.of(ComponentType.PROP_VISIBLE, shown, ComponentType.PROP_ITEM_ID, block != null ? block.item() : "minecraft:air"));
		}
		state.put("pick_page", Map.of(ComponentType.PROP_TEXT, found.isEmpty() ? "Nothing found" : (page + 1) + " of " + pages));
		state.put("pick_prev", Map.of(ComponentType.PROP_ENABLED, String.valueOf(page > 0)));
		state.put("pick_next", Map.of(ComponentType.PROP_ENABLED, String.valueOf(page + 1 < pages)));
		return state;
	}
}
