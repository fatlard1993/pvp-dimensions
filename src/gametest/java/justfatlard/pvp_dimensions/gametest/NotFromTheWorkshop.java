package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.List;
import java.util.Set;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Nobody goes into an arena or a demo from Minicraft's workshop, which holds the player's own things
 * aside as an arena does; from the ordinary world they go in. The workshop is a stand-in: a dimension
 * of Minicraft's namespace the test mod's datapack adds, since the world's namespace is all that is asked.
 */
public final class NotFromTheWorkshop implements FabricClientGameTest {
	private static final ResourceKey<Level> WORKSHOP = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath("minicraft-justfatlard", "workshop_standin"));

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runOnServer(s -> {
				Preset preset = Kinds.DEATHMATCH.make();
				preset.waitingRoom = false;
				check(Arenas.start(s, connection.getServerPlayer(), "workshop-test", preset,
					new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, false), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					}) != null, "no arena");
			});
			server.waitFor(s -> Tables.arena(s) != null && Tables.arena(s).phase == Arena.Phase.LIVE, 2400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel workshop = s.getLevel(WORKSHOP);
				check(workshop != null, "the stand-in workshop dimension is not loaded");
				player.teleportTo(workshop, 0.5, workshop.getMinY() + 4, 0.5, Set.of(), 0, 0, true);
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Travel.join(player, Tables.arena(s));
				check(Visit.of(player) == null && player.level().dimension().equals(WORKSHOP), "the player went into an arena from the workshop");
				Demos.play(player, PvpDimensions.MOD_ID);
				check(Arenas.demos(s).isEmpty(), "a demo was made for a player in the workshop");
				player.teleportTo(s.overworld(), 0.5, 100, 0.5, Set.of(), 0, 0, true);
			});
			context.waitTicks(5);
			server.runOnServer(s -> Travel.join(connection.getServerPlayer(), Tables.arena(s)));
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) != null, 100);
		}
	}
}
