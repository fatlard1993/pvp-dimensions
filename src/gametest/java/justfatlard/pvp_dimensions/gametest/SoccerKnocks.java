package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Balls;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Soccer with more than one ball: as many in play as asked for, and one rolled into another
 * knocking it on. Without the knock a cube passes through another as if it were not there.
 */
public final class SoccerKnocks implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.SOCCER, preset -> preset.soccerBalls = 2);
			server.waitFor(s -> balls(s).size() == 2, 200);

			Vec3[] start = new Vec3[1];
			java.util.UUID[] struck = new java.util.UUID[1];
			server.runOnServer(s -> {
				List<SulfurCube> balls = balls(s);
				SulfurCube a = balls.get(0);
				SulfurCube b = balls.get(1);
				Vec3 at = a.position();
				a.setPos(at.x, at.y, at.z);
				a.setDeltaMovement(Vec3.ZERO);
				b.setPos(at.x + 4, at.y, at.z);
				b.setDeltaMovement(Vec3.ZERO);
				start[0] = b.position();
				struck[0] = b.getUUID();
				a.setDeltaMovement(0.8, 0, 0);
				a.needsSync = true;
			});
			context.waitTicks(40);
			server.runOnServer(s -> {
				SulfurCube b = balls(s).stream().filter(ball -> ball.getUUID().equals(struck[0])).findFirst().orElseThrow();
				double moved = b.getX() - start[0].x;
				check(moved > 1, "a ball rolled into another moved it only " + moved);
			});
		}
	}

	private static List<SulfurCube> balls(MinecraftServer server) {
		Arena arena = arena(server);
		ServerLevel level = arena == null ? null : Arenas.level(server, arena);
		if (level == null) return List.of();
		var footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		return level.getEntitiesOfClass(SulfurCube.class, area, cube -> cube.isAlive() && cube.entityTags().contains(Balls.TAG));
	}
}
