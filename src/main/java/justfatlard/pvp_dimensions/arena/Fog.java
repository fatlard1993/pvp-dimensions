package justfatlard.pvp_dimensions.arena;

import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * How far a player can see in an arena. The game draws its fog where a client stops drawing the
 * world, and a client draws no further than the server says it may: so an arena's fog is the
 * server telling each player inside to draw only so many chunks, and telling them the server's own
 * reach again on the way out. Every client has this; no mod is needed on it.
 */
public final class Fog {
	private Fog() {}

	/** The arena's fog for this player, or with no arena, the server's own reach back. */
	public static void show(ServerPlayer player, @Nullable Arena arena) {
		int reach = player.level().getServer().getPlayerList().getViewDistance();
		if (arena != null && arena.preset.fog > 0) reach = Math.min(reach, arena.preset.fog);
		player.connection.send(new ClientboundSetChunkCacheRadiusPacket(reach));
	}
}
