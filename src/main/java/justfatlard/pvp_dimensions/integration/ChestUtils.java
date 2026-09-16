package justfatlard.pvp_dimensions.integration;

import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * What an arena asks of Chest Utils, where it is installed: a team's chest painted the team's
 * colour, and no chest locked inside an arena, since taking from another team's chest is half of
 * what some games are.
 *
 * <p>By name and reflection, so neither mod needs the other. A Chest Utils too old to have one of
 * these quietly does without it.
 */
public final class ChestUtils {
	private ChestUtils() {}

	public static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("chest-utils");

	private static final String DYED = "justfatlard.chest_utils.block.DyedChests";
	private static final String LOCKS = "justfatlard.chest_utils.block.ChestLocks";

	/** Paint the chest here a dye's colour: "red", "light_blue". */
	public static void paint(ServerLevel level, BlockPos pos, String dye) {
		Object dyed = dyed(level);
		if (dyed == null) return;
		try {
			dyed.getClass().getMethod("paint", ServerLevel.class, BlockPos.class, String.class).invoke(dyed, level, pos, dye);
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.debug("Chest Utils would not paint a chest", e);
		}
	}

	/** The paint off again, for a chest that is going. */
	public static void strip(ServerLevel level, BlockPos pos) {
		Object dyed = dyed(level);
		if (dyed == null) return;
		try {
			dyed.getClass().getMethod("strip", ServerLevel.class, BlockPos.class).invoke(dyed, level, pos);
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.debug("Chest Utils would not strip a chest", e);
		}
	}

	private static @Nullable Object dyed(ServerLevel level) {
		if (!INSTALLED) return null;
		try {
			return Class.forName(DYED).getMethod("get", ServerLevel.class).invoke(null, level);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Chest Utils is told where it may not lock a chest; a version without the question never asks it. */
	public static void refuseLocking(BiPredicate<ServerLevel, BlockPos> where) {
		if (!INSTALLED) return;
		try {
			Method refuse = Class.forName(LOCKS).getMethod("refuseLocking", BiPredicate.class);
			refuse.invoke(null, where);
		} catch (ReflectiveOperationException | RuntimeException e) {
			PvpDimensions.LOGGER.info("This Chest Utils can't be told to leave arena chests unlocked; update it to stop locking in arenas");
		}
	}
}
