package justfatlard.pvp_dimensions.integration;

import java.lang.reflect.Method;
import java.util.function.BiFunction;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Dead Heads, where it is installed: told how long a head placed in an arena stays its owner's, by
 * that arena's preset. By name and reflection, so neither mod needs the other; a Dead Heads too old
 * to ask keeps the server's own lock time everywhere.
 */
public final class DeadHeads {
	private DeadHeads() {}

	public static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("dead-heads");

	private static final String MANAGER = "justfatlard.dead_heads.DeadHeadManager";
	private static final String API = "justfatlard.dead_heads.api.DeadHeadsApi";

	/**
	 * Forget every head on an arena's plot, because the arena is over and its ground unreachable.
	 *
	 * <p>A head in an arena is a record of a whole inventory keyed to a block: leave it and nobody
	 * can ever break or loot one, so nothing ever removes it, and every compass tethered to it
	 * follows its player home and back through every death after. Enough of those and Dead Heads writes a save it can no longer read, which
	 * it discovers at startup, which takes the server with it. That is not a hypothetical.
	 */
	public static void forgetPlot(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ) {
		if (!INSTALLED) return;
		try {
			Method forget = Class.forName(API).getMethod(
				"forgetWithin", ResourceKey.class, BlockPos.class, BlockPos.class);
			forget.invoke(null, dimension, new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ));
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.info("This Dead Heads can't be told an arena's ground is gone;"
				+ " heads left in arenas will pile up in its save");
		}
	}

	/** Forget every head in an arena dimension, for when no arena is left standing in it. */
	public static void forgetDimension(ResourceKey<Level> dimension) {
		if (!INSTALLED) return;
		try {
			Class.forName(API).getMethod("forgetIn", ResourceKey.class).invoke(null, dimension);
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.info("This Dead Heads can't be told an arena dimension is empty;"
				+ " heads left in arenas will pile up in its save");
		}
	}

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
