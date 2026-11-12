package justfatlard.pvp_dimensions.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** One player's time in one copy of a demo, as a {@link DemoHook} sees it. */
public interface DemoRun {
	ServerPlayer player();

	ServerLevel level();

	/**
	 * A spot on the demo's ground, measured the way it was built: blocks east and south of the
	 * ground's north-west corner, at the same height it stood when its terrain was saved. Saved
	 * terrain keeps its heights, so a lever placed at y 70 in the build is at y 70 here.
	 */
	BlockPos at(int east, int y, int south);

	/** How wide the ground is, in blocks, east to west and north to south alike. */
	int width();

	/** Done: the demo is marked played. For a demo that declares a finish, this is the only other way to it than winning its goal. */
	void finish();

	/** Whether this player has finished this demo, on this visit or an earlier one. */
	boolean finished();
}
