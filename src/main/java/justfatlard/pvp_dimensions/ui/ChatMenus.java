package justfatlard.pvp_dimensions.ui;

import java.util.List;
import java.util.Map;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.preset.Field;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * The menus as clickable chat, for a player whose client has no Pandorical: the same choices, as
 * words to click, and every one of them a command that can be typed as well.
 */
public final class ChatMenus {
	private ChatMenus() {}

	public static void main(ServerPlayer player) {
		Access.Tier tier = Access.of(player);
		Say.to(player, Component.literal("PvP Dimensions").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

		Arena mine = Arenas.of(player);
		if (mine != null) {
			MutableComponent line = Say.line("You're in " + mine.title() + " ");
			if (mine.preset.exitCommand || tier == Access.Tier.ADMIN) line.append(Say.button("Leave", "/pvp leave"));
			if (mine.phase == Arena.Phase.LOBBY && (mine.host.equals(player.getUUID()) || tier == Access.Tier.ADMIN)) {
				line.append(" ").append(Say.button("Start the fight", "/pvp begin"));
			}
			Say.to(player, line);
		}

		List<Arena> running = Arenas.running(player.level().getServer());
		if (!running.isEmpty()) {
			Say.to(player, Say.line("Running:"));
			for (Arena arena : running) {
				MutableComponent line = Component.literal("  ").append(Arenas.summary(arena)).append(" ");
				if (mine == null && arena.open() && (arena.invitedOrOpen(player.getUUID()) || tier == Access.Tier.ADMIN)) {
					line.append(Say.button("Join", "/pvp join " + arena.id));
				}
				if (tier == Access.Tier.ADMIN || arena.host.equals(player.getUUID())) line.append(" ").append(Say.button("End", "/pvp end " + arena.id));
				Say.to(player, line);
			}
		}

		if (tier.atLeast(Access.Tier.USER)) {
			MutableComponent line = Say.line("Start: ");
			boolean any = false;
			for (Map.Entry<String, Preset> entry : Presets.all()) {
				if (tier != Access.Tier.ADMIN && !entry.getValue().forUsers) continue;
				line.append(Say.button(entry.getValue().name, "/pvp start " + entry.getKey())).append(" ");
				any = true;
			}
			if (any) Say.to(player, line);
			else Say.to(player, "No presets to start from yet");
		}

		if (tier == Access.Tier.ADMIN) {
			MutableComponent line = Say.line("Edit: ");
			for (Map.Entry<String, Preset> entry : Presets.all()) {
				line.append(Say.button(entry.getValue().name, "/pvp preset show " + entry.getKey())).append(" ");
			}
			line.append(Say.button("New", "/pvp preset new"));
			Say.to(player, line);
		}
	}

	/** The kinds of match a new preset can start from, a family a line. */
	public static void kinds(ServerPlayer player) {
		Say.to(player, "What kind of match?");
		for (justfatlard.pvp_dimensions.preset.Kinds.Family family : justfatlard.pvp_dimensions.preset.Kinds.Family.values()) {
			MutableComponent line = Say.line("  " + family.label + ": ");
			boolean any = false;
			for (justfatlard.pvp_dimensions.preset.Kinds kind : justfatlard.pvp_dimensions.preset.Kinds.values()) {
				if (kind.family != family || !kind.here()) continue;
				line.append(Say.button(kind.label, "/pvp preset new kind " + kind.name())).append(Component.literal(" "));
				any = true;
			}
			if (any) Say.to(player, line);
		}
		Say.to(player, Say.line("Or ").append(Say.suggest("import one", "/pvp preset import ")).append(Component.literal(" shared with this server")));
	}

	/** One section of a preset, a row a setting, each with the clicks that change it. */
	public static void preset(ServerPlayer player, String id, Field.Section section) {
		Preset preset = Presets.get(id);
		if (preset == null) {
			Say.to(player, "No preset called " + id);
			return;
		}
		MutableComponent tabs = Component.literal(preset.name + ": ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		for (Field.Section tab : Field.Section.values()) {
			if (tab == section) tabs.append(Component.literal("[" + tab.label + "]").withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE));
			else tabs.append(Say.button(tab.label, "/pvp preset show " + id + " " + tab.name().toLowerCase()));
			tabs.append(" ");
		}
		Say.to(player, tabs);

		String base = "/pvp preset ";
		for (Field field : Fields.of(preset)) {
			if (field.section != section) continue;
			MutableComponent row = Component.literal("  ");
			if (field instanceof Field.Heading) {
				Say.to(player, row.append(Component.literal(field.label).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
				continue;
			}
			if (!field.label.isEmpty()) {
				row.append(Component.literal(field.label + ": ").withStyle(style -> style.withColor(ChatFormatting.WHITE)
					.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(field.help().isEmpty() ? field.label : field.help())))));
			}
			String key = " " + id + " " + field.key;
			switch (field) {
				case Field.Toggle toggle -> row.append(Say.button(toggle.display(preset), base + "set" + key + (toggle.get(preset) ? " off" : " on")));
				case Field.Choice choice -> row.append(Say.button("<", base + "step" + key + " -1")).append(" ")
					.append(Component.literal(choice.display(preset)).withStyle(ChatFormatting.YELLOW)).append(" ")
					.append(Say.button(">", base + "step" + key + " 1"));
				case Field.Number number -> row.append(Say.button("-", base + "step" + key + " -1")).append(" ")
					.append(Component.literal(number.display(preset)).withStyle(ChatFormatting.YELLOW)).append(" ")
					.append(Say.button("+", base + "step" + key + " 1"));
				case Field.Text text -> row.append(Component.literal(text.display(preset)).withStyle(ChatFormatting.YELLOW)).append(" ")
					.append(Say.suggest("Change", base + "set" + key + " "));
				case Field.BlockRef block -> row.append(Component.literal(block.display(preset)).withStyle(ChatFormatting.YELLOW)).append(" ")
					.append(Say.button("Use held", base + "held" + key)).append(" ")
					.append(Say.suggest("Type", base + "set" + key + " "));
				case Field.Items items -> {
					row.append(Component.literal(items.display(preset)).withStyle(ChatFormatting.YELLOW)).append(" ")
						.append(Say.button("Edit", base + "items" + key)).append(" ")
						.append(Say.button("Clear", base + "set" + key + " clear"));
					if (items.canShuffle()) row.append(" ").append(Say.button("Shuffle", base + "shuffle" + key));
				}
				case Field.Action action -> row.append(Say.button(action.button, base + "do" + key));
				case Field.Heading heading -> { }
				case Field.Picture picture -> row.append(Component.literal(picture.display(preset)).withStyle(ChatFormatting.GRAY));
				case Field.Note note -> row.append(Component.literal(note.display(preset))
					.withStyle(note.warm ? ChatFormatting.GOLD : ChatFormatting.GRAY));
				case Field.Grid grid -> {
					for (Field.Grid.Cell cell : grid.cells(preset)) {
						String name = cell.tooltip().contains(":") ? cell.tooltip().substring(0, cell.tooltip().indexOf(':')) : cell.key();
						row.append(Say.button(name + (cell.mark().isEmpty() ? "" : " " + cell.mark()), base + "set" + key + " " + cell.key())
							.withStyle(style -> style.withColor(cell.dim() ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN)
								.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(cell.tooltip())))))
							.append(" ");
					}
				}
			}
			Say.to(player, row);
		}

		MutableComponent footer = Component.literal("  ");
		footer.append(Say.button("Start it", "/pvp start " + id)).append(" ")
			.append(Say.suggest("Copy", "/pvp preset copy " + id + " ")).append(" ")
			.append(Say.suggest("Delete", "/pvp preset delete " + id));
		Say.to(player, footer);
	}
}
