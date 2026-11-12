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
import justfatlard.spawn_lock.Gate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A player Spawn Lock holds goes into no demo and no arena, by any door; let go, they go in. And a
 * visit ended by being teleported out, as a jail does, sends them back to where it began when the
 * jail later returns them to the arena spot it took them from.
 */
public final class HeldAtSpawn implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			Vec3 before = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				// An admin may be anywhere on arena ground; the rules here are everybody else's.
				s.getPlayerList().deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
				check(!justfatlard.pvp_dimensions.Access.admin(player), "the test player is still an admin");
				Gate.HELD.add(player.getUUID());
				Demos.play(player, PvpDimensions.MOD_ID);
				check(Arenas.demos(s).isEmpty(), "a held player's demo was made");

				Preset preset = Kinds.DEATHMATCH.make();
				preset.waitingRoom = false;
				Arena arena = Arenas.start(s, player, "held-test", preset,
					new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, false), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					});
				check(arena != null, "no arena");
				return player.position();
			});
			server.waitFor(s -> Tables.arena(s) != null && Tables.arena(s).phase == Arena.Phase.LIVE, 2400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Travel.join(player, Tables.arena(s));
				check(Visit.of(player) == null, "a held player was let into an arena");
				Gate.HELD.remove(player.getUUID());
				Travel.join(player, Tables.arena(s));
			});
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) != null
				&& connection.getServerPlayer().level().dimension().equals(Tables.arena(s).dimension), 200);

			Vec3 spot = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Vec3 at = player.position();
				player.teleportTo(s.overworld(), 0.5, 100, 0.5, Set.of(), 0, 0, false);
				check(Visit.of(player) == null, "being teleported out did not end the visit");
				return at;
			});
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.teleportTo(Arenas.level(s, Tables.arena(s)), spot.x, spot.y, spot.z, Set.of(), 0, 0, false);
			});
			server.waitFor(s -> !connection.getServerPlayer().level().dimension().equals(Tables.arena(s).dimension), 100);
			server.runOnServer(s -> {
				Vec3 now = connection.getServerPlayer().position();
				check(new Vec3(now.x, before.y, now.z).distanceTo(before) < 3,
					"turned away from arena ground, the player was not put back where the visit began: " + now + " not " + before);
			});
		}
	}
}
