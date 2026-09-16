package justfatlard.pvp_dimensions.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where one arena stands and what its ground is made of, fixed when the arena is made.
 *
 * <p>Read from the worldgen threads while the server thread changes which arenas exist, so it is
 * immutable, and {@link Footprints} swaps whole lists rather than editing one.
 */
public record Footprint(String arena, ResourceKey<Level> dimension, int minChunkX, int minChunkZ, int chunks, Terrain terrain) {
	public boolean containsChunk(int chunkX, int chunkZ) {
		return chunkX >= minChunkX && chunkX < minChunkX + chunks && chunkZ >= minChunkZ && chunkZ < minChunkZ + chunks;
	}

	public boolean containsBlock(double x, double z) {
		return x >= minX() && x < maxX() && z >= minZ() && z < maxZ();
	}

	public int minX() {
		return minChunkX << 4;
	}

	public int minZ() {
		return minChunkZ << 4;
	}

	/** Exclusive. */
	public int maxX() {
		return (minChunkX + chunks) << 4;
	}

	/** Exclusive. */
	public int maxZ() {
		return (minChunkZ + chunks) << 4;
	}

	public int width() {
		return chunks << 4;
	}

	public double centerX() {
		return minX() + width() / 2.0;
	}

	public double centerZ() {
		return minZ() + width() / 2.0;
	}

	public BlockPos center(int y) {
		return new BlockPos(minX() + width() / 2, y, minZ() + width() / 2);
	}
}
