package justfatlard.pvp_dimensions.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.HudBuilder;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.protocol.ComponentUpdate;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The scoreboard in the top-left corner of a Pandorical player's screen while a fight is on: each
 * side with its colour or face, its score, and a bar that fills toward the win and eases as it
 * moves. Whoever holds the hill or carries a flag is lit. Players without Pandorical have the same
 * on the action bar, and players with it are spared the action bar's copy.
 *
 * <p>Sent whole when what it shows changes shape, a line more or less, and otherwise only the
 * changes each second, which is what lets the bars glide.
 */
public final class GameHud {
	private GameHud() {}

	private static final String OVERLAY = "pvp-dimensions:scoreboard";
	private static final int WIDTH = 126;
	private static final int ROW = 14;
	private static final int TOP = 16;
	private static final int BAR = WIDTH - 19;
	private static final String LIT = "#FFFFE066";
	private static final String PLAIN = "#FFFFFFFF";

	/** What each player was last shown, to tell a change of shape from a change of numbers. */
	private static final Map<UUID, String> shapes = new ConcurrentHashMap<>();

	public static boolean showing(ServerPlayer player) {
		return shapes.containsKey(player.getUUID());
	}

	/** Everyone inside a live arena, their board brought up to date. */
	public static void tick(MinecraftServer server, Arena arena) {
		if (arena.phase != Arena.Phase.LIVE) return;
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || !PandoricalApi.isAvailable(player) || !PandoricalApi.hasCapability(player, Capabilities.HUD)) continue;
			Scoreboard.Board board = Scoreboard.of(server, arena, member);
			if (board == null) hide(player);
			else show(player, board);
		}
	}

	private static void show(ServerPlayer player, Scoreboard.Board board) {
		String shape = shape(board);
		if (shape.equals(shapes.get(player.getUUID()))) {
			PandoricalApi.hud().update(player, OVERLAY, updates(board));
			return;
		}
		shapes.put(player.getUUID(), shape);
		PandoricalApi.hud().show(player, build(board).build());
	}

	public static void hide(ServerPlayer player) {
		if (shapes.remove(player.getUUID()) != null && PandoricalApi.isAvailable(player)) PandoricalApi.hud().hide(player, OVERLAY);
	}

	public static void forget(UUID player) {
		shapes.remove(player);
	}

	/** The lines' identities, what the board has and not what it says. */
	private static String shape(Scoreboard.Board board) {
		StringBuilder shape = new StringBuilder();
		for (Scoreboard.Line line : board.lines()) shape.append(line.key()).append(line.face() != null ? "@" + line.face() : "").append('|');
		return shape.append(board.footer() != null).toString();
	}

	private static int height(Scoreboard.Board board) {
		return TOP + board.lines().size() * ROW + (board.footer() != null ? 22 : 2);
	}

	private static HudBuilder build(Scoreboard.Board board) {
		HudBuilder hud = new HudBuilder(OVERLAY).anchor("top_left").offset(4, 4);
		hud.component(panel("back", 0, 0, WIDTH, height(board), "#A0101010").prop(ComponentType.PROP_BORDER, "flat")
			.prop(ComponentType.PROP_BORDER_COLOR, "#50FFFFFF"));
		hud.component(text("title", 4, 4, WIDTH - 8, board.title()).prop(ComponentType.PROP_COLOR, "#FFFFD27F"));
		hud.component(text("goal", WIDTH - 60, 4, 56, board.goal()).prop(ComponentType.PROP_ALIGN, "right").prop(ComponentType.PROP_COLOR, "#FFA8A8A8"));
		List<Scoreboard.Line> lines = board.lines();
		for (int i = 0; i < lines.size(); i++) {
			Scoreboard.Line line = lines.get(i);
			int y = TOP + i * ROW;
			String id = "r" + i;
			hud.component(panel(id + "_lit", 2, y - 2, WIDTH - 4, ROW, line.lit() ? "#30FFFFFF" : "#00000000"));
			if (line.face() != null) {
				hud.component(new ComponentBuilder(id + "_chip", ComponentType.PLAYER_FACE).bounds(4, y, 8, 8)
					.prop(ComponentType.PROP_PLAYER, line.face().toString()));
			} else {
				hud.component(panel(id + "_chip", 4, y, 8, 8, colour(line.colour())));
			}
			hud.component(text(id + "_label", 15, y, WIDTH - 64, line.label()).prop(ComponentType.PROP_COLOR, line.lit() ? LIT : PLAIN));
			hud.component(text(id + "_value", WIDTH - 50, y, 46, line.value()).prop(ComponentType.PROP_ALIGN, "right")
				.prop(ComponentType.PROP_COLOR, "#FFD8D8D8"));
			hud.component(panel(id + "_track", 15, y + 9, BAR, 3, "#50000000"));
			hud.component(panel(id + "_fill", 15, y + 9, fill(line), 3, colour(line.colour())).prop(ComponentType.PROP_INTERP_TICKS, "10"));
		}
		if (board.footer() != null) {
			hud.component(text("footer", 4, TOP + lines.size() * ROW + 1, WIDTH - 8, board.footer())
				.prop(ComponentType.PROP_WRAP_WIDTH, String.valueOf(WIDTH - 8)).prop(ComponentType.PROP_MAX_LINES, "2")
				.prop(ComponentType.PROP_COLOR, "#FFBFBFBF"));
		}
		return hud;
	}

	private static List<ComponentUpdate> updates(Scoreboard.Board board) {
		List<ComponentUpdate> updates = new ArrayList<>();
		updates.add(new ComponentUpdate("title", Map.of(ComponentType.PROP_TEXT, board.title())));
		updates.add(new ComponentUpdate("goal", Map.of(ComponentType.PROP_TEXT, board.goal())));
		List<Scoreboard.Line> lines = board.lines();
		for (int i = 0; i < lines.size(); i++) {
			Scoreboard.Line line = lines.get(i);
			String id = "r" + i;
			updates.add(new ComponentUpdate(id + "_lit", Map.of(ComponentType.PROP_BACKGROUND, line.lit() ? "#30FFFFFF" : "#00000000")));
			if (line.face() == null) updates.add(new ComponentUpdate(id + "_chip", Map.of(ComponentType.PROP_BACKGROUND, colour(line.colour()))));
			updates.add(new ComponentUpdate(id + "_label", Map.of(ComponentType.PROP_TEXT, line.label(), ComponentType.PROP_COLOR, line.lit() ? LIT : PLAIN)));
			updates.add(new ComponentUpdate(id + "_value", Map.of(ComponentType.PROP_TEXT, line.value())));
			updates.add(new ComponentUpdate(id + "_fill", Map.of(ComponentType.PROP_WIDTH, String.valueOf(fill(line)),
				ComponentType.PROP_BACKGROUND, colour(line.colour()))));
		}
		if (board.footer() != null) updates.add(new ComponentUpdate("footer", Map.of(ComponentType.PROP_TEXT, board.footer())));
		return updates;
	}

	private static int fill(Scoreboard.Line line) {
		return Math.round(BAR * line.fill());
	}

	private static ComponentBuilder panel(String id, int x, int y, int w, int h, String background) {
		return new ComponentBuilder(id, ComponentType.PANEL).bounds(x, y, w, h)
			.prop(ComponentType.PROP_BACKGROUND, background).prop(ComponentType.PROP_BORDER, "none");
	}

	private static ComponentBuilder text(String id, int x, int y, int w, @Nullable String words) {
		return new ComponentBuilder(id, ComponentType.TEXT).bounds(x, y, w, 9)
			.prop(ComponentType.PROP_TEXT, words == null ? "" : words).prop(ComponentType.PROP_SHADOW, "true");
	}

	private static String colour(int argb) {
		return String.format("#%08X", argb);
	}
}
