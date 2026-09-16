package justfatlard.pvp_dimensions.world;

import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Turns generated ground into an arena's: cut to depth, remade in its material, floored and walled.
 *
 * <p>Runs on a chunk while it is still being generated, before the game lights it, so nothing
 * here needs a light update and a whole column can be rewritten for the price of setting blocks
 * in an array. Every step is safe to repeat: {@link #settle} runs the walls again after trees and
 * structures, since a neighbour's tree can reach into a chunk that was already finished.
 */
public final class Shaper {
	private Shaper() {}

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	/** After noise, surface and caves, before trees and ores. */
	public static void terrain(ChunkAccess chunk, Footprint footprint) {
		Terrain terrain = footprint.terrain();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = chunk.getMinY();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunk.getPos().getMinBlockX() + localX;
				int z = chunk.getPos().getMinBlockZ() + localZ;
				int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ) - 1;
				boolean hasGround = surface >= minY;
				int bottom = terrain.cutsDepth() && terrain.depth() > 0 && hasGround
					? Math.max(minY, surface - terrain.depth() + 1) : minY;

				for (int y = minY; y < bottom; y++) set(chunk, pos.set(x, y, z), AIR);

				if (hasGround && !(terrain.palette() instanceof Terrain.Natural)) {
					int span = surface - bottom + 1;
					for (int y = bottom; y <= chunk.getMaxY(); y++) {
						BlockState state = get(chunk, x, y, z);
						if (state.isAir()) continue;
						BlockState swapped = terrain.palette().apply(state, surface - y, span);
						if (swapped != null) set(chunk, pos.set(x, y, z), swapped);
					}
				}

				if (hasGround && terrain.bedrock() != Preset.Bedrock.OFF) set(chunk, pos.set(x, bottom, z), Terrain.BEDROCK);
				column(chunk, footprint, x, z, hasGround ? bottom : minY, pos);
			}
		}
	}

	/**
	 * A flat arena's ground is laid by the game's own rules for its biome, and then the game's
	 * caves and ravines are cut through it like any other. Flat means flat: every hole cut below
	 * the surface is filled back in, each block with whatever the uncut ground beside it has at
	 * that height, which is the same layer.
	 */
	public static void heal(ChunkAccess chunk) {
		int top = chunk.getMinY() - 1;
		int[] tops = new int[256];
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ) - 1;
				tops[localX * 16 + localZ] = surface;
				top = Math.max(top, surface);
			}
		}
		if (top < chunk.getMinY()) return;
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = chunk.getMinY(); y <= top; y++) {
			BlockState layer = null;
			for (int i = 0; i < 256 && layer == null; i++) {
				if (tops[i] < top) continue;
				BlockState state = get(chunk, minX + i / 16, y, minZ + i % 16);
				if (!state.isAir()) layer = state;
			}
			if (layer == null) continue;
			for (int localX = 0; localX < 16; localX++) {
				for (int localZ = 0; localZ < 16; localZ++) {
					int x = minX + localX;
					int z = minZ + localZ;
					if (get(chunk, x, y, z).isAir()) set(chunk, pos.set(x, y, z), layer);
				}
			}
		}
	}

	/**
	 * After trees, ores and structures: the swap again, for anything they brought that the swap
	 * names, and the walls again, for anything that grew into them.
	 */
	public static void settle(ChunkAccess chunk, Footprint footprint) {
		Terrain terrain = footprint.terrain();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		if (terrain.palette().touchesFeatures()) {
			for (int index = 0; index < chunk.getSectionsCount(); index++) {
				LevelChunkSection section = chunk.getSection(index);
				if (section.hasOnlyAir() || !section.maybeHas(state -> terrain.palette().apply(state, 0, 1) != null)) continue;
				int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
				for (int localY = 0; localY < 16; localY++) {
					for (int localX = 0; localX < 16; localX++) {
						for (int localZ = 0; localZ < 16; localZ++) {
							BlockState swapped = terrain.palette().apply(section.getBlockState(localX, localY, localZ), 0, 1);
							if (swapped != null) {
								set(chunk, pos.set(chunk.getPos().getMinBlockX() + localX, baseY + localY, chunk.getPos().getMinBlockZ() + localZ), swapped);
							}
						}
					}
				}
			}
		}

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunk.getPos().getMinBlockX() + localX;
				int z = chunk.getPos().getMinBlockZ() + localZ;
				if (terrain.wallAt(footprint, x, z) == null) continue;
				column(chunk, footprint, x, z, lowestSolid(chunk, x, z), pos);
			}
		}
	}

	/** The wall standing in this column, if one does, and the shell's roof over it. */
	private static void column(ChunkAccess chunk, Footprint footprint, int x, int z, int bottom, BlockPos.MutableBlockPos pos) {
		Terrain terrain = footprint.terrain();
		BlockState wall = terrain.wallAt(footprint, x, z);
		int top = Math.min(terrain.wallTop(), chunk.getMaxY());
		if (wall != null) {
			for (int y = bottom; y <= top; y++) set(chunk, pos.set(x, y, z), wall);
		}
		if (terrain.bedrock() == Preset.Bedrock.SHELL) set(chunk, pos.set(x, top, z), Terrain.BEDROCK);
	}

	private static int lowestSolid(ChunkAccess chunk, int x, int z) {
		for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
			if (!get(chunk, x, y, z).isAir()) return y;
		}
		return chunk.getMinY();
	}

	private static BlockState get(ChunkAccess chunk, int x, int y, int z) {
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
		return section.getBlockState(x & 15, y & 15, z & 15);
	}

	private static void set(ChunkAccess chunk, BlockPos pos, @Nullable BlockState state) {
		if (state == null) return;
		BlockState old = get(chunk, pos.getX(), pos.getY(), pos.getZ());
		if (old == state) return;
		if (old.hasBlockEntity()) chunk.removeBlockEntity(pos);
		chunk.setBlockState(pos, state, 0);
	}
}
