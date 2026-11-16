package justfatlard.pvp_dimensions.gametest;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.world.Divisions;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * The walls that fall are found from the walls' own lines and rays, not by asking every column of
 * the arena: the same columns, in the same order, as asking every column, for every way of
 * dividing an arena at sizes small to the largest.
 */
public final class WallsFromLines implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<Divisions> ways = new ArrayList<>();
			for (int parts = 1; parts <= 12; parts++) {
				ways.add(Divisions.of(parts, false));
				ways.add(Divisions.of(parts, true));
			}
			int[] sizes = {2, 3, 4, 5, 7, 8, 11, 13, 16, 23, 31, Fields.MAX_SIZE};
			for (Divisions way : ways) {
				for (int chunks : sizes) {
					int width = chunks * 16;
					List<int[]> found = way.walls(width);
					int at = 0;
					for (int x = 0; x < width; x++) {
						for (int z = 0; z < width; z++) {
							if (!way.wall(width, x, z)) continue;
							if (at >= found.size() || found.get(at)[0] != x || found.get(at)[1] != z) {
								throw new AssertionError(way + " at " + width + " wide: wall column " + x + "," + z
									+ " is not the next found, " + (at < found.size() ? found.get(at)[0] + "," + found.get(at)[1] : "none"));
							}
							at++;
						}
					}
					if (at != found.size()) throw new AssertionError(way + " at " + width + " wide: " + (found.size() - at) + " columns found that are no wall");
				}
			}
		});
	}
}
