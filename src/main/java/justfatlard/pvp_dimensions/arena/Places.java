package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The three arena dimensions and the plots in them.
 *
 * <p>Dimensions are made when a world loads and not after, so there is one per kind of sky, and
 * every arena of that kind gets a plot of its own in it, a couple of thousand blocks from the
 * next: far past anyone's view distance, and aligned to the game's region files, so a finished
 * arena's plot can be deleted file by file while the server is down.
 */
public final class Places {
	private Places() {}

	public static final ResourceKey<Level> OVERWORLD = key("overworld");
	public static final ResourceKey<Level> NETHER = key("nether");
	public static final ResourceKey<Level> END = key("end");

	/** Four region files across. An arena is at most thirty-two chunks, one region file. */
	public static final int SPACING = 2048;
	private static final int PER_ROW = 256;
	/**
	 * Where the plots start. Clear of the middle, which matters in the end, where the ground
	 * within a thousand blocks of the middle is the main island and the empty ring around it.
	 */
	private static final int OFFSET = 4 * SPACING;

	private static ResourceKey<Level> key(String path) {
		return ResourceKey.create(Registries.DIMENSION, PvpDimensions.id(path));
	}

	public static boolean isArena(ResourceKey<Level> dimension) {
		return dimension.equals(OVERWORLD) || dimension.equals(NETHER) || dimension.equals(END);
	}

	public static ResourceKey<Level> forWorld(Preset.World world) {
		return switch (world) {
			case OVERWORLD -> OVERWORLD;
			case NETHER -> NETHER;
			case END -> END;
		};
	}

	/** The west edge of a plot's cell, in blocks. */
	public static int cellX(int plot) {
		return OFFSET + (plot % PER_ROW) * SPACING;
	}

	public static int cellZ(int plot) {
		return OFFSET + (plot / PER_ROW) * SPACING;
	}

	public static int centerX(int plot) {
		return cellX(plot) + SPACING / 2;
	}

	public static int centerZ(int plot) {
		return cellZ(plot) + SPACING / 2;
	}
}
