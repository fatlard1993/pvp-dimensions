package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.Set;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.ServerConfig;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.onboarding.Progress;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is asked about demos, and how often: a new player once, about all of them; nobody again about
 * demos already asked about; a player who has seen some asked about the one that arrived since; and
 * nobody at all with the offer turned off.
 */
public final class DemosAsk implements FabricClientGameTest {
	private static final String KIND = PvpDimensions.MOD_ID + ":demos";
	private static final String TEST = "pvp-dimensions-gametest";
	/** The asking waits five seconds after a login; this is comfortably past it. */
	private static final int PAST_ASKING = 200;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.waitFor(s -> Progress.of(connection.getServerPlayer()).offered().size() == Demos.all().size(), PAST_ASKING);
			int others = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				int all = PandoricalApi.notices().waiting(player);
				PandoricalApi.notices().withdrawAll(player, KIND);
				int left = PandoricalApi.notices().waiting(player);
				check(all == left + 1, "a new player was not asked, once, in the tray");
				Demos.loggedIn(player);
				return left;
			});
			context.waitTicks(PAST_ASKING);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(PandoricalApi.notices().waiting(player) == others, "a player was asked again about demos already offered");

				player.setAttached(Progress.ATTACHMENT, new Progress(Set.of(PvpDimensions.MOD_ID), Set.of(), Set.of()));
				Demos.loggedIn(player);
			});
			server.waitFor(s -> Progress.of(connection.getServerPlayer()).offered().contains(TEST), PAST_ASKING);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(PandoricalApi.notices().waiting(player) == others + 1, "a demo arriving since was not asked about");
				PandoricalApi.notices().withdrawAll(player, KIND);

				ServerConfig.onboarding(false);
				player.setAttached(Progress.ATTACHMENT, Progress.NONE);
				Demos.loggedIn(player);
			});
			context.waitTicks(PAST_ASKING);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				try {
					check(Progress.of(player).offered().isEmpty() && PandoricalApi.notices().waiting(player) == others,
						"with demos not offered, a player was asked anyway");
				} finally {
					ServerConfig.onboarding(true);
				}
			});
		}
	}
}
