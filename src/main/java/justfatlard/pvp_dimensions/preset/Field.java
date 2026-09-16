package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One setting: its key for commands, its label for the menu, and how to read, show and change it.
 * Everything that edits a preset goes through one of these, so a limit written here holds in the
 * menu and in chat alike.
 */
public abstract sealed class Field {
	/** The questions a preset answers, in the order a host asks them. */
	public enum Section {
		GAME("Game", "minecraft:iron_sword"),
		PLAYERS("Players", "minecraft:player_head"),
		ARENA("Arena", "minecraft:grass_block"),
		TIMELINE("Timeline", "minecraft:clock"),
		GEAR("Gear", "minecraft:chest");

		public final String label;
		public final String icon;

		Section(String label, String icon) {
			this.label = label;
			this.icon = icon;
		}
	}

	public final String key;
	public final String label;
	public final Section section;
	private String help = "";
	private Predicate<Preset> shown = preset -> true;

	protected Field(Section section, String key, String label) {
		this.section = section;
		this.key = key;
		this.label = label;
	}

	public String help() {
		return help;
	}

	public Field help(String help) {
		this.help = help;
		return this;
	}

	public Field when(Predicate<Preset> condition) {
		Predicate<Preset> before = shown;
		this.shown = preset -> before.test(preset) && condition.test(preset);
		return this;
	}

	public boolean shown(Preset preset) {
		return shown.test(preset);
	}

	/** What the menu and chat show as its value. */
	public abstract String display(Preset preset);

	/** Change it from typed words; the complaint when they make no sense, or null. */
	public abstract @Nullable String set(Preset preset, String value);

	/** What {@code /pvp preset set} offers to finish the value with. */
	public List<String> suggestions(Preset preset) {
		return List.of();
	}

	// --- Kinds ---

	public static final class Toggle extends Field {
		private final Function<Preset, Boolean> get;
		private final BiConsumer<Preset, Boolean> put;

		public Toggle(Section section, String key, String label, Function<Preset, Boolean> get, BiConsumer<Preset, Boolean> put) {
			super(section, key, label);
			this.get = get;
			this.put = put;
		}

		public boolean get(Preset preset) {
			return get.apply(preset);
		}

		public void flip(Preset preset) {
			put.accept(preset, !get(preset));
		}

		@Override
		public String display(Preset preset) {
			return get(preset) ? "On" : "Off";
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			switch (value.toLowerCase(Locale.ROOT)) {
				case "on", "true", "yes" -> put.accept(preset, true);
				case "off", "false", "no" -> put.accept(preset, false);
				default -> {
					return "Say on or off";
				}
			}
			return null;
		}

		@Override
		public List<String> suggestions(Preset preset) {
			return List.of("on", "off");
		}
	}

	/** One of a list, each with its label: an enum's values, the biomes, the saved terrains. */
	public static final class Choice extends Field {
		public record Option(String value, String label) {}

		private final Function<Preset, List<Option>> options;
		private final Function<Preset, String> get;
		private final BiConsumer<Preset, String> put;

		public Choice(Section section, String key, String label, Function<Preset, List<Option>> options,
				Function<Preset, String> get, BiConsumer<Preset, String> put) {
			super(section, key, label);
			this.options = options;
			this.get = get;
			this.put = put;
		}

		public static <E extends Enum<E>> Choice ofEnum(Section section, String key, String label, Class<E> type, String[] labels,
				Function<Preset, E> get, BiConsumer<Preset, E> put) {
			E[] values = type.getEnumConstants();
			List<Option> options = new ArrayList<>();
			for (int i = 0; i < values.length; i++) options.add(new Option(values[i].name().toLowerCase(Locale.ROOT), labels[i]));
			List<Option> fixed = List.copyOf(options);
			return new Choice(section, key, label, preset -> fixed,
				preset -> get.apply(preset).name().toLowerCase(Locale.ROOT),
				(preset, value) -> put.accept(preset, Enum.valueOf(type, value.toUpperCase(Locale.ROOT))));
		}

		public List<Option> options(Preset preset) {
			return options.apply(preset);
		}

		public String value(Preset preset) {
			return get.apply(preset);
		}

		/** Forward or back through the options, round the end. */
		public void step(Preset preset, int by) {
			List<Option> all = options(preset);
			if (all.isEmpty()) return;
			int at = 0;
			for (int i = 0; i < all.size(); i++) {
				if (all.get(i).value().equals(value(preset))) at = i;
			}
			put.accept(preset, all.get(Math.floorMod(at + by, all.size())).value());
		}

		@Override
		public String display(Preset preset) {
			String current = value(preset);
			for (Option option : options(preset)) {
				if (option.value().equals(current)) return option.label();
			}
			return current.isEmpty() ? "None" : current;
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			for (Option option : options(preset)) {
				if (option.value().equalsIgnoreCase(value) || option.label().equalsIgnoreCase(value)) {
					put.accept(preset, option.value());
					return null;
				}
			}
			return "Not one of the choices";
		}

		@Override
		public List<String> suggestions(Preset preset) {
			return options(preset).stream().map(Option::value).toList();
		}
	}

	/** A whole number, moved in steps: either a fixed step between limits or a list of stops. */
	public static final class Number extends Field {
		private final int[] stops;
		private final Function<Preset, Integer> get;
		private final BiConsumer<Preset, Integer> put;
		private final Function<Integer, String> words;

		public Number(Section section, String key, String label, int[] stops, Function<Integer, String> words,
				Function<Preset, Integer> get, BiConsumer<Preset, Integer> put) {
			super(section, key, label);
			this.stops = stops;
			this.words = words;
			this.get = get;
			this.put = put;
		}

		public static int[] range(int min, int max, int step) {
			int[] stops = new int[(max - min) / step + 1];
			for (int i = 0; i < stops.length; i++) stops[i] = min + i * step;
			return stops;
		}

		public int get(Preset preset) {
			return get.apply(preset);
		}

		public boolean canStep(Preset preset, int by) {
			int at = nearest(get(preset));
			return at + by >= 0 && at + by < stops.length;
		}

		public void step(Preset preset, int by) {
			int at = Math.max(0, Math.min(stops.length - 1, nearest(get(preset)) + by));
			put.accept(preset, stops[at]);
		}

		private int nearest(int value) {
			int best = 0;
			for (int i = 0; i < stops.length; i++) {
				if (Math.abs(stops[i] - value) < Math.abs(stops[best] - value)) best = i;
			}
			return best;
		}

		@Override
		public String display(Preset preset) {
			return words.apply(get(preset));
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			try {
				int number = Integer.parseInt(value.trim());
				if (number < stops[0] || number > stops[stops.length - 1]) {
					return "Between " + stops[0] + " and " + stops[stops.length - 1];
				}
				put.accept(preset, number);
				return null;
			} catch (NumberFormatException e) {
				return "Needs a number";
			}
		}

		@Override
		public List<String> suggestions(Preset preset) {
			List<String> all = new ArrayList<>();
			for (int stop : stops) all.add(String.valueOf(stop));
			return all;
		}
	}

	public static final class Text extends Field {
		public final int maxLength;
		private final Function<Preset, String> get;
		private final BiConsumer<Preset, String> put;

		public Text(Section section, String key, String label, int maxLength, Function<Preset, String> get, BiConsumer<Preset, String> put) {
			super(section, key, label);
			this.maxLength = maxLength;
			this.get = get;
			this.put = put;
		}

		public String get(Preset preset) {
			return get.apply(preset);
		}

		@Override
		public String display(Preset preset) {
			return get(preset);
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			String trimmed = value.strip();
			if (trimmed.isEmpty()) return "Can't be empty";
			put.accept(preset, trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed);
			return null;
		}
	}

	/** A block, by id. The menu sets it from whatever block the player is holding. */
	public static final class BlockRef extends Field {
		private final Function<Preset, String> get;
		private final BiConsumer<Preset, String> put;
		private final boolean allowNone;

		public BlockRef(Section section, String key, String label, boolean allowNone, Function<Preset, String> get, BiConsumer<Preset, String> put) {
			super(section, key, label);
			this.allowNone = allowNone;
			this.get = get;
			this.put = put;
		}

		public String get(Preset preset) {
			return get.apply(preset);
		}

		public boolean allowsNone() {
			return allowNone;
		}

		@Override
		public String display(Preset preset) {
			String id = get(preset);
			if (id == null || id.isEmpty()) return "Unchanged";
			Identifier parsed = Identifier.tryParse(id);
			if (parsed == null) return id;
			return BuiltInRegistries.BLOCK.getOptional(parsed).map(block -> block.getName().getString()).orElse(id);
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			if (allowNone && (value.equalsIgnoreCase("none") || value.isEmpty())) {
				put.accept(preset, "");
				return null;
			}
			Identifier parsed = Identifier.tryParse(value.contains(":") ? value : "minecraft:" + value);
			if (parsed == null || BuiltInRegistries.BLOCK.getOptional(parsed).isEmpty()) return "No such block";
			put.accept(preset, parsed.toString());
			return null;
		}
	}

	/** A list of items, edited by holding them: see {@code ItemSessions}. */
	public static final class Items extends Field {
		public final boolean laidOut;
		private final Function<Preset, ItemList> list;
		private final Function<Preset, Kit> kit;
		private final BiConsumer<Preset, Kit> putKit;
		private final BiConsumer<Preset, ItemList> putList;
		private Loot.@Nullable Template template;

		private Items(Section section, String key, String label, boolean laidOut, Function<Preset, ItemList> list,
				BiConsumer<Preset, ItemList> putList, Function<Preset, Kit> kit, BiConsumer<Preset, Kit> putKit) {
			super(section, key, label);
			this.laidOut = laidOut;
			this.list = list;
			this.putList = putList;
			this.kit = kit;
			this.putKit = putKit;
		}

		public static Items list(Section section, String key, String label, Function<Preset, ItemList> get, BiConsumer<Preset, ItemList> put) {
			return new Items(section, key, label, false, get, put, null, null);
		}

		public static Items kit(Section section, String key, String label, Function<Preset, Kit> get, BiConsumer<Preset, Kit> put) {
			return new Items(section, key, label, true, null, null, get, put);
		}

		public List<net.minecraft.world.item.ItemStack> stacks(Preset preset) {
			return laidOut ? kit.apply(preset).stacks() : list.apply(preset).stacks();
		}

		public Kit asKit(Preset preset) {
			return laidOut ? kit.apply(preset) : Kit.laid(list.apply(preset).stacks());
		}

		public void store(Preset preset, Kit carried) {
			if (laidOut) putKit.accept(preset, carried);
			else putList.accept(preset, ItemList.copyOf(carried.stacks()));
		}

		public void clear(Preset preset) {
			if (laidOut) putKit.accept(preset, new Kit());
			else putList.accept(preset, new ItemList());
		}

		/** What {@link #shuffle} fills it from; none, and it has no shuffle. */
		public Items shuffles(Loot.Template template) {
			this.template = template;
			return this;
		}

		public boolean canShuffle() {
			return template != null;
		}

		/** Filled afresh at random from its template, a loadout worn as it would be. */
		public void shuffle(Preset preset) {
			if (template == null) return;
			List<net.minecraft.world.item.ItemStack> rolled = Loot.roll(template);
			if (laidOut) putKit.accept(preset, Kit.dressed(rolled));
			else putList.accept(preset, ItemList.copyOf(rolled));
		}

		@Override
		public String display(Preset preset) {
			return laidOut ? kit.apply(preset).describe(3) : list.apply(preset).describe(3);
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			if (value.equalsIgnoreCase("clear") || value.equalsIgnoreCase("none")) {
				clear(preset);
				return null;
			}
			return "Items are set by holding them: use /pvp preset items";
		}

		@Override
		public List<String> suggestions(Preset preset) {
			return List.of("clear");
		}
	}

	/** A button rather than a value: add a layer, remove a pinata. */
	public static final class Action extends Field {
		public final String button;
		private final Consumer<Preset> run;

		public Action(Section section, String key, String label, String button, Consumer<Preset> run) {
			super(section, key, label);
			this.button = button;
			this.run = run;
		}

		public void run(Preset preset) {
			run.accept(preset);
		}

		@Override
		public String display(Preset preset) {
			return button;
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			run(preset);
			return null;
		}
	}

	/**
	 * Words rather than a setting: a line of the match written out, or, where it is {@code warm},
	 * something the game does that the setting beside it doesn't say. Shown only when it has words.
	 */
	public static final class Note extends Field {
		public final boolean warm;
		private final Function<Preset, @Nullable String> words;

		public Note(Section section, String key, boolean warm, Function<Preset, @Nullable String> words) {
			super(section, key, "");
			this.warm = warm;
			this.words = words;
			when(preset -> words.apply(preset) != null);
		}

		@Override
		public String display(Preset preset) {
			String said = words.apply(preset);
			return said == null ? "" : said;
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			return "Nothing to set";
		}
	}

	/**
	 * A picture for each of a set of things, clicked to step each one on: every mob by its spawn
	 * egg, say. What a cell shows and what a click does are the setting's to say.
	 */
	public static final class Grid extends Field {
		/**
		 * One cell. {@code mark} is the few letters on it; a {@code dim} one is off; {@code accent}
		 * is the colour of the stripe down its side, or 0 for none.
		 */
		public record Cell(String key, String item, String mark, boolean dim, int accent, String tooltip, boolean small, String badge) {
			public Cell(String key, String item, String mark, boolean dim, int accent, String tooltip) {
				this(key, item, mark, dim, accent, tooltip, false, "");
			}
		}

		private final Function<Preset, List<Cell>> cells;
		private final BiConsumer<Preset, String> press;
		private final Function<Preset, String> summary;

		public Grid(Section section, String key, String label, Function<Preset, List<Cell>> cells,
				BiConsumer<Preset, String> press, Function<Preset, String> summary) {
			super(section, key, label);
			this.cells = cells;
			this.press = press;
			this.summary = summary;
		}

		public List<Cell> cells(Preset preset) {
			return cells.apply(preset);
		}

		public void press(Preset preset, String cell) {
			press.accept(preset, cell);
		}

		@Override
		public String display(Preset preset) {
			return summary.apply(preset);
		}

		/** From typed words: the cell named is clicked once. */
		@Override
		public @Nullable String set(Preset preset, String value) {
			for (Cell cell : cells(preset)) {
				if (cell.key().equalsIgnoreCase(value) || cell.key().equalsIgnoreCase("minecraft:" + value)) {
					press(preset, cell.key());
					return null;
				}
			}
			return "Not one of these";
		}

		@Override
		public List<String> suggestions(Preset preset) {
			return cells(preset).stream().map(Cell::key).toList();
		}
	}

	/**
	 * A picture of the preset, drawn afresh from it every time: the arena from above, the match's
	 * clock. {@code height} is how many rows it stands in.
	 */
	public static final class Picture extends Field {
		/** A word under the picture and the colour it's written in, saying what a colour means. */
		public record Key(String words, int colour) {}

		/** Cells a palette index each, {@code columns} across, row by row. */
		public record Canvas(int columns, int rows, int[] palette, byte[] cells, List<Key> key) {}

		public final int height;
		private final Function<Preset, Canvas> draw;

		public Picture(Section section, String key, int height, Function<Preset, Canvas> draw) {
			super(section, key, "");
			this.height = height;
			this.draw = draw;
		}

		public Canvas draw(Preset preset) {
			return draw.apply(preset);
		}

		@Override
		public String display(Preset preset) {
			return String.join(", ", draw(preset).key().stream().map(Key::words).toList());
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			return "Nothing to set";
		}
	}

	/** A heading between groups of settings, with nothing to change. */
	public static final class Heading extends Field {
		private final Supplier<String> words;

		public Heading(Section section, String key, String label) {
			this(section, key, label, () -> "");
		}

		public Heading(Section section, String key, String label, Supplier<String> words) {
			super(section, key, label);
			this.words = words;
		}

		@Override
		public String display(Preset preset) {
			return words.get();
		}

		@Override
		public @Nullable String set(Preset preset, String value) {
			return "Nothing to set";
		}
	}
}
