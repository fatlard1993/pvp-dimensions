package justfatlard.pvp_dimensions.arena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The two kinds of portal an arena has. A gate is somebody's own empty obsidian frame out in the
 * world, lit to lead into the arena while it runs and left dark again when it ends. An exit is a
 * frame the arena builds inside itself, leading each player back to wherever they came in from.
 *
 * <p>Both are ordinary nether portals, lit the ordinary way; what makes them ours is being in
 * these lists, which the portal block asks before it asks anything else.
 */
public final class Portals {
	private Portals() {}

	/** How far from whoever starts an arena a frame may be and still count as theirs to light. */
	private static final int REACH = 12;
	private static final int REACH_UP = 6;

	private static final Map<GlobalPos, String> gates = new HashMap<>();
	private static final Map<GlobalPos, String> exits = new HashMap<>();

	public static void index(Arena arena) {
		unindex(arena);
		if (arena.phase == Arena.Phase.ENDED) return;
		for (GlobalPos gate : arena.gates) gates.put(gate, arena.id);
		for (BlockPos exit : arena.exits) exits.put(GlobalPos.of(arena.dimension, exit), arena.id);
	}

	public static void unindex(Arena arena) {
		gates.values().removeIf(arena.id::equals);
		exits.values().removeIf(arena.id::equals);
	}

	public static void clear() {
		gates.clear();
		exits.clear();
	}

	public static @Nullable String gateAt(ServerLevel level, BlockPos pos) {
		return gates.get(GlobalPos.of(level.dimension(), pos));
	}

	public static @Nullable String exitAt(ServerLevel level, BlockPos pos) {
		return exits.get(GlobalPos.of(level.dimension(), pos));
	}

	public static boolean ours(ServerLevel level, BlockPos pos) {
		return Places.isArena(level.dimension()) || gateAt(level, pos) != null;
	}

	// --- Gates ---

	/**
	 * Light the empty frame nearest this spot, if there is one within reach, and tie it to the
	 * arena. Returns how many portal blocks it lit, 0 for no frame found.
	 */
	public static int lightNear(ServerLevel level, Vec3 near, Arena arena) {
		BlockPos origin = BlockPos.containing(near);
		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-REACH, -REACH_UP, -REACH), origin.offset(REACH, REACH_UP, REACH))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() && level.getBlockState(pos.below()).is(Blocks.OBSIDIAN)) candidates.add(pos.immutable());
		}
		candidates.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(near)));
		for (BlockPos pos : candidates) {
			Optional<PortalShape> shape = PortalShape.findEmptyPortalShape(level, pos, Direction.Axis.X);
			if (shape.isEmpty()) continue;
			shape.get().createPortalBlocks(level);
			Set<BlockPos> lit = connected(level, pos);
			for (BlockPos block : lit) arena.gates.add(GlobalPos.of(level.dimension(), block));
			index(arena);
			return lit.size();
		}
		return 0;
	}

	/** Every portal block touching this one, the whole face of the frame. */
	private static Set<BlockPos> connected(ServerLevel level, BlockPos start) {
		Set<BlockPos> found = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		open.add(start);
		while (!open.isEmpty() && found.size() < PortalShape.MAX_WIDTH * PortalShape.MAX_HEIGHT) {
			BlockPos pos = open.poll();
			if (found.contains(pos) || !level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) continue;
			found.add(pos);
			for (Direction direction : Direction.values()) open.add(pos.relative(direction));
		}
		return found;
	}

	/** The arena's frames go dark: the portal blocks come out, the obsidian stays. */
	public static void darken(MinecraftServer server, Arena arena) {
		for (GlobalPos gate : arena.gates) {
			ServerLevel level = server.getLevel(gate.dimension());
			if (level == null) continue;
			if (level.getBlockState(gate.pos()).is(Blocks.NETHER_PORTAL)) {
				level.setBlock(gate.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		unindex(arena);
	}

	/**
	 * Where a player going through a gate is put back when they leave: a step out of the frame
	 * on the side they walked in from, so they are not stood in the portal they just came out of.
	 */
	public static Visit.Home homeFromGate(ServerLevel level, BlockPos portal, Vec3 at, float yaw, float pitch) {
		BlockState state = level.getBlockState(portal);
		Direction.Axis axis = state.hasProperty(NetherPortalBlock.AXIS) ? state.getValue(NetherPortalBlock.AXIS) : Direction.Axis.X;
		boolean alongX = axis == Direction.Axis.X;
		double centre = alongX ? portal.getZ() + 0.5 : portal.getX() + 0.5;
		double mine = alongX ? at.z : at.x;
		int side = mine >= centre ? 1 : -1;
		for (int attempt = 0; attempt < 2; attempt++, side = -side) {
			BlockPos out = alongX ? BlockPos.containing(at.x, at.y, portal.getZ() + side * 1.5) : BlockPos.containing(portal.getX() + side * 1.5, at.y, at.z);
			if (level.getBlockState(out).isAir() && level.getBlockState(out.above()).isAir()) {
				float facing = alongX ? (side > 0 ? 0 : 180) : (side > 0 ? 270 : 90);
				return new Visit.Home(level.dimension(), out.getX() + 0.5, at.y, out.getZ() + 0.5, facing, pitch);
			}
		}
		return new Visit.Home(level.dimension(), at.x, at.y, at.z, yaw, pitch);
	}

	// --- Exits ---

	/** One way out in the middle, or one in each division when walls would cut the middle off. */
	public static void buildExits(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		Terrain terrain = footprint.terrain();
		List<int[]> spots = new ArrayList<>();
		if (terrain.divided()) {
			for (int cell = 0; cell < terrain.parts(); cell++) spots.add(terrain.partCenter(footprint, cell));
		} else {
			spots.add(new int[] {footprint.minX() + footprint.width() / 2 + 6, footprint.minZ() + footprint.width() / 2});
		}
		for (int[] spot : spots) {
			Vec3 ground = Spawns.near(level, arena, spot[0], spot[1], 6);
			buildExit(level, arena, BlockPos.containing(ground));
		}
		index(arena);
	}

	/** An obsidian frame, two wide and three tall inside, lit, with room to walk up to it from both sides. */
	private static void buildExit(ServerLevel level, Arena arena, BlockPos base) {
		BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
		BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
		for (int dx = -2; dx <= 3; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 0; dy <= 4; dy++) level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				BlockPos ground = base.offset(dx, -1, dz);
				if (!Spawns.solid(level.getBlockState(ground))) level.setBlock(ground, obsidian, Block.UPDATE_CLIENTS);
			}
		}
		for (int dx = -1; dx <= 2; dx++) {
			level.setBlock(base.offset(dx, -1, 0), obsidian, Block.UPDATE_CLIENTS);
			level.setBlock(base.offset(dx, 3, 0), obsidian, Block.UPDATE_CLIENTS);
		}
		for (int dy = 0; dy <= 2; dy++) {
			level.setBlock(base.offset(-1, dy, 0), obsidian, Block.UPDATE_CLIENTS);
			level.setBlock(base.offset(2, dy, 0), obsidian, Block.UPDATE_CLIENTS);
		}
		for (int dx = 0; dx <= 1; dx++) {
			for (int dy = 0; dy <= 2; dy++) {
				BlockPos pos = base.offset(dx, dy, 0);
				level.setBlock(pos, portal, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				arena.exits.add(pos.immutable());
			}
		}
	}
}
