package justfatlard.spawn_lock;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Stands in for Spawn Lock's own, under its name and with the one method PvP Dimensions asks by
 * reflection; the test mod provides spawn-lock-justfatlard so the question is asked at all.
 */
public final class Gate {
	private Gate() {}

	public static final Set<UUID> HELD = new HashSet<>();

	public static boolean isWaiting(ServerPlayer player) {
		return HELD.contains(player.getUUID());
	}
}
