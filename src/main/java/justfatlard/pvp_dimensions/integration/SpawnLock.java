package justfatlard.pvp_dimensions.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * Spawn Lock's pen, where it is installed: a newcomer who has not said the password, or somebody an
 * operator has jailed, is held at the spawn and goes into no arena and no demo. The pen keeps them
 * in by putting them back at its spot in whatever level they stand in, which in an arena dimension
 * is over nothing; and an invitation or a demo's offer is a button, which a jail's ban on commands
 * does not reach.
 *
 * <p>By name and reflection, so neither mod needs the other.
 */
public final class SpawnLock {
	private SpawnLock() {}

	private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("spawn-lock-justfatlard");
	private static final String GATE = "justfatlard.spawn_lock.Gate";

	public static boolean held(ServerPlayer player) {
		if (!INSTALLED) return false;
		try {
			return (boolean) Class.forName(GATE).getMethod("isWaiting", ServerPlayer.class).invoke(null, player);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
