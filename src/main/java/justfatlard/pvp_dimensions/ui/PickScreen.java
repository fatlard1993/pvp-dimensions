package justfatlard.pvp_dimensions.ui;

import java.util.List;
import java.util.Map;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Loadouts;
import justfatlard.pvp_dimensions.arena.Teams;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.preset.Kit;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static justfatlard.pvp_dimensions.ui.Ui.*;

/**
 * What a player picks on the way in: a team, when they arrive with the fight already on and so
 * never stood on a colour in a waiting room, and a loadout where there are several. Teams that
 * would be made uneven can't be joined; a loadout at its cap can't be taken, and says who has it,
 * as far as its cap lets them know. A loadout picked closes the screen; so does Done.
 */
public final class PickScreen {
	private PickScreen() {}

	public static final String TYPE = "pvp-dimensions:pick";
	private static final int WIDTH = 236;
	private static final int ROW = 30;
	private static final int SHOWN = 5;

	static void register(ScreenApi screens) {
		screens.onActionFallback(TYPE, PickScreen::pressed);
	}

	/** Whether this arena has a team to pick now: a fight already on, with teams. */
	public static boolean teamsToPick(Arena arena) {
		return arena.phase == Arena.Phase.LIVE && arena.preset.teamsOn();
	}

	public static void show(ServerPlayer player, Arena arena) {
		Preset preset = arena.preset;
		Arena.Member member = arena.member(player.getUUID());
		if (member == null) return;
		int inner = WIDTH - PAD * 2;
		boolean teams = teamsToPick(arena);
		boolean loadouts = preset.picksLoadout();
		int y = 20;
		ScreenBuilder screen = new ScreenBuilder(TYPE).title(teams ? "Join the fight" : "Pick a loadout").pauseGame(false);
		List<justfatlard.pandorical.api.ComponentBuilder> parts = new java.util.ArrayList<>();
		parts.add(text("title", PAD, 7, teams ? "Join the fight" : "Pick a loadout"));

		if (teams) {
			parts.add(faint("team_note", PAD, y, "Your team, while you've only just arrived"));
			y += 12;
			int each = (inner - 2 * (preset.teams - 1)) / preset.teams;
			for (int team = 0; team < preset.teams; team++) {
				TeamColors.Colour colour = TeamColors.of(team);
				boolean open = team == member.team || Teams.open(arena, team, member);
				var button = button("team:" + team, PAD + team * (each + 2), y, each, BUTTON, clip(colour.name(), 7) + " " + arena.teamSize(team),
					open ? "Join " + colour.name() : "Too many on " + colour.name() + " already")
					.prop(ComponentType.PROP_ACCENT, String.format("#%08X", 0xFF000000 | colour.team().rgb()))
					.prop(ComponentType.PROP_ENABLED, String.valueOf(open));
				if (team == member.team) button.prop(ComponentType.PROP_STYLE, "pressed");
				parts.add(button);
			}
			y += BUTTON + 6;
		}

		if (loadouts) {
			parts.add(faint("loadout_note", PAD, y, "Picked just after spawning, it swaps in"));
			y += 12;
			for (int i = 0; i < preset.loadoutCount(); i++) {
				boolean mine = member.loadout == i;
				boolean full = !mine && Loadouts.full(arena, i, member);
				Kit kit = preset.loadoutKit(i);
				List<Arena.Member> holders = Loadouts.holders(arena, i, member);
				String who = holders.isEmpty() ? "" : "Taken by " + String.join(", ", holders.stream().map(holder -> holder.name).toList()) + ". ";
				String cap = Loadouts.capWords(arena, i);
				var row = button("pick:" + i, PAD, y, inner, ROW - 2, "", who + (cap.isEmpty() ? "" : cap.substring(0, 1).toUpperCase() + cap.substring(1) + ". ") + kit.describe(5))
					.prop(ComponentType.PROP_ENABLED, String.valueOf(!full));
				if (mine) row.prop(ComponentType.PROP_STYLE, "pressed");
				parts.add(row);
				parts.add(icon("icon:" + i, PAD + 5, y + 6, Loadouts.icon(preset, i), 1F));
				int having = Loadouts.taken(arena, i, member) + (mine ? 1 : 0);
				String count = preset.loadoutCap(i) > 0 ? "  " + having + "/" + preset.loadoutCap(i) : "";
				parts.add(text("name:" + i, PAD + 26, y + 5, clip(preset.loadoutName(i), 14) + count + (mine ? "  ✔" : "")));
				String under = full ? "Full" : Loadouts.respawnWords(preset, i);
				if (!under.isEmpty()) parts.add(faint("respawn:" + i, PAD + 26, y + 16, clip(under, 22)));
				List<ItemStack> stacks = kit.stacks();
				for (int k = 1; k < stacks.size() && k <= SHOWN; k++) {
					ItemStack stack = stacks.get(k);
					int x = PAD + inner - 4 - (SHOWN - k + 1) * 17;
					parts.add(icon("kit:" + i + ":" + k, x, y + 6, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
				}
				y += ROW;
			}
		}
		parts.add(button("done", PAD + inner - 50, y + 2, 50, BUTTON, "Done", "Keep what you have"));
		y += BUTTON + 2 + PAD;

		screen.size(WIDTH, y);
		screen.panel("dialog", 0, 0, WIDTH, y, panel());
		for (var part : parts) screen.component(part);
		PandoricalApi.screens().open(player, screen.build());
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		if (id == null) return;
		Arena arena = Arenas.of(player);
		Arena.Member member = arena == null ? null : arena.member(player.getUUID());
		if (member == null || id.equals("done")) {
			close(player);
			return;
		}
		try {
			if (id.startsWith("team:")) {
				String problem = Travel.changeTeam(player, arena, member, Integer.parseInt(id.substring(5)));
				if (problem != null) Say.bar(player, problem);
				show(player, arena);
			} else if (id.startsWith("pick:")) {
				Loadouts.pick(player, arena, member, Integer.parseInt(id.substring(5)));
				close(player);
			}
		} catch (NumberFormatException ignored) {
			close(player);
		}
	}

	private static void close(ServerPlayer player) {
		String open = PandoricalApi.getOpenScreenId(player.getUUID());
		if (open != null) PandoricalApi.screens().close(player, open);
	}
}
