package justfatlard.pvp_dimensions.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What worldgen needs from a preset, with every block id already looked up: made once on the
 * server thread, read by every worldgen thread after.
 *
 * @param kind      the ground as it grows, the same ground laid flat, or nothing (a saved terrain is pasted in later)
 * @param depth     how far below its surface each column keeps its ground; 0 keeps all of it
 * @param features  whether trees, spikes, pools and the rest of the biome's features grow; a flat arena may go without
 * @param wallTop   the highest block a wall or the shell reaches
 * @param cols      a grid's columns; one, for slices
 * @param rows      a grid's rows; one, for slices
 * @param slices    how many pie slices the arena is cut into from its middle; 0 for a grid
 * @param cutsDepth false where there is no one surface to measure down from, the nether's roof
 */
public record Terrain(
	Kind kind,
	Holder<Biome> biome,
	int depth,
	boolean features,
	Palette palette,
	Preset.Bedrock bedrock,
	int ceilingY,
	int wallTop,
	int cols,
	int rows,
	int slices,
	List<BlockState> cellWalls,
	boolean structures,
	boolean cutsDepth
) {
	public enum Kind { NOISE, FLAT, EMPTY }

	public static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();


	public boolean divided() {
		return parts() > 1;
	}

	/** How the walls cut the arena up. */
	public Divisions divisions() {
		return new Divisions(cols, rows, slices);
	}

	/** How many parts the walls make. */
	public int parts() {
		return divisions().parts();
	}

	/** The wall block standing at this column, or null where there is none. */
	public @Nullable BlockState wallAt(Footprint footprint, int x, int z) {
		if (bedrock == Preset.Bedrock.BOTTOM_WALLS || bedrock == Preset.Bedrock.SHELL) {
			if (x == footprint.minX() || x == footprint.maxX() - 1 || z == footprint.minZ() || z == footprint.maxZ() - 1) {
				return BEDROCK;
			}
		}
		if (!divided()) return null;

		boolean wall = divisions().wall(footprint.width(), x - footprint.minX(), z - footprint.minZ());
		return wall ? cellWalls.get(cellOf(footprint, x, z)) : null;
	}

	/** Which division a column is in: see {@link Divisions#part}. */
	public int cellOf(Footprint footprint, int x, int z) {
		return divisions().part(footprint.width(), x - footprint.minX(), z - footprint.minZ());
	}

	/** The middle of a division, as block x and z: see {@link Divisions#center}. */
	public int[] partCenter(Footprint footprint, int part) {
		int[] local = divisions().center(footprint.width(), part);
		return new int[] {footprint.minX() + local[0], footprint.minZ() + local[1]};
	}

	public static BlockState block(String id, BlockState fallback) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return fallback;
		return BuiltInRegistries.BLOCK.getOptional(parsed).map(Block::defaultBlockState).orElse(fallback);
	}

	/** How a preset's material setting turns natural ground into what the arena is made of. */
	public sealed interface Palette {
		/**
		 * @param below how far under its column's surface this block is
		 * @param span  how deep the column's ground is, surface to bottom
		 * @return the block to put here instead, or null to leave it
		 */
		@Nullable BlockState apply(BlockState state, int below, int span);

		/** Whether a second pass after trees, ores and structures could change anything. */
		default boolean touchesFeatures() {
			return false;
		}

		static Palette of(Preset preset) {
			return switch (preset.material) {
				case NATURAL -> Natural.INSTANCE;
				case SINGLE -> new Single(block(preset.single, Blocks.STONE.defaultBlockState()));
				case SWAP -> {
					Map<Block, BlockState> map = new HashMap<>();
					preset.swaps.forEach((from, to) -> {
						BlockState source = block(from, null);
						BlockState target = block(to, null);
						if (source != null && target != null && source.getBlock() != target.getBlock()) map.put(source.getBlock(), target);
					});
					yield map.isEmpty() ? Natural.INSTANCE : new Swap(Map.copyOf(map));
				}
				case LAYERS -> {
					List<BlockState> blocks = new ArrayList<>();
					List<Integer> weights = new ArrayList<>();
					for (Preset.Layer layer : preset.layers) {
						BlockState state = block(layer.block(), null);
						if (state != null && layer.percent() > 0) {
							blocks.add(state);
							weights.add(layer.percent());
						}
					}
					if (blocks.isEmpty()) yield Natural.INSTANCE;
					float total = weights.stream().mapToInt(Integer::intValue).sum();
					float[] ends = new float[blocks.size()];
					float sum = 0;
					for (int i = 0; i < ends.length; i++) {
						sum += weights.get(i) / total;
						ends[i] = sum;
					}
					yield new Layers(List.copyOf(blocks), ends);
				}
			};
		}
	}

	public enum Natural implements Palette {
		INSTANCE;

		@Override
		public @Nullable BlockState apply(BlockState state, int below, int span) {
			return null;
		}
	}

	public record Single(BlockState to) implements Palette {
		@Override
		public @Nullable BlockState apply(BlockState state, int below, int span) {
			return ground(state) && state != to ? to : null;
		}
	}

	public record Swap(Map<Block, BlockState> map) implements Palette {
		@Override
		public @Nullable BlockState apply(BlockState state, int below, int span) {
			return map.get(state.getBlock());
		}

		@Override
		public boolean touchesFeatures() {
			return true;
		}
	}

	public record Layers(List<BlockState> blocks, float[] ends) implements Palette {
		@Override
		public @Nullable BlockState apply(BlockState state, int below, int span) {
			if (!ground(state)) return null;
			float depth = span <= 1 ? 0 : below / (float) span;
			for (int i = 0; i < ends.length; i++) {
				if (depth < ends[i]) return blocks.get(i);
			}
			return blocks.get(blocks.size() - 1);
		}
	}

	/**
	 * Ground, as opposed to air, water and lava. Asked before trees and ores are placed, when the
	 * only solid blocks there are are the ground itself.
	 */
	public static boolean ground(BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty();
	}
}
