package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.PortalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Exits seen through Pandorical's immersive portals, in a class of its own: it names types a Pandorical before 15.24.1 does not have. */
public final class PortalViews {
	private PortalViews() {}

	public static void register() {
		PandoricalApi.portals().lookThrough(PortalViews::lookThrough);
	}

	/**
	 * What an exit shows a player through Pandorical's immersive portals: the gate they came in by,
	 * out of the side they walked in from, since that side is where leaving puts them back. Null for
	 * anyone who came in another way, whose home has no portal to show, and for gates, which set
	 * players down in the arena rather than at a portal.
	 *
	 * <p>Found from the arena's own list of gate blocks, so the far world is not loaded to answer.
	 */
	public static PortalApi.@Nullable FarEnd lookThrough(ServerPlayer viewer, ServerLevel level, BlockPos pos) {
		String exit = Portals.exitAt(level, pos);
		if (exit == null) return null;
		Arena arena = Arenas.of(viewer);
		Visit visit = Visit.of(viewer);
		if (arena == null || !arena.id.equals(exit) || visit == null) return null;
		Visit.Home home = visit.home();

		List<BlockPos> gate = new ArrayList<>();
		for (GlobalPos block : arena.gates) {
			if (block.dimension().equals(home.dimension()) && block.pos().distToCenterSqr(home.x(), home.y(), home.z()) <= 9.0) gate.add(block.pos());
		}
		if (gate.isEmpty()) return null;
		BlockPos first = gate.getFirst();
		boolean alongX = gate.stream().anyMatch(block -> block.getX() != first.getX());
		Direction face = alongX
			? home.z() > first.getZ() + 0.5 ? Direction.SOUTH : Direction.NORTH
			: home.x() > first.getX() + 0.5 ? Direction.EAST : Direction.WEST;
		return PortalApi.FarEnd.outOf(GlobalPos.of(home.dimension(), first), face);
	}
}
