package justfatlard.pvp_dimensions.arena;

import java.util.List;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.NoticeApi;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.Say;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two things an arena asks a player rather than tells them: come and fight, and whether they
 * back the name the room has put up. Into Pandorical's tray where the player can see it, and a
 * clickable line in chat where they cannot. Each is taken back the moment it stops meaning
 * anything, so the tray never holds an invitation to a fight that is over.
 */
public final class Asks {
	private Asks() {}

	private static final String INVITE = PvpDimensions.MOD_ID + ":invite";
	private static final String ACCUSE = PvpDimensions.MOD_ID + ":accuse";

	public static void register() {
		PandoricalApi.notices().onChoice(INVITE, (player, arenaId, choice) -> {
			if (!choice.equals("join")) return;
			Arena arena = Arenas.get(player.level().getServer(), arenaId);
			if (arena == null) Say.to(player, "That arena is over");
			else Travel.join(player, arena);
		});
		PandoricalApi.notices().onChoice(ACCUSE, (player, arenaId, choice) -> {
			Arena arena = Arenas.of(player);
			if (choice.equals("agree") && arena != null && arena.id.equals(arenaId)) Spies.agree(player.level().getServer(), arena, player);
		});
	}

	private static boolean tray(ServerPlayer player) {
		return PandoricalApi.hasCapability(player, Capabilities.SCREENS);
	}

	public static void invite(ServerPlayer player, Arena arena, String from) {
		String asking = from + " invites you to " + arena.title();
		if (!tray(player)) {
			player.sendSystemMessage(Say.line(asking + " ").append(Say.button("Join", "/pvp join " + arena.id)));
			return;
		}
		PandoricalApi.notices().offer(player, new NoticeApi.Notice(arena.id, INVITE, "minecraft:iron_sword", asking,
			List.of(new NoticeApi.Choice("join", "minecraft:oak_door", "Join"),
				new NoticeApi.Choice("decline", "minecraft:barrier", "Not now")),
			0));
	}

	/** In, so the invitation is spent. */
	public static void arrived(ServerPlayer player, Arena arena) {
		PandoricalApi.notices().withdraw(player, INVITE, arena.id);
	}

	/** Nobody can come in any more: over, or started and taking no late arrivals. */
	public static void closed(MinecraftServer server, Arena arena) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) PandoricalApi.notices().withdraw(player, INVITE, arena.id);
	}

	/** A name is up, and this player could back it before it lapses. */
	public static void accusation(ServerPlayer voter, Arena arena, String caller, String accused, int seconds) {
		String asking = caller + " says it is " + accused + ". Take the blame with them?";
		if (!tray(voter)) {
			voter.sendSystemMessage(Say.line(asking + " ").append(Say.button("Agree", "/pvp agree")));
			return;
		}
		PandoricalApi.notices().offer(voter, new NoticeApi.Notice(arena.id, ACCUSE, "minecraft:name_tag", asking,
			List.of(new NoticeApi.Choice("agree", "minecraft:iron_sword", "Agree"),
				new NoticeApi.Choice("pass", "minecraft:barrier", "Not them")),
			seconds));
	}

	/** Backed already, by command, so the question is answered. */
	public static void backed(ServerPlayer voter, Arena arena) {
		PandoricalApi.notices().withdraw(voter, ACCUSE, arena.id);
	}

	/** The name is settled one way or the other: taken up, lapsed, or the round is over. */
	public static void settled(Arena arena) {
		MinecraftServer server = PvpDimensions.server();
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) PandoricalApi.notices().withdraw(player, ACCUSE, arena.id);
	}
}
