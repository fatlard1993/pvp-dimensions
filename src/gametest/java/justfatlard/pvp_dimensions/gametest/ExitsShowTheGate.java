package justfatlard.pvp_dimensions.gametest;

import justfatlard.pandorical.api.PortalApi;
import justfatlard.pandorical.portal.ImmersivePortals;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Portals;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;

/**
 * With Pandorical's immersive portals on, an arena's exit shows the player who came in by a lit
 * frame that frame, out of the side they walked into it from: the side leaving puts them back on.
 */
public final class ExitsShowTheGate implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			BlockPos spawn = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			BlockPos frame = spawn.offset(0, 0, 4);
			server.runOnServer(s -> {
				buildEmptyFrame(s.overworld(), frame);
				ImmersivePortals.choose(s, true);
				Preset preset = Kinds.DEATHMATCH.make();
				preset.waitingRoom = false;
				preset.exitPortal = true;
				Arena arena = Arenas.start(s, connection.getServerPlayer(), "exits-test", preset,
					new Arenas.Start(Arenas.Invite.NOBODY, List.of(), true, false), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					});
				check(arena != null, "no arena");
			});
			server.waitFor(s -> Tables.arena(s) != null && Tables.arena(s).phase == Arena.Phase.LIVE, 2400);
			server.waitFor(s -> s.overworld().getBlockState(frame).is(Blocks.NETHER_PORTAL), 200);

			// Into the gate from its south side
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.setPortalCooldown(0);
				player.teleportTo(s.overworld(), frame.getX() + 1.0, frame.getY(), frame.getZ() + 0.7, Set.of(), 0.0F, 0.0F, true);
			});
			server.waitFor(s -> connection.getServerPlayer().level().dimension() != Level.OVERWORLD, 400);

			BlockPos exit = server.computeOnServer(s -> Tables.arena(s).exits.getFirst());
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel arenaLevel = (ServerLevel) player.level();
				player.teleportTo(arenaLevel, exit.getX() + 0.5, exit.getY(), exit.getZ() - 4.5, Set.of(), 0.0F, 0.0F, true);
				PortalApi.FarEnd far = Portals.lookThrough(player, arenaLevel, exit);
				check(far != null, "the exit shows nothing to the player who came in by the gate");
				check(far.portal().dimension() == Level.OVERWORLD && far.portal().pos().closerThan(frame, 4),
					"the exit shows " + far.portal() + ", not the gate at " + frame);
				check(far.face() == Direction.SOUTH, "the exit shows the gate's " + far.face() + " side, not the south one they walked in from");
			});

			for (int waited = 0; context.computeOnClient(client -> farBlocksShowing()) == 0; waited += 5) {
				check(waited < 200, "the exit's view never arrived");
				context.waitTicks(5);
			}
		}
	}

	/** An empty obsidian frame, two wide and three tall inside, standing on {@code base}'s row, facing north and south. */
	private static void buildEmptyFrame(ServerLevel level, BlockPos base) {
		for (int x = -1; x <= 2; x++) {
			for (int y = -1; y <= 3; y++) {
				boolean edge = x == -1 || x == 2 || y == -1 || y == 3;
				level.setBlock(base.offset(x, y, 0), edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 18);
			}
		}
	}

	/**
	 * How many far blocks Pandorical is drawing. Its client classes are in the game this test runs
	 * in but not on the classpath this mod compiles against, so it is asked by name.
	 */
	private static int farBlocksShowing() {
		try {
			return (int) Class.forName("justfatlard.pandorical.client.portal.PortalViews").getMethod("showing").invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Pandorical's portal views are not where this test looks for them", e);
		}
	}

	private static void check(boolean ok, String complaint) {
		if (!ok) throw new AssertionError(complaint);
	}
}
