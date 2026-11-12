package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

/**
 * A monster the arena did not spawn itself, as a spawner or an egg makes one: kept in a demo, where it
 * is what the mod being shown does, and still sent away from an arena's fight.
 */
public final class MonstersInDemos implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			// A quiet demo, and one with monsters of its own, a hunt: either way the added one stays.
			for (String demo : java.util.List.of("pvp-dimensions-gametest", justfatlard.pvp_dimensions.PvpDimensions.MOD_ID)) {
				server.runOnServer(s -> Demos.play(connection.getServerPlayer(), demo));
				server.waitFor(s -> {
					Arena arena = Arenas.of(connection.getServerPlayer());
					return arena != null && arena.demo.equals(demo) && arena.member(connection.getServerPlayer().getUUID()) != null
						&& arena.member(connection.getServerPlayer().getUUID()).inside;
				}, 2400);
				Entity inDemo = server.computeOnServer(s -> zombieBeside(connection.getServerPlayer()));
				context.waitTicks(20);
				server.runOnServer(s -> {
					check(!inDemo.isRemoved(), "a monster made in the " + demo + " demo was sent away: " + inDemo.getRemovalReason() + " at " + inDemo.position());
					Travel.goHome(connection.getServerPlayer(), Arenas.of(connection.getServerPlayer()), Travel.Why.COMMAND);
				});
				server.waitFor(s -> Arenas.demos(s).isEmpty(), 400);
			}

			Tables.start(server, connection, Kinds.DEATHMATCH, preset -> preset.pvp = false);
			Entity inFight = server.computeOnServer(s -> zombieBeside(connection.getServerPlayer()));
			context.waitTicks(20);
			server.runOnServer(s -> check(inFight.isRemoved(), "a monster the arena did not spawn stayed in its fight"));
		}
	}

	private static Entity zombieBeside(ServerPlayer player) {
		Entity zombie = EntityTypes.ZOMBIE.create(player.level(), EntitySpawnReason.SPAWNER);
		zombie.setPos(player.position().add(4, 0, 0));
		player.level().addFreshEntity(zombie);
		return zombie;
	}
}
