package justfatlard.pvp_dimensions;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** How this mod talks: one colour for its own voice, clickable words for anything you can do next. */
public final class Say {
	private Say() {}

	private static final ChatFormatting VOICE = ChatFormatting.GOLD;

	public static MutableComponent line(String words) {
		return Component.literal(words).withStyle(VOICE);
	}

	public static void to(ServerPlayer player, String words) {
		player.sendSystemMessage(line(words));
	}

	public static void to(ServerPlayer player, Component message) {
		player.sendSystemMessage(message);
	}

	/** Above the hotbar, for what changes by the second. */
	public static void bar(ServerPlayer player, String words) {
		player.sendOverlayMessage(Component.literal(words).withStyle(VOICE));
	}

	/** "[Join]", runs the command when clicked. */
	public static MutableComponent button(String label, String command) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	/** "[Set]", puts the command in the chat box to be finished. */
	public static MutableComponent suggest(String label, String command) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.SuggestCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}
}
