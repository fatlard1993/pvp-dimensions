package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Division walls coming down like a curtain: every wall at once, a layer at a time from the top,
 * with the dust of the wall and its breaking sound as it goes. Above the ground it takes a few
 * seconds; under it, where nobody sees, it hurries. Where a wall stood under the ground it is
 * filled with the ground beside it, so a fallen wall leaves a seam, not a trench.
 */
final class Curtain {
	/** Ticks the part above the ground takes to come down. */
	private static final int SHOWN_TICKS = 160;
	private static final int UNDERGROUND_PER_TICK = 12;

	private record Column(int x, int z, BlockState wall, int @Nullable [] source, int ground) {}

	private static final List<Curtain> falling = new ArrayList<>();

	private final ServerLevel level;
	private final List<Column> columns;
	private final int surface;
	private final int bottom;
	private final int abovePerTick;
	private int y;
	private int ticks;

	private Curtain(ServerLevel level, List<Column> columns, int top, int surface, int bottom) {
		this.level = level;
		this.columns = columns;
		this.y = top;
		this.surface = surface;
		this.bottom = bottom;
		this.abovePerTick = Math.max(1, (int) Math.ceil((top - surface) / (double) SHOWN_TICKS));
	}

	/** Every division wall of the arena, starting down now. */
	static void drop(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		Terrain terrain = footprint.terrain();
		List<Column> columns = new ArrayList<>();
		for (int x = footprint.minX(); x < footprint.maxX(); x++) {
			for (int z = footprint.minZ(); z < footprint.maxZ(); z++) {
				BlockState wall = terrain.wallAt(footprint, x, z);
				if (wall == null || wall == Terrain.BEDROCK) continue;
				int[] source = Arenas.besideWall(footprint, terrain, x, z);
				int ground = source == null ? level.getMinY() : level.getHeight(Heightmap.Types.OCEAN_FLOOR, source[0], source[1]);
				columns.add(new Column(x, z, wall, source, ground));
			}
		}
		if (!columns.isEmpty()) falling.add(new Curtain(level, columns, arena.wallTop, arena.surfaceY, level.getMinY()));
	}

	/** Every curtain a little further down; once a tick. */
	static void tick() {
		Iterator<Curtain> curtains = falling.iterator();
		while (curtains.hasNext()) {
			if (curtains.next().step()) curtains.remove();
		}
	}

	/** A few more layers down; true once it reaches the bottom. */
	private boolean step() {
		int layers = y > surface ? abovePerTick : UNDERGROUND_PER_TICK;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos();
		for (int layer = 0; layer < layers && y >= bottom; layer++, y--) {
			for (int i = 0; i < columns.size(); i++) {
				Column column = columns.get(i);
				pos.set(column.x(), y, column.z());
				if (level.getBlockState(pos) != column.wall()) continue;
				BlockState fill = Blocks.AIR.defaultBlockState();
				if (column.source() != null && y < column.ground()) {
					BlockState beside = level.getBlockState(from.set(column.source()[0], y, column.source()[1]));
					if (!beside.hasBlockEntity()) fill = beside;
				}
				level.setBlock(pos, fill, Block.UPDATE_CLIENTS);
				if (y >= surface && layer == 0 && i % 3 == ticks % 3) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, column.wall()), column.x() + 0.5, y + 0.5, column.z() + 0.5, 3, 0.3, 0.3, 0.3, 0.05);
				}
			}
		}
		if (y >= surface - 1 && ticks % 4 == 0 && !columns.isEmpty()) {
			Column noisy = columns.get(level.getRandom().nextInt(columns.size()));
			var sound = noisy.wall().getSoundType();
			level.playSound(null, noisy.x() + 0.5, Math.max(y, surface), noisy.z() + 0.5, sound.getBreakSound(), SoundSource.BLOCKS, 2F, 0.8F);
		}
		ticks++;
		return y < bottom;
	}
}
