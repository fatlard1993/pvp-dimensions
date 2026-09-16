package justfatlard.pvp_dimensions.arena;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * A race: the first to stand on the emerald finish wins it, for themselves or their team. Everyone
 * starts the same distance from it; how it is got to is the preset's, walked, climbed, built up to
 * or dug down to.
 */
public final class Race {
	private Race() {}

	public static void begin(ServerLevel level, Arena arena) {
		Markers.placeFinish(level, arena);
	}

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (arena.marker == null) return;
		if (now % 1000 < 500) Markers.tick(level, arena, now);
		List<Arena.Member> there = Markers.standing(server, arena);
		if (there.isEmpty()) return;
		Arena.Member first = there.get(0);
		if (arena.preset.teamsOn()) Goals.winTeam(server, arena, first.team, first.name + " reached the finish first");
		else Goals.winPlayer(server, arena, first, "reached the finish first");
	}

	/** How far the finish is from whoever is looking, across and up or down. */
	public static @Nullable String status(MinecraftServer server, Arena arena, Arena.@Nullable Member viewer) {
		BlockPos finish = arena.marker;
		if (finish == null || viewer == null) return null;
		ServerPlayer player = server.getPlayerList().getPlayer(viewer.id);
		if (player == null) return null;
		int across = (int) Math.round(Math.hypot(finish.getX() + 0.5 - player.getX(), finish.getZ() + 0.5 - player.getZ()));
		int up = finish.getY() + 1 - (int) Math.floor(player.getY());
		String height = Math.abs(up) < 3 ? "" : up > 0 ? ", " + up + " up" : ", " + (-up) + " down";
		return "The finish: " + across + (across == 1 ? " block" : " blocks") + " away" + height;
	}
}
