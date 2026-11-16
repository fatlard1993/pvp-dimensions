package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.ServerConfig;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * An arena on ground that grows is surveyed off the server thread: not made in the tick it is
 * asked for, counted against the limit while it waits, never made once cancelled, and made in the
 * end on the very ground the survey run in place would have picked.
 */
public final class ArenaPrepares implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksRender();

			List<String> cancelled = new ArrayList<>();
			String kept = server.computeOnServer(s -> {
				check(Arenas.running(s).isEmpty(), "an arena was running before the test");
				List<Arenas.Pending> asked = new ArrayList<>();
				for (int i = 0; i < ServerConfig.maxArenas(); i++) {
					Arenas.Pending pending = Arenas.start(s, null, "prepare-test", natural(), quiet(), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					});
					check(pending != null, "no arena");
					check(pending.arena() == null, "an arena on growing ground was surveyed in the tick it was asked for");
					check(Arenas.preparing(pending.id) == pending, "an arena being surveyed can't be found by its id");
					asked.add(pending);
				}
				check(Arenas.running(s).isEmpty(), "an arena being surveyed is listed as running");
				String[] refused = {null};
				check(Arenas.start(s, null, "prepare-test", natural(), quiet(), complaint -> refused[0] = complaint) == null
					&& refused[0] != null, "arenas still being surveyed did not count against the limit");
				for (Arenas.Pending pending : asked.subList(1, asked.size())) {
					pending.cancel();
					check(Arenas.preparing(pending.id) == null, "a cancelled arena is still being surveyed");
					cancelled.add(pending.id);
				}
				return asked.get(0).id;
			});

			server.waitFor(s -> Arenas.get(s, kept) != null, 600);
			server.runOnServer(s -> {
				Arena arena = Arenas.get(s, kept);
				Arenas.Survey again = Arenas.survey(s, arena.preset, arena.plot);
				check(again != null, "the arena dimension is missing");
				check(arena.minChunkX == again.minChunkX() && arena.minChunkZ == again.minChunkZ() && arena.chunks == again.chunks(),
					"the arena stands at " + arena.minChunkX + "," + arena.minChunkZ + " x" + arena.chunks
						+ ", not where its plot's survey puts it: " + again);
				check(arena.surfaceY == again.surfaceY() && arena.highestY == again.highestY() && arena.wallTop == again.wallTop()
					&& arena.lobbyY == again.lobbyY(), "the arena's heights are not its survey's: " + again);
				check(Arenas.preparing(kept) == null, "a made arena is still listed as being surveyed");
			});
			context.waitTicks(40);
			server.runOnServer(s -> {
				for (String id : cancelled) check(Arenas.get(s, id) == null, "a cancelled arena was made: " + id);
				check(Arenas.running(s).size() == 1, Arenas.running(s).size() + " arenas running, not the one kept");
			});
			server.waitFor(s -> Arenas.get(s, kept).phase == Arena.Phase.LIVE, 2400);
			server.runOnServer(s -> Arenas.end(s, Arenas.get(s, kept), "the test is over"));
		}
	}

	private static Preset natural() {
		Preset preset = Kinds.DEATHMATCH.make();
		preset.shape = Preset.Shape.NATURAL;
		preset.world = Preset.World.OVERWORLD;
		preset.waitingRoom = false;
		preset.size = 4;
		return preset;
	}

	private static Arenas.Start quiet() {
		return new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, false);
	}
}
