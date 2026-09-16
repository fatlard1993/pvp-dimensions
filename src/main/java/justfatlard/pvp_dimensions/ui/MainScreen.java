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
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Places;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.preset.Field;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * The menu: the arena you are in, the arenas running, and the presets to start one from, a tile
 * each. Picking a tile shows how it will start, who is invited and whether the frame beside you
 * is lit, and any settings its admin let users change for their own game, with one button to go.
 * An admin's New asks first what kind of match it's to be.
 */
public final class MainScreen {
	private MainScreen() {}

	public static final String TYPE = "pvp-dimensions:menu";

	private static final int WIDTH = 236;
	private static final int TOP = 20;
	private static final int GAP = 4;
	private static final int ROW = 22;
	private static final int ARENA_ROWS = 3;
	/** Rows of presets shown before they scroll, so the menu fits the smallest window. */
	private static final int PRESET_ROWS = 4;

	/**
	 * How a player means to start the preset they picked, while they are choosing: {@code draft} is
	 * their own copy of it, carrying whatever they have changed of what users may change.
	 */
	private record Choice(String preset, boolean everyone, boolean portal, Preset draft) {}

	private static final Map<UUID, Choice> choosing = new ConcurrentHashMap<>();
	/** Rows of settings a user may change shown before they scroll. */
	private static final int ADJUST_ROWS = 2;
	private static final int ADJUST_CONTROL = 134;
	private static final Map<UUID, String> open = new ConcurrentHashMap<>();

	static void register(ScreenApi screens) {
		screens.onActionFallback(TYPE, MainScreen::pressed);
		screens.onClose(TYPE, player -> {
			open.remove(player.getUUID());
			choosing.remove(player.getUUID());
		});
	}

	static void forget(UUID player) {
		open.remove(player);
		choosing.remove(player);
	}

	public static void show(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Access.Tier tier = Access.of(player);
		List<ComponentBuilder> under = new ArrayList<>();
		List<ComponentBuilder> over = new ArrayList<>();
		int inner = WIDTH - PAD * 2;
		int y = TOP;

		Arena mine = Arenas.of(player);
		if (mine != null) {
			over.add(icon("mine_icon", PAD, y + 2, mine.preset.icon, 1F));
			under.add(text("mine", PAD + ICON + 4, y + 6, clip("In " + mine.title(), 22)));
			int x = PAD + inner;
			if (mine.preset.exitCommand || tier == Access.Tier.ADMIN) {
				x -= 46;
				under.add(button("leave", x, y, 46, BUTTON, "Leave", "Go home"));
			}
			if (mine.phase == Arena.Phase.LOBBY && (mine.host.equals(player.getUUID()) || tier == Access.Tier.ADMIN)) {
				x -= 50;
				under.add(button("begin", x, y, 48, BUTTON, "Fight!", "Start the fight now").prop(ComponentType.PROP_STYLE, "accepted"));
			}
			y += ROW + 4;
		}

		List<Arena> running = Arenas.running(server);
		List<ComponentBuilder> arenaRows = new ArrayList<>();
		List<ComponentBuilder> rows = new ArrayList<>();
		int rowCount = 0;
		int rowsY = 0;
		if (!running.isEmpty()) {
			heading(under, over, "running", y, "minecraft:clock", "Running");
			y += 20;
			int rowY = 0;
			for (Arena arena : running) {
				String id = arena.id;
				arenaRows.add(icon("a_icon:" + id, 2, rowY + 2, arena.preset.icon, 1F));
				arenaRows.add(text("a_name:" + id, ICON + 6, rowY + 1, clip(arena.title(), 20)));
				arenaRows.add(faint("a_state:" + id, ICON + 6, rowY + 11, state(arena)));
				int x = inner - 6;
				boolean canEnd = tier == Access.Tier.ADMIN || arena.host.equals(player.getUUID());
				if (canEnd) {
					x -= 34;
					arenaRows.add(button("end:" + id, x, rowY, 34, BUTTON, "End", "End " + arena.title() + " and send everyone home"));
				}
				if (mine == null && arena.open() && (arena.invitedOrOpen(player.getUUID()) || tier == Access.Tier.ADMIN)) {
					x -= 38;
					arenaRows.add(button("join:" + id, x, rowY, 36, BUTTON, "Join", "Go into " + arena.title()).prop(ComponentType.PROP_STYLE, "accepted"));
				}
				rowY += ROW;
			}
		}
		int arenaListY = y;
		int shownArenas = Math.min(running.size(), ARENA_ROWS);
		y += shownArenas * ROW + (shownArenas > 0 ? 4 : 0);

		List<ComponentBuilder> adjustRows = new ArrayList<>();
		int adjustY = 0;
		int adjustCount = 0;
		if (tier.atLeast(Access.Tier.USER)) {
			heading(under, over, "start", y, "minecraft:iron_sword", tier == Access.Tier.ADMIN ? "Start or edit a game" : "Start a game");
			if (tier == Access.Tier.ADMIN) under.add(button("new", PAD + inner - 46, y - 2, 46, BUTTON, "New", "Make a new preset, from a kind of match or one shared with you"));
			y += 20;
			List<Map.Entry<String, Preset>> presets = new ArrayList<>();
			for (Map.Entry<String, Preset> entry : Presets.all()) {
				if (tier == Access.Tier.ADMIN || entry.getValue().forUsers) presets.add(entry);
			}
			Choice choice = choosing.get(player.getUUID());
			int rowY = 0;
			for (Map.Entry<String, Preset> entry : presets) {
				String id = entry.getKey();
				Preset preset = entry.getValue();
				ComponentBuilder row = KindScreen.row(rows, "pick:" + id, rowY, inner - 6 - (tier == Access.Tier.ADMIN ? 32 : 0),
					preset.icon, preset.name, describe(preset), preset.name + ": " + describe(preset));
				if (choice != null && choice.preset().equals(id)) row.prop(ComponentType.PROP_STYLE, "pressed");
				if (tier == Access.Tier.ADMIN) {
					rows.add(button("edit:" + id, inner - 6 - 30, rowY + 2, 30, 16, "Edit", "Edit " + preset.name));
				}
				rowY += ROW;
			}
			if (presets.isEmpty()) {
				rows.add(faint("no_presets", 2, 8, tier == Access.Tier.ADMIN ? "No presets yet; New makes one" : "Nothing to start yet"));
			}
			// One row at least: with nothing to list, the row says so.
			rowCount = Math.max(presets.size(), 1);
			rowsY = y;
			y += Math.min(rowCount, PRESET_ROWS) * ROW + 2;

			if (choice != null && Presets.get(choice.preset()) != null) {
				Preset preset = Presets.get(choice.preset());
				over.add(icon("go_icon", PAD, y + 2, preset.icon, 1F));
				under.add(text("go_words", PAD + ICON + 4, y + 6, clip("Start " + preset.name, 26)));
				y += ROW;
				int adjY = 0;
				for (String key : Fields.adjustableKeys()) {
					if (!preset.adjustable.contains(key)) continue;
					Field field = Fields.find(choice.draft(), key);
					if (!(field instanceof Field.Number) && !(field instanceof Field.Choice)) continue;
					int x = inner - 6 - ADJUST_CONTROL;
					adjustRows.add(text("adj_l:" + key, 2, adjY + 6, clip(field.label, 16)));
					adjustRows.add(button("adj_prev:" + key, x, adjY, 20, BUTTON, "<", field.help()));
					adjustRows.add(centred("adj_v:" + key, x + 20, adjY + 6, ADJUST_CONTROL - 40, clip(field.display(choice.draft()), 15)));
					adjustRows.add(button("adj_next:" + key, x + ADJUST_CONTROL - 20, adjY, 20, BUTTON, ">", field.help()));
					adjY += ROW;
					adjustCount++;
				}
				adjustY = y;
				y += Math.min(adjustCount, ADJUST_ROWS) * ROW + (adjustCount > 0 ? 2 : 0);
				int third = (inner - GAP * 2) / 3;
				under.add(button("everyone", PAD, y, third, BUTTON, choice.everyone() ? "Invite all" : "Invite none",
					choice.everyone() ? "Everyone online is invited" : "Only a lit frame, or /pvp invite, lets anyone in"));
				boolean canLight = !Places.isArena(player.level().dimension());
				under.add(button("portal", PAD + third + GAP, y, third, BUTTON, choice.portal() && canLight ? "Light frame" : "No frame",
					canLight ? "Light the empty obsidian frame nearest you as a way in" : "Frames are lit out in the world")
					.prop(ComponentType.PROP_ENABLED, String.valueOf(canLight)));
				under.add(button("go", PAD + (third + GAP) * 2, y, inner - (third + GAP) * 2, BUTTON, "Start", "Make the arena and go in")
					.prop(ComponentType.PROP_STYLE, "accepted"));
				y += ROW + 2;
			}
		} else if (running.isEmpty() && mine == null) {
			under.add(text("nothing", PAD, y + 4, "No arenas running"));
			y += ROW;
		}

		int height = y + PAD - 2;
		ScreenBuilder screen = new ScreenBuilder(TYPE).title("PvP Dimensions").pauseGame(false).size(WIDTH, height);
		screen.panel("dialog", 0, 0, WIDTH, height, panel());
		screen.component(text("title", PAD, 7, "PvP Dimensions"));
		for (ComponentBuilder component : under) screen.component(component);
		for (ComponentBuilder component : over) screen.component(component);
		if (shownArenas > 0) {
			List<justfatlard.pandorical.protocol.ComponentDef> defs = new ArrayList<>();
			for (ComponentBuilder row : arenaRows) defs.add(row.build());
			screen.scrollPanel("arenas", PAD, arenaListY, inner, shownArenas * ROW, Map.of(
				ComponentType.PROP_ITEM_HEIGHT, String.valueOf(ROW),
				ComponentType.PROP_VISIBLE_ITEMS, String.valueOf(shownArenas),
				ComponentType.PROP_TOTAL_ITEMS, String.valueOf(running.size()),
				ComponentType.PROP_SHOW_SCROLLBAR, String.valueOf(running.size() > ARENA_ROWS),
				ComponentType.PROP_BACKGROUND, "#00000000"), defs);
		}
		if (adjustCount > 0) {
			List<justfatlard.pandorical.protocol.ComponentDef> defs = new ArrayList<>();
			for (ComponentBuilder row : adjustRows) defs.add(row.build());
			int shownRows = Math.min(adjustCount, ADJUST_ROWS);
			screen.scrollPanel("adjust", PAD, adjustY, inner, shownRows * ROW, Map.of(
				ComponentType.PROP_ITEM_HEIGHT, String.valueOf(ROW),
				ComponentType.PROP_VISIBLE_ITEMS, String.valueOf(shownRows),
				ComponentType.PROP_TOTAL_ITEMS, String.valueOf(adjustCount),
				ComponentType.PROP_SHOW_SCROLLBAR, String.valueOf(adjustCount > ADJUST_ROWS),
				ComponentType.PROP_BACKGROUND, "#00000000"), defs);
		}
		if (!rows.isEmpty()) {
			List<justfatlard.pandorical.protocol.ComponentDef> defs = new ArrayList<>();
			for (ComponentBuilder row : rows) defs.add(row.build());
			int shownRows = Math.min(rowCount, PRESET_ROWS);
			screen.scrollPanel("presets", PAD, rowsY, inner, shownRows * ROW, Map.of(
				ComponentType.PROP_ITEM_HEIGHT, String.valueOf(ROW),
				ComponentType.PROP_VISIBLE_ITEMS, String.valueOf(shownRows),
				ComponentType.PROP_TOTAL_ITEMS, String.valueOf(Math.max(rowCount, shownRows)),
				ComponentType.PROP_SHOW_SCROLLBAR, String.valueOf(rowCount > PRESET_ROWS),
				ComponentType.PROP_BACKGROUND, "#00000000"), defs);
		}
		PandoricalApi.screens().open(player, screen.build());
		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId != null) open.put(player.getUUID(), screenId);
	}

	/** Rebuilt for everyone looking at it, when arenas start or end. */
	public static void refreshAll(MinecraftServer server) {
		for (UUID id : List.copyOf(open.keySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			String screenId = open.get(id);
			if (player == null || screenId == null || !screenId.equals(PandoricalApi.getOpenScreenId(id))) {
				open.remove(id);
				continue;
			}
			show(player);
		}
	}

	private static String state(Arena arena) {
		long now = System.currentTimeMillis();
		String when = switch (arena.phase) {
			case GENERATING -> "getting ready";
			case LOBBY -> "waiting room";
			case LIVE -> arena.endsAt > 0 ? Arenas.clock(Math.max(0, arena.endsAt - now)) + " left" : "for good";
			case ENDED -> "over";
		};
		return arena.inside().size() + " inside, " + when;
	}

	/** A preset in a line: what it is played for, with how many sides and how long it lasts. */
	private static String describe(Preset preset) {
		String mode = preset.teamsOn() ? preset.teams + " teams" : "solo";
		return Fields.goalWord(preset) + " · " + mode + " · " + Fields.shortDuration(preset.lifeMinutes);
	}

	private static void heading(List<ComponentBuilder> under, List<ComponentBuilder> over, String id, int y, String item, String words) {
		over.add(icon(id + "_heading_icon", PAD, y, item, 1F));
		under.add(text(id + "_heading", PAD + ICON + 4, y + 4, words));
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		if (id == null) return;
		MinecraftServer server = player.level().getServer();
		Access.Tier tier = Access.of(player);
		switch (id) {
			case "leave" -> {
				close(player);
				Visit visit = Visit.of(player);
				if (visit != null) Travel.goHome(player, Arenas.get(server, visit.arena()), Travel.Why.COMMAND);
			}
			case "begin" -> {
				Arena arena = Arenas.of(player);
				if (arena != null && (arena.host.equals(player.getUUID()) || tier == Access.Tier.ADMIN)) {
					close(player);
					Arenas.goLive(server, arena);
				}
			}
			case "new" -> {
				if (tier != Access.Tier.ADMIN) return;
				choosing.remove(player.getUUID());
				KindScreen.show(player);
			}
			case "everyone", "portal" -> {
				Choice choice = choosing.get(player.getUUID());
				if (choice == null) return;
				choosing.put(player.getUUID(), id.equals("everyone")
					? new Choice(choice.preset(), !choice.everyone(), choice.portal(), choice.draft())
					: new Choice(choice.preset(), choice.everyone(), !choice.portal(), choice.draft()));
				show(player);
			}
			case "go" -> start(player);
			default -> {
				int colon = id.indexOf(':');
				if (colon < 0) return;
				String kind = id.substring(0, colon);
				String rest = id.substring(colon + 1);
				switch (kind) {
					case "pick" -> {
						Choice before = choosing.get(player.getUUID());
						Preset picked = Presets.get(rest);
						if (picked == null || before != null && before.preset().equals(rest)) choosing.remove(player.getUUID());
						else choosing.put(player.getUUID(), new Choice(rest, before == null || before.everyone(), before == null || before.portal(), Presets.copy(picked)));
						show(player);
					}
					case "adj_prev", "adj_next" -> {
						Choice choice = choosing.get(player.getUUID());
						Preset preset = choice == null ? null : Presets.get(choice.preset());
						if (preset == null || !preset.adjustable.contains(rest)) return;
						Field field = Fields.find(choice.draft(), rest);
						int by = kind.equals("adj_next") ? 1 : -1;
						if (field instanceof Field.Number number) number.step(choice.draft(), by);
						else if (field instanceof Field.Choice options) options.step(choice.draft(), by);
						show(player);
					}
					case "edit" -> {
						if (tier == Access.Tier.ADMIN) EditorScreen.show(player, rest, Field.Section.GAME);
					}
					case "join" -> {
						Arena arena = Arenas.get(server, rest);
						if (arena != null) {
							close(player);
							Travel.join(player, arena);
						}
					}
					case "end" -> {
						Arena arena = Arenas.get(server, rest);
						if (arena != null && (tier == Access.Tier.ADMIN || arena.host.equals(player.getUUID()))) {
							Arenas.end(server, arena, "ended by " + player.getGameProfile().name());
							show(player);
						}
					}
					default -> { }
				}
			}
		}
	}

	private static void start(ServerPlayer player) {
		Choice choice = choosing.remove(player.getUUID());
		if (choice == null) return;
		Preset preset = Presets.get(choice.preset());
		Access.Tier tier = Access.of(player);
		if (preset == null || !tier.atLeast(Access.Tier.USER) || (tier != Access.Tier.ADMIN && !preset.forUsers)) return;
		if (Visit.of(player) != null) {
			Say.to(player, "Leave your arena before starting another");
			return;
		}
		close(player);
		Arenas.Start options = new Arenas.Start(choice.everyone() ? Arenas.Invite.EVERYONE : Arenas.Invite.NOBODY, List.of(),
			choice.portal() && !Places.isArena(player.level().dimension()), true);
		Arena arena = Arenas.start(player.level().getServer(), player, choice.preset(), choice.draft(), options, words -> Say.to(player, words));
		if (arena != null) Say.to(player, "Making " + arena.title() + "; you'll be taken in when it's ready");
	}

	private static void close(ServerPlayer player) {
		String screenId = open.remove(player.getUUID());
		if (screenId != null) PandoricalApi.screens().close(player, screenId);
	}
}
