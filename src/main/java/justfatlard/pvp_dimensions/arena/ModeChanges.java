package justfatlard.pvp_dimensions.arena;

import java.util.Comparator;
import java.util.List;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The game mode changing partway through a fight, as the preset lays out: a creative spell to
 * build in, a minute's warning, then survival as the walls come down, and so on. Each change can
 * empty everyone's pack and hand their kit out again, so nothing made in creative is carried into
 * the fight. Admins keep their own mode, as they always do.
 */
public final class ModeChanges {
	private ModeChanges() {}

	private static List<Preset.ModeChange> sorted(Preset preset) {
		return preset.modeChanges.stream().sorted(Comparator.comparingInt(change -> change.minute)).toList();
	}

	/** The mode the arena is in now: the last change that has happened, or the preset's own. */
	public static Preset.GameRule current(Arena arena) {
		List<Preset.ModeChange> changes = sorted(arena.preset);
		return arena.modesChanged <= 0 || changes.isEmpty() ? arena.preset.gameMode
			: changes.get(Math.min(arena.modesChanged, changes.size()) - 1).mode;
	}

	public static void tick(MinecraftServer server, Arena arena, long now) {
		List<Preset.ModeChange> changes = sorted(arena.preset);
		while (arena.modesWarned < changes.size()) {
			Preset.ModeChange change = changes.get(arena.modesWarned);
			long at = arena.liveAt + change.minute * 60_000L;
			if (now < at - 60_000L) break;
			if (change.warn && now < at) title(server, arena, word(change.mode) + " in a minute", "Get ready", false);
			arena.modesWarned++;
		}
		while (arena.modesChanged < changes.size()) {
			Preset.ModeChange change = changes.get(arena.modesChanged);
			if (now < arena.liveAt + change.minute * 60_000L) break;
			arena.modesChanged++;
			apply(server, arena, change);
		}
	}

	private static void apply(MinecraftServer server, Arena arena, Preset.ModeChange change) {
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || member.watching || member.zombie || Access.admin(player)) continue;
			player.setGameMode(Travel.playMode(arena));
			if (change.kits) {
				player.getInventory().clearContent();
				Travel.equip(player, arena, true);
			}
		}
		title(server, arena, word(change.mode) + "!", change.kits ? "Everyone's kit handed out afresh" : "The game mode has changed", true);
		Arenas.vault(server).touch();
	}

	private static void title(MinecraftServer server, Arena arena, String words, String under, boolean loud) {
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(under).withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(words).withStyle(ChatFormatting.GOLD)));
			if (loud) player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.RAID_HORN, SoundSource.MASTER, 0.7F, 1F);
		}
	}

	public static String word(Preset.GameRule mode) {
		return switch (mode) {
			case SURVIVAL -> "Survival";
			case ADVENTURE -> "Adventure";
			case CREATIVE -> "Creative";
		};
	}
}
