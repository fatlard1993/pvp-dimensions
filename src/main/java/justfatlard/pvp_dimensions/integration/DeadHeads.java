package justfatlard.pvp_dimensions.integration;

import java.lang.reflect.Method;
import java.util.function.BiFunction;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Dead Heads, where it is installed: told how long a head placed in an arena stays its owner's, by
 * that arena's preset. By name and reflection, so neither mod needs the other; a Dead Heads too old
 * to ask keeps the server's own lock time everywhere.
 */
public final class DeadHeads {
	private DeadHeads() {}

	public static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("dead-heads");

	private static final String MANAGER = "justfatlard.dead_heads.DeadHeadManager";

	/** {@code where} answers in minutes for a place, or null to leave it to the server's. */
	public static void lockTimeAt(BiFunction<ServerLevel, BlockPos, Integer> where) {
		if (!INSTALLED) return;
		try {
			Method hook = Class.forName(MANAGER).getMethod("lockTimeAt", BiFunction.class);
			hook.invoke(null, where);
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.info("This Dead Heads can't be told an arena's lock time; heads there keep the server's");
		}
	}
}
