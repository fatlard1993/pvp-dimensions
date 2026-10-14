package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Balls;
import justfatlard.pvp_dimensions.arena.HotPotato;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.phys.AABB;

/**
 * Hot potato, played: the potato stuck on somebody as soon as it comes, riding over their head
 * wherever they go, glowing them, never to be kicked, and catching whoever has it when it blows.
 */
public final class HotPotatoPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.HOT_POTATO, preset -> {});
			server.waitFor(s -> HotPotato.holder(arena(s)) != null, 200);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(player.getUUID().equals(HotPotato.holder(arena(s))), "the only player was not given the potato");
				// Walked somewhere else, the potato goes with them.
				player.teleportTo(player.getX() + 5, player.getY(), player.getZ() + 3);
			});
			context.waitTicks(12);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				SulfurCube potato = potato(s);
				check(potato != null, "no potato");
				check(Math.abs(potato.getX() - player.getX()) < 0.01 && Math.abs(potato.getZ() - player.getZ()) < 0.01
					&& potato.getY() > player.getY() + 1.8, "the potato is not over the holder's head: " + potato.position() + " vs " + player.position());
				check(player.hasEffect(MobEffects.GLOWING), "the holder does not glow");
				check(!Balls.mayStrike(potato, player), "the potato can be kicked");
				check(!HotPotato.pass(player, potato), "the potato passed to itself");
			});
			// Left to blow: the holder is caught.
			server.waitFor(s -> connection.getServerPlayer().isSpectator(), 900);
		}
	}

	private static SulfurCube potato(net.minecraft.server.MinecraftServer server) {
		Arena arena = arena(server);
		ServerLevel level = Arenas.level(server, arena);
		var footprint = arena.footprint();
		List<SulfurCube> cubes = level.getEntitiesOfClass(SulfurCube.class,
			new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ()),
			cube -> cube.entityTags().contains(Balls.TAG));
		return cubes.isEmpty() ? null : cubes.get(0);
	}
}
