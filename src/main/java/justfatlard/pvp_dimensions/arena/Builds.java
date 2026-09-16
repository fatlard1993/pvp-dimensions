package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Putting buildings up in an arena: the blocks each world builds with, a team's colour worked in,
 * and the ground levelled under them first, so a fort on a hillside stands on a flat yard.
 */
final class Builds {
	private Builds() {}

	/** How close to the border and to a wall a building may come. */
	private static final int MARGIN = 3;
	/** How far down a levelled yard is filled before it is left standing on what is there. */
	private static final int FILL_DEPTH = 8;

	/** What one world's buildings are made of, in one team's colour. */
	record Palette(BlockState wall, BlockState trim, BlockState floor, BlockState pillar, BlockState fence,
			BlockState light, BlockState ceilingLight, BlockState accent, BlockState window, BlockState banner, String dye) {}

	/** The world's own materials, with {@code colour}'s where a team shows; white for a base everyone shares. */
	static Palette palette(Preset.World world, TeamColors.@Nullable Colour colour) {
		String dye = colour != null ? colour.dye() : "white";
		BlockState window = block("minecraft:" + dye + "_stained_glass_pane");
		BlockState banner = block("minecraft:" + dye + "_banner");
		return switch (world) {
			case OVERWORLD -> new Palette(block("minecraft:cobblestone"), block("minecraft:stone_bricks"), block("minecraft:oak_planks"),
				block("minecraft:oak_log"), block("minecraft:oak_fence"), block("minecraft:lantern"), block("minecraft:sea_lantern"),
				block("minecraft:" + dye + "_wool"), window, banner, dye);
			case NETHER -> new Palette(block("minecraft:nether_bricks"), block("minecraft:polished_blackstone_bricks"),
				block("minecraft:crimson_planks"), block("minecraft:crimson_stem"), block("minecraft:nether_brick_fence"),
				block("minecraft:soul_lantern"), block("minecraft:shroomlight"), block("minecraft:" + dye + "_wool"), window, banner, dye);
			case END -> new Palette(block("minecraft:end_stone_bricks"), block("minecraft:purpur_block"), block("minecraft:purpur_block"),
				block("minecraft:purpur_pillar"), block("minecraft:iron_bars"), block("minecraft:end_rod"),
				block("minecraft:pearlescent_froglight"), block("minecraft:" + dye + "_concrete"), window, banner, dye);
		};
	}

	static BlockState block(String id) {
		return Terrain.block(id, Blocks.STONE.defaultBlockState());
	}

	/** A block put down fitted to what is around it: a fence joined to its neighbours, a pane to the wall. */
	static void set(ServerLevel level, BlockPos pos, BlockState state) {
		level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
	}

	static void air(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** A ladder on the wall {@code wall} of it. */
	static void ladder(ServerLevel level, BlockPos pos, Direction wall) {
		level.setBlock(pos, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, wall.getOpposite()), Block.UPDATE_ALL);
	}

	/**
	 * The biggest reach up to {@code wanted} a building at this column can have without crossing a
	 * wall or coming too near the border; -1 where even the middle is no good.
	 */
	static int room(Arena arena, int x, int z, int wanted) {
		Footprint footprint = arena.footprint();
		Terrain terrain = footprint.terrain();
		for (int reach = 0; reach <= wanted; reach++) {
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != reach) continue;
					int cx = x + dx;
					int cz = z + dz;
					boolean inside = cx >= footprint.minX() + MARGIN && cx < footprint.maxX() - MARGIN
						&& cz >= footprint.minZ() + MARGIN && cz < footprint.maxZ() - MARGIN;
					if (!inside || terrain.wallAt(footprint, cx, cz) != null) return reach - 2;
				}
			}
		}
		return wanted;
	}

	/**
	 * A square yard levelled round a column: the ground made flat at {@code y}, the top of it
	 * whatever the ground there was, filled under where it was low and cut away where it was high,
	 * and clear to {@code headroom} over it.
	 */
	static void yard(ServerLevel level, int cx, int y, int cz, int reach, int headroom) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState top = level.getBlockState(pos.set(cx, y - 1, cz));
		BlockState under = level.getBlockState(pos.set(cx, y - 2, cz));
		if (!Spawns.solid(top)) top = Blocks.DIRT.defaultBlockState();
		if (!Spawns.solid(under) || under.hasBlockEntity()) under = Blocks.DIRT.defaultBlockState();
		for (int x = cx - reach; x <= cx + reach; x++) {
			for (int z = cz - reach; z <= cz + reach; z++) {
				for (int yy = y; yy <= y + headroom; yy++) air(level, pos.set(x, yy, z));
				level.setBlock(pos.set(x, y - 1, z), top, Block.UPDATE_ALL);
				for (int yy = y - 2; yy >= y - FILL_DEPTH; yy--) {
					BlockState there = level.getBlockState(pos.set(x, yy, z));
					if (Spawns.solid(there) && there.getFluidState().isEmpty()) break;
					level.setBlock(pos, under, Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Where a building on this column stands: the ground players would stand on, or the arena's surface. */
	static int ground(ServerLevel level, Arena arena, int x, int z) {
		var spot = Spawns.standAt(level, arena, x, z);
		if (spot != null) return (int) Math.floor(spot.y);
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return top > level.getMinY() + 1 ? top : arena.surfaceY;
	}

	/** The side of a building that faces the middle of the arena, where its way in goes. */
	static Direction front(Arena arena, int x, int z) {
		double dx = arena.footprint().centerX() - x;
		double dz = arena.footprint().centerZ() - z;
		if (Math.abs(dx) < 1 && Math.abs(dz) < 1) return Direction.SOUTH;
		return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
	}
}
