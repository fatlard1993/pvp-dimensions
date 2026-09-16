package justfatlard.pvp_dimensions.integration;

import java.util.function.Consumer;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loot Ender's lockpicks, where it is installed and its lockpicking is on: for a team's chest
 * another team may only open by picking it. The pick is Loot Ender's own, as hard as a player's
 * lock and spending picks the same way.
 *
 * <p>By name and reflection, so neither mod needs the other.
 */
public final class LootEnder {
	private LootEnder() {}

	public static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("loot-ender");

	private static final String CONFIG = "justfatlard.loot_ender.LootEnderConfig";
	private static final String PICKING = "justfatlard.loot_ender.lock.Lockpicking";

	/** Whether picking is on at all on this server. */
	public static boolean picking() {
		if (!INSTALLED) return false;
		try {
			return (boolean) Class.forName(CONFIG).getMethod("lockpicking").invoke(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	public static boolean hasPick(ServerPlayer player) {
		if (!INSTALLED) return false;
		try {
			return (boolean) Class.forName(PICKING).getMethod("hasPick", ServerPlayer.class).invoke(null, player);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** The lock screen, and {@code open} once it is beaten; false where it could not be put up. */
	public static boolean pick(ServerPlayer player, BlockPos pos, Consumer<ServerPlayer> open) {
		if (!INSTALLED) return false;
		try {
			Class.forName(PICKING).getMethod("pickPlayerLock", ServerPlayer.class, BlockPos.class, Consumer.class).invoke(null, player, pos, open);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.warn("Loot Ender would not start a pick", e);
			return false;
		}
	}
}
