package justfatlard.pvp_dimensions.gametest;

import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Balls;
import justfatlard.pvp_dimensions.arena.Pool;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A pool table, played in a real arena: racked right, only the white taking a hit and only from
 * whoever's shot it is, a ball rolled into another moving it on, and a ball sunk counted for its
 * side.
 *
 * <p>The knock between balls is the part nothing in the game does for us. A sulfur cube passes
 * through another as if it were not there, so without it the white rolls on through the rack and
 * nothing else on the table ever moves.
 */
public final class PoolPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runOnServer(s -> {
				Preset preset = Kinds.POOL.make();
				preset.waitingRoom = false;
				Arena arena = Arenas.start(s, connection.getServerPlayer(), "pool-test", preset,
					new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, true), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					});
				check(arena != null, "no arena");
			});
			server.waitFor(s -> arena(s) != null && arena(s).phase == Arena.Phase.LIVE, 2400);
			server.waitFor(s -> balls(s).size() == 16, 200);

			server.runOnServer(s -> {
				Arena arena = arena(s);
				List<SulfurCube> balls = balls(s);
				check(balls.stream().filter(b -> b.getItemBySlot(EquipmentSlot.BODY).is(Items.BONE_BLOCK)).count() == 1, "not one white ball");
				check(balls.stream().filter(b -> b.getItemBySlot(EquipmentSlot.BODY).is(Items.COAL_BLOCK)).count() == 1, "not one 8-ball");
				check(balls.stream().filter(b -> holds(b, "red_concrete_powder")).count() == 7, "not seven red balls");
				check(balls.stream().filter(b -> holds(b, "blue_concrete_powder")).count() == 7, "not seven blue balls");
				check(Pool.mayStrike(white(s), connection.getServerPlayer()), "the only player may not strike the white on their own shot");
				check(!Pool.mayStrike(red(s), connection.getServerPlayer()), "a red ball took a hit");
				check(arena.bases.size() == 1, "no table");
			});

			// A red ball rolled at another: it stops, and the other goes on.
			Vec3[] start = new Vec3[1];
			server.runOnServer(s -> {
				BlockPos corner = arena(s).bases.get(0);
				// The table cleared to the white and one red, set in a line.
				SulfurCube target = red(s);
				for (SulfurCube ball : balls(s)) {
					if (ball == target || ball.getItemBySlot(EquipmentSlot.BODY).is(Items.BONE_BLOCK)) continue;
					ball.discard();
				}
				SulfurCube white = white(s);
				white.setPos(corner.getX() + 6.5, corner.getY(), corner.getZ() + 6.0);
				white.setDeltaMovement(Vec3.ZERO);
				target.setPos(corner.getX() + 10.5, corner.getY(), corner.getZ() + 6.0);
				target.setDeltaMovement(Vec3.ZERO);
				start[0] = target.position();
				white.setDeltaMovement(0.6, 0, 0);
				white.needsSync = true;
			});
			// Watched as it rolls: shoved rather than struck, the white ploughs on into the red's place.
			double[] furthest = {Double.NEGATIVE_INFINITY};
			for (int t = 0; t < 60; t++) {
				context.waitTick();
				server.runOnServer(s -> furthest[0] = Math.max(furthest[0], white(s).getX()));
			}
			server.runOnServer(s -> {
				SulfurCube target = red(s);
				check(target != null, "the red ball is gone");
				// Balls of one weight trade speeds: the red runs on down the table and the white all
				// but stops where it struck. Merely shoved aside, the red would hardly move.
				double moved = target.position().x - start[0].x;
				check(moved > 5, "the white rolled into the red and the red moved only " + moved);
				// Less than half a ball: both move within the tick they meet, before it is settled.
				check(furthest[0] < start[0].x + 0.5, "the white went on into the red's place, " + (furthest[0] - start[0].x) + " past where it stood");
			});

			// Rolled at the far cushion, the white comes back off it, and never into it.
			double[] far = {Double.NEGATIVE_INFINITY};
			double[] edge = new double[1];
			server.runOnServer(s -> {
				BlockPos corner = arena(s).bases.get(0);
				edge[0] = corner.getX() + 24;
				SulfurCube white = white(s);
				white.setPos(corner.getX() + 18.5, corner.getY(), corner.getZ() + 6.0);
				white.setDeltaMovement(0.5, 0, 0);
				white.needsSync = true;
			});
			for (int t = 0; t < 30; t++) {
				context.waitTick();
				server.runOnServer(s -> far[0] = Math.max(far[0], white(s).getX() + white(s).getBbWidth() / 2));
			}
			server.runOnServer(s -> {
				check(far[0] <= edge[0] + 0.02, "the white went into the cushion, to " + far[0] + " past " + edge[0]);
				check(white(s).getX() < far[0] - 1.5, "the white stopped at the cushion instead of coming back off it: at " + white(s).getX());
				// Shoved into the rail, it is set back on the cloth.
				white(s).setPos(edge[0] + 0.3, white(s).getY(), white(s).getZ());
			});
			context.waitTicks(2);
			server.runOnServer(s -> check(white(s).getX() + white(s).getBbWidth() / 2 <= edge[0] + 0.02, "a ball in the rail was left there"));

			// A red ball set over a pocket during a shot goes down and counts for red.
			server.runOnServer(s -> {
				Arena arena = arena(s);
				BlockPos corner = arena.bases.get(0);
				SulfurCube red = red(s);
				red.setPos(corner.getX() + 0.9, corner.getY() + 0.2, corner.getZ() + 0.9);
				red.setDeltaMovement(Vec3.ZERO);
				SulfurCube white = white(s);
				white.setDeltaMovement(Vec3.ZERO);
				Pool.struck(white, connection.getServerPlayer());
			});
			server.waitFor(s -> red(s) == null, 100);
			server.waitFor(s -> arena(s).scores.getOrDefault(0, 0) == 1, 600);
			server.runOnServer(s -> check(Pool.mayStrike(white(s), connection.getServerPlayer()),
				"after sinking one of their own, the shooter does not have the shot again"));
		}
	}

	private static Arena arena(MinecraftServer server) {
		List<Arena> running = Arenas.running(server);
		return running.isEmpty() ? null : running.get(0);
	}

	private static List<SulfurCube> balls(MinecraftServer server) {
		Arena arena = arena(server);
		ServerLevel level = arena == null ? null : Arenas.level(server, arena);
		if (level == null) return List.of();
		var footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		return level.getEntitiesOfClass(SulfurCube.class, area, cube -> cube.isAlive() && cube.entityTags().contains(Balls.TAG));
	}

	private static SulfurCube white(MinecraftServer server) {
		for (SulfurCube ball : balls(server)) if (ball.getItemBySlot(EquipmentSlot.BODY).is(Items.BONE_BLOCK)) return ball;
		throw new AssertionError("no white ball");
	}

	private static SulfurCube red(MinecraftServer server) {
		for (SulfurCube ball : balls(server)) if (holds(ball, "red_concrete_powder")) return ball;
		return null;
	}

	private static boolean holds(SulfurCube ball, String block) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ball.getItemBySlot(EquipmentSlot.BODY).getItem()).getPath().equals(block);
	}

	private static void check(boolean ok, String complaint) {
		if (!ok) throw new AssertionError(complaint);
	}
}
