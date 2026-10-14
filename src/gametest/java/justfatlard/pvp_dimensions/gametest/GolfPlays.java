package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Balls;
import justfatlard.pvp_dimensions.arena.Golf;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Golf, played: the shot is somebody's, a real hit on your own ball is a stroke and rolls until it
 * stops, the shot then comes round again; a crouched hit chips the ball up; and a ball that finds
 * water comes back to where it was hit from, a stroke the worse.
 */
public final class GolfPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.GOLF, preset -> preset.golfHoles = 1);
			server.waitFor(s -> ball(s) != null && Golf.up(arena(s)) != null, 400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(player.getUUID().equals(Golf.up(arena(s))), "the only golfer is not up");
				SulfurCube ball = ball(s);
				player.teleportTo(ball.getX() - 1.5, ball.getY(), ball.getZ());
				player.attack(ball);
				check(arena(s).member(player.getUUID()).points == 1, "a hit was not a stroke");
				check(Golf.rolling(arena(s)), "the shot is not rolling");
				// No second hit while it rolls.
				player.attack(ball);
				check(arena(s).member(player.getUUID()).points == 1, "the ball took a second stroke while it rolled");
			});
			server.waitFor(s -> !Golf.rolling(arena(s)), 400);

			// A chip: crouched, the ball goes up.
			double[] lift = new double[1];
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				SulfurCube ball = ball(s);
				player.teleportTo(ball.getX() - 1.5, ball.getY(), ball.getZ());
				player.setShiftKeyDown(true);
				player.attack(ball);
				player.setShiftKeyDown(false);
				lift[0] = ball.getDeltaMovement().y;
			});
			server.runOnServer(s -> check(lift[0] > 0.3, "a crouched hit did not chip the ball up: " + lift[0]));
			server.waitFor(s -> !Golf.rolling(arena(s)), 400);

			// Into water: back where it was hit from, a stroke the worse.
			Vec3[] from = new Vec3[1];
			int[] before = new int[1];
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				SulfurCube ball = ball(s);
				from[0] = ball.position();
				before[0] = arena(s).member(player.getUUID()).points;
				player.teleportTo(ball.getX() - 1.5, ball.getY(), ball.getZ());
				player.attack(ball);
				ServerLevel level = Arenas.level(s, arena(s));
				BlockPos pond = BlockPos.containing(ball.getX() + 6, ball.getY(), ball.getZ());
				level.setBlockAndUpdate(pond, Blocks.WATER.defaultBlockState());
				ball.setPos(pond.getX() + 0.5, pond.getY(), pond.getZ() + 0.5);
			});
			server.waitFor(s -> !Golf.rolling(arena(s)), 200);
			server.runOnServer(s -> {
				SulfurCube ball = ball(s);
				check(ball.position().distanceTo(from[0]) < 0.6, "the ball in water was not put back: " + ball.position() + " not " + from[0]);
				check(arena(s).member(connection.getServerPlayer().getUUID()).points == before[0] + 2, "water did not cost a stroke");
			});
		}
	}

	private static SulfurCube ball(MinecraftServer server) {
		Arena arena = arena(server);
		ServerLevel level = arena == null ? null : Arenas.level(server, arena);
		if (level == null) return null;
		var footprint = arena.footprint();
		List<SulfurCube> cubes = level.getEntitiesOfClass(SulfurCube.class,
			new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ()),
			cube -> cube.isAlive() && cube.entityTags().contains(Balls.TAG));
		return cubes.isEmpty() ? null : cubes.get(0);
	}
}
